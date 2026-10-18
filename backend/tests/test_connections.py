import asyncio
import secrets
import time
from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import select

from app.main import create_app
from app.models import GlucoseReadingRecord, ForecastModelRecord
from conftest import make_settings, TEST_TOKEN
from test_forecast import reading_batch
from test_viewer_stream import _stream_scope, _body


def register(client, *, identifier=None, secret=None, claim=False):
    identifier, secret = identifier or str(uuid4()), secret or secrets.token_urlsafe(32)
    headers = {"Authorization": "Bearer " + secret}
    if claim:
        headers["X-Legacy-Token"] = TEST_TOKEN
    response = client.post("/v1/installations" + ("/claim" if claim else ""),
                           headers=headers, json={"installation_id": identifier})
    assert response.status_code == 200, response.text
    return response.json(), headers


def viewer(profile, endpoint="snapshot"):
    return "/v1/shares/" + profile["viewer_path"].split("profile=")[1] + "/" + endpoint


def test_two_installations_isolate_glucose_intakes_forecasts_and_streams(tmp_path):
    app = create_app(make_settings(tmp_path))
    with TestClient(app, base_url="https://testserver") as client:
        a, ah = register(client)
        b, bh = register(client)
        batch = reading_batch(int(time.time() * 1000))
        assert client.post(a["api_path"] + "/v1/glucose/readings", headers=ah, json=batch).status_code == 200
        for reading in batch["readings"]:
            reading["glucose_mg_dl"] = 190
        assert client.post(b["api_path"] + "/v1/glucose/readings", headers=bh, json=batch).status_code == 200
        snap_a, snap_b = client.get(viewer(a)).json(), client.get(viewer(b)).json()
        assert snap_a["current_glucose"]["glucose_mg_dl"] != snap_b["current_glucose"]["glucose_mg_dl"]
        assert snap_a["stream_id"] != snap_b["stream_id"]
        assert snap_a["forecast"] != snap_b["forecast"]
        assert client.get(a["api_path"] + "/v1/forecast/current", headers=bh).status_code == 401
        assert client.get(a["api_path"] + "/v1/intakes", headers=bh).status_code == 401
        assert client.get(a["api_path"] + "/v1/intakes", headers={"Authorization": "Bearer " + TEST_TOKEN}).status_code == 401
        shared_id = str(uuid4())
        insulin = {"client_event_id": shared_id, "occurred_at_ms": int(time.time() * 1000),
                   "insulin_units": 2, "insulin_name": "NovoRapid"}
        event_a = client.post(a["api_path"] + "/v1/insulin-events", headers=ah, json=insulin)
        insulin["insulin_units"] = 7
        event_b = client.post(b["api_path"] + "/v1/insulin-events", headers=bh, json=insulin)
        assert event_a.status_code == event_b.status_code == 200
        assert event_a.json()["id"] != event_b.json()["id"]
        assert client.delete(a["api_path"] + "/v1/intakes/" + event_b.json()["id"], headers=ah).status_code == 404
        assert client.get(b["api_path"] + "/v1/intakes", headers=bh).json()["items"][0]["insulin_units"] == 7
        child_a = app.state.connections.runtimes[a["installation_id"]].app
        child_b = app.state.connections.runtimes[b["installation_id"]].app
        assert child_a.state.database.engine is not child_b.state.database.engine
        assert child_a.state.forecast_service is not child_b.state.forecast_service
        with app.state.database.session_factory() as session:
            assert session.scalar(select(GlucoseReadingRecord)) is None
        with child_a.state.database.session_factory() as sa, child_b.state.database.session_factory() as sb:
            # Equal model versions are shared algorithms, not shared learned state.
            assert sa.bind.url != sb.bind.url
            model_a = sa.scalar(select(ForecastModelRecord))
            model_b = sb.scalar(select(ForecastModelRecord))
            assert model_a is not None and model_b is not None
            original_b = model_b.metrics_json
            model_a.metrics_json = '{"private_profile_marker": 42}'
            sa.commit()
            sb.expire_all()
            assert sb.scalar(select(ForecastModelRecord)).metrics_json == original_b


def test_registration_idempotent_and_no_secret_disclosure(tmp_path):
    settings = make_settings(tmp_path)
    identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
    with TestClient(create_app(settings), base_url="https://testserver") as client:
        a, _ = register(client, identifier=identifier, secret=secret)
        again, _ = register(client, identifier=identifier, secret=secret)
        assert a == again
        assert secret not in str(a)
        response = client.post("/v1/installations", headers={"Authorization": "Bearer " + secrets.token_urlsafe(32)},
                               json={"installation_id": identifier})
        assert response.status_code == 409
    with TestClient(create_app(settings), base_url="https://testserver") as client:
        after_restart, _ = register(client, identifier=identifier, secret=secret)
        assert after_restart == a


def test_rotation_revokes_old_link_preserves_owner_and_history(tmp_path):
    with TestClient(create_app(make_settings(tmp_path)), base_url="https://testserver") as client:
        a, headers = register(client)
        assert client.post(a["api_path"] + "/v1/glucose/readings", headers=headers,
                           json=reading_batch(int(time.time() * 1000))).status_code == 200
        operation = {"operation_id": str(uuid4()), "label": "my-sugar"}
        url = a["api_path"] + "/connection"
        changed = client.post(url, headers=headers, json=operation).json()
        assert changed == client.post(url, headers=headers, json=operation).json()
        assert changed["api_path"] == a["api_path"]
        assert len(changed["viewer_path"].split("profile=")[1]) == 22
        assert client.get(viewer(a)).status_code == 401
        assert client.get(viewer(a, "session")).status_code == 401
        assert client.get(viewer(changed)).json()["current_glucose"] is not None
        assert client.get(viewer(changed, "session")).json()["authenticated"] is True
        assert client.post(viewer(changed)).status_code == 404
        assert client.get(viewer(changed, "intakes")).status_code == 404
        manifest = client.get("/viewer/personal.webmanifest?profile=" + changed["viewer_path"].split("profile=")[1])
        assert manifest.json()["start_url"] == changed["viewer_path"]


def test_legacy_claim_is_explicit_one_time_and_keeps_data(tmp_path):
    app = create_app(make_settings(tmp_path))
    with TestClient(app, base_url="https://testserver") as client:
        batch = reading_batch(int(time.time() * 1000))
        assert client.post("/v1/glucose/readings", headers={"Authorization": "Bearer " + TEST_TOKEN}, json=batch).status_code == 200
        fresh, _ = register(client)
        assert client.get(viewer(fresh)).json()["current_glucose"] is None
        old, headers = register(client, claim=True)
        assert client.get(viewer(old)).json()["current_glucose"] is not None
        assert old["installation_id"] not in app.state.connections.runtimes
        response = client.post("/v1/installations/claim", headers={**headers, "Authorization": "Bearer " + secrets.token_urlsafe(32)},
                               json={"installation_id": str(uuid4())})
        assert response.status_code == 409


def test_invalid_requests_hosts_cleartext_limits_and_no_traversal(tmp_path):
    settings = make_settings(tmp_path, connection_registration_limit=1)
    with TestClient(create_app(settings), base_url="https://testserver") as client:
        a, headers = register(client)
        assert client.post("/v1/installations", headers={**headers, "Host": "evil.invalid"}, json={"installation_id": str(uuid4())}).status_code == 400
        assert client.post("http://testserver/v1/installations", headers=headers, json={"installation_id": str(uuid4())}).status_code == 426
        assert client.post("/v1/installations", headers=headers, json={"installation_id": "../../other"}).status_code == 422
        assert client.post("/v1/installations", headers=headers, content="x" * 3000).status_code == 413
        assert client.post("/v1/installations", headers=headers, json={"installation_id": str(uuid4())}).status_code == 429
        assert client.get("/v1/shares/invalid/snapshot").status_code == 401
        assert client.get("/devices/missing/v1/health", headers=headers).status_code == 401


def test_runtime_eviction_reopens_same_database(tmp_path):
    settings = make_settings(tmp_path, connection_runtime_limit=1)
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        a, headers = register(client)
        assert client.post(a["api_path"] + "/v1/glucose/readings", headers=headers,
                           json=reading_batch(int(time.time() * 1000))).status_code == 200
        b, _ = register(client)
        assert list(app.state.connections.runtimes) == [b["installation_id"]]
        assert client.get(viewer(a)).json()["current_glucose"] is not None
        assert len(app.state.connections.runtimes) == 1


def test_revoked_live_stream_stops_and_does_not_receive_new_glucose(tmp_path):
    async def exercise():
        app = create_app(make_settings(tmp_path))
        manager = app.state.connections
        identifier, secret = str(uuid4()), secrets.token_urlsafe(32)
        row = manager.registry.register(identifier, secret)
        scope = _stream_scope([])
        scope["path"] = "/v1/shares/" + row["handle"] + "/stream"
        scope["raw_path"] = scope["path"].encode()
        delivered = False
        never = asyncio.Event()
        sent = []

        async def receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": b"", "more_body": False}
            await never.wait()
            return {"type": "http.disconnect"}

        async def send(message):
            sent.append(message)
            if b"event: ready" in message.get("body", b""):
                manager.registry.rotate(identifier, secret, str(uuid4()), "changed")
                manager.runtimes[identifier].app.state.glucose_updates.publish_threadsafe(99, 12345)

        async with app.router.lifespan_context(app):
            await asyncio.wait_for(app(scope, receive, send), timeout=3)
        assert "event: ready" in _body(sent)
        assert "event: glucose" not in _body(sent)
        headers = dict(next(message for message in sent if message["type"] == "http.response.start")["headers"])
        assert b"no-transform" in headers[b"cache-control"]
    asyncio.run(exercise())


def test_quota_is_per_installation_and_does_not_affect_glucose(tmp_path):
    app = create_app(make_settings(tmp_path, connection_ai_daily_limit=1))
    with TestClient(app, base_url="https://testserver") as client:
        a, ah = register(client)
        b, bh = register(client)
        # Invalid synthetic request: consumes a request slot, never invokes a provider.
        path = "/v1/transcriptions"
        assert client.post(a["api_path"] + path, headers=ah).status_code != 429
        assert client.post(a["api_path"] + path, headers=ah).status_code == 429
        assert client.post(b["api_path"] + path, headers=bh).status_code != 429
        assert client.post(a["api_path"] + "/v1/glucose/readings", headers=ah,
                           json=reading_batch(int(time.time() * 1000))).status_code == 200
