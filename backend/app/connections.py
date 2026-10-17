"""Installation-scoped storage and revocable, read-only viewer capabilities.

The legacy API remains untouched. New installations never use its database,
credential, forecast artifacts, stream hub, or browser session. Only an explicit
legacy-owner claim can bind that existing database to a personal connection.
"""
from __future__ import annotations

import asyncio
import hashlib
import re
import secrets
import sqlite3
import time
from contextlib import asynccontextmanager, closing
from dataclasses import dataclass, replace
from pathlib import Path
from uuid import UUID

from fastapi import HTTPException, Request
from starlette.responses import JSONResponse, Response
from starlette.middleware import Middleware

HANDLE = re.compile(r"^(?:[a-z0-9][a-z0-9-]{0,23}-)?[A-Za-z0-9_-]{22}$")
SECRET = re.compile(r"^[A-Za-z0-9_-]{43,128}$")
LABEL = re.compile(r"^[a-z0-9][a-z0-9-]{0,23}$")
READ_ROUTES = {"snapshot", "glucose", "stream", "session"}


def digest(secret: str) -> str:
    return hashlib.sha256(secret.encode("ascii")).hexdigest()


class ConnectionRegistry:
    def __init__(self, path: Path, daily_limit: int = 100):
        self.path = path
        self.daily_limit = daily_limit

    def connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("""CREATE TABLE IF NOT EXISTS installations (
            id TEXT PRIMARY KEY, owner_hash TEXT NOT NULL, handle TEXT UNIQUE NOT NULL,
            created_at INTEGER NOT NULL, legacy INTEGER NOT NULL DEFAULT 0,
            rotation_id TEXT, ai_day INTEGER NOT NULL DEFAULT 0,
            ai_requests INTEGER NOT NULL DEFAULT 0)""")
        connection.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_legacy_owner "
                           "ON installations(legacy) WHERE legacy = 1")
        return connection

    def get(self, identifier: str, *, by_handle=False):
        with closing(self.connect()) as connection:
            return connection.execute(
                f"SELECT * FROM installations WHERE {'handle' if by_handle else 'id'} = ?",
                (identifier,),
            ).fetchone()

    def authenticate(self, identifier: str, secret: str):
        row = self.get(identifier)
        if not row or not secrets.compare_digest(row["owner_hash"], digest(secret)):
            raise HTTPException(401, "invalid installation credential")
        return row

    def register(self, identifier: str, secret: str, *, legacy=False):
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT * FROM installations WHERE id = ?", (identifier,)).fetchone()
            if row:
                if not secrets.compare_digest(row["owner_hash"], digest(secret)):
                    raise HTTPException(409, "installation already exists")
                # Idempotent retry must not silently change ownership or storage.
                if bool(row["legacy"]) != legacy:
                    raise HTTPException(409, "installation storage already assigned")
                return row
            if legacy and connection.execute("SELECT 1 FROM installations WHERE legacy = 1").fetchone():
                raise HTTPException(409, "existing data already has an owner")
            since = int(time.time()) - 86400
            count = connection.execute("SELECT COUNT(*) FROM installations WHERE created_at >= ?", (since,)).fetchone()[0]
            if count >= self.daily_limit:
                raise HTTPException(429, "registration capacity reached; try later")
            connection.execute(
                "INSERT INTO installations(id, owner_hash, handle, created_at, legacy) VALUES (?, ?, ?, ?, ?)",
                (identifier, digest(secret), secrets.token_urlsafe(16), int(time.time()), int(legacy)),
            )
            return connection.execute("SELECT * FROM installations WHERE id = ?", (identifier,)).fetchone()

    def rotate(self, identifier: str, secret: str, operation: str, label: str):
        with closing(self.connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT * FROM installations WHERE id = ?", (identifier,)).fetchone()
            if not row or not secrets.compare_digest(row["owner_hash"], digest(secret)):
                raise HTTPException(401, "invalid installation credential")
            if row["rotation_id"] != operation:
                handle = (label + "-" if label else "") + secrets.token_urlsafe(16)
                connection.execute("UPDATE installations SET handle = ?, rotation_id = ? WHERE id = ?",
                                   (handle, operation, identifier))
            return connection.execute("SELECT * FROM installations WHERE id = ?", (identifier,)).fetchone()

    def allow_ai(self, identifier: str, limit: int):
        day = int(time.time()) // 86400
        with closing(self.connect()) as connection, connection:
            cursor = connection.execute("""UPDATE installations SET ai_day = ?,
                ai_requests = CASE WHEN ai_day = ? THEN ai_requests + 1 ELSE 1 END
                WHERE id = ? AND (ai_day != ? OR ai_requests < ?)""", (day, day, identifier, day, limit))
            if cursor.rowcount != 1:
                raise HTTPException(429, "daily AI request limit reached")


@dataclass
class Runtime:
    app: object
    lifespan: object
    active: int = 0
    last_used: float = 0


class Connections:
    def __init__(self, root, factory, settings):
        self.root, self.factory, self.settings = root, factory, settings
        # Sibling of the existing DB: automatically covered by the same /data PVC.
        self.directory = settings.database_path.parent / (settings.database_path.stem + "-installations")
        self.registry = ConnectionRegistry(self.directory / "registry.sqlite3", settings.connection_registration_limit)
        self.runtimes: dict[str, Runtime] = {}
        self.lock = asyncio.Lock()

    @asynccontextmanager
    async def lease(self, row):
        if row["legacy"]:
            yield self.root
            return
        identifier = row["id"]  # Canonical UUID from registry, never an arbitrary path.
        async with self.lock:
            runtime = self.runtimes.get(identifier)
            if runtime is None:
                if len(self.runtimes) >= self.settings.connection_runtime_limit:
                    idle = [(key, value) for key, value in self.runtimes.items() if not value.active]
                    if not idle:
                        raise HTTPException(503, "connection capacity busy; retry shortly")
                    key, evicted = min(idle, key=lambda item: item[1].last_used)
                    await evicted.lifespan.__aexit__(None, None, None)
                    del self.runtimes[key]
                child_settings = replace(self.settings,
                    database_path=self.directory / identifier / "health.sqlite3",
                    api_token=secrets.token_urlsafe(32), viewer_token=None,
                    viewer_public=True, personal_connections=False)
                app = self.factory(settings=child_settings)
                lifespan = app.router.lifespan_context(app)
                await lifespan.__aenter__()
                runtime = self.runtimes[identifier] = Runtime(app, lifespan)
            runtime.active += 1
        try:
            yield runtime.app
        finally:
            async with self.lock:
                runtime.active -= 1
                runtime.last_used = time.monotonic()

    async def close(self):
        for runtime in self.runtimes.values():
            await runtime.lifespan.__aexit__(None, None, None)
        self.runtimes.clear()

    @staticmethod
    def describe(row):
        # Return relative paths, not Host/X-Forwarded-Host-derived credentials.
        return {"installation_id": row["id"], "viewer_path": "/viewer/?profile=" + row["handle"],
                "api_path": "/devices/" + row["id"], "legacy": bool(row["legacy"])}


class ConnectionMiddleware:
    def __init__(self, app, manager: Connections):
        self.app, self.manager = app, manager

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("connection_dispatched"):
            return await self.app(scope, receive, send)
        path = scope["path"]
        if not (path.startswith("/devices/") or path.startswith("/v1/shares/")
                or path.startswith("/v1/installations") or path == "/viewer/personal.webmanifest"):
            return await self.app(scope, receive, send)
        try:
            await self.dispatch(scope, receive, send)
        except HTTPException as error:
            await JSONResponse({"detail": error.detail}, status_code=error.status_code,
                               headers={"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"})(scope, receive, send)

    @staticmethod
    def secret(request):
        value = request.headers.get("authorization", "")
        scheme, separator, supplied = value.partition(" ")
        if scheme.lower() != "bearer" or not separator or not SECRET.fullmatch(supplied):
            raise HTTPException(401, "invalid installation credential")
        return supplied

    @staticmethod
    async def body(request):
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > 2048:
                raise HTTPException(413, "connection request too large")
        import json
        try:
            result = json.loads(data)
            if not isinstance(result, dict):
                raise ValueError()
            return result
        except (ValueError, UnicodeError):
            raise HTTPException(422, "invalid connection request") from None

    async def dispatch(self, scope, receive, send):
        from .main import _is_secure_browser_request
        request = Request(scope, receive)
        # Never send installation credentials over public cleartext HTTP.
        if not _is_secure_browser_request(request):
            raise HTTPException(426, "personal connections require HTTPS")
        registry, path = self.manager.registry, scope["path"]
        if path == "/viewer/personal.webmanifest":
            handle = request.query_params.get("profile", "")
            if not HANDLE.fullmatch(handle) or not registry.get(handle, by_handle=True):
                raise HTTPException(404, "viewer link unavailable")
            start = "/viewer/?profile=" + handle
            response = JSONResponse({"id": start, "start_url": start, "scope": "/viewer/",
                "name": "Juggluco", "short_name": "Juggluco", "display": "standalone",
                "background_color": "#060301", "theme_color": "#060301",
                "icons": [{"src": "/viewer/icons/icon-192.png", "sizes": "192x192", "type": "image/png"},
                          {"src": "/viewer/icons/icon-512.png", "sizes": "512x512", "type": "image/png"}]},
                media_type="application/manifest+json", headers={"Cache-Control": "no-store"})
            return await response(scope, receive, send)
        if path == "/v1/installations" or path == "/v1/installations/claim":
            if request.method != "POST":
                raise HTTPException(405, "POST required")
            secret = self.secret(request)
            body = await self.body(request)
            try:
                identifier = str(UUID(body["installation_id"]))
                if identifier != body["installation_id"]:
                    raise ValueError()
            except (ValueError, KeyError, TypeError, AttributeError):
                raise HTTPException(422, "invalid installation ID") from None
            legacy = path.endswith("/claim")
            if legacy:
                supplied = request.headers.get("x-legacy-token", "")
                expected = self.manager.settings.api_token or ""
                if not expected or not secrets.compare_digest(supplied.encode(), expected.encode()):
                    raise HTTPException(401, "existing connection credential required")
            row = registry.register(identifier, secret, legacy=legacy)
            # Only report ready once schema and independent forecast runtime exist.
            async with self.manager.lease(row):
                pass
            return await JSONResponse(self.manager.describe(row), headers={"Cache-Control": "no-store"})(scope, receive, send)
        share = path.startswith("/v1/shares/")
        parts = path.split("/")
        if share:
            if len(parts) != 5 or parts[4] not in READ_ROUTES or request.method not in {"GET", "HEAD", "DELETE"}:
                raise HTTPException(404, "viewer route unavailable")
            handle, endpoint = parts[3:]
            row = registry.get(handle, by_handle=True) if HANDLE.fullmatch(handle) else None
            if row is None:
                raise HTTPException(401, "viewer link expired or unavailable")
            if endpoint == "session":
                response = (Response(status_code=204) if request.method == "DELETE" else
                            JSONResponse({"authenticated": True, "access_mode": "public", "expires_at_ms": None}))
                response.headers["Cache-Control"] = "no-store"
                return await response(scope, receive, send)
            if request.method == "DELETE":
                raise HTTPException(405, "read-only viewer")
            target = "/v1/viewer/" + endpoint
        else:
            if len(parts) < 4:
                raise HTTPException(404, "connection route unavailable")
            secret = self.secret(request)
            row = registry.authenticate(parts[2], secret)
            target = "/" + "/".join(parts[3:])
            if target == "/connection":
                if request.method == "POST":
                    body = await self.body(request)
                    try:
                        operation = str(UUID(body["operation_id"]))
                        label = body.get("label", "")
                        if not isinstance(label, str) or (label and not LABEL.fullmatch(label)):
                            raise ValueError()
                    except (ValueError, KeyError, TypeError, AttributeError):
                        raise HTTPException(422, "use a short lowercase URL label") from None
                    row = registry.rotate(row["id"], secret, operation, label)
                elif request.method != "GET":
                    raise HTTPException(405, "GET or POST required")
                return await JSONResponse(self.manager.describe(row), headers={"Cache-Control": "no-store"})(scope, receive, send)
            if not target.startswith("/v1/") or target.startswith("/v1/installations"):
                raise HTTPException(404, "connection route unavailable")
            if request.method == "POST" and any(term in target for term in ("analyses", "transcriptions", "chat")):
                registry.allow_ai(row["id"], self.manager.settings.connection_ai_daily_limit)
        async with self.manager.lease(row) as app:
            forwarded = dict(scope, path=target, raw_path=target.encode(), root_path="",
                             connection_dispatched=True)
            forwarded["headers"] = [(k, v) for k, v in scope["headers"]
                                    if k.lower() not in {b"authorization", b"cookie", b"x-legacy-token"}]
            # Internal credential never leaves this process. Public route allowlist
            # is checked above before it can reach any mutation handler.
            forwarded["headers"].append((b"authorization", ("Bearer " + app.state.settings.api_token).encode()))
            forwarded["state"] = dict(scope.get("state", {}))
            if share:
                forwarded["state"]["viewer_link_valid"] = lambda: registry.get(handle, by_handle=True) is not None
            # Legacy requests use the root's inner middleware to avoid redispatch.
            await app(forwarded, receive, send)


def install_connections(application, settings, factory):
    if not settings.personal_connections:
        return
    manager = Connections(application, factory, settings)
    application.state.connections = manager
    previous = application.router.lifespan_context

    @asynccontextmanager
    async def lifespan(app):
        async with previous(app):
            try:
                yield
            finally:
                await manager.close()

    application.router.lifespan_context = lifespan
    # Inside the existing trusted-host/security middleware, including provisioning.
    application.user_middleware.append(Middleware(ConnectionMiddleware, manager=manager))
