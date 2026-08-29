from __future__ import annotations

import base64
import json
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.models import GlucoseReadingRecord
from app.security import VIEWER_SESSION_COOKIE, issue_viewer_session
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
    with TestClient(application, base_url="https://testserver") as client:
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


def test_public_viewer_exposes_only_sanitized_glucose_and_never_grants_writes(
    tmp_path,
    fake_analyzer,
    fake_chat_analyzer,
    fake_transcriber,
):
    application = create_app(
        make_settings(
            tmp_path,
            viewer_token=VIEWER_TOKEN,
            viewer_public=True,
        ),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )
    admin = {"Authorization": f"Bearer {TEST_TOKEN}"}
    viewer = {"Authorization": f"Bearer {VIEWER_TOKEN}"}
    now = int(time.time() * 1_000)

    with TestClient(application, base_url="https://testserver") as client:
        _ingest(
            client,
            admin,
            [
                _reading("private-reading-id", now - 120_000, 112),
                _reading("another-private-id", now - 30_000, 114),
            ],
        )
        created = _meal(client, admin, now - 90_000)
        rapid = _insulin(client, admin, now - 60_000, "NovoRapid")
        long = _insulin(client, admin, now - 45_000, "Tresiba")

        snapshot_response = client.get("/v1/viewer/snapshot")
        assert snapshot_response.status_code == 200, snapshot_response.text
        snapshot = snapshot_response.json()
        assert snapshot["intake_events"] == []
        assert snapshot["intake_events_truncated"] is False
        assert snapshot["forecast"]["activities"] == []
        assert snapshot["insulin_events_order"] == "oldest_first"
        assert snapshot["insulin_events_truncated"] is False
        assert snapshot["insulin_events"] == [
            {
                "occurred_at_ms": now - 60_000,
                "insulin_units": 3.5,
                "insulin_type": "rapid",
                "insulin_name": "NovoRapid",
            },
            {
                "occurred_at_ms": now - 45_000,
                "insulin_units": 9.0,
                "insulin_type": "long",
                "insulin_name": "Tresiba",
            },
        ]
        assert all(
            set(item)
            == {"occurred_at_ms", "insulin_units", "insulin_type", "insulin_name"}
            for item in snapshot["insulin_events"]
        )
        serialized_snapshot = json.dumps(snapshot)
        assert "Rice and vegetables" not in serialized_snapshot
        assert created["id"] not in serialized_snapshot
        assert rapid["id"] not in serialized_snapshot
        assert long["id"] not in serialized_snapshot
        assert len(snapshot["glucose_history"]) == 2
        public_readings = snapshot["glucose_history"]
        assert all(item["reading_id"].startswith("reading-") for item in public_readings)
        assert {item["reading_id"] for item in public_readings}.isdisjoint(
            {"private-reading-id", "another-private-id"}
        )
        assert all(item["sensor_id"] is None for item in public_readings)
        assert all(item["sensor_generation"] is None for item in public_readings)
        assert snapshot["current_glucose"]["reading_id"] == public_readings[-1]["reading_id"]
        assert snapshot["current_glucose"]["sensor_id"] is None
        assert snapshot["current_glucose"]["sensor_generation"] is None

        glucose = client.get("/v1/viewer/glucose", params={"limit": 1})
        assert glucose.status_code == 200, glucose.text
        glucose_page = glucose.json()
        assert glucose_page["items"][0]["reading_id"] == public_readings[-1]["reading_id"]
        assert glucose_page["items"][0]["sensor_id"] is None
        assert glucose_page["next_cursor"] is not None
        encoded_payload = glucose_page["next_cursor"].split(".", 1)[0]
        cursor_payload = json.loads(
            base64.urlsafe_b64decode(
                encoded_payload + "=" * (-len(encoded_payload) % 4)
            )
        )
        assert cursor_payload["before_id"].startswith("reading-")
        assert "private" not in cursor_payload["before_id"]
        second_page = client.get(
            "/v1/viewer/glucose",
            params={"cursor": glucose_page["next_cursor"], "limit": 1},
        )
        assert second_page.status_code == 200, second_page.text
        assert second_page.json()["items"][0]["reading_id"] == public_readings[0]["reading_id"]
        for path in ("/v1/viewer/snapshot", "/v1/viewer/glucose"):
            too_wide = client.get(
                path,
                params={
                    "from_ms": now - 25 * 60 * 60_000,
                    "to_ms": now,
                },
            )
            assert too_wide.status_code == 422
            assert too_wide.json()["detail"] == (
                "public viewer windows cannot exceed 24 hours"
            )
        assert client.get("/v1/viewer/intakes").status_code == 403

        # A valid old viewer cookie still carries no authority on the separate
        # admin router when public link access is enabled.
        old_cookie, _ = issue_viewer_session(application.state.settings)
        client.cookies.set(VIEWER_SESSION_COOKIE, old_cookie)
        reading_payload = {
            "readings": [_reading("public-must-not-write", now, 110)]
        }
        denied_requests = [
            client.post("/v1/glucose/readings", json=reading_payload),
            client.post(
                "/v1/meal-events",
                json={
                    "client_event_id": str(uuid4()),
                    "occurred_at_ms": now,
                    "meal_text": "Must not persist",
                    "carbs_g": 10,
                },
            ),
            client.post(
                "/v1/insulin-events",
                json={
                    "client_event_id": str(uuid4()),
                    "occurred_at_ms": now,
                    "insulin_units": 1,
                    "insulin_name": "NovoRapid",
                },
            ),
            client.put(
                f"/v1/intakes/{created['id']}/meal-portion",
                json={"portion_g": 100},
            ),
            client.delete(f"/v1/intakes/{created['id']}"),
            client.post(
                "/v1/intake-chat/sessions",
                json={"client_session_id": str(uuid4())},
            ),
            client.post(
                "/v1/meal-chat/sessions",
                json={
                    "client_event_id": str(uuid4()),
                    "occurred_at_ms": now,
                },
            ),
            client.get("/v1/intakes"),
        ]
        assert [response.status_code for response in denied_requests] == [401] * len(
            denied_requests
        )
        assert client.post(
            "/v1/glucose/readings",
            headers=viewer,
            json=reading_payload,
        ).status_code == 401

        # Public mode does not interfere with the Android/admin credential.
        assert client.post(
            "/v1/glucose/readings",
            headers=admin,
            json=reading_payload,
        ).status_code == 200


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
    assert current["sensor_id"] == "sensor-private-id"
    assert current["sensor_generation"] == "Libre"
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
    assert payload["insulin_events_order"] == "oldest_first"
    assert payload["insulin_events_truncated"] is False
    assert payload["insulin_events"] == [
        {
            "occurred_at_ms": now - 17 * 60_000,
            "insulin_units": 3.5,
            "insulin_type": "rapid",
            "insulin_name": "NovoRapid",
        },
        {
            "occurred_at_ms": now - 15 * 60_000,
            "insulin_units": 9.0,
            "insulin_type": "long",
            "insulin_name": "Tresiba",
        },
    ]
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

    # A SHA-256 signature has two unused bits in its final unpadded base64url
    # character. Alternate values for those bits decode to the same bytes and
    # must not be accepted as distinct cursor encodings.
    b64url_alphabet = (
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    )
    encoded_payload, encoded_signature = valid_cursor.split(".", 1)
    final_index = b64url_alphabet.index(encoded_signature[-1])
    assert final_index % 4 == 0
    signature_alias = (
        encoded_signature[:-1] + b64url_alphabet[final_index + 1]
    )
    noncanonical_cursor = f"{encoded_payload}.{signature_alias}"
    assert (
        viewer_client.get(
            "/v1/viewer/glucose",
            headers=viewer_headers,
            params={"cursor": noncanonical_cursor},
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
    with pytest.raises(ValueError, match="between 32 and 512"):
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
        assert response.headers["vary"] == "Authorization, Cookie"
