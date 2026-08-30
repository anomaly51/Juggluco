"""Bounded, event-group-safe personalization for forecast event amplitudes.

The main forecast remains responsible for training/inference sample construction, full
point/interval validation, and every alert gate.  This module can only learn a
small amplitude correction around an existing population-prior event curve.  A
fit produced here is deliberately incapable of approving alerts or changing an
event's sign or timing.
"""

from __future__ import annotations

import copy
from dataclasses import dataclass
import hashlib
import json
import math
from typing import Any, Literal, Mapping, Sequence

import numpy as np


EventKind = Literal["meal", "rapid", "long"]

EVENT_PERSONALIZATION_PROTOCOL = "bounded-event-amplitude-v1"
EVENT_PERSONALIZATION_USE_SCOPE = "candidate-point-forecast-only"
EVENT_KINDS: tuple[EventKind, ...] = ("meal", "rapid", "long")
COMBINED_EVENT_VALIDATION_PROTOCOL = "combined-event-response-holdout-v1"
COMBINED_EVENT_PREDICTION_TRANSFORM = "aggregate-capped-deltas-clip-20-600-v1"


def _is_sha256(value: Any) -> bool:
    return bool(
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


@dataclass(frozen=True, slots=True)
class EventEffectSample:
    """One training observation associated with exactly one intake event.

    ``population_effect_mg_dl`` is the signed contribution already predicted by
    the population-prior event curve. ``observed_residual_mg_dl`` is the target
    minus the otherwise complete unpersonalized forecast. Multiple observations
    may share an ``event_id``; the splitter and metrics always keep that group
    intact so a single meal or injection cannot leak into its own validation.
    """

    event_id: str
    kind: EventKind
    occurred_at_ms: int
    population_effect_mg_dl: Sequence[float]
    observed_residual_mg_dl: Sequence[float]
    safety_mask: Sequence[bool] | None = None
    weight: float = 1.0


@dataclass(frozen=True, slots=True)
class EventResponseWindow:
    """A complete population forecast and its held-out response.

    Event contributions retain their real ``(kind, event_id)`` identities. The
    vectors share one horizon axis; neither effects nor target residuals may be
    robust-clipped here because this gate scores the exact runtime correction.
    ``usable_mask`` excludes horizons not available to the originating replay.
    Low-target/reference horizons are always safety-scored, even if a supplied
    ``safety_mask`` omitted them.
    """

    reference_mg_dl: Sequence[float]
    target_mg_dl: Sequence[float]
    event_effects: Mapping[tuple[EventKind, str], Sequence[float]]
    usable_mask: Sequence[bool] | None = None
    safety_mask: Sequence[bool] | None = None


@dataclass(frozen=True, slots=True)
class EventKindPolicy:
    minimum_groups: int
    minimum_train_groups: int
    minimum_validation_groups: int
    prior_equivalent_groups: float
    minimum_relative_improvement: float
    minimum_absolute_improvement_mg_dl: float
    minimum_improved_group_fraction: float
    maximum_group_regression_mg_dl: float
    scale_low: float
    scale_high: float
    maximum_absolute_adjustment_mg_dl: float

    def __post_init__(self) -> None:
        if self.minimum_groups < 2:
            raise ValueError("minimum_groups must be at least two")
        if self.minimum_train_groups < 1:
            raise ValueError("minimum_train_groups must be positive")
        if self.minimum_validation_groups < 1:
            raise ValueError("minimum_validation_groups must be positive")
        if (
            self.minimum_train_groups + self.minimum_validation_groups
            > self.minimum_groups
        ):
            raise ValueError("minimum group split exceeds minimum_groups")
        if not math.isfinite(self.prior_equivalent_groups) or self.prior_equivalent_groups <= 0:
            raise ValueError("prior_equivalent_groups must be finite and positive")
        if not 0.0 <= self.minimum_relative_improvement <= 1.0:
            raise ValueError("minimum_relative_improvement must be between zero and one")
        if self.minimum_absolute_improvement_mg_dl < 0:
            raise ValueError("minimum_absolute_improvement_mg_dl cannot be negative")
        if not 0.0 <= self.minimum_improved_group_fraction <= 1.0:
            raise ValueError("minimum_improved_group_fraction must be between zero and one")
        if self.maximum_group_regression_mg_dl < 0:
            raise ValueError("maximum_group_regression_mg_dl cannot be negative")
        if not 0.0 < self.scale_low <= 1.0 <= self.scale_high:
            raise ValueError("scale bounds must be positive and contain the population prior")
        if self.maximum_absolute_adjustment_mg_dl <= 0:
            raise ValueError("maximum_absolute_adjustment_mg_dl must be positive")


def _default_policy(kind: EventKind) -> EventKindPolicy:
    # Eight independent event groups is the minimum for every learned channel.
    # In particular, long insulin stays on its broad population depot prior with
    # fewer than eight injections. The scalar correction is strongly shrunk by
    # twelve prior-equivalent groups and bounded more tightly for basal insulin.
    common = {
        "minimum_groups": 8,
        "minimum_train_groups": 6,
        "minimum_validation_groups": 2,
        "prior_equivalent_groups": 12.0,
        "minimum_relative_improvement": 0.01,
        "minimum_absolute_improvement_mg_dl": 0.25,
        "minimum_improved_group_fraction": 0.60,
        "maximum_group_regression_mg_dl": 1.0,
    }
    if kind == "long":
        return EventKindPolicy(
            **common,
            scale_low=0.80,
            scale_high=1.20,
            maximum_absolute_adjustment_mg_dl=12.0,
        )
    return EventKindPolicy(
        **common,
        scale_low=0.65,
        scale_high=1.35,
        maximum_absolute_adjustment_mg_dl=30.0,
    )


@dataclass(frozen=True, slots=True)
class EventPersonalizationConfig:
    meal: EventKindPolicy = _default_policy("meal")
    rapid: EventKindPolicy = _default_policy("rapid")
    long: EventKindPolicy = _default_policy("long")
    validation_fraction: float = 0.25
    maximum_points_per_sample: int = 24
    maximum_abs_effect_mg_dl: float = 180.0
    maximum_abs_residual_mg_dl: float = 180.0
    minimum_effect_energy: float = 1e-6
    safety_regression_tolerance_mg_dl: float = 0.0

    def __post_init__(self) -> None:
        if not 0.0 < self.validation_fraction < 1.0:
            raise ValueError("validation_fraction must be between zero and one")
        if self.maximum_points_per_sample < 1:
            raise ValueError("maximum_points_per_sample must be positive")
        if self.maximum_abs_effect_mg_dl <= 0:
            raise ValueError("maximum_abs_effect_mg_dl must be positive")
        if self.maximum_abs_residual_mg_dl <= 0:
            raise ValueError("maximum_abs_residual_mg_dl must be positive")
        if self.minimum_effect_energy <= 0:
            raise ValueError("minimum_effect_energy must be positive")
        if self.safety_regression_tolerance_mg_dl < 0:
            raise ValueError("safety_regression_tolerance_mg_dl cannot be negative")

    def policy(self, kind: EventKind) -> EventKindPolicy:
        return getattr(self, kind)


@dataclass(frozen=True, slots=True)
class _PreparedSample:
    event_id: str
    kind: EventKind
    occurred_at_ms: int
    effect: np.ndarray
    residual: np.ndarray
    safety: np.ndarray
    weight: float


def _prepare_sample(
    sample: EventEffectSample,
    config: EventPersonalizationConfig,
) -> _PreparedSample | None:
    if sample.kind not in EVENT_KINDS:
        return None
    event_id = str(sample.event_id).strip()
    try:
        occurred_at_ms = int(sample.occurred_at_ms)
    except (TypeError, ValueError, OverflowError):
        return None
    if not event_id or len(event_id) > 160 or occurred_at_ms <= 0:
        return None
    try:
        weight = float(sample.weight)
    except (TypeError, ValueError, OverflowError):
        return None
    if not math.isfinite(weight) or not 0.0 < weight <= 1.0:
        return None
    try:
        effect = np.asarray(sample.population_effect_mg_dl, dtype=np.float64)
        residual = np.asarray(sample.observed_residual_mg_dl, dtype=np.float64)
    except (TypeError, ValueError, OverflowError):
        return None
    if (
        effect.ndim != 1
        or residual.ndim != 1
        or effect.shape != residual.shape
        or not 1 <= effect.size <= config.maximum_points_per_sample
        or not np.isfinite(effect).all()
        or not np.isfinite(residual).all()
    ):
        return None
    if sample.safety_mask is None:
        safety = np.zeros(effect.shape, dtype=np.bool_)
    else:
        try:
            safety = np.asarray(sample.safety_mask, dtype=np.bool_)
        except (TypeError, ValueError, OverflowError):
            return None
        if safety.shape != effect.shape:
            return None
    bounded_effect = np.clip(
        effect,
        -config.maximum_abs_effect_mg_dl,
        config.maximum_abs_effect_mg_dl,
    )
    if float(np.mean(bounded_effect * bounded_effect)) < config.minimum_effect_energy:
        return None
    return _PreparedSample(
        event_id=event_id,
        kind=sample.kind,
        occurred_at_ms=occurred_at_ms,
        effect=effect,
        residual=residual,
        safety=safety,
        weight=weight,
    )


def _group_samples(
    samples: Sequence[_PreparedSample],
) -> dict[str, list[_PreparedSample]]:
    grouped: dict[str, list[_PreparedSample]] = {}
    for sample in samples:
        grouped.setdefault(sample.event_id, []).append(sample)
    return grouped


def _ordered_group_ids(grouped: Mapping[str, Sequence[_PreparedSample]]) -> list[str]:
    return sorted(
        grouped,
        key=lambda event_id: (
            min(item.occurred_at_ms for item in grouped[event_id]),
            event_id,
        ),
    )


def _split_groups(
    grouped: Mapping[str, Sequence[_PreparedSample]],
    policy: EventKindPolicy,
    validation_fraction: float,
) -> tuple[list[str], list[str]] | None:
    ordered = _ordered_group_ids(grouped)
    if len(ordered) < policy.minimum_groups:
        return None
    validation_count = max(
        policy.minimum_validation_groups,
        int(math.ceil(len(ordered) * validation_fraction)),
    )
    validation_count = min(validation_count, len(ordered) - policy.minimum_train_groups)
    if validation_count < policy.minimum_validation_groups:
        return None
    training_count = len(ordered) - validation_count
    if training_count < policy.minimum_train_groups:
        return None
    return ordered[:training_count], ordered[training_count:]


def _group_sufficient_statistics(
    group: Sequence[_PreparedSample],
    config: EventPersonalizationConfig,
) -> tuple[float, float]:
    numerator = 0.0
    denominator = 0.0
    total_weight = sum(item.weight for item in group)
    if total_weight <= 0.0:
        return 0.0, 0.0
    for item in group:
        point_weight = item.weight / (total_weight * item.effect.size)
        # Robust clipping belongs only to fitting. Held-out targets/effects stay
        # raw so validation measures the same capped correction used at runtime.
        fit_effect = np.clip(
            item.effect,
            -config.maximum_abs_effect_mg_dl,
            config.maximum_abs_effect_mg_dl,
        )
        fit_residual = np.clip(
            item.residual,
            -config.maximum_abs_residual_mg_dl,
            config.maximum_abs_residual_mg_dl,
        )
        numerator += point_weight * float(np.dot(fit_effect, fit_residual))
        denominator += point_weight * float(np.dot(fit_effect, fit_effect))
    return numerator, denominator


def _fit_delta(
    grouped: Mapping[str, Sequence[_PreparedSample]],
    training_ids: Sequence[str],
    policy: EventKindPolicy,
    config: EventPersonalizationConfig,
) -> tuple[float, float, float]:
    statistics = [
        _group_sufficient_statistics(grouped[event_id], config)
        for event_id in training_ids
    ]
    energies = [denominator for _numerator, denominator in statistics if denominator > 0.0]
    if not energies:
        return 0.0, 0.0, 0.0
    prior_energy = float(np.median(np.asarray(energies, dtype=np.float64)))
    ridge_penalty = policy.prior_equivalent_groups * prior_energy
    numerator = sum(item[0] for item in statistics)
    denominator = sum(item[1] for item in statistics) + ridge_penalty
    if denominator <= 0.0 or not math.isfinite(denominator):
        return 0.0, ridge_penalty, prior_energy
    unbounded_delta = numerator / denominator
    delta = float(
        np.clip(
            unbounded_delta,
            policy.scale_low - 1.0,
            policy.scale_high - 1.0,
        )
    )
    return delta, ridge_penalty, prior_energy


def _bounded_adjustment(
    effect: np.ndarray, delta: float, maximum_absolute_adjustment_mg_dl: float
) -> np.ndarray:
    """One shared correction transform for holdout validation and inference."""

    return np.clip(
        delta * effect,
        -maximum_absolute_adjustment_mg_dl,
        maximum_absolute_adjustment_mg_dl,
    )


def _group_mae(
    group: Sequence[_PreparedSample],
    delta: float,
    maximum_absolute_adjustment_mg_dl: float,
) -> tuple[float, float]:
    prior_total = 0.0
    corrected_total = 0.0
    total_weight = sum(item.weight for item in group)
    for item in group:
        point_weight = item.weight / (total_weight * item.effect.size)
        prior_total += point_weight * float(np.sum(np.abs(item.residual)))
        adjustment = _bounded_adjustment(
            item.effect, delta, maximum_absolute_adjustment_mg_dl
        )
        corrected_total += point_weight * float(
            np.sum(np.abs(item.residual - adjustment))
        )
    return prior_total, corrected_total


def _safety_mae(
    grouped: Mapping[str, Sequence[_PreparedSample]],
    validation_ids: Sequence[str],
    delta: float,
    maximum_absolute_adjustment_mg_dl: float,
) -> tuple[int, float | None, float | None]:
    prior_values: list[float] = []
    corrected_values: list[float] = []
    for event_id in validation_ids:
        for item in grouped[event_id]:
            if not bool(np.any(item.safety)):
                continue
            adjustment = _bounded_adjustment(
                item.effect, delta, maximum_absolute_adjustment_mg_dl
            )
            prior_values.extend(np.abs(item.residual[item.safety]).tolist())
            corrected_values.extend(
                np.abs(
                    item.residual[item.safety] - adjustment[item.safety]
                ).tolist()
            )
    if not prior_values:
        return 0, None, None
    return (
        len(prior_values),
        float(np.mean(np.asarray(prior_values, dtype=np.float64))),
        float(np.mean(np.asarray(corrected_values, dtype=np.float64))),
    )


def _group_digest(group_ids: Sequence[str]) -> str:
    canonical = json.dumps(list(group_ids), separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _sample_digest(samples: Sequence[_PreparedSample]) -> str:
    digest = hashlib.sha256()
    ordered = sorted(
        samples,
        key=lambda item: (
            item.occurred_at_ms,
            item.event_id,
            item.effect.tobytes(),
            item.residual.tobytes(),
            item.safety.tobytes(),
            item.weight,
        ),
    )
    for item in ordered:
        digest.update(item.event_id.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(item.occurred_at_ms).encode("ascii"))
        digest.update(b"\0")
        digest.update(np.asarray(item.effect, dtype="<f8").tobytes())
        digest.update(np.asarray(item.residual, dtype="<f8").tobytes())
        digest.update(np.asarray(item.safety, dtype=np.uint8).tobytes())
        digest.update(np.asarray([item.weight], dtype="<f8").tobytes())
        digest.update(b"\n")
    return digest.hexdigest()


def _population_prior_result(
    *,
    kind: EventKind,
    policy: EventKindPolicy,
    reason: str,
    valid_samples: int,
    rejected_samples: int,
    evidence_groups: int,
    evidence_sha256: str,
) -> dict[str, Any]:
    return {
        "kind": kind,
        "state": "population_prior",
        "accepted": False,
        "reason": reason,
        "scale": 1.0,
        "delta": 0.0,
        "scale_bounds": [policy.scale_low, policy.scale_high],
        "maximum_absolute_adjustment_mg_dl": policy.maximum_absolute_adjustment_mg_dl,
        "evidence_groups": evidence_groups,
        "minimum_groups": policy.minimum_groups,
        "valid_samples": valid_samples,
        "rejected_samples": rejected_samples,
        "evidence_sha256": evidence_sha256,
        "training_groups": 0,
        "validation_groups": 0,
        "training_group_ids_sha256": _group_digest(()),
        "validation_group_ids_sha256": _group_digest(()),
        "validation": {
            "prior_mae_mg_dl": None,
            "corrected_mae_mg_dl": None,
            "absolute_improvement_mg_dl": None,
            "relative_improvement": None,
            "improved_groups": 0,
            "required_improved_groups": 0,
            "maximum_group_regression_mg_dl": None,
            "safety_points": 0,
            "safety_prior_mae_mg_dl": None,
            "safety_corrected_mae_mg_dl": None,
            "safety_noninferior": False,
        },
        "fit": {
            "prior_equivalent_groups": policy.prior_equivalent_groups,
            "ridge_penalty": None,
            "median_group_effect_energy": None,
        },
    }


def _fit_kind(
    kind: EventKind,
    samples: Sequence[_PreparedSample],
    rejected_samples: int,
    config: EventPersonalizationConfig,
) -> dict[str, Any]:
    policy = config.policy(kind)
    grouped = _group_samples(samples)
    split = _split_groups(grouped, policy, config.validation_fraction)
    if split is None:
        return _population_prior_result(
            kind=kind,
            policy=policy,
            reason=(
                f"need at least {policy.minimum_groups} independent {kind} event groups"
            ),
            valid_samples=len(samples),
            rejected_samples=rejected_samples,
            evidence_groups=len(grouped),
            evidence_sha256=_sample_digest(samples),
        )
    training_ids, validation_ids = split
    delta, ridge_penalty, prior_energy = _fit_delta(
        grouped, training_ids, policy, config
    )
    group_results = [
        _group_mae(
            grouped[event_id], delta, policy.maximum_absolute_adjustment_mg_dl
        )
        for event_id in validation_ids
    ]
    prior_mae = float(np.mean([item[0] for item in group_results]))
    corrected_mae = float(np.mean([item[1] for item in group_results]))
    absolute_improvement = prior_mae - corrected_mae
    relative_improvement = absolute_improvement / max(1.0, prior_mae)
    group_improvements = [before - after for before, after in group_results]
    improved_groups = sum(value > 0.0 for value in group_improvements)
    required_improved_groups = int(
        math.ceil(policy.minimum_improved_group_fraction * len(validation_ids))
    )
    maximum_regression = max(
        (after - before for before, after in group_results),
        default=0.0,
    )
    safety_points, safety_prior, safety_corrected = _safety_mae(
        grouped, validation_ids, delta, policy.maximum_absolute_adjustment_mg_dl
    )
    safety_noninferior = bool(
        safety_points == 0
        or (
            safety_prior is not None
            and safety_corrected is not None
            and safety_corrected
            <= safety_prior + config.safety_regression_tolerance_mg_dl
        )
    )
    accepted = bool(
        math.isfinite(delta)
        and abs(delta) > 1e-12
        and absolute_improvement >= policy.minimum_absolute_improvement_mg_dl
        and relative_improvement >= policy.minimum_relative_improvement
        and improved_groups >= required_improved_groups
        and maximum_regression <= policy.maximum_group_regression_mg_dl
        and safety_noninferior
    )
    reason = (
        "group-disjoint validation accepted the bounded amplitude correction"
        if accepted
        else "group-disjoint validation retained the population prior"
    )
    applied_delta = delta if accepted else 0.0
    return {
        "kind": kind,
        "state": "personalized" if accepted else "population_prior",
        "accepted": accepted,
        "reason": reason,
        "scale": 1.0 + applied_delta,
        "delta": applied_delta,
        "proposed_scale": 1.0 + delta,
        "proposed_delta": delta,
        "scale_bounds": [policy.scale_low, policy.scale_high],
        "maximum_absolute_adjustment_mg_dl": policy.maximum_absolute_adjustment_mg_dl,
        "evidence_groups": len(grouped),
        "minimum_groups": policy.minimum_groups,
        "valid_samples": len(samples),
        "rejected_samples": rejected_samples,
        "evidence_sha256": _sample_digest(samples),
        "training_groups": len(training_ids),
        "validation_groups": len(validation_ids),
        "training_group_ids_sha256": _group_digest(training_ids),
        "validation_group_ids_sha256": _group_digest(validation_ids),
        "validation": {
            "prior_mae_mg_dl": prior_mae,
            "corrected_mae_mg_dl": corrected_mae,
            "absolute_improvement_mg_dl": absolute_improvement,
            "relative_improvement": relative_improvement,
            "improved_groups": improved_groups,
            "required_improved_groups": required_improved_groups,
            "maximum_group_regression_mg_dl": maximum_regression,
            "safety_points": safety_points,
            "safety_prior_mae_mg_dl": safety_prior,
            "safety_corrected_mae_mg_dl": safety_corrected,
            "safety_noninferior": safety_noninferior,
        },
        "fit": {
            "prior_equivalent_groups": policy.prior_equivalent_groups,
            "ridge_penalty": ridge_penalty,
            "median_group_effect_energy": prior_energy,
        },
    }


def fit_bounded_event_personalization(
    samples: Sequence[EventEffectSample],
    *,
    config: EventPersonalizationConfig | None = None,
) -> dict[str, Any]:
    """Fit fail-closed per-kind amplitude corrections around population priors.

    The returned JSON-compatible artifact is diagnostic and predictor input only.
    ``alert_approved`` is always false; existing full forecast and alert gates
    remain mandatory after this helper is integrated into a candidate.
    """

    active_config = config or EventPersonalizationConfig()
    prepared: dict[EventKind, list[_PreparedSample]] = {
        kind: [] for kind in EVENT_KINDS
    }
    rejected: dict[EventKind, int] = {kind: 0 for kind in EVENT_KINDS}
    unknown_kind_rejections = 0
    for sample in samples:
        if sample.kind not in EVENT_KINDS:
            unknown_kind_rejections += 1
            continue
        normalized = _prepare_sample(sample, active_config)
        if normalized is None:
            rejected[sample.kind] += 1
            continue
        prepared[sample.kind].append(normalized)
    kinds = {
        kind: _fit_kind(
            kind,
            prepared[kind],
            rejected[kind],
            active_config,
        )
        for kind in EVENT_KINDS
    }
    return {
        "protocol": EVENT_PERSONALIZATION_PROTOCOL,
        "use_scope": EVENT_PERSONALIZATION_USE_SCOPE,
        "alert_approved": False,
        "changes_timing": False,
        "changes_sign": False,
        "validation_protocol": "chronological-event-group-holdout-v1",
        "fit_protocol": "group-balanced-ridge-to-population-prior-v1",
        "unknown_kind_rejections": unknown_kind_rejections,
        "kinds": kinds,
    }


def event_personalization_artifact_is_valid(artifact: Any) -> bool:
    """Validate the immutable bounded-reference correction contract.

    The outer forecast artifact supplies the cryptographic checksum.  This
    validator owns the semantic bounds so a checksummed but malformed nested
    document still fails closed before it can become an active predictor.
    """

    if not isinstance(artifact, Mapping) or (
        artifact.get("protocol") != EVENT_PERSONALIZATION_PROTOCOL
        or artifact.get("use_scope") != EVENT_PERSONALIZATION_USE_SCOPE
        or artifact.get("alert_approved") is not False
        or artifact.get("changes_timing") is not False
        or artifact.get("changes_sign") is not False
        or artifact.get("validation_protocol")
        != "chronological-event-group-holdout-v1"
        or artifact.get("fit_protocol")
        != "group-balanced-ridge-to-population-prior-v1"
        or type(artifact.get("unknown_kind_rejections")) is not int
        or int(artifact["unknown_kind_rejections"]) < 0
    ):
        return False
    kinds = artifact.get("kinds")
    if not isinstance(kinds, Mapping) or set(kinds) != set(EVENT_KINDS):
        return False

    def finite_number(value: Any) -> float:
        if isinstance(value, bool):
            raise ValueError("a numeric field cannot be a boolean")
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("a numeric field must be finite")
        return number

    def count(value: Any) -> int:
        if type(value) is not int or value < 0:
            raise ValueError("an evidence count must be a nonnegative integer")
        return value

    for kind in EVENT_KINDS:
        details = kinds.get(kind)
        if not isinstance(details, Mapping) or details.get("kind") != kind:
            return False
        policy = _default_policy(kind)
        validation = details.get("validation")
        fit = details.get("fit")
        bounds = details.get("scale_bounds")
        if (
            not isinstance(validation, Mapping)
            or not isinstance(fit, Mapping)
            or not isinstance(bounds, (list, tuple))
            or len(bounds) != 2
        ):
            return False
        try:
            accepted = details["accepted"]
            state = details["state"]
            scale = finite_number(details["scale"])
            delta = finite_number(details["delta"])
            scale_low = finite_number(bounds[0])
            scale_high = finite_number(bounds[1])
            cap = finite_number(details["maximum_absolute_adjustment_mg_dl"])
            evidence_groups = count(details["evidence_groups"])
            minimum_groups = count(details["minimum_groups"])
            valid_samples = count(details["valid_samples"])
            count(details["rejected_samples"])
            training_groups = count(details["training_groups"])
            validation_groups = count(details["validation_groups"])
            prior_equivalent_groups = finite_number(fit["prior_equivalent_groups"])
        except (KeyError, TypeError, ValueError, IndexError, OverflowError):
            return False
        if (
            type(accepted) is not bool
            or not isinstance(state, str)
            or state not in {"population_prior", "personalized"}
            or not math.isclose(
                scale_low, policy.scale_low, rel_tol=0.0, abs_tol=1e-12
            )
            or not math.isclose(
                scale_high, policy.scale_high, rel_tol=0.0, abs_tol=1e-12
            )
            or not math.isclose(
                cap,
                policy.maximum_absolute_adjustment_mg_dl,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            or not math.isclose(
                prior_equivalent_groups,
                policy.prior_equivalent_groups,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
            or minimum_groups != policy.minimum_groups
            or valid_samples < evidence_groups
            or training_groups + validation_groups > evidence_groups
            or not _is_sha256(details.get("evidence_sha256"))
            or not _is_sha256(details.get("training_group_ids_sha256"))
            or not _is_sha256(details.get("validation_group_ids_sha256"))
        ):
            return False
        if accepted:
            try:
                safety_noninferior = validation["safety_noninferior"]
                prior_mae = finite_number(validation["prior_mae_mg_dl"])
                corrected_mae = finite_number(validation["corrected_mae_mg_dl"])
                absolute_improvement = finite_number(
                    validation["absolute_improvement_mg_dl"]
                )
                relative_improvement = finite_number(validation["relative_improvement"])
                improved_groups = count(validation["improved_groups"])
                required_improved_groups = count(validation["required_improved_groups"])
                maximum_regression = finite_number(
                    validation["maximum_group_regression_mg_dl"]
                )
                safety_points = count(validation["safety_points"])
                ridge_penalty = finite_number(fit["ridge_penalty"])
                prior_energy = finite_number(fit["median_group_effect_energy"])
                proposed_delta = finite_number(details["proposed_delta"])
                proposed_scale = finite_number(details["proposed_scale"])
                if safety_points:
                    safety_prior = finite_number(validation["safety_prior_mae_mg_dl"])
                    safety_corrected = finite_number(
                        validation["safety_corrected_mae_mg_dl"]
                    )
                    safety_metrics_safe = (
                        0.0 <= safety_corrected <= safety_prior
                    )
                else:
                    safety_metrics_safe = (
                        validation["safety_prior_mae_mg_dl"] is None
                        and validation["safety_corrected_mae_mg_dl"] is None
                    )
            except (KeyError, TypeError, ValueError, OverflowError):
                return False
            if (
                state != "personalized"
                or evidence_groups < policy.minimum_groups
                or training_groups < policy.minimum_train_groups
                or validation_groups < policy.minimum_validation_groups
                or training_groups + validation_groups != evidence_groups
                or not scale_low <= scale <= scale_high
                or not math.isclose(scale, 1.0 + delta, rel_tol=0.0, abs_tol=1e-12)
                or not math.isclose(delta, proposed_delta, rel_tol=0.0, abs_tol=1e-12)
                or not math.isclose(scale, proposed_scale, rel_tol=0.0, abs_tol=1e-12)
                or abs(delta) <= 1e-12
                or safety_noninferior is not True
                or not safety_metrics_safe
                or min(prior_mae, corrected_mae) < 0.0
                or absolute_improvement < policy.minimum_absolute_improvement_mg_dl
                or relative_improvement < policy.minimum_relative_improvement
                or not math.isclose(
                    absolute_improvement,
                    prior_mae - corrected_mae,
                    rel_tol=0.0,
                    abs_tol=1e-9,
                )
                or not math.isclose(
                    relative_improvement,
                    absolute_improvement / max(1.0, prior_mae),
                    rel_tol=0.0,
                    abs_tol=1e-12,
                )
                or required_improved_groups
                != math.ceil(policy.minimum_improved_group_fraction * validation_groups)
                or not required_improved_groups <= improved_groups <= validation_groups
                or maximum_regression > policy.maximum_group_regression_mg_dl
                or prior_energy <= 0.0
                or not math.isclose(
                    ridge_penalty,
                    policy.prior_equivalent_groups * prior_energy,
                    rel_tol=1e-12,
                    abs_tol=1e-12,
                )
            ):
                return False
        elif (
            state != "population_prior"
            or not math.isclose(scale, 1.0, rel_tol=0.0, abs_tol=1e-12)
            or not math.isclose(delta, 0.0, rel_tol=0.0, abs_tol=1e-12)
        ):
            return False
    return True


def apply_bounded_event_personalization(
    kind: EventKind,
    population_effect_mg_dl: Sequence[float] | np.ndarray,
    artifact: Mapping[str, Any] | None,
) -> np.ndarray:
    """Apply one validated correction, otherwise return the population effect.

    Corrupt, incomplete, alert-owning, or out-of-bounds artifacts fail closed.
    The caller should pass the aggregate contribution for this event kind, not
    apply the helper once per overlapping event. The absolute cap is applied to
    the *difference* from the aggregate population curve, and a positive scale
    preserves the curve's sign at every horizon.
    """

    effect = np.asarray(population_effect_mg_dl, dtype=np.float64)
    if not np.isfinite(effect).all():
        raise ValueError("population event effect must be finite")
    result = effect.copy()
    if not event_personalization_artifact_is_valid(artifact):
        return result
    assert artifact is not None
    kinds = artifact.get("kinds")
    assert isinstance(kinds, Mapping)
    details = kinds.get(kind)
    if not isinstance(details, Mapping) or details.get("accepted") is not True:
        return result
    try:
        scale = float(details["scale"])
        bounds = details["scale_bounds"]
        scale_low = float(bounds[0])
        scale_high = float(bounds[1])
        cap = float(details["maximum_absolute_adjustment_mg_dl"])
    except (KeyError, TypeError, ValueError, IndexError):
        return result
    if (
        not all(math.isfinite(value) for value in (scale, scale_low, scale_high, cap))
        or not 0.0 < scale_low <= 1.0 <= scale_high
        or not scale_low <= scale <= scale_high
        or cap <= 0.0
    ):
        return result
    adjustment = _bounded_adjustment(effect, scale - 1.0, cap)
    adjusted = effect + adjustment
    # Positive scaling and symmetric clipping should already preserve sign. Keep
    # an explicit guard so later artifact/config evolution cannot change that.
    adjusted = np.where(effect > 0.0, np.maximum(adjusted, 0.0), adjusted)
    adjusted = np.where(effect < 0.0, np.minimum(adjusted, 0.0), adjusted)
    adjusted = np.where(effect == 0.0, 0.0, adjusted)
    return adjusted


def _combined_thresholds() -> dict[str, int | float]:
    return {
        "minimum_validation_groups_per_kind": 2,
        "minimum_absolute_improvement_mg_dl": 0.25,
        "maximum_group_regression_mg_dl": 1.0,
        "maximum_safety_regression_mg_dl": 0.0,
    }


def _combined_parameter_digest(artifact: Mapping[str, Any]) -> str:
    payload = {
        kind: {
            name: (
                artifact["kinds"][kind][name]
                if name == "accepted"
                else float(artifact["kinds"][kind][name])
            )
            for name in ("accepted", "scale", "delta", "maximum_absolute_adjustment_mg_dl")
        }
        for kind in EVENT_KINDS
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _prepare_response_window(window: EventResponseWindow):
    """Keep raw values and fail closed on malformed or nonfinite horizons."""

    try:
        reference = np.asarray(window.reference_mg_dl, dtype=np.float64)
        target = np.asarray(window.target_mg_dl, dtype=np.float64)
        if reference.ndim != 1 or not reference.size or target.shape != reference.shape:
            return None
        usable = (
            np.ones(reference.shape, dtype=np.bool_)
            if window.usable_mask is None
            else np.asarray(window.usable_mask, dtype=np.bool_)
        )
        safety = (
            np.zeros(reference.shape, dtype=np.bool_)
            if window.safety_mask is None
            else np.asarray(window.safety_mask, dtype=np.bool_)
        )
        if usable.shape != reference.shape or safety.shape != reference.shape:
            return None
        if not isinstance(window.event_effects, Mapping):
            return None
        effects = {}
        for key, raw_effect in window.event_effects.items():
            if not isinstance(key, tuple) or len(key) != 2 or key[0] not in EVENT_KINDS:
                return None
            if not isinstance(key[1], str) or not 1 <= len(key[1].strip()) <= 160:
                return None
            normalized_key = (key[0], key[1].strip())
            effect = np.asarray(raw_effect, dtype=np.float64)
            if effect.shape != reference.shape or normalized_key in effects:
                return None
            effects[normalized_key] = effect
    except (AttributeError, TypeError, ValueError, OverflowError):
        return None
    finite = np.isfinite(reference) & np.isfinite(target)
    for effect in effects.values():
        finite &= np.isfinite(effect)
    usable = (
        usable
        & finite
        & (reference > 20.0)
        & (reference < 600.0)
        & (target > 20.0)
        & (target < 600.0)
    )
    safety = safety | (reference < 80.0) | (target < 80.0)
    digest = hashlib.sha256()
    for vector in (reference, target):
        digest.update(np.asarray(vector, dtype="<f8").tobytes())
    digest.update(np.asarray(usable, dtype=np.uint8).tobytes())
    digest.update(np.asarray(safety, dtype=np.uint8).tobytes())
    for key, effect in sorted(effects.items()):
        digest.update(json.dumps(key, ensure_ascii=False).encode("utf-8"))
        digest.update(np.asarray(effect, dtype="<f8").tobytes())
    return reference, target, effects, usable, safety, digest.hexdigest()


def gate_combined_event_personalization(
    artifact: Mapping[str, Any],
    samples: Sequence[EventEffectSample],
    windows: Sequence[EventResponseWindow],
) -> dict[str, Any]:
    """Approve the *sum* of learned channels on group-disjoint responses.

    Independent meal and insulin regressions can both explain the same CGM
    residual. Therefore their separate approvals are only proposals. This gate
    measures their exact combined, per-kind-capped runtime correction and keeps
    no horizon containing a learned-channel event used for fitting (or absent
    from that channel's frozen validation split).
    """

    valid_artifact = event_personalization_artifact_is_valid(artifact)
    result = copy.deepcopy(dict(artifact)) if valid_artifact else fit_bounded_event_personalization([])
    accepted_kinds = [kind for kind in EVENT_KINDS if result["kinds"][kind]["accepted"]]
    thresholds = _combined_thresholds()
    diagnostic: dict[str, Any] = {
        "protocol": COMBINED_EVENT_VALIDATION_PROTOCOL,
        "prediction_transform": COMBINED_EVENT_PREDICTION_TRANSFORM,
        "accepted": False,
        "candidate_kinds": accepted_kinds,
        "thresholds": thresholds,
        "applied_parameters_sha256": _combined_parameter_digest(result),
        "frozen_splits": {},
        "retained_validation_groups_by_kind": {kind: 0 for kind in accepted_kinds},
        "retained_validation_group_ids_sha256": {},
        "evaluated_windows": len(windows),
        "scored_windows": 0,
        "rejected_windows": 0,
        "scored_points": 0,
        "group_scored_points": 0,
        "excluded_nonheldout_points": 0,
        "excluded_no_learned_event_points": 0,
        "excluded_invalid_points": 0,
        "valid_window_evidence_sha256": _group_digest(()),
        "groups": [],
        "prior_mae_mg_dl": None,
        "corrected_mae_mg_dl": None,
        "absolute_improvement_mg_dl": None,
        "relative_improvement": None,
        "maximum_group_regression_mg_dl": None,
        "safety_points": 0,
        "safety_prior_mae_mg_dl": None,
        "safety_corrected_mae_mg_dl": None,
        "safety_noninferior": False,
    }

    def finish(reason: str, accepted: bool = False) -> dict[str, Any]:
        diagnostic["accepted"] = accepted
        diagnostic["reason"] = reason
        if not accepted:
            for kind in accepted_kinds:
                details = result["kinds"][kind]
                details.update(
                    accepted=False,
                    state="population_prior",
                    scale=1.0,
                    delta=0.0,
                    reason="combined response validation retained the population prior",
                    individually_accepted=True,
                )
        result["combined_validation"] = diagnostic
        return result

    if not valid_artifact:
        return finish("invalid individual artifact")
    if not accepted_kinds:
        return finish("no learned event channels require combined approval")

    # Reconstruct the split from exactly the same validated, raw samples used by
    # the individual fitter. Bind both its fitted proposal and evidence digests;
    # a subset, reordered date, or different config cannot silently change which
    # event groups qualify as held out.
    config = EventPersonalizationConfig()
    expected = fit_bounded_event_personalization(samples)
    validation_ids: dict[EventKind, list[str]] = {}
    for kind in accepted_kinds:
        prepared = [
            value
            for sample in samples
            if sample.kind == kind
            and (value := _prepare_sample(sample, config)) is not None
        ]
        split = _split_groups(_group_samples(prepared), config.policy(kind), config.validation_fraction)
        details = result["kinds"][kind]
        if split is None or details != expected["kinds"][kind]:
            return finish("individual fit or chronological split does not match supplied samples")
        training, heldout = split
        validation_ids[kind] = heldout
        diagnostic["frozen_splits"][kind] = {
            "training_groups": len(training),
            "validation_groups": len(heldout),
            "training_group_ids_sha256": _group_digest(training),
            "validation_group_ids_sha256": _group_digest(heldout),
            "evidence_sha256": _sample_digest(prepared),
        }
    validation_sets = {kind: set(ids) for kind, ids in validation_ids.items()}
    grouped_errors: dict[tuple[EventKind, str], list[tuple[np.ndarray, np.ndarray, np.ndarray]]] = {}
    window_digests = []
    for window in windows:
        prepared_window = _prepare_response_window(window)
        if prepared_window is None:
            diagnostic["rejected_windows"] += 1
            continue
        reference, target, effects, usable, safety, digest = prepared_window
        window_digests.append(digest)
        diagnostic["excluded_invalid_points"] += int(np.count_nonzero(~usable))
        any_heldout = np.zeros(reference.shape, dtype=np.bool_)
        nonheldout = np.zeros(reference.shape, dtype=np.bool_)
        active_groups = {}
        aggregates = {kind: np.zeros(reference.shape) for kind in accepted_kinds}
        for (kind, event_id), effect in effects.items():
            if kind not in validation_sets:
                continue
            active = np.isfinite(effect) & (np.abs(effect) > 1e-8)
            with np.errstate(over="ignore", invalid="ignore"):
                aggregates[kind] += np.where(np.isfinite(effect), effect, 0.0)
            if event_id in validation_sets[kind]:
                any_heldout |= active
                active_groups[(kind, event_id)] = active
            else:
                nonheldout |= active
        finite_aggregate = np.logical_and.reduce(
            [np.isfinite(effect) for effect in aggregates.values()]
        )
        diagnostic["excluded_invalid_points"] += int(
            np.count_nonzero(usable & ~finite_aggregate)
        )
        usable &= finite_aggregate
        diagnostic["excluded_nonheldout_points"] += int(np.count_nonzero(usable & nonheldout))
        diagnostic["excluded_no_learned_event_points"] += int(
            np.count_nonzero(usable & ~nonheldout & ~any_heldout)
        )
        selected = usable & any_heldout & ~nonheldout
        if not bool(np.any(selected)):
            continue
        aggregates = {
            kind: np.where(np.isfinite(effect), effect, 0.0)
            for kind, effect in aggregates.items()
        }
        adjustment = sum(
            apply_bounded_event_personalization(kind, aggregates[kind], result) - aggregates[kind]
            for kind in accepted_kinds
        )
        prediction = np.clip(reference + adjustment, 20.0, 600.0)
        prior_error = np.abs(target - reference)
        corrected_error = np.abs(target - prediction)
        diagnostic["scored_windows"] += 1
        diagnostic["scored_points"] += int(np.count_nonzero(selected))
        for key, active in active_groups.items():
            group_selected = selected & active
            if bool(np.any(group_selected)):
                grouped_errors.setdefault(key, []).append(
                    (
                        prior_error[group_selected],
                        corrected_error[group_selected],
                        safety[group_selected],
                    )
                )
    diagnostic["valid_window_evidence_sha256"] = _group_digest(sorted(window_digests))
    group_results = []
    for kind in accepted_kinds:
        retained = [event_id for event_id in validation_ids[kind] if (kind, event_id) in grouped_errors]
        diagnostic["retained_validation_groups_by_kind"][kind] = len(retained)
        diagnostic["retained_validation_group_ids_sha256"][kind] = _group_digest(retained)
        for event_id in retained:
            observations = grouped_errors[(kind, event_id)]
            # Each real event receives equal total weight, regardless of how many
            # overlapping forecast windows or other event kinds mention it.
            prior_mae = math.fsum(
                float(np.mean(before)) for before, _after, _safe in observations
            ) / len(observations)
            corrected_mae = math.fsum(
                float(np.mean(after)) for _before, after, _safe in observations
            ) / len(observations)
            safety_before = [before[mask] for before, _after, mask in observations if np.any(mask)]
            safety_after = [after[mask] for _before, after, mask in observations if np.any(mask)]
            safety_points = sum(values.size for values in safety_before)
            group_results.append(
                {
                    "kind": kind,
                    "event_id_sha256": hashlib.sha256(event_id.encode("utf-8")).hexdigest(),
                    "scored_windows": len(observations),
                    "scored_points": sum(before.size for before, _after, _safe in observations),
                    "prior_mae_mg_dl": prior_mae,
                    "corrected_mae_mg_dl": corrected_mae,
                    "safety_points": safety_points,
                    "safety_prior_mae_mg_dl": (
                        float(np.mean(np.concatenate(safety_before))) if safety_points else None
                    ),
                    "safety_corrected_mae_mg_dl": (
                        float(np.mean(np.concatenate(safety_after))) if safety_points else None
                    ),
                }
            )
    diagnostic["groups"] = group_results
    diagnostic["group_scored_points"] = sum(row["scored_points"] for row in group_results)
    if not group_results:
        return finish("no group-disjoint combined response horizons")
    prior_mae = math.fsum(row["prior_mae_mg_dl"] for row in group_results) / len(group_results)
    corrected_mae = math.fsum(row["corrected_mae_mg_dl"] for row in group_results) / len(group_results)
    safety_groups = [row for row in group_results if row["safety_points"]]
    safety_prior = (
        math.fsum(row["safety_prior_mae_mg_dl"] for row in safety_groups) / len(safety_groups)
        if safety_groups else None
    )
    safety_corrected = (
        math.fsum(row["safety_corrected_mae_mg_dl"] for row in safety_groups) / len(safety_groups)
        if safety_groups else None
    )
    safety_noninferior = not safety_groups or safety_corrected <= safety_prior
    maximum_regression = max(row["corrected_mae_mg_dl"] - row["prior_mae_mg_dl"] for row in group_results)
    diagnostic.update(
        prior_mae_mg_dl=prior_mae,
        corrected_mae_mg_dl=corrected_mae,
        absolute_improvement_mg_dl=prior_mae - corrected_mae,
        relative_improvement=(prior_mae - corrected_mae) / max(1.0, prior_mae),
        maximum_group_regression_mg_dl=maximum_regression,
        safety_points=sum(row["safety_points"] for row in group_results),
        safety_prior_mae_mg_dl=safety_prior,
        safety_corrected_mae_mg_dl=safety_corrected,
        safety_noninferior=bool(safety_noninferior),
    )
    accepted = bool(
        all(
            count >= thresholds["minimum_validation_groups_per_kind"]
            for count in diagnostic["retained_validation_groups_by_kind"].values()
        )
        and prior_mae - corrected_mae >= thresholds["minimum_absolute_improvement_mg_dl"]
        and maximum_regression <= thresholds["maximum_group_regression_mg_dl"]
        and safety_noninferior
    )
    return finish(
        (
            "group-disjoint combined response accepted"
            if accepted else "combined response failed evidence or noninferiority gates"
        ),
        accepted=accepted,
    )


def combined_event_personalization_is_valid(artifact: Any) -> bool:
    """Require code-owned, internally consistent joint evidence for learned kinds."""

    if not event_personalization_artifact_is_valid(artifact):
        return False
    accepted_kinds = [kind for kind in EVENT_KINDS if artifact["kinds"][kind]["accepted"]]
    if not accepted_kinds:
        return True
    diagnostic = artifact.get("combined_validation")
    if not isinstance(diagnostic, Mapping) or (
        diagnostic.get("protocol") != COMBINED_EVENT_VALIDATION_PROTOCOL
        or diagnostic.get("prediction_transform") != COMBINED_EVENT_PREDICTION_TRANSFORM
        or diagnostic.get("accepted") is not True
        or diagnostic.get("candidate_kinds") != accepted_kinds
        or diagnostic.get("thresholds") != _combined_thresholds()
        or diagnostic.get("applied_parameters_sha256") != _combined_parameter_digest(artifact)
        or not _is_sha256(diagnostic.get("valid_window_evidence_sha256"))
    ):
        return False

    def count(value: Any, minimum: int = 0) -> int:
        if type(value) is not int or value < minimum:
            raise ValueError("invalid joint evidence count")
        return value

    def number(value: Any) -> float:
        if isinstance(value, bool):
            raise ValueError("boolean joint metric")
        result = float(value)
        if not math.isfinite(result):
            raise ValueError("nonfinite joint metric")
        return result

    def close(left: Any, right: float) -> bool:
        return math.isclose(number(left), right, rel_tol=1e-12, abs_tol=1e-9)

    try:
        evaluated_windows = count(diagnostic["evaluated_windows"], 1)
        scored_windows = count(diagnostic["scored_windows"], 1)
        rejected_windows = count(diagnostic["rejected_windows"])
        scored_points = count(diagnostic["scored_points"], scored_windows)
        group_scored_points = count(diagnostic["group_scored_points"], scored_points)
        for name in (
            "excluded_nonheldout_points",
            "excluded_no_learned_event_points",
            "excluded_invalid_points",
        ):
            count(diagnostic[name])
        if scored_windows + rejected_windows > evaluated_windows:
            return False
        frozen = diagnostic["frozen_splits"]
        retained_counts = diagnostic["retained_validation_groups_by_kind"]
        retained_hashes = diagnostic["retained_validation_group_ids_sha256"]
        if any(
            not isinstance(item, Mapping) or set(item) != set(accepted_kinds)
            for item in (frozen, retained_counts, retained_hashes)
        ):
            return False
        for kind in accepted_kinds:
            details = artifact["kinds"][kind]
            split = frozen[kind]
            if not isinstance(split, Mapping) or split != {
                name: details[name]
                for name in (
                    "training_groups", "validation_groups", "training_group_ids_sha256",
                    "validation_group_ids_sha256", "evidence_sha256",
                )
            }:
                return False
            if not 2 <= count(retained_counts[kind]) <= details["validation_groups"]:
                return False
            if not _is_sha256(retained_hashes[kind]):
                return False
        groups = diagnostic["groups"]
        if not isinstance(groups, list) or len(groups) != sum(retained_counts.values()):
            return False
        seen = set()
        kind_counts = {kind: 0 for kind in accepted_kinds}
        parsed = []
        for group in groups:
            if not isinstance(group, Mapping):
                return False
            kind, group_hash = group["kind"], group["event_id_sha256"]
            if kind not in kind_counts or not _is_sha256(group_hash) or (kind, group_hash) in seen:
                return False
            seen.add((kind, group_hash))
            kind_counts[kind] += 1
            group_windows = count(group["scored_windows"], 1)
            group_points = count(group["scored_points"], group_windows)
            if group_windows > scored_windows or group_points > scored_points:
                return False
            prior, corrected = number(group["prior_mae_mg_dl"]), number(group["corrected_mae_mg_dl"])
            if min(prior, corrected) < 0.0:
                return False
            safety_points = count(group["safety_points"])
            if safety_points > group_points:
                return False
            if safety_points:
                safety_prior = number(group["safety_prior_mae_mg_dl"])
                safety_corrected = number(group["safety_corrected_mae_mg_dl"])
                if min(safety_prior, safety_corrected) < 0.0:
                    return False
            elif (
                group["safety_prior_mae_mg_dl"] is not None
                or group["safety_corrected_mae_mg_dl"] is not None
            ):
                return False
            parsed.append((prior, corrected, group_points, safety_points, group))
        if kind_counts != retained_counts or sum(row[2] for row in parsed) != group_scored_points:
            return False
        prior = math.fsum(row[0] for row in parsed) / len(parsed)
        corrected = math.fsum(row[1] for row in parsed) / len(parsed)
        regression = max(row[1] - row[0] for row in parsed)
        if (
            not close(diagnostic["prior_mae_mg_dl"], prior)
            or not close(diagnostic["corrected_mae_mg_dl"], corrected)
            or not close(diagnostic["absolute_improvement_mg_dl"], prior - corrected)
            or not close(diagnostic["relative_improvement"], (prior - corrected) / max(1.0, prior))
            or not close(diagnostic["maximum_group_regression_mg_dl"], regression)
            or prior - corrected < 0.25
            or regression > 1.0
        ):
            return False
        safety_points = sum(row[3] for row in parsed)
        if (
            count(diagnostic["safety_points"]) != safety_points
            or diagnostic["safety_noninferior"] is not True
        ):
            return False
        safe_groups = [row[4] for row in parsed if row[3]]
        if safe_groups:
            before = math.fsum(
                number(row["safety_prior_mae_mg_dl"]) for row in safe_groups
            ) / len(safe_groups)
            after = math.fsum(
                number(row["safety_corrected_mae_mg_dl"]) for row in safe_groups
            ) / len(safe_groups)
            if (
                not close(diagnostic["safety_prior_mae_mg_dl"], before)
                or not close(diagnostic["safety_corrected_mae_mg_dl"], after)
                or after > before
            ):
                return False
        elif (
            diagnostic["safety_prior_mae_mg_dl"] is not None
            or diagnostic["safety_corrected_mae_mg_dl"] is not None
        ):
            return False
    except (KeyError, TypeError, ValueError, IndexError, OverflowError, ZeroDivisionError):
        return False
    return True
