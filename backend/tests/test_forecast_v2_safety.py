from __future__ import annotations

import copy
import json

import numpy as np
import pytest

from app.forecast import (
    FORECAST_ENGINE_VERSION,
    HORIZON_STEPS,
    STATIC_BANDS,
    STATIC_FEATURE_COUNT,
    STATIC_FEATURE_SCHEMA,
    STATIC_HIDDEN_SIZE,
    STATIC_INTERVAL_LEVEL,
    STATIC_LOW_GUARD_MG_DL,
    STATIC_NETWORK_KIND,
    STATIC_PERSONAL_ARCHITECTURE,
    STATIC_PROMOTION_GATE_VERSION,
    STATIC_TRAINING_MODE,
    ForecastService,
    _artifact_content_hash,
    _default_parameters,
    _finite_sample_quantile_level,
    _forecast_interval_bounds,
    _reference_safety_sigma,
    _static_artifact_is_valid,
)
from app.models import ForecastModelRecord


TRAINED_AT_MS = 1_700_000_000_000
DATA_CUTOFF_MS = TRAINED_AT_MS - 86_400_000
SAMPLE_COUNT = 2_304
CALIBRATION_SAMPLES = 22
NORMAL_80_Z = 1.2816


def _valid_static_parameters(version: str) -> dict:
    parameters = _default_parameters()
    parameters.update(
        {
            "kind": "personalized_static_generic_residual",
            "architecture": STATIC_PERSONAL_ARCHITECTURE,
            "feature_schema": STATIC_FEATURE_SCHEMA,
            "network_disabled_event_channels": ["meal", "rapid", "long"],
        }
    )
    network = {
        "kind": STATIC_NETWORK_KIND,
        "feature_schema": STATIC_FEATURE_SCHEMA,
        "x_mean": [0.0] * STATIC_FEATURE_COUNT,
        "x_scale": [1.0] * STATIC_FEATURE_COUNT,
        "w1": [
            [0.0] * STATIC_HIDDEN_SIZE for _ in range(STATIC_FEATURE_COUNT)
        ],
        "b1": [0.0] * STATIC_HIDDEN_SIZE,
        "w2": [[0.0] * HORIZON_STEPS for _ in range(STATIC_HIDDEN_SIZE)],
        "b2": [0.0] * HORIZON_STEPS,
    }
    parameter_count = sum(
        np.asarray(network[name], dtype=np.float64).size
        for name in ("w1", "b1", "w2", "b2")
    )

    band_weights = [0.25, 0.50, 0.50, 0.25]
    blend: list[float] = []
    band_definitions: list[dict[str, float | int]] = []
    for (start, end), weight in zip(STATIC_BANDS, band_weights, strict=True):
        blend.extend([weight] * (((end - start) // 5) + 1))
        band_definitions.append(
            {"start_minutes": start, "end_minutes": end, "weight": weight}
        )

    sigma = [20.0] * HORIZON_STEPS
    reference_sigma = [24.0] * HORIZON_STEPS
    evaluation = {
        "accepted": 1,
        "candidate_equal_day_mae": 18.0,
        "reference_equal_day_mae": 20.0,
        "pinned_equal_day_mae": 20.0,
        "candidate_anchor_mae": 17.5,
        "reference_anchor_mae": 20.0,
        "candidate_coverage_80": 0.80,
        "candidate_interval_score_80": 35.0,
        "reference_interval_score_80": 40.0,
        "candidate_mae_5": 8.0,
        "candidate_mae_15": 10.0,
        "candidate_hypo_recall": 0.90,
        "reference_hypo_recall": 0.80,
        "candidate_hypo_fpr": 0.10,
        "reference_hypo_fpr": 0.12,
        "candidate_hypo_missed_episodes": 0.0,
        "reference_hypo_missed_episodes": 1.0,
        "candidate_low_zone_mae": 8.0,
        "reference_low_zone_mae": 10.0,
        "gate_hypo_safe": 1,
        "hypo_low_points": 50.0,
        "hypo_low_episodes": 6.0,
        "hypo_low_days": 4.0,
        "test_days": 4,
        "test_independent_anchors": 32,
    }
    parameters.update(
        {
            "network": network,
            "persistence_blend_weights": blend,
            "residual_sigma": sigma,
            "frozen_calibration": {
                "method": "frozen-uncentered-conformal-v2",
                "quantile_method": "higher",
                "point_bias": "disabled",
                "low_guard_threshold_mg_dl": STATIC_LOW_GUARD_MG_DL,
                "safety_envelope": "reference-interval-union-v1",
                "interval_level": STATIC_INTERVAL_LEVEL,
                "finite_sample_quantile": _finite_sample_quantile_level(
                    CALIBRATION_SAMPLES
                ),
                "sample_count": CALIBRATION_SAMPLES,
                "bias_mg_dl": [0.0] * HORIZON_STEPS,
                "sigma_mg_dl": sigma,
                "reference_sigma_mg_dl": reference_sigma,
            },
            "artifact": {
                "artifact_version": 4,
                "engine_version": FORECAST_ENGINE_VERSION,
                "architecture": STATIC_PERSONAL_ARCHITECTURE,
                "feature_schema": STATIC_FEATURE_SCHEMA,
                "network_kind": STATIC_NETWORK_KIND,
                "training_mode": STATIC_TRAINING_MODE,
                "promotion_gate_version": STATIC_PROMOTION_GATE_VERSION,
                "accepted": True,
                "model_version": version,
                "trained_at_ms": TRAINED_AT_MS,
                "data_cutoff_ms": DATA_CUTOFF_MS,
                "sample_count": SAMPLE_COUNT,
                "interval_level": STATIC_INTERVAL_LEVEL,
                "dataset_sha256": "d" * 64,
                "feature_count": STATIC_FEATURE_COUNT,
                "parameter_count": int(parameter_count),
                "band_definitions": band_definitions,
                "split": {
                    "train_days": 8,
                    "tuning_days": 1,
                    "calibration_days": 2,
                    "test_days": 4,
                    "purge_minutes": 120,
                    "test_independent_anchors": 32,
                },
                "evaluation": evaluation,
                "reliability": {
                    "overall": 0.35,
                    "by_horizon": [0.35] * HORIZON_STEPS,
                    "clinical_validation": False,
                    "test_day_cap": 0.35,
                },
            },
        }
    )
    parameters["artifact"]["content_sha256"] = _artifact_content_hash(parameters)
    return parameters


def _static_record(version: str, parameters: dict) -> ForecastModelRecord:
    evaluation = parameters["artifact"]["evaluation"]
    return ForecastModelRecord(
        version=version,
        status="candidate",
        architecture=STATIC_PERSONAL_ARCHITECTURE,
        created_at_ms=TRAINED_AT_MS,
        trained_at_ms=TRAINED_AT_MS,
        promoted_at_ms=None,
        training_cutoff_ms=DATA_CUTOFF_MS,
        sample_count=SAMPLE_COUNT,
        parameters_json=json.dumps(parameters, separators=(",", ":")),
        metrics_json=json.dumps(evaluation, separators=(",", ":")),
        decision_reason="forecast v2 safety fixture",
    )


def _promotion_metrics(
    *,
    mae: float,
    rmse: float,
    short_mae: float,
    horizon_mae: float,
    interval_score: float,
    hypo_recall: float,
    hypo_missed_episodes: float,
) -> dict[str, float]:
    return {
        "mae": mae,
        "rmse": rmse,
        "mae_5": short_mae,
        "mae_15": short_mae,
        "mae_30": horizon_mae,
        "mae_60": horizon_mae,
        "mae_120": horizon_mae,
        "coverage_80": 0.80,
        "interval_score_80": interval_score,
        "coverage_band_0": 0.80,
        "coverage_band_1": 0.80,
        "coverage_band_2": 0.80,
        "coverage_band_3": 0.80,
        "hypo_low_points": 50.0,
        "hypo_low_episodes": 6.0,
        "hypo_low_days": 4.0,
        "hypo_recall": hypo_recall,
        "hypo_fpr": 0.10,
        "hypo_missed_episodes": hypo_missed_episodes,
        "low_zone_mae": short_mae,
    }


def _safe_gate_inputs() -> tuple[
    dict[str, float],
    dict[str, float],
    list[dict[str, float]],
]:
    candidate = _promotion_metrics(
        mae=90.0,
        rmse=95.0,
        short_mae=8.0,
        horizon_mae=9.0,
        interval_score=95.0,
        hypo_recall=0.90,
        hypo_missed_episodes=0.0,
    )
    reference = _promotion_metrics(
        mae=100.0,
        rmse=100.0,
        short_mae=10.0,
        horizon_mae=10.0,
        interval_score=100.0,
        hypo_recall=0.80,
        hypo_missed_episodes=1.0,
    )
    day_results = [
        {"candidate_mae": 88.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 88.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 88.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 101.0, "reference_mae": 100.0, "pinned_mae": 100.0},
    ]
    return candidate, reference, day_results


def test_frozen_calibration_never_applies_a_point_bias() -> None:
    prediction = np.full((CALIBRATION_SAMPLES, HORIZON_STEPS), 120.0)
    target = prediction + 25.0

    bias, sigma = ForecastService._frozen_calibration(prediction, target)

    np.testing.assert_array_equal(bias, np.zeros(HORIZON_STEPS))
    assert np.isfinite(sigma).all()
    assert np.all(sigma > 0.0)


def test_finite_sample_conformal_level_for_22_samples_is_conservative() -> None:
    level = _finite_sample_quantile_level(CALIBRATION_SAMPLES)

    assert level == pytest.approx(19.0 / 22.0)
    assert level > STATIC_INTERVAL_LEVEL

    prediction = np.zeros((CALIBRATION_SAMPLES, HORIZON_STEPS))
    scores = np.arange(1.0, CALIBRATION_SAMPLES + 1.0).reshape(-1, 1)
    target = np.broadcast_to(scores, prediction.shape).copy()
    bias, sigma = ForecastService._frozen_calibration(prediction, target)

    np.testing.assert_array_equal(bias, np.zeros(HORIZON_STEPS))
    # k=ceil((22+1)*.8)=19 exactly; NumPy percentile interpolation must not
    # silently move this to the twentieth score.
    np.testing.assert_allclose(NORMAL_80_Z * sigma, np.full(HORIZON_STEPS, 19.0))


def test_reference_safety_interval_contains_both_source_intervals() -> None:
    candidate = np.linspace(90.0, 150.0, HORIZON_STEPS)
    reference = candidate + np.linspace(-30.0, 35.0, HORIZON_STEPS)
    candidate_sigma = np.linspace(6.0, 14.0, HORIZON_STEPS)
    reference_sigma = np.linspace(10.0, 24.0, HORIZON_STEPS)

    safe_sigma = _reference_safety_sigma(
        candidate, reference, candidate_sigma, reference_sigma
    )

    candidate_half = np.maximum.accumulate(
        np.maximum(7.0, NORMAL_80_Z * np.maximum(candidate_sigma, 6.0))
    )
    reference_half = np.maximum.accumulate(
        np.maximum(7.0, NORMAL_80_Z * np.maximum(reference_sigma, 6.0))
    )
    safe_half = NORMAL_80_Z * safe_sigma
    assert np.all(safe_half >= candidate_half)
    assert np.all(candidate - safe_half <= reference - reference_half)
    assert np.all(candidate + safe_half >= reference + reference_half)


def test_static_runtime_bounds_do_not_reapply_legacy_200_sigma_cap() -> None:
    candidate = np.full(HORIZON_STEPS, 580.0)
    reference = np.full(HORIZON_STEPS, 40.0)
    candidate_sigma = np.full(HORIZON_STEPS, 8.0)
    reference_sigma = np.full(HORIZON_STEPS, 260.0)

    safe_sigma = _reference_safety_sigma(
        candidate, reference, candidate_sigma, reference_sigma
    )
    assert np.max(safe_sigma) > 200.0

    displayed_low, displayed_high = _forecast_interval_bounds(
        candidate, safe_sigma
    )
    reference_low, reference_high = _forecast_interval_bounds(
        reference, reference_sigma
    )
    assert np.all(displayed_low <= reference_low)
    assert np.all(displayed_high >= reference_high)


def test_metrics_and_promotion_gate_require_5_and_15_minute_accuracy() -> None:
    prediction = np.zeros((2, HORIZON_STEPS))
    target = np.broadcast_to(
        np.arange(1.0, HORIZON_STEPS + 1.0), prediction.shape
    ).copy()
    measured = ForecastService._metrics(prediction, target)

    assert measured["mae_5"] == pytest.approx(1.0)
    assert measured["mae_15"] == pytest.approx(3.0)

    candidate, reference, day_results = _safe_gate_inputs()
    assert ForecastService.static_promotion_gates(
        candidate, reference, reference, day_results, test_day_count=4
    )["accepted"] is True

    for missing in ("mae_5", "mae_15"):
        malformed = dict(candidate)
        malformed.pop(missing)
        assert ForecastService.static_promotion_gates(
            malformed, reference, reference, day_results, test_day_count=4
        )["accepted"] is False


def test_promotion_gate_rejects_hypo_recall_regression() -> None:
    candidate, reference, day_results = _safe_gate_inputs()
    candidate["hypo_recall"] = 0.60

    result = ForecastService.static_promotion_gates(
        candidate, reference, reference, day_results, test_day_count=4
    )

    assert result["accepted"] is False


def test_promotion_gate_rejects_additional_missed_hypo_episode() -> None:
    candidate, reference, day_results = _safe_gate_inputs()
    candidate["hypo_missed_episodes"] = reference["hypo_missed_episodes"] + 1.0

    result = ForecastService.static_promotion_gates(
        candidate, reference, reference, day_results, test_day_count=4
    )

    assert result["accepted"] is False


def test_promotion_gate_rejects_hypo_or_day_regression_vs_pinned_model() -> None:
    candidate, reference, day_results = _safe_gate_inputs()
    pinned = dict(reference)
    pinned["hypo_recall"] = 0.99
    pinned["hypo_missed_episodes"] = 0.0
    pinned["hypo_fpr"] = 0.01

    hypo_result = ForecastService.static_promotion_gates(
        candidate, reference, pinned, day_results, test_day_count=4
    )
    assert hypo_result["accepted"] is False

    safe_pinned = dict(reference)
    pinned_day_regression = [dict(item) for item in day_results]
    pinned_day_regression[0]["pinned_mae"] = 80.0
    day_result = ForecastService.static_promotion_gates(
        candidate,
        reference,
        safe_pinned,
        pinned_day_regression,
        test_day_count=4,
    )
    assert day_result["no_day_regression_over_2pct"] is False
    assert day_result["accepted"] is False


def test_static_validator_rejects_nonzero_point_bias_even_with_fresh_hash() -> None:
    from test_forecast_static import _static_parameters

    parameters = _static_parameters("static-v2-valid")
    assert _static_artifact_is_valid(parameters) is True

    tampered = copy.deepcopy(parameters)
    tampered["frozen_calibration"]["bias_mg_dl"][0] = 0.01
    tampered["artifact"]["content_sha256"] = _artifact_content_hash(tampered)

    assert _static_artifact_is_valid(tampered) is False


def test_runtime_validator_rejects_record_and_artifact_version_mismatch() -> None:
    from test_forecast_static import _static_parameters

    parameters = _static_parameters("artifact-version")
    assert _static_artifact_is_valid(parameters) is True
    mismatched = _static_record("record-version", parameters)

    assert ForecastService._runtime_model_is_valid(mismatched) is False
