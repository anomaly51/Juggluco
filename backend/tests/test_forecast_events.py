from __future__ import annotations

import copy
import math
import random

import numpy as np
import pytest

from app.forecast_events import (
    EVENT_PERSONALIZATION_PROTOCOL,
    EventEffectSample,
    apply_bounded_event_personalization,
    event_personalization_artifact_is_valid,
    fit_bounded_event_personalization,
)


def samples_for(
    kind: str,
    *,
    groups: int,
    true_delta: float,
    validation_delta: float | None = None,
    safety_residual: float | None = None,
) -> list[EventEffectSample]:
    sign = 1.0 if kind == "meal" else -1.0
    effect = np.asarray([10.0, 20.0, 30.0, 40.0]) * sign
    result: list[EventEffectSample] = []
    for index in range(groups):
        delta = (
            validation_delta
            if validation_delta is not None and index >= groups - 2
            else true_delta
        )
        residual = effect * float(delta)
        safety = np.zeros(effect.shape, dtype=np.bool_)
        if safety_residual is not None and index >= groups - 2:
            safety[-1] = True
            residual = residual.copy()
            residual[-1] = safety_residual
        result.append(
            EventEffectSample(
                event_id=f"{kind}-{index:02d}",
                kind=kind,  # type: ignore[arg-type]
                occurred_at_ms=1_700_000_000_000 + index * 3_600_000,
                population_effect_mg_dl=effect,
                observed_residual_mg_dl=residual,
                safety_mask=safety,
            )
        )
    return result


def test_insufficient_groups_keep_every_kind_on_population_prior():
    artifact = fit_bounded_event_personalization(
        samples_for("meal", groups=7, true_delta=0.3)
        + samples_for("rapid", groups=7, true_delta=0.3)
        + samples_for("long", groups=7, true_delta=0.3)
    )

    assert artifact["protocol"] == EVENT_PERSONALIZATION_PROTOCOL
    assert artifact["alert_approved"] is False
    assert event_personalization_artifact_is_valid(artifact)
    for kind in ("meal", "rapid", "long"):
        result = artifact["kinds"][kind]
        assert result["accepted"] is False
        assert result["state"] == "population_prior"
        assert result["scale"] == 1.0
        assert result["evidence_groups"] == 7
    assert artifact["kinds"]["long"]["minimum_groups"] == 8


def test_meal_and_rapid_fit_strongly_shrunk_group_validated_scalars():
    artifact = fit_bounded_event_personalization(
        samples_for("meal", groups=10, true_delta=0.24)
        + samples_for("rapid", groups=10, true_delta=0.18)
    )

    for kind in ("meal", "rapid"):
        result = artifact["kinds"][kind]
        assert result["accepted"] is True
        assert result["state"] == "personalized"
        assert 1.0 < result["scale"] < 1.10
        assert result["training_groups"] == 7
        assert result["validation_groups"] == 3
        assert result["training_group_ids_sha256"] != result["validation_group_ids_sha256"]
        assert result["fit"]["prior_equivalent_groups"] == 12.0
        assert result["validation"]["corrected_mae_mg_dl"] < result["validation"]["prior_mae_mg_dl"]
    assert artifact["kinds"]["long"]["accepted"] is False


def test_chronological_event_holdout_rejects_non_generalizing_scale():
    artifact = fit_bounded_event_personalization(
        samples_for(
            "meal",
            groups=8,
            true_delta=0.30,
            validation_delta=-0.30,
        )
    )
    result = artifact["kinds"]["meal"]

    assert result["accepted"] is False
    assert result["scale"] == 1.0
    assert result["proposed_scale"] > 1.0
    assert result["validation"]["corrected_mae_mg_dl"] > result["validation"]["prior_mae_mg_dl"]


def test_validation_safety_subset_cannot_regress_even_when_overall_mae_improves():
    artifact = fit_bounded_event_personalization(
        samples_for(
            "meal",
            groups=8,
            true_delta=0.25,
            safety_residual=-0.1,
        )
    )
    result = artifact["kinds"]["meal"]

    assert result["validation"]["absolute_improvement_mg_dl"] > 0
    assert result["validation"]["safety_points"] == 2
    assert result["validation"]["safety_noninferior"] is False
    assert result["accepted"] is False
    assert result["scale"] == 1.0


def test_validation_uses_runtime_cap_before_approving_a_correction():
    # An uncapped +1/3 correction appears to improve these two held-out groups
    # (50 -> 47.78), but the real 30 mg/dL cap removes enough of the first
    # point's benefit to make their actual error worse (50 -> 57.78).
    effect = np.asarray([180.0, 80.0, 80.0])
    validation_residual = np.asarray([70.0, -40.0, -40.0])
    samples = [
        EventEffectSample(
            event_id=f"capped-{index}",
            kind="meal",
            occurred_at_ms=1_700_000_000_000 + index * 3_600_000,
            population_effect_mg_dl=effect,
            observed_residual_mg_dl=effect if index < 6 else validation_residual,
            safety_mask=[True, True, True],
        )
        for index in range(8)
    ]

    artifact = fit_bounded_event_personalization(samples)
    result = artifact["kinds"]["meal"]
    validation = result["validation"]

    assert result["proposed_scale"] == pytest.approx(4.0 / 3.0)
    assert validation["prior_mae_mg_dl"] == pytest.approx(50.0)
    assert validation["corrected_mae_mg_dl"] == pytest.approx(57.7777777778)
    assert validation["safety_corrected_mae_mg_dl"] == pytest.approx(57.7777777778)
    assert validation["safety_noninferior"] is False
    assert result["accepted"] is False
    assert event_personalization_artifact_is_valid(artifact)
    np.testing.assert_array_equal(
        apply_bounded_event_personalization("meal", effect, artifact), effect
    )


def test_holdout_effects_are_not_clipped_like_robust_training_statistics():
    # Clipping the held-out -255 contribution to -180 would report a spurious
    # gain (4 -> 3.2) for scale 1.04. Runtime actually yields error 6.2, so this
    # candidate must be rejected, including on its low-glucose safety subset.
    samples = [
        EventEffectSample(
            event_id=f"raw-effect-{index}",
            kind="rapid",
            occurred_at_ms=1_700_000_000_000 + index * 3_600_000,
            population_effect_mg_dl=[-180.0 if index < 6 else -255.0],
            observed_residual_mg_dl=[-21.6 if index < 6 else -4.0],
            safety_mask=[True],
        )
        for index in range(8)
    ]

    artifact = fit_bounded_event_personalization(samples)
    result = artifact["kinds"]["rapid"]
    validation = result["validation"]

    assert result["proposed_scale"] == pytest.approx(1.04)
    assert validation["prior_mae_mg_dl"] == pytest.approx(4.0)
    assert validation["corrected_mae_mg_dl"] == pytest.approx(6.2)
    assert validation["safety_corrected_mae_mg_dl"] == pytest.approx(6.2)
    assert validation["safety_noninferior"] is False
    assert result["accepted"] is False
    assert event_personalization_artifact_is_valid(artifact)


def test_holdout_residuals_are_scored_raw_while_the_fit_remains_robust():
    effect = [-40.0]
    samples = [
        EventEffectSample(
            event_id=f"raw-residual-{index}",
            kind="rapid",
            occurred_at_ms=1_700_000_000_000 + index * 3_600_000,
            population_effect_mg_dl=effect,
            observed_residual_mg_dl=[-500.0],
            safety_mask=[True],
        )
        for index in range(8)
    ]
    artifact = fit_bounded_event_personalization(samples)
    result = artifact["kinds"]["rapid"]
    assert result["accepted"] is True
    assert event_personalization_artifact_is_valid(artifact)
    adjustment = apply_bounded_event_personalization("rapid", effect, artifact)[0] - effect[0]
    assert result["validation"]["prior_mae_mg_dl"] == pytest.approx(500.0)
    assert result["validation"]["corrected_mae_mg_dl"] == pytest.approx(
        abs(-500.0 - adjustment)
    )


@pytest.mark.parametrize("kind", ("meal", "rapid", "long"))
def test_accepted_holdout_metrics_match_the_actual_capped_prediction(kind):
    effect = np.asarray([180.0, 80.0, 80.0]) * (1.0 if kind == "meal" else -1.0)
    samples = [
        EventEffectSample(
            event_id=f"{kind}-{index}",
            kind=kind,
            occurred_at_ms=1_700_000_000_000 + index * 3_600_000,
            population_effect_mg_dl=effect,
            observed_residual_mg_dl=effect,
            safety_mask=[True, True, True],
        )
        for index in range(8)
    ]
    artifact = fit_bounded_event_personalization(samples)
    details = artifact["kinds"][kind]

    assert details["accepted"] is True
    assert event_personalization_artifact_is_valid(artifact)
    adjustment = apply_bounded_event_personalization(kind, effect, artifact) - effect
    actual_mae = float(np.abs(effect - adjustment).mean())
    assert details["validation"]["corrected_mae_mg_dl"] == pytest.approx(actual_mae)
    assert details["validation"]["safety_corrected_mae_mg_dl"] == pytest.approx(actual_mae)


def test_long_requires_eight_groups_and_remains_more_tightly_bounded():
    prior = fit_bounded_event_personalization(
        samples_for("long", groups=7, true_delta=0.50)
    )["kinds"]["long"]
    learned = fit_bounded_event_personalization(
        samples_for("long", groups=8, true_delta=0.50)
    )["kinds"]["long"]

    assert prior["accepted"] is False
    assert prior["scale"] == 1.0
    assert learned["accepted"] is True
    assert 1.0 < learned["scale"] <= 1.20
    assert learned["maximum_absolute_adjustment_mg_dl"] == 12.0


def test_repeated_observations_never_count_as_additional_event_groups():
    samples = samples_for("rapid", groups=7, true_delta=0.25)
    repeated = [samples[0] for _ in range(100)]
    result = fit_bounded_event_personalization(samples + repeated)["kinds"]["rapid"]

    assert result["evidence_groups"] == 7
    assert result["valid_samples"] == 107
    assert result["accepted"] is False


def test_fit_and_group_split_are_deterministic_across_input_order():
    samples = samples_for("meal", groups=10, true_delta=0.22)
    expected = fit_bounded_event_personalization(samples)
    shuffled = list(samples)
    random.Random(91).shuffle(shuffled)
    actual = fit_bounded_event_personalization(shuffled)

    assert actual == expected


def test_apply_is_fail_closed_sign_preserving_and_caps_absolute_adjustment():
    artifact = fit_bounded_event_personalization(
        samples_for("meal", groups=10, true_delta=2.0)
    )
    details = artifact["kinds"]["meal"]
    assert details["accepted"] is True
    assert details["scale"] <= details["scale_bounds"][1]

    effect = np.asarray([200.0, -200.0, 0.0])
    adjusted = apply_bounded_event_personalization("meal", effect, artifact)
    difference = adjusted - effect
    assert np.max(np.abs(difference)) <= 30.0
    assert adjusted[0] >= 0.0
    assert adjusted[1] <= 0.0
    assert adjusted[2] == 0.0

    unsafe = copy.deepcopy(artifact)
    unsafe["alert_approved"] = True
    assert event_personalization_artifact_is_valid(unsafe) is False
    assert np.array_equal(
        apply_bounded_event_personalization("meal", effect, unsafe), effect
    )
    corrupt = copy.deepcopy(artifact)
    corrupt["kinds"]["meal"]["scale"] = math.inf
    assert event_personalization_artifact_is_valid(corrupt) is False
    assert np.array_equal(
        apply_bounded_event_personalization("meal", effect, corrupt), effect
    )


def test_invalid_samples_are_rejected_without_poisoning_valid_evidence():
    samples = samples_for("meal", groups=8, true_delta=0.25)
    samples.append(
        EventEffectSample(
            event_id="bad",
            kind="meal",
            occurred_at_ms=1_700_000_000_000,
            population_effect_mg_dl=[float("nan")],
            observed_residual_mg_dl=[1.0],
        )
    )
    samples.append(
        EventEffectSample(
            event_id="bad-weight",
            kind="meal",
            occurred_at_ms=1_700_000_000_000,
            population_effect_mg_dl=[1.0],
            observed_residual_mg_dl=[1.0],
            weight="not-a-number",  # type: ignore[arg-type]
        )
    )
    result = fit_bounded_event_personalization(samples)["kinds"]["meal"]

    assert result["accepted"] is True
    assert result["evidence_groups"] == 8
    assert result["rejected_samples"] == 2


def test_empty_evidence_is_a_valid_no_op_artifact():
    artifact = fit_bounded_event_personalization([])

    assert event_personalization_artifact_is_valid(artifact)
    for kind in ("meal", "rapid", "long"):
        np.testing.assert_array_equal(
            apply_bounded_event_personalization(kind, [10.0, -10.0, 0.0], artifact),
            [10.0, -10.0, 0.0],
        )


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("scale_bounds",), [0.01, 100.0]),
        (("scale_bounds",), [0.65, 1.35, 9.0]),
        (("maximum_absolute_adjustment_mg_dl",), 1_000.0),
        (("minimum_groups",), 1),
        (("fit", "prior_equivalent_groups"), 0.0),
        (("fit", "ridge_penalty"), 0.0),
        (("training_groups",), 5),
        (("validation_groups",), 1),
        (("evidence_groups",), 9),
        (("valid_samples",), 0),
        (("rejected_samples",), True),
        (("training_groups",), 6.5),
        (("state",), []),
        (("proposed_scale",), 1.0),
        (("validation", "absolute_improvement_mg_dl"), 100.0),
        (("validation", "relative_improvement"), 1.0),
        (("validation", "improved_groups"), 0),
        (("validation", "required_improved_groups"), 1),
        (("validation", "maximum_group_regression_mg_dl"), 2.0),
        (("validation", "safety_corrected_mae_mg_dl"), 100.0),
        (("validation", "safety_points"), 0),
    ],
)
def test_validator_rejects_policy_or_validation_tampering(path, value):
    artifact = fit_bounded_event_personalization(
        samples_for("meal", groups=8, true_delta=0.30, safety_residual=12.0)
    )
    assert artifact["kinds"]["meal"]["accepted"] is True
    assert event_personalization_artifact_is_valid(artifact)
    changed = copy.deepcopy(artifact)
    target = changed["kinds"]["meal"]
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value

    assert event_personalization_artifact_is_valid(changed) is False
    np.testing.assert_array_equal(
        apply_bounded_event_personalization("meal", [180.0, 80.0], changed),
        [180.0, 80.0],
    )


@pytest.mark.parametrize("kind", ("meal", "rapid", "long"))
def test_no_op_artifacts_cannot_override_code_owned_policy(kind):
    artifact = fit_bounded_event_personalization([])
    artifact["kinds"][kind]["maximum_absolute_adjustment_mg_dl"] = 1_000.0

    assert event_personalization_artifact_is_valid(artifact) is False
