from __future__ import annotations

import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.models import GlucoseReadingRecord
from conftest import TEST_TOKEN, make_settings


VIEWER_TOKEN = "viewer-token-that-is-distinct-and-longer-than-thirty-two-characters"


@pytest.fixture
def viewer_client(
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
    with TestClient(application) as client:
        yield client


@pytest.fixture
def viewer_headers():
    return {"Authorization": f"Bearer {VIEWER_TOKEN}"}


@pytest.fixture
def admin_headers():
    return {"Authorization": f"Bearer {TEST_TOKEN}"}


def _reading(reading_id: str, at_ms: int, glucose: float) -> dict:
    return {
        "reading_id": reading_id,
        "measured_at_ms": at_ms,
        "glucose_mg_dl": glucose,
        "trend_mg_dl_min": 0.7,
        "sensor_id": "sensor-private-id",
        "sensor_generation": "Libre",
        "quality": 0.91,
        "utc_offset_minutes": 180,
    }


def _ingest(client: TestClient, headers: dict[str, str], readings: list[dict]) -> None:
    response = client.post(
        "/v1/glucose/readings",
        headers=headers,
        json={"readings": readings},
    )
    assert response.status_code == 200, response.text


def _meal(client: TestClient, headers: dict[str, str], at_ms: int) -> dict:
    response = client.post(
        "/v1/meal-events",
        headers=headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": at_ms,
            "meal_text": "Rice and vegetables",
            "carbs_g": 48,
            "portion_g": 320,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def _insulin(
    client: TestClient,
    headers: dict[str, str],
    at_ms: int,
    name: str,
) -> dict:
    response = client.post(
        "/v1/insulin-events",
        headers=headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": at_ms,
            "insulin_units": 3.5 if name == "NovoRapid" else 9,
            "insulin_name": name,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_viewer_token_is_limited_to_viewer_get_routes(
    viewer_client,
    viewer_headers,
    admin_headers,
):
    now = int(time.time() * 1_000)
    created = _meal(viewer_client, admin_headers, now - 60_000)

    assert viewer_client.get(
        "/v1/viewer/snapshot", headers=viewer_headers
    ).status_code == 200
    assert viewer_client.get(
        "/v1/viewer/snapshot", headers=admin_headers
    ).status_code == 200

    reading_payload = {"readings": [_reading("viewer-must-not-write", now, 110)]}
    denied_requests = [
        viewer_client.post(
            "/v1/glucose/readings", headers=viewer_headers, json=reading_payload
        ),
        viewer_client.post(
            "/v1/meal-events",
            headers=viewer_headers,
            json={
                "client_event_id": str(uuid4()),
                "occurred_at_ms": now,
                "meal_text": "Must not persist",
                "carbs_g": 10,
            },
        ),
        viewer_client.post(
            "/v1/insulin-events",
            headers=viewer_headers,
            json={
                "client_event_id": str(uuid4()),
                "occurred_at_ms": now,
                "insulin_units": 1,
                "insulin_name": "NovoRapid",
            },
        ),
        viewer_client.put(
            f"/v1/intakes/{created['id']}/meal-portion",
            headers=viewer_headers,
            json={"portion_g": 100},
        ),
        viewer_client.delete(
            f"/v1/intakes/{created['id']}", headers=viewer_headers
        ),
        viewer_client.get("/v1/intakes", headers=viewer_headers),
    ]
    assert [response.status_code for response in denied_requests] == [401] * len(
        denied_requests
    )

    assert viewer_client.get("/v1/viewer/snapshot").status_code == 401
    assert (
        viewer_client.get(
            "/v1/viewer/snapshot",
            headers={"Authorization": "Bearer definitely-wrong"},
        ).status_code
        == 401
    )


def test_snapshot_is_bounded_ascending_and_marks_stale_current_glucose(
    viewer_client,
    viewer_headers,
    admin_headers,
):
    now = int(time.time() * 1_000)
    timestamps = [now - 20 * 60_000, now - 18 * 60_000, now - 16 * 60_000]
    _ingest(
        viewer_client,
        admin_headers,
        [
            _reading("old", timestamps[0], 105),
            _reading("middle", timestamps[1], 110),
            _reading("latest", timestamps[2], 115),
        ],
    )
    meal = _meal(viewer_client, admin_headers, now - 19 * 60_000)
    rapid = _insulin(viewer_client, admin_headers, now - 17 * 60_000, "NovoRapid")
    long = _insulin(viewer_client, admin_headers, now - 15 * 60_000, "Tresiba")

    response = viewer_client.get(
        "/v1/viewer/snapshot",
        headers=viewer_headers,
        params={
            "from_ms": now - 60 * 60_000,
            "to_ms": now,
            "glucose_limit": 2,
            "event_limit": 2,
        },
    )
    assert response.status_code == 200, response.text
    payload = response.json()

    assert payload["api_version"] == "v1"
    assert payload["target_range"] == {
        "low_mg_dl": 75.6,
        "high_mg_dl": 162.0,
        "low_mmol_l": 4.2,
        "high_mmol_l": 9.0,
    }
    assert payload["glucose_history_order"] == "oldest_first"
    assert [item["reading_id"] for item in payload["glucose_history"]] == [
        "middle",
        "latest",
    ]
    assert payload["glucose_history_truncated"] is True
    current = payload["current_glucose"]
    assert current["reading_id"] == "latest"
    assert current["measured_at_ms"] == timestamps[2]
    assert current["received_at_ms"] >= timestamps[2]
    assert current["glucose_mg_dl"] == 115
    assert current["trend_mg_dl_min"] == 0.7
    assert current["quality"] == 0.91
    assert current["age_ms"] >= 16 * 60_000
    assert current["is_stale"] is True

    assert payload["intake_events_order"] == "oldest_first"
    assert [item["kind"] for item in payload["intake_events"]] == [
        "rapid",
        "long",
    ]
    assert payload["intake_events_truncated"] is True
    assert {meal["id"], rapid["id"], long["id"]}.issuperset(
        item["id"] for item in payload["intake_events"]
    )
    assert payload["forecast"]["status"] == "stale"


def test_snapshot_default_keeps_a_full_one_minute_24_hour_graph(
    viewer_client,
    viewer_headers,
):
    end_ms = int(time.time() * 1_000)
    start_ms = end_ms - 24 * 60 * 60_000
    with viewer_client.app.state.database.session_factory() as session:
        session.add_all(
            GlucoseReadingRecord(
                reading_id=f"minute-{index:04d}",
                measured_at_ms=start_ms + index * 60_000,
                glucose_mg_dl=100 + (index % 20),
                trend_mg_dl_min=None,
                sensor_id=None,
                sensor_generation=None,
                quality=1.0,
                utc_offset_minutes=180,
                payload_hash="0" * 64,
                received_at_ms=start_ms + index * 60_000,
            )
            for index in range(1_441)
        )
        session.commit()

    response = viewer_client.get(
        "/v1/viewer/snapshot",
        headers=viewer_headers,
        params={"from_ms": start_ms, "to_ms": end_ms},
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert len(payload["glucose_history"]) == 1_441
    assert payload["glucose_history_truncated"] is False
    assert payload["glucose_history"][0]["measured_at_ms"] == start_ms
    assert payload["glucose_history"][-1]["measured_at_ms"] == end_ms


def test_glucose_cursor_is_signed_filter_bound_and_keeps_same_timestamp_rows(
    viewer_client,
    viewer_headers,
    admin_headers,
):
    now = int(time.time() * 1_000)
    shared = now - 5 * 60_000
    readings = [
        _reading("same-a", shared, 101),
        _reading("same-b", shared, 102),
        _reading("same-c", shared, 103),
        _reading("older", shared - 60_000, 99),
        _reading("oldest", shared - 120_000, 98),
    ]
    _ingest(viewer_client, admin_headers, readings)
    params = {"from_ms": shared - 180_000, "to_ms": now, "limit": 2}

    first = viewer_client.get(
        "/v1/viewer/glucose", headers=viewer_headers, params=params
    )
    assert first.status_code == 200, first.text
    first_payload = first.json()
    assert first_payload["order"] == "newest_first"
    assert first_payload["has_more"] is True
    assert first_payload["next_cursor"]

    all_ids = [item["reading_id"] for item in first_payload["items"]]
    cursor = first_payload["next_cursor"]
    while cursor is not None:
        page = viewer_client.get(
            "/v1/viewer/glucose",
            headers=viewer_headers,
            params={"cursor": cursor, "limit": 2},
        )
        assert page.status_code == 200, page.text
        page_payload = page.json()
        all_ids.extend(item["reading_id"] for item in page_payload["items"])
        cursor = page_payload["next_cursor"]

    assert all_ids == ["same-c", "same-b", "same-a", "older", "oldest"]
    assert len(all_ids) == len(set(all_ids))

    valid_cursor = first_payload["next_cursor"]
    tampered = valid_cursor[:-1] + ("A" if valid_cursor[-1] != "A" else "B")
    assert (
        viewer_client.get(
            "/v1/viewer/glucose",
            headers=viewer_headers,
            params={"cursor": tampered},
        ).status_code
        == 422
    )
    assert (
        viewer_client.get(
            "/v1/viewer/intakes",
            headers=viewer_headers,
            params={"cursor": valid_cursor},
        ).status_code
        == 422
    )
    assert (
        viewer_client.get(
            "/v1/viewer/glucose",
            headers=viewer_headers,
            params={"cursor": valid_cursor, "from_ms": shared - 179_999},
        ).status_code
        == 422
    )


def test_intake_cursor_keeps_same_timestamp_events_and_excludes_tombstones(
    viewer_client,
    viewer_headers,
    admin_headers,
):
    now = int(time.time() * 1_000)
    shared = now - 60_000
    events = [
        _meal(viewer_client, admin_headers, shared),
        _insulin(viewer_client, admin_headers, shared, "NovoRapid"),
        _insulin(viewer_client, admin_headers, shared, "Tresiba"),
        _meal(viewer_client, admin_headers, shared - 60_000),
    ]
    deleted = _meal(viewer_client, admin_headers, shared - 120_000)
    assert viewer_client.delete(
        f"/v1/intakes/{deleted['id']}", headers=admin_headers
    ).status_code == 200

    cursor = None
    found: list[dict] = []
    while True:
        params = {
            "from_ms": shared - 180_000,
            "to_ms": now,
            "limit": 2,
        }
        if cursor is not None:
            params = {"cursor": cursor, "limit": 2}
        response = viewer_client.get(
            "/v1/viewer/intakes", headers=viewer_headers, params=params
        )
        assert response.status_code == 200, response.text
        payload = response.json()
        assert payload["order"] == "newest_first"
        found.extend(payload["items"])
        cursor = payload["next_cursor"]
        if cursor is None:
            break

    assert {item["id"] for item in found} == {item["id"] for item in events}
    assert deleted["id"] not in {item["id"] for item in found}
    assert {item["kind"] for item in found} == {"meal", "rapid", "long"}
    assert len(found) == len({item["id"] for item in found})


def test_viewer_rejects_unbounded_or_invalid_requests(
    viewer_client,
    viewer_headers,
):
    now = int(time.time() * 1_000)
    month_and_a_day = 32 * 24 * 60 * 60_000
    invalid_params = [
        {"from_ms": now, "to_ms": now - 1},
        {"from_ms": now - month_and_a_day, "to_ms": now},
        {"from_ms": now - 1_000, "to_ms": now + 11 * 60_000},
        {"glucose_limit": 2_501},
        {"event_limit": 501},
    ]
    for params in invalid_params:
        response = viewer_client.get(
            "/v1/viewer/snapshot", headers=viewer_headers, params=params
        )
        assert response.status_code == 422, (params, response.text)

    assert (
        viewer_client.get(
            "/v1/viewer/glucose",
            headers=viewer_headers,
            params={"limit": 501},
        ).status_code
        == 422
    )


def test_viewer_configuration_requires_a_distinct_strong_token(tmp_path):
    with pytest.raises(ValueError, match="at least 32"):
        make_settings(tmp_path, viewer_token="too-short")
    with pytest.raises(ValueError, match="must differ"):
        make_settings(tmp_path, viewer_token=TEST_TOKEN)


def test_health_reports_dedicated_viewer_auth(
    viewer_client,
):
    response = viewer_client.get("/v1/health")
    assert response.status_code == 200
    assert response.json()["viewer_auth_configured"] is True


def test_viewer_health_data_is_never_cacheable(
    viewer_client,
    viewer_headers,
):
    for path in (
        "/v1/viewer/snapshot",
        "/v1/viewer/glucose",
        "/v1/viewer/intakes",
    ):
        response = viewer_client.get(path, headers=viewer_headers)
        assert response.status_code == 200, response.text
        assert response.headers["cache-control"] == "no-store, private"
        assert response.headers["vary"] == "Authorization"
