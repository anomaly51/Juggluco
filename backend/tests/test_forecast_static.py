from __future__ import annotations

import copy
import hashlib
import json
import time

import numpy as np
import pytest

from app.forecast import (
    ACTIVE_MODEL_METADATA_KEY,
    ALERT_VALIDATION_PROTOCOL,
    BASELINE_VERSION,
    FORECAST_ENGINE_VERSION,
    STATIC_BANDS,
    STATIC_DISPLAY_PROTOCOL,
    STATIC_FEATURE_COUNT,
    STATIC_FEATURE_SCHEMA,
    STATIC_INTERVAL_LEVEL,
    STATIC_LOW_GUARD_MG_DL,
    STATIC_NETWORK_KIND,
    STATIC_PERSONAL_ARCHITECTURE,
    STATIC_PROMOTION_GATE_VERSION,
    STATIC_RIDGE_ALPHA,
    STATIC_RIDGE_ALPHAS,
    STATIC_TRAINING_MODE,
    ForecastService,
    _Event,
    _artifact_content_hash,
    _alert_episode_metrics,
    _alert_validation_gates,
    _alert_validation_thresholds,
    _baseline_parameters,
    _dataset_fingerprint,
    _default_parameters,
    _finite_sample_quantile_level,
    _model_parameters_hash,
    _static_artifact_is_valid,
    _static_predictor_hash,
    _static_reliability,
)
from app.models import (
    BackendMetadataRecord,
    ForecastModelRecord,
    GlucoseReadingRecord,
    IntakeEventRecord,
)


TRAINED_AT_MS = 1_700_000_000_000
DATA_CUTOFF_MS = TRAINED_AT_MS - 86_400_000
SAMPLE_COUNT = 2_304


def _promotion_metrics(
    *,
    mae: float,
    rmse: float,
    horizon_mae: float,
    interval_score: float,
) -> dict[str, float]:
    return {
        "mae": mae,
        "rmse": rmse,
        "mae_5": horizon_mae,
        "mae_15": horizon_mae,
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
        "hypo_recall": 0.90,
        "hypo_fpr": 0.10,
        "hypo_missed_episodes": 0.0,
        "low_zone_mae": horizon_mae,
    }


def _static_parameters(version: str, *, accepted: bool = True) -> dict:
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
        "alpha": STATIC_RIDGE_ALPHA,
        "coefficients": [[0.0] * 24 for _ in range(STATIC_FEATURE_COUNT)],
        "intercept": [0.0] * 24,
    }
    parameter_count = STATIC_FEATURE_COUNT * 24 + 24
    band_weights = [0.25, 0.50, 0.50, 0.25]
    blend: list[float] = []
    band_definitions: list[dict[str, float | int]] = []
    for (start, end), weight in zip(STATIC_BANDS, band_weights):
        blend.extend([weight] * (((end - start) // 5) + 1))
        band_definitions.append(
            {"start_minutes": start, "end_minutes": end, "weight": weight}
        )
    sigma = [20.0] * 24
    candidate_metrics = _promotion_metrics(
        mae=18.0, rmse=19.0, horizon_mae=9.0, interval_score=35.0
    )
    reference_metrics = _promotion_metrics(
        mae=20.0, rmse=22.0, horizon_mae=10.0, interval_score=40.0
    )
    day_results = [
        {"candidate_mae": 18.0, "reference_mae": 20.0, "pinned_mae": 20.0}
        for _ in range(14)
    ]
    gates = ForecastService.static_promotion_gates(
        candidate_metrics,
        reference_metrics,
        reference_metrics,
        day_results,
        test_day_count=14,
    )
    assert gates["accepted"] is True
    candidate_horizon_mae = [candidate_metrics["mae_5"]] * 24
    reference_horizon_mae = [reference_metrics["mae_5"]] * 24
    reliability = _static_reliability(
        candidate_metrics,
        reference_metrics,
        gates,
        test_days=14,
        independent_anchors=112,
        candidate_horizon_mae=candidate_horizon_mae,
        reference_horizon_mae=reference_horizon_mae,
    )
    evaluation = {
        "accepted": 1 if accepted else 0,
        "prospective": 1,
        "inconclusive": 0,
        "current_comparator_gate_passed": 1,
        "candidate_equal_day_mae": gates["candidate_equal_day_mae"],
        "reference_equal_day_mae": gates["reference_equal_day_mae"],
        "pinned_equal_day_mae": gates["pinned_equal_day_mae"],
        "candidate_anchor_mae": candidate_metrics["mae"],
        "reference_anchor_mae": reference_metrics["mae"],
        "pinned_anchor_mae": reference_metrics["mae"],
        "candidate_rmse": candidate_metrics["rmse"],
        "reference_rmse": reference_metrics["rmse"],
        "pinned_rmse": reference_metrics["rmse"],
        "candidate_coverage_80": candidate_metrics["coverage_80"],
        "candidate_interval_score_80": candidate_metrics["interval_score_80"],
        "reference_interval_score_80": reference_metrics["interval_score_80"],
        "pinned_interval_score_80": reference_metrics["interval_score_80"],
        "candidate_hypo_recall": candidate_metrics["hypo_recall"],
        "reference_hypo_recall": reference_metrics["hypo_recall"],
        "pinned_hypo_recall": reference_metrics["hypo_recall"],
        "candidate_hypo_fpr": candidate_metrics["hypo_fpr"],
        "reference_hypo_fpr": reference_metrics["hypo_fpr"],
        "pinned_hypo_fpr": reference_metrics["hypo_fpr"],
        "candidate_hypo_missed_episodes": candidate_metrics["hypo_missed_episodes"],
        "reference_hypo_missed_episodes": reference_metrics["hypo_missed_episodes"],
        "pinned_hypo_missed_episodes": reference_metrics["hypo_missed_episodes"],
        "candidate_low_zone_mae": candidate_metrics["low_zone_mae"],
        "reference_low_zone_mae": reference_metrics["low_zone_mae"],
        "pinned_low_zone_mae": reference_metrics["low_zone_mae"],
        "hypo_low_points": candidate_metrics["hypo_low_points"],
        "hypo_low_episodes": candidate_metrics["hypo_low_episodes"],
        "hypo_low_days": candidate_metrics["hypo_low_days"],
        "test_days": 14,
        "test_independent_anchors": 112,
        "winning_days": gates["winning_days"],
    }
    for prefix, metrics in (
        ("candidate", candidate_metrics),
        ("reference", reference_metrics),
        ("pinned", reference_metrics),
    ):
        for horizon in (5, 15, 30, 60, 120):
            evaluation[f"{prefix}_mae_{horizon}"] = metrics[f"mae_{horizon}"]
    for band_index in range(4):
        evaluation[f"candidate_coverage_band_{band_index}"] = candidate_metrics[
            f"coverage_band_{band_index}"
        ]
    for key, value in gates.items():
        if key in evaluation or key == "finite":
            continue
        evaluation[f"gate_{key}"] = int(value) if isinstance(value, bool) else value
    parameters.update(
        {
            "network": network,
            "model_selection": {
                "protocol": "chronological-tuning-only-ridge-grid-v1",
                "criterion": "lowest_tuning_mae_then_stronger_regularization",
                "selected_alpha": STATIC_RIDGE_ALPHA,
                "selected_tuning_mae": 9.0,
                "candidates": [
                    {
                        "alpha": alpha,
                        "tuning_mae": 9.0 if alpha == STATIC_RIDGE_ALPHA else 10.0,
                        "band_weights": band_weights,
                    }
                    for alpha in STATIC_RIDGE_ALPHAS
                ],
            },
            "persistence_blend_weights": blend,
            "residual_sigma": sigma,
            "frozen_calibration": {
                "method": "frozen-uncentered-conformal-v2",
                "quantile_method": "exact-order-statistic",
                "point_bias": "disabled",
                "low_guard_threshold_mg_dl": STATIC_LOW_GUARD_MG_DL,
                "safety_envelope": "reference-interval-union-v1",
                "interval_level": STATIC_INTERVAL_LEVEL,
                "sample_count": 32,
                "finite_sample_quantile": _finite_sample_quantile_level(32),
                "finite_sample_rank": 27,
                "bias_mg_dl": [0.0] * 24,
                "sigma_mg_dl": sigma,
                "reference_sigma_mg_dl": [24.0] * 24,
            },
            "artifact": {
                "artifact_version": 6,
                "engine_version": FORECAST_ENGINE_VERSION,
                "architecture": STATIC_PERSONAL_ARCHITECTURE,
                "feature_schema": STATIC_FEATURE_SCHEMA,
                "network_kind": STATIC_NETWORK_KIND,
                "training_mode": STATIC_TRAINING_MODE,
                "promotion_gate_version": STATIC_PROMOTION_GATE_VERSION,
                "accepted": accepted,
                "model_version": version,
                "trained_at_ms": TRAINED_AT_MS,
                "data_cutoff_ms": DATA_CUTOFF_MS,
                "sample_count": SAMPLE_COUNT,
                "interval_level": STATIC_INTERVAL_LEVEL,
                "dataset_sha256": "d" * 64,
                "feature_count": STATIC_FEATURE_COUNT,
                "parameter_count": parameter_count,
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
                "reliability": reliability,
            },
        }
    )
    predictor_hash = _static_predictor_hash(parameters)
    parameters["artifact"]["predictor_sha256"] = predictor_hash
    parameters["artifact"]["approval"] = {
        "state": "approved_prospective" if accepted else "rejected_prospective",
        "protocol": "frozen-future-local-days-v1",
        "minimum_new_days": 14,
        "strictly_after_ms": DATA_CUTOFF_MS,
        "dense_days": 14,
        "independent_anchors": 112,
        "cohort_start_ms": DATA_CUTOFF_MS + 86_400_000,
        "cohort_end_ms": DATA_CUTOFF_MS + 15 * 86_400_000,
        "predictor_sha256": predictor_hash,
        "pinned_comparator_version": BASELINE_VERSION,
        "pinned_comparator_sha256": _model_parameters_hash(_baseline_parameters()),
        "runtime_dependency_version": BASELINE_VERSION,
        "runtime_dependency_sha256": _model_parameters_hash(_baseline_parameters()),
        "candidate_metrics": candidate_metrics,
        "reference_metrics": reference_metrics,
        "pinned_metrics": reference_metrics,
        "current_metrics": reference_metrics,
        "candidate_horizon_mae": candidate_horizon_mae,
        "reference_horizon_mae": reference_horizon_mae,
        "pinned_day_results": day_results,
        "current_day_results": day_results,
    }
    parameters["artifact"]["content_sha256"] = _artifact_content_hash(parameters)
    return parameters


def _static_record(version: str, *, accepted: bool = True) -> ForecastModelRecord:
    parameters = _static_parameters(version, accepted=accepted)
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
        metrics_json=json.dumps(parameters["artifact"]["evaluation"], separators=(",", ":")),
        decision_reason="static contract fixture",
    )


def _display_parameters(version: str) -> dict:
    parameters = _static_parameters(version)
    artifact = parameters["artifact"]
    old_approval = artifact["approval"]
    candidate_metrics = old_approval["candidate_metrics"]
    reference_metrics = old_approval["reference_metrics"]
    day_results = old_approval["pinned_day_results"][:4]
    gates = ForecastService.static_display_gates(
        candidate_metrics,
        reference_metrics,
        reference_metrics,
        day_results,
        test_day_count=4,
    )
    assert gates["accepted"] is True
    evaluation = artifact["evaluation"]
    for key in list(evaluation):
        if key.startswith("gate_"):
            evaluation.pop(key)
    evaluation.update(
        {
            "accepted": 1,
            "prospective": 0,
            "display_only": 1,
            "gate_display_only": 1,
            "exploratory": 1,
            "unbiased_holdout": 0,
            "receipt_causal_validation": 0,
            "prospective_pending": 0,
            "development_only": 0,
            "gate_receipt_causal_evidence_sufficient": 1,
            "test_days": 4,
            "test_independent_anchors": 32,
            "winning_days": gates["winning_days"],
            "candidate_equal_day_mae": gates["candidate_equal_day_mae"],
            "reference_equal_day_mae": gates["reference_equal_day_mae"],
            "pinned_equal_day_mae": gates["pinned_equal_day_mae"],
        }
    )
    for key, value in gates.items():
        if key in evaluation or key == "finite":
            continue
        evaluation[f"gate_{key}"] = int(value) if isinstance(value, bool) else value
    candidate_horizon_mae = [candidate_metrics["mae_5"]] * 24
    reference_horizon_mae = [reference_metrics["mae_5"]] * 24
    artifact["reliability"] = _static_reliability(
        candidate_metrics,
        reference_metrics,
        gates,
        test_days=4,
        independent_anchors=32,
        candidate_horizon_mae=candidate_horizon_mae,
        reference_horizon_mae=reference_horizon_mae,
    )
    artifact["accepted"] = True
    artifact["receipt_causal_replay"] = {
        "validated_for_activation": False,
        "window_count": 64,
        "local_day_count": 4,
        "causal_history_rejections": 0,
        "causal_target_rejections": 10,
        "causal_stale_anchor_rejections": 0,
    }
    artifact["approval"] = {
        "state": "exploratory_retrospective_display",
        "protocol": STATIC_DISPLAY_PROTOCOL,
        "alert_approved": False,
        "unbiased_holdout": False,
        "receipt_causal_validation": False,
        "use_scope": "chart_only_not_for_dosing_or_alerts",
        "approved_model_version": version,
        "evaluated_at_ms": TRAINED_AT_MS,
        "test_days": 4,
        "independent_anchors": 32,
        "pinned_comparator_version": BASELINE_VERSION,
        "pinned_comparator_sha256": _model_parameters_hash(_baseline_parameters()),
        "runtime_dependency_version": BASELINE_VERSION,
        "runtime_dependency_sha256": _model_parameters_hash(_baseline_parameters()),
        "day_results": day_results,
        "candidate_metrics": candidate_metrics,
        "reference_metrics": reference_metrics,
        "pinned_metrics": reference_metrics,
        "candidate_horizon_mae": candidate_horizon_mae,
        "reference_horizon_mae": reference_horizon_mae,
        "predictor_sha256": artifact["predictor_sha256"],
    }
    artifact["content_sha256"] = _artifact_content_hash(parameters)
    return parameters


def _passing_alert_metrics(
    *,
    low_recall: float = 0.90,
    high_recall: float = 0.80,
    missed_low: float = 1.0,
    missed_high: float = 2.0,
    false_alerts_per_day: float = 0.20,
    lead_minutes: float = 30.0,
) -> dict[str, float | None]:
    return {
        "finite": 1.0,
        "evaluation_days": 14.0,
        "evaluated_anchors": 3_500.0,
        "low_episode_count": 10.0,
        "high_episode_count": 10.0,
        "low_episode_days": 5.0,
        "high_episode_days": 5.0,
        "low_selected_episode_recall": low_recall,
        "high_selected_episode_recall": high_recall,
        "low_selected_missed_episodes": missed_low,
        "high_selected_missed_episodes": missed_high,
        "selected_false_alerts_per_day": false_alerts_per_day,
        "low_selected_median_lead_minutes": lead_minutes,
        "high_selected_median_lead_minutes": lead_minutes,
    }


def _attach_passing_alert_validation(parameters: dict) -> dict:
    approval = parameters["artifact"]["approval"]
    selected_days = list(range(14))
    approval["selected_local_days"] = selected_days
    approval["local_days_sha256"] = hashlib.sha256(
        ",".join(str(day) for day in selected_days).encode("ascii")
    ).hexdigest()
    candidate = _passing_alert_metrics()
    approval["alert_validation_anchors"] = int(candidate["evaluated_anchors"])
    comparator = _passing_alert_metrics(
        low_recall=0.80,
        high_recall=0.80,
        missed_low=2.0,
        missed_high=2.0,
        false_alerts_per_day=0.30,
        lead_minutes=27.0,
    )
    gates = _alert_validation_gates(
        candidate, comparator, comparator, comparator
    )
    assert gates["accepted"] is True
    approval["alert_validation"] = {
        "protocol": ALERT_VALIDATION_PROTOCOL,
        "thresholds": _alert_validation_thresholds(),
        "local_days_sha256": approval.get("local_days_sha256"),
        "cohort_start_ms": approval["cohort_start_ms"],
        "cohort_end_ms": approval["cohort_end_ms"],
        "dense_days": approval["dense_days"],
        "candidate_metrics": candidate,
        "reference_metrics": comparator,
        "pinned_metrics": comparator,
        "current_metrics": comparator,
        "gates": gates,
    }
    approval["alert_approved"] = True
    parameters["artifact"]["content_sha256"] = _artifact_content_hash(parameters)
    return parameters


def _add_pending_prospective_fixture(
    session, service: ForecastService, version: str
) -> tuple[str, int, int, int]:
    step_ms = 5 * 60_000
    day_ms = 86_400_000
    base_ms = 1_700_006_400_000  # UTC midnight.
    cutoff_ms = base_ms + day_ms - step_ms
    freeze_time_ms = cutoff_ms + 10_000
    readings: list[GlucoseReadingRecord] = []
    for index in range(15 * 24 * 12):
        measured_at_ms = base_ms + index * step_ms
        readings.append(
            GlucoseReadingRecord(
                reading_id=f"{version}-reading-{index}",
                measured_at_ms=measured_at_ms,
                glucose_mg_dl=120.0,
                trend_mg_dl_min=0.0,
                sensor_id="test",
                sensor_generation="test",
                quality=1.0,
                utc_offset_minutes=0,
                payload_hash=f"{index:064x}"[-64:],
                received_at_ms=(
                    measured_at_ms + 1_000
                    if measured_at_ms <= cutoff_ms
                    else freeze_time_ms + (measured_at_ms - cutoff_ms) + 1_000
                ),
            )
        )
    training_readings = [
        row for row in readings if row.measured_at_ms <= cutoff_ms
    ]
    service._ensure_baseline(session)
    baseline = session.get(ForecastModelRecord, BASELINE_VERSION)
    assert baseline is not None
    parameters = _static_parameters(version)
    artifact = parameters["artifact"]
    artifact["accepted"] = False
    artifact["trained_at_ms"] = freeze_time_ms
    artifact["data_cutoff_ms"] = cutoff_ms
    artifact["dataset_sha256"] = _dataset_fingerprint(training_readings, [])
    artifact["snapshot"] = {
        "last_reading_at_ms": cutoff_ms,
        "max_received_at_ms": cutoff_ms + 1_000,
        "event_revision": 0,
        "active_event_count": 0,
    }
    artifact["evaluation"]["accepted"] = 0
    artifact["evaluation"]["prospective_pending"] = 1
    artifact["approval"] = {
        "state": "pending_prospective",
        "protocol": "frozen-future-local-days-v1",
        "minimum_new_days": 14,
        "strictly_after_ms": cutoff_ms,
        "freeze_time_ms": freeze_time_ms,
        "source_max_received_at_ms": cutoff_ms + 1_000,
        "pinned_comparator_version": BASELINE_VERSION,
        "pinned_comparator_sha256": _model_parameters_hash(_baseline_parameters()),
        "runtime_dependency_version": BASELINE_VERSION,
        "runtime_dependency_sha256": _model_parameters_hash(_baseline_parameters()),
        "predictor_sha256": artifact["predictor_sha256"],
    }
    artifact["content_sha256"] = _artifact_content_hash(parameters)
    predictor_hash = _static_predictor_hash(parameters)
    pending = ForecastModelRecord(
        version=version,
        status="pending",
        architecture=STATIC_PERSONAL_ARCHITECTURE,
        created_at_ms=freeze_time_ms,
        trained_at_ms=freeze_time_ms,
        promoted_at_ms=None,
        training_cutoff_ms=cutoff_ms,
        sample_count=SAMPLE_COUNT,
        parameters_json=json.dumps(parameters, separators=(",", ":")),
        metrics_json=json.dumps(artifact["evaluation"], separators=(",", ":")),
        decision_reason="pending prospective fixture",
    )
    session.add_all([*readings, pending])
    session.commit()
    return predictor_hash, cutoff_ms, freeze_time_ms, readings[-1].measured_at_ms


def test_day_block_gate_rejects_aggregate_win_concentrated_in_one_of_four_days():
    candidate = _promotion_metrics(
        mae=90.0, rmse=90.0, horizon_mae=9.0, interval_score=90.0
    )
    reference = _promotion_metrics(
        mae=100.0, rmse=100.0, horizon_mae=10.0, interval_score=100.0
    )
    day_results = [
        {"candidate_mae": 60.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 101.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 101.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 101.0, "reference_mae": 100.0, "pinned_mae": 100.0},
    ]

    result = ForecastService.static_promotion_gates(
        candidate, reference, reference, day_results, test_day_count=4
    )

    assert result["reference_equal_day_improvement"] > 0.08
    assert result["winning_days"] == 1
    assert result["required_winning_days"] == 3
    assert result["accepted"] is False


def test_day_block_gate_accepts_broad_safe_improvement():
    candidate = _promotion_metrics(
        mae=90.0, rmse=95.0, horizon_mae=9.0, interval_score=95.0
    )
    reference = _promotion_metrics(
        mae=100.0, rmse=100.0, horizon_mae=10.0, interval_score=100.0
    )
    day_results = [
        {"candidate_mae": 88.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 88.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 88.0, "reference_mae": 100.0, "pinned_mae": 100.0},
        {"candidate_mae": 101.0, "reference_mae": 100.0, "pinned_mae": 100.0},
    ]

    result = ForecastService.static_promotion_gates(
        candidate, reference, reference, day_results, test_day_count=4
    )

    assert result["winning_days"] == 3
    assert result["no_day_regression_over_2pct"] is True
    assert result["accepted"] is True


def test_activation_requires_approved_checksummed_static_artifact(app, client):
    del client  # Enter the application lifespan and create the database schema.
    service = app.state.forecast_service
    approved_version = "static-approved"
    rejected_version = "static-not-approved"
    approved = _static_record(approved_version)
    rejected = _static_record(rejected_version, accepted=False)
    approved_parameters = json.loads(approved.parameters_json)
    rejected_parameters = json.loads(rejected.parameters_json)
    assert _static_artifact_is_valid(approved_parameters) is True
    assert _static_artifact_is_valid(rejected_parameters) is False

    tampered = copy.deepcopy(approved_parameters)
    tampered["artifact"]["evaluation"]["candidate_equal_day_mae"] += 1.0
    assert _static_artifact_is_valid(tampered) is False

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add_all([approved, rejected])
        session.commit()
        assert service._champion(session).version == BASELINE_VERSION

        with pytest.raises(ValueError, match="approved, checksummed static model"):
            service.activate_model(session, rejected_version)
        pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
        assert pin is not None and pin.value_text == BASELINE_VERSION

        activated = service.activate_model(session, approved_version)
        assert activated.version == approved_version
        # Forecast activation is independent from the stricter alert evidence.
        assert service._alert_delivery_is_approved(session, activated) is False
        pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
        assert pin is not None and pin.value_text == approved_version


def test_activation_keeps_runtime_dependency_flat_across_many_releases(app, client):
    del client
    service = app.state.forecast_service
    records: list[ForecastModelRecord] = []
    previous_version = BASELINE_VERSION
    previous_parameters = _baseline_parameters()
    for index in range(40):
        record = _static_record(f"static-flat-chain-{index}")
        parameters = json.loads(record.parameters_json)
        parameters["artifact"]["approval"].update(
            {
                "pinned_comparator_version": previous_version,
                "pinned_comparator_sha256": _model_parameters_hash(
                    previous_parameters
                ),
            }
        )
        parameters["artifact"]["content_sha256"] = _artifact_content_hash(
            parameters
        )
        record.parameters_json = json.dumps(parameters, separators=(",", ":"))
        assert _static_artifact_is_valid(parameters) is True
        records.append(record)
        previous_version = record.version
        previous_parameters = parameters

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add_all(records)
        session.commit()
        activated = service.activate_model(session, records[-1].version)
        assert activated.version == "static-flat-chain-39"
        session.delete(records[-2])
        session.commit()
        assert service._champion(session).version == activated.version


def test_activation_still_requires_direct_comparator_provenance(app, client):
    del client
    service = app.state.forecast_service
    record = _static_record("static-missing-comparator")
    parameters = json.loads(record.parameters_json)
    parameters["artifact"]["approval"].update(
        {
            "pinned_comparator_version": "missing-static-comparator",
            "pinned_comparator_sha256": "0" * 64,
        }
    )
    parameters["artifact"]["content_sha256"] = _artifact_content_hash(parameters)
    record.parameters_json = json.dumps(parameters, separators=(",", ":"))
    assert _static_artifact_is_valid(parameters) is True

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add(record)
        session.commit()
        with pytest.raises(ValueError, match="approved, checksummed static model"):
            service.activate_model(session, record.version)
        assert service._champion(session).version == BASELINE_VERSION


def test_alert_delivery_requires_explicit_checksummed_approval_flag(app, client):
    del client
    service = app.state.forecast_service
    record = _static_record("static-alert-approval")
    record.status = "champion"

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add(record)
        session.commit()

        # A forecast-approved artifact is shadow-only until alert safety has an
        # independent, explicit approval bit.
        assert service._alert_delivery_is_approved(session, record) is False

        tampered = json.loads(record.parameters_json)
        tampered["artifact"]["approval"]["alert_approved"] = True
        record.parameters_json = json.dumps(tampered, separators=(",", ":"))
        session.commit()
        # Changing only the bit without updating the complete digest fails.
        assert service._alert_delivery_is_approved(session, record) is False

        tampered["artifact"]["content_sha256"] = _artifact_content_hash(tampered)
        record.parameters_json = json.dumps(tampered, separators=(",", ":"))
        session.commit()
        # A recomputed digest is still insufficient without the separately
        # preregistered episode-level validation envelope.
        assert _static_artifact_is_valid(tampered) is False
        assert service._alert_delivery_is_approved(session, record) is False

        tampered = _attach_passing_alert_validation(tampered)
        record.parameters_json = json.dumps(tampered, separators=(",", ":"))
        session.commit()
        assert _static_artifact_is_valid(tampered) is True
        assert service._alert_delivery_is_approved(session, record) is True

        forged_metrics = copy.deepcopy(tampered)
        forged_metrics["artifact"]["approval"]["alert_validation"][
            "candidate_metrics"
        ]["low_selected_episode_recall"] = 0.10
        forged_metrics["artifact"]["content_sha256"] = _artifact_content_hash(
            forged_metrics
        )
        record.parameters_json = json.dumps(
            forged_metrics, separators=(",", ":")
        )
        session.commit()
        assert _static_artifact_is_valid(forged_metrics) is False
        assert service._alert_delivery_is_approved(session, record) is False


def test_episode_alert_metrics_use_actual_episodes_and_warning_lead():
    base_ms = 1_800_000_000_000
    prediction = np.full((2, 24), 110.0)
    target = np.full((2, 24), 110.0)
    prediction[0, 3:5] = 70.0
    target[0, 5:7] = 70.0
    prediction[1, 3:5] = 170.0
    target[1, 5:7] = 170.0

    metrics = _alert_episode_metrics(
        prediction,
        target,
        np.full(24, 6.0),
        anchor_times_ms=[base_ms, base_ms + 120 * 60_000],
        decision_times_ms=[base_ms, base_ms + 120 * 60_000],
        anchor_glucose_mg_dl=[110.0, 110.0],
        anchor_utc_offset_minutes=[0, 0],
        delivery_ready=[True, True],
    )

    assert metrics["finite"] == 1.0
    assert metrics["low_episode_count"] == 1.0
    assert metrics["high_episode_count"] == 1.0
    assert metrics["low_selected_episode_recall"] == 1.0
    assert metrics["high_selected_episode_recall"] == 1.0
    assert metrics["low_selected_median_lead_minutes"] == 29.0
    assert metrics["high_selected_median_lead_minutes"] == 29.0
    assert metrics["selected_false_alerts_per_day"] == 0.0


def test_episode_alert_replay_uses_issue_time_for_crossing_and_cooldown():
    base_ms = 1_800_000_000_000

    # The first forecast's earliest crossing is already in the past when its
    # fresh (nine-minute-old) anchor reaches the backend. Android rejects this
    # crossing, so prospective replay must not credit it with episode recall.
    prediction = np.full((1, 24), 110.0)
    prediction[0, :3] = 70.0
    target = np.full((1, 24), 110.0)
    target[0, 4:6] = 70.0
    late = _alert_episode_metrics(
        prediction,
        target,
        np.full(24, 6.0),
        anchor_times_ms=[base_ms],
        decision_times_ms=[base_ms + 9 * 60_000],
        anchor_glucose_mg_dl=[110.0],
        anchor_utc_offset_minutes=[0],
        delivery_ready=[True],
    )
    assert late["low_selected_alert_count"] == 0.0
    assert late["low_selected_episode_recall"] == 0.0
    assert late["low_selected_missed_episodes"] == 1.0

    # A crossing only thirty seconds after server receipt is not credited: the
    # preregistered one-minute delivery margin puts it in the past by the time
    # Android can evaluate the response.
    margin = _alert_episode_metrics(
        prediction,
        target,
        np.full(24, 6.0),
        anchor_times_ms=[base_ms],
        decision_times_ms=[base_ms + 4 * 60_000 + 30_000],
        anchor_glucose_mg_dl=[110.0],
        anchor_utc_offset_minutes=[0],
        delivery_ready=[True],
    )
    assert margin["low_selected_alert_count"] == 0.0
    assert margin["low_selected_episode_recall"] == 0.0

    # These anchors are ten minutes apart, but their actual issue times are
    # exactly one 15-minute cooldown apart. Both are deliverable, matching the
    # phone's `< cooldown` suppression rule.
    prediction = np.full((2, 24), 110.0)
    prediction[:, 3:5] = 70.0
    target = np.full((2, 24), 110.0)
    cooldown = _alert_episode_metrics(
        prediction,
        target,
        np.full(24, 6.0),
        anchor_times_ms=[base_ms, base_ms + 10 * 60_000],
        decision_times_ms=[base_ms, base_ms + 15 * 60_000],
        anchor_glucose_mg_dl=[110.0, 110.0],
        anchor_utc_offset_minutes=[0, 0],
        delivery_ready=[True, True],
    )
    assert cooldown["low_selected_alert_count"] == 2.0


def test_alert_replay_reuses_live_meal_uncertainty_and_quality_suppression():
    anchor = 1_800_000_000_000

    def history(quality: float) -> list[GlucoseReadingRecord]:
        result: list[GlucoseReadingRecord] = []
        for index in range(24):
            measured_at_ms = anchor - (23 - index) * 5 * 60_000
            result.append(
                GlucoseReadingRecord(
                    reading_id=f"runtime-{quality}-{index}",
                    measured_at_ms=measured_at_ms,
                    glucose_mg_dl=150.0,
                    trend_mg_dl_min=0.0,
                    sensor_id="test",
                    sensor_generation="test",
                    quality=quality,
                    utc_offset_minutes=0,
                    payload_hash=f"{index:064x}",
                    received_at_ms=measured_at_ms,
                )
            )
        return result

    parameters = _default_parameters()
    uncertain_meal = _Event(
        event_id="uncertain-meal",
        occurred_at_ms=anchor,
        kind="meal",
        label="Meal",
        amount=100.0,
        known_at_ms=anchor,
        carbs_low_g=20.0,
        carbs_high_g=180.0,
        absorption_confidence=0.2,
        ai_confidence=0.2,
    )
    status, _confidence, _coverage, meal_sigma, _event_confidence = (
        ForecastService._runtime_forecast_adjustments(
            history(1.0), [uncertain_meal], anchor, parameters
        )
    )
    assert status == "ok"
    assert float(meal_sigma[-1]) > 0.0

    prediction = np.full((1, 24), 150.0)
    target = np.full((1, 24), 110.0)

    def metrics(sigma: np.ndarray, *, ready: bool) -> dict[str, float | None]:
        return _alert_episode_metrics(
            prediction,
            target,
            sigma,
            anchor_times_ms=[anchor],
            decision_times_ms=[anchor],
            anchor_glucose_mg_dl=[150.0],
            anchor_utc_offset_minutes=[0],
            delivery_ready=[ready],
        )

    assert metrics(np.full(24, 6.0), ready=True)[
        "high_selected_alert_count"
    ] == 0.0
    widened = np.sqrt(np.full(24, 6.0) ** 2 + meal_sigma**2)
    assert metrics(widened, ready=True)["high_selected_alert_count"] == 1.0

    low_quality_status, *_rest = ForecastService._runtime_forecast_adjustments(
        history(0.0), [], anchor, parameters
    )
    assert low_quality_status == "low_confidence"
    assert metrics(widened, ready=low_quality_status == "ok")[
        "high_selected_alert_count"
    ] == 0.0


def test_alert_replay_arbitrates_one_android_direction_per_issue():
    anchor = 1_800_000_000_000
    target = np.full((1, 24), 110.0)

    def metrics(prediction: np.ndarray, sigma: np.ndarray):
        return _alert_episode_metrics(
            prediction,
            target,
            sigma,
            anchor_times_ms=[anchor],
            decision_times_ms=[anchor],
            anchor_glucose_mg_dl=[110.0],
            anchor_utc_offset_minutes=[0],
            delivery_ready=[True],
        )

    # An extremely wide interval crosses both targets at the same time. Android
    # emits one alert and its final deterministic tie-break selects low.
    both_possible = metrics(
        np.full((1, 24), 110.0), np.full(24, 100.0)
    )
    assert both_possible["low_selected_alert_count"] == 1.0
    assert both_possible["high_selected_alert_count"] == 0.0
    assert both_possible["low_selected_possible_count"] == 1.0
    assert both_possible["selected_false_alerts_per_day"] == 1.0

    # At an equal crossing time, likely evidence outranks possible evidence
    # before the final low-direction tie-break.
    high_likely_prediction = np.full((1, 24), 110.0)
    high_likely_prediction[0, :2] = 170.0
    likely_tie = metrics(high_likely_prediction, np.full(24, 80.0))
    assert likely_tie["low_selected_alert_count"] == 0.0
    assert likely_tie["high_selected_alert_count"] == 1.0
    assert likely_tie["high_selected_likely_count"] == 1.0


def test_episode_rearm_and_matching_do_not_reuse_one_alert():
    anchor = 1_800_000_000_000
    prediction = np.full((1, 24), 110.0)
    prediction[0, 3:5] = 70.0

    def metrics(target: np.ndarray) -> dict[str, float | None]:
        return _alert_episode_metrics(
            prediction,
            target,
            np.full(24, 6.0),
            anchor_times_ms=[anchor],
            decision_times_ms=[anchor],
            anchor_glucose_mg_dl=[110.0],
            anchor_utc_offset_minutes=[0],
            delivery_ready=[True],
        )

    # One in-target point is not enough to rearm a distinct low episode.
    not_rearmed_target = np.full((1, 24), 110.0)
    not_rearmed_target[0, 5:7] = 70.0
    not_rearmed_target[0, 8:10] = 70.0
    not_rearmed = metrics(not_rearmed_target)
    assert not_rearmed["low_episode_count"] == 1.0
    assert not_rearmed["low_selected_episode_recall"] == 1.0

    # Three in-target five-minute readings establish the preregistered rearm
    # gap. The single earlier alert may match only the first distinct episode.
    rearmed_target = np.full((1, 24), 110.0)
    rearmed_target[0, 5:7] = 70.0
    rearmed_target[0, 10:12] = 70.0
    rearmed = metrics(rearmed_target)
    assert rearmed["low_episode_count"] == 2.0
    assert rearmed["low_selected_alert_count"] == 1.0
    assert rearmed["low_selected_episode_recall"] == 0.5
    assert rearmed["low_selected_missed_episodes"] == 1.0
    assert rearmed["selected_false_alerts_per_day"] == 0.0


def test_episode_alert_gates_are_independent_and_fail_closed():
    candidate = _passing_alert_metrics()
    comparator = _passing_alert_metrics(
        low_recall=0.80,
        high_recall=0.80,
        missed_low=2.0,
        missed_high=2.0,
        false_alerts_per_day=0.30,
        lead_minutes=27.0,
    )
    passing = _alert_validation_gates(
        candidate, comparator, comparator, comparator
    )
    assert passing["evidence_sufficient"] is True
    assert passing["accepted"] is True

    exact_boundary = {
        **candidate,
        "low_episode_count": 5.0,
        "high_episode_count": 8.0,
        "low_selected_episode_recall": 0.80,
        "high_selected_episode_recall": 0.75,
        "low_selected_missed_episodes": 1.0,
        "high_selected_missed_episodes": 2.0,
        "selected_false_alerts_per_day": 1.0,
        "low_selected_median_lead_minutes": 15.0,
        "high_selected_median_lead_minutes": 15.0,
    }
    assert _alert_validation_gates(
        exact_boundary, exact_boundary, exact_boundary, exact_boundary
    )["accepted"] is True

    insufficient = _alert_validation_gates(
        {
            **candidate,
            "low_episode_count": 4.0,
            "low_episode_days": 3.0,
            "low_selected_episode_recall": 1.0,
            "low_selected_missed_episodes": 0.0,
        },
        comparator,
        comparator,
        comparator,
    )
    assert insufficient["evidence_sufficient"] is False
    assert insufficient["accepted"] is False

    noisy = _alert_validation_gates(
        {**candidate, "selected_false_alerts_per_day": 1.01},
        comparator,
        comparator,
        comparator,
    )
    assert noisy["false_alert_rate_absolute"] is False
    assert noisy["accepted"] is False

    low_recall_candidate = {
        **candidate,
        "low_episode_count": 5.0,
        "low_selected_episode_recall": 0.60,
        "low_selected_missed_episodes": 2.0,
    }
    low_recall_comparator = {
        **comparator,
        "low_episode_count": 5.0,
        "low_selected_episode_recall": 0.80,
        "low_selected_missed_episodes": 1.0,
    }
    low_recall = _alert_validation_gates(
        low_recall_candidate,
        low_recall_comparator,
        low_recall_comparator,
        low_recall_comparator,
    )
    assert low_recall["low_recall_absolute"] is False
    assert low_recall["accepted"] is False

    late = _alert_validation_gates(
        {
            **candidate,
            "low_selected_median_lead_minutes": 14.9,
        },
        comparator,
        comparator,
        comparator,
    )
    assert late["median_lead_absolute"] is False
    assert late["accepted"] is False


def test_current_uses_runtime_valid_pinned_alert_approved_champion(
    app, client, auth_headers
):
    service = app.state.forecast_service
    version = "static-alert-current-e2e"
    record = _static_record(version)
    parameters = json.loads(record.parameters_json)
    parameters = _attach_passing_alert_validation(parameters)
    record.parameters_json = json.dumps(parameters, separators=(",", ":"))

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add(record)
        session.commit()
        activated = service.activate_model(session, version)
        assert activated.status == "champion"
        assert service._alert_delivery_is_approved(session, activated) is True

    anchor = int(time.time() * 1_000) - 1_000
    readings = [
        {
            "reading_id": f"alert-approved-e2e-{index}",
            "measured_at_ms": anchor - (23 - index) * 5 * 60_000,
            "glucose_mg_dl": 110.0 + index * 0.1,
            "trend_mg_dl_min": 0.0,
            "quality": 1.0,
        }
        for index in range(24)
    ]
    uploaded = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": readings},
    )
    assert uploaded.status_code == 200

    response = client.get("/v1/forecast/current", headers=auth_headers)
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "ready"
    assert payload["model_version"] == version
    assert payload["based_on_glucose_mg_dl"] == readings[-1]["glucose_mg_dl"]
    assert payload["alert_assessment"]["monitoring_status"] == "eligible"
    assert payload["alert_assessment"]["delivery_eligible"] is True
    assert payload["alert_assessment"]["suppressed_reasons"] == []


def test_pending_and_development_artifacts_cannot_activate(app, client):
    del client
    service = app.state.forecast_service
    pending = _static_record("static-pending")
    pending_parameters = json.loads(pending.parameters_json)
    pending_parameters["artifact"]["accepted"] = False
    pending_parameters["artifact"]["evaluation"]["accepted"] = 0
    pending_parameters["artifact"]["evaluation"]["prospective_pending"] = 1
    pending_parameters["artifact"]["approval"]["state"] = "pending_prospective"
    pending_parameters["artifact"]["content_sha256"] = _artifact_content_hash(
        pending_parameters
    )
    pending.parameters_json = json.dumps(pending_parameters, separators=(",", ":"))
    pending.metrics_json = json.dumps(
        pending_parameters["artifact"]["evaluation"], separators=(",", ":")
    )
    pending.status = "pending"

    development = _static_record("static-development")
    development_parameters = json.loads(development.parameters_json)
    development_parameters["artifact"].pop("approval")
    development_parameters["artifact"]["content_sha256"] = _artifact_content_hash(
        development_parameters
    )
    development.parameters_json = json.dumps(
        development_parameters, separators=(",", ":")
    )

    assert _static_artifact_is_valid(
        pending_parameters, require_approved=False
    ) is True
    assert _static_artifact_is_valid(pending_parameters) is False
    assert _static_artifact_is_valid(development_parameters) is False

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add_all([pending, development])
        session.commit()
        for version in (pending.version, development.version):
            with pytest.raises(ValueError, match="approved, checksummed static model"):
                service.activate_model(session, version)


def test_display_only_artifact_can_activate_but_never_deliver_alerts(app, client):
    del client
    service = app.state.forecast_service
    version = "static-display-only"
    parameters = _display_parameters(version)
    assert _static_artifact_is_valid(parameters) is True

    tampered = copy.deepcopy(parameters)
    tampered["artifact"]["approval"]["alert_approved"] = True
    tampered["artifact"]["content_sha256"] = _artifact_content_hash(tampered)
    assert _static_artifact_is_valid(tampered) is False

    record = ForecastModelRecord(
        version=version,
        status="candidate",
        architecture=STATIC_PERSONAL_ARCHITECTURE,
        created_at_ms=TRAINED_AT_MS,
        trained_at_ms=TRAINED_AT_MS,
        promoted_at_ms=None,
        training_cutoff_ms=DATA_CUTOFF_MS,
        sample_count=SAMPLE_COUNT,
        parameters_json=json.dumps(parameters, separators=(",", ":")),
        metrics_json=json.dumps(
            parameters["artifact"]["evaluation"], separators=(",", ":")
        ),
        decision_reason="display-only fixture",
    )
    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add(record)
        session.commit()
        activated = service.activate_model(session, version)
        assert activated.status == "champion"
        assert service._alert_delivery_is_approved(session, activated) is False


def test_validator_recomputes_safety_gates_and_exact_reliability_cap():
    valid = _static_parameters("static-recomputed-gates")
    assert _static_artifact_is_valid(valid) is True

    tampered_hypo = copy.deepcopy(valid)
    tampered_hypo["artifact"]["evaluation"]["candidate_hypo_recall"] = 0.01
    tampered_hypo["artifact"]["content_sha256"] = _artifact_content_hash(
        tampered_hypo
    )
    assert _static_artifact_is_valid(tampered_hypo) is False

    tampered_gate = copy.deepcopy(valid)
    tampered_gate["artifact"]["evaluation"]["gate_interval_score_safe"] = 0
    tampered_gate["artifact"]["content_sha256"] = _artifact_content_hash(
        tampered_gate
    )
    assert _static_artifact_is_valid(tampered_gate) is False

    tampered_cap = copy.deepcopy(valid)
    tampered_cap["artifact"]["reliability"]["test_day_cap"] = 0.35
    tampered_cap["artifact"]["content_sha256"] = _artifact_content_hash(
        tampered_cap
    )
    assert _static_artifact_is_valid(tampered_cap) is False

    tampered_reliability = copy.deepcopy(valid)
    tampered_reliability["artifact"]["reliability"]["overall"] += 0.01
    tampered_reliability["artifact"]["content_sha256"] = _artifact_content_hash(
        tampered_reliability
    )
    assert _static_artifact_is_valid(tampered_reliability) is False

    tampered_audit = copy.deepcopy(valid)
    tampered_audit["artifact"]["evaluation"]["hypo_low_points"] += 1.0
    tampered_audit["artifact"]["content_sha256"] = _artifact_content_hash(
        tampered_audit
    )
    assert _static_artifact_is_valid(tampered_audit) is False

    tampered_selection = copy.deepcopy(valid)
    tampered_selection["model_selection"]["selected_alpha"] = STATIC_RIDGE_ALPHAS[-1]
    tampered_selection["artifact"]["content_sha256"] = _artifact_content_hash(
        tampered_selection
    )
    assert _static_artifact_is_valid(tampered_selection) is False


def test_prospective_cohort_is_the_earliest_fixed_fourteen_whole_days():
    day_ms = 86_400_000
    base_ms = 1_700_006_400_000  # UTC midnight.
    readings: list[GlucoseReadingRecord] = []
    for index in range(18 * 24 * 12):
        measured_at_ms = base_ms + index * 300_000
        readings.append(
            GlucoseReadingRecord(
                reading_id=f"r-{index}",
                measured_at_ms=measured_at_ms,
                glucose_mg_dl=120.0,
                trend_mg_dl_min=0.0,
                sensor_id="test",
                sensor_generation="test",
                quality=1.0,
                utc_offset_minutes=0,
                payload_hash=f"{index:064x}"[-64:],
                received_at_ms=measured_at_ms + 1_000,
            )
        )
    windows = [
        (index, np.full(24, 120.0))
        for index in range(len(readings) - 24)
    ]
    cutoff_ms = base_ms + 2 * day_ms - 300_000

    selected, manifest = ForecastService._prospective_dense_windows(
        readings, windows, strictly_after_ms=cutoff_ms
    )

    selected_days = {
        ForecastService._window_local_day(readings, window) for window in selected
    }
    assert manifest["available_dense_days"] == 16
    assert manifest["dense_days"] == 14
    assert len(selected_days) == 14
    assert min(selected_days) == (base_ms // day_ms) + 2
    assert max(selected_days) == (base_ms // day_ms) + 15
    assert all(readings[window[0]].measured_at_ms > cutoff_ms for window in selected)


def test_prospective_causal_windows_reject_bulk_uploaded_future_labels():
    base_ms = 1_700_006_400_000

    def rows(*, bulk: bool) -> list[GlucoseReadingRecord]:
        result: list[GlucoseReadingRecord] = []
        for index in range(8 * 12):
            measured_at_ms = base_ms + index * 300_000
            result.append(
                GlucoseReadingRecord(
                    reading_id=f"{'b' if bulk else 'n'}-{index}",
                    measured_at_ms=measured_at_ms,
                    glucose_mg_dl=120.0,
                    trend_mg_dl_min=0.0,
                    sensor_id="test",
                    sensor_generation="test",
                    quality=1.0,
                    utc_offset_minutes=0,
                    payload_hash=f"{index:064x}"[-64:],
                    received_at_ms=(
                        base_ms + 9 * 3_600_000
                        if bulk
                        else measured_at_ms + 1_000
                    ),
                )
            )
        return result

    causal, causal_manifest = ForecastService._prospective_causal_windows(
        rows(bulk=False)
    )
    bulk, bulk_manifest = ForecastService._prospective_causal_windows(rows(bulk=True))

    assert causal
    assert causal_manifest["causal_target_rejections"] == 0
    assert bulk == []
    assert bulk_manifest["causal_target_rejections"] > 0


def test_prospective_causal_windows_reject_sequential_stale_backfill():
    base_ms = 1_700_006_400_000
    readings: list[GlucoseReadingRecord] = []
    for index in range(9 * 12):
        measured_at_ms = base_ms + index * 300_000
        readings.append(
            GlucoseReadingRecord(
                reading_id=f"backfill-{index}",
                measured_at_ms=measured_at_ms,
                glucose_mg_dl=120.0,
                trend_mg_dl_min=0.0,
                sensor_id="test",
                sensor_generation="test",
                quality=1.0,
                utc_offset_minutes=0,
                payload_hash=f"{index:064x}"[-64:],
                # Receipt order is causal, but every historical anchor would
                # have been stale under the live forecasting contract.
                received_at_ms=measured_at_ms + 60 * 60_000,
            )
        )

    windows, manifest = ForecastService._prospective_causal_windows(readings)

    assert windows == []
    assert manifest["causal_stale_anchor_rejections"] > 0


def test_non_hypo_gate_distinguishes_inconclusive_from_bad_point_model():
    candidate = _promotion_metrics(
        mae=18.0, rmse=19.0, horizon_mae=9.0, interval_score=35.0
    )
    reference = _promotion_metrics(
        mae=20.0, rmse=22.0, horizon_mae=10.0, interval_score=40.0
    )
    no_hypo_candidate = {
        **candidate,
        "hypo_low_points": 0.0,
        "hypo_low_episodes": 0.0,
        "hypo_low_days": 0.0,
        "hypo_recall": None,
        "low_zone_mae": None,
    }
    day_results = [
        {"candidate_mae": 18.0, "reference_mae": 20.0, "pinned_mae": 20.0}
        for _ in range(14)
    ]

    safe_without_hypo = ForecastService._non_hypo_promotion_gates(
        no_hypo_candidate,
        reference,
        reference,
        day_results,
        test_day_count=14,
    )
    bad_without_hypo = ForecastService._non_hypo_promotion_gates(
        {**no_hypo_candidate, "mae": 25.0, "rmse": 25.0},
        reference,
        reference,
        [
            {
                "candidate_mae": 25.0,
                "reference_mae": 20.0,
                "pinned_mae": 20.0,
            }
            for _ in range(14)
        ],
        test_day_count=14,
    )

    assert safe_without_hypo["accepted"] is True
    assert bad_without_hypo["accepted"] is False


def test_prospective_reference_includes_event_known_between_measurement_and_receipt():
    anchor_ms = 1_700_006_400_000
    anchor = GlucoseReadingRecord(
        reading_id="anchor",
        measured_at_ms=anchor_ms,
        glucose_mg_dl=120.0,
        trend_mg_dl_min=0.0,
        sensor_id="test",
        sensor_generation="test",
        quality=1.0,
        utc_offset_minutes=0,
        payload_hash="a" * 64,
        received_at_ms=anchor_ms + 10 * 60_000,
    )
    event = _Event(
        event_id="00000000-0000-0000-0000-000000000001",
        occurred_at_ms=anchor_ms + 5 * 60_000,
        known_at_ms=anchor_ms + 6 * 60_000,
        kind="meal",
        label="future-known meal",
        amount=40.0,
    )
    windows = [(0, np.full(24, 120.0))]
    parameters = _default_parameters()

    legacy = ForecastService._reference_for_windows(
        [anchor], [event], windows, parameters
    )
    prospective = ForecastService._reference_for_windows(
        [anchor],
        [event],
        windows,
        parameters,
        decision_times_ms={0: anchor.received_at_ms},
    )

    np.testing.assert_allclose(legacy, np.full((1, 24), 120.0))
    assert prospective[0, -1] > legacy[0, -1]


def test_prospective_evaluation_finalizes_pending_candidate_once(app, client):
    del client
    service = app.state.forecast_service
    version = "static-prospective-e2e"

    with app.state.database.session_factory() as session:
        predictor_before, _cutoff, _freeze, _latest = (
            _add_pending_prospective_fixture(session, service, version)
        )

        result = service.evaluate_static_candidate(session, version)
        stored = session.get(ForecastModelRecord, version)
        assert stored is not None
        stored_parameters = json.loads(stored.parameters_json)

        assert result.status == "rejected"
        assert stored.status == "rejected"
        assert stored_parameters["artifact"]["approval"]["state"] == (
            "rejected_prospective"
        )
        assert stored_parameters["artifact"]["approval"]["alert_approved"] is False
        alert_validation = stored_parameters["artifact"]["approval"][
            "alert_validation"
        ]
        assert alert_validation["protocol"] == ALERT_VALIDATION_PROTOCOL
        assert alert_validation["gates"]["evidence_sufficient"] is False
        assert alert_validation["gates"]["accepted"] is False
        assert stored_parameters["artifact"]["evaluation"]["inconclusive"] == 0
        assert _static_predictor_hash(stored_parameters) == predictor_before
        with pytest.raises(ValueError, match="not a pending prospective candidate"):
            service.evaluate_static_candidate(session, version)


@pytest.mark.parametrize("deleted", [False, True], ids=["edited", "deleted"])
def test_intake_mutation_terminalizes_pending_and_releases_next_freeze(
    app, client, deleted
):
    del client
    service = app.state.forecast_service
    version = f"static-mutation-{'delete' if deleted else 'edit'}"

    with app.state.database.session_factory() as session:
        _predictor, cutoff_ms, _freeze_ms, latest_ms = (
            _add_pending_prospective_fixture(session, service, version)
        )
        pending = session.get(ForecastModelRecord, version)
        assert pending is not None
        original_parameters_json = pending.parameters_json
        occurred_at_ms = cutoff_ms + 60 * 60_000
        updated_at_ms = occurred_at_ms + 10 * 60_000
        suffix = "2" if deleted else "1"
        session.add(
            IntakeEventRecord(
                id=f"00000000-0000-0000-0000-00000000000{suffix}",
                client_event_id=(
                    f"10000000-0000-0000-0000-00000000000{suffix}"
                ),
                occurred_at_ms=occurred_at_ms,
                meal_text="edited prospective meal",
                carbs_g=35.0,
                portion_g=200.0,
                original_portion_g=250.0,
                original_carbs_g=44.0,
                carbs_source="manual",
                payload_hash=suffix * 64,
                created_at_ms=occurred_at_ms,
                updated_at_ms=updated_at_ms,
                deleted_at_ms=updated_at_ms if deleted else None,
                sync_version=1,
            )
        )
        session.commit()

        result = service.evaluate_static_candidate(session, version)
        stored = session.get(ForecastModelRecord, version)

        assert result.status == "rejected"
        assert result.metrics["permanent_invalidation"] == 1
        assert stored is not None
        assert stored.status == "rejected"
        assert "intake edits/deletions" in (stored.decision_reason or "")
        assert stored.parameters_json == original_parameters_json
        # The terminal record no longer occupies the singleton pending slot;
        # a newly frozen candidate at the latest reading can register.
        service._assert_prospective_registration_allowed(
            session, cutoff_ms=latest_ms
        )


def test_active_comparator_pin_change_aborts_final_decision(
    app, client, monkeypatch
):
    del client
    service = app.state.forecast_service
    version = "static-active-pin-race"

    with app.state.database.session_factory() as session:
        _add_pending_prospective_fixture(session, service, version)
        replacement = _static_record("static-race-replacement")
        session.add(replacement)
        session.commit()
        original_metrics = ForecastService._metrics
        changed = False

        def metrics_with_pin_change(prediction, target):
            nonlocal changed
            if not changed:
                pin = session.get(
                    BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY
                )
                assert pin is not None
                pin.value_text = replacement.version
                session.commit()
                changed = True
            return original_metrics(prediction, target)

        monkeypatch.setattr(service, "_metrics", metrics_with_pin_change)

        result = service.evaluate_static_candidate(session, version)
        stored = session.get(ForecastModelRecord, version)

        assert changed is True
        assert result.status == "skipped"
        assert result.metrics["active_comparator_changed"] == 1
        assert stored is not None and stored.status == "pending"


def test_active_comparator_pin_change_aborts_training_freeze(
    app, client, monkeypatch
):
    del client
    import app.forecast as forecast_module

    service = app.state.forecast_service
    version = "static-training-pin-race"
    replacement = _static_record("static-training-race-replacement")
    base_ms = 1_700_006_400_000  # UTC midnight.
    step_ms = 5 * 60_000

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add(replacement)
        session.add_all(
            [
                GlucoseReadingRecord(
                    reading_id=f"training-race-{index}",
                    measured_at_ms=base_ms + index * step_ms,
                    glucose_mg_dl=120.0,
                    trend_mg_dl_min=0.0,
                    sensor_id="test",
                    sensor_generation="test",
                    quality=1.0,
                    utc_offset_minutes=0,
                    payload_hash=f"{index:064x}"[-64:],
                    received_at_ms=base_ms + index * step_ms + 1_000,
                )
                for index in range(17 * 24 * 12)
            ]
        )
        session.commit()

        original_fit = forecast_module._fit_network
        changed = False

        def fit_with_pin_change(
            features,
            residual,
            alpha=forecast_module.STATIC_RIDGE_ALPHA,
        ):
            nonlocal changed
            fitted = original_fit(features, residual, alpha)
            if not changed:
                pin = session.get(
                    BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY
                )
                assert pin is not None
                pin.value_text = replacement.version
                session.commit()
                changed = True
            return fitted

        monkeypatch.setattr(forecast_module, "_fit_network", fit_with_pin_change)

        result = service.train_static_model(
            session, candidate_version=version, stage_pending=True
        )

        assert changed is True
        assert result.status == "skipped"
        assert result.metrics["active_comparator_changed"] == 1
        assert session.get(ForecastModelRecord, version) is None


def test_display_training_cleanly_rejects_insufficient_receipt_causal_evidence(
    app,
    client,
    monkeypatch,
):
    del client
    service = app.state.forecast_service
    version = "static-display-insufficient-causal-replay"
    base_ms = 1_700_006_400_000  # UTC midnight.
    step_ms = 5 * 60_000

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add_all(
            [
                GlucoseReadingRecord(
                    reading_id=f"display-causal-{index}",
                    measured_at_ms=base_ms + index * step_ms,
                    glucose_mg_dl=120.0,
                    trend_mg_dl_min=0.0,
                    sensor_id="test",
                    sensor_generation="test",
                    quality=1.0,
                    utc_offset_minutes=0,
                    payload_hash=f"{index:064x}"[-64:],
                    received_at_ms=base_ms + index * step_ms + 1_000,
                )
                for index in range(17 * 24 * 12)
            ]
        )
        session.commit()

        original_display_gates = service.static_display_gates

        def point_gates_pass(*args, **kwargs):
            return {**original_display_gates(*args, **kwargs), "accepted": True}

        monkeypatch.setattr(service, "static_display_gates", point_gates_pass)
        monkeypatch.setattr(
            service,
            "_prospective_causal_windows",
            lambda _readings: (
                [],
                {
                    "causal_history_rejections": 0,
                    "causal_target_rejections": 1,
                    "causal_stale_anchor_rejections": 0,
                },
            ),
        )

        result = service.train_static_model(
            session,
            candidate_version=version,
            stage_pending=False,
            allow_display_activation=True,
        )
        stored = session.get(ForecastModelRecord, version)

        assert result.status == "rejected"
        assert result.metrics["gate_receipt_causal_evidence_sufficient"] == 0
        assert stored is not None and stored.status == "rejected"


def test_non_finite_artifact_fails_closed_without_raising(app, client):
    del client
    service = app.state.forecast_service
    version = "static-non-finite"
    record = _static_record(version)
    parameters = json.loads(record.parameters_json)
    parameters["network"]["intercept"][0] = float("nan")
    # Keep a syntactically plausible digest. Validation must catch the rejected
    # non-standard JSON number instead of propagating json.dumps(ValueError).
    record.parameters_json = json.dumps(parameters, separators=(",", ":"))

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add(record)
        session.add(
            BackendMetadataRecord(
                key=ACTIVE_MODEL_METADATA_KEY, value_text=version
            )
        )
        session.commit()

        assert service._champion(session).version == BASELINE_VERSION


def test_runtime_uses_only_the_valid_artifact_pin_and_fails_closed(app, client):
    del client
    service = app.state.forecast_service
    pinned_version = "static-pinned"
    newer_version = "static-newer-unpinned"
    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add_all(
            [_static_record(pinned_version), _static_record(newer_version)]
        )
        session.commit()
        service.activate_model(session, pinned_version)

        newer = session.get(ForecastModelRecord, newer_version)
        assert newer is not None
        newer.status = "champion"
        newer.promoted_at_ms = TRAINED_AT_MS + 10_000
        session.commit()
        assert service._champion(session).version == pinned_version

        pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
        assert pin is not None
        pin.value_text = "missing-artifact"
        session.commit()
        assert service._champion(session).version == BASELINE_VERSION
        assert pin.value_text == BASELINE_VERSION


def test_active_status_uses_frozen_champion_metadata_not_newer_rejection(app, client):
    del client
    service = app.state.forecast_service
    approved = _static_record("static-active")
    rejected = _static_record("static-later-rejected", accepted=False)
    rejected.status = "rejected"
    rejected.trained_at_ms = TRAINED_AT_MS + 50_000

    with app.state.database.session_factory() as session:
        service._ensure_baseline(session)
        session.add_all([approved, rejected])
        session.commit()
        service.activate_model(session, approved.version)

        status = service.status(session, now_ms=TRAINED_AT_MS + 100_000)

    assert status.training.state == "frozen"
    assert status.training.last_trained_at_ms == TRAINED_AT_MS
    assert status.training.sample_count == SAMPLE_COUNT
