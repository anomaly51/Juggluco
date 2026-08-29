from __future__ import annotations

import asyncio
import threading
import time
from dataclasses import dataclass
from uuid import uuid4


@dataclass(frozen=True, slots=True)
class GlucoseUpdate:
    """A privacy-minimized notification emitted after a durable CGM commit."""

    revision: int
    latest_reading_at_ms: int | None
    committed_at_ms: int


class GlucoseUpdateHub:
    """Process-local, latest-wins fan-out for the single-replica SQLite service.

    Producers run in FastAPI's worker threads while subscribers live on the
    application event loop. Each subscriber has one bounded slot: a slow or
    suspended browser receives the newest durable revision instead of building
    an unbounded medical-data backlog.
    """

    def __init__(self, stream_id: str | None = None) -> None:
        self.stream_id = stream_id or str(uuid4())
        self._loop: asyncio.AbstractEventLoop | None = None
        self._subscribers: set[asyncio.Queue[GlucoseUpdate | None]] = set()
        self._state_lock = threading.Lock()
        self._latest_submitted: GlucoseUpdate | None = None
        self._closed = True

    def start(self) -> None:
        """Bind thread-safe publications to the current application loop."""

        self._loop = asyncio.get_running_loop()
        self._closed = False

    async def close(self) -> None:
        """Release all open streams during application shutdown."""

        self._closed = True
        subscribers = tuple(self._subscribers)
        self._subscribers.clear()
        for queue in subscribers:
            self._replace_queued(queue, None)
        self._loop = None

    @property
    def latest_submitted(self) -> GlucoseUpdate | None:
        """Return the newest scheduled commit for diagnostics and tests."""

        with self._state_lock:
            return self._latest_submitted

    def subscribe(self) -> asyncio.Queue[GlucoseUpdate | None]:
        if self._closed or self._loop is not asyncio.get_running_loop():
            raise RuntimeError("glucose update hub is not running")
        queue: asyncio.Queue[GlucoseUpdate | None] = asyncio.Queue(maxsize=1)
        self._subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue[GlucoseUpdate | None]) -> None:
        self._subscribers.discard(queue)

    def publish_threadsafe(
        self,
        revision: int,
        latest_reading_at_ms: int | None,
    ) -> None:
        """Schedule one post-commit invalidation from a request worker thread."""

        update = GlucoseUpdate(
            revision=int(revision),
            latest_reading_at_ms=(
                int(latest_reading_at_ms)
                if latest_reading_at_ms is not None
                else None
            ),
            committed_at_ms=int(time.time() * 1_000),
        )
        with self._state_lock:
            previous = self._latest_submitted
            if previous is not None and update.revision <= previous.revision:
                return
            self._latest_submitted = update
            loop = self._loop
        if loop is None or self._closed:
            return
        try:
            loop.call_soon_threadsafe(self._deliver, update)
        except RuntimeError:
            # The lifespan may have closed the loop immediately after the
            # durable commit. The next process reconstructs state from SQLite.
            return

    def _deliver(self, update: GlucoseUpdate) -> None:
        if self._closed:
            return
        # ``call_soon_threadsafe`` calls from different request threads can be
        # registered in a different order than the commits were submitted. Use
        # the monotonic value protected by ``_state_lock`` so a delayed callback
        # can never replace a newer revision in a subscriber's one-slot queue.
        with self._state_lock:
            latest = self._latest_submitted
        if latest is not None and latest.revision > update.revision:
            update = latest
        for queue in tuple(self._subscribers):
            self._replace_queued(queue, update)

    @staticmethod
    def _replace_queued(
        queue: asyncio.Queue[GlucoseUpdate | None],
        update: GlucoseUpdate | None,
    ) -> None:
        if queue.full():
            try:
                queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
        try:
            queue.put_nowait(update)
        except asyncio.QueueFull:
            # Only another delivery on the same event loop can fill the slot.
            # That newer value is already the better notification to retain.
            pass
