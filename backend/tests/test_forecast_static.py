from __future__ import annotations

import copy
import json

import pytest

from app.forecast import (
    ACTIVE_MODEL_METADATA_KEY,
    BASELINE_VERSION,
    FORECAST_ENGINE_VERSION,
    STATIC_BANDS,
    STATIC_FEATURE_COUNT,
    STATIC_FEATURE_SCHEMA,
    STATIC_HIDDEN_SIZE,
    STATIC_INTERVAL_LEVEL,
    STATIC_NETWORK_KIND,
    STATIC_PERSONAL_ARCHITECTURE,
    STATIC_PROMOTION_GATE_VERSION,
    STATIC_TRAINING_MODE,
    ForecastService,
    _artifact_content_hash,
    _default_parameters,
    _static_artifact_is_valid,
)
from app.models import BackendMetadataRecord, ForecastModelRecord


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
        "mae_30": horizon_mae,
        "mae_60": horizon_mae,
        "mae_120": horizon_mae,
        "coverage_80": 0.80,
        "interval_score_80": interval_score,
        "coverage_band_0": 0.80,
        "coverage_band_1": 0.80,
        "coverage_band_2": 0.80,
        "coverage_band_3": 0.80,
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
        "w1": [[0.0] * STATIC_HIDDEN_SIZE for _ in range(STATIC_FEATURE_COUNT)],
        "b1": [0.0] * STATIC_HIDDEN_SIZE,
        "w2": [[0.0] * 24 for _ in range(STATIC_HIDDEN_SIZE)],
        "b2": [0.0] * 24,
    }
    parameter_count = (
        STATIC_FEATURE_COUNT * STATIC_HIDDEN_SIZE
        + STATIC_HIDDEN_SIZE
        + STATIC_HIDDEN_SIZE * 24
        + 24
    )
    band_weights = [0.25, 0.50, 0.50, 0.25]
    blend: list[float] = []
    band_definitions: list[dict[str, float | int]] = []
    for (start, end), weight in zip(STATIC_BANDS, band_weights):
        blend.extend([weight] * (((end - start) // 5) + 1))
        band_definitions.append(
            {"start_minutes": start, "end_minutes": end, "weight": weight}
        )
    sigma = [20.0] * 24
    evaluation = {
        "accepted": 1 if accepted else 0,
        "candidate_equal_day_mae": 18.0,
        "reference_equal_day_mae": 20.0,
        "pinned_equal_day_mae": 20.0,
        "candidate_anchor_mae": 17.5,
        "reference_anchor_mae": 20.0,
        "candidate_coverage_80": 0.80,
        "candidate_interval_score_80": 35.0,
        "reference_interval_score_80": 40.0,
        "test_days": 4,
        "test_independent_anchors": 32,
    }
    parameters.update(
        {
            "network": network,
            "persistence_blend_weights": blend,
            "residual_sigma": sigma,
            "frozen_calibration": {
                "method": "frozen-day-block-conformal-v1",
                "interval_level": STATIC_INTERVAL_LEVEL,
                "sample_count": 32,
                "bias_mg_dl": [0.0] * 24,
                "sigma_mg_dl": sigma,
            },
            "artifact": {
                "artifact_version": 3,
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
                "reliability": {
                    "overall": 0.35,
                    "by_horizon": [0.35] * 24,
                    "clinical_validation": False,
                },
            },
        }
    )
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
        pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
        assert pin is not None and pin.value_text == approved_version


def test_non_finite_artifact_fails_closed_without_raising(app, client):
    del client
    service = app.state.forecast_service
    version = "static-non-finite"
    record = _static_record(version)
    parameters = json.loads(record.parameters_json)
    parameters["network"]["b2"][0] = float("nan")
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
