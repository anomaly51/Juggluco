from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Callable

from fastapi.testclient import TestClient
from sqlalchemy import event as sqlalchemy_event

from app.forecast import glucose_source_revision
from app.main import create_app
from app.models import GlucoseReadingRecord
from app.realtime import GlucoseUpdateHub
from app.security import VIEWER_SESSION_COOKIE, issue_viewer_session
from conftest import TEST_TOKEN, make_settings


VIEWER_TOKEN = "stream-viewer-token-that-is-longer-than-thirty-two-characters"


def _stream_scope(headers: list[tuple[bytes, bytes]]) -> dict:
    return {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": "GET",
        "scheme": "https",
        "path": "/v1/viewer/stream",
        "raw_path": b"/v1/viewer/stream",
        "query_string": b"",
        "root_path": "",
        "headers": [(b"host", b"testserver"), *headers],
        "client": ("127.0.0.1", 43123),
        "server": ("testserver", 443),
        "state": {},
    }


async def _exercise_stream(
    application,
    *,
    headers: list[tuple[bytes, bytes]],
    stop_after: bytes,
    on_ready: Callable[[], None] | None = None,
) -> list[dict]:
    sent: list[dict] = []
    request_delivered = False
    stop = asyncio.Event()
    on_ready_holder: list[Callable[[], None] | None] = [on_ready]

    async def receive():
        nonlocal request_delivered
        if not request_delivered:
            request_delivered = True
            return {"type": "http.request", "body": b"", "more_body": False}
        await stop.wait()
        return {"type": "http.disconnect"}

    async def send(message):
        sent.append(message)
        body = message.get("body", b"")
        callback = on_ready_holder[0]
        if b"event: ready" in body and callback is not None:
            on_ready_holder[0] = None
            callback()
        if stop_after in body:
            stop.set()

    async with application.router.lifespan_context(application):
        await asyncio.wait_for(
            application(_stream_scope(headers), receive, send),
            timeout=3,
        )
    return sent


def _body(messages: list[dict]) -> str:
    return b"".join(
        message.get("body", b"")
        for message in messages
        if message["type"] == "http.response.body"
    ).decode("utf-8")


def _event_payload(body: str, event: str) -> dict:
    marker = f"event: {event}\n"
    section = body.split(marker, 1)[1]
    data = next(line[6:] for line in section.splitlines() if line.startswith("data: "))
    return json.loads(data)


def test_hub_coalesces_slow_subscribers_to_the_latest_revision():
    async def exercise():
        hub = GlucoseUpdateHub("test-stream")
        hub.start()
        first = hub.subscribe()
        second = hub.subscribe()
        hub.publish_threadsafe(1, 100)
        hub.publish_threadsafe(2, 200)
        hub.publish_threadsafe(3, 300)
        await asyncio.sleep(0)
        assert (await first.get()).revision == 3
        assert (await second.get()).revision == 3
        assert hub.latest_submitted is not None
        assert hub.latest_submitted.revision == 3
        await hub.close()

    asyncio.run(exercise())


def test_hub_never_regresses_when_threadsafe_callbacks_arrive_reversed():
    async def exercise():
        hub = GlucoseUpdateHub("test-stream")
        hub.start()
        queue = hub.subscribe()
        scheduled: list[tuple[Callable, tuple]] = []

        class DeferredLoop:
            def call_soon_threadsafe(self, callback, *args):
                scheduled.append((callback, args))

        hub._loop = DeferredLoop()  # type: ignore[assignment]
        hub.publish_threadsafe(1, 100)
        hub.publish_threadsafe(2, 200)
        assert [args[0].revision for _, args in scheduled] == [1, 2]

        for callback, args in reversed(scheduled):
            callback(*args)

        assert (await queue.get()).revision == 2
        await hub.close()

    asyncio.run(exercise())


def test_private_stream_auth_headers_and_minimized_glucose_event(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    application = create_app(
        make_settings(tmp_path, viewer_token=VIEWER_TOKEN),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    with TestClient(application, base_url="https://testserver") as client:
        assert client.get("/v1/viewer/stream").status_code == 401

    def publish() -> None:
        application.state.glucose_updates.publish_threadsafe(1, 1_787_212_500_000)

    messages = asyncio.run(
        _exercise_stream(
            application,
            headers=[(b"authorization", f"Bearer {VIEWER_TOKEN}".encode())],
            stop_after=b"event: glucose",
            on_ready=publish,
        )
    )
    start = next(message for message in messages if message["type"] == "http.response.start")
    response_headers = dict(start["headers"])
    assert start["status"] == 200
    assert response_headers[b"content-type"].startswith(b"text/event-stream")
    assert response_headers[b"x-accel-buffering"] == b"no"
    assert b"no-transform" in response_headers[b"cache-control"]

    body = _body(messages)
    assert "event: ready" in body
    assert "event: glucose" in body
    payload = _event_payload(body, "glucose")
    assert payload == {
        "latest_reading_at_ms": 1_787_212_500_000,
        "revision": 1,
        "server_time_ms": payload["server_time_ms"],
        "stream_id": application.state.glucose_updates.stream_id,
    }
    assert "reading_id" not in body
    assert "sensor_id" not in body
    assert "glucose_mg_dl" not in body


def test_public_stream_is_allowlisted_and_sends_observable_heartbeats(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
    monkeypatch,
):
    from app import viewer

    monkeypatch.setattr(viewer, "STREAM_HEARTBEAT_SECONDS", 0.01)
    application = create_app(
        make_settings(tmp_path, viewer_token=None, viewer_public=True),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    messages = asyncio.run(
        _exercise_stream(
            application,
            headers=[],
            stop_after=b"event: heartbeat",
        )
    )
    body = _body(messages)
    heartbeat = _event_payload(body, "heartbeat")
    assert heartbeat["stream_id"] == application.state.glucose_updates.stream_id
    assert heartbeat["revision"] == 0
    assert heartbeat["server_time_ms"] > 0


def test_ingestion_broadcasts_only_after_a_durable_mutation(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    application = create_app(
        make_settings(tmp_path, viewer_token=VIEWER_TOKEN),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    observed: list[tuple[int, int | None]] = []

    def observe(revision: int, latest_at: int | None) -> None:
        # A separate connection can observe both values only after commit.
        with application.state.database.session_factory() as verification:
            assert glucose_source_revision(verification) == revision
            assert verification.get(GlucoseReadingRecord, f"cgm-{latest_at}") is not None
        observed.append((revision, latest_at))
        application.state.glucose_updates.publish_threadsafe(revision, latest_at)

    application.state.forecast_service.set_glucose_commit_listener(observe)
    now = int(time.time() * 1_000)
    reading = {
        "reading_id": f"cgm-{now}",
        "measured_at_ms": now,
        "glucose_mg_dl": 112,
        "trend_mg_dl_min": 0.4,
    }
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}
    with TestClient(application, base_url="https://testserver") as client:
        initial = client.get(
            "/v1/viewer/snapshot",
            headers={"Authorization": f"Bearer {VIEWER_TOKEN}"},
        ).json()
        assert initial["glucose_revision"] == 0
        assert initial["stream_id"] == application.state.glucose_updates.stream_id

        created = client.post(
            "/v1/glucose/readings",
            headers=headers,
            json={"readings": [reading]},
        )
        assert created.status_code == 200, created.text
        assert observed == [(1, now)]

        duplicate = client.post(
            "/v1/glucose/readings",
            headers=headers,
            json={"readings": [reading]},
        )
        assert duplicate.status_code == 200, duplicate.text
        assert observed == [(1, now)]

        corrected = client.post(
            "/v1/glucose/readings",
            headers=headers,
            json={"readings": [{**reading, "glucose_mg_dl": 118}]},
        )
        assert corrected.status_code == 200, corrected.text
        assert observed == [(1, now), (2, now)]

        snapshot = client.get(
            "/v1/viewer/snapshot",
            headers={"Authorization": f"Bearer {VIEWER_TOKEN}"},
        ).json()
        assert snapshot["glucose_revision"] == 2
        assert snapshot["current_glucose"]["glucose_mg_dl"] == 118


def test_snapshot_reads_its_revision_before_any_glucose_row(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    application = create_app(
        make_settings(tmp_path, viewer_token=VIEWER_TOKEN),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    statements: list[str] = []

    def record_statement(_connection, _cursor, statement, _parameters, _context, _many):
        statements.append(statement.casefold())

    sqlalchemy_event.listen(
        application.state.database.engine,
        "before_cursor_execute",
        record_statement,
    )
    try:
        with TestClient(application, base_url="https://testserver") as client:
            response = client.get(
                "/v1/viewer/snapshot",
                headers={"Authorization": f"Bearer {VIEWER_TOKEN}"},
            )
            assert response.status_code == 200, response.text
    finally:
        sqlalchemy_event.remove(
            application.state.database.engine,
            "before_cursor_execute",
            record_statement,
        )

    revision_query = next(
        index
        for index, statement in enumerate(statements)
        if "cast(backend_metadata.value_text as integer)" in statement
    )
    first_glucose_query = next(
        index
        for index, statement in enumerate(statements)
        if "from glucose_readings" in statement
    )
    assert revision_query < first_glucose_query


def test_session_stream_closes_when_its_signed_cookie_expires(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    settings = make_settings(tmp_path, viewer_token=VIEWER_TOKEN)
    application = create_app(
        settings,
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    encoded, _ = issue_viewer_session(
        settings,
        now_seconds=int(time.time()) - settings.viewer_session_days * 86_400 + 2,
    )

    async def exercise() -> list[dict]:
        sent: list[dict] = []
        request_delivered = False

        async def receive():
            nonlocal request_delivered
            if not request_delivered:
                request_delivered = True
                return {"type": "http.request", "body": b"", "more_body": False}
            await asyncio.Future()

        async def send(message):
            sent.append(message)

        async with application.router.lifespan_context(application):
            await asyncio.wait_for(
                application(
                    _stream_scope(
                        [
                            (
                                b"cookie",
                                f"{VIEWER_SESSION_COOKIE}={encoded}".encode(),
                            )
                        ]
                    ),
                    receive,
                    send,
                ),
                timeout=3,
            )
        return sent

    started = time.monotonic()
    messages = asyncio.run(exercise())
    assert time.monotonic() - started < 3
    assert "event: ready" in _body(messages)
    assert messages[-1]["type"] == "http.response.body"
    assert messages[-1].get("more_body") is False
