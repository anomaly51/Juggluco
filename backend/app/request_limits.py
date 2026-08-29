from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from starlette.responses import JSONResponse
from starlette.types import Message, Receive, Scope, Send


VIEWER_SESSION_MAX_BODY_BYTES = 2_048


class _RequestBodyTooLarge(Exception):
    pass


class ViewerSessionBodyLimitMiddleware:
    """Bound the unauthenticated session-exchange body, including chunked bodies."""

    def __init__(
        self,
        app: Callable[..., Awaitable[Any]],
        max_bytes: int = VIEWER_SESSION_MAX_BODY_BYTES,
    ):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if (
            scope["type"] != "http"
            or scope.get("method") != "POST"
            or scope.get("path") != "/v1/viewer/session"
        ):
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        raw_length = headers.get(b"content-length")
        if raw_length is not None:
            try:
                declared_length = int(raw_length)
            except ValueError:
                await self._reject(scope, receive, send)
                return
            if declared_length < 0 or declared_length > self.max_bytes:
                await self._reject(scope, receive, send)
                return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    raise _RequestBodyTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except _RequestBodyTooLarge:
            await self._reject(scope, receive, send)

    @staticmethod
    async def _reject(scope: Scope, receive: Receive, send: Send) -> None:
        response = JSONResponse(
            status_code=413,
            content={"detail": "viewer session request is too large"},
        )
        await response(scope, receive, send)
