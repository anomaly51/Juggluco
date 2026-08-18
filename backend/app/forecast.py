from __future__ import annotations

import bisect
import hashlib
import json
import logging
import math
import threading
import time
from dataclasses import dataclass, replace
from typing import Any, Sequence
from uuid import UUID, uuid4

import numpy as np
from sqlalchemy import Integer, and_, cast, delete, func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .models import (
    AnalysisRecord,
    BackendMetadataRecord,
    ForecastCalibrationRecord,
    ForecastMaintenanceRecord,
    ForecastModelRecord,
    ForecastPointRecord,
    ForecastRunRecord,
    ForecastScoreRecord,
    GlucoseReadingRecord,
    IntakeEventRecord,
    SyncChangeRecord,
)
from .schemas import (
    ForecastAccuracyStatus,
    ForecastActivity,
    ForecastActivityPoint,
    ForecastCapabilityStatus,
    ForecastCurrentResponse,
    ForecastDataStatus,
    ForecastPoint,
    ForecastStatusResponse,
    ForecastTrainingStatus,
    ForecastTrainResponse,
    GlucoseReadingsCreate,
    GlucoseReadingsResponse,
)


STEP_MINUTES = 5
STEP_MS = STEP_MINUTES * 60_000
HORIZON_MINUTES = 120
HORIZON_STEPS = HORIZON_MINUTES // STEP_MINUTES
HISTORY_STEPS = 24
MINIMUM_TRAIN_WINDOWS = 48
VALIDATION_WINDOWS = 16
EMBARGO_WINDOWS = HORIZON_STEPS
MINIMUM_TRAINING_WINDOWS = MINIMUM_TRAIN_WINDOWS + VALIDATION_WINDOWS + EMBARGO_WINDOWS
MINIMUM_CLEAN_EVENT_SAMPLES = 3
MINIMUM_CONTEXTUAL_EVENT_SAMPLES = 8
MINIMUM_CONTEXTUAL_VALIDATION_EVENTS = 5
MINIMUM_CONTEXTUAL_VALIDATION_WINDOWS = 12
FORECAST_RETENTION_DAYS = 35
PRUNE_INTERVAL_MS = 24 * 60 * 60_000
MAX_TRAINING_WINDOWS = 4_000
STALE_AFTER_MS = 15 * 60_000
MATCH_TOLERANCE_MS = 150_000
CONTEXT_HISTORY_MINUTES = 72 * 60
V3_FEATURE_SCHEMA = "context-sequence-v3"
V3_NETWORK_KIND = "contextual_gated_v3"
PERSONAL_ARCHITECTURE = "personalized-contextual-gated-mlp-direct-24-v3"
LEGACY_PERSONAL_ARCHITECTURE = "personalized-hybrid-mlp-direct-24-v2"
STATIC_PERSONAL_ARCHITECTURE = "personalized-static-generic-residual-v1"
FORECAST_ENGINE_VERSION = "forecast-engine-v5-static"
ACTIVE_MODEL_METADATA_KEY = "active_forecast_model"
ACTIVATION_HISTORY_METADATA_KEY = "forecast_model_activation_history"
STATIC_TRAINING_MODE = "manual"
STATIC_INTERVAL_LEVEL = 0.80
STATIC_TRAINING_SEED = 20_260_805
STATIC_FEATURE_SCHEMA = "generic-glucose-context-v1"
STATIC_NETWORK_KIND = "static_generic_tanh_v1"
STATIC_FEATURE_COUNT = 138
STATIC_HIDDEN_SIZE = 12
STATIC_PURGE_MINUTES = HORIZON_MINUTES
STATIC_PURGE_WINDOWS = STATIC_PURGE_MINUTES // STEP_MINUTES
STATIC_PROMOTION_GATE_VERSION = "independent-day-block-v2"
STATIC_MIN_TRAIN_DAYS = 8
STATIC_TUNING_DAYS = 1
STATIC_CALIBRATION_DAYS = 2
STATIC_TEST_DAYS = 4
STATIC_REQUIRED_DAYS = float(
    STATIC_MIN_TRAIN_DAYS
    + STATIC_TUNING_DAYS
    + STATIC_CALIBRATION_DAYS
    + STATIC_TEST_DAYS
)
STATIC_MIN_USABLE_DAY_HOURS = 16
STATIC_MIN_DAY_DENSITY = 0.80
STATIC_BANDS: tuple[tuple[int, int], ...] = (
    (5, 30),
    (35, 60),
    (65, 90),
    (95, 120),
)
# Bumps immutable current-run identity when explanation semantics change without
# pretending that the median predictor itself is a new trained model.
ACTION_PROFILE_CONTRACT_VERSION = 2
BASELINE_VERSION = "event-aware-persistence-v3"
CONDITIONAL_NOTICE = (
    "Experimental estimate only. It assumes no unrecorded food, insulin, exercise, "
    "illness, or sensor error. Never use this forecast to calculate a dose."
)
logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class _Event:
    event_id: str
    occurred_at_ms: int
    kind: str
    label: str
    amount: float
    # `occurred_at_ms` describes physiology; `known_at_ms` describes what a
    # historical forecast was allowed to know.  Live forecasts may intentionally
    # use a backdated event after the user records it, while replay/training must
    # never expose it to an earlier anchor.
    known_at_ms: int | None = None
    carbs_low_g: float | None = None
    carbs_high_g: float | None = None
    absorption_speed: float | None = None
    absorption_peak_minutes: float | None = None
    absorption_duration_minutes: float | None = None
    absorption_confidence: float | None = None
    protein_g: float | None = None
    fat_g: float | None = None
    fiber_g: float | None = None
    # None means a directly entered carbohydrate value, not an AI estimate with
    # missing confidence. This distinction matters when widening the interval.
    ai_confidence: float | None = None


@dataclass(frozen=True, slots=True)
class _EffectiveActionEstimate:
    """Bounded explanation kernel for one immutable intake event.

    This is deliberately smaller than the glucose predictor.  CGM can support a
    context-conditioned *effective* response, but it cannot recover an
    injection's pharmacokinetics, especially when doses overlap.  Keeping the
    low-dimensional timing/amplitude estimate separate prevents a flexible
    residual network from being rendered as a falsely precise PK curve.
    """

    onset_minutes: float
    peak_minutes: float
    duration_minutes: float
    peak_low_minutes: float
    peak_high_minutes: float
    end_low_minutes: float
    end_high_minutes: float
    amplitude_scale: float
    attribution_confidence: float
    identifiability: str
    action_model: str
    overlap_count: int
    contribution_values: np.ndarray | None = None
    activity_values: np.ndarray | None = None


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, float(value)))


def _finite(value: Any, fallback: float = 0.0) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return fallback
    return result if math.isfinite(result) else fallback


def _json_dict(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _validated_vector(value: Any, *, positive: bool = False) -> np.ndarray | None:
    try:
        result = np.asarray(value, dtype=np.float64)
    except (TypeError, ValueError):
        return None
    if (
        result.shape != (HORIZON_STEPS,)
        or not np.isfinite(result).all()
        or (positive and np.any(result <= 0.0))
    ):
        return None
    return result


def _apply_static_predictor(
    prediction: np.ndarray,
    reference_prediction: np.ndarray,
    sigma: np.ndarray,
    parameters: dict[str, Any],
) -> tuple[np.ndarray, np.ndarray]:
    """Apply immutable persistence shrinkage and frozen calibration."""

    blend = _validated_vector(parameters.get("persistence_blend_weights"))
    calibration = parameters.get("frozen_calibration")
    if blend is None or np.any(blend < 0.0) or np.any(blend > 1.0):
        return prediction, sigma
    if not isinstance(calibration, dict):
        return prediction, sigma
    bias = _validated_vector(calibration.get("bias_mg_dl"))
    frozen_sigma = _validated_vector(calibration.get("sigma_mg_dl"), positive=True)
    if bias is None or frozen_sigma is None:
        return prediction, sigma
    reference = np.asarray(reference_prediction, dtype=np.float64)
    if prediction.ndim == 1:
        if reference.shape != (HORIZON_STEPS,):
            return prediction, sigma
        shrunk = reference + blend * (prediction - reference)
        return np.clip(shrunk + bias, 20.0, 600.0), np.maximum(frozen_sigma, 6.0)
    if reference.shape != prediction.shape:
        return prediction, sigma
    shrunk = reference + blend.reshape(1, -1) * (prediction - reference)
    return (
        np.clip(shrunk + bias.reshape(1, -1), 20.0, 600.0),
        np.maximum(frozen_sigma, 6.0),
    )


def _artifact_content_hash(parameters: dict[str, Any]) -> str:
    """Hash the complete immutable parameter/evaluation envelope.

    Static artifacts embed their record identity, split manifest, frozen
    calibration, comparator metrics, and promotion decision inside ``artifact``.
    Removing only the digest itself avoids a circular hash while ensuring that a
    changed weight, split, metric, or approval bit invalidates the artifact.
    """

    payload = dict(parameters)
    artifact = payload.get("artifact")
    if isinstance(artifact, dict):
        artifact_without_hash = dict(artifact)
        artifact_without_hash.pop("content_sha256", None)
        payload["artifact"] = artifact_without_hash
    canonical = json.dumps(
        payload, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _static_artifact_is_valid(parameters: dict[str, Any]) -> bool:
    artifact = parameters.get("artifact")
    network = parameters.get("network")
    calibration = parameters.get("frozen_calibration")
    reliability = artifact.get("reliability") if isinstance(artifact, dict) else None
    evaluation = artifact.get("evaluation") if isinstance(artifact, dict) else None
    split = artifact.get("split") if isinstance(artifact, dict) else None
    if not all(
        isinstance(item, dict)
        for item in (artifact, network, calibration, reliability, evaluation, split)
    ):
        return False
    expected = artifact.get("content_sha256")
    try:
        computed_hash = _artifact_content_hash(parameters)
    except (TypeError, ValueError, OverflowError):
        # ``json.loads`` accepts non-standard NaN/Infinity tokens. A corrupt
        # persisted artifact must fail closed instead of breaking status/current.
        return False
    blend = _validated_vector(parameters.get("persistence_blend_weights"))
    bias = _validated_vector(calibration.get("bias_mg_dl"))
    sigma = _validated_vector(calibration.get("sigma_mg_dl"), positive=True)
    residual_sigma = _validated_vector(parameters.get("residual_sigma"), positive=True)
    if not (
        isinstance(expected, str)
        and len(expected) == 64
        and expected == computed_hash
        and parameters.get("architecture") == STATIC_PERSONAL_ARCHITECTURE
        and parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA
        and parameters.get("kind") == "personalized_static_generic_residual"
        and artifact.get("artifact_version") == 3
        and artifact.get("engine_version") == FORECAST_ENGINE_VERSION
        and artifact.get("architecture") == STATIC_PERSONAL_ARCHITECTURE
        and artifact.get("feature_schema") == STATIC_FEATURE_SCHEMA
        and artifact.get("network_kind") == STATIC_NETWORK_KIND
        and artifact.get("training_mode") == STATIC_TRAINING_MODE
        and artifact.get("promotion_gate_version")
        == STATIC_PROMOTION_GATE_VERSION
        and artifact.get("accepted") is True
        and isinstance(artifact.get("model_version"), str)
        and 0 < len(artifact["model_version"]) <= 96
        and math.isclose(
            _finite(artifact.get("interval_level"), -1.0),
            STATIC_INTERVAL_LEVEL,
            abs_tol=1e-9,
        )
        and isinstance(artifact.get("dataset_sha256"), str)
        and len(artifact["dataset_sha256"]) == 64
        and int(_finite(artifact.get("feature_count"), 0)) == STATIC_FEATURE_COUNT
        and int(_finite(artifact.get("parameter_count"), 0)) > 0
        and int(_finite(artifact.get("parameter_count"), 0)) <= 5_000
        and int(_finite(split.get("train_days"), 0)) >= STATIC_MIN_TRAIN_DAYS
        and int(_finite(split.get("tuning_days"), 0)) >= STATIC_TUNING_DAYS
        and int(_finite(split.get("calibration_days"), 0))
        >= STATIC_CALIBRATION_DAYS
        and int(_finite(split.get("test_days"), 0)) >= STATIC_TEST_DAYS
        and int(_finite(split.get("purge_minutes"), 0)) >= HORIZON_MINUTES
        and int(_finite(split.get("test_independent_anchors"), 0)) >= 32
        and network.get("kind") == STATIC_NETWORK_KIND
        and network.get("feature_schema") == STATIC_FEATURE_SCHEMA
        and blend is not None
        and np.all((blend >= 0.0) & (blend <= 1.0))
        and bias is not None
        and sigma is not None
        and residual_sigma is not None
        and np.allclose(sigma, residual_sigma, rtol=0.0, atol=1e-9)
        and calibration.get("method") == "frozen-day-block-conformal-v1"
        and math.isclose(
            _finite(calibration.get("interval_level"), -1.0),
            STATIC_INTERVAL_LEVEL,
            abs_tol=1e-9,
        )
        and int(_finite(calibration.get("sample_count"), 0)) >= VALIDATION_WINDOWS
        and parameters.get("network_disabled_event_channels")
        == ["meal", "rapid", "long"]
    ):
        return False
    expected_band_values: list[float] = []
    for band_index, (start, end) in enumerate(STATIC_BANDS):
        band_value = float(blend[(start // STEP_MINUTES) - 1])
        expected_band_values.extend(
            [band_value] * (((end - start) // STEP_MINUTES) + 1)
        )
        declared = artifact.get("band_definitions")
        if not isinstance(declared, list) or len(declared) != len(STATIC_BANDS):
            return False
        band = declared[band_index]
        if not isinstance(band, dict) or (
            int(_finite(band.get("start_minutes"), -1)) != start
            or int(_finite(band.get("end_minutes"), -1)) != end
            or not math.isclose(
                _finite(band.get("weight"), -1.0), band_value, abs_tol=1e-9
            )
        ):
            return False
    if not np.allclose(blend, np.asarray(expected_band_values), atol=1e-9, rtol=0.0):
        return False
    try:
        x_mean = np.asarray(network["x_mean"], dtype=np.float64)
        x_scale = np.asarray(network["x_scale"], dtype=np.float64)
        w1 = np.asarray(network["w1"], dtype=np.float64)
        b1 = np.asarray(network["b1"], dtype=np.float64)
        w2 = np.asarray(network["w2"], dtype=np.float64)
        b2 = np.asarray(network["b2"], dtype=np.float64)
    except (KeyError, TypeError, ValueError):
        return False
    hidden = w1.shape[1] if w1.ndim == 2 else 0
    tensors = (x_mean, x_scale, w1, b1, w2, b2)
    parameter_count = int(sum(tensor.size for tensor in (w1, b1, w2, b2)))
    if (
        x_mean.shape != (STATIC_FEATURE_COUNT,)
        or x_scale.shape != (STATIC_FEATURE_COUNT,)
        or np.any(x_scale <= 1e-8)
        or hidden != STATIC_HIDDEN_SIZE
        or w1.shape != (STATIC_FEATURE_COUNT, hidden)
        or b1.shape != (hidden,)
        or w2.shape != (hidden, HORIZON_STEPS)
        or b2.shape != (HORIZON_STEPS,)
        or parameter_count != int(artifact.get("parameter_count"))
        or any(not np.isfinite(tensor).all() for tensor in tensors)
    ):
        return False
    required_evaluation = (
        "accepted",
        "candidate_equal_day_mae",
        "reference_equal_day_mae",
        "pinned_equal_day_mae",
        "candidate_anchor_mae",
        "reference_anchor_mae",
        "candidate_coverage_80",
        "candidate_interval_score_80",
        "reference_interval_score_80",
        "test_days",
        "test_independent_anchors",
    )
    if evaluation.get("accepted") != 1 or any(
        key not in evaluation or not math.isfinite(_finite(evaluation.get(key), math.nan))
        for key in required_evaluation
        if key != "accepted"
    ):
        return False
    overall = _finite(reliability.get("overall"), -1.0)
    by_horizon = reliability.get("by_horizon")
    return bool(
        0.0 <= overall <= 0.35
        and reliability.get("clinical_validation") is False
        and isinstance(by_horizon, list)
        and len(by_horizon) == HORIZON_STEPS
        and all(0.0 <= _finite(item, -1.0) <= 0.35 for item in by_horizon)
    )


def _dataset_fingerprint(
    readings: Sequence[GlucoseReadingRecord], events: Sequence[_Event]
) -> str:
    digest = hashlib.sha256()
    for row in readings:
        digest.update(
            json.dumps(
                (
                    row.reading_id,
                    row.payload_hash,
                    row.quality,
                    row.utc_offset_minutes,
                ),
                separators=(",", ":"),
                allow_nan=False,
            ).encode("utf-8")
        )
        digest.update(b"\n")
    for event in events:
        digest.update(
            json.dumps(
                (
                    event.event_id,
                    event.occurred_at_ms,
                    _event_known_at(event),
                    event.kind,
                    event.amount,
                    event.carbs_low_g,
                    event.carbs_high_g,
                    event.absorption_speed,
                    event.absorption_peak_minutes,
                    event.absorption_duration_minutes,
                    event.absorption_confidence,
                    event.protein_g,
                    event.fat_g,
                    event.fiber_g,
                    event.ai_confidence,
                ),
                separators=(",", ":"),
                allow_nan=False,
            ).encode("utf-8")
        )
        digest.update(b"\n")
    return digest.hexdigest()


def _default_parameters() -> dict[str, Any]:
    # These are deliberately broad warm-start shapes, not patient-specific claims.
    # Personal training replaces the response timing and sensitivities when data supports it.
    return {
        "kind": "hybrid_baseline",
        # New candidates are trained with a broad depot kernel for basal insulin.
        # Persisted personal champions without this marker retain their original
        # feature semantics; the baseline is recognized by ``kind`` below.
        "action_kernel_version": 2,
        "profiles": {
            "rapid_peak_minutes": 75.0,
            "rapid_duration_minutes": 300.0,
            "long_peak_minutes": 720.0,
            "long_duration_minutes": 2_520.0,
            "meal_profile_confidence": 0.18,
            "rapid_profile_confidence": 0.18,
            "long_profile_confidence": 0.12,
        },
        "evidence_counts": {"meal": 0, "rapid": 0, "long": 0},
        "sensitivities": {
            "carb_mg_dl_per_g": 0.85,
            "rapid_mg_dl_per_unit": 7.0,
            "long_mg_dl_per_unit": 2.0,
        },
        "residual_sigma": [8.0 + 0.22 * (index * STEP_MINUTES) for index in range(1, 25)],
    }


def _baseline_parameters() -> dict[str, Any]:
    """Return the code-owned safe baseline, never mutable database parameters."""

    parameters = _default_parameters()
    parameters["kind"] = "event_aware_persistence_baseline"
    parameters["prediction_reference"] = "event_aware_persistence"
    return parameters


def _reading_payload_hash(reading: Any, _utc_offset_minutes: int | None) -> str:
    # Only clinical sample identity is immutable. Sensor labels, quality estimates and
    # UTC offset are transport/context metadata and may legitimately differ between the
    # live path and a later native-history replay of the same sample.
    canonical = json.dumps(
        {
            "reading_id": reading.reading_id,
            "measured_at_ms": reading.measured_at_ms,
            "glucose_mg_dl": reading.glucose_mg_dl,
            "trend_mg_dl_min": reading.trend_mg_dl_min,
        },
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _triangular_cdf(age_minutes: float, peak_minutes: float, duration_minutes: float) -> float:
    """Integral of a peak-normalized triangular activity curve."""

    duration = max(15.0, duration_minutes)
    peak = _clamp(peak_minutes, 1.0, duration - 1.0)
    if age_minutes <= 0:
        return 0.0
    if age_minutes >= duration:
        return 1.0
    if age_minutes <= peak:
        return (age_minutes * age_minutes) / (peak * duration)
    remaining = duration - age_minutes
    return 1.0 - (remaining * remaining) / ((duration - peak) * duration)


def _triangular_activity(
    age_minutes: float, peak_minutes: float, duration_minutes: float
) -> float:
    """Peak-normalized instantaneous activity for the same curve as its CDF."""

    duration = max(15.0, duration_minutes)
    peak = _clamp(peak_minutes, 1.0, duration - 1.0)
    if age_minutes <= 0.0 or age_minutes >= duration:
        return 0.0
    if age_minutes <= peak:
        return _clamp(age_minutes / peak, 0.0, 1.0)
    return _clamp((duration - age_minutes) / (duration - peak), 0.0, 1.0)


def _basal_depot_geometry(duration_minutes: float) -> tuple[float, float]:
    """Return the end of the slow rise and start of the slow fall.

    Tresiba is represented as an overlapping basal depot rather than a sharp
    per-injection triangle.  The wide plateau is intentionally conservative:
    CGM alone cannot identify a unique peak for one dose in a daily stack.
    """

    duration = _clamp(duration_minutes, 360.0, 4_320.0)
    rise = _clamp(duration * 0.12, 180.0, 480.0)
    fall = _clamp(duration * 0.18, 300.0, 720.0)
    if rise + fall > duration * 0.72:
        scale = duration * 0.72 / (rise + fall)
        rise *= scale
        fall *= scale
    return rise, duration - fall


def _basal_depot_activity(age_minutes: float, duration_minutes: float) -> float:
    duration = _clamp(duration_minutes, 360.0, 4_320.0)
    rise_end, fall_start = _basal_depot_geometry(duration)
    if age_minutes <= 0.0 or age_minutes >= duration:
        return 0.0
    if age_minutes < rise_end:
        return _clamp(age_minutes / rise_end, 0.0, 1.0)
    if age_minutes <= fall_start:
        return 1.0
    return _clamp((duration - age_minutes) / (duration - fall_start), 0.0, 1.0)


def _basal_depot_cdf(age_minutes: float, duration_minutes: float) -> float:
    """Area-normalized integral of :func:`_basal_depot_activity`."""

    duration = _clamp(duration_minutes, 360.0, 4_320.0)
    rise_end, fall_start = _basal_depot_geometry(duration)
    fall = duration - fall_start
    area = duration - 0.5 * (rise_end + fall)
    if age_minutes <= 0.0:
        return 0.0
    if age_minutes >= duration:
        return 1.0
    if age_minutes < rise_end:
        integral = age_minutes * age_minutes / (2.0 * rise_end)
    elif age_minutes <= fall_start:
        integral = 0.5 * rise_end + (age_minutes - rise_end)
    else:
        into_fall = age_minutes - fall_start
        integral = (
            0.5 * rise_end
            + (fall_start - rise_end)
            + into_fall
            - into_fall * into_fall / (2.0 * fall)
        )
    return _clamp(integral / area, 0.0, 1.0)


def _uses_basal_depot(parameters: dict[str, Any]) -> bool:
    # The persisted baseline predates the explicit marker but is mathematical,
    # not a learned feature transform, so it can safely adopt the depot prior.
    return bool(
        int(_finite(parameters.get("action_kernel_version"), 0.0)) >= 2
        or parameters.get("kind") == "hybrid_baseline"
    )


def _event_cdf(
    event: _Event,
    age_minutes: float,
    peak_minutes: float,
    duration_minutes: float,
    parameters: dict[str, Any],
) -> float:
    if event.kind == "long" and _uses_basal_depot(parameters):
        return _basal_depot_cdf(age_minutes, duration_minutes)
    return _triangular_cdf(age_minutes, peak_minutes, duration_minutes)


def _event_activity(
    event: _Event,
    age_minutes: float,
    peak_minutes: float,
    duration_minutes: float,
    parameters: dict[str, Any],
) -> float:
    if event.kind == "long" and _uses_basal_depot(parameters):
        return _basal_depot_activity(age_minutes, duration_minutes)
    return _triangular_activity(age_minutes, peak_minutes, duration_minutes)


def _shifted_triangular_cdf(
    age_minutes: float,
    onset_minutes: float,
    peak_minutes: float,
    duration_minutes: float,
) -> float:
    return _triangular_cdf(
        age_minutes - onset_minutes,
        peak_minutes - onset_minutes,
        duration_minutes - onset_minutes,
    )


def _shifted_triangular_activity(
    age_minutes: float,
    onset_minutes: float,
    peak_minutes: float,
    duration_minutes: float,
) -> float:
    return _triangular_activity(
        age_minutes - onset_minutes,
        peak_minutes - onset_minutes,
        duration_minutes - onset_minutes,
    )


def _resolve_meal_profile(event: _Event) -> tuple[float, float, float, float]:
    speed = event.absorption_speed
    if speed is None:
        # Nutrient estimates modulate a neutral prior without pretending to measure GI.
        slowing = 0.0
        slowing += 0.006 * max(0.0, event.fat_g or 0.0)
        slowing += 0.010 * max(0.0, event.fiber_g or 0.0)
        slowing += 0.002 * max(0.0, event.protein_g or 0.0)
        speed = _clamp(0.56 - slowing, 0.08, 0.92)
    else:
        speed = _clamp(speed, 0.0, 1.0)

    peak = event.absorption_peak_minutes
    if peak is None:
        peak = 150.0 - 110.0 * speed
    duration = event.absorption_duration_minutes
    if duration is None:
        duration = 390.0 - 225.0 * speed
    duration = _clamp(duration, 45.0, 720.0)
    peak = _clamp(peak, 10.0, duration - 5.0)
    confidence = event.absorption_confidence
    if confidence is None:
        confidence = (
            0.62
            if event.ai_confidence is None
            else max(0.18, min(0.62, event.ai_confidence * 0.7))
        )
    return speed, peak, duration, _clamp(confidence, 0.0, 1.0)


def _profile_for_event(event: _Event, parameters: dict[str, Any]) -> tuple[float, float, float]:
    profiles = parameters.get("profiles", {})
    if event.kind == "meal":
        _speed, peak, duration, estimate_confidence = _resolve_meal_profile(event)
        learned_confidence = _clamp(
            _finite(profiles.get("meal_profile_confidence"), 0.18), 0.0, 1.0
        )
        return peak, duration, min(estimate_confidence, learned_confidence)
    if event.kind == "rapid":
        duration = _clamp(_finite(profiles.get("rapid_duration_minutes"), 300.0), 60, 720)
        peak = _clamp(_finite(profiles.get("rapid_peak_minutes"), 75.0), 10, duration - 5)
        return peak, duration, _clamp(
            _finite(profiles.get("rapid_profile_confidence"), 0.18), 0.0, 1.0
        )
    duration = _clamp(_finite(profiles.get("long_duration_minutes"), 2_520.0), 360, 4_320)
    peak = _clamp(_finite(profiles.get("long_peak_minutes"), 720.0), 60, duration - 30)
    return peak, duration, _clamp(
        _finite(profiles.get("long_profile_confidence"), 0.12), 0.0, 1.0
    )


def _event_basis(
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    horizons = np.arange(1, HORIZON_STEPS + 1, dtype=np.float64) * STEP_MINUTES
    meal = np.zeros(HORIZON_STEPS, dtype=np.float64)
    rapid = np.zeros(HORIZON_STEPS, dtype=np.float64)
    long = np.zeros(HORIZON_STEPS, dtype=np.float64)
    for event in events:
        age = (anchor_ms - event.occurred_at_ms) / 60_000.0
        peak, duration, _confidence = _profile_for_event(event, parameters)
        before = _event_cdf(event, age, peak, duration, parameters)
        if before >= 1.0:
            continue
        change = np.asarray(
            [
                max(
                    0.0,
                    _event_cdf(event, age + horizon, peak, duration, parameters)
                    - before,
                )
                for horizon in horizons
            ],
            dtype=np.float64,
        )
        if event.kind == "meal":
            meal += event.amount * change
        elif event.kind == "rapid":
            rapid += event.amount * change
        else:
            long += event.amount * change
    return meal, rapid, long


def _event_glucose_increment(
    event: _Event,
    from_ms: int,
    to_ms: int,
    parameters: dict[str, Any],
) -> float:
    """Prior contribution of one known event between two timestamps.

    This is used only to deconfound response-profile evidence. It is deliberately
    conservative: the empirical target event remains measured from the CGM trace,
    while already-known neighbouring events are removed using the broad warm start.
    """

    peak, duration, _confidence = _profile_for_event(event, parameters)
    before_age = (from_ms - event.occurred_at_ms) / 60_000.0
    after_age = (to_ms - event.occurred_at_ms) / 60_000.0
    fraction = _event_cdf(
        event, after_age, peak, duration, parameters
    ) - _event_cdf(
        event, before_age, peak, duration, parameters
    )
    sensitivity = parameters.get("sensitivities", {})
    if event.kind == "meal":
        scale = _finite(sensitivity.get("carb_mg_dl_per_g"), 0.85)
    elif event.kind == "rapid":
        scale = -_finite(sensitivity.get("rapid_mg_dl_per_unit"), 7.0)
    else:
        scale = -_finite(sensitivity.get("long_mg_dl_per_unit"), 2.0)
    return event.amount * fraction * scale


def _meal_event_uncertainty(
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> tuple[np.ndarray, float]:
    """Return an additive forecast sigma and a monotone confidence multiplier.

    LLM carbohydrate ranges are not observations. Their range and both AI/profile
    confidence therefore widen the probabilistic band instead of being rendered as
    the same precision as a directly confirmed gram amount.
    """

    horizons = np.arange(1, HORIZON_STEPS + 1, dtype=np.float64) * STEP_MINUTES
    variance = np.zeros(HORIZON_STEPS, dtype=np.float64)
    confidence_multiplier = 1.0
    carb_scale = _finite(
        parameters.get("sensitivities", {}).get("carb_mg_dl_per_g"), 0.85
    )
    for event in events:
        if event.kind != "meal" or event.amount <= 0:
            continue
        peak, duration, _profile_confidence = _profile_for_event(event, parameters)
        age = (anchor_ms - event.occurred_at_ms) / 60_000.0
        before = _event_cdf(event, age, peak, duration, parameters)
        change = np.asarray(
            [
                max(
                    0.0,
                    _event_cdf(event, age + horizon, peak, duration, parameters)
                    - before,
                )
                for horizon in horizons
            ],
            dtype=np.float64,
        )
        activity_weight = float(np.max(change))
        if activity_weight <= 0:
            continue

        low = event.amount if event.carbs_low_g is None else max(0.0, event.carbs_low_g)
        high = event.amount if event.carbs_high_g is None else max(low, event.carbs_high_g)
        low = min(low, event.amount)
        high = max(high, event.amount)
        # Treat the supplied low/high span as an approximate central 80% interval.
        amount_sigma = max(event.amount - low, high - event.amount) / 1.2816
        ai_uncertainty = (
            0.0
            if event.ai_confidence is None
            else 1.0 - _clamp(event.ai_confidence, 0.0, 1.0)
        )
        absorption_uncertainty = (
            0.0
            if event.absorption_confidence is None
            else 1.0 - _clamp(event.absorption_confidence, 0.0, 1.0)
        )
        # Portion uncertainty changes amplitude; confidence uncertainty also covers
        # plausible timing/ingredient errors without shifting the median arbitrarily.
        amplitude_sigma = amount_sigma * carb_scale * change
        confidence_sigma = (
            event.amount
            * carb_scale
            * change
            * (0.28 * ai_uncertainty + 0.18 * absorption_uncertainty)
        )
        variance += amplitude_sigma * amplitude_sigma + confidence_sigma * confidence_sigma

        relative_range = _clamp(
            (high - low) / max(10.0, 2.0 * event.amount), 0.0, 1.0
        )
        uncertainty_level = max(relative_range, ai_uncertainty, absorption_uncertainty)
        event_multiplier = 1.0 - 0.45 * uncertainty_level * activity_weight
        confidence_multiplier = min(confidence_multiplier, _clamp(event_multiplier, 0.5, 1.0))
    return np.sqrt(variance), confidence_multiplier


def _recent_slope(readings: Sequence[GlucoseReadingRecord]) -> float:
    if len(readings) < 2:
        trend = readings[-1].trend_mg_dl_min if readings else None
        return _clamp(_finite(trend), -3.0, 3.0)
    newest_ms = readings[-1].measured_at_ms
    recent = [
        item for item in readings if item.measured_at_ms >= newest_ms - 55 * 60_000
    ][-120:]
    x = np.asarray([(item.measured_at_ms - newest_ms) / 60_000.0 for item in recent])
    y = np.asarray([item.glucose_mg_dl for item in recent], dtype=np.float64)
    if float(np.ptp(x)) < 1.0:
        fitted = 0.0
    else:
        fitted = float(np.polyfit(x, y, 1)[0])
    supplied = recent[-1].trend_mg_dl_min
    if supplied is not None:
        fitted = fitted * 0.7 + _finite(supplied) * 0.3
    return _clamp(fitted, -3.0, 3.0)


def _baseline_prediction(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> np.ndarray:
    current = float(readings[-1].glucose_mg_dl)
    horizons = np.arange(1, HORIZON_STEPS + 1, dtype=np.float64) * STEP_MINUTES
    slope = _recent_slope(readings)
    # A decaying trend avoids indefinitely extending a transient CGM slope.
    trend_delta = slope * 42.0 * (1.0 - np.exp(-horizons / 42.0))
    meal, rapid, long = _event_basis(events, anchor_ms, parameters)
    sensitivity = parameters.get("sensitivities", {})
    event_delta = (
        meal * _finite(sensitivity.get("carb_mg_dl_per_g"), 0.85)
        - rapid * _finite(sensitivity.get("rapid_mg_dl_per_unit"), 7.0)
        - long * _finite(sensitivity.get("long_mg_dl_per_unit"), 2.0)
    )
    return np.clip(current + trend_delta + event_delta, 20.0, 600.0)


def _event_reference_prediction(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> np.ndarray:
    """Persistence reference that preserves bounded known-event priors."""

    current = float(readings[-1].glucose_mg_dl)
    meal, rapid, long = _event_basis(events, anchor_ms, parameters)
    sensitivity = parameters.get("sensitivities", {})
    event_delta = (
        meal * _finite(sensitivity.get("carb_mg_dl_per_g"), 0.85)
        - rapid * _finite(sensitivity.get("rapid_mg_dl_per_unit"), 7.0)
        - long * _finite(sensitivity.get("long_mg_dl_per_unit"), 2.0)
    )
    return np.clip(current + event_delta, 20.0, 600.0)


def _nearest_value(
    readings: Sequence[GlucoseReadingRecord],
    target_ms: int,
    tolerance_ms: int,
    times: Sequence[int] | None = None,
) -> GlucoseReadingRecord | None:
    if not readings:
        return None
    times = times if times is not None else [item.measured_at_ms for item in readings]
    position = bisect.bisect_left(times, target_ms)
    candidates = []
    if position < len(readings):
        candidates.append(readings[position])
    if position:
        candidates.append(readings[position - 1])
    if not candidates:
        return None
    result = min(candidates, key=lambda item: abs(item.measured_at_ms - target_ms))
    return result if abs(result.measured_at_ms - target_ms) <= tolerance_ms else None


def _history_features_v2(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> np.ndarray:
    current = float(readings[-1].glucose_mg_dl)
    glucose_features: list[float] = []
    masks: list[float] = []
    last_value = current
    used_reading_ids: set[str] = set()
    for offset in range(HISTORY_STEPS - 1, -1, -1):
        target = anchor_ms - offset * STEP_MS
        matched = _nearest_value(readings, target, MATCH_TOLERANCE_MS)
        if matched is None or matched.reading_id in used_reading_ids:
            glucose_features.append((last_value - current) / 50.0)
            masks.append(0.0)
        else:
            used_reading_ids.add(matched.reading_id)
            last_value = float(matched.glucose_mg_dl)
            glucose_features.append((last_value - current) / 50.0)
            masks.append(_clamp(matched.quality if matched.quality is not None else 1.0, 0, 1))
    latest = readings[-1]
    offset_minutes = latest.utc_offset_minutes or 0
    local_minutes = ((anchor_ms // 60_000) + offset_minutes) % (24 * 60)
    angle = 2.0 * math.pi * local_minutes / (24 * 60)
    meal, rapid, long = _event_basis(events, anchor_ms, parameters)
    scalars = [
        (current - 120.0) / 100.0,
        _recent_slope(readings) / 3.0,
        math.sin(angle),
        math.cos(angle),
        float(np.mean(masks)),
    ]
    return np.asarray(
        glucose_features
        + masks
        + scalars
        + list(meal / 50.0)
        + list(rapid / 5.0)
        + list(long / 20.0),
        dtype=np.float64,
    )


def _event_known_at(event: _Event) -> int:
    return event.occurred_at_ms if event.known_at_ms is None else event.known_at_ms


def _multiscale_history_features(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> np.ndarray:
    """Causal, bounded context representation for the v3 personal model.

    The detailed two-hour trace remains intact.  Longer history is sampled more
    coarsely and summarized, giving the small NumPy network information about
    latent day-scale state without pretending that an unobserved cause is known.
    """

    if not readings:
        raise ValueError("context features require at least one glucose reading")
    current = float(readings[-1].glucose_mg_dl)
    reading_times = [row.measured_at_ms for row in readings]

    fine_values: list[float] = []
    fine_masks: list[float] = []
    last_value = current
    used_ids: set[str] = set()
    for offset in range(HISTORY_STEPS - 1, -1, -1):
        target = anchor_ms - offset * STEP_MS
        matched = _nearest_value(
            readings, target, MATCH_TOLERANCE_MS, reading_times
        )
        if matched is None or matched.reading_id in used_ids:
            fine_values.append((last_value - current) / 50.0)
            fine_masks.append(0.0)
        else:
            used_ids.add(matched.reading_id)
            last_value = float(matched.glucose_mg_dl)
            fine_values.append((last_value - current) / 50.0)
            fine_masks.append(
                _clamp(matched.quality if matched.quality is not None else 0.75, 0, 1)
            )

    # 30-minute samples cover six hours, three-hour samples cover one day, and
    # nine-hour samples reach 72 hours.  Values and masks have a stable dimension.
    coarse_values: list[float] = []
    coarse_masks: list[float] = []
    for step_minutes, count in ((30, 12), (180, 8), (540, 8)):
        for multiplier in range(count, 0, -1):
            target = anchor_ms - multiplier * step_minutes * 60_000
            matched = _nearest_value(
                readings, target, MATCH_TOLERANCE_MS, reading_times
            )
            if matched is None:
                coarse_values.append(0.0)
                coarse_masks.append(0.0)
            else:
                coarse_values.append((float(matched.glucose_mg_dl) - current) / 50.0)
                coarse_masks.append(
                    _clamp(
                        matched.quality if matched.quality is not None else 0.75,
                        0,
                        1,
                    )
                )

    dynamics: list[float] = []
    for window_minutes in (30, 120, 360, 1_440, 4_320):
        window_start = anchor_ms - window_minutes * 60_000
        start_index = bisect.bisect_left(reading_times, window_start)
        window = [row for row in readings[start_index:] if row.measured_at_ms <= anchor_ms]
        if not window:
            dynamics.extend((0.0, 0.0, 0.0, 0.0, 0.0))
            continue
        values = np.asarray([row.glucose_mg_dl for row in window], dtype=np.float64)
        elapsed = max(
            5.0,
            (window[-1].measured_at_ms - window[0].measured_at_ms) / 60_000.0,
        )
        slope = (float(values[-1]) - float(values[0])) / elapsed
        expected = max(1.0, window_minutes / STEP_MINUTES)
        dynamics.extend(
            (
                (float(np.mean(values)) - current) / 50.0,
                float(np.std(values)) / 50.0,
                float(np.ptp(values)) / 100.0,
                _clamp(slope, -3.0, 3.0) / 3.0,
                _clamp(len(window) / expected, 0.0, 1.0),
            )
        )

    latest = readings[-1]
    offset_minutes = latest.utc_offset_minutes or 0
    local_total_minutes = (anchor_ms // 60_000) + offset_minutes
    local_minutes = local_total_minutes % (24 * 60)
    day_index = (local_total_minutes // (24 * 60)) % 7
    daily_angle = 2.0 * math.pi * local_minutes / (24 * 60)
    weekday_angle = 2.0 * math.pi * day_index / 7.0
    clock_and_state = [
        (current - 120.0) / 100.0,
        _recent_slope(readings) / 3.0,
        math.sin(daily_angle),
        math.cos(daily_angle),
        math.sin(2.0 * daily_angle),
        math.cos(2.0 * daily_angle),
        math.sin(weekday_angle),
        math.cos(weekday_angle),
        float(np.mean(fine_masks)),
    ]

    meal, rapid, long = _event_basis(events, anchor_ms, parameters)
    disabled_event_channels = {
        str(item)
        for item in parameters.get("network_disabled_event_channels", [])
        if str(item) in {"meal", "rapid", "long"}
    }
    meal_n = np.zeros_like(meal) if "meal" in disabled_event_channels else meal / 50.0
    rapid_n = (
        np.zeros_like(rapid) if "rapid" in disabled_event_channels else rapid / 5.0
    )
    long_n = np.zeros_like(long) if "long" in disabled_event_channels else long / 20.0
    event_curves = list(meal_n) + list(rapid_n) + list(long_n)
    event_interactions = (
        list(meal_n * rapid_n)
        + list(meal_n * long_n)
        + list(rapid_n * long_n)
    )

    event_summaries: list[float] = []
    for kind, scale in (("meal", 50.0), ("rapid", 5.0), ("long", 20.0)):
        matching = (
            []
            if kind in disabled_event_channels
            else [event for event in events if event.kind == kind]
        )
        active = 0.0
        for event in matching:
            peak, duration, _confidence = _profile_for_event(event, parameters)
            age = (anchor_ms - event.occurred_at_ms) / 60_000.0
            active += event.amount * _event_activity(
                event, age, peak, duration, parameters
            )
        event_summaries.append(active / scale)
        for minutes in (360, 1_440, 4_320):
            recent_amount = sum(
                event.amount
                for event in matching
                if anchor_ms - minutes * 60_000
                <= event.occurred_at_ms
                <= anchor_ms
            )
            event_summaries.append(recent_amount / scale)

    return np.asarray(
        fine_values
        + fine_masks
        + coarse_values
        + coarse_masks
        + dynamics
        + clock_and_state
        + event_curves
        + event_interactions
        + event_summaries,
        dtype=np.float64,
    )


def _history_features(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> np.ndarray:
    if parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA:
        # The first 138 v3 features are glucose history, masks, dynamics, and
        # clock/state only. Event curves start at index 138 and are deliberately
        # excluded because this snapshot cannot identify separate personal meal,
        # rapid-, and long-insulin effects.
        generic = _multiscale_history_features(
            readings, (), anchor_ms, parameters
        )[:STATIC_FEATURE_COUNT]
        if generic.shape != (STATIC_FEATURE_COUNT,):
            raise ValueError("invalid static generic feature vector")
        return generic
    if parameters.get("feature_schema") == V3_FEATURE_SCHEMA:
        return _multiscale_history_features(readings, events, anchor_ms, parameters)
    return _history_features_v2(readings, events, anchor_ms, parameters)


def _network_predict(features: np.ndarray, parameters: dict[str, Any]) -> np.ndarray:
    return _network_predict_batch(features.reshape(1, -1), parameters)[0]


def _forecast_arrays(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> tuple[np.ndarray, np.ndarray]:
    def raw_prediction(causal_events: Sequence[_Event]) -> np.ndarray:
        if (
            parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA
            or parameters.get("prediction_reference")
            == "event_aware_persistence"
        ):
            baseline = _event_reference_prediction(
                readings, causal_events, anchor_ms, parameters
            )
        else:
            baseline = _baseline_prediction(
                readings, causal_events, anchor_ms, parameters
            )
        features = _history_features(readings, causal_events, anchor_ms, parameters)
        prediction = baseline + _network_predict(features, parameters)
        if prediction.shape != (HORIZON_STEPS,) or not np.isfinite(prediction).all():
            prediction = np.nan_to_num(
                baseline, nan=float(readings[-1].glucose_mg_dl), posinf=600.0, neginf=20.0
            )
        return np.clip(prediction, 20.0, 600.0)

    median = raw_prediction(events)
    # A currently known event may be backdated to a time after the last CGM
    # anchor.  Direct multi-horizon heads can otherwise let its later feature
    # channels affect earlier output steps.  Re-evaluate only the pre-event
    # steps with the event set actually causal at that point.
    if any(event.occurred_at_ms > anchor_ms for event in events):
        cached: dict[tuple[str, ...], np.ndarray] = {}
        for index, minute in enumerate(
            range(STEP_MINUTES, HORIZON_MINUTES + 1, STEP_MINUTES)
        ):
            point_ms = anchor_ms + minute * 60_000
            eligible = [event for event in events if event.occurred_at_ms < point_ms]
            if len(eligible) == len(events):
                continue
            key = tuple(event.event_id for event in eligible)
            causal_prediction = cached.get(key)
            if causal_prediction is None:
                causal_prediction = raw_prediction(eligible)
                cached[key] = causal_prediction
            median[index] = causal_prediction[index]
    try:
        sigma = np.asarray(
            parameters.get("residual_sigma", _default_parameters()["residual_sigma"]),
            dtype=np.float64,
        )
    except (TypeError, ValueError):
        sigma = np.asarray(_default_parameters()["residual_sigma"], dtype=np.float64)
    if (
        sigma.shape != (HORIZON_STEPS,)
        or not np.isfinite(sigma).all()
        or np.any(sigma <= 0.0)
    ):
        sigma = np.asarray(_default_parameters()["residual_sigma"], dtype=np.float64)
    return median, np.maximum(sigma, 6.0)


def _has_contextual_personal_model(parameters: dict[str, Any]) -> bool:
    network = parameters.get("network")
    required = {
        "x_mean",
        "x_scale",
        "w1",
        "b1",
        "base_w",
        "base_b",
        "event_w1",
        "event_b1",
        "event_w2",
        "event_b2",
        "gate_w",
        "gate_b",
    }
    return bool(
        parameters.get("feature_schema") == V3_FEATURE_SCHEMA
        and isinstance(network, dict)
        and network.get("kind") == V3_NETWORK_KIND
        and network.get("feature_schema") == V3_FEATURE_SCHEMA
        and required.issubset(network)
    )


def _has_contextual_event_evidence(
    parameters: dict[str, Any], event: _Event
) -> bool:
    response_count = int(
        parameters.get("evidence_counts", {}).get(event.kind, 0) or 0
    )
    validation = parameters.get("event_channel_validation", {})
    kind_validation = validation.get(event.kind, {}) if isinstance(validation, dict) else {}
    return bool(
        response_count >= MINIMUM_CONTEXTUAL_EVENT_SAMPLES
        and isinstance(kind_validation, dict)
        and kind_validation.get("validated") is True
        and int(kind_validation.get("validation_events", 0) or 0)
        >= MINIMUM_CONTEXTUAL_VALIDATION_EVENTS
        and int(kind_validation.get("validation_windows", 0) or 0)
        >= MINIMUM_CONTEXTUAL_VALIDATION_WINDOWS
    )


def _contextual_event_contribution(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    event: _Event,
    anchor_ms: int,
    parameters: dict[str, Any],
) -> np.ndarray:
    """Return the guarded marginal model estimate for one known event.

    This is a counterfactual visualization (`all events` minus `this event`),
    not a causal or pharmacodynamic measurement.  Sign and magnitude guards keep
    a noisy residual model from presenting an unsafe explanatory curve.
    """

    if (
        not readings
        or not _has_contextual_personal_model(parameters)
        or not _has_contextual_event_evidence(parameters, event)
    ):
        return np.asarray(
            [
                _event_glucose_increment(
                    event, anchor_ms, anchor_ms + step * 60_000, parameters
                )
                for step in range(STEP_MINUTES, HORIZON_MINUTES + 1, STEP_MINUTES)
            ],
            dtype=np.float64,
        )
    full_events = list(events)
    if not any(item.event_id == event.event_id for item in full_events):
        full_events.append(event)
    without_event = [item for item in full_events if item.event_id != event.event_id]
    full, _sigma = _forecast_arrays(readings, full_events, anchor_ms, parameters)
    without, _without_sigma = _forecast_arrays(
        readings, without_event, anchor_ms, parameters
    )
    marginal = np.nan_to_num(full - without, nan=0.0, posinf=0.0, neginf=0.0)
    if event.kind == "meal":
        bound = min(600.0, event.amount * 4.0)
        marginal = np.clip(marginal, 0.0, bound)
    elif event.kind == "rapid":
        bound = min(600.0, event.amount * 30.0)
        marginal = np.clip(marginal, -bound, 0.0)
    else:
        bound = min(600.0, event.amount * 12.0)
        marginal = np.clip(marginal, -bound, 0.0)
    for index, minute in enumerate(
        range(STEP_MINUTES, HORIZON_MINUTES + 1, STEP_MINUTES)
    ):
        if anchor_ms + minute * 60_000 <= event.occurred_at_ms:
            marginal[index] = 0.0
    return marginal


def _overlap_count(
    event: _Event,
    events: Sequence[_Event],
    parameters: dict[str, Any],
) -> int:
    """Count every other physiological kernel intersecting this event.

    Timestamp is not identity.  This intentionally never merges doses: it only
    reports the attribution difficulty while the counterfactual below continues
    to remove one immutable ``event_id`` at a time.  Cross-kind overlap matters:
    a meal, NovoRapid and an active Tresiba depot can all confound the same CGM
    response even though they retain separate records and signs.
    """

    _peak, duration, _confidence = _profile_for_event(event, parameters)
    start = event.occurred_at_ms
    end = start + round(duration * 60_000)
    overlaps = 0
    for other in events:
        if other.event_id == event.event_id:
            continue
        _other_peak, other_duration, _other_confidence = _profile_for_event(
            other, parameters
        )
        other_start = other.occurred_at_ms
        other_end = other_start + round(other_duration * 60_000)
        if max(start, other_start) < min(end, other_end):
            overlaps += 1
    return overlaps


def _prior_action_estimate(
    event: _Event,
    events: Sequence[_Event],
    parameters: dict[str, Any],
) -> _EffectiveActionEstimate:
    peak, duration, confidence = _profile_for_event(event, parameters)
    overlap_count = _overlap_count(event, events, parameters)
    evidence = int(parameters.get("evidence_counts", {}).get(event.kind, 0) or 0)
    personalized = evidence >= MINIMUM_CLEAN_EVENT_SAMPLES

    if event.kind == "long" and _uses_basal_depot(parameters):
        rise_end, fall_start = _basal_depot_geometry(duration)
        representative_peak = _clamp(peak, rise_end, fall_start)
        attribution = _clamp(
            min(confidence, 0.42) / math.sqrt(1.0 + overlap_count), 0.02, 0.42
        )
        return _EffectiveActionEstimate(
            onset_minutes=0.0,
            peak_minutes=representative_peak,
            duration_minutes=duration,
            peak_low_minutes=rise_end,
            peak_high_minutes=fall_start,
            end_low_minutes=max(fall_start + 30.0, duration * 0.84),
            end_high_minutes=min(4_320.0, duration * 1.18),
            amplitude_scale=1.0,
            attribution_confidence=attribution,
            identifiability=(
                "low" if personalized and overlap_count == 0 else "not_identifiable"
            ),
            action_model="basal_depot",
            overlap_count=overlap_count,
        )

    peak_radius = _clamp(
        12.0 + (1.0 - confidence) * (50.0 if event.kind == "rapid" else 70.0)
        + overlap_count * 8.0,
        10.0,
        120.0,
    )
    end_radius = _clamp(
        30.0 + (1.0 - confidence) * 120.0 + overlap_count * 15.0,
        30.0,
        210.0,
    )
    attribution = _clamp(
        confidence / math.sqrt(1.0 + overlap_count), 0.02, 0.88
    )
    return _EffectiveActionEstimate(
        onset_minutes=0.0,
        peak_minutes=peak,
        duration_minutes=duration,
        peak_low_minutes=max(5.0, peak - peak_radius),
        peak_high_minutes=min(duration - 5.0, peak + peak_radius),
        end_low_minutes=max(peak + 5.0, duration - end_radius),
        end_high_minutes=min(1_440.0, duration + end_radius),
        amplitude_scale=1.0,
        attribution_confidence=attribution,
        identifiability=(
            "medium"
            if personalized and overlap_count == 0
            else ("low" if personalized else "not_identifiable")
        ),
        action_model="personalized_kernel" if personalized else "population_prior",
        overlap_count=overlap_count,
    )


def _rapid_contextual_action_estimate(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    event: _Event,
    anchor_ms: int,
    parameters: dict[str, Any],
) -> _EffectiveActionEstimate:
    """Project a validated neural marginal onto a constrained rapid kernel.

    The direct network remains responsible for the glucose forecast.  For the
    explanatory action curve we fit only onset, peak, width and amplitude inside
    conservative bounds, then shrink them toward the learned/population prior.
    This gives context dependence without presenting arbitrary neural wiggles as
    pharmacokinetics.
    """

    prior = _prior_action_estimate(event, events, parameters)
    if (
        event.kind != "rapid"
        or not _has_contextual_personal_model(parameters)
        or not _has_contextual_event_evidence(parameters, event)
    ):
        return prior

    raw = _contextual_event_contribution(
        readings, events, event, anchor_ms, parameters
    )
    target = np.maximum(-np.asarray(raw, dtype=np.float64), 0.0)
    if target.shape != (HORIZON_STEPS,) or not np.isfinite(target).all():
        return prior
    signal = float(np.max(target))
    if signal < 0.5:
        return prior

    age_at_anchor = (anchor_ms - event.occurred_at_ms) / 60_000.0
    horizons = np.arange(1, HORIZON_STEPS + 1, dtype=np.float64) * STEP_MINUTES
    sensitivity = _clamp(
        _finite(
            parameters.get("sensitivities", {}).get("rapid_mg_dl_per_unit"),
            7.0,
        ),
        1.0,
        30.0,
    )
    total_scale = max(0.1, event.amount * sensitivity)
    prior_peak = prior.peak_minutes
    prior_duration = prior.duration_minutes
    candidates: list[tuple[float, float, float, float, float]] = []
    for onset in (0.0, 5.0, 10.0, 15.0, 20.0, 30.0):
        for peak_factor in (0.72, 0.86, 1.0, 1.14, 1.28):
            peak = _clamp(
                prior_peak * peak_factor,
                onset + 10.0,
                min(300.0, prior_duration - 35.0),
            )
            for width_factor in (0.78, 0.90, 1.0, 1.12, 1.25):
                duration = _clamp(
                    prior_duration * width_factor,
                    peak + 30.0,
                    720.0,
                )
                before = _shifted_triangular_cdf(
                    age_at_anchor, onset, peak, duration
                )
                basis = total_scale * np.asarray(
                    [
                        max(
                            0.0,
                            _shifted_triangular_cdf(
                                age_at_anchor + horizon, onset, peak, duration
                            )
                            - before,
                        )
                        for horizon in horizons
                    ],
                    dtype=np.float64,
                )
                denominator = float(np.dot(basis, basis))
                if denominator <= 1e-8:
                    continue
                amplitude = _clamp(
                    float(np.dot(target, basis)) / denominator, 0.45, 1.75
                )
                residual = target - amplitude * basis
                normalized_loss = float(np.mean(residual * residual)) / max(
                    4.0, float(np.mean(target * target))
                )
                # The penalty is intentionally material: a small apparent gain
                # cannot move timing far from the validated prior.
                regularization = (
                    0.030 * (onset / 30.0) ** 2
                    + 0.045 * ((peak - prior_peak) / max(20.0, prior_peak)) ** 2
                    + 0.055
                    * ((duration - prior_duration) / max(60.0, prior_duration)) ** 2
                    + 0.035 * (amplitude - 1.0) ** 2
                )
                candidates.append(
                    (
                        normalized_loss + regularization,
                        onset,
                        peak,
                        duration,
                        amplitude,
                    )
                )
    if not candidates:
        return prior
    _score, fitted_onset, fitted_peak, fitted_duration, fitted_amplitude = min(
        candidates, key=lambda item: item[0]
    )

    # A peak wholly outside the observed 120-minute counterfactual is only weakly
    # identified.  Overlaps remain separate by event ID but explicitly reduce
    # attribution confidence.
    observed_peak = anchor_ms <= (
        event.occurred_at_ms + fitted_peak * 60_000
    ) <= anchor_ms + HORIZON_MINUTES * 60_000
    overlap_penalty = 1.0 / math.sqrt(1.0 + prior.overlap_count)
    fit_quality = math.exp(-math.sqrt(max(0.0, _score)))
    evidence_count = int(
        parameters.get("evidence_counts", {}).get("rapid", 0) or 0
    )
    evidence_maturity = _clamp(evidence_count / 20.0, 0.4, 1.0)
    attribution = _clamp(
        prior.attribution_confidence
        * (0.45 + 0.55 * fit_quality)
        * evidence_maturity
        * (1.0 if observed_peak else 0.72)
        * overlap_penalty,
        0.05,
        0.92,
    )
    adaptation = _clamp(0.20 + 0.70 * attribution, 0.20, 0.82)
    onset = adaptation * fitted_onset
    peak = prior_peak + adaptation * (fitted_peak - prior_peak)
    duration = prior_duration + adaptation * (fitted_duration - prior_duration)
    amplitude = 1.0 + adaptation * (fitted_amplitude - 1.0)
    peak = _clamp(peak, onset + 10.0, duration - 30.0)
    duration = _clamp(duration, peak + 30.0, 720.0)

    # Preserve the exact validated counterfactual as the signed contribution
    # contract.  The constrained kernel supplies timing/strength metadata and the
    # normalized action band; it must not rewrite what the predictor attributed.
    contribution = np.concatenate(
        (np.zeros(1, dtype=np.float64), np.asarray(raw, dtype=np.float64))
    )
    activity = np.asarray(
        [
            _shifted_triangular_activity(
                age_at_anchor + minute, onset, peak, duration
            )
            for minute in range(0, HORIZON_MINUTES + STEP_MINUTES, STEP_MINUTES)
        ],
        dtype=np.float64,
    )
    peak_radius = _clamp(
        10.0 + (1.0 - attribution) * 55.0 + prior.overlap_count * 10.0,
        10.0,
        95.0,
    )
    end_radius = _clamp(
        25.0 + (1.0 - attribution) * 130.0 + prior.overlap_count * 18.0,
        25.0,
        220.0,
    )
    if attribution >= 0.72 and prior.overlap_count == 0 and observed_peak:
        identifiability = "high"
    elif attribution >= 0.42 and prior.overlap_count <= 1:
        identifiability = "medium"
    else:
        identifiability = "low"
    return _EffectiveActionEstimate(
        onset_minutes=onset,
        peak_minutes=peak,
        duration_minutes=duration,
        peak_low_minutes=max(onset + 5.0, peak - peak_radius),
        peak_high_minutes=min(duration - 10.0, peak + peak_radius),
        end_low_minutes=max(peak + 20.0, duration - end_radius),
        end_high_minutes=min(720.0, duration + end_radius),
        amplitude_scale=amplitude,
        attribution_confidence=attribution,
        identifiability=identifiability,
        action_model="contextual_counterfactual",
        overlap_count=prior.overlap_count,
        contribution_values=contribution,
        activity_values=activity,
    )


def _basal_context_amplitude(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> tuple[float, float]:
    """Fit one slowly varying amplitude for the overlapping basal depot.

    All active long-insulin events share this scale.  We intentionally do not
    infer separate peaks or widths from their highly collinear CGM response.
    """

    long_events = [event for event in events if event.kind == "long"]
    if (
        not long_events
        or not readings
        or not _has_contextual_personal_model(parameters)
        or not all(
            _has_contextual_event_evidence(parameters, event)
            for event in long_events
        )
    ):
        return 1.0, 0.0
    without_long = [event for event in events if event.kind != "long"]
    full, _sigma = _forecast_arrays(readings, events, anchor_ms, parameters)
    without, _without_sigma = _forecast_arrays(
        readings, without_long, anchor_ms, parameters
    )
    target = np.maximum(-(full - without), 0.0)
    basis = np.zeros(HORIZON_STEPS, dtype=np.float64)
    for event in long_events:
        basis += np.asarray(
            [
                max(
                    0.0,
                    -_event_glucose_increment(
                        event,
                        anchor_ms,
                        anchor_ms + step * 60_000,
                        parameters,
                    ),
                )
                for step in range(STEP_MINUTES, HORIZON_MINUTES + 1, STEP_MINUTES)
            ],
            dtype=np.float64,
        )
    denominator = float(np.dot(basis, basis))
    if denominator <= 1e-8 or float(np.max(target)) < 0.25:
        return 1.0, 0.0
    raw_scale = _clamp(float(np.dot(target, basis)) / denominator, 0.75, 1.25)
    fitted = raw_scale * basis
    relative_rmse = math.sqrt(float(np.mean((target - fitted) ** 2))) / max(
        1.0, float(np.mean(target))
    )
    confidence = _clamp(math.exp(-relative_rmse) * 0.45, 0.05, 0.45)
    # Slow basal adaptation is strongly shrunk and shared across the depot.
    scale = 1.0 + confidence * 0.55 * (raw_scale - 1.0)
    return _clamp(scale, 0.88, 1.12), confidence


def _fit_network(x_train: np.ndarray, residual_train: np.ndarray) -> dict[str, Any]:
    """Fit the deterministic, deliberately small static residual head."""

    x_mean = x_train.mean(axis=0)
    x_scale = x_train.std(axis=0)
    x_scale[x_scale < 0.05] = 1.0
    x = np.clip((x_train - x_mean) / x_scale, -8.0, 8.0)
    y = np.clip(residual_train, -180.0, 180.0)
    rng = np.random.default_rng(STATIC_TRAINING_SEED)
    hidden_size = STATIC_HIDDEN_SIZE
    w1 = rng.normal(0.0, 0.08, size=(x.shape[1], hidden_size))
    b1 = np.zeros(hidden_size)
    w2 = rng.normal(0.0, 0.04, size=(hidden_size, HORIZON_STEPS))
    b2 = np.zeros(HORIZON_STEPS)
    moment1 = [np.zeros_like(value) for value in (w1, b1, w2, b2)]
    moment2 = [np.zeros_like(value) for value in (w1, b1, w2, b2)]
    learning_rate = 0.006
    regularization = 0.0008
    smoothness = 0.035
    for iteration in range(1, 321):
        hidden = np.tanh(x @ w1 + b1)
        prediction = hidden @ w2 + b2
        error = prediction - y
        # Huber loss limits the influence of sensor errors and unrecorded meals.
        gradient_output = np.where(np.abs(error) <= 30.0, error, 30.0 * np.sign(error))
        gradient_output /= max(1, x.shape[0] * HORIZON_STEPS)
        second_difference = prediction[:, 2:] - 2.0 * prediction[:, 1:-1] + prediction[:, :-2]
        smooth_gradient = np.zeros_like(prediction)
        smooth_scale = 2.0 * smoothness / max(1, x.shape[0] * (HORIZON_STEPS - 2))
        smooth_gradient[:, :-2] += smooth_scale * second_difference
        smooth_gradient[:, 1:-1] -= 2.0 * smooth_scale * second_difference
        smooth_gradient[:, 2:] += smooth_scale * second_difference
        gradient_output += smooth_gradient
        gradients_w2 = hidden.T @ gradient_output + regularization * w2
        gradients_b2 = gradient_output.sum(axis=0)
        gradient_hidden = (gradient_output @ w2.T) * (1.0 - hidden * hidden)
        gradients_w1 = x.T @ gradient_hidden + regularization * w1
        gradients_b1 = gradient_hidden.sum(axis=0)
        gradients = [gradients_w1, gradients_b1, gradients_w2, gradients_b2]
        values = [w1, b1, w2, b2]
        for index, (value, gradient) in enumerate(zip(values, gradients)):
            np.clip(gradient, -5.0, 5.0, out=gradient)
            moment1[index] = 0.9 * moment1[index] + 0.1 * gradient
            moment2[index] = 0.999 * moment2[index] + 0.001 * gradient * gradient
            corrected1 = moment1[index] / (1.0 - 0.9**iteration)
            corrected2 = moment2[index] / (1.0 - 0.999**iteration)
            value -= learning_rate * corrected1 / (np.sqrt(corrected2) + 1e-8)
    return {
        "kind": STATIC_NETWORK_KIND,
        "feature_schema": STATIC_FEATURE_SCHEMA,
        "x_mean": x_mean.tolist(),
        "x_scale": x_scale.tolist(),
        "w1": w1.tolist(),
        "b1": b1.tolist(),
        "w2": w2.tolist(),
        "b2": b2.tolist(),
    }


def _fit_contextual_network(
    x_train: np.ndarray, residual_train: np.ndarray
) -> dict[str, Any]:
    """Fit a compact shared encoder with a gated second-stage residual head.

    This remains deliberately small enough for the localhost NumPy backend.  The
    first head learns general glucose dynamics; the gated head can represent
    nonlinear interactions between latent multi-day context and event channels.
    """

    x_mean = x_train.mean(axis=0)
    x_scale = x_train.std(axis=0)
    x_scale[x_scale < 0.05] = 1.0
    x = np.clip((x_train - x_mean) / x_scale, -8.0, 8.0)
    y = np.clip(residual_train, -180.0, 180.0)
    rng = np.random.default_rng(20_260_805)
    shared_size = 28
    interaction_size = 14
    w1 = rng.normal(0.0, 0.055, size=(x.shape[1], shared_size))
    b1 = np.zeros(shared_size)
    base_w = rng.normal(0.0, 0.035, size=(shared_size, HORIZON_STEPS))
    base_b = np.mean(y, axis=0)
    event_w1 = rng.normal(0.0, 0.05, size=(shared_size, interaction_size))
    event_b1 = np.zeros(interaction_size)
    event_w2 = rng.normal(0.0, 0.025, size=(interaction_size, HORIZON_STEPS))
    event_b2 = np.zeros(HORIZON_STEPS)
    gate_w = rng.normal(0.0, 0.02, size=(shared_size, HORIZON_STEPS))
    gate_b = np.full(HORIZON_STEPS, -0.4)
    values = [
        w1,
        b1,
        base_w,
        base_b,
        event_w1,
        event_b1,
        event_w2,
        event_b2,
        gate_w,
        gate_b,
    ]
    moment1 = [np.zeros_like(value) for value in values]
    moment2 = [np.zeros_like(value) for value in values]
    learning_rate = 0.006
    regularization = 0.0006
    for iteration in range(1, 181):
        shared = np.tanh(x @ w1 + b1)
        base = shared @ base_w + base_b
        interaction_hidden = np.tanh(shared @ event_w1 + event_b1)
        refinement = interaction_hidden @ event_w2 + event_b2
        gate = 1.0 / (1.0 + np.exp(-np.clip(shared @ gate_w + gate_b, -20.0, 20.0)))
        prediction = base + gate * refinement
        error = prediction - y
        gradient_output = np.where(
            np.abs(error) <= 30.0, error, 30.0 * np.sign(error)
        )
        gradient_output /= max(1, x.shape[0] * HORIZON_STEPS)

        gradient_base_w = shared.T @ gradient_output + regularization * base_w
        gradient_base_b = gradient_output.sum(axis=0)
        gradient_refinement = gradient_output * gate
        gradient_event_w2 = (
            interaction_hidden.T @ gradient_refinement + regularization * event_w2
        )
        gradient_event_b2 = gradient_refinement.sum(axis=0)
        gradient_interaction_hidden = (
            gradient_refinement @ event_w2.T
        ) * (1.0 - interaction_hidden * interaction_hidden)
        gradient_event_w1 = (
            shared.T @ gradient_interaction_hidden + regularization * event_w1
        )
        gradient_event_b1 = gradient_interaction_hidden.sum(axis=0)
        gradient_gate_pre = gradient_output * refinement * gate * (1.0 - gate)
        gradient_gate_w = shared.T @ gradient_gate_pre + regularization * gate_w
        gradient_gate_b = gradient_gate_pre.sum(axis=0)
        gradient_shared = (
            gradient_output @ base_w.T
            + gradient_interaction_hidden @ event_w1.T
            + gradient_gate_pre @ gate_w.T
        ) * (1.0 - shared * shared)
        gradient_w1 = x.T @ gradient_shared + regularization * w1
        gradient_b1 = gradient_shared.sum(axis=0)
        gradients = [
            gradient_w1,
            gradient_b1,
            gradient_base_w,
            gradient_base_b,
            gradient_event_w1,
            gradient_event_b1,
            gradient_event_w2,
            gradient_event_b2,
            gradient_gate_w,
            gradient_gate_b,
        ]
        for index, (value, gradient) in enumerate(zip(values, gradients)):
            np.clip(gradient, -5.0, 5.0, out=gradient)
            moment1[index] = 0.9 * moment1[index] + 0.1 * gradient
            moment2[index] = 0.999 * moment2[index] + 0.001 * gradient * gradient
            corrected1 = moment1[index] / (1.0 - 0.9**iteration)
            corrected2 = moment2[index] / (1.0 - 0.999**iteration)
            value -= learning_rate * corrected1 / (np.sqrt(corrected2) + 1e-8)
    return {
        "kind": V3_NETWORK_KIND,
        "feature_schema": V3_FEATURE_SCHEMA,
        "x_mean": x_mean.tolist(),
        "x_scale": x_scale.tolist(),
        "w1": w1.tolist(),
        "b1": b1.tolist(),
        "base_w": base_w.tolist(),
        "base_b": base_b.tolist(),
        "event_w1": event_w1.tolist(),
        "event_b1": event_b1.tolist(),
        "event_w2": event_w2.tolist(),
        "event_b2": event_b2.tolist(),
        "gate_w": gate_w.tolist(),
        "gate_b": gate_b.tolist(),
    }


class ForecastService:
    def __init__(self) -> None:
        self._training_lock = threading.Lock()

    @staticmethod
    def _runtime_model_is_valid(record: ForecastModelRecord) -> bool:
        if record.version == BASELINE_VERSION:
            return True
        if record.architecture != STATIC_PERSONAL_ARCHITECTURE:
            return False
        parameters = _json_dict(record.parameters_json)
        if not _static_artifact_is_valid(parameters):
            return False
        artifact = parameters.get("artifact", {})
        evaluation = artifact.get("evaluation", {})
        metrics = _json_dict(record.metrics_json)
        return bool(
            artifact.get("model_version") == record.version
            and int(_finite(artifact.get("trained_at_ms"), -1))
            == int(record.trained_at_ms or -1)
            and int(_finite(artifact.get("data_cutoff_ms"), -1))
            == int(record.training_cutoff_ms or -1)
            and int(_finite(artifact.get("sample_count"), -1))
            == int(record.sample_count)
            and evaluation == metrics
        )

    def _ensure_baseline(self, session: Session) -> ForecastModelRecord:
        record = session.get(ForecastModelRecord, BASELINE_VERSION)
        if record is not None:
            return record
        now = _now_ms()
        baseline_parameters = _baseline_parameters()
        record = ForecastModelRecord(
            version=BASELINE_VERSION,
            status="champion",
            architecture="event-aware-persistence-prior-v3",
            created_at_ms=now,
            trained_at_ms=None,
            promoted_at_ms=now,
            training_cutoff_ms=None,
            sample_count=0,
            parameters_json=json.dumps(baseline_parameters, separators=(",", ":")),
            metrics_json="{}",
            decision_reason="Safe cold-start model",
        )
        session.add(record)
        try:
            session.commit()
        except IntegrityError:
            session.rollback()
            record = session.get(ForecastModelRecord, BASELINE_VERSION)
            if record is None:
                raise
        return record

    @staticmethod
    def _server_instance_id(session: Session) -> UUID:
        record = session.get(BackendMetadataRecord, "server_instance_id")
        if record is None:
            value = str(uuid4())
            record = BackendMetadataRecord(key="server_instance_id", value_text=value)
            session.add(record)
            try:
                session.commit()
            except IntegrityError:
                session.rollback()
                record = session.get(BackendMetadataRecord, "server_instance_id")
                if record is None:
                    raise
        try:
            return UUID(record.value_text)
        except (TypeError, ValueError):
            # Metadata is not user health data; repair only this invalid singleton.
            record.value_text = str(uuid4())
            session.commit()
            return UUID(record.value_text)

    @staticmethod
    def _source_revision(session: Session) -> tuple[int, int, int, int, int]:
        """One-statement fingerprint for source data concurrency guards."""

        event_revision = (
            select(func.max(SyncChangeRecord.id)).scalar_subquery()
        )
        active_events = (
            select(func.count(IntakeEventRecord.id))
            .where(IntakeEventRecord.deleted_at_ms.is_(None))
            .scalar_subquery()
        )
        reading_count, last_reading, max_received, sync_revision, event_count = (
            session.execute(
                select(
                    func.count(GlucoseReadingRecord.reading_id),
                    func.max(GlucoseReadingRecord.measured_at_ms),
                    func.max(GlucoseReadingRecord.received_at_ms),
                    event_revision,
                    active_events,
                )
            ).one()
        )
        return (
            int(reading_count or 0),
            int(last_reading or 0),
            int(max_received or 0),
            int(sync_revision or 0),
            int(event_count or 0),
        )
    def _champion(self, session: Session) -> ForecastModelRecord:
        """Return only the explicit valid pin; otherwise fail closed to baseline."""

        baseline = self._ensure_baseline(session)
        pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
        if pin is not None:
            selected = session.get(ForecastModelRecord, pin.value_text)
            if selected is not None and self._runtime_model_is_valid(selected):
                return selected
        if pin is None:
            session.add(
                BackendMetadataRecord(
                    key=ACTIVE_MODEL_METADATA_KEY, value_text=baseline.version
                )
            )
        else:
            pin.value_text = baseline.version
        session.commit()
        return baseline

    @staticmethod
    def _activation_history(session: Session) -> list[str]:
        record = session.get(BackendMetadataRecord, ACTIVATION_HISTORY_METADATA_KEY)
        if record is None:
            return []
        try:
            parsed = json.loads(record.value_text)
        except (TypeError, json.JSONDecodeError):
            return []
        return [str(item) for item in parsed if isinstance(item, str)] if isinstance(parsed, list) else []

    @staticmethod
    def _store_activation_history(session: Session, history: Sequence[str]) -> None:
        compact = list(history)[-50:]
        value = json.dumps(compact, separators=(",", ":"))
        record = session.get(BackendMetadataRecord, ACTIVATION_HISTORY_METADATA_KEY)
        if record is None:
            session.add(
                BackendMetadataRecord(
                    key=ACTIVATION_HISTORY_METADATA_KEY, value_text=value
                )
            )
        else:
            record.value_text = value

    def activate_model(self, session: Session, version: str) -> ForecastModelRecord:
        selected = session.get(ForecastModelRecord, version)
        if selected is None:
            raise ValueError(f"unknown forecast model version: {version}")
        if not self._runtime_model_is_valid(selected):
            raise ValueError(
                "only the baseline or an approved, checksummed static model can be activated"
            )
        if selected.status not in {"candidate", "champion", "retired"}:
            raise ValueError(f"forecast model {version} is not eligible for activation")
        pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
        if pin is None:
            session.add(
                BackendMetadataRecord(key=ACTIVE_MODEL_METADATA_KEY, value_text=version)
            )
        else:
            pin.value_text = version
        history = self._activation_history(session)
        if not history:
            history.append(BASELINE_VERSION)
        if not history or history[-1] != version:
            history.append(version)
        self._store_activation_history(session, history)
        now = _now_ms()
        for record in session.scalars(
            select(ForecastModelRecord).where(ForecastModelRecord.status == "champion")
        ):
            if record.version != version:
                record.status = "retired"
        selected.status = "champion"
        selected.promoted_at_ms = now
        session.commit()
        return selected

    def rollback_model(
        self, session: Session, version: str | None = None
    ) -> ForecastModelRecord:
        if version is None:
            current = self._champion(session)
            version = BASELINE_VERSION
            for candidate_version in reversed(self._activation_history(session)[:-1]):
                selected = session.get(ForecastModelRecord, candidate_version)
                if (
                    candidate_version != current.version
                    and selected is not None
                    and self._runtime_model_is_valid(selected)
                ):
                    version = candidate_version
                    break
        return self.activate_model(session, version)

    @staticmethod
    def _load_readings(
        session: Session,
        *,
        through_ms: int | None = None,
        from_ms: int | None = None,
        limit: int = 20_000,
    ) -> list[GlucoseReadingRecord]:
        statement = select(GlucoseReadingRecord)
        if through_ms is not None:
            statement = statement.where(GlucoseReadingRecord.measured_at_ms <= through_ms)
        if from_ms is not None:
            statement = statement.where(GlucoseReadingRecord.measured_at_ms >= from_ms)
        rows = list(
            session.scalars(
                statement.order_by(
                    GlucoseReadingRecord.measured_at_ms.desc(),
                    GlucoseReadingRecord.reading_id.desc(),
                ).limit(limit)
            )
        )
        rows.reverse()
        return rows

    @staticmethod
    def _occupied_bin_count(
        session: Session, *, through_ms: int | None = None
    ) -> int:
        bucket = cast(GlucoseReadingRecord.measured_at_ms / STEP_MS, Integer)
        statement = select(func.count(func.distinct(bucket)))
        if through_ms is not None:
            statement = statement.where(GlucoseReadingRecord.measured_at_ms <= through_ms)
        return int(session.scalar(statement) or 0)

    @staticmethod
    def _load_events(
        session: Session,
        *,
        through_ms: int,
        from_ms: int | None = None,
        known_through_ms: int | None = None,
    ) -> list[_Event]:
        statement = select(IntakeEventRecord).where(
            IntakeEventRecord.deleted_at_ms.is_(None),
            IntakeEventRecord.occurred_at_ms <= through_ms,
        )
        if from_ms is not None:
            statement = statement.where(IntakeEventRecord.occurred_at_ms >= from_ms)
        if known_through_ms is not None:
            statement = statement.where(
                IntakeEventRecord.created_at_ms <= known_through_ms
            )
        # Timestamp is intentionally not an identity: multiple independently
        # confirmed foods and insulin doses may occur in the same millisecond.
        # Stable tie-breakers keep their activity/list presentation repeatable
        # without merging any event.
        records = list(
            session.scalars(
                statement.order_by(
                    IntakeEventRecord.occurred_at_ms,
                    IntakeEventRecord.created_at_ms,
                    IntakeEventRecord.id,
                )
            )
        )
        analysis_ids = {record.analysis_id for record in records if record.analysis_id}
        analysis_by_id: dict[str, dict[str, Any]] = {}
        if analysis_ids:
            for identifier, raw in session.execute(
                select(AnalysisRecord.id, AnalysisRecord.result_json).where(
                    AnalysisRecord.id.in_(analysis_ids)
                )
            ):
                analysis_by_id[identifier] = _json_dict(raw)
        events: list[_Event] = []
        for record in records:
            analysis = analysis_by_id.get(record.analysis_id or "", {})
            if record.carbs_g is not None:
                meal_name = str(analysis.get("meal_name") or record.meal_text or "Meal")
                events.append(
                    _Event(
                        event_id=record.id,
                        occurred_at_ms=record.occurred_at_ms,
                        kind="meal",
                        label=f"{meal_name} · {record.carbs_g:g} g carbs",
                        amount=float(record.carbs_g),
                        known_at_ms=record.created_at_ms,
                        carbs_low_g=analysis.get("carbs_low_g"),
                        carbs_high_g=analysis.get("carbs_high_g"),
                        absorption_speed=analysis.get("absorption_speed"),
                        absorption_peak_minutes=analysis.get("absorption_peak_minutes"),
                        absorption_duration_minutes=analysis.get("absorption_duration_minutes"),
                        absorption_confidence=analysis.get("absorption_confidence"),
                        protein_g=analysis.get("estimated_protein_g"),
                        fat_g=analysis.get("estimated_fat_g"),
                        fiber_g=analysis.get("estimated_fiber_g"),
                        ai_confidence=(
                            _clamp(_finite(analysis.get("confidence")), 0, 1)
                            if "confidence" in analysis
                            else None
                        ),
                    )
                )
            elif record.insulin_units is not None:
                kind = "rapid" if record.insulin_type == "rapid" else "long"
                name = record.insulin_name or ("NovoRapid" if kind == "rapid" else "Tresiba")
                events.append(
                    _Event(
                        event_id=record.id,
                        occurred_at_ms=record.occurred_at_ms,
                        kind=kind,
                        label=f"{name} · {record.insulin_units:g} U",
                        amount=float(record.insulin_units),
                        known_at_ms=record.created_at_ms,
                    )
                )
        return events

    def ingest(self, session: Session, payload: GlucoseReadingsCreate) -> GlucoseReadingsResponse:
        inserted = 0
        unchanged = 0
        updated = 0
        context_updated = 0
        corrected_ids: list[str] = []
        now = _now_ms()
        unique_payload: dict[str, Any] = {}
        for reading in payload.readings:
            unique_payload[reading.reading_id] = reading
        existing_by_id = {
            item.reading_id: item
            for item in session.scalars(
                select(GlucoseReadingRecord).where(
                    GlucoseReadingRecord.reading_id.in_(unique_payload)
                )
            )
        }
        for reading_id, reading in unique_payload.items():
            reading_offset_minutes = (
                reading.utc_offset_minutes
                if reading.utc_offset_minutes is not None
                else payload.utc_offset_minutes
            )
            digest = _reading_payload_hash(reading, reading_offset_minutes)
            existing = existing_by_id.get(reading_id)
            if existing is not None:
                material_same = (
                    existing.measured_at_ms == reading.measured_at_ms
                    and existing.glucose_mg_dl == reading.glucose_mg_dl
                    and existing.trend_mg_dl_min == reading.trend_mg_dl_min
                )
                canonical_timestamp_id = reading_id == f"cgm-{reading.measured_at_ms}"
                if not material_same and not canonical_timestamp_id:
                    session.rollback()
                    raise ValueError(
                        f"reading_id {reading_id!r} is already used for different data"
                    )
                if not material_same:
                    existing.measured_at_ms = reading.measured_at_ms
                    existing.glucose_mg_dl = reading.glucose_mg_dl
                    existing.trend_mg_dl_min = reading.trend_mg_dl_min
                    existing.payload_hash = digest
                    existing.received_at_ms = now
                    updated += 1
                    corrected_ids.append(reading_id)
                else:
                    # Rewrite hashes created by the metadata-sensitive preview and
                    # enrich nullable transport context without creating a conflict.
                    unchanged += 1
                    if existing.payload_hash != digest:
                        existing.payload_hash = digest
                metadata_changed = False
                for name, value in (
                    ("sensor_id", reading.sensor_id),
                    ("sensor_generation", reading.sensor_generation),
                    ("quality", reading.quality),
                    ("utc_offset_minutes", reading_offset_minutes),
                ):
                    if value is not None and getattr(existing, name) != value:
                        setattr(existing, name, value)
                        metadata_changed = True
                if metadata_changed:
                    existing.received_at_ms = now
                    context_updated += 1
                continue
            session.add(
                GlucoseReadingRecord(
                    reading_id=reading.reading_id,
                    measured_at_ms=reading.measured_at_ms,
                    glucose_mg_dl=reading.glucose_mg_dl,
                    trend_mg_dl_min=reading.trend_mg_dl_min,
                    sensor_id=reading.sensor_id,
                    sensor_generation=reading.sensor_generation,
                    quality=reading.quality,
                    utc_offset_minutes=reading_offset_minutes,
                    payload_hash=digest,
                    received_at_ms=now,
                )
            )
            inserted += 1
        try:
            if updated:
                session.execute(
                    delete(ForecastScoreRecord).where(
                        ForecastScoreRecord.reading_id.in_(corrected_ids)
                    )
                )
            session.commit()
        except IntegrityError as error:
            session.rollback()
            raise ValueError("a reading identity conflicted during ingestion") from error

        latest_at = session.scalar(select(func.max(GlucoseReadingRecord.measured_at_ms)))
        if inserted or updated or context_updated:
            try:
                self.score_available(session)
            except Exception:
                session.rollback()
                logger.exception("forecast scoring failed after durable glucose ingestion")
            try:
                self.prune(session)
            except Exception:
                session.rollback()
                logger.exception("forecast retention maintenance failed")
        forecast_generated = False
        try:
            forecast_generated = bool(self.current(session).points)
        except Exception:
            session.rollback()
            logger.exception("forecast generation failed after durable glucose ingestion")
        return GlucoseReadingsResponse(
            inserted=inserted,
            unchanged=unchanged,
            updated=updated,
            latest_reading_at_ms=latest_at,
            forecast_generated=forecast_generated,
        )

    def rebuild_calibration(self, session: Session) -> None:
        """Compatibility no-op: runtime scoring never changes inference state."""

        del session

    @staticmethod
    def should_schedule_training(payload: GlucoseReadingsCreate) -> bool:
        del payload
        return False

    def prune(self, session: Session, now_ms: int | None = None) -> int:
        """Bound immutable forecast audit storage while retaining 30-day metrics."""

        now = now_ms if now_ms is not None else _now_ms()
        marker = session.get(ForecastMaintenanceRecord, "last_prune")
        if marker is not None and now - marker.value_ms < PRUNE_INTERVAL_MS:
            return 0
        cutoff = now - FORECAST_RETENTION_DAYS * 86_400_000
        old_count = session.scalar(
            select(func.count(ForecastRunRecord.id)).where(
                ForecastRunRecord.generated_at_ms < cutoff
            )
        ) or 0
        if old_count:
            # Forecast points and scores are owned by a run and cascade with it.
            session.execute(
                delete(ForecastRunRecord).where(
                    ForecastRunRecord.generated_at_ms < cutoff
                )
            )
        if marker is None:
            session.add(ForecastMaintenanceRecord(key="last_prune", value_ms=now))
        else:
            marker.value_ms = now
        session.commit()
        return int(old_count)

    def maybe_train(self, session: Session) -> None:
        """Backward-compatible no-op; training is an explicit local admin action."""

        del session
        return None

    @staticmethod
    def _calibration(
        session: Session, model_version: str,
    ) -> dict[int, ForecastCalibrationRecord]:
        return {
            row.step_minutes: row
            for row in session.scalars(
                select(ForecastCalibrationRecord).where(
                    ForecastCalibrationRecord.model_version == model_version
                )
            )
        }

    def score_available(self, session: Session) -> int:
        latest_at = session.scalar(select(func.max(GlucoseReadingRecord.measured_at_ms)))
        if latest_at is None:
            return 0
        candidates = list(
            session.execute(
                select(ForecastPointRecord, ForecastRunRecord)
                .join(ForecastRunRecord, ForecastRunRecord.id == ForecastPointRecord.run_id)
                .outerjoin(
                    ForecastScoreRecord,
                    and_(
                        ForecastScoreRecord.run_id == ForecastPointRecord.run_id,
                        ForecastScoreRecord.step_minutes == ForecastPointRecord.step_minutes,
                    ),
                )
                .where(
                    ForecastScoreRecord.run_id.is_(None),
                    ForecastPointRecord.at_ms <= latest_at + MATCH_TOLERANCE_MS,
                )
                .limit(20_000)
            )
        )
        if not candidates:
            return 0
        minimum = min(point.at_ms for point, _run in candidates) - MATCH_TOLERANCE_MS
        readings = list(
            session.scalars(
                select(GlucoseReadingRecord)
                .where(
                    GlucoseReadingRecord.measured_at_ms >= minimum,
                    GlucoseReadingRecord.measured_at_ms <= latest_at + MATCH_TOLERANCE_MS,
                )
                .order_by(GlucoseReadingRecord.measured_at_ms)
            )
        )
        reading_times = [item.measured_at_ms for item in readings]
        now = _now_ms()
        scored = 0
        for point, run in candidates:
            actual = _nearest_value(
                readings, point.at_ms, MATCH_TOLERANCE_MS, reading_times
            )
            if actual is None or actual.received_at_ms < run.generated_at_ms:
                continue
            residual = float(actual.glucose_mg_dl - point.median_mg_dl)
            session.add(
                ForecastScoreRecord(
                    run_id=point.run_id,
                    step_minutes=point.step_minutes,
                    model_version=run.model_version,
                    forecast_at_ms=point.at_ms,
                    reading_id=actual.reading_id,
                    actual_mg_dl=actual.glucose_mg_dl,
                    residual_mg_dl=residual,
                    absolute_error_mg_dl=abs(residual),
                    squared_error=residual * residual,
                    inside_interval=(
                        1 if point.low_mg_dl <= actual.glucose_mg_dl <= point.high_mg_dl else 0
                    ),
                    scored_at_ms=now,
                )
            )
            scored += 1
        if scored:
            session.commit()
        return scored

    @staticmethod
    def _input_hash(
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
        model_version: str,
        calibration: dict[int, ForecastCalibrationRecord] | None = None,
        event_revision: int = 0,
    ) -> str:
        del calibration
        content = {
            "engine": FORECAST_ENGINE_VERSION,
            "action_profile_contract": ACTION_PROFILE_CONTRACT_VERSION,
            "model": model_version,
            "event_revision": event_revision,
            "readings": [
                (
                    row.reading_id,
                    row.payload_hash,
                    row.quality,
                    row.utc_offset_minutes,
                )
                for row in readings
            ],
            "events": [
                (
                    event.event_id,
                    event.occurred_at_ms,
                    _event_known_at(event),
                    event.kind,
                    event.amount,
                    event.carbs_low_g,
                    event.carbs_high_g,
                    event.absorption_speed,
                    event.absorption_peak_minutes,
                    event.absorption_duration_minutes,
                    event.absorption_confidence,
                    event.protein_g,
                    event.fat_g,
                    event.fiber_g,
                    event.ai_confidence,
                )
                for event in events
            ],
        }
        return hashlib.sha256(
            json.dumps(content, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()

    @staticmethod
    def _quality_status(
        readings: Sequence[GlucoseReadingRecord], anchor_ms: int
    ) -> tuple[str, float, float]:
        reading_times = [row.measured_at_ms for row in readings]
        grid_rows: list[GlucoseReadingRecord] = []
        seen_ids: set[str] = set()
        for offset in range(HISTORY_STEPS):
            target = anchor_ms - offset * STEP_MS
            matched = _nearest_value(
                readings, target, MATCH_TOLERANCE_MS, reading_times
            )
            if matched is not None and matched.reading_id not in seen_ids:
                grid_rows.append(matched)
                seen_ids.add(matched.reading_id)
        coverage = len(grid_rows) / HISTORY_STEPS
        qualities = [row.quality if row.quality is not None else 0.75 for row in grid_rows]
        quality = float(np.mean(qualities)) if qualities else 0.55
        poor = coverage < 0.65 or quality < 0.45
        confidence = _clamp(0.18 + 0.52 * coverage * quality, 0.1, 0.72)
        return ("low_confidence" if poor else "ok"), confidence, coverage

    def _activities(
        self,
        events: Sequence[_Event],
        anchor_ms: int,
        parameters: dict[str, Any],
        readings: Sequence[GlucoseReadingRecord] | None = None,
    ) -> list[ForecastActivity]:
        activities: list[ForecastActivity] = []
        model_readings = readings or ()
        basal_scale, basal_context_confidence = _basal_context_amplitude(
            model_readings, events, anchor_ms, parameters
        )
        for event in events:
            # Zero-carbohydrate meal analyses are valid audit records but cannot
            # satisfy the strictly-positive public ForecastActivity amount contract.
            if not math.isfinite(event.amount) or event.amount <= 0.0:
                continue
            contextual = bool(
                readings
                and _has_contextual_personal_model(parameters)
                and _has_contextual_event_evidence(parameters, event)
            )
            estimate = _prior_action_estimate(event, events, parameters)
            if event.kind == "rapid" and contextual:
                estimate = _rapid_contextual_action_estimate(
                    model_readings, events, event, anchor_ms, parameters
                )
            elif event.kind == "long":
                # Long doses are an overlapping depot.  Even with a contextual
                # champion they share one slow amplitude adaptation; no per-dose
                # neural peak or width is inferred from collinear CGM evidence.
                confidence = estimate.attribution_confidence
                if basal_context_confidence > 0.0:
                    confidence = _clamp(
                        max(confidence, basal_context_confidence)
                        / math.sqrt(1.0 + estimate.overlap_count),
                        0.02,
                        0.45,
                    )
                long_contribution = np.asarray(
                    [
                        basal_scale
                        * _event_glucose_increment(
                            event,
                            anchor_ms,
                            anchor_ms + minute * 60_000,
                            parameters,
                        )
                        for minute in range(
                            0, HORIZON_MINUTES + STEP_MINUTES, STEP_MINUTES
                        )
                    ],
                    dtype=np.float64,
                )
                long_activity = np.asarray(
                    [
                        _basal_depot_activity(
                            (
                                anchor_ms
                                + minute * 60_000
                                - event.occurred_at_ms
                            )
                            / 60_000.0,
                            estimate.duration_minutes,
                        )
                        for minute in range(
                            0, HORIZON_MINUTES + STEP_MINUTES, STEP_MINUTES
                        )
                    ],
                    dtype=np.float64,
                )
                estimate = replace(
                    estimate,
                    amplitude_scale=basal_scale,
                    attribution_confidence=confidence,
                    identifiability=(
                        "low"
                        if basal_context_confidence > 0.0
                        and estimate.overlap_count == 0
                        else "not_identifiable"
                    ),
                    action_model="basal_depot",
                    contribution_values=long_contribution,
                    activity_values=long_activity,
                )

            end_ms = event.occurred_at_ms + round(
                estimate.duration_minutes * 60_000
            )
            if end_ms < anchor_ms:
                continue
            if event.kind == "meal":
                speed, _peak, _duration, _estimate_confidence = _resolve_meal_profile(event)
                strength = _clamp(
                    event.amount / 80.0 * estimate.amplitude_scale, 0.04, 1.0
                )
                unit = "g"
                if any(
                    value is not None
                    for value in (
                        event.absorption_speed,
                        event.absorption_peak_minutes,
                        event.absorption_duration_minutes,
                    )
                ):
                    profile_source = "ai_estimate"
                elif any(
                    value is not None
                    for value in (event.protein_g, event.fat_g, event.fiber_g)
                ):
                    profile_source = "nutrient_estimate"
                else:
                    profile_source = "population_prior"
            elif event.kind == "rapid":
                speed = None
                strength = _clamp(
                    event.amount / 10.0 * estimate.amplitude_scale, 0.04, 1.0
                )
                unit = "U"
                profile_source = (
                    "personalized"
                    if int(parameters.get("evidence_counts", {}).get("rapid", 0) or 0)
                    >= MINIMUM_CLEAN_EVENT_SAMPLES
                    else "population_prior"
                )
            else:
                speed = None
                # Long insulin is intentionally visually lower and much wider.
                strength = _clamp(
                    event.amount / 45.0 * estimate.amplitude_scale, 0.03, 0.62
                )
                unit = "U"
                profile_source = (
                    "personalized"
                    if int(parameters.get("evidence_counts", {}).get("long", 0) or 0)
                    >= MINIMUM_CLEAN_EVENT_SAMPLES
                    else "population_prior"
                )
            if contextual and event.kind == "meal":
                # Keep the established wire enum for existing Android clients;
                # the point series, rather than a new label, carries the v3
                # context-dependent estimate.
                profile_source = "personalized"
                contextual_values = np.concatenate(
                    (
                        np.zeros(1, dtype=np.float64),
                        _contextual_event_contribution(
                            readings or (), events, event, anchor_ms, parameters
                        ),
                    )
                )
                changes = np.diff(contextual_values, prepend=contextual_values[0])
                raw_activity = (
                    np.maximum(changes, 0.0)
                    if event.kind == "meal"
                    else np.maximum(-changes, 0.0)
                )
                maximum_activity = float(np.max(raw_activity))
                activity_values = (
                    raw_activity / maximum_activity
                    if maximum_activity > 1e-9
                    else np.zeros_like(raw_activity)
                )
                prior_peak_ms = event.occurred_at_ms + round(
                    estimate.peak_minutes * 60_000
                )
                if (
                    maximum_activity > 1e-9
                    and anchor_ms <= prior_peak_ms <= anchor_ms + HORIZON_MINUTES * 60_000
                ):
                    displayed_peak_ms = anchor_ms + int(
                        np.argmax(activity_values)
                    ) * STEP_MS
                    effective_peak = (
                        displayed_peak_ms - event.occurred_at_ms
                    ) / 60_000.0
                    radius = max(
                        10.0,
                        (
                            estimate.peak_high_minutes
                            - estimate.peak_low_minutes
                        )
                        / 2.0,
                    )
                    estimate = replace(
                        estimate,
                        peak_minutes=effective_peak,
                        peak_low_minutes=max(
                            estimate.onset_minutes + 5.0,
                            effective_peak - radius,
                        ),
                        peak_high_minutes=min(
                            estimate.duration_minutes - 5.0,
                            effective_peak + radius,
                        ),
                        attribution_confidence=_clamp(
                            estimate.attribution_confidence
                            / math.sqrt(1.0 + estimate.overlap_count),
                            0.02,
                            0.88,
                        ),
                        identifiability=(
                            "medium"
                            if estimate.overlap_count == 0
                            else "low"
                        ),
                        action_model="contextual_counterfactual",
                        contribution_values=contextual_values,
                        activity_values=activity_values,
                    )
                else:
                    displayed_peak_ms = prior_peak_ms
                    estimate = replace(
                        estimate,
                        action_model="contextual_counterfactual",
                        identifiability="low",
                        contribution_values=contextual_values,
                        activity_values=activity_values,
                    )
            else:
                contextual_values = estimate.contribution_values
                activity_values = estimate.activity_values
                displayed_peak_ms = event.occurred_at_ms + round(
                    estimate.peak_minutes * 60_000
                )
                if estimate.action_model == "contextual_counterfactual":
                    profile_source = "personalized"
            contribution_points: list[ForecastActivityPoint] = []
            for minute in range(0, HORIZON_MINUTES + STEP_MINUTES, STEP_MINUTES):
                at_ms = anchor_ms + minute * 60_000
                point_index = minute // STEP_MINUTES
                contribution = (
                    float(contextual_values[point_index])
                    if contextual_values is not None
                    else _event_glucose_increment(event, anchor_ms, at_ms, parameters)
                )
                age_minutes = (at_ms - event.occurred_at_ms) / 60_000.0
                activity = (
                    float(activity_values[point_index])
                    if activity_values is not None
                    else _event_activity(
                        event,
                        age_minutes,
                        estimate.peak_minutes,
                        estimate.duration_minutes,
                        parameters,
                    )
                )
                contribution_points.append(
                    ForecastActivityPoint(
                        at_ms=at_ms,
                        minutes_from_anchor=minute,
                        contribution_mg_dl=round(
                            _clamp(_finite(contribution), -600.0, 600.0), 3
                        ),
                        activity=round(
                            _clamp(
                                _finite(activity),
                                0.0,
                                1.0,
                            ),
                            6,
                        ),
                    )
                )
            activities.append(
                ForecastActivity(
                    event_id=UUID(event.event_id),
                    kind=event.kind,
                    label=event.label,
                    start_ms=event.occurred_at_ms,
                    peak_ms=displayed_peak_ms,
                    end_ms=end_ms,
                    strength=strength,
                    confidence=estimate.attribution_confidence,
                    absorption_speed=speed,
                    amount=event.amount,
                    unit=unit,
                    profile_source=profile_source,
                    profile_confidence=estimate.attribution_confidence,
                    points=contribution_points,
                    onset_ms=event.occurred_at_ms
                    + round(estimate.onset_minutes * 60_000),
                    peak_low_ms=event.occurred_at_ms
                    + round(estimate.peak_low_minutes * 60_000),
                    peak_high_ms=event.occurred_at_ms
                    + round(estimate.peak_high_minutes * 60_000),
                    end_low_ms=event.occurred_at_ms
                    + round(estimate.end_low_minutes * 60_000),
                    end_high_ms=event.occurred_at_ms
                    + round(estimate.end_high_minutes * 60_000),
                    attribution_confidence=estimate.attribution_confidence,
                    identifiability=estimate.identifiability,
                    action_model=estimate.action_model,
                    overlap_count=estimate.overlap_count,
                )
            )
        return activities

    @staticmethod
    def _run_response(
        session: Session, run: ForecastRunRecord
    ) -> ForecastCurrentResponse:
        points = list(
            session.scalars(
                select(ForecastPointRecord)
                .where(ForecastPointRecord.run_id == run.id)
                .order_by(ForecastPointRecord.step_minutes)
            )
        )
        try:
            activities = [ForecastActivity.model_validate(item) for item in json.loads(run.activities_json)]
        except (TypeError, ValueError, json.JSONDecodeError):
            activities = []
        return ForecastCurrentResponse(
            status=run.status,
            generated_at_ms=run.generated_at_ms,
            based_on_reading_at_ms=run.based_on_reading_at_ms,
            horizon_minutes=120,
            model_version=run.model_version,
            confidence=run.confidence,
            points=[
                ForecastPoint(
                    at_ms=point.at_ms,
                    median_mg_dl=point.median_mg_dl,
                    low_mg_dl=point.low_mg_dl,
                    high_mg_dl=point.high_mg_dl,
                )
                for point in points
            ],
            activities=activities,
            conditional_notice=run.conditional_notice,
        )

    def current(self, session: Session, now_ms: int | None = None) -> ForecastCurrentResponse:
        now = now_ms if now_ms is not None else _now_ms()
        champion = self._champion(session)
        latest = session.scalar(
            select(GlucoseReadingRecord).order_by(
                GlucoseReadingRecord.measured_at_ms.desc(),
                GlucoseReadingRecord.reading_id.desc(),
            )
        )
        if latest is None:
            return ForecastCurrentResponse(
                status="no_data",
                generated_at_ms=now,
                based_on_reading_at_ms=None,
                horizon_minutes=120,
                model_version=champion.version,
                confidence=0.0,
                points=[],
                activities=[],
                conditional_notice=CONDITIONAL_NOTICE,
            )
        anchor_ms = latest.measured_at_ms
        parameters = (
            _baseline_parameters()
            if champion.version == BASELINE_VERSION
            else (_json_dict(champion.parameters_json) or _default_parameters())
        )
        events = self._load_events(
            # A confirmed event recorded after the last CGM sample but before this
            # request is known causal information. Its negative age makes its curve
            # begin only at the correct future offset from the reading anchor.
            session,
            through_ms=now,
            from_ms=anchor_ms - 96 * 60 * 60_000,
            known_through_ms=now,
        )
        readings = self._load_readings(
            session,
            through_ms=anchor_ms,
            from_ms=(
                anchor_ms
                - CONTEXT_HISTORY_MINUTES * 60_000
                - MATCH_TOLERANCE_MS
            ),
            limit=20_000,
        )
        if now - anchor_ms > STALE_AFTER_MS:
            activities = self._activities(
                events, anchor_ms, parameters, readings=readings
            )
            return ForecastCurrentResponse(
                status="stale",
                generated_at_ms=now,
                based_on_reading_at_ms=anchor_ms,
                horizon_minutes=120,
                model_version=champion.version,
                confidence=0.0,
                points=[],
                activities=activities,
                conditional_notice=CONDITIONAL_NOTICE,
            )

        quality_status, quality_confidence, coverage = self._quality_status(readings, anchor_ms)
        occupied_bins = self._occupied_bin_count(session, through_ms=anchor_ms)
        personalization_days = occupied_bins / (24 * 60 / STEP_MINUTES)
        status_value = quality_status
        if status_value == "ok":
            if champion.version == BASELINE_VERSION:
                status_value = "cold_start"
            else:
                status_value = "ready"
        if champion.version == BASELINE_VERSION:
            model_confidence = 0.34
        else:
            artifact = parameters.get("artifact", {})
            reliability = artifact.get("reliability", {}) if isinstance(artifact, dict) else {}
            model_confidence = _clamp(
                _finite(reliability.get("overall"), 0.30), 0.12, 0.65
            )
        meal_uncertainty_sigma, event_confidence_multiplier = _meal_event_uncertainty(
            events, anchor_ms, parameters
        )
        confidence = _clamp(
            min(quality_confidence, model_confidence) * event_confidence_multiplier,
            0.05,
            0.9,
        )
        event_revision = int(session.scalar(select(func.max(SyncChangeRecord.id))) or 0)
        input_hash = self._input_hash(
            readings, events, champion.version, None, event_revision
        )
        existing = session.scalar(
            select(ForecastRunRecord)
            .where(
                ForecastRunRecord.based_on_reading_at_ms == anchor_ms,
                ForecastRunRecord.model_version == champion.version,
                ForecastRunRecord.input_hash == input_hash,
            )
            .order_by(ForecastRunRecord.generated_at_ms.desc())
        )
        if existing is not None:
            return self._run_response(session, existing)

        median, sigma = _forecast_arrays(readings, events, anchor_ms, parameters)
        if champion.architecture == STATIC_PERSONAL_ARCHITECTURE:
            reference = _event_reference_prediction(
                readings, events, anchor_ms, parameters
            )
            median, sigma = _apply_static_predictor(
                median, reference, sigma, parameters
            )
        # Activity explanations use the same complete causal context and v3
        # median model as the trajectory.  Legacy/baseline champions still use
        # the prior curves through the guarded fallback in `_activities`.
        activities = self._activities(
            events, anchor_ms, parameters, readings=readings
        )
        if champion.version == BASELINE_VERSION:
            sigma *= 1.25
        if status_value == "low_confidence":
            sigma *= 1.0 + max(0.45, 1.0 - coverage)
        sigma = np.sqrt(sigma * sigma + meal_uncertainty_sigma * meal_uncertainty_sigma)
        median = np.clip(
            np.nan_to_num(median, nan=float(latest.glucose_mg_dl), posinf=600.0, neginf=20.0),
            20.0,
            600.0,
        )
        sigma = np.nan_to_num(sigma, nan=60.0, posinf=120.0, neginf=60.0)
        sigma = np.clip(sigma, 6.0, 200.0)
        # 80% central interval; it widens monotonically to avoid visual false precision.
        half_width = np.maximum.accumulate(np.maximum(7.0, sigma * 1.2816))
        low = np.clip(median - half_width, 20.0, 600.0)
        high = np.clip(median + half_width, 20.0, 600.0)
        run = ForecastRunRecord(
            id=str(uuid4()),
            generated_at_ms=now,
            based_on_reading_at_ms=anchor_ms,
            model_version=champion.version,
            horizon_minutes=120,
            confidence=confidence,
            status=status_value,
            conditional_notice=CONDITIONAL_NOTICE,
            input_hash=input_hash,
            activities_json=json.dumps(
                [item.model_dump(mode="json") for item in activities], separators=(",", ":")
            ),
        )
        session.add(run)
        # Flush the immutable parent snapshot before bulk point inserts. SQLAlchemy has
        # no ORM relationship here, so an explicit boundary keeps SQLite FK ordering
        # deterministic.
        session.flush()
        for index, step in enumerate(range(STEP_MINUTES, HORIZON_MINUTES + 1, STEP_MINUTES)):
            session.add(
                ForecastPointRecord(
                    run_id=run.id,
                    step_minutes=step,
                    at_ms=anchor_ms + step * 60_000,
                    median_mg_dl=round(float(median[index]), 3),
                    low_mg_dl=round(float(low[index]), 3),
                    high_mg_dl=round(float(high[index]), 3),
                )
            )
        session.commit()
        return self._run_response(session, run)

    @staticmethod
    def _training_windows(
        readings: Sequence[GlucoseReadingRecord],
        *,
        max_windows: int | None = MAX_TRAINING_WINDOWS,
    ) -> list[tuple[int, np.ndarray]]:
        windows: list[tuple[int, np.ndarray]] = []
        if not readings:
            return windows
        reading_times = [row.measured_at_ms for row in readings]
        previous_bucket: int | None = None
        for anchor_index, anchor in enumerate(readings):
            bucket = anchor.measured_at_ms // STEP_MS
            if bucket == previous_bucket:
                continue
            previous_bucket = bucket
            if anchor.measured_at_ms - readings[0].measured_at_ms < (HISTORY_STEPS - 1) * STEP_MS:
                continue
            if readings[-1].measured_at_ms - anchor.measured_at_ms < HORIZON_STEPS * STEP_MS:
                continue
            history_rows = [
                _nearest_value(
                    readings,
                    anchor.measured_at_ms - offset * STEP_MS,
                    MATCH_TOLERANCE_MS,
                    reading_times,
                )
                for offset in range(HISTORY_STEPS - 1, -1, -1)
            ]
            future_rows = [
                _nearest_value(
                    readings,
                    anchor.measured_at_ms + step * STEP_MS,
                    MATCH_TOLERANCE_MS,
                    reading_times,
                )
                for step in range(1, HORIZON_STEPS + 1)
            ]
            if any(row is None for row in history_rows) or any(
                row is None for row in future_rows
            ):
                continue
            concrete_history = [row for row in history_rows if row is not None]
            concrete_future = [row for row in future_rows if row is not None]
            if len({row.reading_id for row in concrete_history}) != HISTORY_STEPS:
                continue
            if (
                len({row.reading_id for row in concrete_future}) != HORIZON_STEPS
                or any(row.measured_at_ms <= anchor.measured_at_ms for row in concrete_future)
            ):
                continue
            target = np.asarray(
                [row.glucose_mg_dl for row in concrete_future], dtype=np.float64
            )
            windows.append((anchor_index, target))
        if max_windows is not None and len(windows) > max_windows:
            indexes = np.linspace(0, len(windows) - 1, max_windows, dtype=int)
            windows = [windows[index] for index in indexes]
        return windows

    @staticmethod
    def _event_aligned_effects(
        readings: Sequence[GlucoseReadingRecord], events: Sequence[_Event], kind: str
    ) -> tuple[list[float], list[float]]:
        peaks: list[float] = []
        sensitivities: list[float] = []
        search_end = 360 if kind in {"meal", "rapid"} else 2_880
        sign = 1.0 if kind == "meal" else -1.0
        prior_parameters = _default_parameters()
        reading_times = [item.measured_at_ms for item in readings]
        for event in [item for item in events if item.kind == kind]:
            window_start = event.occurred_at_ms - 30 * 60_000
            window_end = event.occurred_at_ms + search_end * 60_000
            neighbours = [
                other
                for other in events
                if other.event_id != event.event_id
                and window_start <= other.occurred_at_ms <= window_end
            ]
            # A meal and its usual pre-bolus are a single observable episode and
            # must not invalidate each other. Reject only genuinely ambiguous dense
            # episodes; known neighbours are conditioned out with broad priors below.
            if kind in {"meal", "rapid"}:
                same_kind = [other for other in neighbours if other.kind == kind]
                non_long = [other for other in neighbours if other.kind != "long"]
                if same_kind or len(non_long) > 2:
                    continue
            else:
                # Daily basal dosing is expected. Only duplicate/stacked basal entries
                # within six hours are too ambiguous to attribute safely.
                if any(
                    other.kind == "long"
                    and abs(other.occurred_at_ms - event.occurred_at_ms)
                    < 6 * 60 * 60_000
                    for other in neighbours
                ):
                    continue
            if event.amount <= 0:
                continue
            baseline = _nearest_value(
                readings, event.occurred_at_ms, MATCH_TOLERANCE_MS, reading_times
            )
            before = _nearest_value(
                readings,
                event.occurred_at_ms - 30 * 60_000,
                MATCH_TOLERANCE_MS,
                reading_times,
            )
            if baseline is None or before is None:
                continue
            elapsed_before = max(
                5.0, (baseline.measured_at_ms - before.measured_at_ms) / 60_000.0
            )
            pre_event_trend = _clamp(
                (baseline.glucose_mg_dl - before.glucose_mg_dl) / elapsed_before,
                -2.0,
                2.0,
            )
            observations: list[tuple[int, float]] = []
            step = 10 if kind != "long" else 60
            for minute in range(step, search_end + 1, step):
                found = _nearest_value(
                    readings,
                    event.occurred_at_ms + minute * 60_000,
                    MATCH_TOLERANCE_MS,
                    reading_times,
                )
                if found is not None:
                    # A short local slope contains useful momentum, but extrapolating
                    # it over 6-48 h can fabricate hundreds of mg/dL. Saturate it like
                    # the live trend prior, and cap residual drift for extra safety.
                    trend_horizon = 42.0 if kind in {"meal", "rapid"} else 60.0
                    trend_delta = pre_event_trend * trend_horizon * (
                        1.0 - math.exp(-minute / trend_horizon)
                    )
                    trend_delta = _clamp(
                        trend_delta, -60.0 if kind != "long" else -30.0,
                        60.0 if kind != "long" else 30.0,
                    )
                    confounder_delta = sum(
                        _event_glucose_increment(
                            other,
                            event.occurred_at_ms,
                            found.measured_at_ms,
                            prior_parameters,
                        )
                        for other in events
                        if other.event_id != event.event_id
                    )
                    detrended = (
                        found.glucose_mg_dl
                        - baseline.glucose_mg_dl
                        - trend_delta
                        - confounder_delta
                    )
                    observations.append(
                        (minute, sign * detrended)
                    )
            if len(observations) < 4:
                continue
            peak_minute, effect = max(observations, key=lambda item: item[1])
            if effect <= 3.0:
                continue
            peaks.append(float(peak_minute))
            sensitivities.append(effect / event.amount)
        return peaks, sensitivities

    def _personalized_parameters(
        self,
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
    ) -> dict[str, Any]:
        parameters = _default_parameters()
        profiles = dict(parameters["profiles"])
        sensitivities = dict(parameters["sensitivities"])
        evidence_counts: dict[str, int] = {}
        for kind, sensitivity_name, prior, low, high in (
            ("meal", "carb_mg_dl_per_g", 0.85, 0.1, 4.0),
            ("rapid", "rapid_mg_dl_per_unit", 7.0, 1.0, 30.0),
            ("long", "long_mg_dl_per_unit", 2.0, 0.15, 12.0),
        ):
            peaks, effects = self._event_aligned_effects(readings, events, kind)
            evidence_counts[kind] = len(effects)
            if len(effects) >= MINIMUM_CLEAN_EVENT_SAMPLES:
                blend = min(0.8, len(effects) / 20.0)
                empirical = _clamp(float(np.median(effects)), low, high)
                sensitivities[sensitivity_name] = (1.0 - blend) * prior + blend * empirical
            # Only the rapid kernel gets a globally learned timing update.  Daily
            # long-insulin depots overlap for most of their support, so CGM cannot
            # identify a truthful sharp peak/end for one Tresiba injection.  Long
            # evidence may adapt basal sensitivity, never per-dose timing.
            if len(peaks) >= MINIMUM_CLEAN_EVENT_SAMPLES and kind == "rapid":
                key = f"{kind}_peak_minutes"
                prior_peak = profiles[key]
                blend = min(0.75, len(peaks) / 20.0)
                profiles[key] = (1.0 - blend) * prior_peak + blend * float(np.median(peaks))
                duration_key = f"{kind}_duration_minutes"
                lower = profiles[key] + 30.0
                estimated_duration = max(lower, float(np.quantile(peaks, 0.8)) * 2.2)
                profiles[duration_key] = (1.0 - blend) * profiles[duration_key] + blend * estimated_duration
        profiles["rapid_peak_minutes"] = _clamp(profiles["rapid_peak_minutes"], 20, 240)
        profiles["rapid_duration_minutes"] = _clamp(
            profiles["rapid_duration_minutes"],
            profiles["rapid_peak_minutes"] + 30,
            720,
        )
        profiles["long_peak_minutes"] = _clamp(profiles["long_peak_minutes"], 240, 1_800)
        profiles["long_duration_minutes"] = _clamp(
            profiles["long_duration_minutes"],
            profiles["long_peak_minutes"] + 120,
            4_320,
        )
        for kind, prior in (("meal", 0.18), ("rapid", 0.18), ("long", 0.12)):
            count = evidence_counts.get(kind, 0)
            profiles[f"{kind}_profile_confidence"] = (
                prior
                if count < MINIMUM_CLEAN_EVENT_SAMPLES
                else _clamp(0.28 + count / 24.0, prior, 0.88)
            )
        parameters["profiles"] = profiles
        parameters["sensitivities"] = sensitivities
        parameters["evidence_counts"] = evidence_counts
        parameters["kind"] = "personalized_hybrid_neural"
        return parameters

    def _dataset_for_parameters(
        self,
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
        windows: Sequence[tuple[int, np.ndarray]],
        parameters: dict[str, Any],
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        features: list[np.ndarray] = []
        baselines: list[np.ndarray] = []
        targets: list[np.ndarray] = []
        sorted_events = sorted(events, key=lambda item: item.occurred_at_ms)
        reading_times = [row.measured_at_ms for row in readings]
        history_minutes = (
            CONTEXT_HISTORY_MINUTES
            if parameters.get("feature_schema")
            in {V3_FEATURE_SCHEMA, STATIC_FEATURE_SCHEMA}
            else (HISTORY_STEPS - 1) * STEP_MINUTES
        )
        for anchor_index, target in windows:
            anchor = readings[anchor_index]
            causal_recent = [
                item
                for item in sorted_events
                if item.occurred_at_ms <= anchor.measured_at_ms
                and _event_known_at(item) <= anchor.measured_at_ms
                and item.occurred_at_ms
                >= anchor.measured_at_ms - 96 * 60 * 60_000
            ]
            history_start = (
                anchor.measured_at_ms
                - history_minutes * 60_000
                - MATCH_TOLERANCE_MS
            )
            history_index = bisect.bisect_left(reading_times, history_start)
            causal_readings = readings[history_index : anchor_index + 1]
            features.append(
                _history_features(causal_readings, causal_recent, anchor.measured_at_ms, parameters)
            )
            reference_function = (
                _event_reference_prediction
                if (
                    parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA
                    or parameters.get("prediction_reference")
                    == "event_aware_persistence"
                )
                else _baseline_prediction
            )
            baselines.append(
                reference_function(
                    causal_readings,
                    causal_recent,
                    anchor.measured_at_ms,
                    parameters,
                )
            )
            targets.append(target)
        return np.vstack(features), np.vstack(baselines), np.vstack(targets)

    @staticmethod
    def _reference_for_windows(
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
        windows: Sequence[tuple[int, np.ndarray]],
        parameters: dict[str, Any],
    ) -> np.ndarray:
        sorted_events = sorted(events, key=lambda item: item.occurred_at_ms)
        references: list[np.ndarray] = []
        for anchor_index, _target in windows:
            anchor = readings[anchor_index]
            causal_events = [
                event
                for event in sorted_events
                if event.occurred_at_ms <= anchor.measured_at_ms
                and _event_known_at(event) <= anchor.measured_at_ms
                and event.occurred_at_ms
                >= anchor.measured_at_ms - 96 * 60 * 60_000
            ]
            references.append(
                _event_reference_prediction(
                    [anchor], causal_events, anchor.measured_at_ms, parameters
                )
            )
        return np.vstack(references)

    @staticmethod
    def _metrics(prediction: np.ndarray, target: np.ndarray) -> dict[str, float]:
        absolute = np.abs(prediction - target)
        return {
            "mae": float(np.mean(absolute)),
            "mae_30": float(np.mean(absolute[:, 5])),
            "mae_60": float(np.mean(absolute[:, 11])),
            "mae_120": float(np.mean(absolute[:, 23])),
            "rmse": float(np.sqrt(np.mean((prediction - target) ** 2))),
        }

    @staticmethod
    def _interval_metrics(
        prediction: np.ndarray,
        target: np.ndarray,
        sigma: np.ndarray,
    ) -> dict[str, float | None]:
        half_width = 1.2816 * np.maximum(6.0, sigma.reshape(1, -1))
        low = prediction - half_width
        high = prediction + half_width
        inside = (target >= low) & (target <= high)
        interval_score = (
            (high - low)
            + (2.0 / (1.0 - STATIC_INTERVAL_LEVEL))
            * np.maximum(0.0, low - target)
            + (2.0 / (1.0 - STATIC_INTERVAL_LEVEL))
            * np.maximum(0.0, target - high)
        )
        hypo = target < 70.0
        hypo_count = int(np.sum(hypo))
        hypo_misses = int(np.sum(hypo & (low > 70.0)))
        result: dict[str, float | None] = {
            "coverage_80": float(np.mean(inside)),
            "mean_interval_width": float(np.mean(2.0 * half_width)),
            "interval_score_80": float(np.mean(interval_score)),
            "hypo_samples": float(hypo_count),
            "hypo_miss_rate": (
                float(hypo_misses / hypo_count) if hypo_count else None
            ),
        }
        for band_index, (start, end) in enumerate(STATIC_BANDS):
            start_index = (start // STEP_MINUTES) - 1
            end_index = end // STEP_MINUTES
            result[f"coverage_band_{band_index}"] = float(
                np.mean(inside[:, start_index:end_index])
            )
        return result

    @staticmethod
    def _window_local_day(
        readings: Sequence[GlucoseReadingRecord], window: tuple[int, np.ndarray]
    ) -> int:
        reading = readings[window[0]]
        offset_ms = int(reading.utc_offset_minutes or 0) * 60_000
        return (reading.measured_at_ms + offset_ms) // 86_400_000

    @classmethod
    def _static_day_split(
        cls,
        readings: Sequence[GlucoseReadingRecord],
        windows: Sequence[tuple[int, np.ndarray]],
    ) -> tuple[
        list[tuple[int, np.ndarray]],
        list[tuple[int, np.ndarray]],
        list[tuple[int, np.ndarray]],
        list[tuple[int, np.ndarray]],
        dict[str, Any],
    ] | None:
        """Partition complete windows by whole local days before downsampling."""

        grouped: dict[int, list[tuple[int, np.ndarray]]] = {}
        for window in windows:
            grouped.setdefault(cls._window_local_day(readings, window), []).append(window)
        eligible: list[tuple[int, list[tuple[int, np.ndarray]], float]] = []
        for day, day_windows in sorted(grouped.items()):
            day_windows.sort(key=lambda item: readings[item[0]].measured_at_ms)
            times = [readings[item[0]].measured_at_ms for item in day_windows]
            if len(times) < 2:
                continue
            span_hours = (times[-1] - times[0]) / 3_600_000.0
            expected = max(1, int(round((times[-1] - times[0]) / STEP_MS)) + 1)
            occupied = len({value // STEP_MS for value in times})
            density = occupied / expected
            if (
                span_hours >= STATIC_MIN_USABLE_DAY_HOURS
                and density >= STATIC_MIN_DAY_DENSITY
            ):
                eligible.append((day, day_windows, density))
        required_days = (
            STATIC_MIN_TRAIN_DAYS
            + STATIC_TUNING_DAYS
            + STATIC_CALIBRATION_DAYS
            + STATIC_TEST_DAYS
        )
        if len(eligible) < required_days:
            return None
        # Reserve the final seven full days, and use all earlier eligible days for
        # training. This keeps the annotated early interval causal and maximizes
        # generic glucose-dynamics evidence without touching future evaluation.
        test_items = eligible[-STATIC_TEST_DAYS:]
        calibration_items = eligible[
            -(STATIC_TEST_DAYS + STATIC_CALIBRATION_DAYS) : -STATIC_TEST_DAYS
        ]
        tuning_items = eligible[
            -(
                STATIC_TEST_DAYS
                + STATIC_CALIBRATION_DAYS
                + STATIC_TUNING_DAYS
            ) : -(STATIC_TEST_DAYS + STATIC_CALIBRATION_DAYS)
        ]
        train_items = eligible[
            : -(
                STATIC_TEST_DAYS
                + STATIC_CALIBRATION_DAYS
                + STATIC_TUNING_DAYS
            )
        ]
        if len(train_items) < STATIC_MIN_TRAIN_DAYS:
            return None

        def minute_of_day(window: tuple[int, np.ndarray]) -> int:
            reading = readings[window[0]]
            return int(
                (
                    reading.measured_at_ms // 60_000
                    + int(reading.utc_offset_minutes or 0)
                )
                % (24 * 60)
            )

        def flatten(
            items: Sequence[tuple[int, list[tuple[int, np.ndarray]], float]],
            *,
            purge_start: bool,
            purge_end: bool,
        ) -> list[tuple[int, np.ndarray]]:
            result: list[tuple[int, np.ndarray]] = []
            for item_index, (_day, day_windows, _density) in enumerate(items):
                for window in day_windows:
                    minute = minute_of_day(window)
                    if purge_start and item_index == 0 and minute < STATIC_PURGE_MINUTES:
                        continue
                    if (
                        purge_end
                        and item_index == len(items) - 1
                        and minute >= 24 * 60 - STATIC_PURGE_MINUTES
                    ):
                        continue
                    result.append(window)
            return result

        train = flatten(train_items, purge_start=False, purge_end=True)
        tuning = flatten(tuning_items, purge_start=True, purge_end=True)
        calibration = flatten(
            calibration_items, purge_start=True, purge_end=True
        )
        test = flatten(test_items, purge_start=True, purge_end=False)
        if min(len(train), len(tuning), len(calibration), len(test)) < VALIDATION_WINDOWS:
            return None

        def day_hash(items: Sequence[tuple[int, list[tuple[int, np.ndarray]], float]]) -> str:
            canonical = ",".join(str(item[0]) for item in items).encode("ascii")
            return hashlib.sha256(canonical).hexdigest()

        manifest = {
            "train_days": len(train_items),
            "tuning_days": len(tuning_items),
            "calibration_days": len(calibration_items),
            "test_days": len(test_items),
            "purge_minutes": STATIC_PURGE_MINUTES,
            "train_windows": len(train),
            "tuning_windows": len(tuning),
            "calibration_windows": len(calibration),
            "test_windows": len(test),
            "train_days_sha256": day_hash(train_items),
            "tuning_days_sha256": day_hash(tuning_items),
            "calibration_days_sha256": day_hash(calibration_items),
            "test_days_sha256": day_hash(test_items),
            "minimum_day_hours": STATIC_MIN_USABLE_DAY_HOURS,
            "minimum_day_density": STATIC_MIN_DAY_DENSITY,
        }
        return train, tuning, calibration, test, manifest

    @classmethod
    def _independent_windows(
        cls,
        readings: Sequence[GlucoseReadingRecord],
        windows: Sequence[tuple[int, np.ndarray]],
        *,
        spacing_minutes: int = HORIZON_MINUTES,
    ) -> list[tuple[int, np.ndarray]]:
        result: list[tuple[int, np.ndarray]] = []
        last_by_day: dict[int, int] = {}
        for window in windows:
            day = cls._window_local_day(readings, window)
            anchor_ms = readings[window[0]].measured_at_ms
            previous = last_by_day.get(day)
            if previous is None or anchor_ms - previous >= spacing_minutes * 60_000:
                result.append(window)
                last_by_day[day] = anchor_ms
        return result

    @staticmethod
    def _frozen_calibration(
        prediction: np.ndarray, target: np.ndarray
    ) -> tuple[np.ndarray, np.ndarray]:
        residual = target - prediction
        bias = np.median(residual, axis=0)
        centered = residual - bias.reshape(1, -1)
        try:
            half_width = np.quantile(
                np.abs(centered), STATIC_INTERVAL_LEVEL, axis=0, method="higher"
            )
        except TypeError:  # NumPy < 1.22 compatibility.
            half_width = np.quantile(
                np.abs(centered), STATIC_INTERVAL_LEVEL, axis=0, interpolation="higher"
            )
        half_width = np.maximum.accumulate(np.maximum(7.0, half_width))
        sigma = half_width / 1.2816
        return np.asarray(bias, dtype=np.float64), np.asarray(sigma, dtype=np.float64)

    @classmethod
    def _equal_day_results(
        cls,
        readings: Sequence[GlucoseReadingRecord],
        windows: Sequence[tuple[int, np.ndarray]],
        candidate: np.ndarray,
        reference: np.ndarray,
        pinned: np.ndarray,
        target: np.ndarray,
    ) -> list[dict[str, float]]:
        grouped: dict[int, list[int]] = {}
        for index, window in enumerate(windows):
            grouped.setdefault(cls._window_local_day(readings, window), []).append(index)
        return [
            {
                "candidate_mae": float(np.mean(np.abs(candidate[indexes] - target[indexes]))),
                "reference_mae": float(np.mean(np.abs(reference[indexes] - target[indexes]))),
                "pinned_mae": float(np.mean(np.abs(pinned[indexes] - target[indexes]))),
            }
            for _day, indexes in sorted(grouped.items())
        ]

    @staticmethod
    def static_promotion_gates(
        candidate_metrics: dict[str, float | None],
        reference_metrics: dict[str, float | None],
        pinned_metrics: dict[str, float | None],
        day_results: Sequence[dict[str, float]],
        *,
        test_day_count: int,
        finite: bool = True,
    ) -> dict[str, bool | float | int]:
        """Independent-day gate; overlapping 5-minute averages cannot promote."""

        result: dict[str, bool | float | int] = {
            "finite": bool(finite),
            "test_days": int(test_day_count),
            "accepted": False,
        }
        required = (
            "mae",
            "rmse",
            "mae_30",
            "mae_60",
            "mae_120",
            "coverage_80",
            "interval_score_80",
            "coverage_band_0",
            "coverage_band_1",
            "coverage_band_2",
            "coverage_band_3",
        )
        if (
            not finite
            or test_day_count < STATIC_TEST_DAYS
            or len(day_results) != test_day_count
            or any(
                metrics.get(key) is None
                or not math.isfinite(float(metrics[key]))
                or float(metrics[key]) < 0.0
                for metrics in (candidate_metrics, reference_metrics, pinned_metrics)
                for key in required
            )
        ):
            return result

        candidate_days = np.asarray(
            [item["candidate_mae"] for item in day_results], dtype=np.float64
        )
        reference_days = np.asarray(
            [item["reference_mae"] for item in day_results], dtype=np.float64
        )
        pinned_days = np.asarray(
            [item["pinned_mae"] for item in day_results], dtype=np.float64
        )
        if not all(np.isfinite(item).all() and np.all(item > 0) for item in (candidate_days, reference_days, pinned_days)):
            return result
        candidate_equal = float(np.mean(candidate_days))
        reference_equal = float(np.mean(reference_days))
        pinned_equal = float(np.mean(pinned_days))
        reference_improvement = 1.0 - candidate_equal / reference_equal
        pinned_improvement = 1.0 - candidate_equal / pinned_equal
        day_improvements = 1.0 - candidate_days / reference_days
        winning_days = int(np.sum(candidate_days < reference_days))
        required_wins = (
            test_day_count - 1
            if test_day_count < 8
            else int(math.ceil(0.70 * test_day_count))
        )
        minimum_reference_improvement = 0.08 if test_day_count < 8 else 0.05
        no_bad_day = bool(np.all(candidate_days <= reference_days * 1.02))
        horizons_safe = all(
            float(candidate_metrics[key])
            <= max(float(reference_metrics[key]) * 1.02, float(reference_metrics[key]) + 0.5)
            and float(candidate_metrics[key])
            <= max(float(pinned_metrics[key]) * 1.02, float(pinned_metrics[key]) + 0.5)
            for key in ("mae_30", "mae_60", "mae_120")
        )
        coverage_safe = (
            0.75 <= float(candidate_metrics["coverage_80"]) <= 0.90
            and all(
                float(candidate_metrics[f"coverage_band_{index}"]) >= 0.70
                for index in range(len(STATIC_BANDS))
            )
        )
        result.update(
            {
                "candidate_equal_day_mae": candidate_equal,
                "reference_equal_day_mae": reference_equal,
                "pinned_equal_day_mae": pinned_equal,
                "reference_equal_day_improvement": reference_improvement,
                "pinned_equal_day_improvement": pinned_improvement,
                "winning_days": winning_days,
                "required_winning_days": required_wins,
                "median_day_improvement": float(np.median(day_improvements)),
                "no_day_regression_over_2pct": no_bad_day,
                "horizons_safe": bool(horizons_safe),
                "coverage_safe": bool(coverage_safe),
                "interval_score_safe": float(candidate_metrics["interval_score_80"])
                <= float(reference_metrics["interval_score_80"]) * 0.98,
                "rmse_safe": float(candidate_metrics["rmse"])
                <= float(reference_metrics["rmse"]) * 0.98,
                "anchor_mae_safe": float(candidate_metrics["mae"])
                <= float(reference_metrics["mae"]) * 0.97,
            }
        )
        result["accepted"] = bool(
            reference_improvement >= minimum_reference_improvement
            and pinned_improvement >= 0.03
            and winning_days >= required_wins
            and float(result["median_day_improvement"]) > 0.0
            and no_bad_day
            and horizons_safe
            and coverage_safe
            and bool(result["interval_score_safe"])
            and bool(result["rmse_safe"])
            and bool(result["anchor_mae_safe"])
        )
        return result

    @staticmethod
    def _event_channel_gate_result(
        full_prediction: np.ndarray,
        ablated_prediction: np.ndarray,
        target: np.ndarray,
        *,
        response_samples: int,
        validation_events: int,
    ) -> dict[str, Any]:
        validation_windows = int(target.shape[0]) if target.ndim == 2 else 0
        result: dict[str, Any] = {
            "validated": False,
            "response_samples": int(response_samples),
            "validation_events": int(validation_events),
            "validation_windows": validation_windows,
            "full_mae": None,
            "ablated_mae": None,
            "improvement_mg_dl": None,
            "relative_improvement": None,
        }
        if (
            response_samples < MINIMUM_CONTEXTUAL_EVENT_SAMPLES
            or validation_events < MINIMUM_CONTEXTUAL_VALIDATION_EVENTS
            or validation_windows < MINIMUM_CONTEXTUAL_VALIDATION_WINDOWS
            or full_prediction.shape != target.shape
            or ablated_prediction.shape != target.shape
            or target.shape[1:] != (HORIZON_STEPS,)
            or not np.isfinite(full_prediction).all()
            or not np.isfinite(ablated_prediction).all()
            or not np.isfinite(target).all()
        ):
            return result
        full_absolute = np.abs(full_prediction - target)
        ablated_absolute = np.abs(ablated_prediction - target)
        full_mae = float(np.mean(full_absolute))
        ablated_mae = float(np.mean(ablated_absolute))
        improvement = ablated_mae - full_mae
        relative = improvement / max(1.0, ablated_mae)
        required_improvement = max(0.25, 0.01 * ablated_mae)
        horizon_safe = all(
            float(np.mean(full_absolute[:, index]))
            <= float(np.mean(ablated_absolute[:, index])) * 1.10 + 0.5
            for index in (5, 11, 23)
        )
        result.update(
            {
                "validated": bool(
                    improvement >= required_improvement and horizon_safe
                ),
                "full_mae": full_mae,
                "ablated_mae": ablated_mae,
                "improvement_mg_dl": improvement,
                "relative_improvement": relative,
            }
        )
        return result

    def _validate_event_channels(
        self,
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
        validation_windows: Sequence[tuple[int, np.ndarray]],
        parameters: dict[str, Any],
        full_prediction: np.ndarray,
        target: np.ndarray,
        *,
        training_cutoff_ms: int,
    ) -> dict[str, dict[str, Any]]:
        results: dict[str, dict[str, Any]] = {}
        evidence = parameters.get("evidence_counts", {})
        for kind in ("meal", "rapid", "long"):
            response_samples = int(evidence.get(kind, 0) or 0)
            affected_rows: list[int] = []
            validation_event_ids: set[str] = set()
            for row_index, (anchor_index, _target) in enumerate(validation_windows):
                anchor_ms = readings[anchor_index].measured_at_ms
                row_event_ids: set[str] = set()
                for event in events:
                    if (
                        event.kind != kind
                        or event.occurred_at_ms <= training_cutoff_ms
                        or event.occurred_at_ms > anchor_ms
                        or _event_known_at(event) > anchor_ms
                    ):
                        continue
                    _peak, duration, _confidence = _profile_for_event(
                        event, parameters
                    )
                    if anchor_ms < event.occurred_at_ms + duration * 60_000:
                        row_event_ids.add(event.event_id)
                if row_event_ids:
                    affected_rows.append(row_index)
                    validation_event_ids.update(row_event_ids)
            if (
                response_samples < MINIMUM_CONTEXTUAL_EVENT_SAMPLES
                or len(validation_event_ids) < MINIMUM_CONTEXTUAL_VALIDATION_EVENTS
                or len(affected_rows) < MINIMUM_CONTEXTUAL_VALIDATION_WINDOWS
            ):
                results[kind] = self._event_channel_gate_result(
                    np.empty((0, HORIZON_STEPS)),
                    np.empty((0, HORIZON_STEPS)),
                    np.empty((0, HORIZON_STEPS)),
                    response_samples=response_samples,
                    validation_events=len(validation_event_ids),
                )
                continue
            ablated_events = [event for event in events if event.kind != kind]
            ablated_x, ablated_baseline, _ablated_target = self._dataset_for_parameters(
                readings, ablated_events, validation_windows, parameters
            )
            ablated_prediction = np.clip(
                ablated_baseline
                + _network_predict_batch(ablated_x, parameters),
                20.0,
                600.0,
            )
            selected = np.asarray(affected_rows, dtype=int)
            results[kind] = self._event_channel_gate_result(
                full_prediction[selected],
                ablated_prediction[selected],
                target[selected],
                response_samples=response_samples,
                validation_events=len(validation_event_ids),
            )
        return results

    def train_static_model(
        self,
        session: Session,
        data_cutoff_ms: int | None = None,
        candidate_version: str | None = None,
    ) -> ForecastTrainResponse:
        """Manually build one frozen candidate from an immutable causal snapshot.

        This method is intentionally reachable only from the local admin CLI. It
        never activates a candidate: a model that passes the independent-day gate
        is staged with ``status=candidate`` and requires an explicit activation.
        """

        with self._training_lock:
            return self._train_static_model_unlocked(
                session,
                data_cutoff_ms=data_cutoff_ms,
                candidate_version=candidate_version,
            )

    def _train_static_model_unlocked(
        self,
        session: Session,
        *,
        data_cutoff_ms: int | None,
        candidate_version: str | None,
    ) -> ForecastTrainResponse:
        champion = self._champion(session)
        source_revision_before = self._source_revision(session)
        latest_available = session.scalar(
            select(func.max(GlucoseReadingRecord.measured_at_ms))
        )
        if latest_available is None:
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=champion.version,
                reason="No glucose readings are available for manual training",
                sample_count=0,
                metrics={},
            )
        requested_cutoff = (
            int(data_cutoff_ms) if data_cutoff_ms is not None else int(latest_available)
        )
        if requested_cutoff <= 0:
            raise ValueError("data cutoff must be a positive Unix timestamp in milliseconds")
        readings = self._load_readings(
            session,
            through_ms=min(requested_cutoff, int(latest_available)),
            limit=60_000,
        )
        if not readings:
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=champion.version,
                reason="No glucose readings exist at or before the requested cutoff",
                sample_count=0,
                metrics={},
            )
        cutoff_ms = int(readings[-1].measured_at_ms)
        all_windows = self._training_windows(readings, max_windows=None)
        split_result = self._static_day_split(readings, all_windows)
        if split_result is None:
            day_count = len(
                {
                    self._window_local_day(readings, window)
                    for window in all_windows
                }
            )
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=champion.version,
                reason=(
                    "Need at least 15 dense local-day blocks: 8+ training, 1 tuning, "
                    "2 frozen calibration, and 4 untouched test days"
                ),
                sample_count=len(all_windows),
                metrics={"eligible_day_candidates": day_count},
            )
        train_windows, tuning_windows, calibration_windows, test_windows, split = (
            split_result
        )
        tuning_independent = self._independent_windows(readings, tuning_windows)
        calibration_independent = self._independent_windows(
            readings, calibration_windows
        )
        test_independent = self._independent_windows(readings, test_windows)
        split["tuning_independent_anchors"] = len(tuning_independent)
        split["calibration_independent_anchors"] = len(calibration_independent)
        split["test_independent_anchors"] = len(test_independent)
        if (
            len(calibration_independent) < VALIDATION_WINDOWS
            or len(test_independent) < 8 * STATIC_TEST_DAYS
        ):
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=champion.version,
                reason="Not enough independent 120-minute anchors after chronological purges",
                sample_count=len(train_windows),
                metrics={
                    "calibration_independent_anchors": len(calibration_independent),
                    "test_independent_anchors": len(test_independent),
                },
            )

        events = self._load_events(
            session, through_ms=cutoff_ms, known_through_ms=cutoff_ms
        )
        # Analyze label support, but do not fit personal event curves from the 27
        # heavily overlapping records. The runtime event reference keeps bounded
        # population priors, while the network receives glucose/context only.
        evidence_parameters = self._personalized_parameters(readings, events)
        parameters = _default_parameters()
        parameters["evidence_counts"] = dict(
            evidence_parameters.get("evidence_counts", {})
        )
        parameters["kind"] = "personalized_static_generic_residual"
        parameters["feature_schema"] = STATIC_FEATURE_SCHEMA
        parameters["architecture"] = STATIC_PERSONAL_ARCHITECTURE
        parameters["network_disabled_event_channels"] = [
            "meal",
            "rapid",
            "long",
        ]
        parameters["event_channel_validation"] = {
            kind: {
                "validated": False,
                "reason": "not_identifiable_in_snapshot",
                "response_samples": int(
                    parameters["evidence_counts"].get(kind, 0) or 0
                ),
            }
            for kind in ("meal", "rapid", "long")
        }

        x_train, reference_train, target_train = self._dataset_for_parameters(
            readings, events, train_windows, parameters
        )
        parameters["network"] = _fit_network(
            x_train, target_train - reference_train
        )

        def raw_static(
            windows: Sequence[tuple[int, np.ndarray]],
        ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
            features, reference, target = self._dataset_for_parameters(
                readings, events, windows, parameters
            )
            residual = _network_predict_batch(features, parameters)
            raw = np.clip(reference + residual, 20.0, 600.0)
            return features, reference, target, raw

        _tune_x, tune_reference, tune_target, tune_raw = raw_static(
            tuning_independent
        )
        grid = (0.0, 0.25, 0.50, 0.75, 1.0)
        band_weights: list[float] = []
        for start, end in STATIC_BANDS:
            start_index = (start // STEP_MINUTES) - 1
            end_index = end // STEP_MINUTES
            best_weight = 0.0
            best_loss = math.inf
            for weight in grid:
                prediction = tune_reference[:, start_index:end_index] + weight * (
                    tune_raw[:, start_index:end_index]
                    - tune_reference[:, start_index:end_index]
                )
                loss = float(
                    np.mean(
                        np.abs(prediction - tune_target[:, start_index:end_index])
                    )
                )
                if loss < best_loss - 1e-9:
                    best_loss = loss
                    best_weight = weight
            band_weights.append(best_weight)
        blend = np.asarray(
            [
                weight
                for weight, (start, end) in zip(band_weights, STATIC_BANDS)
                for _ in range(((end - start) // STEP_MINUTES) + 1)
            ],
            dtype=np.float64,
        )
        parameters["persistence_blend_weights"] = blend.tolist()

        _cal_x, cal_reference, cal_target, cal_raw = raw_static(
            calibration_independent
        )
        cal_shrunk = cal_reference + blend.reshape(1, -1) * (
            cal_raw - cal_reference
        )
        bias, sigma = self._frozen_calibration(cal_shrunk, cal_target)
        reference_bias, reference_sigma = self._frozen_calibration(
            cal_reference, cal_target
        )
        parameters["residual_sigma"] = sigma.tolist()
        parameters["frozen_calibration"] = {
            "method": "frozen-day-block-conformal-v1",
            "interval_level": STATIC_INTERVAL_LEVEL,
            "sample_count": len(calibration_independent),
            "bias_mg_dl": bias.tolist(),
            "sigma_mg_dl": sigma.tolist(),
        }

        _test_x, test_reference, test_target, test_raw = raw_static(test_independent)
        candidate_prediction = np.clip(
            test_reference
            + blend.reshape(1, -1) * (test_raw - test_reference)
            + bias.reshape(1, -1),
            20.0,
            600.0,
        )
        reference_prediction = np.clip(
            test_reference + reference_bias.reshape(1, -1), 20.0, 600.0
        )

        champion_parameters = (
            _baseline_parameters()
            if champion.version == BASELINE_VERSION
            else (_json_dict(champion.parameters_json) or _default_parameters())
        )
        champion_x, champion_base, champion_target = self._dataset_for_parameters(
            readings, events, test_independent, champion_parameters
        )
        pinned_prediction = np.clip(
            champion_base
            + _network_predict_batch(champion_x, champion_parameters),
            20.0,
            600.0,
        )
        pinned_sigma = np.asarray(
            champion_parameters.get(
                "residual_sigma", _default_parameters()["residual_sigma"]
            ),
            dtype=np.float64,
        )
        if champion.architecture == STATIC_PERSONAL_ARCHITECTURE:
            champion_reference = self._reference_for_windows(
                readings, events, test_independent, champion_parameters
            )
            pinned_prediction, pinned_sigma = _apply_static_predictor(
                pinned_prediction,
                champion_reference,
                pinned_sigma,
                champion_parameters,
            )
        elif champion.version == BASELINE_VERSION:
            pinned_sigma = pinned_sigma * 1.25
        if pinned_sigma.shape != (HORIZON_STEPS,) or not np.isfinite(pinned_sigma).all():
            pinned_sigma = np.asarray(
                _default_parameters()["residual_sigma"], dtype=np.float64
            )

        candidate_metrics: dict[str, float | None] = {
            **self._metrics(candidate_prediction, test_target),
            **self._interval_metrics(candidate_prediction, test_target, sigma),
        }
        reference_metrics: dict[str, float | None] = {
            **self._metrics(reference_prediction, test_target),
            **self._interval_metrics(
                reference_prediction, test_target, reference_sigma
            ),
        }
        pinned_metrics: dict[str, float | None] = {
            **self._metrics(pinned_prediction, champion_target),
            **self._interval_metrics(
                pinned_prediction, champion_target, pinned_sigma
            ),
        }
        day_results = self._equal_day_results(
            readings,
            test_independent,
            candidate_prediction,
            reference_prediction,
            pinned_prediction,
            test_target,
        )
        gates = self.static_promotion_gates(
            candidate_metrics,
            reference_metrics,
            pinned_metrics,
            day_results,
            test_day_count=len(day_results),
            finite=bool(
                np.isfinite(candidate_prediction).all()
                and np.isfinite(sigma).all()
            ),
        )
        accepted = bool(gates["accepted"])

        # Overlapping windows are retained only as a transparent secondary
        # diagnostic; they never participate in the gate or confidence.
        _diag_x, diag_reference, diag_target, diag_raw = raw_static(test_windows)
        diag_prediction = np.clip(
            diag_reference
            + blend.reshape(1, -1) * (diag_raw - diag_reference)
            + bias.reshape(1, -1),
            20.0,
            600.0,
        )
        diagnostic_metrics = self._metrics(diag_prediction, diag_target)

        test_days = len(day_results)
        confidence_cap = 0.35 if test_days < 8 else (0.50 if test_days < 14 else 0.65)
        reference_skill = _clamp(
            1.0
            - float(candidate_metrics["mae"] or 0.0)
            / max(1e-6, float(reference_metrics["mae"] or 0.0)),
            0.0,
            0.25,
        ) / 0.25
        calibration_skill = _clamp(
            1.0 - abs(float(candidate_metrics["coverage_80"] or 0.0) - 0.8) / 0.2,
            0.0,
            1.0,
        )
        winning_fraction = float(gates.get("winning_days", 0)) / max(1, test_days)
        evidence_fraction = _clamp(len(test_independent) / max(1, test_days * 12), 0.0, 1.0)
        reliability_overall = confidence_cap * (
            0.45 * reference_skill
            + 0.25 * calibration_skill
            + 0.20 * winning_fraction
            + 0.10 * evidence_fraction
        )
        reliability_overall = _clamp(reliability_overall, 0.05, confidence_cap)
        by_horizon = [
            _clamp(
                reliability_overall
                * _clamp(
                    float(reference_error)
                    / max(float(candidate_error), float(reference_error), 1e-6),
                    0.65,
                    1.10,
                ),
                0.0,
                confidence_cap,
            )
            for candidate_error, reference_error in zip(
                np.mean(np.abs(candidate_prediction - test_target), axis=0),
                np.mean(np.abs(reference_prediction - test_target), axis=0),
            )
        ]

        now = _now_ms()
        if candidate_version is None:
            version = f"static-{now}-{uuid4().hex[:8]}"
        else:
            version = str(candidate_version).strip()
            if (
                not version
                or len(version) > 96
                or any(
                    character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
                    for character in version
                )
            ):
                raise ValueError(
                    "candidate version must contain only letters, digits, '.', '_' or '-'"
                )
        if session.get(ForecastModelRecord, version) is not None:
            raise ValueError(f"forecast model version already exists: {version}")

        evaluation: dict[str, float | int | None] = {
            "accepted": int(accepted),
            "candidate_equal_day_mae": _finite(
                gates.get("candidate_equal_day_mae"),
                float(candidate_metrics["mae"] or 0.0),
            ),
            "reference_equal_day_mae": _finite(
                gates.get("reference_equal_day_mae"),
                float(reference_metrics["mae"] or 0.0),
            ),
            "pinned_equal_day_mae": _finite(
                gates.get("pinned_equal_day_mae"),
                float(pinned_metrics["mae"] or 0.0),
            ),
            "candidate_anchor_mae": float(candidate_metrics["mae"] or 0.0),
            "reference_anchor_mae": float(reference_metrics["mae"] or 0.0),
            "pinned_anchor_mae": float(pinned_metrics["mae"] or 0.0),
            "candidate_rmse": float(candidate_metrics["rmse"] or 0.0),
            "reference_rmse": float(reference_metrics["rmse"] or 0.0),
            "candidate_mae_30": float(candidate_metrics["mae_30"] or 0.0),
            "candidate_mae_60": float(candidate_metrics["mae_60"] or 0.0),
            "candidate_mae_120": float(candidate_metrics["mae_120"] or 0.0),
            "reference_mae_30": float(reference_metrics["mae_30"] or 0.0),
            "reference_mae_60": float(reference_metrics["mae_60"] or 0.0),
            "reference_mae_120": float(reference_metrics["mae_120"] or 0.0),
            "candidate_coverage_80": float(candidate_metrics["coverage_80"] or 0.0),
            "candidate_interval_score_80": float(
                candidate_metrics["interval_score_80"] or 0.0
            ),
            "reference_interval_score_80": float(
                reference_metrics["interval_score_80"] or 0.0
            ),
            "test_days": test_days,
            "test_independent_anchors": len(test_independent),
            "calibration_independent_anchors": len(calibration_independent),
            "winning_days": int(gates.get("winning_days", 0)),
            "diagnostic_overlapping_mae": float(diagnostic_metrics["mae"]),
            "reliability": reliability_overall,
        }
        for key, value in gates.items():
            if key in evaluation or key == "finite":
                continue
            if isinstance(value, bool):
                evaluation[f"gate_{key}"] = int(value)
            elif isinstance(value, (int, float)) and math.isfinite(float(value)):
                evaluation[f"gate_{key}"] = value

        network = parameters["network"]
        parameter_count = int(
            sum(
                np.asarray(network[name], dtype=np.float64).size
                for name in ("w1", "b1", "w2", "b2")
            )
        )
        max_received_at = max(int(row.received_at_ms) for row in readings)
        event_revision = source_revision_before[3]
        parameters["artifact"] = {
            "artifact_version": 3,
            "engine_version": FORECAST_ENGINE_VERSION,
            "architecture": STATIC_PERSONAL_ARCHITECTURE,
            "feature_schema": STATIC_FEATURE_SCHEMA,
            "network_kind": STATIC_NETWORK_KIND,
            "training_mode": STATIC_TRAINING_MODE,
            "promotion_gate_version": STATIC_PROMOTION_GATE_VERSION,
            "interval_level": STATIC_INTERVAL_LEVEL,
            "seed": STATIC_TRAINING_SEED,
            "feature_count": STATIC_FEATURE_COUNT,
            "parameter_count": parameter_count,
            "model_version": version,
            "trained_at_ms": now,
            "data_cutoff_ms": cutoff_ms,
            "sample_count": len(train_windows),
            "dataset_sha256": _dataset_fingerprint(readings, events),
            "snapshot": {
                "last_reading_at_ms": cutoff_ms,
                "max_received_at_ms": max_received_at,
                "event_revision": event_revision,
                "active_event_count": len(events),
            },
            "split": split,
            "band_definitions": [
                {
                    "start_minutes": start,
                    "end_minutes": end,
                    "weight": weight,
                }
                for weight, (start, end) in zip(band_weights, STATIC_BANDS)
            ],
            "event_channels": {
                "meal": "population_prior_not_identifiable",
                "rapid": "population_prior_not_identifiable",
                "long": "population_prior_not_identifiable",
            },
            "reliability": {
                "overall": reliability_overall,
                "by_horizon": by_horizon,
                "clinical_validation": False,
                "test_day_cap": confidence_cap,
            },
            "evaluation": evaluation,
            "accepted": accepted,
        }
        parameters["artifact"]["content_sha256"] = _artifact_content_hash(
            parameters
        )
        reason = (
            "Passed the independent-day static promotion gate; explicit activation is required"
            if accepted
            else "Rejected: independent-day evidence did not beat the frozen persistence and pinned comparators"
        )
        candidate = ForecastModelRecord(
            version=version,
            status="candidate" if accepted else "rejected",
            architecture=STATIC_PERSONAL_ARCHITECTURE,
            created_at_ms=now,
            trained_at_ms=now,
            promoted_at_ms=None,
            training_cutoff_ms=cutoff_ms,
            sample_count=len(train_windows),
            parameters_json=json.dumps(
                parameters, separators=(",", ":"), allow_nan=False
            ),
            metrics_json=json.dumps(
                evaluation, separators=(",", ":"), allow_nan=False
            ),
            decision_reason=reason,
        )
        # SQLite's legacy transaction mode does not BEGIN for SELECT. Take a
        # short write reservation only for the final revision check + insert.
        # Monotone received/sync revisions make any interleaved source mutation
        # observable without blocking mobile ingestion during model fitting.
        session.rollback()
        session.execute(text("BEGIN IMMEDIATE"))
        source_revision_after = self._source_revision(session)
        if source_revision_after != source_revision_before:
            session.rollback()
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=champion.version,
                reason=(
                    "Source glucose/intake data changed during training; retry from a fresh snapshot"
                ),
                sample_count=len(train_windows),
                metrics={"source_revision_changed": 1},
            )
        session.add(candidate)
        session.commit()
        return ForecastTrainResponse(
            status="accepted" if accepted else "rejected",
            promoted=False,
            model_version=version,
            reason=reason,
            sample_count=len(train_windows),
            metrics=evaluation,
        )

    # Compatibility for local Python callers. HTTP ingestion never calls this
    # method, and maybe_train() remains a no-op.
    def train(self, session: Session) -> ForecastTrainResponse:
        return self.train_static_model(session)

    def _legacy_train(self, session: Session) -> ForecastTrainResponse:
        del session
        raise RuntimeError("legacy automatic-promotion training is disabled")

    @staticmethod
    def candidate_is_promotable(
        candidate_metrics: dict[str, float | None],
        champion_metrics: dict[str, float | None],
        *,
        finite: bool = True,
    ) -> bool:
        """Conservative champion gate, kept pure so both decisions are testable."""

        if not finite:
            return False
        required = (
            "mae",
            "rmse",
            "mae_30",
            "mae_60",
            "mae_120",
            "coverage_80",
            "mean_interval_width",
        )
        if any(
            key not in candidate_metrics
            or key not in champion_metrics
            or candidate_metrics[key] is None
            or champion_metrics[key] is None
            or not math.isfinite(float(candidate_metrics[key]))
            or not math.isfinite(float(champion_metrics[key]))
            or float(champion_metrics[key]) < 0
            or float(candidate_metrics[key]) < 0
            for key in required
        ):
            return False
        improves = (
            float(candidate_metrics["mae"]) <= float(champion_metrics["mae"]) * 0.98
            and float(candidate_metrics["rmse"])
            <= float(champion_metrics["rmse"]) * 0.98
        )
        horizons_safe = all(
            float(candidate_metrics[key]) <= float(champion_metrics[key]) * 1.12
            for key in ("mae_30", "mae_60", "mae_120")
        )
        calibrated = (
            float(candidate_metrics["coverage_80"]) >= 0.70
            and float(candidate_metrics["coverage_80"])
            >= float(champion_metrics["coverage_80"]) - 0.08
            and float(candidate_metrics["mean_interval_width"])
            <= float(champion_metrics["mean_interval_width"]) * 1.20
            and float(candidate_metrics["mean_interval_width"]) <= 140.0
        )
        candidate_hypo = candidate_metrics.get("hypo_miss_rate")
        champion_hypo = champion_metrics.get("hypo_miss_rate")
        hypo_safe = True
        if candidate_hypo is not None:
            hypo_safe = float(candidate_hypo) <= 0.35
            if champion_hypo is not None:
                hypo_safe = hypo_safe and float(candidate_hypo) <= float(champion_hypo) + 0.05
        return bool(improves and horizons_safe and calibrated and hypo_safe)

    def status(self, session: Session, now_ms: int | None = None) -> ForecastStatusResponse:
        now = now_ms if now_ms is not None else _now_ms()
        champion = self._champion(session)
        reading_count, first_at, last_at = session.execute(
            select(
                func.count(GlucoseReadingRecord.reading_id),
                func.min(GlucoseReadingRecord.measured_at_ms),
                func.max(GlucoseReadingRecord.measured_at_ms),
            )
        ).one()
        meal_count = session.scalar(
            select(func.count(IntakeEventRecord.id)).where(
                IntakeEventRecord.deleted_at_ms.is_(None),
                IntakeEventRecord.carbs_g.is_not(None),
            )
        ) or 0
        rapid_count = session.scalar(
            select(func.count(IntakeEventRecord.id)).where(
                IntakeEventRecord.deleted_at_ms.is_(None),
                IntakeEventRecord.insulin_type == "rapid",
            )
        ) or 0
        long_count = session.scalar(
            select(func.count(IntakeEventRecord.id)).where(
                IntakeEventRecord.deleted_at_ms.is_(None),
                IntakeEventRecord.insulin_type == "long",
            )
        ) or 0
        latest_attempt = session.scalar(
            select(ForecastModelRecord)
            .where(
                ForecastModelRecord.trained_at_ms.is_not(None),
                ForecastModelRecord.architecture == STATIC_PERSONAL_ARCHITECTURE,
            )
            .order_by(ForecastModelRecord.trained_at_ms.desc())
        )
        if champion.version != BASELINE_VERSION:
            last_trained = champion.trained_at_ms
            trained_samples = champion.sample_count
        else:
            last_trained = (
                latest_attempt.trained_at_ms if latest_attempt is not None else None
            )
            trained_samples = latest_attempt.sample_count if latest_attempt is not None else 0
        score_filter = ForecastScoreRecord.model_version == champion.version
        scored_points = session.scalar(
            select(func.count(ForecastScoreRecord.run_id)).where(score_filter)
        ) or 0

        def horizon_mae(step: int) -> float | None:
            value = session.scalar(
                select(func.avg(ForecastScoreRecord.absolute_error_mg_dl)).where(
                    score_filter,
                    ForecastScoreRecord.step_minutes == step,
                )
            )
            return round(float(value), 3) if value is not None else None

        def window_mae(days: int) -> float | None:
            value = session.scalar(
                select(func.avg(ForecastScoreRecord.absolute_error_mg_dl)).where(
                    score_filter,
                    ForecastScoreRecord.scored_at_ms >= now - days * 86_400_000
                )
            )
            return round(float(value), 3) if value is not None else None

        coverage = session.scalar(
            select(func.avg(ForecastScoreRecord.inside_interval)).where(score_filter)
        )
        occupied_bins = self._occupied_bin_count(session, through_ms=last_at)
        days = occupied_bins / (24 * 60 / STEP_MINUTES)
        span_bins = (
            int((last_at - first_at) // STEP_MS) + 1
            if first_at is not None and last_at is not None
            else 0
        )
        coverage_density = _clamp(occupied_bins / span_bins if span_bins else 0.0, 0, 1)
        if not reading_count:
            status_value = "no_data"
        elif last_at is not None and now - last_at > STALE_AFTER_MS:
            status_value = "stale"
        else:
            recent = self._load_readings(
                session,
                through_ms=last_at,
                from_ms=last_at - (HISTORY_STEPS - 1) * STEP_MS - MATCH_TOLERANCE_MS,
                limit=20_000,
            )
            quality_status, _confidence, _coverage = self._quality_status(recent, last_at)
            if quality_status == "low_confidence":
                status_value = "low_confidence"
            elif champion.version != BASELINE_VERSION:
                status_value = "ready"
            else:
                status_value = "cold_start"
        training_state = (
            "frozen" if champion.version != BASELINE_VERSION else "manual_only"
        )
        comparison_record = (
            champion if champion.version != BASELINE_VERSION else latest_attempt
        )
        comparison_parameters = (
            _json_dict(comparison_record.parameters_json)
            if comparison_record is not None
            else {}
        )
        snapshot = comparison_parameters.get("artifact", {}).get("snapshot", {})
        current_max_received = session.scalar(
            select(func.max(GlucoseReadingRecord.received_at_ms))
        )
        current_event_revision = int(
            session.scalar(select(func.max(SyncChangeRecord.id))) or 0
        )
        data_changed_since_training = bool(
            comparison_record is not None
            and isinstance(snapshot, dict)
            and (
                int(_finite(snapshot.get("last_reading_at_ms"), -1))
                != int(last_at or -1)
                or int(_finite(snapshot.get("max_received_at_ms"), -1))
                != int(current_max_received or -1)
                or int(_finite(snapshot.get("event_revision"), -1))
                != current_event_revision
            )
        )
        parameters = (
            _baseline_parameters()
            if champion.version == BASELINE_VERSION
            else (_json_dict(champion.parameters_json) or _default_parameters())
        )
        profiles = parameters.get("profiles", {})
        evidence = parameters.get("evidence_counts", {})
        return ForecastStatusResponse(
            status=status_value,
            server_instance_id=self._server_instance_id(session),
            model_version=champion.version,
            training=ForecastTrainingStatus(
                state=training_state,
                mode=STATIC_TRAINING_MODE,
                automatic_enabled=False,
                data_changed_since_training=data_changed_since_training,
                last_trained_at_ms=last_trained,
                next_eligible_at_ms=None,
                sample_count=int(trained_samples),
                minimum_samples=MINIMUM_TRAIN_WINDOWS,
            ),
            data=ForecastDataStatus(
                reading_count=int(reading_count),
                days_covered=round(days, 3),
                coverage_density=round(coverage_density, 4),
                confirmed_meals=int(meal_count),
                rapid_events=int(rapid_count),
                long_events=int(long_count),
                last_reading_at_ms=last_at,
            ),
            accuracy=ForecastAccuracyStatus(
                scored_points=int(scored_points),
                mae_30_mg_dl=horizon_mae(30),
                mae_60_mg_dl=horizon_mae(60),
                mae_120_mg_dl=horizon_mae(120),
                mae_7d_mg_dl=window_mae(7),
                mae_30d_mg_dl=window_mae(30),
                coverage_80=round(float(coverage), 4) if coverage is not None else None,
            ),
            capabilities=ForecastCapabilityStatus(
                personal_model_active=champion.version != BASELINE_VERSION,
                ready_for_display=status_value == "ready",
                occupied_5m_bins=occupied_bins,
                training_days_required=STATIC_REQUIRED_DAYS,
                ready_days_required=STATIC_REQUIRED_DAYS,
                meal_response_samples=int(evidence.get("meal", 0) or 0),
                rapid_response_samples=int(evidence.get("rapid", 0) or 0),
                long_response_samples=int(evidence.get("long", 0) or 0),
                meal_profile_confidence=_clamp(
                    _finite(profiles.get("meal_profile_confidence"), 0.18), 0, 1
                ),
                rapid_profile_confidence=_clamp(
                    _finite(profiles.get("rapid_profile_confidence"), 0.18), 0, 1
                ),
                long_profile_confidence=_clamp(
                    _finite(profiles.get("long_profile_confidence"), 0.12), 0, 1
                ),
            ),
        )


def _network_predict_batch(features: np.ndarray, parameters: dict[str, Any]) -> np.ndarray:
    fallback = np.zeros((features.shape[0], HORIZON_STEPS), dtype=np.float64)
    network = parameters.get("network")
    if not isinstance(network, dict):
        return fallback
    try:
        if features.ndim != 2 or not np.isfinite(features).all():
            return fallback

        def finite_array(name: str, shape: tuple[int, ...]) -> np.ndarray:
            value = np.asarray(network[name], dtype=np.float64)
            if value.shape != shape or not np.isfinite(value).all():
                raise ValueError(f"invalid forecast tensor {name}")
            return value

        x_mean = np.asarray(network["x_mean"], dtype=np.float64)
        x_scale = np.asarray(network["x_scale"], dtype=np.float64)
        if (
            x_mean.ndim != 1
            or x_scale.shape != x_mean.shape
            or features.shape[1] != x_mean.size
            or not np.isfinite(x_mean).all()
            or not np.isfinite(x_scale).all()
            or np.any(x_scale <= 1e-8)
        ):
            return fallback
        w1_raw = np.asarray(network["w1"], dtype=np.float64)
        if w1_raw.ndim != 2 or w1_raw.shape[0] != x_mean.size:
            return fallback
        hidden_size = w1_raw.shape[1]
        if hidden_size <= 0:
            return fallback
        w1 = finite_array("w1", (x_mean.size, hidden_size))
        b1 = finite_array("b1", (hidden_size,))
        with np.errstate(over="raise", divide="raise", invalid="raise"):
            normalized = np.clip((features - x_mean) / x_scale, -8.0, 8.0)
            shared = np.tanh(normalized @ w1 + b1)
            if network.get("kind") == V3_NETWORK_KIND:
                base_w = finite_array("base_w", (hidden_size, HORIZON_STEPS))
                base_b = finite_array("base_b", (HORIZON_STEPS,))
                event_w1_raw = np.asarray(network["event_w1"], dtype=np.float64)
                if event_w1_raw.ndim != 2 or event_w1_raw.shape[0] != hidden_size:
                    return fallback
                interaction_size = event_w1_raw.shape[1]
                if interaction_size <= 0:
                    return fallback
                event_w1 = finite_array(
                    "event_w1", (hidden_size, interaction_size)
                )
                event_b1 = finite_array("event_b1", (interaction_size,))
                event_w2 = finite_array(
                    "event_w2", (interaction_size, HORIZON_STEPS)
                )
                event_b2 = finite_array("event_b2", (HORIZON_STEPS,))
                gate_w = finite_array("gate_w", (hidden_size, HORIZON_STEPS))
                gate_b = finite_array("gate_b", (HORIZON_STEPS,))
                base = shared @ base_w + base_b
                interaction_hidden = np.tanh(shared @ event_w1 + event_b1)
                refinement = interaction_hidden @ event_w2 + event_b2
                gate = 1.0 / (
                    1.0
                    + np.exp(
                        -np.clip(shared @ gate_w + gate_b, -20.0, 20.0)
                    )
                )
                prediction = base + gate * refinement
            else:
                w2 = finite_array("w2", (hidden_size, HORIZON_STEPS))
                b2 = finite_array("b2", (HORIZON_STEPS,))
                prediction = shared @ w2 + b2
        if (
            prediction.shape != (features.shape[0], HORIZON_STEPS)
            or not np.isfinite(prediction).all()
        ):
            return fallback
        return np.clip(prediction, -180.0, 180.0)
    except (KeyError, TypeError, ValueError, FloatingPointError, OverflowError):
        # A persisted incompatible/corrupt personal model must degrade to the
        # conservative mathematical baseline rather than break forecasting.
        return fallback
