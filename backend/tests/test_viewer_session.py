from __future__ import annotations

import asyncio
import time

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.request_limits import ViewerSessionBodyLimitMiddleware
from app.security import VIEWER_SESSION_COOKIE, issue_viewer_session
from conftest import TEST_TOKEN, make_settings


VIEWER_TOKEN = "pwa-viewer-token-that-is-longer-than-thirty-two-characters"
SECOND_VIEWER_TOKEN = "rotated-pwa-viewer-token-that-is-also-long-enough"


@pytest.fixture
def pwa_app(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    return create_app(
        make_settings(tmp_path, viewer_token=VIEWER_TOKEN),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )


@pytest.fixture
def pwa_client(pwa_app):
    with TestClient(pwa_app, base_url="https://testserver") as client:
        yield client


def _login(client: TestClient, token: str = VIEWER_TOKEN):
    return client.post(
        "/v1/viewer/session",
        json={"token": token},
        headers={"Origin": "https://testserver"},
    )


def test_browser_session_is_http_only_persistent_and_read_only(pwa_client):
    response = _login(pwa_client)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["authenticated"] is True
    assert body["access_mode"] == "session"
    assert body["expires_at_ms"] > int(time.time() * 1_000)
    assert VIEWER_TOKEN not in response.text

    cookie = response.headers["set-cookie"]
    assert cookie.startswith(f"{VIEWER_SESSION_COOKIE}=")
    assert "HttpOnly" in cookie
    assert "Secure" in cookie
    assert "SameSite=strict" in cookie
    assert "Path=/" in cookie
    assert "Max-Age=" in cookie

    snapshot = pwa_client.get("/v1/viewer/snapshot")
    assert snapshot.status_code == 200, snapshot.text
    assert snapshot.headers["cache-control"] == "no-store, private"
    assert snapshot.headers["vary"] == "Authorization, Cookie"

    denied = pwa_client.post("/v1/glucose/readings", json={"readings": []})
    assert denied.status_code == 401

    refreshed = pwa_client.get("/v1/viewer/session")
    assert refreshed.status_code == 200
    assert refreshed.json()["access_mode"] == "session"
    assert refreshed.json()["expires_at_ms"] >= body["expires_at_ms"]
    assert "HttpOnly" in refreshed.headers["set-cookie"]

    logout = pwa_client.delete(
        "/v1/viewer/session",
        headers={"Origin": "https://testserver"},
    )
    assert logout.status_code == 204
    assert "Max-Age=0" in logout.headers["set-cookie"]
    assert pwa_client.get("/v1/viewer/snapshot").status_code == 401


def test_public_viewer_session_is_anonymous_and_never_sets_a_login_cookie(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    application = create_app(
        make_settings(tmp_path, viewer_token=None, viewer_public=True),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    with TestClient(application, base_url="https://testserver") as client:
        session = client.get("/v1/viewer/session")
        assert session.status_code == 200, session.text
        assert session.json() == {
            "authenticated": True,
            "access_mode": "public",
            "expires_at_ms": None,
        }
        assert "set-cookie" not in session.headers

        for path in (
            "/v1/viewer/snapshot",
            "/v1/viewer/glucose",
        ):
            response = client.get(path)
            assert response.status_code == 200, response.text
            assert response.headers["cache-control"] == "no-store, private"
            assert response.headers["x-robots-tag"] == "noindex, nofollow, noarchive"
        assert client.get("/v1/viewer/intakes").status_code == 403

        sensitive = "not-needed-public-token-that-must-not-be-reflected"
        login = client.post(
            "/v1/viewer/session",
            json={"token": sensitive},
            headers={"Origin": "https://testserver"},
        )
        assert login.status_code == 409
        assert login.json() == {
            "detail": "viewer public access is enabled; no session is required"
        }
        assert sensitive not in login.text
        assert "set-cookie" not in login.headers

        cross_origin = client.post(
            "/v1/viewer/session",
            json={"token": sensitive},
            headers={"Origin": "https://attacker.invalid"},
        )
        assert cross_origin.status_code == 403
        assert "set-cookie" not in cross_origin.headers

        client.cookies.set(VIEWER_SESSION_COOKIE, "stale-cookie")
        public_status = client.get("/v1/viewer/session")
        assert public_status.status_code == 200
        assert "Max-Age=0" in public_status.headers["set-cookie"]

        client.cookies.set(VIEWER_SESSION_COOKIE, "stale-cookie")
        logout = client.delete(
            "/v1/viewer/session",
            headers={"Origin": "https://testserver"},
        )
        assert logout.status_code == 204
        assert "Max-Age=0" in logout.headers["set-cookie"]
        assert client.get("/v1/viewer/snapshot").status_code == 200


def test_public_viewer_is_explicit_and_fails_closed_by_default(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    private_settings = make_settings(tmp_path, viewer_token=None)
    assert private_settings.viewer_public is False
    application = create_app(
        private_settings,
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    with TestClient(application, base_url="https://testserver") as client:
        assert client.get("/v1/viewer/session").status_code == 401
        for path in (
            "/v1/viewer/snapshot",
            "/v1/viewer/glucose",
            "/v1/viewer/intakes",
        ):
            assert client.get(path).status_code == 401


def test_public_viewer_env_flag_is_strict(monkeypatch):
    monkeypatch.setenv("JUGGLUCO_VIEWER_PUBLIC", "yes")
    with pytest.raises(ValueError, match="JUGGLUCO_VIEWER_PUBLIC must be true or false"):
        Settings.from_env()

    monkeypatch.setenv("JUGGLUCO_VIEWER_PUBLIC", "TRUE")
    assert Settings.from_env().viewer_public is True


def test_session_login_rejects_wrong_admin_and_cross_origin_tokens(pwa_client):
    for token in ("x" * 40, TEST_TOKEN):
        response = _login(pwa_client, token)
        assert response.status_code == 401
        assert token not in response.text
        assert "set-cookie" not in response.headers

    cross_origin = pwa_client.post(
        "/v1/viewer/session",
        json={"token": VIEWER_TOKEN},
        headers={"Origin": "https://attacker.invalid"},
    )
    assert cross_origin.status_code == 403
    assert "set-cookie" not in cross_origin.headers

    malformed_origin = pwa_client.post(
        "/v1/viewer/session",
        json={"token": VIEWER_TOKEN},
        headers={"Origin": "http://["},
    )
    assert malformed_origin.status_code == 403


def test_session_login_never_reflects_credentials(pwa_client):
    credentials = ("SENSITIVE-TOKEN", "s" * 513, "ю" * 32)
    for credential in credentials:
        response = _login(pwa_client, credential)
        assert response.status_code == 401
        assert credential not in response.text
        assert "set-cookie" not in response.headers

    invalid_payloads = (
        {"token": [VIEWER_TOKEN]},
        {"token": VIEWER_TOKEN, "private_note": VIEWER_TOKEN},
    )
    for payload in invalid_payloads:
        response = pwa_client.post("/v1/viewer/session", json=payload)
        assert response.status_code == 422
        assert response.json() == {"detail": "invalid viewer session request"}
        assert VIEWER_TOKEN not in response.text
        assert response.headers["cache-control"] == "no-store, private"

    wrong_content_type = pwa_client.post(
        "/v1/viewer/session",
        content=VIEWER_TOKEN,
        headers={"Content-Type": "text/plain"},
    )
    assert wrong_content_type.status_code == 422
    assert wrong_content_type.json() == {"detail": "invalid viewer session request"}
    assert VIEWER_TOKEN not in wrong_content_type.text


def test_session_exchange_rejects_oversized_bodies_without_reflection(pwa_client):
    oversized_secret = "z" * 3_000
    response = pwa_client.post(
        "/v1/viewer/session",
        json={"token": oversized_secret},
    )
    assert response.status_code == 413
    assert oversized_secret not in response.text
    assert response.headers["cache-control"] == "no-store, private"



def test_session_body_limit_counts_chunked_transport():
    async def exercise() -> list[dict]:
        messages = iter(
            (
                {"type": "http.request", "body": b"x" * 1_500, "more_body": True},
                {"type": "http.request", "body": b"x" * 1_500, "more_body": False},
            )
        )
        sent: list[dict] = []

        async def receive():
            return next(messages)

        async def send(message):
            sent.append(message)

        async def consume(_scope, limited_receive, _send):
            while (await limited_receive()).get("more_body"):
                pass

        middleware = ViewerSessionBodyLimitMiddleware(consume)
        await middleware(
            {
                "type": "http",
                "method": "POST",
                "path": "/v1/viewer/session",
                "headers": [(b"content-type", b"application/json")],
            },
            receive,
            send,
        )
        return sent

    sent = asyncio.run(exercise())
    assert sent[0]["type"] == "http.response.start"
    assert sent[0]["status"] == 413


def test_viewer_requires_https_away_from_loopback(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    application = create_app(
        make_settings(
            tmp_path,
            viewer_token=VIEWER_TOKEN,
            allowed_hosts=("device.lan", "127.0.0.1"),
        ),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    with TestClient(application, base_url="http://device.lan") as client:
        login = client.post("/v1/viewer/session", json={"token": VIEWER_TOKEN})
        assert login.status_code == 426
        assert "set-cookie" not in login.headers
        assert client.get("/viewer/").status_code == 426

    # A caller-controlled loopback Host header must not bypass the HTTPS gate
    # when the actual network peer is remote.
    with TestClient(
        application,
        base_url="http://127.0.0.1",
        client=("198.51.100.7", 51_000),
    ) as client:
        login = client.post("/v1/viewer/session", json={"token": VIEWER_TOKEN})
        assert login.status_code == 426
        assert "set-cookie" not in login.headers
        assert client.get("/viewer/").status_code == 426

    with TestClient(
        application,
        base_url="http://127.0.0.1",
        client=("127.0.0.1", 51_000),
    ) as client:
        login = client.post("/v1/viewer/session", json={"token": VIEWER_TOKEN})
        assert login.status_code == 200
        assert "Secure" in login.headers["set-cookie"]


def test_viewer_accepts_forwarded_https_only_from_configured_proxy(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    application = create_app(
        make_settings(
            tmp_path,
            viewer_token=VIEWER_TOKEN,
            allowed_hosts=("viewer.example",),
            viewer_trusted_proxy_cidrs=("10.42.0.7/32",),
        ),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    forwarded_headers = {
        "X-Forwarded-Proto": "https",
        "Origin": "https://viewer.example",
    }
    with TestClient(
        application,
        base_url="http://viewer.example",
        client=("10.42.0.8", 51_000),
    ) as client:
        assert client.post(
            "/v1/viewer/session",
            json={"token": VIEWER_TOKEN},
            headers=forwarded_headers,
        ).status_code == 426

    with TestClient(
        application,
        base_url="http://viewer.example",
        client=("10.42.0.7", 51_000),
    ) as client:
        login = client.post(
            "/v1/viewer/session",
            json={"token": VIEWER_TOKEN},
            headers=forwarded_headers,
        )
        assert login.status_code == 200
        assert login.headers["strict-transport-security"] == "max-age=31536000"


def test_cookie_tampering_expiry_rotation_and_bearer_precedence(pwa_app):
    settings = pwa_app.state.settings
    now_seconds = int(time.time())
    valid, _ = issue_viewer_session(settings, now_seconds=now_seconds)
    expired, _ = issue_viewer_session(
        settings,
        now_seconds=now_seconds - (settings.viewer_session_days + 1) * 24 * 60 * 60,
    )
    tampered = valid[:-1] + ("A" if valid[-1] != "A" else "B")

    with TestClient(pwa_app, base_url="https://testserver") as client:
        for value in ("not-a-session", expired, tampered):
            response = client.get(
                "/v1/viewer/snapshot",
                headers={"Cookie": f"{VIEWER_SESSION_COOKIE}={value}"},
            )
            assert response.status_code == 401

        cookie_header = {"Cookie": f"{VIEWER_SESSION_COOKIE}={valid}"}
        assert client.get("/v1/viewer/snapshot", headers=cookie_header).status_code == 200
        assert (
            client.get(
                "/v1/viewer/snapshot",
                headers={
                    **cookie_header,
                    "Authorization": "Bearer explicitly-wrong",
                },
            ).status_code
            == 401
        )

        pwa_app.state.settings = make_settings(
            settings.database_path.parent,
            viewer_token=SECOND_VIEWER_TOKEN,
        )
        assert client.get("/v1/viewer/snapshot", headers=cookie_header).status_code == 401


def test_session_configuration_is_bounded(tmp_path):
    with pytest.raises(ValueError, match="between 1 and 365"):
        make_settings(tmp_path, viewer_token=VIEWER_TOKEN, viewer_session_days=0)
    with pytest.raises(ValueError, match="between 1 and 365"):
        make_settings(tmp_path, viewer_token=VIEWER_TOKEN, viewer_session_days=366)
    with pytest.raises(ValueError, match="between 32 and 512"):
        make_settings(tmp_path, viewer_token="x" * 513)
    with pytest.raises(ValueError, match="URL-safe ASCII"):
        make_settings(tmp_path, viewer_token="ю" * 32)
    with pytest.raises(ValueError, match="only IP networks"):
        make_settings(
            tmp_path,
            viewer_token=VIEWER_TOKEN,
            viewer_trusted_proxy_cidrs=("not-a-network",),
        )


def test_pwa_static_files_use_safe_cache_and_security_headers(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    dist = tmp_path / "pwa-dist"
    assets = dist / "assets"
    assets.mkdir(parents=True)
    (dist / "index.html").write_text("<main>Juggluco PWA</main>", encoding="utf-8")
    (dist / "manifest.webmanifest").write_text("{}", encoding="utf-8")
    (dist / "sw.js").write_text("self.addEventListener('fetch',()=>{});", encoding="utf-8")
    (assets / "app-deadbeef.js").write_text("export {};", encoding="utf-8")
    application = create_app(
        make_settings(
            tmp_path,
            viewer_token=VIEWER_TOKEN,
            pwa_dist_path=dist,
        ),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )

    with TestClient(application, base_url="https://testserver") as client:
        home = client.get("/", follow_redirects=False)
        assert home.status_code == 307
        assert home.headers["location"] == "/viewer/"

        index = client.get("/viewer/")
        assert index.status_code == 200
        assert index.headers["cache-control"] == "no-cache"
        assert "default-src 'self'" in index.headers["content-security-policy"]
        assert index.headers["x-frame-options"] == "DENY"
        assert index.headers["x-robots-tag"] == "noindex, nofollow, noarchive"

        asset = client.get("/viewer/assets/app-deadbeef.js")
        assert asset.status_code == 200
        assert asset.headers["cache-control"] == "public, max-age=31536000, immutable"

        manifest = client.get("/viewer/manifest.webmanifest")
        assert manifest.status_code == 200
        assert manifest.headers["content-type"].startswith("application/manifest+json")

        worker = client.get("/viewer/sw.js")
        assert worker.status_code == 200
        assert worker.headers["service-worker-allowed"] == "/viewer/"

        fallback = client.get("/viewer/settings")
        assert fallback.status_code == 200
        assert "Juggluco PWA" in fallback.text

        for missing_asset in ("missing", "missing.js"):
            missing = client.get(f"/viewer/assets/{missing_asset}")
            assert missing.status_code == 404
            assert missing.headers["cache-control"] == "no-store"
            assert "Juggluco PWA" not in missing.text

        api_miss = client.get("/v1/definitely-not-a-route")
        assert api_miss.status_code == 404
        assert api_miss.headers["content-type"].startswith("application/json")
