from __future__ import annotations

import json
import math
import time
from uuid import uuid4

import numpy as np
import pytest

from app.forecast import (
    BASELINE_VERSION,
    HORIZON_STEPS,
    PERSONAL_ARCHITECTURE,
    STEP_MS,
    V3_FEATURE_SCHEMA,
    ForecastService,
    _Event,
    _contextual_event_contribution,
    _default_parameters,
    _event_glucose_increment,
    _forecast_arrays,
    _history_features,
    _json_dict,
    _multiscale_history_features,
    _network_predict_batch,
    _profile_for_event,
    _triangular_activity,
)
from app.models import (
    ForecastModelRecord,
    GlucoseReadingRecord,
    IntakeEventRecord,
)


ANCHOR_MS = 1_900_000_000_000


def _reading_history(
    anchor_ms: int,
    *,
    context: str,
    hours: int = 72,
) -> list[GlucoseReadingRecord]:
    """Two histories with an identical recent trace but different older context."""

    readings: list[GlucoseReadingRecord] = []
    count = hours * 60 // 5 + 1
    for index in range(count):
        measured_at_ms = anchor_ms - (count - 1 - index) * STEP_MS
        age_minutes = (anchor_ms - measured_at_ms) // 60_000
        if age_minutes <= 120:
            # The complete legacy two-hour input is byte-for-byte equivalent.
            glucose = 118.0 + ((index % 7) - 3) * 0.15
        elif context == "settled":
            glucose = 94.0 + (index % 5) * 0.2
        else:
            # Separate 6 h, 24 h and 72 h patterns make this sensitive to every
            # intended context scale without relying on a particular feature slot.
            if age_minutes <= 6 * 60:
                glucose = 154.0 + (index % 5) * 0.2
            elif age_minutes <= 24 * 60:
                glucose = 178.0 - (index % 9) * 0.25
            else:
                glucose = 72.0 + (index % 11) * 0.3
        readings.append(
            GlucoseReadingRecord(
                reading_id=f"{context}-{index}",
                measured_at_ms=measured_at_ms,
                glucose_mg_dl=glucose,
                trend_mg_dl_min=0.0,
                sensor_id="sensor-v3-test",
                sensor_generation="test",
                quality=1.0,
                utc_offset_minutes=0,
                payload_hash=f"{index:064x}"[-64:],
                received_at_ms=anchor_ms,
            )
        )
    return readings


def _v3_parameters() -> dict:
    parameters = _default_parameters()
    parameters["kind"] = "personalized_contextual_neural"
    parameters["feature_schema"] = V3_FEATURE_SCHEMA
    # Contextual attribution is enabled only after the per-kind evidence gate.
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
    return parameters


def _one_hidden_unit_network(
    feature_count: int,
    weights: dict[int, float],
    *,
    bias: float,
    output_weight: float,
) -> dict:
    w1 = np.zeros((feature_count, 1), dtype=np.float64)
    for index, weight in weights.items():
        w1[index, 0] = weight
    return {
        "kind": "contextual_gated_v3",
        "feature_schema": V3_FEATURE_SCHEMA,
        "x_mean": np.zeros(feature_count, dtype=np.float64).tolist(),
        "x_scale": np.ones(feature_count, dtype=np.float64).tolist(),
        "w1": w1.tolist(),
        "b1": [bias],
        "base_w": np.full(
            (1, HORIZON_STEPS), output_weight, dtype=np.float64
        ).tolist(),
        "base_b": np.zeros(HORIZON_STEPS, dtype=np.float64).tolist(),
        "event_w1": [[0.0]],
        "event_b1": [0.0],
        "event_w2": np.zeros((1, HORIZON_STEPS), dtype=np.float64).tolist(),
        "event_b2": np.zeros(HORIZON_STEPS, dtype=np.float64).tolist(),
        "gate_w": np.zeros((1, HORIZON_STEPS), dtype=np.float64).tolist(),
        "gate_b": np.zeros(HORIZON_STEPS, dtype=np.float64).tolist(),
    }


def _legacy_zero_network(feature_count: int) -> dict:
    return {
        "kind": "legacy_direct_v2",
        "x_mean": np.zeros(feature_count, dtype=np.float64).tolist(),
        "x_scale": np.ones(feature_count, dtype=np.float64).tolist(),
        "w1": np.zeros((feature_count, 1), dtype=np.float64).tolist(),
        "b1": [0.0],
        "w2": np.zeros((1, HORIZON_STEPS), dtype=np.float64).tolist(),
        "b2": np.zeros(HORIZON_STEPS, dtype=np.float64).tolist(),
    }


def _context_interaction_parameters(
    settled: list[GlucoseReadingRecord],
    volatile: list[GlucoseReadingRecord],
    event: _Event,
    anchor_ms: int,
) -> dict:
    parameters = _v3_parameters()
    settled_without = _history_features(settled, [], anchor_ms, parameters)
    volatile_without = _history_features(volatile, [], anchor_ms, parameters)
    settled_with = _history_features(settled, [event], anchor_ms, parameters)
    volatile_with = _history_features(volatile, [event], anchor_ms, parameters)

    context_change = np.abs(volatile_without - settled_without)
    event_change = np.abs(settled_with - settled_without)
    context_candidates = np.flatnonzero(
        (context_change > 1e-8) & (event_change < 1e-10)
    )
    event_candidates = np.flatnonzero(
        (event_change > 1e-8) & (context_change < 1e-10)
    )
    assert context_candidates.size, "v3 features must retain non-event context"
    assert event_candidates.size, "v3 features must retain the causal event sequence"
    context_index = int(context_candidates[np.argmax(context_change[context_candidates])])
    event_index = int(event_candidates[np.argmax(event_change[event_candidates])])

    context_a = float(settled_without[context_index])
    context_b = float(volatile_without[context_index])
    event_delta = float(settled_with[event_index] - settled_without[event_index])
    context_weight = 2.0 / (context_b - context_a)
    event_weight = 0.8 / event_delta
    # With no event, the hidden preactivation is -1 in the settled context and
    # +1 in the volatile context. The same event shift therefore has a different
    # marginal effect through tanh, which makes the expected attribution explicit.
    bias = (
        -1.0
        - context_a * context_weight
        - float(settled_without[event_index]) * event_weight
    )
    parameters["network"] = _one_hidden_unit_network(
        settled_without.size,
        {context_index: context_weight, event_index: event_weight},
        bias=bias,
        output_weight=-12.0,
    )
    return parameters


def test_multiscale_context_changes_v3_features_and_prediction():
    settled = _reading_history(ANCHOR_MS, context="settled")
    volatile = _reading_history(ANCHOR_MS, context="volatile")
    assert [row.glucose_mg_dl for row in settled[-25:]] == [
        row.glucose_mg_dl for row in volatile[-25:]
    ]

    parameters = _v3_parameters()
    settled_features = _history_features(settled, [], ANCHOR_MS, parameters)
    volatile_features = _history_features(volatile, [], ANCHOR_MS, parameters)
    np.testing.assert_allclose(
        settled_features,
        _multiscale_history_features(settled, [], ANCHOR_MS, parameters),
    )
    assert settled_features.shape == volatile_features.shape
    assert np.isfinite(settled_features).all()
    assert np.isfinite(volatile_features).all()
    changed = np.flatnonzero(np.abs(volatile_features - settled_features) > 1e-8)
    assert changed.size, "older 6/24/72-hour context must not collapse to the last two hours"

    feature_index = int(changed[np.argmax(
        np.abs(volatile_features[changed] - settled_features[changed])
    )])
    first = float(settled_features[feature_index])
    second = float(volatile_features[feature_index])
    weight = 2.0 / (second - first)
    parameters["network"] = _one_hidden_unit_network(
        settled_features.size,
        {feature_index: weight},
        bias=-1.0 - first * weight,
        output_weight=15.0,
    )
    settled_prediction, _ = _forecast_arrays(settled, [], ANCHOR_MS, parameters)
    volatile_prediction, _ = _forecast_arrays(volatile, [], ANCHOR_MS, parameters)
    assert np.max(np.abs(volatile_prediction - settled_prediction)) > 10.0


def test_same_dose_and_age_get_contextual_counterfactual_attribution_in_v3():
    settled = _reading_history(ANCHOR_MS, context="settled")
    volatile = _reading_history(ANCHOR_MS, context="volatile")
    event = _Event(
        event_id=str(uuid4()),
        occurred_at_ms=ANCHOR_MS - 30 * 60_000,
        known_at_ms=ANCHOR_MS - 30 * 60_000,
        kind="rapid",
        label="NovoRapid",
        amount=6.0,
    )
    parameters = _context_interaction_parameters(
        settled, volatile, event, ANCHOR_MS
    )

    settled_contribution = _contextual_event_contribution(
        settled, [event], event, ANCHOR_MS, parameters
    )
    volatile_contribution = _contextual_event_contribution(
        volatile, [event], event, ANCHOR_MS, parameters
    )
    assert settled_contribution.shape == (HORIZON_STEPS,)
    assert volatile_contribution.shape == (HORIZON_STEPS,)
    assert np.isfinite(settled_contribution).all()
    assert np.isfinite(volatile_contribution).all()
    assert np.any(settled_contribution < -0.1)
    assert np.any(volatile_contribution < -0.1)
    assert np.max(np.abs(settled_contribution - volatile_contribution)) > 0.25

    service = ForecastService()
    settled_activity = service._activities(
        [event], ANCHOR_MS, parameters, readings=settled
    )[0]
    volatile_activity = service._activities(
        [event], ANCHOR_MS, parameters, readings=volatile
    )[0]
    np.testing.assert_allclose(
        [point.contribution_mg_dl for point in settled_activity.points[1:]],
        np.round(settled_contribution, 3),
        atol=5e-4,
    )
    np.testing.assert_allclose(
        [point.contribution_mg_dl for point in volatile_activity.points[1:]],
        np.round(volatile_contribution, 3),
        atol=5e-4,
    )


def test_backdated_event_is_unknown_to_earlier_historical_window(app, client):
    # Requesting the TestClient fixture enters the app lifespan and creates tables.
    assert client is not None
    early_anchor = ANCHOR_MS
    known_at_ms = early_anchor + 30 * 60_000
    late_anchor = early_anchor + 60 * 60_000
    event_id = str(uuid4())
    with app.state.database.session_factory() as session:
        session.add(
            IntakeEventRecord(
                id=event_id,
                client_event_id=str(uuid4()),
                occurred_at_ms=early_anchor - 15 * 60_000,
                meal_text=None,
                carbs_g=None,
                carbs_source=None,
                insulin_units=5.0,
                insulin_type="rapid",
                insulin_name="NovoRapid",
                analysis_id=None,
                payload_hash="b" * 64,
                created_at_ms=known_at_ms,
                updated_at_ms=known_at_ms,
                deleted_at_ms=None,
                sync_version=1,
            )
        )
        session.commit()

        assert app.state.forecast_service._load_events(
            session,
            through_ms=late_anchor,
            known_through_ms=early_anchor,
        ) == []
        events = app.state.forecast_service._load_events(
            session,
            through_ms=late_anchor,
            known_through_ms=late_anchor,
        )
    assert len(events) == 1
    assert events[0].event_id == event_id
    assert events[0].known_at_ms == known_at_ms

    readings = _reading_history(late_anchor, context="settled")
    early_index = next(
        index
        for index, reading in enumerate(readings)
        if reading.measured_at_ms == early_anchor
    )
    late_index = len(readings) - 1
    target = np.zeros(HORIZON_STEPS, dtype=np.float64)
    service = ForecastService()
    parameters = _v3_parameters()
    early_with = service._dataset_for_parameters(
        readings, events, [(early_index, target)], parameters
    )
    early_without = service._dataset_for_parameters(
        readings, [], [(early_index, target)], parameters
    )
    np.testing.assert_allclose(early_with[0], early_without[0])
    np.testing.assert_allclose(early_with[1], early_without[1])

    late_with = service._dataset_for_parameters(
        readings, events, [(late_index, target)], parameters
    )
    late_without = service._dataset_for_parameters(
        readings, [], [(late_index, target)], parameters
    )
    assert not np.allclose(late_with[0], late_without[0])
    assert not np.allclose(late_with[1], late_without[1])


def test_baseline_and_v2_keep_exact_triangular_activity_fallback():
    readings = _reading_history(ANCHOR_MS, context="settled", hours=2)
    event = _Event(
        event_id=str(uuid4()),
        occurred_at_ms=ANCHOR_MS - 20 * 60_000,
        kind="rapid",
        label="NovoRapid",
        amount=5.0,
    )
    baseline = _default_parameters()
    v2 = _default_parameters()
    v2["kind"] = "personalized_hybrid_neural"
    feature_count = _history_features(readings, [event], ANCHOR_MS, v2).size
    v2["network"] = _legacy_zero_network(feature_count)
    v2_prediction, v2_sigma = _forecast_arrays(
        readings, [event], ANCHOR_MS, v2
    )
    assert v2_prediction.shape == v2_sigma.shape == (HORIZON_STEPS,)
    assert np.isfinite(v2_prediction).all()
    assert np.isfinite(v2_sigma).all()

    peak, duration, _ = _profile_for_event(event, baseline)
    for parameters in (baseline, v2):
        activity = ForecastService()._activities(
            [event], ANCHOR_MS, parameters, readings=readings
        )[0]
        for point in activity.points:
            age_minutes = (point.at_ms - event.occurred_at_ms) / 60_000.0
            expected_activity = round(
                _triangular_activity(age_minutes, peak, duration), 6
            )
            expected_contribution = round(
                _event_glucose_increment(
                    event, ANCHOR_MS, point.at_ms, parameters
                ),
                3,
            )
            assert point.activity == expected_activity
            assert point.contribution_mg_dl == expected_contribution


def test_v3_parameters_round_trip_json_without_changing_outputs():
    settled = _reading_history(ANCHOR_MS, context="settled")
    volatile = _reading_history(ANCHOR_MS, context="volatile")
    event = _Event(
        event_id=str(uuid4()),
        occurred_at_ms=ANCHOR_MS - 25 * 60_000,
        known_at_ms=ANCHOR_MS - 25 * 60_000,
        kind="rapid",
        label="NovoRapid",
        amount=5.5,
    )
    parameters = _context_interaction_parameters(
        settled, volatile, event, ANCHOR_MS
    )
    encoded = json.dumps(parameters, separators=(",", ":"), allow_nan=False)
    restored = _json_dict(encoded)
    assert restored == parameters
    assert restored["feature_schema"] == V3_FEATURE_SCHEMA
    assert restored["network"]["kind"] == "contextual_gated_v3"
    assert PERSONAL_ARCHITECTURE == "personalized-contextual-gated-mlp-direct-24-v3"

    prediction_before, sigma_before = _forecast_arrays(
        settled, [event], ANCHOR_MS, parameters
    )
    prediction_after, sigma_after = _forecast_arrays(
        settled, [event], ANCHOR_MS, restored
    )
    contribution_before = _contextual_event_contribution(
        settled, [event], event, ANCHOR_MS, parameters
    )
    contribution_after = _contextual_event_contribution(
        settled, [event], event, ANCHOR_MS, restored
    )
    np.testing.assert_array_equal(prediction_after, prediction_before)
    np.testing.assert_array_equal(sigma_after, sigma_before)
    np.testing.assert_array_equal(contribution_after, contribution_before)


def test_v3_future_event_has_zero_pre_event_forecast_and_attribution():
    readings = _reading_history(ANCHOR_MS, context="settled")
    event = _Event(
        event_id=str(uuid4()),
        occurred_at_ms=ANCHOR_MS + 12 * 60_000,
        known_at_ms=ANCHOR_MS,
        kind="rapid",
        label="NovoRapid",
        amount=6.0,
    )
    parameters = _v3_parameters()
    without_features = _history_features(readings, [], ANCHOR_MS, parameters)
    with_features = _history_features(readings, [event], ANCHOR_MS, parameters)
    event_change = np.abs(with_features - without_features)
    changed = np.flatnonzero(event_change > 1e-8)
    assert changed.size
    feature_index = int(changed[np.argmax(event_change[changed])])
    signed_delta = float(with_features[feature_index] - without_features[feature_index])
    event_weight = 0.8 / signed_delta
    parameters["network"] = _one_hidden_unit_network(
        without_features.size,
        {feature_index: event_weight},
        bias=-float(without_features[feature_index]) * event_weight,
        output_weight=-10.0,
    )

    without_event, _ = _forecast_arrays(readings, [], ANCHOR_MS, parameters)
    with_event, _ = _forecast_arrays(readings, [event], ANCHOR_MS, parameters)
    contribution = _contextual_event_contribution(
        readings, [event], event, ANCHOR_MS, parameters
    )
    # Forecast points are +5, +10, +15 ... minutes. A +12 minute event cannot
    # alter either the forecast or its explanation at +5/+10.
    np.testing.assert_array_equal(with_event[:2], without_event[:2])
    np.testing.assert_array_equal(contribution[:2], np.zeros(2))
    assert np.any(np.abs(with_event[2:] - without_event[2:]) > 1e-6)
    assert np.any(contribution[2:] < -1e-6)


@pytest.mark.parametrize(
    "corrupt",
    (
        lambda network: network["x_scale"].__setitem__(0, 0.0),
        lambda network: network["w1"][0].__setitem__(0, float("nan")),
        lambda network: network["base_w"][0].__setitem__(0, float("inf")),
        lambda network: network.__setitem__("gate_b", [0.0]),
    ),
)
def test_corrupt_v3_network_fails_closed_to_zero_residual(corrupt):
    readings = _reading_history(ANCHOR_MS, context="settled")
    parameters = _v3_parameters()
    features = _history_features(readings, [], ANCHOR_MS, parameters)
    parameters["network"] = _one_hidden_unit_network(
        features.size, {0: 0.5}, bias=0.0, output_weight=4.0
    )
    corrupt(parameters["network"])
    residual = _network_predict_batch(features.reshape(1, -1), parameters)
    np.testing.assert_array_equal(
        residual, np.zeros((1, HORIZON_STEPS), dtype=np.float64)
    )


def test_corrupt_legacy_model_is_audit_only_and_current_remains_finite(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    model_readings = _reading_history(anchor, context="settled", hours=2)
    payload = {
        "utc_offset_minutes": 0,
        "readings": [
            {
                "reading_id": row.reading_id,
                "measured_at_ms": row.measured_at_ms,
                "glucose_mg_dl": row.glucose_mg_dl,
                "quality": 1.0,
            }
            for row in model_readings
        ],
    }
    assert client.post(
        "/v1/glucose/readings", headers=auth_headers, json=payload
    ).status_code == 200
    parameters = _v3_parameters()
    feature_count = _history_features(
        model_readings, [], anchor, parameters
    ).size
    parameters["network"] = _one_hidden_unit_network(
        feature_count, {0: 0.5}, bias=0.0, output_weight=4.0
    )
    parameters["network"]["x_scale"][0] = 0.0
    parameters["network"]["event_w2"][0][0] = float("nan")
    parameters["residual_sigma"] = [float("nan")] * HORIZON_STEPS
    version = "corrupt-v3-model"
    with app.state.database.session_factory() as session:
        baseline = session.get(ForecastModelRecord, BASELINE_VERSION)
        assert baseline is not None
        baseline.status = "retired"
        session.add(
            ForecastModelRecord(
                version=version,
                status="champion",
                architecture=PERSONAL_ARCHITECTURE,
                created_at_ms=anchor,
                trained_at_ms=anchor,
                promoted_at_ms=anchor,
                training_cutoff_ms=anchor,
                sample_count=100,
                parameters_json=json.dumps(parameters),
                metrics_json=json.dumps({"candidate_coverage_80": 0.8}),
                decision_reason="corrupt-model regression test",
            )
        )
        session.commit()
    response = client.get("/v1/forecast/current", headers=auth_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["model_version"] == BASELINE_VERSION
    assert len(body["points"]) == HORIZON_STEPS
    for point in body["points"]:
        assert all(
            math.isfinite(point[key])
            for key in ("median_mg_dl", "low_mg_dl", "high_mg_dl")
        )
    json.dumps(body, allow_nan=False)


def test_event_channel_gate_requires_independent_holdout_improvement():
    target = np.full((12, HORIZON_STEPS), 120.0)
    full = target + 4.0
    ablated = target + 10.0
    accepted = ForecastService._event_channel_gate_result(
        full,
        ablated,
        target,
        response_samples=8,
        validation_events=5,
    )
    assert accepted["validated"] is True
    assert accepted["improvement_mg_dl"] == pytest.approx(6.0)

    insufficient_training = ForecastService._event_channel_gate_result(
        full,
        ablated,
        target,
        response_samples=7,
        validation_events=5,
    )
    insufficient_events = ForecastService._event_channel_gate_result(
        full,
        ablated,
        target,
        response_samples=8,
        validation_events=4,
    )
    no_improvement = ForecastService._event_channel_gate_result(
        ablated,
        full,
        target,
        response_samples=8,
        validation_events=5,
    )
    assert insufficient_training["validated"] is False
    assert insufficient_events["validated"] is False
    assert no_improvement["validated"] is False
