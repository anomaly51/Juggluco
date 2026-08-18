from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import time
from uuid import uuid4

from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.main import _soft_delete_intake_record, create_app
from app.models import ForecastMaintenanceRecord, SyncChangeRecord
from conftest import FakeAnalyzer, TEST_TOKEN, make_settings


def valid_payload(**overrides):
    payload = {
        "client_event_id": str(uuid4()),
        "occurred_at_ms": int(time.time() * 1000) - 30_000,
        "insulin_units": 4.5,
        "insulin_name": "NovoRapid",
    }
    payload.update(overrides)
    return payload


def test_create_list_and_get_intake(client, auth_headers):
    payload = valid_payload()
    created = client.post("/v1/insulin-events", headers=auth_headers, json=payload)
    assert created.status_code == 200
    event = created.json()
    assert event["client_event_id"] == payload["client_event_id"]
    assert event["meal_text"] is None
    assert event["insulin_units"] == 4.5
    assert event["insulin_type"] == "rapid"
    assert event["insulin_name"] == "NovoRapid"
    assert event["ai_confidence"] == 0.0
    assert event["deleted"] is False
    assert event["sync_version"] > 0

    listed = client.get("/v1/intakes", headers=auth_headers)
    assert listed.status_code == 200
    assert listed.json()["items"] == [event]
    assert listed.json()["next_sync_version"] == event["sync_version"]

    fetched = client.get(f"/v1/intakes/{event['id']}", headers=auth_headers)
    assert fetched.json() == event


def test_creation_does_not_schedule_or_mark_forecast_training(
    app, client, auth_headers
):
    now_ms = int(time.time() * 1_000)
    later = now_ms - 10 * 60_000
    earlier = now_ms - 90 * 60_000
    for occurred_at_ms in (later, earlier):
        response = client.post(
            "/v1/insulin-events",
            headers=auth_headers,
            json=valid_payload(
                client_event_id=str(uuid4()),
                occurred_at_ms=occurred_at_ms,
            ),
        )
        assert response.status_code == 200
    with app.state.database.session_factory() as session:
        assert session.get(ForecastMaintenanceRecord, "training_dirty_since") is None


def test_client_event_id_is_idempotent_and_conflicts_on_changed_data(
    client, auth_headers
):
    payload = valid_payload()
    first = client.post("/v1/insulin-events", headers=auth_headers, json=payload)
    second = client.post("/v1/insulin-events", headers=auth_headers, json=payload)
    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()

    changed = {**payload, "insulin_units": 7.0}
    conflict = client.post("/v1/insulin-events", headers=auth_headers, json=changed)
    assert conflict.status_code == 409


def test_manual_meal_is_idempotent_and_keeps_an_editable_portion_baseline(
    client, auth_headers
):
    payload = {
        "client_event_id": str(uuid4()),
        "occurred_at_ms": int(time.time() * 1000) - 30_000,
        "meal_text": "Buckwheat with chicken",
        "carbs_g": 48.0,
        "portion_g": 300.0,
    }
    first = client.post("/v1/meal-events", headers=auth_headers, json=payload)
    retry = client.post("/v1/meal-events", headers=auth_headers, json=payload)
    assert first.status_code == retry.status_code == 200
    assert first.json() == retry.json()
    event = first.json()
    assert event["carbs_source"] == "manual"
    assert event["portion_g"] == event["original_portion_g"] == 300.0
    assert event["original_carbs_g"] == 48.0

    changed = client.put(
        f"/v1/intakes/{event['id']}/meal-portion",
        headers=auth_headers,
        json={"portion_g": 150.0},
    )
    assert changed.status_code == 200
    assert changed.json()["portion_g"] == 150.0
    assert changed.json()["carbs_g"] == 24.0
    assert changed.json()["original_carbs_g"] == 48.0


def test_manual_meal_rejects_missing_description(client, auth_headers):
    response = client.post(
        "/v1/meal-events",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": int(time.time() * 1000),
            "meal_text": " ",
            "carbs_g": 20,
        },
    )
    assert response.status_code == 422


def test_validation_prevents_incomplete_or_unsafe_records(client, auth_headers):
    empty = valid_payload(insulin_units=None)
    assert client.post(
        "/v1/insulin-events", headers=auth_headers, json=empty
    ).status_code == 422

    unknown_product = valid_payload(insulin_name="Other")
    assert client.post(
        "/v1/insulin-events", headers=auth_headers, json=unknown_product
    ).status_code == 422

    mixed = valid_payload(meal_text="This field must never be accepted")
    assert client.post(
        "/v1/insulin-events", headers=auth_headers, json=mixed
    ).status_code == 422

    future = valid_payload(occurred_at_ms=int(time.time() * 1000) + 20 * 60_000)
    assert client.post(
        "/v1/insulin-events", headers=auth_headers, json=future
    ).status_code == 422


def test_generic_intake_post_is_not_a_write_bypass(client, auth_headers):
    response = client.post(
        "/v1/intakes",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": int(time.time() * 1000),
            "meal_text": "Unconfirmed meal",
            "carbs_g": 25,
            "carbs_source": "ai_estimate",
            "analysis_id": str(uuid4()),
        },
    )
    assert response.status_code == 405


def test_delete_is_soft_and_delta_sync_returns_tombstone(client, auth_headers):
    created = client.post(
        "/v1/insulin-events", headers=auth_headers, json=valid_payload()
    ).json()
    deleted = client.delete(
        f"/v1/intakes/{created['id']}", headers=auth_headers
    )
    assert deleted.status_code == 200
    tombstone = deleted.json()
    assert tombstone["deleted"] is True
    assert tombstone["sync_version"] > created["sync_version"]

    initial = client.get("/v1/intakes", headers=auth_headers).json()
    assert initial["items"] == []

    delta = client.get(
        "/v1/intakes",
        params={"after_sync_version": created["sync_version"]},
        headers=auth_headers,
    ).json()
    assert delta["items"] == [tombstone]
    assert delta["next_sync_version"] == tombstone["sync_version"]


def test_delete_requires_auth_and_returns_clear_not_found(client, auth_headers):
    missing_id = str(uuid4())
    unauthorized = client.delete(f"/v1/intakes/{missing_id}")
    assert unauthorized.status_code == 401
    missing = client.delete(
        f"/v1/intakes/{missing_id}", headers=auth_headers
    )
    assert missing.status_code == 404
    assert missing.json()["detail"] == "intake event not found"


def test_concurrent_delete_creates_exactly_one_tombstone_revision(
    app, client, auth_headers
):
    created = client.post(
        "/v1/insulin-events", headers=auth_headers, json=valid_payload()
    ).json()
    barrier = Barrier(2)
    deleted_at_ms = int(time.time() * 1_000)

    def delete_from_independent_transaction():
        with app.state.database.session_factory() as session:
            barrier.wait(timeout=5)
            record, deleted_now = _soft_delete_intake_record(
                session, created["id"], now_ms=deleted_at_ms
            )
            assert record is not None
            return (
                deleted_now,
                record.deleted_at_ms,
                record.updated_at_ms,
                record.sync_version,
            )

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(
            executor.map(lambda _index: delete_from_independent_transaction(), range(2))
        )

    assert sorted(result[0] for result in results) == [False, True]
    assert results[0][1:] == results[1][1:]
    assert results[0][1] == deleted_at_ms
    with app.state.database.session_factory() as session:
        delete_changes = list(
            session.scalars(
                select(SyncChangeRecord).where(
                    SyncChangeRecord.event_id == created["id"],
                    SyncChangeRecord.operation == "delete",
                )
            )
        )
        assert len(delete_changes) == 1
        assert delete_changes[0].id == results[0][3]
        assert session.get(ForecastMaintenanceRecord, "training_dirty_since") is None


def test_list_filters_intakes_by_inclusive_occurred_time_window(
    client, auth_headers
):
    now_ms = int(time.time() * 1000)
    times = [now_ms - 180_000, now_ms - 120_000, now_ms - 60_000]
    created = []
    for index, occurred_at_ms in enumerate(times):
        response = client.post(
            "/v1/insulin-events",
            headers=auth_headers,
            json=valid_payload(
                client_event_id=str(uuid4()),
                occurred_at_ms=occurred_at_ms,
                insulin_units=1.0 + index,
            ),
        )
        assert response.status_code == 200
        created.append(response.json())

    bounded = client.get(
        "/v1/intakes",
        params={"from_ms": times[1], "to_ms": times[2]},
        headers=auth_headers,
    )
    assert bounded.status_code == 200
    assert bounded.json()["items"] == created[1:]

    from_only = client.get(
        "/v1/intakes",
        params={"from_ms": times[2]},
        headers=auth_headers,
    )
    assert from_only.status_code == 200
    assert from_only.json()["items"] == [created[2]]

    to_only = client.get(
        "/v1/intakes",
        params={"to_ms": times[0]},
        headers=auth_headers,
    )
    assert to_only.status_code == 200
    assert to_only.json()["items"] == [created[0]]


def test_list_rejects_reversed_occurred_time_window(client, auth_headers):
    response = client.get(
        "/v1/intakes",
        params={"from_ms": 2_000, "to_ms": 1_000},
        headers=auth_headers,
    )
    assert response.status_code == 422
    assert response.json()["detail"] == "from_ms must be less than or equal to to_ms"


def test_sqlite_events_survive_backend_restart(tmp_path):
    settings = make_settings(tmp_path)
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}
    payload = valid_payload()

    first_app = create_app(settings, analyzer=FakeAnalyzer())
    with TestClient(first_app) as first_client:
        created = first_client.post(
            "/v1/insulin-events", headers=headers, json=payload
        ).json()

    second_app = create_app(settings, analyzer=FakeAnalyzer())
    with TestClient(second_app) as second_client:
        listed = second_client.get("/v1/intakes", headers=headers)
        assert listed.status_code == 200
        assert listed.json()["items"] == [created]
