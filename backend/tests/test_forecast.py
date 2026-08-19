from __future__ import annotations

import math
import json
import sqlite3
import time
from uuid import uuid4

import pytest

from app.forecast import (
    ACTIVE_MODEL_METADATA_KEY,
    BASELINE_VERSION,
    FORECAST_ENGINE_VERSION,
    ForecastService,
    _Event,
    _default_parameters,
    _event_glucose_increment,
    _meal_event_uncertainty,
)
from app.main import create_app
from app.models import (
    AnalysisRecord,
    BackendMetadataRecord,
    ForecastMaintenanceRecord,
    ForecastCalibrationRecord,
    ForecastModelRecord,
    ForecastPointRecord,
    ForecastRunRecord,
    ForecastScoreRecord,
    GlucoseReadingRecord,
)
from app.schemas import AnalysisItem, MealChatModelResult, MealChatProposal
from conftest import FakeAnalyzer, TEST_TOKEN, make_settings
from fastapi.testclient import TestClient
from sqlalchemy import func, select


STEP_MS = 5 * 60_000


def reading_batch(now_ms: int, count: int = 24, *, quality: float = 1.0):
    return {
        "utc_offset_minutes": 180,
        "readings": [
            {
                "reading_id": f"reading-{now_ms}-{index}",
                "measured_at_ms": now_ms - (count - 1 - index) * STEP_MS,
                "glucose_mg_dl": 112 + index * 0.08,
                "trend_mg_dl_min": 0.0,
                "sensor_id": "sensor-a",
                "sensor_generation": "libre",
                "quality": quality,
            }
            for index in range(count)
        ],
    }


def create_confirmed_meal(
    client,
    headers,
    fake_chat_analyzer,
    occurred_at_ms: int,
    *,
    speed: float | None = None,
    peak_minutes: int | None = None,
    duration_minutes: int | None = None,
    absorption_confidence: float | None = None,
    name: str = "Test meal",
    carbs: float = 45,
):
    fake_chat_analyzer.results.append(
        MealChatModelResult(
            assistant_message="Ready to save.",
            proposal=MealChatProposal(
                meal_name=name,
                meal_description=name,
                total_portion_g=180,
                estimated_carbs_g=carbs,
                carbs_low_g=max(0, carbs - 8),
                carbs_high_g=carbs + 8,
                confidence=0.78,
                absorption_speed=speed,
                absorption_peak_minutes=peak_minutes,
                absorption_duration_minutes=duration_minutes,
                absorption_confidence=(
                    absorption_confidence
                    if absorption_confidence is not None
                    else (0.66 if speed is not None else None)
                ),
                estimated_protein_g=8,
                estimated_fat_g=7,
                estimated_fiber_g=4,
                items=[AnalysisItem(name=name, portion_g=180, carbs_g=carbs)],
                warnings=[],
            ),
            ready_to_confirm=True,
        )
    )
    session_response = client.post(
        "/v1/meal-chat/sessions",
        headers=headers,
        json={"client_event_id": str(uuid4()), "occurred_at_ms": occurred_at_ms},
    )
    assert session_response.status_code == 200
    session_id = session_response.json()["id"]
    turn = client.post(
        f"/v1/meal-chat/sessions/{session_id}/messages",
        headers=headers,
        data={"text": name},
    )
    assert turn.status_code == 200
    confirmed = client.post(
        f"/v1/meal-chat/sessions/{session_id}/confirm", headers=headers
    )
    assert confirmed.status_code == 200
    return confirmed.json()


def test_glucose_ingestion_is_idempotent_across_metadata_paths(client, auth_headers):
    now = int(time.time() * 1_000) - 1_000
    payload = reading_batch(now)
    first = client.post("/v1/glucose/readings", headers=auth_headers, json=payload)
    assert first.status_code == 200
    assert first.json()["inserted"] == 24
    assert first.json()["forecast_generated"] is True

    replay = dict(payload["readings"][-1])
    replay.update(
        sensor_id="history-import",
        sensor_generation="different-metadata",
        quality=0.7,
    )
    second = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [replay], "utc_offset_minutes": -120},
    )
    assert second.status_code == 200
    assert second.json()["inserted"] == 0
    assert second.json()["unchanged"] == 1

    changed = {**replay, "glucose_mg_dl": replay["glucose_mg_dl"] + 10}
    conflict = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [changed]},
    )
    assert conflict.status_code == 409


def test_per_reading_utc_offset_overrides_batch_fallback(app, client, auth_headers):
    now = int(time.time() * 1_000) - 1_000
    payload = {
        "utc_offset_minutes": -300,
        "readings": [
            {
                "reading_id": f"dst-before-{now}",
                "measured_at_ms": now - STEP_MS,
                "glucose_mg_dl": 110,
                "utc_offset_minutes": -300,
            },
            {
                "reading_id": f"dst-after-{now}",
                "measured_at_ms": now,
                "glucose_mg_dl": 111,
                "utc_offset_minutes": -240,
            },
            {
                "reading_id": f"legacy-offset-{now}",
                "measured_at_ms": now - 2 * STEP_MS,
                "glucose_mg_dl": 109,
            },
        ],
    }
    response = client.post(
        "/v1/glucose/readings", headers=auth_headers, json=payload
    )
    assert response.status_code == 200

    with app.state.database.session_factory() as session:
        offsets = {
            row.reading_id: row.utc_offset_minutes
            for row in session.scalars(
                select(GlucoseReadingRecord).where(
                    GlucoseReadingRecord.reading_id.in_(
                        [item["reading_id"] for item in payload["readings"]]
                    )
                )
            )
        }
    assert offsets[f"dst-before-{now}"] == -300
    assert offsets[f"dst-after-{now}"] == -240
    assert offsets[f"legacy-offset-{now}"] == -300


def test_current_forecast_is_direct_24_step_120_minute_distribution(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    response = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json=reading_batch(anchor),
    )
    assert response.status_code == 200
    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert forecast["status"] == "cold_start"
    assert forecast["based_on_reading_at_ms"] == anchor
    assert forecast["horizon_minutes"] == 120
    assert len(forecast["points"]) == 24
    assert forecast["points"][0]["at_ms"] == anchor + STEP_MS
    assert forecast["points"][-1]["at_ms"] == anchor + 120 * 60_000
    for point in forecast["points"]:
        assert point["low_mg_dl"] <= point["median_mg_dl"] <= point["high_mg_dl"]
    widths = [
        point["high_mg_dl"] - point["low_mg_dl"] for point in forecast["points"]
    ]
    assert widths == sorted(widths)


def test_confirmed_intake_after_reading_anchor_changes_only_eligible_future_points(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 90_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor)
    ).status_code == 200
    before = client.get("/v1/forecast/current", headers=auth_headers).json()
    event = client.post(
        "/v1/insulin-events",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": anchor + 30_000,
            "insulin_units": 8,
            "insulin_name": "NovoRapid",
        },
    )
    assert event.status_code == 200
    after = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert after["based_on_reading_at_ms"] == anchor
    assert after["points"] != before["points"]
    assert after["points"][-1]["median_mg_dl"] < before["points"][-1]["median_mg_dl"]
    activity = next(item for item in after["activities"] if item["kind"] == "rapid")
    assert activity["start_ms"] == anchor + 30_000


def test_truly_future_event_never_leaks_into_current_forecast(client, auth_headers):
    now = int(time.time() * 1_000)
    anchor = now - 1_000
    client.post("/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor))
    before = client.get("/v1/forecast/current", headers=auth_headers).json()
    event = client.post(
        "/v1/insulin-events",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": now + 8 * 60_000,
            "insulin_units": 8,
            "insulin_name": "NovoRapid",
        },
    )
    assert event.status_code == 200
    after = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert after["points"] == before["points"]
    assert after["activities"] == []


def test_confirmed_meal_moves_forecast_up_and_absorption_is_visible(
    client, auth_headers, fake_chat_analyzer
):
    anchor = int(time.time() * 1_000) - 1_000
    client.post("/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor))
    baseline = client.get("/v1/forecast/current", headers=auth_headers).json()
    create_confirmed_meal(
        client, auth_headers, fake_chat_analyzer, anchor, speed=0.85, carbs=55
    )
    meal_forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert meal_forecast["points"][-1]["median_mg_dl"] > baseline["points"][-1]["median_mg_dl"]
    meal = next(item for item in meal_forecast["activities"] if item["kind"] == "meal")
    assert meal["absorption_speed"] == 0.85
    assert meal["start_ms"] == anchor
    assert meal["start_ms"] < meal["peak_ms"] < meal["end_ms"]


def test_unconfirmed_meal_draft_never_enters_forecast(
    client, auth_headers, fake_chat_analyzer
):
    anchor = int(time.time() * 1_000) - 1_000
    client.post("/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor))
    before = client.get("/v1/forecast/current", headers=auth_headers).json()
    fake_chat_analyzer.results.append(
        MealChatModelResult(
            assistant_message="Ready to save.",
            proposal=MealChatProposal(
                meal_name="Unsaved cake",
                meal_description="Unsaved cake",
                total_portion_g=100,
                estimated_carbs_g=50,
                carbs_low_g=40,
                carbs_high_g=60,
                confidence=0.7,
                absorption_speed=0.9,
                items=[AnalysisItem(name="Cake", portion_g=100, carbs_g=50)],
                warnings=[],
            ),
            ready_to_confirm=True,
        )
    )
    created = client.post(
        "/v1/meal-chat/sessions",
        headers=auth_headers,
        json={"client_event_id": str(uuid4()), "occurred_at_ms": anchor},
    ).json()
    turn = client.post(
        f"/v1/meal-chat/sessions/{created['id']}/messages",
        headers=auth_headers,
        data={"text": "cake"},
    )
    assert turn.status_code == 200
    after = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert after["points"] == before["points"]
    assert after["activities"] == []


def test_rapid_moves_forecast_down_and_long_profile_is_lower_and_wider(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    client.post("/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor))
    baseline = client.get("/v1/forecast/current", headers=auth_headers).json()
    rapid = client.post(
        "/v1/insulin-events",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": anchor,
            "insulin_units": 8,
            "insulin_name": "NovoRapid",
        },
    )
    assert rapid.status_code == 200
    long = client.post(
        "/v1/insulin-events",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": anchor,
            "insulin_units": 8,
            "insulin_name": "Tresiba",
        },
    )
    assert long.status_code == 200
    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert forecast["points"][-1]["median_mg_dl"] < baseline["points"][-1]["median_mg_dl"]
    rapid_activity = next(item for item in forecast["activities"] if item["kind"] == "rapid")
    long_activity = next(item for item in forecast["activities"] if item["kind"] == "long")
    assert long_activity["strength"] < rapid_activity["strength"]
    assert long_activity["peak_ms"] > rapid_activity["peak_ms"]
    assert long_activity["end_ms"] > rapid_activity["end_ms"]
    assert long_activity["end_ms"] > anchor + 120 * 60_000


@pytest.mark.parametrize(
    ("kind", "amount", "unit", "direction", "expected_source"),
    [
        ("meal", 55.0, "g", 1, "ai_estimate"),
        ("rapid", 8.0, "U", -1, "population_prior"),
        ("long", 18.0, "U", -1, "population_prior"),
    ],
)
def test_activity_points_use_same_bounded_causal_event_profile(
    client,
    auth_headers,
    fake_chat_analyzer,
    kind,
    amount,
    unit,
    direction,
    expected_source,
):
    anchor = int(time.time() * 1_000) - 1_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor)
    ).status_code == 200
    if kind == "meal":
        event = create_confirmed_meal(
            client,
            auth_headers,
            fake_chat_analyzer,
            anchor,
            speed=0.85,
            carbs=amount,
        )
    else:
        created = client.post(
            "/v1/insulin-events",
            headers=auth_headers,
            json={
                "client_event_id": str(uuid4()),
                "occurred_at_ms": anchor,
                "insulin_units": amount,
                "insulin_name": "NovoRapid" if kind == "rapid" else "Tresiba",
            },
        )
        assert created.status_code == 200
        event = created.json()

    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    activity = next(
        item for item in forecast["activities"] if item["event_id"] == event["id"]
    )
    assert activity["kind"] == kind
    assert activity["amount"] == amount
    assert activity["unit"] == unit
    assert activity["profile_source"] == expected_source
    assert activity["profile_confidence"] == activity["confidence"]
    assert activity["start_ms"] == anchor
    assert activity["start_ms"] < activity["peak_ms"] < activity["end_ms"]

    points = activity["points"]
    assert len(points) == 25
    assert [point["minutes_from_anchor"] for point in points] == list(range(0, 125, 5))
    assert [point["at_ms"] for point in points] == [
        anchor + minute * 60_000 for minute in range(0, 125, 5)
    ]
    assert points[0]["contribution_mg_dl"] == 0
    assert points[0]["activity"] == 0
    contributions = [point["contribution_mg_dl"] for point in points]
    activity_values = [point["activity"] for point in points]
    assert all(math.isfinite(value) and -600 <= value <= 600 for value in contributions)
    assert all(math.isfinite(value) and 0 <= value <= 1 for value in activity_values)
    assert all(
        value >= 0 if direction > 0 else value <= 0 for value in contributions
    )
    assert contributions[-1] * direction > 0
    assert max(activity_values) > 0
    peak_from_anchor = (activity["peak_ms"] - anchor) / 60_000
    if peak_from_anchor <= 120:
        max_point = max(points, key=lambda point: point["activity"])
        assert abs(max_point["minutes_from_anchor"] - peak_from_anchor) <= 5
        assert max_point["activity"] > 0.9


def test_zero_carb_meal_remains_auditable_but_is_not_an_activity(
    client, auth_headers, fake_chat_analyzer
):
    anchor = int(time.time() * 1_000) - 1_000
    assert client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json=reading_batch(anchor),
    ).status_code == 200
    event = create_confirmed_meal(
        client,
        auth_headers,
        fake_chat_analyzer,
        anchor,
        carbs=0,
        name="Carbohydrate-free record",
    )
    assert event["carbs_g"] == 0
    response = client.get("/v1/forecast/current", headers=auth_headers)
    assert response.status_code == 200
    assert all(
        activity["event_id"] != event["id"]
        for activity in response.json()["activities"]
    )


def test_activity_points_have_no_pre_event_effect(client, auth_headers):
    now = int(time.time() * 1_000)
    anchor = now - 10 * 60_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor)
    ).status_code == 200
    baseline = client.get("/v1/forecast/current", headers=auth_headers).json()
    occurred_at = anchor + 8 * 60_000
    event = client.post(
        "/v1/insulin-events",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": occurred_at,
            "insulin_units": 6,
            "insulin_name": "NovoRapid",
        },
    )
    assert event.status_code == 200
    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    activity = next(
        item
        for item in forecast["activities"]
        if item["event_id"] == event.json()["id"]
    )
    by_minute = {point["minutes_from_anchor"]: point for point in activity["points"]}
    for minute in (0, 5):
        assert by_minute[minute]["contribution_mg_dl"] == 0
        assert by_minute[minute]["activity"] == 0
    assert by_minute[10]["contribution_mg_dl"] < 0
    assert by_minute[10]["activity"] > 0
    assert forecast["points"][0] == baseline["points"][0]
    assert forecast["points"][1]["median_mg_dl"] < baseline["points"][1]["median_mg_dl"]


@pytest.mark.parametrize("kind", ["meal", "rapid", "long"])
def test_soft_delete_removes_event_and_regenerates_current_forecast(
    app, client, auth_headers, fake_chat_analyzer, kind
):
    anchor = int(time.time() * 1_000) - 1_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor)
    ).status_code == 200
    if kind == "meal":
        event = create_confirmed_meal(
            client, auth_headers, fake_chat_analyzer, anchor, speed=0.8, carbs=60
        )
    else:
        created = client.post(
            "/v1/insulin-events",
            headers=auth_headers,
            json={
                "client_event_id": str(uuid4()),
                "occurred_at_ms": anchor,
                "insulin_units": 8 if kind == "rapid" else 18,
                "insulin_name": "NovoRapid" if kind == "rapid" else "Tresiba",
            },
        )
        assert created.status_code == 200
        event = created.json()
    with_event = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert any(item["event_id"] == event["id"] for item in with_event["activities"])
    with app.state.database.session_factory() as session:
        run_count_before = session.scalar(select(func.count(ForecastRunRecord.id)))

    deleted = client.delete(f"/v1/intakes/{event['id']}", headers=auth_headers)
    assert deleted.status_code == 200
    tombstone = deleted.json()
    assert tombstone["deleted"] is True
    repeated = client.delete(f"/v1/intakes/{event['id']}", headers=auth_headers)
    assert repeated.status_code == 200
    assert repeated.json() == tombstone

    assert client.get("/v1/intakes", headers=auth_headers).json()["items"] == []
    audit_items = client.get(
        "/v1/intakes", params={"include_deleted": True}, headers=auth_headers
    ).json()["items"]
    assert audit_items == [tombstone]
    without_event = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert all(
        item["event_id"] != event["id"] for item in without_event["activities"]
    )
    assert without_event["points"] != with_event["points"]
    with app.state.database.session_factory() as session:
        assert session.scalar(select(func.count(ForecastRunRecord.id))) == run_count_before + 1
        assert session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        ) == 0
        if kind == "meal":
            assert event["analysis_id"] is not None
            assert session.get(AnalysisRecord, event["analysis_id"]) is not None


def test_continuous_absorption_speed_shifts_peak_and_end(
    client, auth_headers, fake_chat_analyzer
):
    anchor = int(time.time() * 1_000) - 1_000
    client.post("/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor))
    fast = create_confirmed_meal(
        client,
        auth_headers,
        fake_chat_analyzer,
        anchor,
        speed=0.92,
        name="Fast meal",
    )
    slow = create_confirmed_meal(
        client,
        auth_headers,
        fake_chat_analyzer,
        anchor,
        speed=0.12,
        name="Slow meal",
    )
    activities = client.get("/v1/forecast/current", headers=auth_headers).json()["activities"]
    by_id = {item["event_id"]: item for item in activities}
    fast_activity = by_id[fast["id"]]
    slow_activity = by_id[slow["id"]]
    assert fast_activity["peak_ms"] < slow_activity["peak_ms"]
    assert fast_activity["end_ms"] < slow_activity["end_ms"]


def test_same_timestamp_events_remain_independent_in_sync_forecast_and_delete(
    app, client, auth_headers, fake_chat_analyzer
):
    """A graph point is a presentation cluster, never a backend aggregate identity."""

    anchor = int(time.time() * 1_000) - 1_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor)
    ).status_code == 200
    baseline = client.get("/v1/forecast/current", headers=auth_headers).json()

    fast = create_confirmed_meal(
        client,
        auth_headers,
        fake_chat_analyzer,
        anchor,
        speed=0.91,
        peak_minutes=35,
        duration_minutes=105,
        absorption_confidence=0.83,
        carbs=18,
        name="Sweet drink",
    )
    slow = create_confirmed_meal(
        client,
        auth_headers,
        fake_chat_analyzer,
        anchor,
        speed=0.18,
        peak_minutes=128,
        duration_minutes=345,
        absorption_confidence=0.71,
        carbs=27,
        name="Buckwheat",
    )
    insulin_response = client.post(
        "/v1/insulin-events",
        headers=auth_headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": anchor,
            "insulin_units": 1.25,
            "insulin_name": "NovoRapid",
        },
    )
    assert insulin_response.status_code == 200
    insulin = insulin_response.json()

    event_ids = {fast["id"], slow["id"], insulin["id"]}
    assert len(event_ids) == 3
    assert {fast["client_event_id"], slow["client_event_id"], insulin["client_event_id"]}
    assert all(event["occurred_at_ms"] == anchor for event in (fast, slow, insulin))
    assert (
        fast["absorption_speed"],
        fast["absorption_peak_minutes"],
        fast["absorption_duration_minutes"],
        fast["absorption_confidence"],
    ) == (0.91, 35, 105, 0.83)
    assert (
        slow["absorption_speed"],
        slow["absorption_peak_minutes"],
        slow["absorption_duration_minutes"],
        slow["absorption_confidence"],
    ) == (0.18, 128, 345, 0.71)
    assert insulin["absorption_speed"] is None
    assert insulin["absorption_peak_minutes"] is None
    assert insulin["absorption_duration_minutes"] is None
    assert insulin["absorption_confidence"] is None

    with app.state.database.session_factory() as session:
        persisted_profiles = {
            analysis_id: json.loads(session.get(AnalysisRecord, analysis_id).result_json)
            for analysis_id in (fast["analysis_id"], slow["analysis_id"])
        }
    assert persisted_profiles[fast["analysis_id"]]["absorption_speed"] == 0.91
    assert persisted_profiles[fast["analysis_id"]]["absorption_peak_minutes"] == 35
    assert persisted_profiles[fast["analysis_id"]]["absorption_duration_minutes"] == 105
    assert persisted_profiles[fast["analysis_id"]]["absorption_confidence"] == 0.83
    assert persisted_profiles[slow["analysis_id"]]["absorption_speed"] == 0.18

    sync = client.get(
        "/v1/intakes",
        params={"after_sync_version": 0},
        headers=auth_headers,
    )
    assert sync.status_code == 200
    synced_by_id = {item["id"]: item for item in sync.json()["items"]}
    assert set(synced_by_id) == event_ids
    assert synced_by_id[fast["id"]] == fast
    assert synced_by_id[slow["id"]] == slow
    assert synced_by_id[insulin["id"]] == insulin

    combined = client.get("/v1/forecast/current", headers=auth_headers).json()
    activities_by_id = {item["event_id"]: item for item in combined["activities"]}
    assert set(activities_by_id) == event_ids
    assert activities_by_id[fast["id"]]["absorption_speed"] == 0.91
    assert activities_by_id[slow["id"]]["absorption_speed"] == 0.18
    assert activities_by_id[insulin["id"]]["kind"] == "rapid"

    # The baseline event path is additive: each UUID contributes independently,
    # even though all three have the exact same physiological timestamp.
    for index, (before_point, combined_point) in enumerate(
        zip(baseline["points"], combined["points"]), start=1
    ):
        expected_delta = sum(
            activity["points"][index]["contribution_mg_dl"]
            for activity in activities_by_id.values()
        )
        actual_delta = combined_point["median_mg_dl"] - before_point["median_mg_dl"]
        assert actual_delta == pytest.approx(expected_delta, abs=0.006)

    deleted = client.delete(f"/v1/intakes/{fast['id']}", headers=auth_headers)
    assert deleted.status_code == 200
    assert deleted.json()["deleted"] is True
    assert deleted.json()["id"] == fast["id"]

    active = client.get("/v1/intakes", headers=auth_headers).json()["items"]
    assert {item["id"] for item in active} == {slow["id"], insulin["id"]}
    audit = client.get(
        "/v1/intakes", params={"include_deleted": True}, headers=auth_headers
    ).json()["items"]
    assert {item["id"] for item in audit} == event_ids
    assert sum(item["deleted"] for item in audit) == 1

    after_delete = client.get("/v1/forecast/current", headers=auth_headers).json()
    remaining_activities = {
        item["event_id"]: item for item in after_delete["activities"]
    }
    assert set(remaining_activities) == {slow["id"], insulin["id"]}
    for index, (before_point, current_point) in enumerate(
        zip(baseline["points"], after_delete["points"]), start=1
    ):
        expected_delta = sum(
            activity["points"][index]["contribution_mg_dl"]
            for activity in remaining_activities.values()
        )
        actual_delta = current_point["median_mg_dl"] - before_point["median_mg_dl"]
        assert actual_delta == pytest.approx(expected_delta, abs=0.006)


def test_stale_and_low_quality_data_fail_visibly(client, auth_headers):
    stale_anchor = int(time.time() * 1_000) - 20 * 60_000
    client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(stale_anchor)
    )
    stale = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert stale["status"] == "stale"
    assert stale["points"] == []
    assert stale["confidence"] == 0


def test_sparse_low_quality_data_widens_and_marks_forecast(client, auth_headers):
    anchor = int(time.time() * 1_000) - 1_000
    payload = reading_batch(anchor, count=4, quality=0.2)
    client.post("/v1/glucose/readings", headers=auth_headers, json=payload)
    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert forecast["status"] == "low_confidence"
    assert len(forecast["points"]) == 24
    assert forecast["confidence"] < 0.3


def test_one_minute_cgm_is_resampled_to_full_two_hour_history(client, auth_headers):
    anchor = int(time.time() * 1_000) - 1_000
    count = 121
    payload = {
        "readings": [
            {
                "reading_id": f"one-minute-{index}",
                "measured_at_ms": anchor - (count - 1 - index) * 60_000,
                "glucose_mg_dl": 108 + 3 * math.sin(index / 15.0),
                "quality": 1,
            }
            for index in range(count)
        ]
    }
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=payload
    ).status_code == 200
    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert forecast["status"] == "cold_start"
    assert len(forecast["points"]) == 24
    assert forecast["points"][0]["at_ms"] == anchor + STEP_MS
    assert forecast["points"][-1]["at_ms"] == anchor + 120 * 60_000


def test_twenty_four_one_minute_rows_are_not_mistaken_for_two_hours(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    count = 24
    payload = {
        "readings": [
            {
                "reading_id": f"short-one-minute-{index}",
                "measured_at_ms": anchor - (count - 1 - index) * 60_000,
                "glucose_mg_dl": 110,
                "quality": 1,
            }
            for index in range(count)
        ]
    }
    client.post("/v1/glucose/readings", headers=auth_headers, json=payload)
    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert forecast["status"] == "low_confidence"
    assert forecast["confidence"] < 0.4


def test_one_minute_cgm_produces_windows_without_automatic_training(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    count = 700
    payload = {
        "readings": [
            {
                "reading_id": f"training-one-minute-{index}",
                "measured_at_ms": anchor - (count - 1 - index) * 60_000,
                "glucose_mg_dl": 116 + 9 * math.sin(index / 37.0),
                "quality": 1,
            }
            for index in range(count)
        ]
    }
    response = client.post(
        "/v1/glucose/readings", headers=auth_headers, json=payload
    )
    assert response.status_code == 200
    status = client.get("/v1/forecast/status", headers=auth_headers).json()
    # About 11 hours is enough to construct causal windows, but intentionally not
    # enough evidence for a manual static build. Ingest never creates an attempt.
    assert status["training"]["last_trained_at_ms"] is None
    assert status["training"]["mode"] == "manual"
    assert status["training"]["automatic_enabled"] is False
    assert status["capabilities"]["personal_model_active"] is False
    with app.state.database.session_factory() as session:
        rows = app.state.forecast_service._load_readings(session)
        assert len(app.state.forecast_service._training_windows(rows)) >= 88
        assert session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        ) == 0


def test_forecasts_are_scored_only_after_actual_reading_arrives(client, auth_headers):
    anchor = int(time.time() * 1_000) - STEP_MS
    client.post("/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor))
    assert client.get("/v1/forecast/status", headers=auth_headers).json()["accuracy"][
        "scored_points"
    ] == 0
    actual = {
        "reading_id": "new-actual",
        "measured_at_ms": anchor + STEP_MS,
        "glucose_mg_dl": 118,
        "trend_mg_dl_min": 0.1,
        "quality": 1,
    }
    response = client.post(
        "/v1/glucose/readings", headers=auth_headers, json={"readings": [actual]}
    )
    assert response.status_code == 200
    accuracy = client.get("/v1/forecast/status", headers=auth_headers).json()["accuracy"]
    assert accuracy["scored_points"] >= 1
    assert accuracy["mae_7d_mg_dl"] is not None
    assert accuracy["mae_30d_mg_dl"] is not None


def test_mobile_training_route_is_absent_even_with_sufficient_history(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    count = 1000
    payload = {
        "readings": [
            {
                "reading_id": f"train-{index}",
                "measured_at_ms": anchor - (count - 1 - index) * STEP_MS,
                "glucose_mg_dl": 115 + 12 * math.sin(index / 13.0) + 0.03 * index,
                "trend_mg_dl_min": None,
                "quality": 1,
            }
            for index in range(count)
        ]
    }
    ingested = client.post("/v1/glucose/readings", headers=auth_headers, json=payload)
    assert ingested.status_code == 200
    assert "/v1/forecast/train" not in {
        route.path for route in client.app.routes if hasattr(route, "path")
    }
    trained = client.post("/v1/forecast/train", headers=auth_headers)
    assert trained.status_code == 404
    with app.state.database.session_factory() as session:
        assert session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        ) == 0
    status = client.get("/v1/forecast/status", headers=auth_headers).json()
    assert status["training"]["last_trained_at_ms"] is None
    assert status["training"]["automatic_enabled"] is False
    assert status["capabilities"]["ready_for_display"] is False


def test_candidate_gate_promotes_only_a_safe_chronological_improvement():
    champion = {
        "mae": 20.0, "rmse": 25.0, "mae_30": 12.0, "mae_60": 20.0,
        "mae_120": 35.0, "coverage_80": 0.8, "mean_interval_width": 55.0,
        "hypo_miss_rate": 0.2,
    }
    improved = {
        "mae": 17.0, "rmse": 21.0, "mae_30": 11.0, "mae_60": 18.0,
        "mae_120": 32.0, "coverage_80": 0.79, "mean_interval_width": 52.0,
        "hypo_miss_rate": 0.18,
    }
    assert ForecastService.candidate_is_promotable(improved, champion) is True


def test_candidate_gate_rejects_regression_or_non_finite_output():
    champion = {
        "mae": 20.0, "rmse": 25.0, "mae_30": 12.0, "mae_60": 20.0,
        "mae_120": 35.0, "coverage_80": 0.8, "mean_interval_width": 55.0,
        "hypo_miss_rate": 0.2,
    }
    regressed_horizon = {
        "mae": 18.0,
        "rmse": 22.0,
        "mae_30": 11.0,
        "mae_60": 18.0,
        "mae_120": 45.0,
        "coverage_80": 0.82,
        "mean_interval_width": 54.0,
        "hypo_miss_rate": 0.18,
    }
    assert ForecastService.candidate_is_promotable(regressed_horizon, champion) is False
    assert ForecastService.candidate_is_promotable(champion, champion, finite=False) is False


def test_gappy_completed_backfill_never_creates_a_training_attempt(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    count = 900
    gappy = {
        "backfill_complete": True,
        "readings": [
            {
                "reading_id": f"gappy-{index}",
                "measured_at_ms": anchor - (count - 1 - index) * 10 * 60_000,
                "glucose_mg_dl": 110,
                "quality": 1,
            }
            for index in range(count)
        ]
    }
    response = client.post("/v1/glucose/readings", headers=auth_headers, json=gappy)
    assert response.status_code == 200
    with app.state.database.session_factory() as session:
        first_attempts = session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        )
    assert first_attempts == 0

    response = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={
            "readings": [
                {
                    "reading_id": "gappy-next",
                    "measured_at_ms": anchor + STEP_MS,
                    "glucose_mg_dl": 111,
                    "quality": 1,
                }
            ]
        },
    )
    assert response.status_code == 200
    with app.state.database.session_factory() as session:
        second_attempts = session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        )
    assert second_attempts == first_attempts == 0


def test_unobserved_event_kinds_keep_independent_low_confidence(monkeypatch):
    service = ForecastService()

    def evidence(_readings, _events, kind):
        if kind == "meal":
            return [45.0, 50.0, 55.0, 60.0], [0.8, 0.9, 1.0, 0.85]
        return [], []

    monkeypatch.setattr(service, "_event_aligned_effects", evidence)
    parameters = service._personalized_parameters([], [])
    profiles = parameters["profiles"]
    assert profiles["meal_profile_confidence"] > 0.18
    assert profiles["rapid_profile_confidence"] == 0.18
    assert profiles["long_profile_confidence"] == 0.12
    assert parameters["evidence_counts"] == {"meal": 4, "rapid": 0, "long": 0}


def test_retention_prunes_only_derived_runs_and_has_score_query_index(
    app, client, auth_headers
):
    now = int(time.time() * 1_000) - 1_000
    client.post("/v1/glucose/readings", headers=auth_headers, json=reading_batch(now))
    with app.state.database.session_factory() as session:
        recent_count = session.scalar(select(func.count(ForecastRunRecord.id)))
        reading = session.scalar(select(GlucoseReadingRecord))
        assert reading is not None
        old_run = ForecastRunRecord(
            id=str(uuid4()),
            generated_at_ms=now - 36 * 86_400_000,
            based_on_reading_at_ms=reading.measured_at_ms,
            model_version=BASELINE_VERSION,
            horizon_minutes=120,
            confidence=0.2,
            status="cold_start",
            conditional_notice="test",
            input_hash="a" * 64,
            activities_json="[]",
        )
        session.add(old_run)
        session.flush()
        session.add(
            ForecastPointRecord(
                run_id=old_run.id,
                step_minutes=5,
                at_ms=reading.measured_at_ms + STEP_MS,
                median_mg_dl=110,
                low_mg_dl=90,
                high_mg_dl=130,
            )
        )
        session.flush()
        session.add(
            ForecastScoreRecord(
                run_id=old_run.id,
                step_minutes=5,
                model_version=BASELINE_VERSION,
                forecast_at_ms=reading.measured_at_ms + STEP_MS,
                reading_id=reading.reading_id,
                actual_mg_dl=112,
                residual_mg_dl=2,
                absolute_error_mg_dl=2,
                squared_error=4,
                inside_interval=1,
                scored_at_ms=now - 36 * 86_400_000,
            )
        )
        marker = session.get(ForecastMaintenanceRecord, "last_prune")
        assert marker is not None
        marker.value_ms = 0
        session.commit()
        assert app.state.forecast_service.prune(session, now_ms=now + 1_000) == 1
        assert session.get(ForecastRunRecord, old_run.id) is None
        assert session.get(ForecastScoreRecord, (old_run.id, 5)) is None
        assert session.scalar(select(func.count(ForecastRunRecord.id))) == recent_count
    index_names = {index.name for index in ForecastScoreRecord.__table__.indexes}
    assert "ix_forecast_scores_model_step_scored" in index_names
    assert client.get("/v1/forecast/status", headers=auth_headers).status_code == 200


def test_versioned_forecast_tables_upgrade_legacy_preview_database(tmp_path):
    database_path = tmp_path / "legacy-preview.db"
    with sqlite3.connect(database_path) as connection:
        connection.executescript(
            """
            CREATE TABLE forecast_scores (
                run_id TEXT NOT NULL,
                step_minutes INTEGER NOT NULL,
                scored_at_ms INTEGER NOT NULL,
                PRIMARY KEY (run_id, step_minutes)
            );
            CREATE TABLE forecast_calibration (
                step_minutes INTEGER PRIMARY KEY,
                residual_bias_mg_dl REAL NOT NULL,
                residual_variance REAL NOT NULL,
                sample_count INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            );
            """
        )
    application = create_app(make_settings(tmp_path, database_path=database_path), analyzer=FakeAnalyzer())
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}
    with TestClient(application) as local_client:
        anchor = int(time.time() * 1_000) - STEP_MS
        response = local_client.post(
            "/v1/glucose/readings", headers=headers, json=reading_batch(anchor)
        )
        assert response.status_code == 200
        actual = {
            "reading_id": "legacy-upgrade-actual",
            "measured_at_ms": anchor + STEP_MS,
            "glucose_mg_dl": 118,
            "quality": 1,
        }
        assert local_client.post(
            "/v1/glucose/readings", headers=headers, json={"readings": [actual]}
        ).status_code == 200
        assert local_client.get("/v1/forecast/current", headers=headers).status_code == 200
        assert local_client.get("/v1/forecast/status", headers=headers).status_code == 200
    with sqlite3.connect(database_path) as connection:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
    assert {"forecast_scores", "forecast_calibration"}.issubset(tables)
    assert {"forecast_scores_v2", "forecast_calibration_v2"}.issubset(tables)


def test_live_scoring_updates_accuracy_without_mutating_online_calibration(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 2 * STEP_MS
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor)
    ).status_code == 200
    frozen_state = (7.5, 225.0, 17, anchor - 1_000)
    with app.state.database.session_factory() as session:
        baseline = session.get(ForecastModelRecord, BASELINE_VERSION)
        assert baseline is not None
        session.add(
            ForecastCalibrationRecord(
                model_version=BASELINE_VERSION,
                step_minutes=5,
                residual_bias_mg_dl=frozen_state[0],
                residual_variance=frozen_state[1],
                sample_count=frozen_state[2],
                updated_at_ms=frozen_state[3],
            )
        )
        session.commit()

    scored = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={
            "readings": [{
                "reading_id": "live-score-actual",
                "measured_at_ms": anchor + STEP_MS,
                "glucose_mg_dl": 116,
                "quality": 1,
            }]
        },
    )
    assert scored.status_code == 200
    with app.state.database.session_factory() as session:
        calibration = session.get(
            ForecastCalibrationRecord, (BASELINE_VERSION, 5)
        )
        assert calibration is not None
        assert (
            calibration.residual_bias_mg_dl,
            calibration.residual_variance,
            calibration.sample_count,
            calibration.updated_at_ms,
        ) == frozen_state
        active_scores = session.scalar(
            select(func.count(ForecastScoreRecord.run_id)).where(
                ForecastScoreRecord.model_version == BASELINE_VERSION
            )
        )
        assert active_scores >= 1
    accuracy = client.get("/v1/forecast/status", headers=auth_headers).json()["accuracy"]
    assert accuracy["scored_points"] == active_scores


def _synthetic_event_readings(
    start_ms: int, end_ms: int, events: list[_Event]
) -> list[GlucoseReadingRecord]:
    parameters = _default_parameters()
    causal_start = start_ms - 96 * 60 * 60_000
    readings: list[GlucoseReadingRecord] = []
    for index, measured_at_ms in enumerate(range(start_ms, end_ms + 1, STEP_MS)):
        glucose = 135.0 + sum(
            _event_glucose_increment(
                event, causal_start, measured_at_ms, parameters
            )
            for event in events
        )
        readings.append(
            GlucoseReadingRecord(
                reading_id=f"synthetic-{start_ms}-{index}",
                measured_at_ms=measured_at_ms,
                glucose_mg_dl=glucose,
                trend_mg_dl_min=None,
                payload_hash=f"{index:064x}"[-64:],
                received_at_ms=end_ms,
            )
        )
    return readings


def test_meal_and_normal_prebolus_both_remain_personalization_evidence():
    origin = 1_800_000_000_000
    events: list[_Event] = []
    for index in range(3):
        meal_at = origin + (2 + index * 8) * 60 * 60_000
        events.extend(
            [
                _Event(
                    event_id=f"rapid-{index}",
                    occurred_at_ms=meal_at - 15 * 60_000,
                    kind="rapid",
                    label="NovoRapid",
                    amount=4,
                ),
                _Event(
                    event_id=f"meal-{index}",
                    occurred_at_ms=meal_at,
                    kind="meal",
                    label="Meal",
                    amount=40,
                ),
            ]
        )
    readings = _synthetic_event_readings(
        origin, origin + 28 * 60 * 60_000, events
    )
    service = ForecastService()
    meal_peaks, meal_effects = service._event_aligned_effects(readings, events, "meal")
    rapid_peaks, rapid_effects = service._event_aligned_effects(readings, events, "rapid")
    assert len(meal_effects) == len(meal_peaks) == 3
    assert len(rapid_effects) == len(rapid_peaks) == 3
    parameters = service._personalized_parameters(readings, events)
    assert parameters["evidence_counts"]["meal"] == 3
    assert parameters["evidence_counts"]["rapid"] == 3


def test_daily_tresiba_events_produce_stable_long_profile_evidence():
    origin = 1_810_000_000_000
    events = [
        _Event(
            event_id=f"long-{index}",
            occurred_at_ms=origin + (1 + index * 24) * 60 * 60_000,
            kind="long",
            label="Tresiba",
            amount=18,
        )
        for index in range(3)
    ]
    readings = _synthetic_event_readings(
        origin, origin + 98 * 60 * 60_000, events
    )
    peaks, effects = ForecastService._event_aligned_effects(readings, events, "long")
    assert len(effects) == len(peaks) == 3
    assert all(math.isfinite(value) and 0.15 <= value <= 12 for value in effects)
    assert max(effects) - min(effects) < 2.0


def test_meal_estimate_uncertainty_widens_band_and_reduces_confidence():
    anchor = 1_820_000_000_000
    common = {
        "occurred_at_ms": anchor,
        "kind": "meal",
        "label": "Meal",
        "amount": 70,
    }
    manual = _Event(event_id="manual", **common)
    tight = _Event(
        event_id="tight",
        carbs_low_g=68,
        carbs_high_g=72,
        ai_confidence=0.95,
        absorption_confidence=0.9,
        **common,
    )
    uncertain = _Event(
        event_id="uncertain",
        carbs_low_g=30,
        carbs_high_g=120,
        ai_confidence=0.25,
        absorption_confidence=0.25,
        **common,
    )
    manual_sigma, manual_confidence = _meal_event_uncertainty(
        [manual], anchor, _default_parameters()
    )
    tight_sigma, tight_confidence = _meal_event_uncertainty(
        [tight], anchor, _default_parameters()
    )
    uncertain_sigma, uncertain_confidence = _meal_event_uncertainty(
        [uncertain], anchor, _default_parameters()
    )
    assert np_all_greater_equal(tight_sigma, manual_sigma)
    assert np_all_greater_equal(uncertain_sigma, tight_sigma)
    assert uncertain_confidence <= tight_confidence <= manual_confidence
    assert uncertain_sigma[-1] > tight_sigma[-1] > manual_sigma[-1]


def np_all_greater_equal(left, right) -> bool:
    return all(float(a) + 1e-9 >= float(b) for a, b in zip(left, right))


def test_backfill_and_corrections_never_train_automatically(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    all_readings = reading_batch(anchor, count=900)["readings"]
    canonical_index = 700
    canonical_at = all_readings[canonical_index]["measured_at_ms"]
    all_readings[canonical_index]["reading_id"] = f"cgm-{canonical_at}"
    partial = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": all_readings[:500], "backfill_complete": False},
    )
    assert partial.status_code == 200
    live = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [all_readings[-1]]},
    )
    assert live.status_code == 200
    with app.state.database.session_factory() as session:
        assert session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        ) == 0

    complete = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": all_readings[500:], "backfill_complete": True},
    )
    assert complete.status_code == 200
    with app.state.database.session_factory() as session:
        assert session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        ) == 0

    # Repeated completion boundaries remain pure ingest acknowledgements.
    assert client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [all_readings[-1]], "backfill_complete": True},
    ).status_code == 200
    with app.state.database.session_factory() as session:
        assert session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        ) == 0

    # Canonical corrections are rescored for monitoring but still never train.
    corrected = {
        **all_readings[canonical_index],
        "glucose_mg_dl": all_readings[canonical_index]["glucose_mg_dl"] + 4,
    }
    assert client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [corrected], "backfill_complete": True},
    ).status_code == 200
    with app.state.database.session_factory() as session:
        assert session.scalar(
            select(func.count(ForecastModelRecord.version)).where(
                ForecastModelRecord.trained_at_ms.is_not(None)
            )
        ) == 0


def test_empty_backfill_boundary_validation_and_success(client, auth_headers):
    for payload in ({"readings": []}, {"readings": [], "backfill_complete": False}):
        assert client.post(
            "/v1/glucose/readings", headers=auth_headers, json=payload
        ).status_code == 422
    response = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [], "backfill_complete": True},
    )
    assert response.status_code == 200
    assert response.json()["inserted"] == 0


def test_server_instance_id_is_stable_per_database_and_changes_after_recreation(tmp_path):
    database_path = tmp_path / "instance-id.db"
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}

    first_app = create_app(
        make_settings(tmp_path, database_path=database_path), analyzer=FakeAnalyzer()
    )
    with TestClient(first_app) as first_client:
        first_id = first_client.get("/v1/forecast/status", headers=headers).json()[
            "server_instance_id"
        ]
        assert first_client.get("/v1/forecast/status", headers=headers).json()[
            "server_instance_id"
        ] == first_id

    second_app = create_app(
        make_settings(tmp_path, database_path=database_path), analyzer=FakeAnalyzer()
    )
    with TestClient(second_app) as second_client:
        assert second_client.get("/v1/forecast/status", headers=headers).json()[
            "server_instance_id"
        ] == first_id

    database_path.unlink()
    recreated_app = create_app(
        make_settings(tmp_path, database_path=database_path), analyzer=FakeAnalyzer()
    )
    with TestClient(recreated_app) as recreated_client:
        recreated_id = recreated_client.get(
            "/v1/forecast/status", headers=headers
        ).json()["server_instance_id"]
    assert recreated_id != first_id


def test_same_millisecond_cgm_correction_advances_source_revision(
    app, client, auth_headers, monkeypatch
):
    fixed_now_ms = int(time.time() * 1_000)
    measured_at_ms = fixed_now_ms - STEP_MS
    reading_id = f"cgm-{measured_at_ms}"
    reading = {
        "reading_id": reading_id,
        "measured_at_ms": measured_at_ms,
        "glucose_mg_dl": 118,
        "trend_mg_dl_min": 0.0,
        "quality": 1.0,
    }
    monkeypatch.setattr("app.forecast._now_ms", lambda: fixed_now_ms)

    first = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [reading]},
    )
    assert first.status_code == 200
    with app.state.database.session_factory() as session:
        before = app.state.forecast_service._source_revision(session)

    retry = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [reading]},
    )
    assert retry.status_code == 200
    assert retry.json()["unchanged"] == 1
    with app.state.database.session_factory() as session:
        after_retry = app.state.forecast_service._source_revision(session)
    assert after_retry == before

    corrected = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [{**reading, "glucose_mg_dl": 148}]},
    )
    assert corrected.status_code == 200
    assert corrected.json()["updated"] == 1
    with app.state.database.session_factory() as session:
        after = app.state.forecast_service._source_revision(session)

    # Count, measurement horizon, wall-clock receipt maximum and intake
    # aggregates are deliberately identical. Only the durable mutation counter
    # can make this same-millisecond in-place correction observable.
    assert after[:-1] == before[:-1]
    assert after[-1] == before[-1] + 1


def test_canonical_cgm_correction_rescores_without_mutating_calibration(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 2 * STEP_MS
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=reading_batch(anchor)
    ).status_code == 200
    actual_at = anchor + STEP_MS
    reading_id = f"cgm-{actual_at}"
    first_actual = {
        "reading_id": reading_id,
        "measured_at_ms": actual_at,
        "glucose_mg_dl": 118,
        "quality": 1,
    }
    assert client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [first_actual]},
    ).status_code == 200
    with app.state.database.session_factory() as session:
        score_before = session.scalar(
            select(ForecastScoreRecord).where(
                ForecastScoreRecord.reading_id == reading_id
            )
        )
        assert score_before is not None
        residual_before = score_before.residual_mg_dl
        frozen_calibration = (4.25, 196.0, 23, anchor - 12_345)
        session.add(
            ForecastCalibrationRecord(
                model_version=score_before.model_version,
                step_minutes=score_before.step_minutes,
                residual_bias_mg_dl=frozen_calibration[0],
                residual_variance=frozen_calibration[1],
                sample_count=frozen_calibration[2],
                updated_at_ms=frozen_calibration[3],
            )
        )
        session.commit()
        anchored_runs_before = list(
            session.scalars(
                select(ForecastRunRecord).where(
                    ForecastRunRecord.based_on_reading_at_ms == actual_at
                )
            )
        )
        assert len(anchored_runs_before) == 1
        first_hash = anchored_runs_before[0].input_hash

    corrected = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [{**first_actual, "glucose_mg_dl": 148}]},
    )
    assert corrected.status_code == 200
    assert corrected.json()["updated"] == 1
    with app.state.database.session_factory() as session:
        reading = session.get(GlucoseReadingRecord, reading_id)
        assert reading is not None and reading.glucose_mg_dl == 148
        score_after = session.scalar(
            select(ForecastScoreRecord).where(
                ForecastScoreRecord.reading_id == reading_id
            )
        )
        assert score_after is not None
        assert score_after.actual_mg_dl == 148
        assert score_after.residual_mg_dl != residual_before
        calibration = session.get(
            ForecastCalibrationRecord,
            (score_after.model_version, score_after.step_minutes),
        )
        assert calibration is not None
        assert (
            calibration.residual_bias_mg_dl,
            calibration.residual_variance,
            calibration.sample_count,
            calibration.updated_at_ms,
        ) == frozen_calibration
        anchored_runs_after = list(
            session.scalars(
                select(ForecastRunRecord).where(
                    ForecastRunRecord.based_on_reading_at_ms == actual_at
                )
            )
        )
        assert len(anchored_runs_after) == 2
        assert any(run.input_hash != first_hash for run in anchored_runs_after)


def test_legacy_champion_and_colliding_run_are_audit_only(tmp_path):
    database_path = tmp_path / "legacy-engine.db"
    application = create_app(
        make_settings(tmp_path, database_path=database_path), analyzer=FakeAnalyzer()
    )
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}
    anchor = int(time.time() * 1_000) - 1_000
    rows: list[GlucoseReadingRecord] = []
    with TestClient(application) as local_client:
        with application.state.database.session_factory() as session:
            for index, item in enumerate(reading_batch(anchor)["readings"]):
                row = GlucoseReadingRecord(
                    reading_id=item["reading_id"],
                    measured_at_ms=item["measured_at_ms"],
                    glucose_mg_dl=item["glucose_mg_dl"],
                    trend_mg_dl_min=item["trend_mg_dl_min"],
                    sensor_id=item["sensor_id"],
                    sensor_generation=item["sensor_generation"],
                    quality=item["quality"],
                    utc_offset_minutes=180,
                    payload_hash=f"{index + 1:064x}",
                    received_at_ms=anchor,
                )
                rows.append(row)
                session.add(row)
            legacy_version = "personal-legacy-preview"
            session.add(
                ForecastModelRecord(
                    version=legacy_version,
                    status="champion",
                    architecture="personalized-hybrid-mlp-direct-24-v1",
                    created_at_ms=anchor - 1_000,
                    trained_at_ms=anchor - 1_000,
                    promoted_at_ms=anchor - 1_000,
                    training_cutoff_ms=anchor - STEP_MS,
                    sample_count=500,
                    parameters_json=json.dumps(_default_parameters()),
                    metrics_json="{}",
                    decision_reason="legacy preview",
                )
            )
            session.flush()
            colliding_hash = ForecastService._input_hash(
                rows, [], BASELINE_VERSION, {}
            )
            legacy_run_id = str(uuid4())
            session.add(
                ForecastRunRecord(
                    id=legacy_run_id,
                    generated_at_ms=anchor - 500,
                    based_on_reading_at_ms=anchor,
                    model_version=legacy_version,
                    horizon_minutes=120,
                    confidence=0.99,
                    status="ready",
                    conditional_notice="legacy",
                    input_hash=colliding_hash,
                    activities_json="[]",
                )
            )
            session.flush()
            session.add(
                ForecastPointRecord(
                    run_id=legacy_run_id,
                    step_minutes=5,
                    at_ms=anchor + STEP_MS,
                    median_mg_dl=500,
                    low_mg_dl=499,
                    high_mg_dl=501,
                )
            )
            session.commit()

        current = local_client.get("/v1/forecast/current", headers=headers)
        assert current.status_code == 200
        body = current.json()
        assert body["model_version"] == BASELINE_VERSION
        assert len(body["points"]) == 24
        assert body["points"][0]["median_mg_dl"] != 500
        with application.state.database.session_factory() as session:
            legacy = session.get(ForecastModelRecord, legacy_version)
            baseline = session.get(ForecastModelRecord, BASELINE_VERSION)
            assert legacy is not None
            assert baseline is not None
            assert baseline.status == "champion"
            assert baseline.architecture == "event-aware-persistence-prior-v3"
            pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
            assert pin is not None and pin.value_text == BASELINE_VERSION
            assert session.get(ForecastRunRecord, legacy_run_id) is not None
            assert session.scalar(
                select(func.count(ForecastRunRecord.id)).where(
                    ForecastRunRecord.model_version == BASELINE_VERSION
                )
            ) == 1
    assert FORECAST_ENGINE_VERSION == "forecast-engine-v6-static-safe"
