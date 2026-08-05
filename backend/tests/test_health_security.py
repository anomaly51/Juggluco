from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app
from conftest import FakeAnalyzer, make_settings


def test_health_is_public_but_does_not_expose_secrets(client):
    response = client.get("/v1/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["database"] == "ok"
    assert body["auth_configured"] is True
    assert body["ai_configured"] is False
    assert "openrouter" not in response.text.lower()
    assert "model" not in response.text.lower()
    assert "api_token" not in response.text
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-request-id"]


def test_protected_routes_require_the_exact_bearer_token(client):
    missing = client.get("/v1/intakes")
    assert missing.status_code == 401
    assert missing.headers["www-authenticate"] == "Bearer"

    wrong = client.get(
        "/v1/intakes", headers={"Authorization": "Bearer definitely-wrong"}
    )
    assert wrong.status_code == 401


def test_untrusted_host_is_rejected(client, auth_headers):
    response = client.get(
        "/v1/intakes", headers={**auth_headers, "Host": "attacker.example"}
    )
    assert response.status_code == 400


def test_missing_server_token_fails_closed(tmp_path):
    settings = make_settings(tmp_path, api_token=None)
    application = create_app(settings, analyzer=FakeAnalyzer())
    with TestClient(application) as local_client:
        health = local_client.get("/v1/health")
        assert health.json()["status"] == "degraded"
        protected = local_client.get("/v1/intakes")
        assert protected.status_code == 503
