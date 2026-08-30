from __future__ import annotations

import bisect
import hashlib
import json
import logging
import math
import threading
import time
from dataclasses import dataclass, replace
from itertools import product
from typing import Any, Callable, Sequence
from uuid import UUID, uuid4

import numpy as np
from sqlalchemy import Integer, and_, cast, delete, func, select, text, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .forecast_events import (
    EVENT_KINDS,
    EventEffectSample,
    EventResponseWindow,
    apply_bounded_event_personalization,
    combined_event_personalization_is_valid,
    fit_bounded_event_personalization,
    gate_combined_event_personalization,
)
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
    ForecastAlertAssessment,
    ForecastAlertCrossing,
    ForecastCapabilityStatus,
    ForecastCurrentResponse,
    ForecastDataStatus,
    ForecastLatestTrainingAttempt,
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
STATIC_PERSONAL_ARCHITECTURE = "personalized-static-ridge-residual-v4"
FORECAST_ENGINE_VERSION = "forecast-engine-v8-trend-smooth-residual"
ACTIVE_MODEL_METADATA_KEY = "active_forecast_model"
ACTIVATION_HISTORY_METADATA_KEY = "forecast_model_activation_history"
GLUCOSE_SOURCE_REVISION_METADATA_KEY = "forecast_glucose_source_revision"
STATIC_TRAINING_MODE = "manual"
STATIC_INTERVAL_LEVEL = 0.80
STATIC_INTERVAL_Z = 1.2816
STATIC_LOW_GUARD_MG_DL = 90.0
STATIC_ALERT_SAFETY_ENVELOPE = "reference-interval-union-v1"
STATIC_DISPLAY_SAFETY_ENVELOPE = "chart-only-conformal-v1"
STATIC_DISPLAY_SIGMA_EXPANSION = 1.05
STATIC_TRAINING_SEED = 20_260_805
STATIC_FEATURE_SCHEMA = "generic-glucose-context-v3"
STATIC_NETWORK_KIND = "static_generic_ridge_v4"
STATIC_ARTIFACT_VERSION = 7
STATIC_FEATURE_COUNT = 138
STATIC_RIDGE_ALPHA = 100.0
STATIC_RIDGE_ALPHAS = (10.0, 30.0, 100.0, 300.0, 1_000.0)
STATIC_REFERENCE_KIND = "quality-gated-damped-trend-events-v1"
STATIC_EVENT_LABELS_CAUSAL = "anchor-known-training-labels-v1"
STATIC_EVENT_LABELS_RETROSPECTIVE = "retrospective-training-labels-v1"
STATIC_TREND_DECAY_MINUTES = 42.0
STATIC_TREND_LOOKBACK_MINUTES = 55
STATIC_HORIZON_SMOOTHNESS = 2.0
STATIC_SHRINK_GRID = (0.0, 0.25, 0.50, 0.75, 1.0)
STATIC_SHRINK_KNOT_MINUTES = (5, 30, 60, 120)
STATIC_TRAJECTORY_METRICS = (
    "trajectory_max_step_mg_dl",
    "trajectory_p95_step_mg_dl",
    "trajectory_max_curvature_mg_dl",
    "trajectory_p95_curvature_mg_dl",
    "strong_trend_samples",
    "near_flat_strong_trend_rate",
    "strong_trend_direction_agreement",
)
STATIC_PURGE_MINUTES = HORIZON_MINUTES
STATIC_PURGE_WINDOWS = STATIC_PURGE_MINUTES // STEP_MINUTES
STATIC_PROMOTION_GATE_VERSION = "independent-day-block-hypo-safe-v3"
STATIC_DISPLAY_PROTOCOL = "retrospective-sensor-time-display-v2"
STATIC_MIN_TRAIN_DAYS = 8
STATIC_TUNING_DAYS = 1
STATIC_CALIBRATION_DAYS = 2
STATIC_TEST_DAYS = 4
STATIC_PROSPECTIVE_MIN_DAYS = 14
STATIC_PROSPECTIVE_PROTOCOL = "frozen-future-local-days-v1"
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
ALERT_TARGET_LOW_MMOL_L = 4.2
ALERT_TARGET_HIGH_MMOL_L = 9.0
# Juggluco's canonical display conversion is 18.0 mg/dL per mmol/L. Keeping
# both exact wire values avoids a rounded Android label becoming a different
# threshold from the backend assessment.
ALERT_TARGET_LOW_MG_DL = 75.6
ALERT_TARGET_HIGH_MG_DL = 162.0
ALERT_MAX_LEAD_MINUTES = 60
ALERT_REQUIRED_CONSECUTIVE_POINTS = 2
ALERT_DELIVERY_MAX_ANCHOR_AGE_MINUTES = 10
ALERT_VALIDATION_DELIVERY_MARGIN_SECONDS = 60
ALERT_VALIDATION_PROTOCOL = "frozen-14d-episode-alert-v3"
ALERT_VALIDATION_POLICY_SENSITIVITY = "early"
ALERT_VALIDATION_MIN_USER_HORIZON_MINUTES = 15
ALERT_VALIDATION_COOLDOWN_MINUTES = 15
ALERT_VALIDATION_EPISODE_REARM_MINUTES = 15
# Each accepted dense day spans at least twenty hours at >=80% five-minute
# density. Requiring eight observed decisions/hour prevents alert validation
# from silently falling back to the sparse 120-minute promotion anchors.
ALERT_VALIDATION_MIN_ANCHORS_PER_DAY = 20 * 8
ALERT_VALIDATION_MIN_ANCHORS = (
    STATIC_PROSPECTIVE_MIN_DAYS * ALERT_VALIDATION_MIN_ANCHORS_PER_DAY
)
ALERT_VALIDATION_MAX_ANCHORS = (
    STATIC_PROSPECTIVE_MIN_DAYS * 24 * 60 // STEP_MINUTES
)
ALERT_VALIDATION_MIN_EPISODES = 5
ALERT_VALIDATION_MIN_EPISODE_DAYS = 4
ALERT_VALIDATION_MIN_LOW_RECALL = 0.80
ALERT_VALIDATION_MIN_HIGH_RECALL = 0.75
ALERT_VALIDATION_MAX_MISSED_LOW_EPISODES = 1
ALERT_VALIDATION_MAX_FALSE_ALERTS_PER_DAY = 1.0
ALERT_VALIDATION_MIN_MEDIAN_LEAD_MINUTES = 15.0
ALERT_VALIDATION_RECALL_TOLERANCE = 0.05
ALERT_VALIDATION_FALSE_ALERT_TOLERANCE_PER_DAY = 0.25
ALERT_VALIDATION_LEAD_TOLERANCE_MINUTES = 5.0
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


def glucose_source_revision(session: Session) -> int:
    """Return the durable monotonic revision for viewer reconciliation."""

    value = session.scalar(
        select(cast(BackendMetadataRecord.value_text, Integer)).where(
            BackendMetadataRecord.key == GLUCOSE_SOURCE_REVISION_METADATA_KEY
        )
    )
    return int(value or 0)


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


def _safe_glucose_mg_dl(value: Any) -> float | None:
    """Return a public-contract glucose value or fail closed for legacy rows."""

    parsed = _finite(value, math.nan)
    return parsed if 20.0 <= parsed <= 600.0 else None


def _alert_crossing(
    points: Sequence[ForecastPoint],
    anchor_ms: int,
    *,
    direction: str,
    evidence: str,
) -> ForecastAlertCrossing | None:
    """Return a bounded qualitative crossing supported by two 5-minute points.

    Evidence is searched independently so an early interval-edge crossing and
    a later median crossing can both reach the phone's sensitivity policy. The
    labels are intentionally qualitative; an 80% marginal forecast interval is
    not a calibrated probability of ever crossing a threshold.
    """

    if (
        direction not in {"low", "high"}
        or evidence not in {"possible", "likely"}
        or anchor_ms <= 0
    ):
        return None
    horizon_end_ms = anchor_ms + ALERT_MAX_LEAD_MINUTES * 60_000
    ordered = sorted(
        (
            point
            for point in points
            if anchor_ms < point.at_ms <= horizon_end_ms
            and (point.at_ms - anchor_ms) % STEP_MS == 0
            and 20.0 <= point.low_mg_dl <= point.median_mg_dl
            and point.median_mg_dl <= point.high_mg_dl <= 600.0
        ),
        key=lambda point: point.at_ms,
    )
    if len(ordered) < ALERT_REQUIRED_CONSECUTIVE_POINTS:
        return None

    def outside(point: ForecastPoint, evidence: str) -> bool:
        if direction == "low":
            value = (
                point.median_mg_dl
                if evidence == "likely"
                else point.low_mg_dl
            )
            return value < ALERT_TARGET_LOW_MG_DL
        value = (
            point.median_mg_dl
            if evidence == "likely"
            else point.high_mg_dl
        )
        return value > ALERT_TARGET_HIGH_MG_DL

    for index in range(len(ordered) - 1):
        first = ordered[index]
        second = ordered[index + 1]
        if second.at_ms - first.at_ms != STEP_MS:
            continue
        if not (outside(first, evidence) and outside(second, evidence)):
            continue
        lead_minutes = int((first.at_ms - anchor_ms) // 60_000)
        interval_edge = (
            first.low_mg_dl if direction == "low" else first.high_mg_dl
        )
        return ForecastAlertCrossing(
            direction=direction,
            evidence=evidence,
            crossing_at_ms=first.at_ms,
            lead_minutes=lead_minutes,
            predicted_median_mg_dl=first.median_mg_dl,
            interval_edge_mg_dl=interval_edge,
        )
    return None


def _alert_assessment(
    *,
    status: str,
    anchor_ms: int | None,
    points: Sequence[ForecastPoint],
    model_version: str,
    reading_fresh: bool,
    alert_approved: bool,
) -> ForecastAlertAssessment:
    """Build a read-only target assessment with fail-closed delivery state."""

    common = {
        "target_low_mg_dl": ALERT_TARGET_LOW_MG_DL,
        "target_high_mg_dl": ALERT_TARGET_HIGH_MG_DL,
        "target_low_mmol_l": ALERT_TARGET_LOW_MMOL_L,
        "target_high_mmol_l": ALERT_TARGET_HIGH_MMOL_L,
    }
    unavailable_reason = {
        "no_data": "no_data",
        "stale": "stale",
        "low_confidence": "low_confidence",
    }.get(status)
    if unavailable_reason is not None or not reading_fresh or anchor_ms is None:
        reasons = [
            unavailable_reason
            or ("no_data" if anchor_ms is None else "reading_not_fresh")
        ]
        return ForecastAlertAssessment(
            monitoring_status="unavailable",
            delivery_eligible=False,
            suppressed_reasons=reasons,
            low_possible=None,
            low_likely=None,
            high_possible=None,
            high_likely=None,
            low=None,
            high=None,
            **common,
        )

    low_possible = _alert_crossing(
        points, anchor_ms, direction="low", evidence="possible"
    )
    low_likely = _alert_crossing(
        points, anchor_ms, direction="low", evidence="likely"
    )
    high_possible = _alert_crossing(
        points, anchor_ms, direction="high", evidence="possible"
    )
    high_likely = _alert_crossing(
        points, anchor_ms, direction="high", evidence="likely"
    )
    # Backward-compatible summaries keep the original conservative preference.
    low = low_likely or low_possible
    high = high_likely or high_possible
    crossings = {
        "low_possible": low_possible,
        "low_likely": low_likely,
        "high_possible": high_possible,
        "high_likely": high_likely,
        "low": low,
        "high": high,
    }
    if status == "ready" and alert_approved:
        return ForecastAlertAssessment(
            monitoring_status="eligible",
            delivery_eligible=True,
            suppressed_reasons=[],
            **crossings,
            **common,
        )

    if model_version == BASELINE_VERSION:
        reason = "baseline_model"
    elif status != "ready":
        reason = "model_not_ready"
    else:
        reason = "alert_not_approved"
    return ForecastAlertAssessment(
        monitoring_status="shadow",
        delivery_eligible=False,
        suppressed_reasons=[reason],
        **crossings,
        **common,
    )


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


def _static_shrinkage_curve(knot_weights: Sequence[float]) -> np.ndarray:
    """Interpolate a conservative, horizon-smooth persistence blend.

    The previous four independent step bands could turn an otherwise smooth
    residual forecast into a visible jump at 30/35, 60/65, or 90/95 minutes.
    A monotone knot curve keeps the same low-variance shrinkage idea while making
    every adjacent five-minute weight continuous.
    """

    knots = np.asarray(knot_weights, dtype=np.float64)
    if (
        knots.shape != (len(STATIC_SHRINK_KNOT_MINUTES),)
        or not np.isfinite(knots).all()
        or np.any(knots < 0.0)
        or np.any(knots > 1.0)
        or np.any(np.diff(knots) > 1e-12)
    ):
        raise ValueError("static shrinkage knots must be finite and non-increasing")
    horizons = np.arange(
        STEP_MINUTES, HORIZON_MINUTES + STEP_MINUTES, STEP_MINUTES, dtype=np.float64
    )
    return np.interp(
        horizons,
        np.asarray(STATIC_SHRINK_KNOT_MINUTES, dtype=np.float64),
        knots,
    )


def _static_horizon_smoother() -> np.ndarray:
    """Return the code-owned second-difference smoother for residual targets."""

    second_difference = np.zeros((HORIZON_STEPS - 2, HORIZON_STEPS), dtype=np.float64)
    for index in range(HORIZON_STEPS - 2):
        second_difference[index, index : index + 3] = (1.0, -2.0, 1.0)
    penalty = second_difference.T @ second_difference
    return np.linalg.solve(
        np.eye(HORIZON_STEPS, dtype=np.float64) + STATIC_HORIZON_SMOOTHNESS * penalty,
        np.eye(HORIZON_STEPS, dtype=np.float64),
    )


def _finite_sample_quantile_level(
    sample_count: int, coverage: float = STATIC_INTERVAL_LEVEL
) -> float:
    """Conservative split-conformal order statistic for a finite calibration set."""

    if sample_count <= 0:
        raise ValueError("sample_count must be positive")
    return min(1.0, math.ceil((sample_count + 1) * coverage) / sample_count)


def _apply_static_predictor(
    prediction: np.ndarray,
    reference_prediction: np.ndarray,
    sigma: np.ndarray,
    parameters: dict[str, Any],
) -> tuple[np.ndarray, np.ndarray]:
    """Apply immutable shrinkage and the calibration for this model's scope.

    The chart-only predictor keeps its evaluated central estimate and conformal
    band. Only the separately validated prospective/alert scope unions that band
    with the reference and applies the low-glucose guard; doing so to a chart
    predictor would change the point trajectory that was actually selected.
    """

    blend = _validated_vector(parameters.get("persistence_blend_weights"))
    calibration = parameters.get("frozen_calibration")
    if blend is None or np.any(blend < 0.0) or np.any(blend > 1.0):
        return prediction, sigma
    if not isinstance(calibration, dict):
        return prediction, sigma
    if not _static_calibration_scope_is_valid(calibration):
        return prediction, sigma
    bias = _validated_vector(calibration.get("bias_mg_dl"))
    frozen_sigma = _validated_vector(calibration.get("sigma_mg_dl"), positive=True)
    reference_sigma = _validated_vector(
        calibration.get("reference_sigma_mg_dl"), positive=True
    )
    if (
        bias is None
        or not np.allclose(bias, 0.0, rtol=0.0, atol=1e-12)
        or frozen_sigma is None
        or reference_sigma is None
    ):
        return prediction, sigma
    reference = np.asarray(reference_prediction, dtype=np.float64)
    display_only = (
        calibration["safety_envelope"] == STATIC_DISPLAY_SAFETY_ENVELOPE
    )
    if prediction.ndim == 1:
        if reference.shape != (HORIZON_STEPS,):
            return prediction, sigma
        shrunk = np.clip(reference + blend * (prediction - reference), 20.0, 600.0)
        if display_only:
            return shrunk, frozen_sigma.copy()
        # Preserve every low-glucose signal emitted by either trajectory. This
        # makes the point forecast's <70 false-safe set a subset of the reference
        # model's false-safe set without shifting ordinary-range predictions.
        shrunk = np.where(
            (shrunk <= STATIC_LOW_GUARD_MG_DL)
            | (reference <= STATIC_LOW_GUARD_MG_DL),
            np.minimum(shrunk, reference),
            shrunk,
        )
        safe_sigma = _reference_safety_sigma(
            shrunk, reference, frozen_sigma, reference_sigma
        )
        return shrunk, safe_sigma
    if reference.shape != prediction.shape:
        return prediction, sigma
    shrunk = reference + blend.reshape(1, -1) * (prediction - reference)
    shrunk = np.clip(shrunk, 20.0, 600.0)
    if display_only:
        return shrunk, np.broadcast_to(frozen_sigma, shrunk.shape).copy()
    shrunk = np.where(
        (shrunk <= STATIC_LOW_GUARD_MG_DL)
        | (reference <= STATIC_LOW_GUARD_MG_DL),
        np.minimum(shrunk, reference),
        shrunk,
    )
    safe_sigma = _reference_safety_sigma(
        shrunk, reference, frozen_sigma, reference_sigma
    )
    return shrunk, safe_sigma


def _static_calibration_scope_is_valid(calibration: dict[str, Any]) -> bool:
    envelope = calibration.get("safety_envelope")
    if envelope == STATIC_DISPLAY_SAFETY_ENVELOPE:
        expansion, low_guard = STATIC_DISPLAY_SIGMA_EXPANSION, False
    elif envelope == STATIC_ALERT_SAFETY_ENVELOPE:
        expansion, low_guard = 1.0, True
    else:
        return False
    return bool(
        calibration.get("point_low_guard") is low_guard
        and math.isclose(
            _finite(calibration.get("sigma_expansion"), -1.0),
            expansion,
            rel_tol=0.0,
            abs_tol=1e-12,
        )
    )


def _reference_safety_sigma(
    candidate_prediction: np.ndarray,
    reference_prediction: np.ndarray,
    candidate_sigma: np.ndarray,
    reference_sigma: np.ndarray,
) -> np.ndarray:
    """Return a symmetric band containing both frozen central intervals.

    The learned median may improve average error while still missing a rare low.
    Until enough independent hypo episodes exist, its displayed lower bound must
    never be higher than the calibrated event-aware reference lower bound.  The
    same union is used on the upper side so the returned API can retain its
    existing median+sigma contract without hiding asymmetric widening.
    """

    candidate = np.asarray(candidate_prediction, dtype=np.float64)
    reference = np.asarray(reference_prediction, dtype=np.float64)
    candidate_scale = np.asarray(candidate_sigma, dtype=np.float64)
    reference_scale = np.asarray(reference_sigma, dtype=np.float64)
    if candidate.shape != reference.shape or candidate.shape[-1:] != (HORIZON_STEPS,):
        return np.maximum(candidate_scale, 6.0)
    if candidate_scale.shape != (HORIZON_STEPS,) or reference_scale.shape != (
        HORIZON_STEPS,
    ):
        return np.maximum(candidate_scale, 6.0)
    candidate_half = np.maximum.accumulate(
        np.maximum(7.0, STATIC_INTERVAL_Z * np.maximum(candidate_scale, 6.0))
    )
    reference_half = np.maximum.accumulate(
        np.maximum(7.0, STATIC_INTERVAL_Z * np.maximum(reference_scale, 6.0))
    )
    if candidate.ndim == 1:
        union_half = np.maximum.reduce(
            (
                candidate_half,
                candidate - (reference - reference_half),
                (reference + reference_half) - candidate,
            )
        )
    else:
        union_half = np.maximum.reduce(
            (
                np.broadcast_to(candidate_half, candidate.shape),
                candidate - (reference - reference_half.reshape(1, -1)),
                (reference + reference_half.reshape(1, -1)) - candidate,
            )
        )
    union_half = np.maximum.accumulate(np.maximum(union_half, 7.0), axis=-1)
    return union_half / STATIC_INTERVAL_Z


def _forecast_interval_bounds(
    prediction: np.ndarray, sigma: np.ndarray
) -> tuple[np.ndarray, np.ndarray]:
    """Build the displayed/scored 80% interval under one shared contract.

    A static safety union may legitimately exceed the historical 200 mg/dL
    sigma cap.  Capping its half-width at the full API glucose span still allows
    [20, 600] and therefore cannot narrow either clipped source interval.
    """

    point = np.clip(np.asarray(prediction, dtype=np.float64), 20.0, 600.0)
    scale = np.asarray(sigma, dtype=np.float64)
    if scale.shape == (HORIZON_STEPS,):
        scale = np.broadcast_to(scale, point.shape)
    elif scale.shape != point.shape:
        raise ValueError("sigma must have 24 horizons or match prediction shape")
    maximum_sigma = (600.0 - 20.0) / STATIC_INTERVAL_Z
    scale = np.nan_to_num(
        scale, nan=60.0, posinf=maximum_sigma, neginf=60.0
    )
    half_width = np.maximum.accumulate(
        np.maximum(7.0, STATIC_INTERVAL_Z * np.maximum(6.0, scale)), axis=-1
    )
    half_width = np.minimum(600.0 - 20.0, half_width)
    return (
        np.clip(point - half_width, 20.0, 600.0),
        np.clip(point + half_width, 20.0, 600.0),
    )


def _alert_validation_thresholds() -> dict[str, float | int | str]:
    """Return the preregistered, code-owned alert validation thresholds."""

    return {
        "protocol": ALERT_VALIDATION_PROTOCOL,
        "target_low_mg_dl": ALERT_TARGET_LOW_MG_DL,
        "target_high_mg_dl": ALERT_TARGET_HIGH_MG_DL,
        "horizon_minutes": ALERT_MAX_LEAD_MINUTES,
        "minimum_user_horizon_minutes": (
            ALERT_VALIDATION_MIN_USER_HORIZON_MINUTES
        ),
        "policy_sensitivity": ALERT_VALIDATION_POLICY_SENSITIVITY,
        "evidence_arbitration": "earliest_crossing_likely_on_tie",
        "direction_arbitration": "earliest_crossing_likely_then_low_on_tie",
        "maximum_deliveries_per_issue": 1,
        "required_consecutive_points": ALERT_REQUIRED_CONSECUTIVE_POINTS,
        "maximum_anchor_age_minutes": ALERT_DELIVERY_MAX_ANCHOR_AGE_MINUTES,
        "delivery_margin_seconds": ALERT_VALIDATION_DELIVERY_MARGIN_SECONDS,
        "cooldown_minutes": ALERT_VALIDATION_COOLDOWN_MINUTES,
        "episode_rearm_minutes": ALERT_VALIDATION_EPISODE_REARM_MINUTES,
        "minimum_days": STATIC_PROSPECTIVE_MIN_DAYS,
        "minimum_anchors": ALERT_VALIDATION_MIN_ANCHORS,
        "minimum_anchors_per_day": ALERT_VALIDATION_MIN_ANCHORS_PER_DAY,
        "maximum_anchors": ALERT_VALIDATION_MAX_ANCHORS,
        "minimum_episodes_per_direction": ALERT_VALIDATION_MIN_EPISODES,
        "minimum_episode_days_per_direction": ALERT_VALIDATION_MIN_EPISODE_DAYS,
        "minimum_low_episode_recall": ALERT_VALIDATION_MIN_LOW_RECALL,
        "minimum_high_episode_recall": ALERT_VALIDATION_MIN_HIGH_RECALL,
        "maximum_missed_low_episodes": ALERT_VALIDATION_MAX_MISSED_LOW_EPISODES,
        "maximum_selected_false_alerts_per_day": (
            ALERT_VALIDATION_MAX_FALSE_ALERTS_PER_DAY
        ),
        "minimum_median_lead_minutes": ALERT_VALIDATION_MIN_MEDIAN_LEAD_MINUTES,
        "comparator_recall_tolerance": ALERT_VALIDATION_RECALL_TOLERANCE,
        "comparator_false_alert_tolerance_per_day": (
            ALERT_VALIDATION_FALSE_ALERT_TOLERANCE_PER_DAY
        ),
        "comparator_lead_tolerance_minutes": (
            ALERT_VALIDATION_LEAD_TOLERANCE_MINUTES
        ),
    }


def _alert_episode_metrics(
    prediction: np.ndarray,
    target: np.ndarray,
    sigma: np.ndarray,
    *,
    anchor_times_ms: Sequence[int],
    decision_times_ms: Sequence[int],
    anchor_glucose_mg_dl: Sequence[float],
    anchor_utc_offset_minutes: Sequence[int],
    delivery_ready: Sequence[bool],
) -> dict[str, float | None]:
    """Score target-crossing alerts as deduplicated clinical episodes.

    The function is deterministic and read-only. It replays Android's Early
    sensitivity at the maximum selectable 60-minute horizon, including its
    single-direction arbitration, with the shortest selectable cooldown.
    Actual episodes require two adjacent five-minute readings outside the exact
    4.2--9.0 mmol/L target.
    """

    point = np.asarray(prediction, dtype=np.float64)
    actual = np.asarray(target, dtype=np.float64)
    scale = np.asarray(sigma, dtype=np.float64)
    row_count = point.shape[0] if point.ndim == 2 else 0
    empty: dict[str, float | None] = {
        "finite": 0.0,
        "evaluation_days": 0.0,
        "evaluated_anchors": float(row_count),
        "low_episode_count": 0.0,
        "high_episode_count": 0.0,
        "low_episode_days": 0.0,
        "high_episode_days": 0.0,
        "low_selected_alert_count": 0.0,
        "high_selected_alert_count": 0.0,
        "low_selected_possible_count": 0.0,
        "high_selected_possible_count": 0.0,
        "low_selected_likely_count": 0.0,
        "high_selected_likely_count": 0.0,
        "low_selected_episode_recall": None,
        "high_selected_episode_recall": None,
        "low_selected_missed_episodes": 0.0,
        "high_selected_missed_episodes": 0.0,
        "low_selected_median_lead_minutes": None,
        "high_selected_median_lead_minutes": None,
        "low_selected_false_alerts": 0.0,
        "high_selected_false_alerts": 0.0,
        "selected_false_alerts_per_day": None,
    }
    if (
        point.ndim != 2
        or point.shape != actual.shape
        or point.shape[1:] != (HORIZON_STEPS,)
        or row_count <= 0
        or len(anchor_times_ms) != row_count
        or len(decision_times_ms) != row_count
        or len(anchor_glucose_mg_dl) != row_count
        or len(anchor_utc_offset_minutes) != row_count
        or len(delivery_ready) != row_count
        or scale.shape not in {(HORIZON_STEPS,), point.shape}
        or not np.isfinite(point).all()
        or not np.isfinite(actual).all()
        or not np.isfinite(scale).all()
    ):
        return empty
    anchors = [int(value) for value in anchor_times_ms]
    decisions = [int(value) for value in decision_times_ms]
    currents = [_finite(value, math.nan) for value in anchor_glucose_mg_dl]
    offsets = [int(value) for value in anchor_utc_offset_minutes]
    if (
        any(value <= 0 for value in anchors)
        or any(
            decision_ms < anchor_ms
            for anchor_ms, decision_ms in zip(anchors, decisions)
        )
        or any(not math.isfinite(value) or not 20.0 <= value <= 600.0 for value in currents)
        or np.any(actual < 20.0)
        or np.any(actual > 600.0)
    ):
        return empty

    low, high = _forecast_interval_bounds(point, scale)
    actual_by_time: dict[int, float] = {}
    offset_by_time: dict[int, int] = {}
    for row_index, anchor_ms in enumerate(anchors):
        for horizon_index in range(HORIZON_STEPS):
            at_ms = anchor_ms + (horizon_index + 1) * STEP_MS
            value = float(actual[row_index, horizon_index])
            previous = actual_by_time.get(at_ms)
            if previous is not None and not math.isclose(
                previous, value, rel_tol=0.0, abs_tol=1e-6
            ):
                return empty
            actual_by_time[at_ms] = value
            offset_by_time.setdefault(at_ms, offsets[row_index])

    def episodes(direction: str) -> list[tuple[int, int, int]]:
        threshold = (
            ALERT_TARGET_LOW_MG_DL
            if direction == "low"
            else ALERT_TARGET_HIGH_MG_DL
        )
        result: list[tuple[int, int, int]] = []
        run: list[int] = []

        def finish() -> None:
            if len(run) >= ALERT_REQUIRED_CONSECUTIVE_POINTS:
                start_ms = run[0]
                end_ms = run[-1]
                offset_ms = offset_by_time.get(start_ms, 0) * 60_000
                result.append(
                    (start_ms, end_ms, (start_ms + offset_ms) // 86_400_000)
                )
            run.clear()

        previous_at: int | None = None
        for at_ms, value in sorted(actual_by_time.items()):
            outside = (
                value < threshold if direction == "low" else value > threshold
            )
            adjacent = previous_at is not None and at_ms - previous_at == STEP_MS
            if outside:
                if run and not adjacent:
                    finish()
                run.append(at_ms)
            else:
                finish()
            previous_at = at_ms
        finish()
        merged: list[tuple[int, int, int]] = []
        rearm_ms = ALERT_VALIDATION_EPISODE_REARM_MINUTES * 60_000
        for start_ms, end_ms, day in result:
            if merged and start_ms - merged[-1][1] <= rearm_ms:
                previous_start, _previous_end, previous_day = merged[-1]
                merged[-1] = (previous_start, end_ms, previous_day)
            else:
                merged.append((start_ms, end_ms, day))
        return merged

    low_episodes = episodes("low")
    high_episodes = episodes("high")

    # Exactly one candidate can reach Android's coordinator for each issue.
    # Tuples are (issue_ms, crossing_ms, evidence).
    raw_alerts: dict[str, list[tuple[int, int, str]]] = {
        "low": [],
        "high": [],
    }
    evaluated_local_days: set[int] = set()
    first_hour_steps = ALERT_MAX_LEAD_MINUTES // STEP_MINUTES
    for row_index, anchor_ms in sorted(
        enumerate(anchors), key=lambda item: item[1]
    ):
        # Receipt is only the earliest possible delivery. Account for backend
        # response, networking, and Android dispatch with a preregistered fixed
        # margin so validation cannot claim a crossing that would already be
        # stale by the time the phone can act on it.
        issue_ms = (
            decisions[row_index]
            + ALERT_VALIDATION_DELIVERY_MARGIN_SECONDS * 1_000
        )
        offset_ms = offsets[row_index] * 60_000
        evaluated_local_days.add((anchor_ms + offset_ms) // 86_400_000)
        current = currents[row_index]
        if (
            not bool(delivery_ready[row_index])
            or
            issue_ms - anchor_ms
            > ALERT_DELIVERY_MAX_ANCHOR_AGE_MINUTES * 60_000
            or not ALERT_TARGET_LOW_MG_DL <= current <= ALERT_TARGET_HIGH_MG_DL
        ):
            continue
        sources = {
            ("low", "possible"): low[row_index, :first_hour_steps],
            ("low", "likely"): point[row_index, :first_hour_steps],
            ("high", "possible"): high[row_index, :first_hour_steps],
            ("high", "likely"): point[row_index, :first_hour_steps],
        }
        crossings: dict[tuple[str, str], tuple[int, int, str] | None] = {}
        for (direction, evidence), values in sources.items():
            threshold = (
                ALERT_TARGET_LOW_MG_DL
                if direction == "low"
                else ALERT_TARGET_HIGH_MG_DL
            )
            outside = values < threshold if direction == "low" else values > threshold
            for index in range(first_hour_steps - 1):
                if bool(outside[index]) and bool(outside[index + 1]):
                    crossing_ms = anchor_ms + (index + 1) * STEP_MS
                    # Android evaluates remaining lead at delivery time and
                    # rejects a crossing that has already happened. Preserve
                    # that exact first-crossing behavior in prospective replay.
                    if crossing_ms > issue_ms:
                        crossings[(direction, evidence)] = (
                            issue_ms,
                            crossing_ms,
                            evidence,
                        )
                    break
            crossings.setdefault((direction, evidence), None)

        def choose_evidence(direction: str) -> tuple[int, int, str] | None:
            possible = crossings[(direction, "possible")]
            likely = crossings[(direction, "likely")]
            if possible is None:
                return likely
            if likely is None:
                return possible
            # ForecastRiskEvaluator Early policy: earliest crossing wins and a
            # likely crossing wins an exact time tie.
            return likely if likely[1] <= possible[1] else possible

        low_candidate = choose_evidence("low")
        high_candidate = choose_evidence("high")
        selected: tuple[str, tuple[int, int, str]] | None
        if low_candidate is None:
            selected = (
                None if high_candidate is None else ("high", high_candidate)
            )
        elif high_candidate is None:
            selected = ("low", low_candidate)
        elif low_candidate[1] != high_candidate[1]:
            selected = (
                ("low", low_candidate)
                if low_candidate[1] < high_candidate[1]
                else ("high", high_candidate)
            )
        elif low_candidate[2] != high_candidate[2]:
            selected = (
                ("low", low_candidate)
                if low_candidate[2] == "likely"
                else ("high", high_candidate)
            )
        else:
            # Android's final deterministic tie-break gives low precedence.
            selected = ("low", low_candidate)
        if selected is not None:
            direction, candidate = selected
            raw_alerts[direction].append(candidate)

    cooldown_ms = ALERT_VALIDATION_COOLDOWN_MINUTES * 60_000

    def deduplicate(
        items: Sequence[tuple[int, int, str]],
    ) -> list[tuple[int, int, str]]:
        result: list[tuple[int, int, str]] = []
        last_issued_ms: int | None = None
        for issued_ms, crossing_ms, evidence in sorted(items):
            if last_issued_ms is not None and issued_ms - last_issued_ms < cooldown_ms:
                continue
            result.append((issued_ms, crossing_ms, evidence))
            last_issued_ms = issued_ms
        return result

    alerts = {key: deduplicate(value) for key, value in raw_alerts.items()}

    def score(
        episode_values: Sequence[tuple[int, int, int]],
        alert_values: Sequence[tuple[int, int, str]],
    ) -> tuple[float | None, int, float | None, int]:
        leads: list[float] = []
        matched = 0
        used_alert_indexes: set[int] = set()
        horizon_ms = ALERT_MAX_LEAD_MINUTES * 60_000
        for start_ms, _end_ms, _day in episode_values:
            eligible = [
                (index, issued_ms)
                for index, (issued_ms, _crossing_ms, _evidence) in enumerate(
                    alert_values
                )
                if index not in used_alert_indexes
                if issued_ms < start_ms <= issued_ms + horizon_ms
            ]
            if eligible:
                matched += 1
                alert_index, issued_ms = min(eligible, key=lambda item: item[1])
                used_alert_indexes.add(alert_index)
                leads.append((start_ms - issued_ms) / 60_000.0)
        # One delivered alert may validate at most one distinct rearmed episode;
        # repeats that cannot be paired remain false alerts rather than being
        # hidden behind the same eventual threshold crossing.
        false_alerts = len(alert_values) - len(used_alert_indexes)
        count = len(episode_values)
        return (
            float(matched / count) if count else None,
            count - matched,
            float(np.median(leads)) if leads else None,
            false_alerts,
        )

    low_selected = score(low_episodes, alerts["low"])
    high_selected = score(high_episodes, alerts["high"])

    evaluation_days = len(evaluated_local_days)
    selected_false = low_selected[3] + high_selected[3]

    def evidence_count(direction: str, evidence: str) -> float:
        return float(sum(item[2] == evidence for item in alerts[direction]))

    return {
        "finite": 1.0,
        "evaluation_days": float(evaluation_days),
        "evaluated_anchors": float(row_count),
        "low_episode_count": float(len(low_episodes)),
        "high_episode_count": float(len(high_episodes)),
        "low_episode_days": float(len({item[2] for item in low_episodes})),
        "high_episode_days": float(len({item[2] for item in high_episodes})),
        "low_selected_alert_count": float(len(alerts["low"])),
        "high_selected_alert_count": float(len(alerts["high"])),
        "low_selected_possible_count": evidence_count("low", "possible"),
        "high_selected_possible_count": evidence_count("high", "possible"),
        "low_selected_likely_count": evidence_count("low", "likely"),
        "high_selected_likely_count": evidence_count("high", "likely"),
        "low_selected_episode_recall": low_selected[0],
        "high_selected_episode_recall": high_selected[0],
        "low_selected_missed_episodes": float(low_selected[1]),
        "high_selected_missed_episodes": float(high_selected[1]),
        "low_selected_median_lead_minutes": low_selected[2],
        "high_selected_median_lead_minutes": high_selected[2],
        "low_selected_false_alerts": float(low_selected[3]),
        "high_selected_false_alerts": float(high_selected[3]),
        "selected_false_alerts_per_day": (
            float(selected_false / evaluation_days) if evaluation_days else None
        ),
    }


def _alert_validation_gates(
    candidate_metrics: dict[str, float | None],
    reference_metrics: dict[str, float | None],
    pinned_metrics: dict[str, float | None],
    current_metrics: dict[str, float | None] | None = None,
) -> dict[str, bool | float | int]:
    """Apply preregistered episode-level gates without affecting model training."""

    comparators = [reference_metrics, pinned_metrics]
    if current_metrics is not None:
        comparators.append(current_metrics)

    def metric(values: dict[str, float | None], name: str) -> float:
        return _finite(values.get(name), math.nan)

    finite_names = (
        "finite",
        "evaluation_days",
        "evaluated_anchors",
        "low_episode_count",
        "high_episode_count",
        "low_episode_days",
        "high_episode_days",
        "low_selected_episode_recall",
        "high_selected_episode_recall",
        "low_selected_missed_episodes",
        "high_selected_missed_episodes",
        "selected_false_alerts_per_day",
    )
    finite = all(
        math.isfinite(metric(values, name))
        for values in (candidate_metrics, *comparators)
        for name in finite_names
    ) and all(
        math.isfinite(metric(candidate_metrics, name))
        for name in (
            "low_selected_median_lead_minutes",
            "high_selected_median_lead_minutes",
        )
    ) and all(
        metric(values, "finite") == 1.0
        for values in (candidate_metrics, *comparators)
    )
    cohort_consistent = all(
        metric(values, name) == metric(candidate_metrics, name)
        for values in comparators
        for name in (
            "evaluation_days",
            "evaluated_anchors",
            "low_episode_count",
            "high_episode_count",
            "low_episode_days",
            "high_episode_days",
        )
    )

    def internally_consistent(values: dict[str, float | None]) -> bool:
        low_count = metric(values, "low_episode_count")
        high_count = metric(values, "high_episode_count")
        low_missed = metric(values, "low_selected_missed_episodes")
        high_missed = metric(values, "high_selected_missed_episodes")
        low_recall = metric(values, "low_selected_episode_recall")
        high_recall = metric(values, "high_selected_episode_recall")
        false_rate = metric(values, "selected_false_alerts_per_day")
        return bool(
            low_count > 0.0
            and high_count > 0.0
            and all(
                math.isclose(value, round(value), abs_tol=1e-9)
                for value in (
                    low_count,
                    high_count,
                    low_missed,
                    high_missed,
                    metric(values, "evaluation_days"),
                    metric(values, "evaluated_anchors"),
                )
            )
            and 0.0 <= low_missed <= low_count
            and 0.0 <= high_missed <= high_count
            and math.isclose(
                low_recall,
                (low_count - low_missed) / low_count,
                abs_tol=1e-9,
            )
            and math.isclose(
                high_recall,
                (high_count - high_missed) / high_count,
                abs_tol=1e-9,
            )
            and false_rate >= 0.0
        )

    metrics_consistent = bool(
        finite
        and all(
            internally_consistent(values)
            for values in (candidate_metrics, *comparators)
        )
    )
    evidence_sufficient = bool(
        finite
        and cohort_consistent
        and metrics_consistent
        and metric(candidate_metrics, "finite") == 1.0
        and metric(candidate_metrics, "evaluation_days")
        == STATIC_PROSPECTIVE_MIN_DAYS
        and metric(candidate_metrics, "evaluated_anchors")
        >= ALERT_VALIDATION_MIN_ANCHORS
        and metric(candidate_metrics, "evaluated_anchors")
        <= ALERT_VALIDATION_MAX_ANCHORS
        and metric(candidate_metrics, "low_episode_count")
        >= ALERT_VALIDATION_MIN_EPISODES
        and metric(candidate_metrics, "high_episode_count")
        >= ALERT_VALIDATION_MIN_EPISODES
        and metric(candidate_metrics, "low_episode_days")
        >= ALERT_VALIDATION_MIN_EPISODE_DAYS
        and metric(candidate_metrics, "high_episode_days")
        >= ALERT_VALIDATION_MIN_EPISODE_DAYS
    )
    low_recall_absolute = bool(
        evidence_sufficient
        and metric(candidate_metrics, "low_selected_episode_recall")
        >= ALERT_VALIDATION_MIN_LOW_RECALL
    )
    high_recall_absolute = bool(
        evidence_sufficient
        and metric(candidate_metrics, "high_selected_episode_recall")
        >= ALERT_VALIDATION_MIN_HIGH_RECALL
    )
    low_recall_comparator_safe = bool(
        evidence_sufficient
        and all(
            metric(candidate_metrics, "low_selected_episode_recall")
            >= metric(values, "low_selected_episode_recall")
            - ALERT_VALIDATION_RECALL_TOLERANCE
            for values in comparators
        )
    )
    high_recall_comparator_safe = bool(
        evidence_sufficient
        and all(
            metric(candidate_metrics, "high_selected_episode_recall")
            >= metric(values, "high_selected_episode_recall")
            - ALERT_VALIDATION_RECALL_TOLERANCE
            for values in comparators
        )
    )
    missed_low_absolute = bool(
        evidence_sufficient
        and metric(candidate_metrics, "low_selected_missed_episodes")
        <= ALERT_VALIDATION_MAX_MISSED_LOW_EPISODES
    )
    missed_low_comparator_safe = bool(
        evidence_sufficient
        and all(
            metric(candidate_metrics, "low_selected_missed_episodes")
            <= metric(values, "low_selected_missed_episodes")
            for values in comparators
        )
    )
    false_alert_rate_absolute = bool(
        evidence_sufficient
        and metric(candidate_metrics, "selected_false_alerts_per_day")
        <= ALERT_VALIDATION_MAX_FALSE_ALERTS_PER_DAY
    )
    false_alert_rate_comparator_safe = bool(
        evidence_sufficient
        and all(
            metric(candidate_metrics, "selected_false_alerts_per_day")
            <= metric(values, "selected_false_alerts_per_day")
            + ALERT_VALIDATION_FALSE_ALERT_TOLERANCE_PER_DAY
            for values in comparators
        )
    )
    median_lead_absolute = bool(
        evidence_sufficient
        and metric(candidate_metrics, "low_selected_median_lead_minutes")
        >= ALERT_VALIDATION_MIN_MEDIAN_LEAD_MINUTES
        and metric(candidate_metrics, "high_selected_median_lead_minutes")
        >= ALERT_VALIDATION_MIN_MEDIAN_LEAD_MINUTES
    )

    def lead_comparator_safe(name: str) -> bool:
        candidate = metric(candidate_metrics, name)
        for values in comparators:
            comparator = metric(values, name)
            if math.isfinite(comparator) and (
                candidate
                < comparator - ALERT_VALIDATION_LEAD_TOLERANCE_MINUTES
            ):
                return False
        return True

    median_lead_comparator_safe = bool(
        evidence_sufficient
        and lead_comparator_safe("low_selected_median_lead_minutes")
        and lead_comparator_safe("high_selected_median_lead_minutes")
    )
    result: dict[str, bool | float | int] = {
        "finite": bool(finite),
        "cohort_consistent": bool(cohort_consistent),
        "metrics_consistent": metrics_consistent,
        "evidence_sufficient": evidence_sufficient,
        "low_recall_absolute": low_recall_absolute,
        "high_recall_absolute": high_recall_absolute,
        "low_recall_comparator_safe": low_recall_comparator_safe,
        "high_recall_comparator_safe": high_recall_comparator_safe,
        "missed_low_absolute": missed_low_absolute,
        "missed_low_comparator_safe": missed_low_comparator_safe,
        "false_alert_rate_absolute": false_alert_rate_absolute,
        "false_alert_rate_comparator_safe": false_alert_rate_comparator_safe,
        "median_lead_absolute": median_lead_absolute,
        "median_lead_comparator_safe": median_lead_comparator_safe,
        "minimum_low_recall": ALERT_VALIDATION_MIN_LOW_RECALL,
        "minimum_high_recall": ALERT_VALIDATION_MIN_HIGH_RECALL,
        "maximum_missed_low_episodes": (
            ALERT_VALIDATION_MAX_MISSED_LOW_EPISODES
        ),
        "maximum_selected_false_alerts_per_day": (
            ALERT_VALIDATION_MAX_FALSE_ALERTS_PER_DAY
        ),
        "minimum_median_lead_minutes": (
            ALERT_VALIDATION_MIN_MEDIAN_LEAD_MINUTES
        ),
        "accepted": False,
    }
    result["accepted"] = bool(
        evidence_sufficient
        and low_recall_absolute
        and high_recall_absolute
        and low_recall_comparator_safe
        and high_recall_comparator_safe
        and missed_low_absolute
        and missed_low_comparator_safe
        and false_alert_rate_absolute
        and false_alert_rate_comparator_safe
        and median_lead_absolute
        and median_lead_comparator_safe
    )
    return result


def _alert_approval_envelope_is_valid(approval: dict[str, Any]) -> bool:
    """Validate the checksummed alert claim independently of forecast approval."""

    if approval.get("alert_approved") is not True:
        return True
    validation = approval.get("alert_validation")
    if not isinstance(validation, dict):
        return False
    thresholds = validation.get("thresholds")
    candidate = validation.get("candidate_metrics")
    reference = validation.get("reference_metrics")
    pinned = validation.get("pinned_metrics")
    current = validation.get("current_metrics")
    gates = validation.get("gates")
    selected_days = approval.get("selected_local_days")
    if not all(
        isinstance(item, dict)
        for item in (thresholds, candidate, reference, pinned, current, gates)
    ):
        return False
    if (
        validation.get("protocol") != ALERT_VALIDATION_PROTOCOL
        or thresholds != _alert_validation_thresholds()
        or not isinstance(selected_days, list)
        or len(selected_days) != STATIC_PROSPECTIVE_MIN_DAYS
        or any(not isinstance(day, int) for day in selected_days)
        or selected_days != sorted(set(selected_days))
        or not isinstance(approval.get("local_days_sha256"), str)
        or len(approval.get("local_days_sha256", "")) != 64
        or hashlib.sha256(
            ",".join(str(day) for day in selected_days).encode("ascii")
        ).hexdigest()
        != approval.get("local_days_sha256")
        or validation.get("local_days_sha256")
        != approval.get("local_days_sha256")
        or int(_finite(validation.get("cohort_start_ms"), -1))
        != int(_finite(approval.get("cohort_start_ms"), -2))
        or int(_finite(validation.get("cohort_end_ms"), -1))
        != int(_finite(approval.get("cohort_end_ms"), -2))
        or int(_finite(validation.get("dense_days"), -1))
        != STATIC_PROSPECTIVE_MIN_DAYS
        or int(_finite(candidate.get("evaluated_anchors"), -1))
        != int(_finite(approval.get("alert_validation_anchors"), -2))
    ):
        return False
    computed = _alert_validation_gates(candidate, reference, pinned, current)
    return gates == computed and computed.get("accepted") is True


def _static_reliability(
    candidate_metrics: dict[str, float | None],
    reference_metrics: dict[str, float | None],
    gates: dict[str, bool | float | int],
    *,
    test_days: int,
    independent_anchors: int,
    candidate_horizon_mae: Sequence[float],
    reference_horizon_mae: Sequence[float],
) -> dict[str, Any]:
    candidate_errors = np.asarray(candidate_horizon_mae, dtype=np.float64)
    reference_errors = np.asarray(reference_horizon_mae, dtype=np.float64)
    if (
        candidate_errors.shape != (HORIZON_STEPS,)
        or reference_errors.shape != (HORIZON_STEPS,)
        or not np.isfinite(candidate_errors).all()
        or not np.isfinite(reference_errors).all()
    ):
        raise ValueError("reliability requires 24 finite horizon errors")
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
    evidence_fraction = _clamp(
        independent_anchors / max(1, test_days * 12), 0.0, 1.0
    )
    overall = confidence_cap * (
        0.45 * reference_skill
        + 0.25 * calibration_skill
        + 0.20 * winning_fraction
        + 0.10 * evidence_fraction
    )
    overall = _clamp(overall, 0.05, confidence_cap)
    by_horizon = [
        _clamp(
            overall
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
            candidate_errors, reference_errors
        )
    ]
    return {
        "overall": overall,
        "by_horizon": by_horizon,
        "clinical_validation": False,
        "test_day_cap": confidence_cap,
    }


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


def _static_predictor_hash(parameters: dict[str, Any]) -> str:
    """Hash only fields that can affect a frozen prediction or interval.

    Prospective evaluation is allowed to replace the signed approval/evaluation
    envelope, but it must not mutate the network, blend, event priors, or frozen
    calibration.  Keeping this digest inside the outer checksummed artifact makes
    that boundary independently auditable.
    """

    payload = {key: value for key, value in parameters.items() if key != "artifact"}
    canonical = json.dumps(
        payload, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _model_parameters_hash(parameters: dict[str, Any]) -> str:
    """Canonical digest for an exact frozen comparator parameter document."""

    canonical = json.dumps(
        parameters, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _static_runtime_dependency_envelope_is_valid(
    approval: dict[str, Any],
) -> bool:
    """Keep runtime compatibility flat while preserving comparator provenance.

    A candidate may be evaluated against the active champion, but making that
    champion a transitive runtime dependency creates an ever-growing chain that
    eventually becomes impossible to validate. Static artifacts therefore bind
    inference compatibility directly to the code-owned baseline. The separately
    checksummed ``pinned_comparator_*`` fields remain evaluation provenance.
    """

    return bool(
        approval.get("runtime_dependency_version") == BASELINE_VERSION
        and approval.get("runtime_dependency_sha256")
        == _model_parameters_hash(_baseline_parameters())
    )


def _static_artifact_is_valid(
    parameters: dict[str, Any], *, require_approved: bool = True
) -> bool:
    artifact = parameters.get("artifact")
    network = parameters.get("network")
    model_selection = parameters.get("model_selection")
    calibration = parameters.get("frozen_calibration")
    reliability = artifact.get("reliability") if isinstance(artifact, dict) else None
    evaluation = artifact.get("evaluation") if isinstance(artifact, dict) else None
    split = artifact.get("split") if isinstance(artifact, dict) else None
    approval = artifact.get("approval") if isinstance(artifact, dict) else None
    reference_configuration = parameters.get("reference_configuration")
    event_personalization_context = parameters.get("event_personalization_context")
    if not all(
        isinstance(item, dict)
        for item in (
            artifact,
            network,
            model_selection,
            calibration,
            reliability,
            evaluation,
            split,
            reference_configuration,
            event_personalization_context,
        )
    ):
        return False
    expected = artifact.get("content_sha256")
    predictor_hash = artifact.get("predictor_sha256")
    try:
        computed_hash = _artifact_content_hash(parameters)
    except (TypeError, ValueError, OverflowError):
        # ``json.loads`` accepts non-standard NaN/Infinity tokens. A corrupt
        # persisted artifact must fail closed instead of breaking status/current.
        return False
    blend = _validated_vector(parameters.get("persistence_blend_weights"))
    bias = _validated_vector(calibration.get("bias_mg_dl"))
    sigma = _validated_vector(calibration.get("sigma_mg_dl"), positive=True)
    reference_sigma = _validated_vector(
        calibration.get("reference_sigma_mg_dl"), positive=True
    )
    residual_sigma = _validated_vector(parameters.get("residual_sigma"), positive=True)
    calibration_samples = int(_finite(calibration.get("sample_count"), 0))
    expected_quantile = (
        _finite_sample_quantile_level(calibration_samples)
        if calibration_samples > 0
        else -1.0
    )
    if not (
        isinstance(expected, str)
        and len(expected) == 64
        and expected == computed_hash
        and isinstance(predictor_hash, str)
        and len(predictor_hash) == 64
        and predictor_hash == _static_predictor_hash(parameters)
        and parameters.get("architecture") == STATIC_PERSONAL_ARCHITECTURE
        and parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA
        and parameters.get("kind") == "personalized_static_generic_residual"
        and parameters.get("prediction_reference") == STATIC_REFERENCE_KIND
        and reference_configuration.get("quality_gated") is True
        and math.isclose(
            _finite(reference_configuration.get("trend_decay_minutes"), -1.0),
            STATIC_TREND_DECAY_MINUTES,
            abs_tol=1e-12,
        )
        and int(_finite(reference_configuration.get("trend_lookback_minutes"), -1))
        == STATIC_TREND_LOOKBACK_MINUTES
        and artifact.get("artifact_version") == STATIC_ARTIFACT_VERSION
        and artifact.get("engine_version") == FORECAST_ENGINE_VERSION
        and artifact.get("architecture") == STATIC_PERSONAL_ARCHITECTURE
        and artifact.get("feature_schema") == STATIC_FEATURE_SCHEMA
        and artifact.get("network_kind") == STATIC_NETWORK_KIND
        and artifact.get("reference_kind") == STATIC_REFERENCE_KIND
        and artifact.get("training_mode") == STATIC_TRAINING_MODE
        and artifact.get("promotion_gate_version")
        == STATIC_PROMOTION_GATE_VERSION
        and (not require_approved or artifact.get("accepted") is True)
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
        and np.allclose(bias, 0.0, rtol=0.0, atol=1e-12)
        and sigma is not None
        and reference_sigma is not None
        and residual_sigma is not None
        and np.allclose(sigma, residual_sigma, rtol=0.0, atol=1e-9)
        and calibration.get("method") == "frozen-uncentered-conformal-v2"
        and calibration.get("quantile_method") == "exact-order-statistic"
        and calibration.get("point_bias") == "disabled"
        and math.isclose(
            _finite(calibration.get("low_guard_threshold_mg_dl"), -1.0),
            STATIC_LOW_GUARD_MG_DL,
            abs_tol=1e-9,
        )
        and _static_calibration_scope_is_valid(calibration)
        and math.isclose(
            _finite(calibration.get("interval_level"), -1.0),
            STATIC_INTERVAL_LEVEL,
            abs_tol=1e-9,
        )
        and calibration_samples >= VALIDATION_WINDOWS
        and math.isclose(
            _finite(calibration.get("finite_sample_quantile"), -1.0),
            expected_quantile,
            abs_tol=1e-12,
        )
        and int(_finite(calibration.get("finite_sample_rank"), -1))
        == min(
            calibration_samples,
            math.ceil((calibration_samples + 1) * STATIC_INTERVAL_LEVEL),
        )
        and parameters.get("network_disabled_event_channels")
        == ["meal", "rapid", "long"]
        and combined_event_personalization_is_valid(
            parameters.get("event_personalization")
        )
        and event_personalization_context.get("label_mode")
        == (
            STATIC_EVENT_LABELS_RETROSPECTIVE
            if calibration.get("safety_envelope") == STATIC_DISPLAY_SAFETY_ENVELOPE
            else STATIC_EVENT_LABELS_CAUSAL
        )
        and int(_finite(event_personalization_context.get("label_cutoff_ms"), -1))
        == int(_finite(artifact.get("data_cutoff_ms"), -2))
        and 0
        < int(_finite(event_personalization_context.get("last_training_target_at_ms"), -1))
        < int(_finite(event_personalization_context.get("first_tuning_anchor_at_ms"), -1))
        <= int(_finite(artifact.get("data_cutoff_ms"), -2))
        and int(_finite(event_personalization_context.get("training_window_count"), -1))
        == int(_finite(artifact.get("sample_count"), -2))
        and isinstance(event_personalization_context.get("training_windows_sha256"), str)
        and len(event_personalization_context["training_windows_sha256"]) == 64
    ):
        return False
    declared_knots = artifact.get("shrinkage_knots")
    if not isinstance(declared_knots, list) or len(declared_knots) != len(
        STATIC_SHRINK_KNOT_MINUTES
    ):
        return False
    try:
        artifact_knot_minutes = [int(item["minute"]) for item in declared_knots]
        artifact_knot_weights = [float(item["weight"]) for item in declared_knots]
        expected_blend = _static_shrinkage_curve(artifact_knot_weights)
    except (KeyError, TypeError, ValueError):
        return False
    if (
        artifact_knot_minutes != list(STATIC_SHRINK_KNOT_MINUTES)
        or not np.allclose(blend, expected_blend, atol=1e-12, rtol=0.0)
    ):
        return False
    try:
        x_mean = np.asarray(network["x_mean"], dtype=np.float64)
        x_scale = np.asarray(network["x_scale"], dtype=np.float64)
        coefficients = np.asarray(network["coefficients"], dtype=np.float64)
        intercept = np.asarray(network["intercept"], dtype=np.float64)
    except (KeyError, TypeError, ValueError):
        return False
    tensors = (x_mean, x_scale, coefficients, intercept)
    parameter_count = int(coefficients.size + intercept.size)
    if (
        x_mean.shape != (STATIC_FEATURE_COUNT,)
        or x_scale.shape != (STATIC_FEATURE_COUNT,)
        or np.any(x_scale <= 1e-8)
        or not any(
            math.isclose(
                _finite(network.get("alpha"), -1.0), candidate, abs_tol=1e-12
            )
            for candidate in STATIC_RIDGE_ALPHAS
        )
        or not math.isclose(
            _finite(network.get("horizon_smoothness"), -1.0),
            STATIC_HORIZON_SMOOTHNESS,
            abs_tol=1e-12,
        )
        or coefficients.shape != (STATIC_FEATURE_COUNT, HORIZON_STEPS)
        or intercept.shape != (HORIZON_STEPS,)
        or parameter_count != int(artifact.get("parameter_count"))
        or any(not np.isfinite(tensor).all() for tensor in tensors)
    ):
        return False
    candidates = model_selection.get("candidates")
    try:
        candidate_alphas = [float(item["alpha"]) for item in candidates]
        candidate_losses = [float(item["tuning_mae"]) for item in candidates]
        candidate_shrink_knots = [
            [float(value) for value in item["shrink_knots"]] for item in candidates
        ]
    except (KeyError, TypeError, ValueError):
        return False
    selected_index = min(
        range(len(STATIC_RIDGE_ALPHAS)),
        key=lambda index: (candidate_losses[index], -candidate_alphas[index]),
    ) if isinstance(candidates, list) and len(candidates) == len(STATIC_RIDGE_ALPHAS) else -1
    if (
        model_selection.get("protocol")
        != "chronological-tuning-only-smooth-shrink-ridge-grid-v2"
        or model_selection.get("criterion")
        != "lowest_tuning_mae_then_stronger_regularization"
        or candidate_alphas != list(STATIC_RIDGE_ALPHAS)
        or selected_index < 0
        or any(not math.isfinite(value) or value < 0.0 for value in candidate_losses)
        or any(
            len(values) != len(STATIC_SHRINK_KNOT_MINUTES)
            or any(value not in set(STATIC_SHRINK_GRID) for value in values)
            or any(later > earlier for earlier, later in zip(values, values[1:]))
            for values in candidate_shrink_knots
        )
        or not math.isclose(
            _finite(model_selection.get("selected_alpha"), -1.0),
            candidate_alphas[selected_index],
            abs_tol=1e-12,
        )
        or not math.isclose(
            _finite(model_selection.get("selected_tuning_mae"), -1.0),
            candidate_losses[selected_index],
            abs_tol=1e-12,
        )
        or not math.isclose(
            _finite(network.get("alpha"), -1.0),
            candidate_alphas[selected_index],
            abs_tol=1e-12,
        )
        or not np.allclose(
            blend,
            _static_shrinkage_curve(candidate_shrink_knots[selected_index]),
            atol=1e-12,
            rtol=0.0,
        )
    ):
        return False
    if (
        require_approved
        and isinstance(approval, dict)
        and approval.get("state") == "exploratory_retrospective_display"
    ):
        if calibration.get("safety_envelope") != STATIC_DISPLAY_SAFETY_ENVELOPE:
            return False
        return _static_display_approval_is_valid(
            artifact=artifact,
            evaluation=evaluation,
            reliability=reliability,
            approval=approval,
            predictor_hash=predictor_hash,
        )
    required_evaluation = (
        "accepted",
        "candidate_equal_day_mae",
        "reference_equal_day_mae",
        "pinned_equal_day_mae",
        "candidate_anchor_mae",
        "reference_anchor_mae",
        "pinned_anchor_mae",
        "candidate_rmse",
        "reference_rmse",
        "pinned_rmse",
        "candidate_coverage_80",
        "candidate_interval_score_80",
        "reference_interval_score_80",
        "pinned_interval_score_80",
        "candidate_mae_5",
        "candidate_mae_15",
        "candidate_mae_30",
        "candidate_mae_60",
        "candidate_mae_120",
        "reference_mae_5",
        "reference_mae_15",
        "reference_mae_30",
        "reference_mae_60",
        "reference_mae_120",
        "pinned_mae_5",
        "pinned_mae_15",
        "pinned_mae_30",
        "pinned_mae_60",
        "pinned_mae_120",
        "candidate_hypo_recall",
        "reference_hypo_recall",
        "pinned_hypo_recall",
        "candidate_hypo_fpr",
        "reference_hypo_fpr",
        "pinned_hypo_fpr",
        "candidate_hypo_missed_episodes",
        "reference_hypo_missed_episodes",
        "pinned_hypo_missed_episodes",
        "candidate_low_zone_mae",
        "reference_low_zone_mae",
        "pinned_low_zone_mae",
        "hypo_low_points",
        "hypo_low_episodes",
        "hypo_low_days",
        "test_days",
        "test_independent_anchors",
        "winning_days",
        "gate_reference_equal_day_improvement",
        "gate_pinned_equal_day_improvement",
        "gate_required_winning_days",
        "gate_median_day_improvement",
    )
    required_gate_flags = (
        "gate_no_day_regression_over_2pct",
        "gate_horizons_safe",
        "gate_coverage_safe",
        "gate_hypo_evidence_sufficient",
        "gate_hypo_recall_safe",
        "gate_hypo_episode_safe",
        "gate_hypo_false_alarm_safe",
        "gate_low_zone_safe",
        "gate_hypo_safe",
        "gate_interval_score_safe",
        "gate_rmse_safe",
        "gate_anchor_mae_safe",
        "gate_trajectory_continuity_safe",
        "gate_strong_trend_preserved",
    )
    required_evaluation = (
        required_evaluation
        + tuple(
            f"candidate_coverage_band_{index}"
            for index in range(len(STATIC_BANDS))
        )
        + tuple(
            f"{prefix}_{metric_name}"
            for prefix in ("candidate", "reference", "pinned")
            for metric_name in STATIC_TRAJECTORY_METRICS
        )
        + required_gate_flags
    )
    expected_reliability: dict[str, Any] | None = None
    if require_approved:
        if (
            not isinstance(approval, dict)
            or calibration.get("safety_envelope") != STATIC_ALERT_SAFETY_ENVELOPE
            or approval.get("state") != "approved_prospective"
            or approval.get("protocol") != STATIC_PROSPECTIVE_PROTOCOL
            or not _alert_approval_envelope_is_valid(approval)
            or int(_finite(approval.get("minimum_new_days"), -1))
            != STATIC_PROSPECTIVE_MIN_DAYS
            or int(_finite(approval.get("strictly_after_ms"), -1))
            != int(_finite(artifact.get("data_cutoff_ms"), -2))
            or int(_finite(approval.get("dense_days"), -1))
            != STATIC_PROSPECTIVE_MIN_DAYS
            or int(_finite(approval.get("independent_anchors"), -1))
            != int(_finite(evaluation.get("test_independent_anchors"), -2))
            or int(_finite(approval.get("cohort_start_ms"), -1))
            <= int(_finite(artifact.get("data_cutoff_ms"), -1))
            or int(_finite(approval.get("cohort_end_ms"), -1))
            <= int(_finite(approval.get("cohort_start_ms"), -1))
            or approval.get("predictor_sha256") != predictor_hash
            or not _static_runtime_dependency_envelope_is_valid(approval)
            or not isinstance(approval.get("pinned_comparator_version"), str)
            or approval.get("pinned_comparator_version")
            == artifact.get("model_version")
            or not isinstance(approval.get("pinned_comparator_sha256"), str)
            or len(approval.get("pinned_comparator_sha256", "")) != 64
            or evaluation.get("prospective") != 1
            or evaluation.get("inconclusive") != 0
            or evaluation.get("current_comparator_gate_passed") != 1
            or evaluation.get("accepted") != 1
            or evaluation.get("gate_hypo_safe") != 1
            or any(
                key not in evaluation
                or not math.isfinite(_finite(evaluation.get(key), math.nan))
                for key in required_evaluation
                if key != "accepted"
            )
        ):
            return False
    elif evaluation.get("accepted") not in {0, 1}:
        return False
    if require_approved:
        candidate_metrics = approval.get("candidate_metrics")
        reference_metrics = approval.get("reference_metrics")
        pinned_metrics = approval.get("pinned_metrics")
        current_metrics = approval.get("current_metrics")
        pinned_days = approval.get("pinned_day_results")
        current_days = approval.get("current_day_results")
        if not all(
            isinstance(item, dict)
            for item in (
                candidate_metrics,
                reference_metrics,
                pinned_metrics,
                current_metrics,
            )
        ) or not all(isinstance(item, list) for item in (pinned_days, current_days)):
            return False
        test_days = int(_finite(evaluation.get("test_days"), -1))
        if (
            test_days != STATIC_PROSPECTIVE_MIN_DAYS
            or int(_finite(evaluation.get("test_independent_anchors"), 0))
            < 8 * test_days
        ):
            return False
        try:
            frozen_gates = ForecastService.static_promotion_gates(
                candidate_metrics,
                reference_metrics,
                pinned_metrics,
                pinned_days,
                test_day_count=test_days,
            )
            current_gates = ForecastService.static_promotion_gates(
                candidate_metrics,
                reference_metrics,
                current_metrics,
                current_days,
                test_day_count=test_days,
            )
        except (KeyError, TypeError, ValueError, IndexError):
            return False
        if not bool(frozen_gates.get("accepted")) or not bool(
            current_gates.get("accepted")
        ):
            return False
        metric_consistency = {
            "candidate_anchor_mae": candidate_metrics.get("mae"),
            "reference_anchor_mae": reference_metrics.get("mae"),
            "pinned_anchor_mae": pinned_metrics.get("mae"),
            "candidate_rmse": candidate_metrics.get("rmse"),
            "reference_rmse": reference_metrics.get("rmse"),
            "pinned_rmse": pinned_metrics.get("rmse"),
            "candidate_coverage_80": candidate_metrics.get("coverage_80"),
            "candidate_interval_score_80": candidate_metrics.get(
                "interval_score_80"
            ),
            "reference_interval_score_80": reference_metrics.get(
                "interval_score_80"
            ),
            "pinned_interval_score_80": pinned_metrics.get("interval_score_80"),
            "candidate_hypo_recall": candidate_metrics.get("hypo_recall"),
            "reference_hypo_recall": reference_metrics.get("hypo_recall"),
            "pinned_hypo_recall": pinned_metrics.get("hypo_recall"),
            "candidate_hypo_fpr": candidate_metrics.get("hypo_fpr"),
            "reference_hypo_fpr": reference_metrics.get("hypo_fpr"),
            "pinned_hypo_fpr": pinned_metrics.get("hypo_fpr"),
            "candidate_hypo_missed_episodes": candidate_metrics.get(
                "hypo_missed_episodes"
            ),
            "reference_hypo_missed_episodes": reference_metrics.get(
                "hypo_missed_episodes"
            ),
            "pinned_hypo_missed_episodes": pinned_metrics.get(
                "hypo_missed_episodes"
            ),
            "candidate_low_zone_mae": candidate_metrics.get("low_zone_mae"),
            "reference_low_zone_mae": reference_metrics.get("low_zone_mae"),
            "pinned_low_zone_mae": pinned_metrics.get("low_zone_mae"),
            "hypo_low_points": candidate_metrics.get("hypo_low_points"),
            "hypo_low_episodes": candidate_metrics.get("hypo_low_episodes"),
            "hypo_low_days": candidate_metrics.get("hypo_low_days"),
        }
        for band_index in range(len(STATIC_BANDS)):
            metric_consistency[f"candidate_coverage_band_{band_index}"] = (
                candidate_metrics.get(f"coverage_band_{band_index}")
            )
        for prefix, metrics in (
            ("candidate", candidate_metrics),
            ("reference", reference_metrics),
            ("pinned", pinned_metrics),
        ):
            for horizon in (5, 15, 30, 60, 120):
                metric_consistency[f"{prefix}_mae_{horizon}"] = metrics.get(
                    f"mae_{horizon}"
                )
            for metric_name in STATIC_TRAJECTORY_METRICS:
                metric_consistency[f"{prefix}_{metric_name}"] = metrics.get(
                    metric_name
                )
        if any(
            expected_value is None
            or not math.isclose(
                _finite(evaluation.get(key), math.nan),
                _finite(expected_value, math.nan),
                rel_tol=0.0,
                abs_tol=1e-9,
            )
            for key, expected_value in metric_consistency.items()
        ):
            return False
        gate_consistency = {
            "candidate_equal_day_mae": frozen_gates.get(
                "candidate_equal_day_mae"
            ),
            "reference_equal_day_mae": frozen_gates.get(
                "reference_equal_day_mae"
            ),
            "pinned_equal_day_mae": frozen_gates.get("pinned_equal_day_mae"),
            "winning_days": frozen_gates.get("winning_days"),
            "gate_reference_equal_day_improvement": frozen_gates.get(
                "reference_equal_day_improvement"
            ),
            "gate_pinned_equal_day_improvement": frozen_gates.get(
                "pinned_equal_day_improvement"
            ),
            "gate_required_winning_days": frozen_gates.get(
                "required_winning_days"
            ),
            "gate_median_day_improvement": frozen_gates.get(
                "median_day_improvement"
            ),
        }
        if any(
            expected_value is None
            or not math.isclose(
                _finite(evaluation.get(key), math.nan),
                _finite(expected_value, math.nan),
                rel_tol=0.0,
                abs_tol=1e-9,
            )
            for key, expected_value in gate_consistency.items()
        ):
            return False
        if any(evaluation.get(key) != 1 for key in required_gate_flags):
            return False
        try:
            expected_reliability = _static_reliability(
                candidate_metrics,
                reference_metrics,
                frozen_gates,
                test_days=test_days,
                independent_anchors=int(
                    _finite(evaluation.get("test_independent_anchors"), 0)
                ),
                candidate_horizon_mae=approval["candidate_horizon_mae"],
                reference_horizon_mae=approval["reference_horizon_mae"],
            )
        except (KeyError, TypeError, ValueError, IndexError):
            return False
    overall = _finite(reliability.get("overall"), -1.0)
    test_day_cap = _finite(reliability.get("test_day_cap"), -1.0)
    evaluated_days = int(_finite(evaluation.get("test_days"), 0))
    expected_cap = 0.35 if evaluated_days < 8 else (0.50 if evaluated_days < 14 else 0.65)
    by_horizon = reliability.get("by_horizon")
    basic_reliability_valid = bool(
        math.isclose(test_day_cap, expected_cap, abs_tol=1e-12)
        and 0.0 <= overall <= test_day_cap
        and reliability.get("clinical_validation") is False
        and isinstance(by_horizon, list)
        and len(by_horizon) == HORIZON_STEPS
        and all(0.0 <= _finite(item, -1.0) <= test_day_cap for item in by_horizon)
    )
    if not basic_reliability_valid or expected_reliability is None:
        return basic_reliability_valid and not require_approved
    expected_by_horizon = expected_reliability["by_horizon"]
    return bool(
        math.isclose(
            overall,
            float(expected_reliability["overall"]),
            rel_tol=0.0,
            abs_tol=1e-12,
        )
        and math.isclose(
            test_day_cap,
            float(expected_reliability["test_day_cap"]),
            rel_tol=0.0,
            abs_tol=1e-12,
        )
        and isinstance(expected_by_horizon, list)
        and all(
            math.isclose(
                _finite(actual, math.nan),
                float(expected),
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            for actual, expected in zip(by_horizon, expected_by_horizon)
        )
    )


def _static_display_approval_is_valid(
    *,
    artifact: dict[str, Any],
    evaluation: dict[str, Any],
    reliability: dict[str, Any],
    approval: dict[str, Any],
    predictor_hash: str,
) -> bool:
    """Validate a checksummed retrospective approval for chart display only."""

    test_days = int(_finite(evaluation.get("test_days"), -1))
    causal_replay = artifact.get("receipt_causal_replay")
    snapshot = artifact.get("snapshot")
    raw_source_hash = snapshot.get("raw_source_sha256") if isinstance(snapshot, dict) else None
    if not (
        isinstance(raw_source_hash, str)
        and len(raw_source_hash) == 64
        and all(character in "0123456789abcdef" for character in raw_source_hash)
    ):
        return False
    if not isinstance(causal_replay, dict) or any(
        type(causal_replay.get(key)) is not int or causal_replay[key] < 0
        for key in (
            "window_count",
            "local_day_count",
            "causal_history_rejections",
            "causal_target_rejections",
            "causal_stale_anchor_rejections",
        )
    ):
        return False
    receipt_evidence_sufficient = bool(
        causal_replay["window_count"] >= 32 and causal_replay["local_day_count"] >= 4
    )
    independent_anchors = int(
        _finite(evaluation.get("test_independent_anchors"), -1)
    )
    if (
        approval.get("protocol") != STATIC_DISPLAY_PROTOCOL
        or approval.get("alert_approved") is not False
        or approval.get("unbiased_holdout") is not False
        or approval.get("receipt_causal_validation") is not False
        or approval.get("validation_clock") != "sensor_measured_at"
        or approval.get("receipt_causal_evidence_required") is not False
        or approval.get("use_scope") != "chart_only_not_for_dosing_or_alerts"
        or not _static_runtime_dependency_envelope_is_valid(approval)
        or approval.get("predictor_sha256") != predictor_hash
        or approval.get("approved_model_version") != artifact.get("model_version")
        or int(_finite(approval.get("test_days"), -1)) != test_days
        or int(_finite(approval.get("independent_anchors"), -1))
        != independent_anchors
        or test_days != STATIC_TEST_DAYS
        or independent_anchors < 8 * STATIC_TEST_DAYS
        or evaluation.get("accepted") != 1
        or evaluation.get("display_only") != 1
        or evaluation.get("exploratory") != 1
        or evaluation.get("unbiased_holdout") != 0
        or evaluation.get("receipt_causal_validation") != 0
        or evaluation.get("prospective") != 0
        or evaluation.get("prospective_pending") != 0
        or evaluation.get("gate_display_only") != 1
        or evaluation.get("receipt_causal_gate_required") != 0
        or evaluation.get("gate_receipt_causal_evidence_sufficient")
        != int(receipt_evidence_sufficient)
        or causal_replay.get("role") != "historical_availability_diagnostic"
        or causal_replay.get("source_snapshot_sha256") != raw_source_hash
        or causal_replay.get("validated_for_activation") is not False
        or causal_replay["local_day_count"] > causal_replay["window_count"]
        or not isinstance(approval.get("pinned_comparator_version"), str)
        or approval.get("pinned_comparator_version") == artifact.get("model_version")
        or not isinstance(approval.get("pinned_comparator_sha256"), str)
        or len(approval.get("pinned_comparator_sha256", "")) != 64
    ):
        return False
    candidate_metrics = approval.get("candidate_metrics")
    reference_metrics = approval.get("reference_metrics")
    pinned_metrics = approval.get("pinned_metrics")
    day_results = approval.get("day_results")
    if not all(
        isinstance(item, dict)
        for item in (candidate_metrics, reference_metrics, pinned_metrics)
    ) or not isinstance(day_results, list):
        return False
    try:
        gates = ForecastService.static_display_gates(
            candidate_metrics,
            reference_metrics,
            pinned_metrics,
            day_results,
            test_day_count=test_days,
        )
    except (KeyError, TypeError, ValueError, IndexError, OverflowError):
        return False
    if not bool(gates.get("accepted")):
        return False

    metric_consistency: dict[str, Any] = {
        "candidate_anchor_mae": candidate_metrics.get("mae"),
        "reference_anchor_mae": reference_metrics.get("mae"),
        "pinned_anchor_mae": pinned_metrics.get("mae"),
        "candidate_rmse": candidate_metrics.get("rmse"),
        "reference_rmse": reference_metrics.get("rmse"),
        "pinned_rmse": pinned_metrics.get("rmse"),
        "candidate_coverage_80": candidate_metrics.get("coverage_80"),
        "candidate_interval_score_80": candidate_metrics.get("interval_score_80"),
        "reference_interval_score_80": reference_metrics.get("interval_score_80"),
        "pinned_interval_score_80": pinned_metrics.get("interval_score_80"),
        "candidate_hypo_recall": candidate_metrics.get("hypo_recall"),
        "reference_hypo_recall": reference_metrics.get("hypo_recall"),
        "pinned_hypo_recall": pinned_metrics.get("hypo_recall"),
        "candidate_hypo_fpr": candidate_metrics.get("hypo_fpr"),
        "reference_hypo_fpr": reference_metrics.get("hypo_fpr"),
        "pinned_hypo_fpr": pinned_metrics.get("hypo_fpr"),
        "candidate_hypo_missed_episodes": candidate_metrics.get(
            "hypo_missed_episodes"
        ),
        "reference_hypo_missed_episodes": reference_metrics.get(
            "hypo_missed_episodes"
        ),
        "pinned_hypo_missed_episodes": pinned_metrics.get(
            "hypo_missed_episodes"
        ),
        "candidate_low_zone_mae": candidate_metrics.get("low_zone_mae"),
        "reference_low_zone_mae": reference_metrics.get("low_zone_mae"),
        "pinned_low_zone_mae": pinned_metrics.get("low_zone_mae"),
        "hypo_low_points": candidate_metrics.get("hypo_low_points"),
        "hypo_low_episodes": candidate_metrics.get("hypo_low_episodes"),
        "hypo_low_days": candidate_metrics.get("hypo_low_days"),
    }
    for prefix, metrics in (
        ("candidate", candidate_metrics),
        ("reference", reference_metrics),
        ("pinned", pinned_metrics),
    ):
        for horizon in (5, 15, 30, 60, 120):
            metric_consistency[f"{prefix}_mae_{horizon}"] = metrics.get(
                f"mae_{horizon}"
            )
        for metric_name in STATIC_TRAJECTORY_METRICS:
            metric_consistency[f"{prefix}_{metric_name}"] = metrics.get(metric_name)
    for band_index in range(len(STATIC_BANDS)):
        metric_consistency[f"candidate_coverage_band_{band_index}"] = (
            candidate_metrics.get(f"coverage_band_{band_index}")
        )
    if any(
        expected is None
        or not math.isclose(
            _finite(evaluation.get(key), math.nan),
            _finite(expected, math.nan),
            rel_tol=0.0,
            abs_tol=1e-9,
        )
        for key, expected in metric_consistency.items()
    ):
        return False

    direct_gate_keys = {
        "accepted",
        "test_days",
        "candidate_equal_day_mae",
        "reference_equal_day_mae",
        "pinned_equal_day_mae",
        "winning_days",
    }
    for key, value in gates.items():
        if key == "finite":
            continue
        evaluation_key = key if key in direct_gate_keys else f"gate_{key}"
        if isinstance(value, bool):
            if evaluation.get(evaluation_key) != int(value):
                return False
        elif not math.isclose(
            _finite(evaluation.get(evaluation_key), math.nan),
            _finite(value, math.nan),
            rel_tol=0.0,
            abs_tol=1e-9,
        ):
            return False

    candidate_horizon_mae = approval.get("candidate_horizon_mae")
    reference_horizon_mae = approval.get("reference_horizon_mae")
    try:
        expected_reliability = _static_reliability(
            candidate_metrics,
            reference_metrics,
            gates,
            test_days=test_days,
            independent_anchors=independent_anchors,
            candidate_horizon_mae=candidate_horizon_mae,
            reference_horizon_mae=reference_horizon_mae,
        )
    except (KeyError, TypeError, ValueError, IndexError):
        return False
    by_horizon = reliability.get("by_horizon")
    expected_by_horizon = expected_reliability.get("by_horizon")
    return bool(
        reliability.get("clinical_validation") is False
        and math.isclose(
            _finite(reliability.get("overall"), math.nan),
            _finite(expected_reliability.get("overall"), math.nan),
            rel_tol=0.0,
            abs_tol=1e-12,
        )
        and math.isclose(
            _finite(reliability.get("test_day_cap"), math.nan),
            0.35,
            rel_tol=0.0,
            abs_tol=1e-12,
        )
        and math.isclose(
            _finite(reliability.get("test_day_cap"), math.nan),
            _finite(expected_reliability.get("test_day_cap"), math.nan),
            rel_tol=0.0,
            abs_tol=1e-12,
        )
        and isinstance(by_horizon, list)
        and isinstance(expected_by_horizon, list)
        and len(by_horizon) == HORIZON_STEPS
        and len(expected_by_horizon) == HORIZON_STEPS
        and all(
            math.isclose(
                _finite(actual, math.nan),
                _finite(expected, math.nan),
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            for actual, expected in zip(by_horizon, expected_by_horizon)
        )
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


def _static_reference_prediction(
    readings: Sequence[GlucoseReadingRecord],
    events: Sequence[_Event],
    anchor_ms: int,
    parameters: dict[str, Any],
) -> np.ndarray:
    """Quality-gated trend plus group-validated, bounded event amplitudes."""

    current = float(readings[-1].glucose_mg_dl)
    recent = [
        row
        for row in readings
        if anchor_ms - STATIC_TREND_LOOKBACK_MINUTES * 60_000
        <= row.measured_at_ms
        <= anchor_ms
    ]
    reliability = 0.0
    if len(recent) >= 4:
        elapsed_minutes = max(
            0.0, (recent[-1].measured_at_ms - recent[0].measured_at_ms) / 60_000.0
        )
        occupied_bins = len({row.measured_at_ms // STEP_MS for row in recent})
        expected_bins = max(1.0, elapsed_minutes / STEP_MINUTES + 1.0)
        density = _clamp(occupied_bins / expected_bins, 0.0, 1.0)
        mean_quality = float(
            np.mean(
                [
                    _clamp(row.quality if row.quality is not None else 0.75, 0.0, 1.0)
                    for row in recent
                ]
            )
        )
        x = np.asarray(
            [(row.measured_at_ms - recent[-1].measured_at_ms) / 60_000.0 for row in recent],
            dtype=np.float64,
        )
        y = np.asarray([row.glucose_mg_dl for row in recent], dtype=np.float64)
        if float(np.ptp(x)) >= 1.0:
            fitted = np.polyval(np.polyfit(x, y, 1), x)
            linear_rmse = math.sqrt(float(np.mean((y - fitted) ** 2)))
            stability = 1.0 / (1.0 + (linear_rmse / 18.0) ** 2)
        else:
            stability = 0.0
        reliability = _clamp(
            min(1.0, elapsed_minutes / 30.0) * density * mean_quality * stability,
            0.0,
            1.0,
        )

    horizons = (
        np.arange(1, HORIZON_STEPS + 1, dtype=np.float64) * STEP_MINUTES
    )
    slope = _recent_slope(recent or readings) * reliability
    trend_delta = slope * STATIC_TREND_DECAY_MINUTES * (
        1.0 - np.exp(-horizons / STATIC_TREND_DECAY_MINUTES)
    )
    meal, rapid, long = _event_basis(events, anchor_ms, parameters)
    sensitivity = parameters.get("sensitivities", {})
    population_effects = {
        "meal": meal * _finite(sensitivity.get("carb_mg_dl_per_g"), 0.85),
        "rapid": -rapid * _finite(sensitivity.get("rapid_mg_dl_per_unit"), 7.0),
        "long": -long * _finite(sensitivity.get("long_mg_dl_per_unit"), 2.0),
    }
    personalization = parameters.get("event_personalization")
    event_delta = sum(
        apply_bounded_event_personalization(kind, population_effects[kind], personalization)
        for kind in EVENT_KINDS
    )
    return np.clip(current + trend_delta + event_delta, 20.0, 600.0)


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
        if parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA:
            baseline = _static_reference_prediction(
                readings, causal_events, anchor_ms, parameters
            )
        elif parameters.get("prediction_reference") == "event_aware_persistence":
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
    # Static amplitude corrections do not identify an individual event's timing
    # or pharmacokinetics; the separate activity kernel must keep its prior label.
    personalized = bool(
        parameters.get("feature_schema") != STATIC_FEATURE_SCHEMA
        and evidence >= MINIMUM_CLEAN_EVENT_SAMPLES
    )

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


def _fit_network(
    x_train: np.ndarray,
    residual_train: np.ndarray,
    alpha: float = STATIC_RIDGE_ALPHA,
) -> dict[str, Any]:
    """Fit a deterministic regularized direct multi-horizon residual head.

    A linear ridge head is intentionally used here instead of the previous
    small MLP.  With only a few weeks of one-person data, the lower-capacity
    model is substantially easier to audit and less likely to turn an
    unrecorded meal or sensor artifact into a confident non-linear trajectory.
    The event-aware physiological reference remains outside this learned head.
    """

    x_mean = x_train.mean(axis=0)
    x_scale = x_train.std(axis=0)
    x_scale[x_scale < 0.05] = 1.0
    x = np.clip((x_train - x_mean) / x_scale, -8.0, 8.0)
    y = np.clip(residual_train, -180.0, 180.0) @ _static_horizon_smoother()
    intercept = y.mean(axis=0)
    centered = y - intercept.reshape(1, -1)
    gram = x.T @ x
    if not any(math.isclose(alpha, item, abs_tol=1e-12) for item in STATIC_RIDGE_ALPHAS):
        raise ValueError("ridge alpha is outside the preregistered tuning grid")
    regularized = gram + alpha * np.eye(x.shape[1], dtype=np.float64)
    try:
        coefficients = np.linalg.solve(regularized, x.T @ centered)
    except np.linalg.LinAlgError:
        coefficients = np.linalg.pinv(regularized, rcond=1e-10) @ (x.T @ centered)
    return {
        "kind": STATIC_NETWORK_KIND,
        "feature_schema": STATIC_FEATURE_SCHEMA,
        "alpha": alpha,
        "horizon_smoothness": STATIC_HORIZON_SMOOTHNESS,
        "x_mean": x_mean.tolist(),
        "x_scale": x_scale.tolist(),
        "coefficients": coefficients.tolist(),
        "intercept": intercept.tolist(),
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
    def __init__(
        self,
        glucose_commit_listener: Callable[[int, int | None], None] | None = None,
    ) -> None:
        self._training_lock = threading.Lock()
        self._glucose_commit_listener = glucose_commit_listener

    def set_glucose_commit_listener(
        self,
        listener: Callable[[int, int | None], None] | None,
    ) -> None:
        self._glucose_commit_listener = listener

    @staticmethod
    def _stored_static_model_is_valid(
        record: ForecastModelRecord, *, require_approved: bool
    ) -> bool:
        if record.version == BASELINE_VERSION:
            return require_approved
        if record.architecture != STATIC_PERSONAL_ARCHITECTURE:
            return False
        parameters = _json_dict(record.parameters_json)
        if not _static_artifact_is_valid(
            parameters, require_approved=require_approved
        ):
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

    @staticmethod
    def _runtime_model_is_valid(record: ForecastModelRecord) -> bool:
        return ForecastService._stored_static_model_is_valid(
            record, require_approved=True
        )

    @classmethod
    def _runtime_model_dependencies_are_valid(
        cls, session: Session, record: ForecastModelRecord
    ) -> bool:
        del session  # Runtime compatibility is deliberately code-owned and flat.
        if not cls._runtime_model_is_valid(record):
            return False
        if record.version == BASELINE_VERSION:
            return True
        parameters = _json_dict(record.parameters_json)
        approval = parameters.get("artifact", {}).get("approval", {})
        return bool(
            isinstance(approval, dict)
            and _static_runtime_dependency_envelope_is_valid(approval)
        )

    @classmethod
    def _activation_model_provenance_is_valid(
        cls, session: Session, record: ForecastModelRecord
    ) -> bool:
        """Require the exact evaluation comparator when changing the active pin."""

        if not cls._runtime_model_dependencies_are_valid(session, record):
            return False
        if record.version == BASELINE_VERSION:
            return True
        parameters = _json_dict(record.parameters_json)
        approval = parameters.get("artifact", {}).get("approval", {})
        comparator_version = approval.get("pinned_comparator_version")
        comparator_hash = approval.get("pinned_comparator_sha256")
        if (
            not isinstance(comparator_version, str)
            or comparator_version == record.version
            or not isinstance(comparator_hash, str)
        ):
            return False
        if comparator_version == BASELINE_VERSION:
            return comparator_hash == _model_parameters_hash(_baseline_parameters())
        comparator = session.get(ForecastModelRecord, comparator_version)
        return bool(
            comparator is not None
            and _model_parameters_hash(_json_dict(comparator.parameters_json))
            == comparator_hash
        )

    @staticmethod
    def _assert_prospective_registration_allowed(
        session: Session, *, cutoff_ms: int
    ) -> None:
        latest_decided_cohort_end = -1
        for record in session.scalars(
            select(ForecastModelRecord).where(
                ForecastModelRecord.architecture == STATIC_PERSONAL_ARCHITECTURE
            )
        ):
            parameters = _json_dict(record.parameters_json)
            artifact = parameters.get("artifact", {})
            approval = artifact.get("approval") if isinstance(artifact, dict) else None
            if record.status == "pending":
                raise ValueError(
                    "a preregistered prospective forecast candidate is already pending"
                )
            if not isinstance(approval, dict):
                continue
            if approval.get("protocol") != STATIC_PROSPECTIVE_PROTOCOL:
                continue
            latest_decided_cohort_end = max(
                latest_decided_cohort_end,
                int(_finite(approval.get("cohort_end_ms"), -1)),
            )
        if cutoff_ms <= latest_decided_cohort_end:
            raise ValueError(
                "a new prospective candidate must freeze after the prior cohort ended"
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
    def _source_revision(session: Session) -> tuple[int, int, int, int, int, int]:
        """One-statement fingerprint for source data concurrency guards."""

        event_revision = (
            select(func.max(SyncChangeRecord.id)).scalar_subquery()
        )
        active_events = (
            select(func.count(IntakeEventRecord.id))
            .where(IntakeEventRecord.deleted_at_ms.is_(None))
            .scalar_subquery()
        )
        glucose_revision = (
            select(cast(BackendMetadataRecord.value_text, Integer))
            .where(
                BackendMetadataRecord.key
                == GLUCOSE_SOURCE_REVISION_METADATA_KEY
            )
            .scalar_subquery()
        )
        (
            reading_count,
            last_reading,
            max_received,
            sync_revision,
            event_count,
            glucose_source_revision,
        ) = (
            session.execute(
                select(
                    func.count(GlucoseReadingRecord.reading_id),
                    func.max(GlucoseReadingRecord.measured_at_ms),
                    func.max(GlucoseReadingRecord.received_at_ms),
                    event_revision,
                    active_events,
                    glucose_revision,
                )
            ).one()
        )
        return (
            int(reading_count or 0),
            int(last_reading or 0),
            int(max_received or 0),
            int(sync_revision or 0),
            int(event_count or 0),
            int(glucose_source_revision or 0),
        )

    @staticmethod
    def _frozen_source_fingerprint(session: Session, *, cutoff_ms: int) -> str:
        """Hash every source row that belongs to a frozen training interval.

        The global source revision is ideal for viewer reconciliation, but it
        advances for ordinary live appends after a model's cutoff. A training
        commit must ignore only those strictly-future rows while still noticing
        a correction, backfill, tombstone, restore, or deletion anywhere in the
        historical interval. Hash raw rows rather than only the transformed
        training objects so excluded tombstones and late historical intakes are
        covered too. Linked analysis JSON is included because it supplies meal
        context used by ``_load_events``.
        """

        digest = hashlib.sha256()
        digest.update(f"cutoff:{int(cutoff_ms)}\n".encode("ascii"))

        def update_digest(scope: bytes, row: Any) -> None:
            digest.update(scope)
            digest.update(
                json.dumps(
                    tuple(row),
                    ensure_ascii=False,
                    separators=(",", ":"),
                    allow_nan=False,
                ).encode("utf-8")
            )
            digest.update(b"\n")

        reading_rows = session.execute(
            select(
                GlucoseReadingRecord.reading_id,
                GlucoseReadingRecord.measured_at_ms,
                GlucoseReadingRecord.glucose_mg_dl,
                GlucoseReadingRecord.trend_mg_dl_min,
                GlucoseReadingRecord.sensor_id,
                GlucoseReadingRecord.sensor_generation,
                GlucoseReadingRecord.quality,
                GlucoseReadingRecord.utc_offset_minutes,
                GlucoseReadingRecord.payload_hash,
                GlucoseReadingRecord.received_at_ms,
            )
            .where(GlucoseReadingRecord.measured_at_ms <= cutoff_ms)
            .order_by(
                GlucoseReadingRecord.measured_at_ms,
                GlucoseReadingRecord.reading_id,
            )
        )
        for row in reading_rows:
            update_digest(b"reading:", row)

        event_rows = session.execute(
            select(
                IntakeEventRecord.id,
                IntakeEventRecord.client_event_id,
                IntakeEventRecord.occurred_at_ms,
                IntakeEventRecord.meal_text,
                IntakeEventRecord.carbs_g,
                IntakeEventRecord.portion_g,
                IntakeEventRecord.original_portion_g,
                IntakeEventRecord.original_carbs_g,
                IntakeEventRecord.carbs_source,
                IntakeEventRecord.insulin_units,
                IntakeEventRecord.insulin_type,
                IntakeEventRecord.insulin_name,
                IntakeEventRecord.analysis_id,
                IntakeEventRecord.payload_hash,
                IntakeEventRecord.created_at_ms,
                IntakeEventRecord.updated_at_ms,
                IntakeEventRecord.deleted_at_ms,
                IntakeEventRecord.sync_version,
                AnalysisRecord.result_json,
            )
            .outerjoin(
                AnalysisRecord,
                AnalysisRecord.id == IntakeEventRecord.analysis_id,
            )
            .where(IntakeEventRecord.occurred_at_ms <= cutoff_ms)
            .order_by(
                IntakeEventRecord.occurred_at_ms,
                IntakeEventRecord.id,
            )
        )
        for row in event_rows:
            update_digest(b"event:", row)

        return digest.hexdigest()

    def _champion(self, session: Session) -> ForecastModelRecord:
        """Return only the explicit valid pin; otherwise fail closed to baseline."""

        baseline = self._ensure_baseline(session)
        pin = session.get(BackendMetadataRecord, ACTIVE_MODEL_METADATA_KEY)
        if pin is not None:
            selected = session.get(ForecastModelRecord, pin.value_text)
            if selected is not None and self._runtime_model_dependencies_are_valid(
                session, selected
            ):
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
        if not self._activation_model_provenance_is_valid(session, selected):
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
                    and self._runtime_model_dependencies_are_valid(session, selected)
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
        earliest_first: bool = False,
    ) -> list[GlucoseReadingRecord]:
        statement = select(GlucoseReadingRecord)
        if through_ms is not None:
            statement = statement.where(GlucoseReadingRecord.measured_at_ms <= through_ms)
        if from_ms is not None:
            statement = statement.where(GlucoseReadingRecord.measured_at_ms >= from_ms)
        ordering = (
            (
                GlucoseReadingRecord.measured_at_ms.asc(),
                GlucoseReadingRecord.reading_id.asc(),
            )
            if earliest_first
            else (
                GlucoseReadingRecord.measured_at_ms.desc(),
                GlucoseReadingRecord.reading_id.desc(),
            )
        )
        rows = list(session.scalars(statement.order_by(*ordering).limit(limit)))
        if not earliest_first:
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
                        known_at_ms=max(record.created_at_ms, record.updated_at_ms),
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
                        known_at_ms=max(record.created_at_ms, record.updated_at_ms),
                    )
                )
        return events

    def ingest(self, session: Session, payload: GlucoseReadingsCreate) -> GlucoseReadingsResponse:
        inserted = 0
        unchanged = 0
        updated = 0
        context_updated = 0
        source_mutated = False
        corrected_ids: list[str] = []
        committed_revision: int | None = None
        latest_at: int | None = None
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
                    source_mutated = True
                    updated += 1
                    corrected_ids.append(reading_id)
                else:
                    # Rewrite hashes created by the metadata-sensitive preview and
                    # enrich nullable transport context without creating a conflict.
                    unchanged += 1
                    if existing.payload_hash != digest:
                        existing.payload_hash = digest
                        source_mutated = True
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
                    source_mutated = True
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
            source_mutated = True
            inserted += 1
        try:
            if source_mutated:
                # A wall-clock maximum is not a mutation revision: an in-place
                # correction can share its millisecond with the previous write,
                # and clocks can move backwards. This counter advances in the
                # same SQLite transaction as every model-relevant CGM mutation.
                session.execute(
                    text(
                        """
                        INSERT INTO backend_metadata (key, value_text)
                        VALUES (:metadata_key, '1')
                        ON CONFLICT(key) DO UPDATE SET value_text = CAST(
                            CAST(backend_metadata.value_text AS INTEGER) + 1 AS TEXT
                        )
                        """
                    ),
                    {"metadata_key": GLUCOSE_SOURCE_REVISION_METADATA_KEY},
                )
            if updated:
                session.execute(
                    delete(ForecastScoreRecord).where(
                        ForecastScoreRecord.reading_id.in_(corrected_ids)
                    )
                )
            if source_mutated:
                # ``autoflush`` is deliberately disabled for this database.
                # Flush before reading the watermark/latest row, then notify
                # listeners only after the transaction is durably committed.
                session.flush()
                committed_revision = glucose_source_revision(session)
                latest_at = session.scalar(
                    select(func.max(GlucoseReadingRecord.measured_at_ms))
                )
            session.commit()
        except IntegrityError as error:
            session.rollback()
            raise ValueError("a reading identity conflicted during ingestion") from error

        if committed_revision is not None and self._glucose_commit_listener is not None:
            try:
                self._glucose_commit_listener(committed_revision, latest_at)
            except Exception:
                # A disconnected viewer can never make the authoritative phone
                # ingestion fail after its SQLite transaction has committed.
                logger.exception("could not publish durable glucose update")
        if latest_at is None:
            latest_at = session.scalar(
                select(func.max(GlucoseReadingRecord.measured_at_ms))
            )
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

    @classmethod
    def _runtime_forecast_adjustments(
        cls,
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
        anchor_ms: int,
        parameters: dict[str, Any],
    ) -> tuple[str, float, float, np.ndarray, float]:
        """Return the exact quality/meal transform shared by live and replay."""

        quality_status, quality_confidence, coverage = cls._quality_status(
            readings, anchor_ms
        )
        meal_sigma, event_confidence = _meal_event_uncertainty(
            events, anchor_ms, parameters
        )
        return (
            quality_status,
            quality_confidence,
            coverage,
            meal_sigma,
            event_confidence,
        )

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
                    if parameters.get("feature_schema") != STATIC_FEATURE_SCHEMA
                    and int(parameters.get("evidence_counts", {}).get("rapid", 0) or 0)
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
                    if parameters.get("feature_schema") != STATIC_FEATURE_SCHEMA
                    and int(parameters.get("evidence_counts", {}).get("long", 0) or 0)
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

    @classmethod
    def _alert_delivery_is_approved(
        cls, session: Session, record: ForecastModelRecord | None
    ) -> bool:
        """Require a runtime-valid artifact with an explicitly checksummed alert bit."""

        if (
            record is None
            or record.version == BASELINE_VERSION
            or record.status != "champion"
            or not cls._runtime_model_dependencies_are_valid(session, record)
        ):
            return False
        parameters = _json_dict(record.parameters_json)
        approval = parameters.get("artifact", {}).get("approval", {})
        # The runtime validator above recomputes the complete artifact digest,
        # so this exact boolean cannot be added or changed without invalidating
        # the model. Missing flags remain shadow-only for backward compatibility.
        return bool(
            isinstance(approval, dict)
            and parameters.get("frozen_calibration", {}).get("safety_envelope")
            == STATIC_ALERT_SAFETY_ENVELOPE
            and approval.get("state") == "approved_prospective"
            and approval.get("protocol") == STATIC_PROSPECTIVE_PROTOCOL
            and approval.get("alert_approved") is True
            and _alert_approval_envelope_is_valid(approval)
        )

    @staticmethod
    def _based_on_reading(
        session: Session, measured_at_ms: int
    ) -> GlucoseReadingRecord | None:
        return session.scalar(
            select(GlucoseReadingRecord)
            .where(GlucoseReadingRecord.measured_at_ms == measured_at_ms)
            .order_by(GlucoseReadingRecord.reading_id.desc())
        )

    @staticmethod
    def _reading_is_fresh_for_alerts(
        reading: GlucoseReadingRecord | None, now_ms: int
    ) -> bool:
        if reading is None or _safe_glucose_mg_dl(reading.glucose_mg_dl) is None:
            return False
        measurement_age = now_ms - int(reading.measured_at_ms)
        receipt_delay = int(reading.received_at_ms) - int(reading.measured_at_ms)
        receipt_age = now_ms - int(reading.received_at_ms)
        maximum_age_ms = ALERT_DELIVERY_MAX_ANCHOR_AGE_MINUTES * 60_000
        return bool(
            0 <= measurement_age <= maximum_age_ms
            and 0 <= receipt_delay <= maximum_age_ms
            and 0 <= receipt_age <= maximum_age_ms
        )

    @classmethod
    def _run_response(
        cls,
        session: Session,
        run: ForecastRunRecord,
        *,
        now_ms: int | None = None,
    ) -> ForecastCurrentResponse:
        point_records = list(
            session.scalars(
                select(ForecastPointRecord)
                .where(ForecastPointRecord.run_id == run.id)
                .order_by(ForecastPointRecord.step_minutes)
            )
        )
        points = [
            ForecastPoint(
                at_ms=point.at_ms,
                median_mg_dl=point.median_mg_dl,
                low_mg_dl=point.low_mg_dl,
                high_mg_dl=point.high_mg_dl,
            )
            for point in point_records
        ]
        try:
            activities = [ForecastActivity.model_validate(item) for item in json.loads(run.activities_json)]
        except (TypeError, ValueError, json.JSONDecodeError):
            activities = []
        response_now_ms = now_ms if now_ms is not None else _now_ms()
        based_on_reading = cls._based_on_reading(
            session, run.based_on_reading_at_ms
        )
        based_on_glucose_mg_dl = (
            _safe_glucose_mg_dl(based_on_reading.glucose_mg_dl)
            if based_on_reading is not None
            else None
        )
        model = session.get(ForecastModelRecord, run.model_version)
        return ForecastCurrentResponse(
            status=run.status,
            generated_at_ms=run.generated_at_ms,
            based_on_reading_at_ms=run.based_on_reading_at_ms,
            based_on_glucose_mg_dl=based_on_glucose_mg_dl,
            horizon_minutes=120,
            model_version=run.model_version,
            confidence=run.confidence,
            points=points,
            activities=activities,
            conditional_notice=run.conditional_notice,
            alert_assessment=_alert_assessment(
                status=run.status,
                anchor_ms=run.based_on_reading_at_ms,
                points=points,
                model_version=run.model_version,
                reading_fresh=cls._reading_is_fresh_for_alerts(
                    based_on_reading, response_now_ms
                ),
                alert_approved=cls._alert_delivery_is_approved(session, model),
            ),
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
                based_on_glucose_mg_dl=None,
                horizon_minutes=120,
                model_version=champion.version,
                confidence=0.0,
                points=[],
                activities=[],
                conditional_notice=CONDITIONAL_NOTICE,
                alert_assessment=_alert_assessment(
                    status="no_data",
                    anchor_ms=None,
                    points=[],
                    model_version=champion.version,
                    reading_fresh=False,
                    alert_approved=False,
                ),
            )
        anchor_ms = latest.measured_at_ms
        based_on_glucose_mg_dl = _safe_glucose_mg_dl(latest.glucose_mg_dl)
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
        terminal_status = (
            "stale"
            if now - anchor_ms > STALE_AFTER_MS
            else ("low_confidence" if based_on_glucose_mg_dl is None else None)
        )
        if terminal_status is not None:
            activities = self._activities(
                events, anchor_ms, parameters, readings=readings
            )
            return ForecastCurrentResponse(
                status=terminal_status,
                generated_at_ms=now,
                based_on_reading_at_ms=anchor_ms,
                based_on_glucose_mg_dl=based_on_glucose_mg_dl,
                horizon_minutes=120,
                model_version=champion.version,
                confidence=0.0,
                points=[],
                activities=activities,
                conditional_notice=CONDITIONAL_NOTICE,
                alert_assessment=_alert_assessment(
                    status=terminal_status,
                    anchor_ms=anchor_ms,
                    points=[],
                    model_version=champion.version,
                    reading_fresh=False,
                    alert_approved=False,
                ),
            )

        (
            quality_status,
            quality_confidence,
            coverage,
            meal_uncertainty_sigma,
            event_confidence_multiplier,
        ) = self._runtime_forecast_adjustments(
            readings, events, anchor_ms, parameters
        )
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
            return self._run_response(session, existing, now_ms=now)

        median, sigma = _forecast_arrays(readings, events, anchor_ms, parameters)
        if champion.architecture == STATIC_PERSONAL_ARCHITECTURE:
            reference = _static_reference_prediction(
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
        static_maximum_sigma = (600.0 - 20.0) / STATIC_INTERVAL_Z
        sigma = np.nan_to_num(
            sigma,
            nan=60.0,
            posinf=(
                static_maximum_sigma
                if champion.architecture == STATIC_PERSONAL_ARCHITECTURE
                else 120.0
            ),
            neginf=60.0,
        )
        sigma = (
            np.maximum(sigma, 6.0)
            if champion.architecture == STATIC_PERSONAL_ARCHITECTURE
            else np.clip(sigma, 6.0, 200.0)
        )
        # Intrinsic static calibration and live rendering share these bounds.
        # Live quality/meal uncertainty above may only widen them further.
        low, high = _forecast_interval_bounds(median, sigma)
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
        return self._run_response(session, run, now_ms=now)

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
    def _prospective_causal_windows(
        readings: Sequence[GlucoseReadingRecord],
    ) -> tuple[list[tuple[int, np.ndarray]], dict[str, int]]:
        """Build replay windows using backend receipt time as decision time.

        A delayed/bulk upload is not a historical forecast. Every history row
        must already have reached the backend when the anchor arrived, while all
        labels must arrive strictly later. Equal receipt timestamps (a common
        backfill signature) are rejected instead of creating look-ahead. The
        anchor itself must also have been fresh enough for live ``current()``;
        sequential historical backfills are not retrospective forecasts.
        """

        windows: list[tuple[int, np.ndarray]] = []
        rejected_history = 0
        rejected_target = 0
        rejected_stale_anchor = 0
        if not readings:
            return windows, {
                "causal_history_rejections": 0,
                "causal_target_rejections": 0,
                "causal_stale_anchor_rejections": 0,
            }
        reading_times = [row.measured_at_ms for row in readings]
        previous_bucket: int | None = None
        for anchor_index, anchor in enumerate(readings):
            bucket = anchor.measured_at_ms // STEP_MS
            if bucket == previous_bucket:
                continue
            previous_bucket = bucket
            if (
                anchor.measured_at_ms - readings[0].measured_at_ms
                < (HISTORY_STEPS - 1) * STEP_MS
                or readings[-1].measured_at_ms - anchor.measured_at_ms
                < HORIZON_STEPS * STEP_MS
            ):
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
            if (
                len({row.reading_id for row in concrete_history}) != HISTORY_STEPS
                or len({row.reading_id for row in concrete_future}) != HORIZON_STEPS
                or any(
                    row.measured_at_ms <= anchor.measured_at_ms
                    for row in concrete_future
                )
            ):
                continue
            decision_ms = int(anchor.received_at_ms)
            if any(int(row.received_at_ms) > decision_ms for row in concrete_history):
                rejected_history += 1
                continue
            if any(int(row.received_at_ms) <= decision_ms for row in concrete_future):
                rejected_target += 1
                continue
            anchor_latency_ms = decision_ms - int(anchor.measured_at_ms)
            if anchor_latency_ms < 0 or anchor_latency_ms > STALE_AFTER_MS:
                rejected_stale_anchor += 1
                continue
            windows.append(
                (
                    anchor_index,
                    np.asarray(
                        [row.glucose_mg_dl for row in concrete_future],
                        dtype=np.float64,
                    ),
                )
            )
        return windows, {
            "causal_history_rejections": rejected_history,
            "causal_target_rejections": rejected_target,
            "causal_stale_anchor_rejections": rejected_stale_anchor,
        }

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

    @staticmethod
    def _fit_static_event_personalization(
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
        training_windows: Sequence[tuple[int, np.ndarray]],
        parameters: dict[str, Any],
        *,
        retrospective_label_cutoff_ms: int | None = None,
    ) -> dict[str, Any]:
        """Fit small prior corrections from the training partition only.

        A meal/injection stays one group across every overlapping CGM window.
        Same-kind overlaps are excluded per horizon rather than being assigned
        arbitrary responsibility. Other known event kinds stay in the complete
        population reference when forming the residual to avoid double-counting.
        A chart-only build may use imported/backdated labels for these training
        targets, explicitly bounded by its snapshot cutoff. Tuning, calibration,
        and evaluation retain their original anchor-known event semantics.
        """

        population_parameters = dict(parameters)
        population_parameters.pop("event_personalization", None)
        reading_times = [row.measured_at_ms for row in readings]
        sorted_events = sorted(events, key=lambda item: item.occurred_at_ms)
        horizons_ms = np.arange(1, HORIZON_STEPS + 1) * STEP_MS
        samples: list[EventEffectSample] = []
        response_windows: list[EventResponseWindow] = []
        for anchor_index, raw_target in training_windows:
            anchor_ms = int(readings[anchor_index].measured_at_ms)
            known_cutoff_ms = (
                anchor_ms
                if retrospective_label_cutoff_ms is None
                else retrospective_label_cutoff_ms
            )
            causal_events = [
                event
                for event in sorted_events
                if anchor_ms - 96 * 60 * 60_000 <= event.occurred_at_ms <= anchor_ms
                and _event_known_at(event) <= known_cutoff_ms
                and event.kind in EVENT_KINDS
            ]
            if not causal_events:
                continue
            target = np.asarray(raw_target, dtype=np.float64)
            if target.shape != (HORIZON_STEPS,):
                continue
            history_index = bisect.bisect_left(
                reading_times,
                anchor_ms - CONTEXT_HISTORY_MINUTES * 60_000 - MATCH_TOLERANCE_MS,
            )
            reference = _static_reference_prediction(
                readings[history_index : anchor_index + 1],
                causal_events,
                anchor_ms,
                population_parameters,
            )
            residual = target - reference
            valid_target = (
                np.isfinite(target)
                & np.isfinite(reference)
                & (target > 20.0)
                & (target < 600.0)
                & (reference > 20.0)
                & (reference < 600.0)
            )
            # A later recorded intake is not a feature of this anchor. Exclude
            # its affected outcomes from amplitude attribution rather than
            # crediting the earlier meal/injection with that unmodeled effect.
            future_event_times = [
                event.occurred_at_ms
                for event in sorted_events
                if anchor_ms < event.occurred_at_ms <= anchor_ms + HORIZON_STEPS * STEP_MS
                and event.kind in EVENT_KINDS
                and event.amount > 0.0
                and (
                    retrospective_label_cutoff_ms is None
                    or _event_known_at(event) <= retrospective_label_cutoff_ms
                )
            ]
            if future_event_times:
                valid_target &= anchor_ms + horizons_ms < min(future_event_times)
            safety = (target < 80.0) | (reference < 80.0)
            event_effects: dict[tuple[str, str], np.ndarray] = {}
            for kind in EVENT_KINDS:
                matching = [event for event in causal_events if event.kind == kind]
                if not matching:
                    continue
                effects = np.asarray(
                    [
                        [
                            _event_glucose_increment(
                                event,
                                anchor_ms,
                                anchor_ms + int(horizon_ms),
                                population_parameters,
                            )
                            for horizon_ms in horizons_ms
                        ]
                        for event in matching
                    ],
                    dtype=np.float64,
                )
                finite_effects = np.isfinite(effects)
                active = finite_effects & (np.abs(effects) > 1e-8)
                identifiable = (
                    (np.sum(active, axis=0) == 1)
                    & np.all(finite_effects, axis=0)
                    & valid_target
                )
                for event, effect, active_horizons in zip(matching, effects, active):
                    event_effects[(kind, event.event_id)] = effect
                    usable = identifiable & active_horizons
                    if not np.any(usable):
                        continue
                    samples.append(
                        EventEffectSample(
                            event_id=event.event_id,
                            kind=kind,
                            occurred_at_ms=event.occurred_at_ms,
                            population_effect_mg_dl=effect[usable],
                            observed_residual_mg_dl=residual[usable],
                            safety_mask=safety[usable],
                        )
                    )
            response_windows.append(
                EventResponseWindow(
                    reference_mg_dl=reference,
                    target_mg_dl=target,
                    event_effects=event_effects,
                    usable_mask=valid_target,
                    safety_mask=safety,
                )
            )
        artifact = fit_bounded_event_personalization(samples)
        if not any(artifact["kinds"][kind]["accepted"] for kind in EVENT_KINDS):
            return artifact
        return gate_combined_event_personalization(artifact, samples, response_windows)

    def _dataset_for_parameters(
        self,
        readings: Sequence[GlucoseReadingRecord],
        events: Sequence[_Event],
        windows: Sequence[tuple[int, np.ndarray]],
        parameters: dict[str, Any],
        *,
        decision_times_ms: dict[int, int] | None = None,
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
            decision_ms = (
                decision_times_ms.get(anchor_index, anchor.measured_at_ms)
                if decision_times_ms is not None
                else anchor.measured_at_ms
            )
            event_occurrence_cutoff_ms = (
                decision_ms
                if decision_times_ms is not None
                else anchor.measured_at_ms
            )
            causal_recent = [
                item
                for item in sorted_events
                if item.occurred_at_ms <= event_occurrence_cutoff_ms
                and _event_known_at(item) <= decision_ms
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
            if decision_times_ms is not None:
                causal_readings = [
                    row
                    for row in causal_readings
                    if int(row.received_at_ms) <= decision_ms
                ]
            features.append(
                _history_features(causal_readings, causal_recent, anchor.measured_at_ms, parameters)
            )
            if parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA:
                reference_function = _static_reference_prediction
            elif parameters.get("prediction_reference") == "event_aware_persistence":
                reference_function = _event_reference_prediction
            else:
                reference_function = _baseline_prediction
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
        *,
        decision_times_ms: dict[int, int] | None = None,
    ) -> np.ndarray:
        sorted_events = sorted(events, key=lambda item: item.occurred_at_ms)
        reading_times = [row.measured_at_ms for row in readings]
        references: list[np.ndarray] = []
        for anchor_index, _target in windows:
            anchor = readings[anchor_index]
            decision_ms = (
                decision_times_ms.get(anchor_index, anchor.measured_at_ms)
                if decision_times_ms is not None
                else anchor.measured_at_ms
            )
            event_occurrence_cutoff_ms = (
                decision_ms
                if decision_times_ms is not None
                else anchor.measured_at_ms
            )
            causal_events = [
                event
                for event in sorted_events
                if event.occurred_at_ms <= event_occurrence_cutoff_ms
                and _event_known_at(event) <= decision_ms
                and event.occurred_at_ms
                >= anchor.measured_at_ms - 96 * 60 * 60_000
            ]
            history_minutes = (
                CONTEXT_HISTORY_MINUTES
                if parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA
                else (HISTORY_STEPS - 1) * STEP_MINUTES
            )
            history_start = anchor.measured_at_ms - history_minutes * 60_000
            history_index = bisect.bisect_left(reading_times, history_start)
            causal_readings = readings[history_index : anchor_index + 1]
            if decision_times_ms is not None:
                causal_readings = [
                    row for row in causal_readings if int(row.received_at_ms) <= decision_ms
                ]
            reference_function = (
                _static_reference_prediction
                if parameters.get("feature_schema") == STATIC_FEATURE_SCHEMA
                else _event_reference_prediction
            )
            references.append(
                reference_function(
                    causal_readings, causal_events, anchor.measured_at_ms, parameters
                )
            )
        return np.vstack(references)

    @staticmethod
    def _metrics(prediction: np.ndarray, target: np.ndarray) -> dict[str, float]:
        absolute = np.abs(prediction - target)
        return {
            "mae": float(np.mean(absolute)),
            "mae_5": float(np.mean(absolute[:, 0])),
            "mae_15": float(np.mean(absolute[:, 2])),
            "mae_30": float(np.mean(absolute[:, 5])),
            "mae_60": float(np.mean(absolute[:, 11])),
            "mae_120": float(np.mean(absolute[:, 23])),
            "rmse": float(np.sqrt(np.mean((prediction - target) ** 2))),
        }

    @staticmethod
    def _trajectory_diagnostics(
        prediction: np.ndarray, trend_reference: np.ndarray
    ) -> dict[str, float]:
        """Describe visual continuity and preservation of a strong reference trend."""

        candidate = np.asarray(prediction, dtype=np.float64)
        reference = np.asarray(trend_reference, dtype=np.float64)
        if (
            candidate.ndim != 2
            or candidate.shape != reference.shape
            or candidate.shape[1] != HORIZON_STEPS
            or not np.isfinite(candidate).all()
            or not np.isfinite(reference).all()
        ):
            return {
                "trajectory_max_step_mg_dl": math.inf,
                "trajectory_p95_step_mg_dl": math.inf,
                "trajectory_max_curvature_mg_dl": math.inf,
                "trajectory_p95_curvature_mg_dl": math.inf,
                "strong_trend_samples": 0.0,
                "near_flat_strong_trend_rate": 1.0,
                "strong_trend_direction_agreement": 0.0,
            }
        adjacent = np.abs(np.diff(candidate, axis=1))
        curvature = np.abs(np.diff(candidate, n=2, axis=1))
        # Five-to-thirty minutes is long enough to ignore a single noisy point,
        # but short enough that the damped trend reference still carries signal.
        reference_change = reference[:, 5] - reference[:, 0]
        candidate_change = candidate[:, 5] - candidate[:, 0]
        strong = np.abs(reference_change) >= 8.0
        strong_count = int(np.sum(strong))
        if strong_count:
            strong_reference = reference_change[strong]
            strong_candidate = candidate_change[strong]
            near_flat = np.abs(strong_candidate) < np.maximum(
                3.0, 0.35 * np.abs(strong_reference)
            )
            direction_agreement = np.sign(strong_candidate) == np.sign(strong_reference)
            near_flat_rate = float(np.mean(near_flat))
            agreement_rate = float(np.mean(direction_agreement))
        else:
            near_flat_rate = 0.0
            agreement_rate = 1.0
        return {
            "trajectory_max_step_mg_dl": float(np.max(adjacent)),
            "trajectory_p95_step_mg_dl": float(np.quantile(adjacent, 0.95)),
            "trajectory_max_curvature_mg_dl": float(np.max(curvature)),
            "trajectory_p95_curvature_mg_dl": float(np.quantile(curvature, 0.95)),
            "strong_trend_samples": float(strong_count),
            "near_flat_strong_trend_rate": near_flat_rate,
            "strong_trend_direction_agreement": agreement_rate,
        }

    @staticmethod
    def _interval_metrics(
        prediction: np.ndarray,
        target: np.ndarray,
        sigma: np.ndarray,
        *,
        readings: Sequence[GlucoseReadingRecord] | None = None,
        windows: Sequence[tuple[int, np.ndarray]] | None = None,
    ) -> dict[str, float | None]:
        low, high = _forecast_interval_bounds(prediction, sigma)
        inside = (target >= low) & (target <= high)
        interval_score = (
            (high - low)
            + (2.0 / (1.0 - STATIC_INTERVAL_LEVEL))
            * np.maximum(0.0, low - target)
            + (2.0 / (1.0 - STATIC_INTERVAL_LEVEL))
            * np.maximum(0.0, target - high)
        )
        hypo = target < 70.0
        risk = low <= 70.0
        hypo_count = int(np.sum(hypo))
        true_positive = int(np.sum(hypo & risk))
        false_negative = hypo_count - true_positive
        negative_count = int(np.sum(~hypo))
        false_positive = int(np.sum((~hypo) & risk))
        low_zone = target < 80.0
        low_zone_count = int(np.sum(low_zone))

        timeline: list[tuple[int, int, bool, bool]] = []
        if (
            readings is not None
            and windows is not None
            and len(windows) == target.shape[0]
        ):
            for row_index, window in enumerate(windows):
                anchor = readings[window[0]]
                offset_ms = int(anchor.utc_offset_minutes or 0) * 60_000
                for horizon_index in range(HORIZON_STEPS):
                    at_ms = anchor.measured_at_ms + (horizon_index + 1) * STEP_MS
                    timeline.append(
                        (
                            at_ms,
                            (at_ms + offset_ms) // 86_400_000,
                            bool(hypo[row_index, horizon_index]),
                            bool(risk[row_index, horizon_index]),
                        )
                    )
        else:
            for point_index, (actual_low, warned) in enumerate(
                zip(hypo.reshape(-1), risk.reshape(-1))
            ):
                timeline.append(
                    (
                        point_index * STEP_MS,
                        point_index // (24 * 60 // STEP_MINUTES),
                        bool(actual_low),
                        bool(warned),
                    )
                )
        timeline.sort(key=lambda item: item[0])
        low_days = {day for _at, day, actual_low, _risk in timeline if actual_low}
        episode_count = 0
        missed_episodes = 0
        episode_last_at: int | None = None
        episode_warned = False
        for at_ms, _day, actual_low, warned in timeline:
            if not actual_low:
                continue
            if episode_last_at is None or at_ms - episode_last_at > 15 * 60_000:
                if episode_last_at is not None and not episode_warned:
                    missed_episodes += 1
                episode_count += 1
                episode_warned = False
            episode_warned = episode_warned or warned
            episode_last_at = at_ms
        if episode_last_at is not None and not episode_warned:
            missed_episodes += 1
        result: dict[str, float | None] = {
            "coverage_80": float(np.mean(inside)),
            "mean_interval_width": float(np.mean(high - low)),
            "interval_score_80": float(np.mean(interval_score)),
            "hypo_samples": float(hypo_count),
            "hypo_low_points": float(hypo_count),
            "hypo_low_episodes": float(episode_count),
            "hypo_low_days": float(len(low_days)),
            "hypo_recall": (
                float(true_positive / hypo_count) if hypo_count else None
            ),
            "hypo_fpr": (
                float(false_positive / negative_count) if negative_count else None
            ),
            "hypo_missed_episodes": float(missed_episodes),
            "low_zone_mae": (
                float(np.mean(np.abs(prediction[low_zone] - target[low_zone])))
                if low_zone_count
                else None
            ),
            "hypo_miss_rate": (
                float(false_negative / hypo_count) if hypo_count else None
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

    @classmethod
    def _prospective_dense_windows(
        cls,
        readings: Sequence[GlucoseReadingRecord],
        windows: Sequence[tuple[int, np.ndarray]],
        *,
        strictly_after_ms: int,
    ) -> tuple[list[tuple[int, np.ndarray]], dict[str, Any]]:
        """Select independent anchors from complete dense local days after a freeze.

        The local day containing the artifact cutoff is always discarded, even
        when the cutoff happens just after midnight.  Eligible later days must
        cover the beginning and the final forecastable part of the day, remain
        dense between those edges, and are then downsampled to non-overlapping
        120-minute anchors.  No target that helped fit or calibrate the artifact
        can therefore re-enter its prospective decision.
        """

        cutoff_rows = [
            row for row in readings if row.measured_at_ms <= strictly_after_ms
        ]
        if not cutoff_rows:
            return [], {
                "protocol": STATIC_PROSPECTIVE_PROTOCOL,
                "dense_days": 0,
                "independent_anchors": 0,
                "local_days_sha256": hashlib.sha256(b"").hexdigest(),
                "selected_local_days": [],
            }
        cutoff_row = max(cutoff_rows, key=lambda row: row.measured_at_ms)
        cutoff_offset_ms = int(cutoff_row.utc_offset_minutes or 0) * 60_000
        cutoff_local_day = (
            cutoff_row.measured_at_ms + cutoff_offset_ms
        ) // 86_400_000

        grouped: dict[int, list[tuple[int, np.ndarray]]] = {}
        for window in windows:
            anchor = readings[window[0]]
            day = cls._window_local_day(readings, window)
            if anchor.measured_at_ms <= strictly_after_ms or day <= cutoff_local_day:
                continue
            grouped.setdefault(day, []).append(window)

        eligible: list[tuple[int, list[tuple[int, np.ndarray]], float]] = []
        for day, day_windows in sorted(grouped.items()):
            day_windows.sort(key=lambda item: readings[item[0]].measured_at_ms)
            times = [readings[item[0]].measured_at_ms for item in day_windows]
            if len(times) < 2:
                continue
            local_minutes = [
                (
                    reading.measured_at_ms // 60_000
                    + int(reading.utc_offset_minutes or 0)
                )
                % (24 * 60)
                for reading in (readings[item[0]] for item in day_windows)
            ]
            span_hours = (times[-1] - times[0]) / 3_600_000.0
            expected = max(1, int(round((times[-1] - times[0]) / STEP_MS)) + 1)
            occupied = len({value // STEP_MS for value in times})
            density = occupied / expected
            # Complete targets end two hours after their anchor, so an anchor at
            # 21:00 still verifies the day through 23:00.  The one-hour edge
            # tolerance accommodates ordinary sensor gaps without admitting a
            # partial morning/evening as a "whole" day.
            covers_day_edges = min(local_minutes) <= 60 and max(local_minutes) >= 21 * 60
            if (
                span_hours >= 20.0
                and density >= STATIC_MIN_DAY_DENSITY
                and covers_day_edges
            ):
                eligible.append((day, day_windows, density))

        available_days = len(eligible)
        # The cohort is preregistered: exactly the earliest fourteen eligible
        # days, never an operator-selected expanding prefix.  Four days suffice
        # for the point/day gate, while fourteen provide a materially better
        # chance of observing the predeclared low-glucose safety evidence.
        eligible = eligible[:STATIC_PROSPECTIVE_MIN_DAYS]
        independent: list[tuple[int, np.ndarray]] = []
        for _day, day_windows, _density in eligible:
            independent.extend(cls._independent_windows(readings, day_windows))
        day_ids = [item[0] for item in eligible]
        day_digest = hashlib.sha256(
            ",".join(str(day) for day in day_ids).encode("ascii")
        ).hexdigest()
        return independent, {
            "protocol": STATIC_PROSPECTIVE_PROTOCOL,
            "dense_days": len(eligible),
            "available_dense_days": available_days,
            "independent_anchors": len(independent),
            "local_days_sha256": day_digest,
            "selected_local_days": day_ids,
            "minimum_day_hours": 20.0,
            "minimum_day_density": STATIC_MIN_DAY_DENSITY,
            "edge_tolerance_minutes": 60,
            "first_local_day": day_ids[0] if day_ids else None,
            "last_local_day": day_ids[-1] if day_ids else None,
        }

    @staticmethod
    def _frozen_calibration(
        prediction: np.ndarray, target: np.ndarray
    ) -> tuple[np.ndarray, np.ndarray]:
        if (
            prediction.ndim != 2
            or prediction.shape != target.shape
            or prediction.shape[1] != HORIZON_STEPS
            or prediction.shape[0] <= 0
            or not np.isfinite(prediction).all()
            or not np.isfinite(target).all()
        ):
            raise ValueError("calibration requires finite N x 24 prediction and target arrays")
        # Never shift a point forecast using only a tiny calibration tail. The
        # calibration fold estimates uncertainty, not a mutable median offset.
        bias = np.zeros(HORIZON_STEPS, dtype=np.float64)
        rank = min(
            prediction.shape[0],
            max(
                1,
                math.ceil(
                    (prediction.shape[0] + 1) * STATIC_INTERVAL_LEVEL
                ),
            ),
        )
        ordered_scores = np.sort(np.abs(target - prediction), axis=0)
        half_width = ordered_scores[rank - 1]
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
            "mae_5",
            "mae_15",
            "mae_30",
            "mae_60",
            "mae_120",
            "coverage_80",
            "interval_score_80",
            "coverage_band_0",
            "coverage_band_1",
            "coverage_band_2",
            "coverage_band_3",
            "hypo_low_points",
            "hypo_low_episodes",
            "hypo_low_days",
            "hypo_recall",
            "hypo_fpr",
            "hypo_missed_episodes",
            "low_zone_mae",
            *STATIC_TRAJECTORY_METRICS,
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
        no_bad_day = bool(
            np.all(candidate_days <= reference_days * 1.02)
            and np.all(candidate_days <= pinned_days * 1.02)
        )
        horizons_safe = all(
            float(candidate_metrics[key])
            <= max(float(reference_metrics[key]) * 1.02, float(reference_metrics[key]) + 0.5)
            and float(candidate_metrics[key])
            <= max(float(pinned_metrics[key]) * 1.02, float(pinned_metrics[key]) + 0.5)
            for key in ("mae_5", "mae_15", "mae_30", "mae_60", "mae_120")
        )
        coverage_safe = (
            0.75 <= float(candidate_metrics["coverage_80"]) <= 0.90
            and all(
                float(candidate_metrics[f"coverage_band_{index}"]) >= 0.70
                for index in range(len(STATIC_BANDS))
            )
        )
        hypo_evidence_sufficient = bool(
            float(candidate_metrics["hypo_low_points"]) >= 40
            and float(candidate_metrics["hypo_low_episodes"]) >= 5
            and float(candidate_metrics["hypo_low_days"]) >= 4
        )
        hypo_recall_safe = bool(
            float(candidate_metrics["hypo_recall"])
            >= float(reference_metrics["hypo_recall"]) - 0.05
            and float(candidate_metrics["hypo_recall"])
            >= float(pinned_metrics["hypo_recall"]) - 0.05
        )
        hypo_episode_safe = bool(
            float(candidate_metrics["hypo_missed_episodes"])
            <= float(reference_metrics["hypo_missed_episodes"])
            and float(candidate_metrics["hypo_missed_episodes"])
            <= float(pinned_metrics["hypo_missed_episodes"])
        )
        hypo_false_alarm_safe = bool(
            float(candidate_metrics["hypo_fpr"])
            <= float(reference_metrics["hypo_fpr"]) + 0.05
            and float(candidate_metrics["hypo_fpr"])
            <= float(pinned_metrics["hypo_fpr"]) + 0.05
        )
        low_zone_safe = bool(
            float(candidate_metrics["low_zone_mae"])
            <= max(
                float(reference_metrics["low_zone_mae"]) * 1.02,
                float(reference_metrics["low_zone_mae"]) + 0.5,
            )
            and float(candidate_metrics["low_zone_mae"])
            <= max(
                float(pinned_metrics["low_zone_mae"]) * 1.02,
                float(pinned_metrics["low_zone_mae"]) + 0.5,
            )
        )
        hypo_safe = bool(
            hypo_evidence_sufficient
            and hypo_recall_safe
            and hypo_episode_safe
            and hypo_false_alarm_safe
            and low_zone_safe
        )
        continuity_step_limit = min(
            18.0,
            max(
                15.0,
                float(reference_metrics["trajectory_max_step_mg_dl"]) + 4.0,
                float(pinned_metrics["trajectory_max_step_mg_dl"]) + 4.0,
            ),
        )
        continuity_curvature_limit = min(
            10.0,
            max(
                8.0,
                float(reference_metrics["trajectory_max_curvature_mg_dl"])
                + 3.0,
                float(pinned_metrics["trajectory_max_curvature_mg_dl"]) + 3.0,
            ),
        )
        continuity_p95_curvature_limit = min(
            6.0,
            max(
                5.0,
                float(reference_metrics["trajectory_p95_curvature_mg_dl"])
                + 2.0,
                float(pinned_metrics["trajectory_p95_curvature_mg_dl"]) + 2.0,
            ),
        )
        trajectory_continuity_safe = bool(
            float(candidate_metrics["trajectory_max_step_mg_dl"])
            <= continuity_step_limit
            and float(candidate_metrics["trajectory_max_curvature_mg_dl"])
            <= continuity_curvature_limit
            and float(candidate_metrics["trajectory_p95_curvature_mg_dl"])
            <= continuity_p95_curvature_limit
        )
        strong_trend_samples = int(
            float(candidate_metrics["strong_trend_samples"])
        )
        strong_trend_evidence_sufficient = strong_trend_samples >= 4
        strong_trend_preserved = bool(
            not strong_trend_evidence_sufficient
            or (
                float(candidate_metrics["near_flat_strong_trend_rate"]) <= 0.25
                and float(
                    candidate_metrics["strong_trend_direction_agreement"]
                )
                >= 0.75
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
                "hypo_evidence_sufficient": hypo_evidence_sufficient,
                "hypo_recall_safe": hypo_recall_safe,
                "hypo_episode_safe": hypo_episode_safe,
                "hypo_false_alarm_safe": hypo_false_alarm_safe,
                "low_zone_safe": low_zone_safe,
                "hypo_safe": hypo_safe,
                "trajectory_continuity_safe": trajectory_continuity_safe,
                "trajectory_step_limit_mg_dl": continuity_step_limit,
                "trajectory_curvature_limit_mg_dl": continuity_curvature_limit,
                "trajectory_p95_curvature_limit_mg_dl": (
                    continuity_p95_curvature_limit
                ),
                "strong_trend_evidence_sufficient": (
                    strong_trend_evidence_sufficient
                ),
                "strong_trend_preserved": strong_trend_preserved,
                "interval_score_safe": float(candidate_metrics["interval_score_80"])
                <= float(reference_metrics["interval_score_80"]) * 0.98
                and float(candidate_metrics["interval_score_80"])
                <= float(pinned_metrics["interval_score_80"]) * 0.98,
                "rmse_safe": float(candidate_metrics["rmse"])
                <= float(reference_metrics["rmse"]) * 0.98
                and float(candidate_metrics["rmse"])
                <= float(pinned_metrics["rmse"]) * 0.98,
                "anchor_mae_safe": float(candidate_metrics["mae"])
                <= float(reference_metrics["mae"]) * 0.97
                and float(candidate_metrics["mae"])
                <= float(pinned_metrics["mae"]) * 0.97,
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
            and hypo_safe
            and trajectory_continuity_safe
            and strong_trend_preserved
        )
        return result

    @staticmethod
    def static_display_gates(
        candidate_metrics: dict[str, float | None],
        reference_metrics: dict[str, float | None],
        pinned_metrics: dict[str, float | None],
        day_results: Sequence[dict[str, float]],
        *,
        test_day_count: int,
        finite: bool = True,
    ) -> dict[str, bool | float | int]:
        """Engineering selection gate for an exploratory display-only predictor.

        This deliberately cannot approve predictive alert delivery. It keeps
        independent-day improvement, horizon noninferiority, low-zone point
        accuracy, interval calibration, and trajectory continuity. Episode
        recall/false alarms and agreement with the reference's trend remain
        diagnostics here, not claims about a notification system. Alert
        eligibility still requires the separate frozen future-day protocol.
        """

        result = ForecastService.static_promotion_gates(
            candidate_metrics,
            reference_metrics,
            pinned_metrics,
            day_results,
            test_day_count=test_day_count,
            finite=finite,
        )
        required = (
            "reference_equal_day_improvement",
            "pinned_equal_day_improvement",
            "winning_days",
            "required_winning_days",
            "median_day_improvement",
            "no_day_regression_over_2pct",
            "horizons_safe",
            "coverage_safe",
            "low_zone_safe",
            "rmse_safe",
            "anchor_mae_safe",
            "trajectory_continuity_safe",
        )
        if any(key not in result for key in required):
            result["accepted"] = False
            result["display_only"] = True
            return result
        interval_score_safe = bool(
            float(candidate_metrics["interval_score_80"])
            <= float(reference_metrics["interval_score_80"])
            and float(candidate_metrics["interval_score_80"])
            <= float(pinned_metrics["interval_score_80"])
        )
        low_zone_display_safe = bool(result["low_zone_safe"])
        # A small calibration set has coarse order statistics. Modest
        # conservative over-coverage is acceptable for a chart-only band; all
        # horizon bands must still satisfy the declared coverage floor.
        display_coverage_safe = bool(
            0.75 <= float(candidate_metrics["coverage_80"]) <= 0.92
            and all(
                float(candidate_metrics[f"coverage_band_{index}"]) >= 0.70
                for index in range(len(STATIC_BANDS))
            )
        )
        result.update(
            {
                "display_only": True,
                "coverage_safe": display_coverage_safe,
                "interval_score_safe": interval_score_safe,
                "low_zone_display_safe": low_zone_display_safe,
            }
        )
        result["accepted"] = bool(
            float(result["reference_equal_day_improvement"]) >= 0.05
            and float(result["pinned_equal_day_improvement"]) >= 0.03
            and int(result["winning_days"]) >= int(result["required_winning_days"])
            and float(result["median_day_improvement"]) > 0.0
            and result["no_day_regression_over_2pct"]
            and result["horizons_safe"]
            and display_coverage_safe
            and interval_score_safe
            and result["rmse_safe"]
            and result["anchor_mae_safe"]
            and low_zone_display_safe
            and result["trajectory_continuity_safe"]
        )
        return result

    @staticmethod
    def _non_hypo_promotion_gates(
        candidate_metrics: dict[str, float | None],
        reference_metrics: dict[str, float | None],
        pinned_metrics: dict[str, float | None],
        day_results: Sequence[dict[str, float]],
        *,
        test_day_count: int,
        finite: bool = True,
    ) -> dict[str, bool | float | int]:
        """Evaluate every preregistered gate except unavailable hypo evidence.

        The prospective decision may be *inconclusive* only when the point,
        interval, and independent-day gates would otherwise pass.  Supplying a
        common synthetic hypo-perfect block lets the canonical gate remain the
        single implementation of those non-hypoglycemia thresholds; none of the
        synthetic values are persisted or used to approve a model.
        """

        synthetic_hypo = {
            "hypo_low_points": 40.0,
            "hypo_low_episodes": 5.0,
            "hypo_low_days": 4.0,
            "hypo_recall": 1.0,
            "hypo_fpr": 0.0,
            "hypo_missed_episodes": 0.0,
            "low_zone_mae": 1.0,
        }

        def with_synthetic_hypo(
            metrics: dict[str, float | None],
        ) -> dict[str, float | None]:
            return {**metrics, **synthetic_hypo}

        return ForecastService.static_promotion_gates(
            with_synthetic_hypo(candidate_metrics),
            with_synthetic_hypo(reference_metrics),
            with_synthetic_hypo(pinned_metrics),
            day_results,
            test_day_count=test_day_count,
            finite=finite,
        )

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
        stage_pending: bool = True,
        allow_display_activation: bool = False,
    ) -> ForecastTrainResponse:
        """Manually build one frozen candidate from an immutable causal snapshot.

        This method is intentionally reachable only from the local admin CLI and
        never activates a candidate itself.  The normal path freezes a
        development-gate pass with ``status=pending`` for prospective approval.
        A separately requested display-only path may emit an activation-eligible
        chart model whose checksummed approval keeps predictive alerts disabled.
        """

        with self._training_lock:
            return self._train_static_model_unlocked(
                session,
                data_cutoff_ms=data_cutoff_ms,
                candidate_version=candidate_version,
                stage_pending=stage_pending,
                allow_display_activation=allow_display_activation,
            )

    def _train_static_model_unlocked(
        self,
        session: Session,
        *,
        data_cutoff_ms: int | None,
        candidate_version: str | None,
        stage_pending: bool,
        allow_display_activation: bool,
    ) -> ForecastTrainResponse:
        if stage_pending and allow_display_activation:
            raise ValueError(
                "display-only activation and prospective registration are mutually exclusive"
            )
        champion = self._champion(session)
        champion_parameters_json = champion.parameters_json
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
        frozen_source_fingerprint_before = self._frozen_source_fingerprint(
            session, cutoff_ms=cutoff_ms
        )
        # The first load discovers the concrete cutoff. Reload after freezing
        # the raw-source fingerprint so any historical write racing either side
        # of this query is visible at the final guarded commit.
        readings = self._load_readings(
            session, through_ms=cutoff_ms, limit=60_000
        )
        if not readings or int(readings[-1].measured_at_ms) != cutoff_ms:
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=champion.version,
                reason=(
                    "Source glucose/intake data changed while freezing the training snapshot; "
                    "retry from a fresh snapshot"
                ),
                sample_count=0,
                metrics={"source_revision_changed": 1},
            )
        source_revision_before = self._source_revision(session)
        all_windows = self._training_windows(readings, max_windows=None)
        receipt_causal_windows, receipt_causal_diagnostics = (
            self._prospective_causal_windows(readings)
        )
        receipt_causal_days = len(
            {
                self._window_local_day(readings, window)
                for window in receipt_causal_windows
            }
        )
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
                    "2 frozen calibration, and 4 held-out retrospective selection days"
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
        # Flexible event-specific networks remain unidentifiable from a sparse
        # log. Fit only a strongly shrunk amplitude correction around each prior,
        # using independent event groups from the training partition alone.
        evidence_parameters = self._personalized_parameters(readings, events)
        parameters = _default_parameters()
        parameters["evidence_counts"] = dict(
            evidence_parameters.get("evidence_counts", {})
        )
        parameters["kind"] = "personalized_static_generic_residual"
        parameters["feature_schema"] = STATIC_FEATURE_SCHEMA
        parameters["architecture"] = STATIC_PERSONAL_ARCHITECTURE
        parameters["prediction_reference"] = STATIC_REFERENCE_KIND
        parameters["reference_configuration"] = {
            "trend_decay_minutes": STATIC_TREND_DECAY_MINUTES,
            "trend_lookback_minutes": STATIC_TREND_LOOKBACK_MINUTES,
            "quality_gated": True,
        }
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
        parameters["event_personalization"] = (
            self._fit_static_event_personalization(
                readings,
                events,
                train_windows,
                parameters,
                retrospective_label_cutoff_ms=(
                    cutoff_ms if allow_display_activation else None
                ),
            )
        )
        parameters["event_personalization_context"] = {
            "label_mode": (
                STATIC_EVENT_LABELS_RETROSPECTIVE
                if allow_display_activation
                else STATIC_EVENT_LABELS_CAUSAL
            ),
            "label_cutoff_ms": cutoff_ms,
            "last_training_target_at_ms": max(
                int(readings[index].measured_at_ms) + HORIZON_STEPS * STEP_MS
                for index, _target in train_windows
            ),
            "first_tuning_anchor_at_ms": min(
                int(readings[index].measured_at_ms) for index, _target in tuning_windows
            ),
            "training_window_count": len(train_windows),
            "training_windows_sha256": hashlib.sha256(
                ",".join(
                    str(readings[index].measured_at_ms) for index, _target in train_windows
                ).encode("ascii")
            ).hexdigest(),
        }

        x_train, reference_train, target_train = self._dataset_for_parameters(
            readings, events, train_windows, parameters
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

        selection_results: list[dict[str, Any]] = []
        selected_network: dict[str, Any] | None = None
        selected_blend: np.ndarray | None = None
        selected_knots: tuple[float, ...] | None = None
        selected_loss = math.inf
        selected_alpha = -1.0
        for alpha in STATIC_RIDGE_ALPHAS:
            parameters["network"] = _fit_network(
                x_train, target_train - reference_train, alpha
            )
            _tune_x, tune_reference, tune_target, tune_raw = raw_static(
                tuning_independent
            )
            candidate_blend: np.ndarray | None = None
            candidate_knots: tuple[float, ...] | None = None
            tuning_mae = math.inf
            for raw_knots in product(
                STATIC_SHRINK_GRID, repeat=len(STATIC_SHRINK_KNOT_MINUTES)
            ):
                knots = tuple(float(value) for value in raw_knots)
                if any(later > earlier for earlier, later in zip(knots, knots[1:])):
                    continue
                curve = _static_shrinkage_curve(knots)
                prediction = tune_reference + curve.reshape(1, -1) * (
                    tune_raw - tune_reference
                )
                loss = float(np.mean(np.abs(prediction - tune_target)))
                if loss < tuning_mae - 1e-9:
                    tuning_mae = loss
                    candidate_blend = curve
                    candidate_knots = knots
            if candidate_blend is None or candidate_knots is None:
                raise RuntimeError("smooth shrinkage grid did not produce a candidate")
            selection_results.append(
                {
                    "alpha": alpha,
                    "tuning_mae": tuning_mae,
                    "shrink_knots": list(candidate_knots),
                }
            )
            if tuning_mae < selected_loss or (
                tuning_mae == selected_loss and alpha > selected_alpha
            ):
                selected_loss = tuning_mae
                selected_alpha = alpha
                selected_network = parameters["network"]
                selected_blend = candidate_blend
                selected_knots = candidate_knots
        if selected_network is None or selected_blend is None or selected_knots is None:
            raise RuntimeError("ridge tuning grid did not produce a finite candidate")
        parameters["network"] = selected_network
        parameters["model_selection"] = {
            "protocol": "chronological-tuning-only-smooth-shrink-ridge-grid-v2",
            "criterion": "lowest_tuning_mae_then_stronger_regularization",
            "selected_alpha": selected_alpha,
            "selected_tuning_mae": selected_loss,
            "candidates": selection_results,
        }
        blend = selected_blend
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
        sigma_expansion = STATIC_DISPLAY_SIGMA_EXPANSION if allow_display_activation else 1.0
        sigma = sigma * sigma_expansion
        parameters["residual_sigma"] = sigma.tolist()
        parameters["frozen_calibration"] = {
            "method": "frozen-uncentered-conformal-v2",
            "interval_level": STATIC_INTERVAL_LEVEL,
            "sample_count": len(calibration_independent),
            "finite_sample_quantile": _finite_sample_quantile_level(
                len(calibration_independent)
            ),
            "finite_sample_rank": min(
                len(calibration_independent),
                math.ceil(
                    (len(calibration_independent) + 1) * STATIC_INTERVAL_LEVEL
                ),
            ),
            "quantile_method": "exact-order-statistic",
            "point_bias": "disabled",
            "low_guard_threshold_mg_dl": STATIC_LOW_GUARD_MG_DL,
            "safety_envelope": (
                STATIC_DISPLAY_SAFETY_ENVELOPE
                if allow_display_activation
                else STATIC_ALERT_SAFETY_ENVELOPE
            ),
            "point_low_guard": not allow_display_activation,
            "sigma_expansion": sigma_expansion,
            "bias_mg_dl": bias.tolist(),
            "sigma_mg_dl": sigma.tolist(),
            "reference_sigma_mg_dl": reference_sigma.tolist(),
        }

        _test_x, test_reference, test_target, test_raw = raw_static(test_independent)
        reference_prediction = np.clip(
            test_reference, 20.0, 600.0
        )
        candidate_prediction, candidate_safety_sigma = _apply_static_predictor(
            test_raw,
            reference_prediction,
            sigma,
            parameters,
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
        if pinned_sigma.shape not in {
            (HORIZON_STEPS,),
            pinned_prediction.shape,
        } or not np.isfinite(pinned_sigma).all():
            pinned_sigma = np.asarray(
                _default_parameters()["residual_sigma"], dtype=np.float64
            )

        candidate_metrics: dict[str, float | None] = {
            **self._metrics(candidate_prediction, test_target),
            **self._trajectory_diagnostics(candidate_prediction, reference_prediction),
            **self._interval_metrics(
                candidate_prediction,
                test_target,
                candidate_safety_sigma,
                readings=readings,
                windows=test_independent,
            ),
        }
        reference_metrics: dict[str, float | None] = {
            **self._metrics(reference_prediction, test_target),
            **self._trajectory_diagnostics(reference_prediction, reference_prediction),
            **self._interval_metrics(
                reference_prediction,
                test_target,
                reference_sigma,
                readings=readings,
                windows=test_independent,
            ),
        }
        pinned_metrics: dict[str, float | None] = {
            **self._metrics(pinned_prediction, champion_target),
            **self._trajectory_diagnostics(pinned_prediction, reference_prediction),
            **self._interval_metrics(
                pinned_prediction,
                champion_target,
                pinned_sigma,
                readings=readings,
                windows=test_independent,
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
        display_gates = self.static_display_gates(
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
        if allow_display_activation:
            # Historical receipt timing describes what a server could have
            # known live, not whether sensor-time history can train a chart.
            # Backfilled CGM remains valid for explicit retrospective display;
            # only the separate prospective/alert protocol can establish live
            # receipt-causal performance. Preserve these counts as diagnostics.
            display_gates = {
                **display_gates,
                "receipt_causal_evidence_sufficient": bool(
                    len(receipt_causal_windows) >= 32 and receipt_causal_days >= 4
                ),
            }
        reporting_gates = display_gates if allow_display_activation else gates
        accepted = bool(reporting_gates["accepted"])

        # Overlapping windows are retained only as a transparent secondary
        # diagnostic; they never participate in the gate or confidence.
        _diag_x, diag_reference, diag_target, diag_raw = raw_static(test_windows)
        diag_prediction, _diag_sigma = _apply_static_predictor(
            diag_raw,
            np.clip(diag_reference, 20.0, 600.0),
            sigma,
            parameters,
        )
        diagnostic_metrics = self._metrics(diag_prediction, diag_target)

        test_days = len(day_results)
        development_reliability = _static_reliability(
            candidate_metrics,
            reference_metrics,
            reporting_gates,
            test_days=test_days,
            independent_anchors=len(test_independent),
            candidate_horizon_mae=np.mean(
                np.abs(candidate_prediction - test_target), axis=0
            ),
            reference_horizon_mae=np.mean(
                np.abs(reference_prediction - test_target), axis=0
            ),
        )
        reliability_overall = float(development_reliability["overall"])
        by_horizon = development_reliability["by_horizon"]
        confidence_cap = float(development_reliability["test_day_cap"])

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
                reporting_gates.get("candidate_equal_day_mae"),
                float(candidate_metrics["mae"] or 0.0),
            ),
            "reference_equal_day_mae": _finite(
                reporting_gates.get("reference_equal_day_mae"),
                float(reference_metrics["mae"] or 0.0),
            ),
            "pinned_equal_day_mae": _finite(
                reporting_gates.get("pinned_equal_day_mae"),
                float(pinned_metrics["mae"] or 0.0),
            ),
            "candidate_anchor_mae": float(candidate_metrics["mae"] or 0.0),
            "reference_anchor_mae": float(reference_metrics["mae"] or 0.0),
            "pinned_anchor_mae": float(pinned_metrics["mae"] or 0.0),
            "candidate_rmse": float(candidate_metrics["rmse"] or 0.0),
            "reference_rmse": float(reference_metrics["rmse"] or 0.0),
            "pinned_rmse": float(pinned_metrics["rmse"] or 0.0),
            "candidate_mae_5": float(candidate_metrics["mae_5"] or 0.0),
            "candidate_mae_15": float(candidate_metrics["mae_15"] or 0.0),
            "candidate_mae_30": float(candidate_metrics["mae_30"] or 0.0),
            "candidate_mae_60": float(candidate_metrics["mae_60"] or 0.0),
            "candidate_mae_120": float(candidate_metrics["mae_120"] or 0.0),
            "reference_mae_5": float(reference_metrics["mae_5"] or 0.0),
            "reference_mae_15": float(reference_metrics["mae_15"] or 0.0),
            "reference_mae_30": float(reference_metrics["mae_30"] or 0.0),
            "reference_mae_60": float(reference_metrics["mae_60"] or 0.0),
            "reference_mae_120": float(reference_metrics["mae_120"] or 0.0),
            "pinned_mae_5": float(pinned_metrics["mae_5"] or 0.0),
            "pinned_mae_15": float(pinned_metrics["mae_15"] or 0.0),
            "pinned_mae_30": float(pinned_metrics["mae_30"] or 0.0),
            "pinned_mae_60": float(pinned_metrics["mae_60"] or 0.0),
            "pinned_mae_120": float(pinned_metrics["mae_120"] or 0.0),
            "candidate_coverage_80": float(candidate_metrics["coverage_80"] or 0.0),
            "candidate_interval_score_80": float(
                candidate_metrics["interval_score_80"] or 0.0
            ),
            "reference_interval_score_80": float(
                reference_metrics["interval_score_80"] or 0.0
            ),
            "pinned_interval_score_80": float(
                pinned_metrics["interval_score_80"] or 0.0
            ),
            "candidate_hypo_recall": float(
                candidate_metrics["hypo_recall"] or 0.0
            ),
            "reference_hypo_recall": float(
                reference_metrics["hypo_recall"] or 0.0
            ),
            "pinned_hypo_recall": float(pinned_metrics["hypo_recall"] or 0.0),
            "candidate_hypo_fpr": float(candidate_metrics["hypo_fpr"] or 0.0),
            "reference_hypo_fpr": float(reference_metrics["hypo_fpr"] or 0.0),
            "pinned_hypo_fpr": float(pinned_metrics["hypo_fpr"] or 0.0),
            "candidate_hypo_missed_episodes": float(
                candidate_metrics["hypo_missed_episodes"] or 0.0
            ),
            "reference_hypo_missed_episodes": float(
                reference_metrics["hypo_missed_episodes"] or 0.0
            ),
            "pinned_hypo_missed_episodes": float(
                pinned_metrics["hypo_missed_episodes"] or 0.0
            ),
            "candidate_low_zone_mae": float(
                candidate_metrics["low_zone_mae"] or 0.0
            ),
            "reference_low_zone_mae": float(
                reference_metrics["low_zone_mae"] or 0.0
            ),
            "pinned_low_zone_mae": float(
                pinned_metrics["low_zone_mae"] or 0.0
            ),
            "hypo_low_points": float(candidate_metrics["hypo_low_points"] or 0.0),
            "hypo_low_episodes": float(
                candidate_metrics["hypo_low_episodes"] or 0.0
            ),
            "hypo_low_days": float(candidate_metrics["hypo_low_days"] or 0.0),
            "test_days": test_days,
            "test_independent_anchors": len(test_independent),
            "calibration_independent_anchors": len(calibration_independent),
            "winning_days": int(reporting_gates.get("winning_days", 0)),
            "diagnostic_overlapping_mae": float(diagnostic_metrics["mae"]),
            "reliability": reliability_overall,
        }
        for prefix, metrics in (
            ("candidate", candidate_metrics),
            ("reference", reference_metrics),
            ("pinned", pinned_metrics),
        ):
            for metric_name in STATIC_TRAJECTORY_METRICS:
                evaluation[f"{prefix}_{metric_name}"] = float(
                    metrics[metric_name] or 0.0
                )
        for band_index in range(len(STATIC_BANDS)):
            evaluation[f"candidate_coverage_band_{band_index}"] = float(
                candidate_metrics[f"coverage_band_{band_index}"] or 0.0
            )
        for key, value in reporting_gates.items():
            if key in evaluation or key == "finite":
                continue
            if isinstance(value, bool):
                evaluation[f"gate_{key}"] = int(value)
            elif isinstance(value, (int, float)) and math.isfinite(float(value)):
                evaluation[f"gate_{key}"] = value

        development_evaluation = dict(evaluation)
        development_accepted = accepted
        pending_eligible = bool(
            stage_pending
            and development_accepted
            and cutoff_ms == int(latest_available)
        )
        evaluation = dict(evaluation)
        if allow_display_activation:
            # This is an explicitly exploratory engineering selection, not an
            # unbiased validation claim: the artifact may power a clearly
            # labelled chart, never an alert or dose recommendation.
            accepted = bool(development_accepted)
            evaluation["accepted"] = int(accepted)
            evaluation["display_only"] = 1
            evaluation["exploratory"] = 1
            evaluation["unbiased_holdout"] = 0
            evaluation["receipt_causal_validation"] = 0
            evaluation["receipt_causal_gate_required"] = 0
            evaluation["prospective"] = 0
            evaluation["prospective_pending"] = 0
            evaluation["development_only"] = 0
            evaluation["strict_gate_passed"] = int(bool(gates["accepted"]))
        else:
            # Development evidence is diagnostic only. Even the private
            # historical helper cannot emit an activation-eligible artifact.
            accepted = False
            evaluation["accepted"] = 0
            evaluation["prospective_pending"] = int(pending_eligible)
            evaluation["development_only"] = int(not stage_pending)
        if pending_eligible:
            self._assert_prospective_registration_allowed(
                session, cutoff_ms=cutoff_ms
            )

        network = parameters["network"]
        parameter_count = int(
            sum(
                np.asarray(network[name], dtype=np.float64).size
                for name in ("coefficients", "intercept")
            )
        )
        max_received_at = max(int(row.received_at_ms) for row in readings)
        event_revision = source_revision_before[3]
        parameters["artifact"] = {
            "artifact_version": STATIC_ARTIFACT_VERSION,
            "engine_version": FORECAST_ENGINE_VERSION,
            "architecture": STATIC_PERSONAL_ARCHITECTURE,
            "feature_schema": STATIC_FEATURE_SCHEMA,
            "network_kind": STATIC_NETWORK_KIND,
            "reference_kind": STATIC_REFERENCE_KIND,
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
                "raw_source_sha256": frozen_source_fingerprint_before,
                "source_revision": list(source_revision_before),
                "last_reading_at_ms": cutoff_ms,
                "max_received_at_ms": max_received_at,
                "event_revision": event_revision,
                "active_event_count": len(events),
                "glucose_source_revision": source_revision_before[5],
            },
            "split": split,
            "receipt_causal_replay": {
                "role": "historical_availability_diagnostic",
                "source_snapshot_sha256": frozen_source_fingerprint_before,
                "validated_for_activation": False,
                "window_count": len(receipt_causal_windows),
                "local_day_count": receipt_causal_days,
                **receipt_causal_diagnostics,
            },
            "shrinkage_knots": [
                {
                    "minute": minute,
                    "weight": weight,
                }
                for minute, weight in zip(
                    STATIC_SHRINK_KNOT_MINUTES, selected_knots
                )
            ],
            "event_channels": {
                kind: (
                    "bounded_reference_amplitude"
                    if parameters["event_personalization"]["kinds"][kind]["accepted"]
                    else "population_prior_not_identifiable"
                )
                for kind in EVENT_KINDS
            },
            "reliability": {
                "overall": reliability_overall,
                "by_horizon": by_horizon,
                "clinical_validation": False,
                "test_day_cap": confidence_cap,
            },
            "evaluation": evaluation,
            "accepted": accepted,
            "predictor_sha256": _static_predictor_hash(parameters),
        }
        parameters["artifact"]["development_evaluation"] = (
            development_evaluation
        )
        comparator_parameters = champion_parameters
        runtime_dependency_sha256 = _model_parameters_hash(_baseline_parameters())
        if accepted and allow_display_activation:
            parameters["artifact"]["approval"] = {
                "state": "exploratory_retrospective_display",
                "protocol": STATIC_DISPLAY_PROTOCOL,
                "alert_approved": False,
                "unbiased_holdout": False,
                "receipt_causal_validation": False,
                "validation_clock": "sensor_measured_at",
                "receipt_causal_evidence_required": False,
                "use_scope": "chart_only_not_for_dosing_or_alerts",
                "approved_model_version": version,
                "evaluated_at_ms": now,
                "test_days": test_days,
                "independent_anchors": len(test_independent),
                "pinned_comparator_version": champion.version,
                "pinned_comparator_sha256": _model_parameters_hash(
                    comparator_parameters
                ),
                "runtime_dependency_version": BASELINE_VERSION,
                "runtime_dependency_sha256": runtime_dependency_sha256,
                "day_results": day_results,
                "candidate_metrics": candidate_metrics,
                "reference_metrics": reference_metrics,
                "pinned_metrics": pinned_metrics,
                "candidate_horizon_mae": np.mean(
                    np.abs(candidate_prediction - test_target), axis=0
                ).tolist(),
                "reference_horizon_mae": np.mean(
                    np.abs(reference_prediction - test_target), axis=0
                ).tolist(),
                "predictor_sha256": parameters["artifact"]["predictor_sha256"],
            }
        elif pending_eligible:
            parameters["artifact"]["approval"] = {
                "state": "pending_prospective",
                "protocol": STATIC_PROSPECTIVE_PROTOCOL,
                # Forecast approval and predictive-alert approval are separate
                # safety claims. Prospective forecast evaluation never enables
                # notification delivery implicitly.
                "alert_approved": False,
                "minimum_new_days": STATIC_PROSPECTIVE_MIN_DAYS,
                "strictly_after_ms": cutoff_ms,
                "development_gate_passed": bool(development_accepted),
                "freeze_time_ms": now,
                "source_revision": list(source_revision_before),
                "max_received_at_ms": max_received_at,
                "pinned_comparator_version": champion.version,
                "pinned_comparator_sha256": _model_parameters_hash(
                    comparator_parameters
                ),
                "runtime_dependency_version": BASELINE_VERSION,
                "runtime_dependency_sha256": runtime_dependency_sha256,
            }
        parameters["artifact"]["content_sha256"] = _artifact_content_hash(
            parameters
        )
        if accepted and allow_display_activation:
            if not _static_artifact_is_valid(parameters, require_approved=True):
                raise RuntimeError(
                    "constructed exploratory display envelope failed artifact validation"
                )
            reason = (
                "Exploratory retrospective selection passed; eligible for a clearly "
                "labelled chart only. Predictive alerts and dosing use remain disabled "
                "pending receipt-causal prospective validation"
            )
        elif allow_display_activation:
            reason = (
                "Rejected: retrospective display evidence did not safely beat the "
                "persistence and pinned comparators"
            )
        elif pending_eligible:
            reason = (
                "Frozen pending a preregistered fourteen-day prospective evaluation; "
                "not eligible for activation"
            )
        elif stage_pending and development_accepted:
            reason = (
                "Rejected: a prospective artifact must freeze at the latest available reading"
            )
        else:
            reason = (
                "Development-only gate passed; this artifact cannot be activated"
                if development_accepted
                else "Rejected: independent-day evidence did not beat the frozen persistence and pinned comparators"
            )
        candidate = ForecastModelRecord(
            version=version,
            status=(
                "candidate"
                if accepted and allow_display_activation
                else ("pending" if pending_eligible else "rejected")
            ),
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
        frozen_source_fingerprint_after = self._frozen_source_fingerprint(
            session, cutoff_ms=cutoff_ms
        )
        if frozen_source_fingerprint_after != frozen_source_fingerprint_before:
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
        active_pin_version = session.scalar(
            select(BackendMetadataRecord.value_text).where(
                BackendMetadataRecord.key == ACTIVE_MODEL_METADATA_KEY
            )
        )
        fresh_champion = session.scalar(
            select(ForecastModelRecord)
            .where(ForecastModelRecord.version == champion.version)
            .execution_options(populate_existing=True)
        )
        if (
            active_pin_version != champion.version
            or fresh_champion is None
            or fresh_champion.parameters_json != champion_parameters_json
            or not self._runtime_model_dependencies_are_valid(
                session, fresh_champion
            )
        ):
            session.rollback()
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=champion.version,
                reason="Active comparator changed during training; retry from a fresh snapshot",
                sample_count=len(train_windows),
                metrics={"active_comparator_changed": 1},
            )
        if pending_eligible:
            self._assert_prospective_registration_allowed(
                session, cutoff_ms=cutoff_ms
            )
        session.add(candidate)
        session.commit()
        return ForecastTrainResponse(
            status=(
                "accepted"
                if accepted and allow_display_activation
                else ("pending" if pending_eligible else "rejected")
            ),
            promoted=False,
            model_version=version,
            reason=reason,
            sample_count=len(train_windows),
            metrics=evaluation,
        )

    def evaluate_static_candidate(
        self, session: Session, version: str
    ) -> ForecastTrainResponse:
        """Evaluate one frozen pending artifact on strictly future local days.

        This operator-only path never fits, tunes, calibrates, or activates a
        model.  A conclusive decision replaces only the checksummed prospective
        evaluation/approval envelope and the database status.  All predictor
        fields outside ``artifact`` remain byte-for-byte equivalent.
        """

        with self._training_lock:
            return self._evaluate_static_candidate_unlocked(session, version)

    @staticmethod
    def _permanently_reject_pending_candidate(
        session: Session,
        record: ForecastModelRecord,
        *,
        original_parameters_json: str,
        reason: str,
    ) -> ForecastTrainResponse:
        """Atomically terminalize a pending artifact that can never be replayed.

        A permanent causal/snapshot violation must not leave the singleton
        preregistration slot occupied forever. The exact original parameter JSON
        is part of the compare-and-swap so a concurrent evaluator or operator
        change cannot be overwritten by this rejection.
        """

        version = record.version
        sample_count = int(record.sample_count)
        session.rollback()
        session.execute(text("BEGIN IMMEDIATE"))
        outcome = session.execute(
            update(ForecastModelRecord)
            .where(
                ForecastModelRecord.version == version,
                ForecastModelRecord.status == "pending",
                ForecastModelRecord.parameters_json == original_parameters_json,
            )
            .values(status="rejected", decision_reason=reason),
            execution_options={"synchronize_session": False},
        )
        if outcome.rowcount != 1:
            session.rollback()
            raise ValueError(
                "pending artifact changed while applying permanent invalidation"
            )
        session.commit()
        return ForecastTrainResponse(
            status="rejected",
            promoted=False,
            model_version=version,
            reason=reason,
            sample_count=sample_count,
            metrics={"permanent_invalidation": 1},
        )

    def _evaluate_static_candidate_unlocked(
        self, session: Session, version: str
    ) -> ForecastTrainResponse:
        selected = session.get(ForecastModelRecord, version)
        if selected is None:
            raise ValueError(f"unknown forecast model version: {version}")
        if selected.status != "pending":
            raise ValueError(
                f"forecast model {version} is not a pending prospective candidate"
            )
        original_parameters_json = selected.parameters_json
        if not self._stored_static_model_is_valid(
            selected, require_approved=False
        ):
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason="Permanently rejected: pending forecast artifact is corrupt or incompatible",
            )

        parameters = _json_dict(original_parameters_json)
        artifact = parameters.get("artifact", {})
        approval = artifact.get("approval")
        predictor_hash = artifact.get("predictor_sha256")
        comparator_version = (
            approval.get("pinned_comparator_version")
            if isinstance(approval, dict)
            else None
        )
        comparator_hash = (
            approval.get("pinned_comparator_sha256")
            if isinstance(approval, dict)
            else None
        )
        if (
            not isinstance(approval, dict)
            or parameters.get("frozen_calibration", {}).get("safety_envelope")
            != STATIC_ALERT_SAFETY_ENVELOPE
            or approval.get("state") != "pending_prospective"
            or approval.get("protocol") != STATIC_PROSPECTIVE_PROTOCOL
            or artifact.get("accepted") is not False
            or not isinstance(predictor_hash, str)
            or len(predictor_hash) != 64
            or predictor_hash != _static_predictor_hash(parameters)
            or not isinstance(comparator_version, str)
            or not isinstance(comparator_hash, str)
            or len(comparator_hash) != 64
            or not _static_runtime_dependency_envelope_is_valid(approval)
            or int(_finite(approval.get("minimum_new_days"), -1))
            != STATIC_PROSPECTIVE_MIN_DAYS
        ):
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason="Permanently rejected: model was not frozen with the prospective protocol",
            )

        frozen_comparator = session.get(ForecastModelRecord, comparator_version)
        frozen_comparator_hash = (
            _model_parameters_hash(_baseline_parameters())
            if comparator_version == BASELINE_VERSION
            else (
                _model_parameters_hash(_json_dict(frozen_comparator.parameters_json))
                if frozen_comparator is not None
                else None
            )
        )
        if (
            frozen_comparator is None
            or not self._runtime_model_dependencies_are_valid(
                session, frozen_comparator
            )
            or frozen_comparator_hash != comparator_hash
        ):
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason="Permanently rejected: frozen pinned comparator is unavailable or changed",
            )
        frozen_comparator_parameters_json = frozen_comparator.parameters_json

        cutoff_ms = int(_finite(artifact.get("data_cutoff_ms"), -1))
        if cutoff_ms <= 0 or cutoff_ms != int(selected.training_cutoff_ms or -1):
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason="Permanently rejected: pending artifact has an invalid data cutoff",
            )

        source_revision_before = self._source_revision(session)
        training_readings = self._load_readings(
            session, through_ms=cutoff_ms, limit=60_000
        )
        training_events = self._load_events(
            session, through_ms=cutoff_ms, known_through_ms=cutoff_ms
        )
        if (
            not training_readings
            or int(training_readings[-1].measured_at_ms) != cutoff_ms
            or _dataset_fingerprint(training_readings, training_events)
            != artifact.get("dataset_sha256")
        ):
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason=(
                    "Permanently rejected: frozen training snapshot no longer matches "
                    "the artifact"
                ),
            )

        latest_available = session.scalar(
            select(func.max(GlucoseReadingRecord.measured_at_ms))
        )
        if latest_available is None or int(latest_available) <= cutoff_ms:
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=version,
                reason="No glucose readings exist after the frozen data cutoff",
                sample_count=0,
                metrics={"prospective_dense_days": 0},
            )
        readings = self._load_readings(
            session,
            through_ms=int(latest_available),
            from_ms=cutoff_ms - 96 * 60 * 60_000 - MATCH_TOLERANCE_MS,
            limit=200_000,
            earliest_first=True,
        )
        all_windows, causal_manifest = self._prospective_causal_windows(readings)
        future_windows, future_manifest = self._prospective_dense_windows(
            readings, all_windows, strictly_after_ms=cutoff_ms
        )
        future_manifest.update(causal_manifest)
        future_days = int(future_manifest["dense_days"])
        if (
            future_days < STATIC_PROSPECTIVE_MIN_DAYS
            or len(future_windows) < 8 * future_days
        ):
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=version,
                reason=(
                    "Need the complete preregistered fourteen dense local days strictly after "
                    "the frozen data cutoff"
                ),
                sample_count=len(future_windows),
                metrics={
                    "prospective_dense_days": future_days,
                    "prospective_independent_anchors": len(future_windows),
                },
            )

        selected_local_days = {
            int(value)
            for value in future_manifest.get("selected_local_days", [])
        }
        # Point-model promotion remains based on non-overlapping anchors. Alert
        # validation replays every causal five-minute decision on those exact
        # preregistered days so false-alert rate matches live polling rather than
        # being diluted by the independent-window downsampling.
        alert_windows = [
            window
            for window in all_windows
            if readings[window[0]].measured_at_ms > cutoff_ms
            and self._window_local_day(readings, window) in selected_local_days
        ]
        future_manifest["alert_validation_anchors"] = len(alert_windows)

        cohort_windows = alert_windows or future_windows
        cohort_start_ms = min(
            readings[window[0]].measured_at_ms for window in cohort_windows
        )
        cohort_end_ms = max(
            readings[window[0]].measured_at_ms + HORIZON_MINUTES * 60_000
            for window in cohort_windows
        )
        freeze_time_ms = int(_finite(approval.get("freeze_time_ms"), -1))
        received_before_freeze = session.scalar(
            select(func.count(GlucoseReadingRecord.reading_id)).where(
                GlucoseReadingRecord.measured_at_ms > cutoff_ms,
                GlucoseReadingRecord.measured_at_ms
                <= cohort_end_ms + MATCH_TOLERANCE_MS,
                GlucoseReadingRecord.received_at_ms <= freeze_time_ms,
            )
        )
        if freeze_time_ms <= 0 or int(received_before_freeze or 0) > 0:
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason=(
                    "Permanently rejected: prospective cohort contains readings "
                    "ingested before the freeze"
                ),
            )

        # Current rows retain only their latest edit/tombstone. If such a
        # mutation happened during or after this cohort, the earlier event value
        # cannot be replayed exactly without immutable shadow forecasts. Fail
        # closed and require a newly frozen candidate after the mutation.
        unreplayable_mutations = session.scalar(
            select(func.count(IntakeEventRecord.id)).where(
                IntakeEventRecord.updated_at_ms > IntakeEventRecord.created_at_ms,
                IntakeEventRecord.updated_at_ms >= cohort_start_ms,
                IntakeEventRecord.occurred_at_ms <= cohort_end_ms,
                IntakeEventRecord.occurred_at_ms
                >= cohort_start_ms - 96 * 60 * 60_000,
            )
        )
        if int(unreplayable_mutations or 0) > 0:
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason=(
                    "Permanently rejected: intake edits/deletions overlap the frozen "
                    "cohort and cannot be replayed exactly; stage a new candidate"
                ),
            )

        decision_times_ms = {
            window[0]: int(readings[window[0]].received_at_ms)
            for window in future_windows
        }
        alert_decision_times_ms = {
            window[0]: int(readings[window[0]].received_at_ms)
            for window in alert_windows
        }
        events = self._load_events(
            session,
            through_ms=max(
                [*decision_times_ms.values(), *alert_decision_times_ms.values()]
            ),
            known_through_ms=max(
                [*decision_times_ms.values(), *alert_decision_times_ms.values()]
            ),
        )
        features, reference, target = self._dataset_for_parameters(
            readings,
            events,
            future_windows,
            parameters,
            decision_times_ms=decision_times_ms,
        )
        reference_prediction = np.clip(reference, 20.0, 600.0)
        raw_prediction = np.clip(
            reference + _network_predict_batch(features, parameters), 20.0, 600.0
        )
        frozen_sigma = np.asarray(
            parameters["frozen_calibration"]["sigma_mg_dl"], dtype=np.float64
        )
        candidate_prediction, candidate_sigma = _apply_static_predictor(
            raw_prediction,
            reference_prediction,
            frozen_sigma,
            parameters,
        )
        reference_sigma = np.asarray(
            parameters["frozen_calibration"]["reference_sigma_mg_dl"],
            dtype=np.float64,
        )

        def parameters_for_record(record: ForecastModelRecord) -> dict[str, Any]:
            return (
                _baseline_parameters()
                if record.version == BASELINE_VERSION
                else (_json_dict(record.parameters_json) or _default_parameters())
            )

        def comparator_outputs(
            record: ForecastModelRecord,
            evaluation_windows: Sequence[tuple[int, np.ndarray]],
            evaluation_decision_times_ms: dict[int, int],
        ) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
            comparator_parameters = parameters_for_record(record)
            comparator_x, comparator_base, comparator_target = (
                self._dataset_for_parameters(
                    readings,
                    events,
                    evaluation_windows,
                    comparator_parameters,
                    decision_times_ms=evaluation_decision_times_ms,
                )
            )
            comparator_prediction = np.clip(
                comparator_base
                + _network_predict_batch(comparator_x, comparator_parameters),
                20.0,
                600.0,
            )
            comparator_sigma = np.asarray(
                comparator_parameters.get(
                    "residual_sigma", _default_parameters()["residual_sigma"]
                ),
                dtype=np.float64,
            )
            if record.architecture == STATIC_PERSONAL_ARCHITECTURE:
                comparator_reference = self._reference_for_windows(
                    readings,
                    events,
                    evaluation_windows,
                    comparator_parameters,
                    decision_times_ms=evaluation_decision_times_ms,
                )
                comparator_prediction, comparator_sigma = _apply_static_predictor(
                    comparator_prediction,
                    comparator_reference,
                    comparator_sigma,
                    comparator_parameters,
                )
            elif record.version == BASELINE_VERSION:
                comparator_sigma = comparator_sigma * 1.25
            if comparator_sigma.shape not in {
                (HORIZON_STEPS,),
                comparator_prediction.shape,
            } or not np.isfinite(comparator_sigma).all():
                comparator_sigma = np.asarray(
                    _default_parameters()["residual_sigma"], dtype=np.float64
                )
            return comparator_prediction, comparator_sigma, comparator_target

        pinned_prediction, pinned_sigma, pinned_target = comparator_outputs(
            frozen_comparator, future_windows, decision_times_ms
        )
        current_champion = self._champion(session)
        current_champion_parameters_json = current_champion.parameters_json
        current_prediction: np.ndarray | None = None
        current_sigma: np.ndarray | None = None
        current_target: np.ndarray | None = None
        if current_champion.version != frozen_comparator.version:
            current_prediction, current_sigma, current_target = comparator_outputs(
                current_champion, future_windows, decision_times_ms
            )

        candidate_metrics: dict[str, float | None] = {
            **self._metrics(candidate_prediction, target),
            **self._trajectory_diagnostics(candidate_prediction, reference_prediction),
            **self._interval_metrics(
                candidate_prediction,
                target,
                candidate_sigma,
                readings=readings,
                windows=future_windows,
            ),
        }
        reference_metrics: dict[str, float | None] = {
            **self._metrics(reference_prediction, target),
            **self._trajectory_diagnostics(reference_prediction, reference_prediction),
            **self._interval_metrics(
                reference_prediction,
                target,
                reference_sigma,
                readings=readings,
                windows=future_windows,
            ),
        }
        pinned_metrics: dict[str, float | None] = {
            **self._metrics(pinned_prediction, pinned_target),
            **self._trajectory_diagnostics(pinned_prediction, reference_prediction),
            **self._interval_metrics(
                pinned_prediction,
                pinned_target,
                pinned_sigma,
                readings=readings,
                windows=future_windows,
            ),
        }
        day_results = self._equal_day_results(
            readings,
            future_windows,
            candidate_prediction,
            reference_prediction,
            pinned_prediction,
            target,
        )
        gates = self.static_promotion_gates(
            candidate_metrics,
            reference_metrics,
            pinned_metrics,
            day_results,
            test_day_count=len(day_results),
            finite=bool(
                np.isfinite(candidate_prediction).all()
                and np.isfinite(candidate_sigma).all()
            ),
        )
        non_hypo_gates = self._non_hypo_promotion_gates(
            candidate_metrics,
            reference_metrics,
            pinned_metrics,
            day_results,
            test_day_count=len(day_results),
            finite=bool(
                np.isfinite(candidate_prediction).all()
                and np.isfinite(candidate_sigma).all()
            ),
        )
        current_gates: dict[str, bool | float | int] | None = None
        current_non_hypo_gates: dict[str, bool | float | int] | None = None
        current_day_results: list[dict[str, float]] | None = None
        current_metrics: dict[str, float | None] | None = None
        if (
            current_prediction is not None
            and current_sigma is not None
            and current_target is not None
        ):
            current_metrics = {
                **self._metrics(current_prediction, current_target),
                **self._trajectory_diagnostics(current_prediction, reference_prediction),
                **self._interval_metrics(
                    current_prediction,
                    current_target,
                    current_sigma,
                    readings=readings,
                    windows=future_windows,
                ),
            }
            current_day_results = self._equal_day_results(
                readings,
                future_windows,
                candidate_prediction,
                reference_prediction,
                current_prediction,
                target,
            )
            current_gates = self.static_promotion_gates(
                candidate_metrics,
                reference_metrics,
                current_metrics,
                current_day_results,
                test_day_count=len(current_day_results),
                finite=bool(np.isfinite(current_prediction).all()),
            )
            current_non_hypo_gates = self._non_hypo_promotion_gates(
                candidate_metrics,
                reference_metrics,
                current_metrics,
                current_day_results,
                test_day_count=len(current_day_results),
                finite=bool(np.isfinite(current_prediction).all()),
            )

        alert_features, alert_reference, alert_target = self._dataset_for_parameters(
            readings,
            events,
            alert_windows,
            parameters,
            decision_times_ms=alert_decision_times_ms,
        )
        alert_reference_prediction = np.clip(alert_reference, 20.0, 600.0)
        alert_raw_prediction = np.clip(
            alert_reference
            + _network_predict_batch(alert_features, parameters),
            20.0,
            600.0,
        )
        alert_candidate_prediction, alert_candidate_sigma = _apply_static_predictor(
            alert_raw_prediction,
            alert_reference_prediction,
            frozen_sigma,
            parameters,
        )
        alert_pinned_prediction, alert_pinned_sigma, alert_pinned_target = (
            comparator_outputs(
                frozen_comparator, alert_windows, alert_decision_times_ms
            )
        )
        if current_champion.version != frozen_comparator.version:
            (
                alert_current_prediction,
                alert_current_sigma,
                alert_current_target,
            ) = comparator_outputs(
                current_champion, alert_windows, alert_decision_times_ms
            )
        else:
            alert_current_prediction = alert_pinned_prediction
            alert_current_sigma = alert_pinned_sigma
            alert_current_target = alert_pinned_target
        alert_anchor_times = [
            int(readings[window[0]].measured_at_ms) for window in alert_windows
        ]
        alert_issue_times = [
            int(alert_decision_times_ms[window[0]]) for window in alert_windows
        ]
        alert_anchor_glucose = [
            float(readings[window[0]].glucose_mg_dl) for window in alert_windows
        ]
        alert_anchor_offsets = [
            int(readings[window[0]].utc_offset_minutes or 0)
            for window in alert_windows
        ]

        reading_times = [row.measured_at_ms for row in readings]
        sorted_events = sorted(events, key=lambda item: item.occurred_at_ms)
        alert_runtime_contexts: list[
            tuple[list[GlucoseReadingRecord], list[_Event], int]
        ] = []
        for window in alert_windows:
            anchor_index = window[0]
            anchor = readings[anchor_index]
            decision_ms = alert_decision_times_ms[anchor_index]
            history_start = (
                anchor.measured_at_ms
                - CONTEXT_HISTORY_MINUTES * 60_000
                - MATCH_TOLERANCE_MS
            )
            history_index = bisect.bisect_left(reading_times, history_start)
            causal_readings = [
                row
                for row in readings[history_index : anchor_index + 1]
                if int(row.received_at_ms) <= decision_ms
            ]
            causal_events = [
                event
                for event in sorted_events
                if event.occurred_at_ms <= decision_ms
                and _event_known_at(event) <= decision_ms
                and event.occurred_at_ms
                >= anchor.measured_at_ms - 96 * 60 * 60_000
            ]
            alert_runtime_contexts.append(
                (causal_readings, causal_events, anchor.measured_at_ms)
            )

        def runtime_adjustments(
            runtime_parameters: dict[str, Any],
        ) -> tuple[np.ndarray, list[bool]]:
            meal_sigmas: list[np.ndarray] = []
            delivery_ready: list[bool] = []
            for causal_readings, causal_events, anchor_ms in alert_runtime_contexts:
                status, _confidence, _coverage, meal_sigma, _event_confidence = (
                    self._runtime_forecast_adjustments(
                        causal_readings,
                        causal_events,
                        anchor_ms,
                        runtime_parameters,
                    )
                )
                meal_sigmas.append(meal_sigma)
                delivery_ready.append(status == "ok")
            return np.vstack(meal_sigmas), delivery_ready

        candidate_meal_sigma, alert_delivery_ready = runtime_adjustments(parameters)
        pinned_parameters = parameters_for_record(frozen_comparator)
        pinned_meal_sigma, pinned_delivery_ready = runtime_adjustments(
            pinned_parameters
        )
        if current_champion.version == frozen_comparator.version:
            current_meal_sigma = pinned_meal_sigma
            current_delivery_ready = pinned_delivery_ready
        else:
            current_meal_sigma, current_delivery_ready = runtime_adjustments(
                parameters_for_record(current_champion)
            )

        # This transform is identical to live `current()`: uncertain confirmed
        # meals may only widen the interval, never alter the frozen median.
        alert_candidate_sigma = np.sqrt(
            alert_candidate_sigma * alert_candidate_sigma
            + candidate_meal_sigma * candidate_meal_sigma
        )
        alert_reference_sigma = np.sqrt(
            reference_sigma * reference_sigma
            + candidate_meal_sigma * candidate_meal_sigma
        )
        alert_pinned_sigma = np.sqrt(
            alert_pinned_sigma * alert_pinned_sigma
            + pinned_meal_sigma * pinned_meal_sigma
        )
        alert_current_sigma = np.sqrt(
            alert_current_sigma * alert_current_sigma
            + current_meal_sigma * current_meal_sigma
        )

        def alert_metrics(
            prediction: np.ndarray,
            target_values: np.ndarray,
            sigma_values: np.ndarray,
            delivery_ready: Sequence[bool],
        ) -> dict[str, float | None]:
            return _alert_episode_metrics(
                prediction,
                target_values,
                sigma_values,
                anchor_times_ms=alert_anchor_times,
                decision_times_ms=alert_issue_times,
                anchor_glucose_mg_dl=alert_anchor_glucose,
                anchor_utc_offset_minutes=alert_anchor_offsets,
                delivery_ready=delivery_ready,
            )

        candidate_alert_metrics = alert_metrics(
            alert_candidate_prediction,
            alert_target,
            alert_candidate_sigma,
            alert_delivery_ready,
        )
        reference_alert_metrics = alert_metrics(
            alert_reference_prediction,
            alert_target,
            alert_reference_sigma,
            alert_delivery_ready,
        )
        pinned_alert_metrics = alert_metrics(
            alert_pinned_prediction,
            alert_pinned_target,
            alert_pinned_sigma,
            pinned_delivery_ready,
        )
        current_alert_metrics = alert_metrics(
            alert_current_prediction,
            alert_current_target,
            alert_current_sigma,
            current_delivery_ready,
        )
        alert_gates = _alert_validation_gates(
            candidate_alert_metrics,
            reference_alert_metrics,
            pinned_alert_metrics,
            current_alert_metrics,
        )
        accepted = bool(gates["accepted"]) and bool(
            current_gates is None or current_gates["accepted"]
        )
        alert_approved = bool(accepted and alert_gates["accepted"])
        hypo_evidence_sufficient = bool(
            float(candidate_metrics.get("hypo_low_points") or 0.0) >= 40
            and float(candidate_metrics.get("hypo_low_episodes") or 0.0) >= 5
            and float(candidate_metrics.get("hypo_low_days") or 0.0) >= 4
        )
        inconclusive = bool(
            not hypo_evidence_sufficient
            and non_hypo_gates.get("accepted")
            and (
                current_non_hypo_gates is None
                or current_non_hypo_gates.get("accepted")
            )
        )
        evaluation: dict[str, float | int | None] = {
            "accepted": int(accepted),
            "prospective": 1,
            "inconclusive": int(inconclusive),
            "current_comparator_gate_passed": int(
                current_gates is None or bool(current_gates["accepted"])
            ),
            "alert_validation_passed": int(bool(alert_gates["accepted"])),
            "alert_evidence_sufficient": int(
                bool(alert_gates["evidence_sufficient"])
            ),
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
            "pinned_rmse": float(pinned_metrics["rmse"] or 0.0),
            "candidate_mae_5": float(candidate_metrics["mae_5"] or 0.0),
            "candidate_mae_15": float(candidate_metrics["mae_15"] or 0.0),
            "candidate_mae_30": float(candidate_metrics["mae_30"] or 0.0),
            "candidate_mae_60": float(candidate_metrics["mae_60"] or 0.0),
            "candidate_mae_120": float(candidate_metrics["mae_120"] or 0.0),
            "reference_mae_5": float(reference_metrics["mae_5"] or 0.0),
            "reference_mae_15": float(reference_metrics["mae_15"] or 0.0),
            "reference_mae_30": float(reference_metrics["mae_30"] or 0.0),
            "reference_mae_60": float(reference_metrics["mae_60"] or 0.0),
            "reference_mae_120": float(reference_metrics["mae_120"] or 0.0),
            "pinned_mae_5": float(pinned_metrics["mae_5"] or 0.0),
            "pinned_mae_15": float(pinned_metrics["mae_15"] or 0.0),
            "pinned_mae_30": float(pinned_metrics["mae_30"] or 0.0),
            "pinned_mae_60": float(pinned_metrics["mae_60"] or 0.0),
            "pinned_mae_120": float(pinned_metrics["mae_120"] or 0.0),
            "candidate_coverage_80": float(
                candidate_metrics["coverage_80"] or 0.0
            ),
            "candidate_interval_score_80": float(
                candidate_metrics["interval_score_80"] or 0.0
            ),
            "reference_interval_score_80": float(
                reference_metrics["interval_score_80"] or 0.0
            ),
            "pinned_interval_score_80": float(
                pinned_metrics["interval_score_80"] or 0.0
            ),
            "candidate_hypo_recall": float(
                candidate_metrics["hypo_recall"] or 0.0
            ),
            "reference_hypo_recall": float(
                reference_metrics["hypo_recall"] or 0.0
            ),
            "pinned_hypo_recall": float(pinned_metrics["hypo_recall"] or 0.0),
            "candidate_hypo_fpr": float(candidate_metrics["hypo_fpr"] or 0.0),
            "reference_hypo_fpr": float(reference_metrics["hypo_fpr"] or 0.0),
            "pinned_hypo_fpr": float(pinned_metrics["hypo_fpr"] or 0.0),
            "candidate_hypo_missed_episodes": float(
                candidate_metrics["hypo_missed_episodes"] or 0.0
            ),
            "reference_hypo_missed_episodes": float(
                reference_metrics["hypo_missed_episodes"] or 0.0
            ),
            "pinned_hypo_missed_episodes": float(
                pinned_metrics["hypo_missed_episodes"] or 0.0
            ),
            "candidate_low_zone_mae": float(
                candidate_metrics["low_zone_mae"] or 0.0
            ),
            "reference_low_zone_mae": float(
                reference_metrics["low_zone_mae"] or 0.0
            ),
            "pinned_low_zone_mae": float(
                pinned_metrics["low_zone_mae"] or 0.0
            ),
            "hypo_low_points": float(candidate_metrics["hypo_low_points"] or 0.0),
            "hypo_low_episodes": float(
                candidate_metrics["hypo_low_episodes"] or 0.0
            ),
            "hypo_low_days": float(candidate_metrics["hypo_low_days"] or 0.0),
            "test_days": len(day_results),
            "test_independent_anchors": len(future_windows),
            "winning_days": int(gates.get("winning_days", 0)),
        }
        for prefix, metrics in (
            ("candidate", candidate_metrics),
            ("reference", reference_metrics),
            ("pinned", pinned_metrics),
        ):
            for metric_name in STATIC_TRAJECTORY_METRICS:
                evaluation[f"{prefix}_{metric_name}"] = float(
                    metrics[metric_name] or 0.0
                )
        for band_index in range(len(STATIC_BANDS)):
            evaluation[f"candidate_coverage_band_{band_index}"] = float(
                candidate_metrics[f"coverage_band_{band_index}"] or 0.0
            )
        for key, value in gates.items():
            if key in evaluation or key == "finite":
                continue
            if isinstance(value, bool):
                evaluation[f"gate_{key}"] = int(value)
            elif isinstance(value, (int, float)) and math.isfinite(float(value)):
                evaluation[f"gate_{key}"] = value

        prospective_days = len(day_results)
        candidate_horizon_mae = np.mean(
            np.abs(candidate_prediction - target), axis=0
        ).tolist()
        reference_horizon_mae = np.mean(
            np.abs(reference_prediction - target), axis=0
        ).tolist()
        prospective_reliability = _static_reliability(
            candidate_metrics,
            reference_metrics,
            (
                gates
                if "winning_days" in gates
                else non_hypo_gates
            ),
            test_days=prospective_days,
            independent_anchors=len(future_windows),
            candidate_horizon_mae=candidate_horizon_mae,
            reference_horizon_mae=reference_horizon_mae,
        )

        evaluated_at_ms = _now_ms()
        evaluated_parameters = json.loads(original_parameters_json)
        evaluated_artifact = evaluated_parameters["artifact"]
        evaluated_artifact["evaluation"] = evaluation
        evaluated_artifact["accepted"] = accepted
        evaluated_artifact["reliability"] = prospective_reliability
        evaluated_artifact["approval"] = {
            **approval,
            **future_manifest,
            "state": (
                "approved_prospective"
                if accepted
                else (
                    "inconclusive_prospective"
                    if inconclusive
                    else "rejected_prospective"
                )
            ),
            "protocol": STATIC_PROSPECTIVE_PROTOCOL,
            "minimum_new_days": STATIC_PROSPECTIVE_MIN_DAYS,
            "strictly_after_ms": cutoff_ms,
            "evaluated_at_ms": evaluated_at_ms,
            "cohort_start_ms": cohort_start_ms,
            "cohort_end_ms": cohort_end_ms,
            "alert_approved": alert_approved,
            "alert_validation": {
                "protocol": ALERT_VALIDATION_PROTOCOL,
                "thresholds": _alert_validation_thresholds(),
                "local_days_sha256": future_manifest["local_days_sha256"],
                "cohort_start_ms": cohort_start_ms,
                "cohort_end_ms": cohort_end_ms,
                "dense_days": future_days,
                "candidate_metrics": candidate_alert_metrics,
                "reference_metrics": reference_alert_metrics,
                "pinned_metrics": pinned_alert_metrics,
                "current_metrics": current_alert_metrics,
                "gates": alert_gates,
            },
            "last_reading_at_ms": cohort_end_ms,
            "pinned_comparator_version": frozen_comparator.version,
            "pinned_comparator_sha256": comparator_hash,
            "current_comparator_version": current_champion.version,
            "pinned_day_results": day_results,
            "current_day_results": (
                current_day_results
                if current_day_results is not None
                else day_results
            ),
            "current_metrics": (
                current_metrics if current_metrics is not None else pinned_metrics
            ),
            "candidate_metrics": candidate_metrics,
            "reference_metrics": reference_metrics,
            "pinned_metrics": pinned_metrics,
            "candidate_horizon_mae": candidate_horizon_mae,
            "reference_horizon_mae": reference_horizon_mae,
            "predictor_sha256": predictor_hash,
        }
        if _static_predictor_hash(evaluated_parameters) != predictor_hash:
            raise RuntimeError("prospective evaluation attempted to mutate the predictor")
        evaluated_artifact["content_sha256"] = _artifact_content_hash(
            evaluated_parameters
        )
        if accepted and not _static_artifact_is_valid(
            evaluated_parameters, require_approved=True
        ):
            raise RuntimeError(
                "constructed prospective approval failed static artifact validation"
            )

        # Do not hold a write transaction while generating thousands of
        # forecasts.  Reserve it only for an atomic revision + artifact check.
        session.rollback()
        session.execute(text("BEGIN IMMEDIATE"))
        if self._source_revision(session) != source_revision_before:
            session.rollback()
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=version,
                reason="Source glucose/intake data changed during evaluation; retry",
                sample_count=len(future_windows),
                metrics={"source_revision_changed": 1},
            )
        active_pin_version = session.scalar(
            select(BackendMetadataRecord.value_text).where(
                BackendMetadataRecord.key == ACTIVE_MODEL_METADATA_KEY
            )
        )
        fresh_current_champion = session.scalar(
            select(ForecastModelRecord)
            .where(ForecastModelRecord.version == current_champion.version)
            .execution_options(populate_existing=True)
        )
        if (
            active_pin_version != current_champion.version
            or fresh_current_champion is None
            or fresh_current_champion.parameters_json
            != current_champion_parameters_json
            or not self._runtime_model_dependencies_are_valid(
                session, fresh_current_champion
            )
        ):
            session.rollback()
            return ForecastTrainResponse(
                status="skipped",
                promoted=False,
                model_version=version,
                reason="Active comparator changed during evaluation; retry",
                sample_count=len(future_windows),
                metrics={"active_comparator_changed": 1},
            )
        fresh_frozen_comparator = session.scalar(
            select(ForecastModelRecord)
            .where(ForecastModelRecord.version == frozen_comparator.version)
            .execution_options(populate_existing=True)
        )
        if (
            fresh_frozen_comparator is None
            or fresh_frozen_comparator.parameters_json
            != frozen_comparator_parameters_json
            or not self._runtime_model_dependencies_are_valid(
                session, fresh_frozen_comparator
            )
            or _model_parameters_hash(
                _json_dict(fresh_frozen_comparator.parameters_json)
            )
            != comparator_hash
        ):
            session.rollback()
            return self._permanently_reject_pending_candidate(
                session,
                selected,
                original_parameters_json=original_parameters_json,
                reason=(
                    "Permanently rejected: frozen pinned comparator changed during "
                    "prospective evaluation"
                ),
            )
        stored = session.get(ForecastModelRecord, version)
        if (
            stored is None
            or stored.status != "pending"
            or stored.parameters_json != original_parameters_json
        ):
            session.rollback()
            raise ValueError("pending artifact changed during prospective evaluation")
        stored.parameters_json = json.dumps(
            evaluated_parameters, separators=(",", ":"), allow_nan=False
        )
        stored.metrics_json = json.dumps(
            evaluation, separators=(",", ":"), allow_nan=False
        )
        stored.status = (
            "candidate" if accepted else ("inconclusive" if inconclusive else "rejected")
        )
        stored.decision_reason = (
            (
                "Passed prospective forecast and episode-level alert gates; "
                "explicit activation is required"
                if alert_approved
                else (
                    "Passed prospective forecast gates; predictive alerts remain "
                    "shadow because episode-level alert evidence was insufficient or unsafe"
                )
            )
            if accepted
            else (
                "Prospective cohort was inconclusive because it lacked preregistered "
                "hypoglycemia evidence"
                if inconclusive
                else "Rejected by prospective point, interval, or hypoglycemia safety gates"
            )
        )
        session.commit()
        return ForecastTrainResponse(
            status=(
                "accepted" if accepted else ("inconclusive" if inconclusive else "rejected")
            ),
            promoted=False,
            model_version=version,
            reason=stored.decision_reason,
            sample_count=len(future_windows),
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
        latest_attempt_status: ForecastLatestTrainingAttempt | None = None
        if latest_attempt is not None:
            raw_latest_metrics = _json_dict(latest_attempt.metrics_json)
            latest_attempt_status = ForecastLatestTrainingAttempt(
                model_version=latest_attempt.version,
                status=latest_attempt.status,
                trained_at_ms=int(latest_attempt.trained_at_ms),
                training_cutoff_ms=latest_attempt.training_cutoff_ms,
                sample_count=int(latest_attempt.sample_count),
                decision_reason=latest_attempt.decision_reason,
                metrics={
                    key: value
                    for key, value in raw_latest_metrics.items()
                    if isinstance(key, str)
                    and isinstance(value, (int, float))
                    and not isinstance(value, bool)
                    and math.isfinite(float(value))
                },
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
        current_glucose_revision = int(
            session.scalar(
                select(cast(BackendMetadataRecord.value_text, Integer)).where(
                    BackendMetadataRecord.key
                    == GLUCOSE_SOURCE_REVISION_METADATA_KEY
                )
            )
            or 0
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
                or (
                    "glucose_source_revision" in snapshot
                    and int(
                        _finite(snapshot.get("glucose_source_revision"), -1)
                    )
                    != current_glucose_revision
                )
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
                latest_attempt=latest_attempt_status,
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
        with np.errstate(over="raise", divide="raise", invalid="raise"):
            normalized = np.clip((features - x_mean) / x_scale, -8.0, 8.0)
        if network.get("kind") == STATIC_NETWORK_KIND:
            coefficients = finite_array(
                "coefficients", (x_mean.size, HORIZON_STEPS)
            )
            intercept = finite_array("intercept", (HORIZON_STEPS,))
            prediction = normalized @ coefficients + intercept
        else:
            w1_raw = np.asarray(network["w1"], dtype=np.float64)
            if w1_raw.ndim != 2 or w1_raw.shape[0] != x_mean.size:
                return fallback
            hidden_size = w1_raw.shape[1]
            if hidden_size <= 0:
                return fallback
            w1 = finite_array("w1", (x_mean.size, hidden_size))
            b1 = finite_array("b1", (hidden_size,))
            with np.errstate(over="raise", divide="raise", invalid="raise"):
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
