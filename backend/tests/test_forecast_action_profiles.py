from __future__ import annotations

import math
import time
from uuid import uuid4

import numpy as np

from app.forecast import (
    HORIZON_STEPS,
    V3_FEATURE_SCHEMA,
    V3_NETWORK_KIND,
    ForecastService,
    _Event,
    _default_parameters,
    _history_features,
)
from app.models import GlucoseReadingRecord


STEP_MS = 5 * 60_000
ACTION_MODELS = {
    "population_prior",
    "personalized_kernel",
    "contextual_counterfactual",
    "basal_depot",
}
IDENTIFIABILITY_LEVELS = {"low", "medium", "high", "not_identifiable"}
PROFILE_CONTRACT_FIELDS = {
    "onset_ms",
    "peak_low_ms",
    "peak_high_ms",
    "end_low_ms",
    "end_high_ms",
    "attribution_confidence",
    "identifiability",
    "action_model",
    "overlap_count",
}


def _reading_batch(anchor_ms: int, count: int = 24) -> dict:
    return {
        "utc_offset_minutes": 180,
        "readings": [
            {
                "reading_id": f"action-profile-{anchor_ms}-{index}",
                "measured_at_ms": anchor_ms - (count - 1 - index) * STEP_MS,
                "glucose_mg_dl": 118.0 + 0.04 * index,
                "trend_mg_dl_min": 0.0,
                "quality": 1.0,
            }
            for index in range(count)
        ],
    }


def _post_insulin(client, headers, occurred_at_ms: int, units: float, name: str) -> dict:
    response = client.post(
        "/v1/insulin-events",
        headers=headers,
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": occurred_at_ms,
            "insulin_units": units,
            "insulin_name": name,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def _assert_profile_contract(activity: dict) -> None:
    missing = PROFILE_CONTRACT_FIELDS.difference(activity)
    assert not missing, f"new forecasts must populate action-profile fields: {sorted(missing)}"

    assert activity["start_ms"] <= activity["onset_ms"] <= activity["peak_ms"]
    assert activity["peak_low_ms"] <= activity["peak_ms"] <= activity["peak_high_ms"]
    assert activity["end_low_ms"] <= activity["end_ms"] <= activity["end_high_ms"]
    assert activity["onset_ms"] < activity["end_ms"]
    assert activity["peak_low_ms"] < activity["peak_high_ms"]
    assert activity["end_low_ms"] < activity["end_high_ms"]
    assert 0.0 <= activity["attribution_confidence"] <= 1.0
    assert activity["identifiability"] in IDENTIFIABILITY_LEVELS
    assert activity["action_model"] in ACTION_MODELS
    assert isinstance(activity["overlap_count"], int)
    assert activity["overlap_count"] >= 0


def test_simultaneous_rapid_long_and_second_rapid_stay_separately_attributable(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=_reading_batch(anchor)
    ).status_code == 200

    rapid_small = _post_insulin(client, auth_headers, anchor, 2.0, "NovoRapid")
    rapid_large = _post_insulin(client, auth_headers, anchor, 6.0, "NovoRapid")
    long = _post_insulin(client, auth_headers, anchor, 18.0, "Tresiba")
    expected_ids = {rapid_small["id"], rapid_large["id"], long["id"]}

    forecast = client.get("/v1/forecast/current", headers=auth_headers)
    assert forecast.status_code == 200
    by_id = {item["event_id"]: item for item in forecast.json()["activities"]}
    assert expected_ids.issubset(by_id)
    assert by_id[rapid_small["id"]]["kind"] == "rapid"
    assert by_id[rapid_large["id"]]["kind"] == "rapid"
    assert by_id[long["id"]]["kind"] == "long"

    # A graph cluster is presentation only. Every dose keeps its own UUID,
    # counterfactual point series, uncertainty, and overlap metadata.
    for event_id in expected_ids:
        activity = by_id[event_id]
        _assert_profile_contract(activity)
        assert len(activity["points"]) == 25
        # All three physiological action windows intersect. Cross-kind overlap
        # still makes attribution harder even though the UUID counterfactuals
        # and the visual rows remain separate.
        assert activity["overlap_count"] >= 2
    assert by_id[rapid_small["id"]]["points"] != by_id[rapid_large["id"]]["points"]
    assert by_id[rapid_small["id"]]["points"][-1]["contribution_mg_dl"] < 0
    assert by_id[rapid_large["id"]]["points"][-1]["contribution_mg_dl"] < 0

    deleted = client.delete(
        f"/v1/intakes/{rapid_small['id']}", headers=auth_headers
    )
    assert deleted.status_code == 200
    after_delete = client.get("/v1/forecast/current", headers=auth_headers).json()
    remaining = {item["event_id"] for item in after_delete["activities"]}
    assert rapid_small["id"] not in remaining
    assert {rapid_large["id"], long["id"]}.issubset(remaining)


def test_insufficient_event_evidence_is_explicit_population_prior(client, auth_headers):
    anchor = int(time.time() * 1_000) - 1_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=_reading_batch(anchor)
    ).status_code == 200
    rapid = _post_insulin(client, auth_headers, anchor, 4.0, "NovoRapid")
    long = _post_insulin(client, auth_headers, anchor, 16.0, "Tresiba")

    response = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert response["model_version"] == "event-aware-persistence-v3"
    by_id = {item["event_id"]: item for item in response["activities"]}
    rapid_activity = by_id[rapid["id"]]
    long_activity = by_id[long["id"]]
    for activity in (rapid_activity, long_activity):
        _assert_profile_contract(activity)
        assert activity["profile_source"] == "population_prior"
        assert activity["identifiability"] in {"low", "not_identifiable"}
        assert activity["attribution_confidence"] <= 0.35

    assert rapid_activity["action_model"] == "population_prior"
    assert long_activity["action_model"] == "basal_depot"
    rapid_peak_width = rapid_activity["peak_high_ms"] - rapid_activity["peak_low_ms"]
    long_peak_width = long_activity["peak_high_ms"] - long_activity["peak_low_ms"]
    rapid_end_width = rapid_activity["end_high_ms"] - rapid_activity["end_low_ms"]
    long_end_width = long_activity["end_high_ms"] - long_activity["end_low_ms"]
    assert long_peak_width > rapid_peak_width > 0
    assert long_end_width > rapid_end_width > 0


def _synthetic_readings(anchor_ms: int) -> list[GlucoseReadingRecord]:
    return [
        GlucoseReadingRecord(
            reading_id=f"context-{index}",
            measured_at_ms=anchor_ms - (23 - index) * STEP_MS,
            glucose_mg_dl=122.0 + 2.0 * math.sin(index / 4.0),
            trend_mg_dl_min=None,
            quality=1.0,
            utc_offset_minutes=180,
            payload_hash=f"{index:064x}"[-64:],
            received_at_ms=anchor_ms,
        )
        for index in range(24)
    ]


def _validated_zero_residual_v3(
    readings: list[GlucoseReadingRecord], events: list[_Event], anchor_ms: int
) -> dict:
    parameters = _default_parameters()
    parameters["kind"] = "personalized_contextual_gated_neural"
    parameters["feature_schema"] = V3_FEATURE_SCHEMA
    parameters["evidence_counts"] = {"meal": 8, "rapid": 8, "long": 8}
    parameters["event_channel_validation"] = {
        kind: {
            "validated": True,
            "response_samples": 8,
            "validation_events": 5,
            "validation_windows": 12,
        }
        for kind in ("meal", "rapid", "long")
    }
    feature_count = _history_features(readings, events, anchor_ms, parameters).size
    shared_size = 2
    interaction_size = 2
    parameters["network"] = {
        "kind": V3_NETWORK_KIND,
        "feature_schema": V3_FEATURE_SCHEMA,
        "x_mean": np.zeros(feature_count).tolist(),
        "x_scale": np.ones(feature_count).tolist(),
        "w1": np.zeros((feature_count, shared_size)).tolist(),
        "b1": np.zeros(shared_size).tolist(),
        "base_w": np.zeros((shared_size, HORIZON_STEPS)).tolist(),
        "base_b": np.zeros(HORIZON_STEPS).tolist(),
        "event_w1": np.zeros((shared_size, interaction_size)).tolist(),
        "event_b1": np.zeros(interaction_size).tolist(),
        "event_w2": np.zeros((interaction_size, HORIZON_STEPS)).tolist(),
        "event_b2": np.zeros(HORIZON_STEPS).tolist(),
        "gate_w": np.zeros((shared_size, HORIZON_STEPS)).tolist(),
        "gate_b": np.zeros(HORIZON_STEPS).tolist(),
    }
    return parameters


def test_contextual_attribution_has_per_event_peak_and_end_uncertainty():
    anchor = 1_900_000_000_000
    readings = _synthetic_readings(anchor)
    events = [
        _Event(
            event_id=str(uuid4()),
            occurred_at_ms=anchor - 20 * 60_000,
            kind="rapid",
            label="NovoRapid 3 U",
            amount=3.0,
        ),
        _Event(
            event_id=str(uuid4()),
            occurred_at_ms=anchor - 20 * 60_000,
            kind="rapid",
            label="NovoRapid 7 U",
            amount=7.0,
        ),
    ]
    parameters = _validated_zero_residual_v3(readings, events, anchor)

    service = ForecastService()
    isolated = service._activities(
        [events[0]], anchor, parameters, readings=readings
    )[0].model_dump(mode="json")
    activities = service._activities(events, anchor, parameters, readings=readings)
    assert {str(item.event_id) for item in activities} == {
        event.event_id for event in events
    }
    _assert_profile_contract(isolated)
    assert isolated["overlap_count"] == 0
    for activity_model in activities:
        activity = activity_model.model_dump(mode="json")
        _assert_profile_contract(activity)
        assert activity["action_model"] == "contextual_counterfactual"
        assert activity["profile_source"] == "personalized"
        # Two indistinguishable same-kind doses at the same instant can retain
        # separate UUID counterfactuals, but must not be presented as highly
        # identifiable causal effects.
        assert activity["identifiability"] in {"low", "medium"}
        assert activity["attribution_confidence"] > 0.0
        assert activity["attribution_confidence"] < isolated["attribution_confidence"]
        assert activity["overlap_count"] >= 1


def test_future_events_cannot_leak_into_points_or_action_profile(client, auth_headers):
    now = int(time.time() * 1_000)
    anchor = now - 10 * 60_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=_reading_batch(anchor)
    ).status_code == 200
    baseline = client.get("/v1/forecast/current", headers=auth_headers).json()

    occurred_at = anchor + 8 * 60_000
    known_event = _post_insulin(client, auth_headers, occurred_at, 5.0, "NovoRapid")
    with_known_event = client.get("/v1/forecast/current", headers=auth_headers).json()
    activity = next(
        item
        for item in with_known_event["activities"]
        if item["event_id"] == known_event["id"]
    )
    _assert_profile_contract(activity)
    assert activity["onset_ms"] >= occurred_at
    assert activity["peak_low_ms"] >= activity["onset_ms"]
    points_by_minute = {
        point["minutes_from_anchor"]: point for point in activity["points"]
    }
    for minute in (0, 5):
        assert points_by_minute[minute]["activity"] == 0
        assert points_by_minute[minute]["contribution_mg_dl"] == 0
    assert with_known_event["points"][0] == baseline["points"][0]
    assert points_by_minute[10]["contribution_mg_dl"] < 0

    # An event that has not happened yet is not a causal input at all.
    future = _post_insulin(
        client, auth_headers, now + 8 * 60_000, 9.0, "NovoRapid"
    )
    after_future = client.get("/v1/forecast/current", headers=auth_headers).json()
    assert after_future["points"] == with_known_event["points"]
    assert future["id"] not in {
        item["event_id"] for item in after_future["activities"]
    }


def test_tresiba_profiles_overlap_broadly_without_an_artificial_spike(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=_reading_batch(anchor)
    ).status_code == 200
    earlier = _post_insulin(
        client, auth_headers, anchor - 12 * 60 * 60_000, 16.0, "Tresiba"
    )
    current = _post_insulin(client, auth_headers, anchor, 16.0, "Tresiba")

    forecast = client.get("/v1/forecast/current", headers=auth_headers).json()
    by_id = {item["event_id"]: item for item in forecast["activities"]}
    for event_id in (earlier["id"], current["id"]):
        activity = by_id[event_id]
        _assert_profile_contract(activity)
        assert activity["kind"] == "long"
        assert activity["action_model"] == "basal_depot"
        assert activity["overlap_count"] >= 1
        assert activity["end_ms"] - activity["start_ms"] >= 24 * 60 * 60_000

        values = [point["activity"] for point in activity["points"]]
        nonzero = [value for value in values if value > 1e-6]
        # A new basal dose may have a legitimate 30–60 minute onset delay, but
        # its visible action must still span many samples rather than one spike.
        assert len(nonzero) >= 10
        assert max(abs(right - left) for left, right in zip(values, values[1:])) <= 0.15
        # A broad basal/depot profile must not put most of its visible action into
        # one five-minute sample merely to manufacture a visually precise peak.
        assert max(values) / sum(values) <= 0.20
        assert all(point["contribution_mg_dl"] <= 0 for point in activity["points"])
