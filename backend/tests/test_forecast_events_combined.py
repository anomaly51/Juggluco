from __future__ import annotations

import copy

import numpy as np
import pytest

from app.forecast_events import (
    COMBINED_EVENT_VALIDATION_PROTOCOL,
    EventEffectSample,
    EventResponseWindow,
    apply_bounded_event_personalization,
    combined_event_personalization_is_valid,
    event_personalization_artifact_is_valid,
    fit_bounded_event_personalization,
    gate_combined_event_personalization,
)


BASE_MS = 1_800_000_000_000


def _cohort(
    *,
    validation_residual=(10.0,),
    training_residual=(10.0,),
    heldout_effect=100.0,
    reference=150.0,
    safety_mask=None,
):
    validation_residual = np.asarray(validation_residual, dtype=float)
    training_residual = np.broadcast_to(training_residual, validation_residual.shape)
    samples = []
    windows = []
    for index in range(8):
        residual = training_residual if index < 6 else validation_residual
        reference_values = np.full(residual.shape, reference)
        effects = {}
        for kind, sign in (("meal", 1.0), ("rapid", -1.0)):
            effect = np.full(residual.shape, (100.0 if index < 6 else heldout_effect) * sign)
            event_id = f"{kind}-{index}"
            effects[(kind, event_id)] = effect
            samples.append(
                EventEffectSample(
                    event_id=event_id,
                    kind=kind,
                    occurred_at_ms=BASE_MS + index * 86_400_000,
                    population_effect_mg_dl=effect,
                    observed_residual_mg_dl=residual,
                    safety_mask=safety_mask,
                )
            )
        windows.append(
            EventResponseWindow(
                reference_mg_dl=reference_values,
                target_mg_dl=reference_values + residual,
                event_effects=effects,
                safety_mask=safety_mask,
            )
        )
    artifact = fit_bounded_event_personalization(samples)
    assert artifact["kinds"]["meal"]["accepted"] is True
    assert artifact["kinds"]["rapid"]["accepted"] is True
    assert artifact["kinds"]["long"]["accepted"] is False
    return artifact, samples, windows


def _assert_all_prior(artifact):
    assert event_personalization_artifact_is_valid(artifact)
    assert combined_event_personalization_is_valid(artifact)
    for details in artifact["kinds"].values():
        assert details["accepted"] is False
        assert details["state"] == "population_prior"
        assert details["scale"] == 1.0
        assert details["delta"] == 0.0


def test_combined_gate_rejects_independent_channels_double_explaining_one_residual():
    artifact, samples, windows = _cohort(validation_residual=(3.0,))
    original = copy.deepcopy(artifact)
    result = gate_combined_event_personalization(artifact, samples, windows)

    assert artifact == original
    diagnostic = result["combined_validation"]
    assert diagnostic["protocol"] == COMBINED_EVENT_VALIDATION_PROTOCOL
    assert diagnostic["accepted"] is False
    assert diagnostic["prior_mae_mg_dl"] == pytest.approx(3.0)
    assert diagnostic["corrected_mae_mg_dl"] == pytest.approx(11.0 / 3.0)
    assert diagnostic["absolute_improvement_mg_dl"] < 0.0
    assert diagnostic["retained_validation_groups_by_kind"] == {"meal": 2, "rapid": 2}
    _assert_all_prior(result)
    for kind in ("meal", "rapid"):
        assert result["kinds"][kind]["proposed_scale"] == original["kinds"][kind]["proposed_scale"]
        assert result["kinds"][kind]["individually_accepted"] is True


def test_combined_gate_accepts_and_scores_exact_runtime_sum_on_heldout_groups():
    artifact, samples, windows = _cohort(validation_residual=(10.0, 12.0))
    result = gate_combined_event_personalization(artifact, samples, windows)
    diagnostic = result["combined_validation"]

    assert diagnostic["accepted"] is True
    assert combined_event_personalization_is_valid(result)
    assert diagnostic["scored_windows"] == 2
    assert diagnostic["scored_points"] == 4
    assert diagnostic["group_scored_points"] == 8
    assert diagnostic["excluded_nonheldout_points"] == 12
    assert len(diagnostic["groups"]) == 4
    window = windows[-1]
    expected_delta = sum(
        apply_bounded_event_personalization(kind, effect, artifact) - effect
        for (kind, _event_id), effect in window.event_effects.items()
    )
    expected_prediction = np.clip(np.asarray(window.reference_mg_dl) + expected_delta, 20.0, 600.0)
    assert diagnostic["corrected_mae_mg_dl"] == pytest.approx(
        float(np.mean(np.abs(np.asarray(window.target_mg_dl) - expected_prediction)))
    )
    for kind in ("meal", "rapid"):
        assert result["kinds"][kind] == artifact["kinds"][kind]
        assert (
            diagnostic["frozen_splits"][kind]["training_group_ids_sha256"]
            == artifact["kinds"][kind]["training_group_ids_sha256"]
        )


@pytest.mark.parametrize("nonheldout_id", ["meal-0", "unknown-not-in-fitted-cohort"])
def test_any_active_fitted_or_unknown_learned_event_excludes_only_its_horizons(nonheldout_id):
    artifact, samples, windows = _cohort(validation_residual=(10.0, 10.0))
    changed = []
    for window in windows[-2:]:
        effects = dict(window.event_effects)
        effects[("meal", nonheldout_id)] = np.asarray([100.0, 0.0])
        # A non-learned basal channel need not belong to the learned split.
        effects[("long", "known-basal-outside-cohort")] = np.asarray([-2.0, -2.0])
        changed.append(
            EventResponseWindow(window.reference_mg_dl, window.target_mg_dl, effects)
        )
    result = gate_combined_event_personalization(artifact, samples, changed)

    assert result["combined_validation"]["accepted"] is True
    assert result["combined_validation"]["scored_points"] == 2
    assert result["combined_validation"]["excluded_nonheldout_points"] == 2
    assert combined_event_personalization_is_valid(result)


def test_holdout_meal_cannot_validate_a_simultaneously_active_fitted_rapid_event():
    artifact, samples, windows = _cohort()
    mixed = [
        EventResponseWindow(
            window.reference_mg_dl,
            window.target_mg_dl,
            {
                ("meal", f"meal-{index}"): [100.0],
                ("rapid", "rapid-0"): [-100.0],
            },
        )
        for index, window in zip((6, 7), windows[-2:])
    ]
    result = gate_combined_event_personalization(artifact, samples, mixed)

    _assert_all_prior(result)
    assert result["combined_validation"]["scored_points"] == 0
    assert result["combined_validation"]["excluded_nonheldout_points"] == 2


def test_every_accepted_kind_requires_two_retained_real_validation_groups():
    artifact, samples, windows = _cohort()
    result = gate_combined_event_personalization(artifact, samples, windows[-1:])

    assert result["combined_validation"]["absolute_improvement_mg_dl"] > 0.25
    assert result["combined_validation"]["retained_validation_groups_by_kind"] == {"meal": 1, "rapid": 1}
    _assert_all_prior(result)


def test_combined_runtime_cap_is_applied_once_to_overlapping_heldout_event_aggregate():
    samples = [
        EventEffectSample(
            event_id=f"meal-{index}",
            kind="meal",
            occurred_at_ms=BASE_MS + index * 86_400_000,
            population_effect_mg_dl=[100.0],
            observed_residual_mg_dl=[100.0 if index < 6 else 40.0],
        )
        for index in range(8)
    ]
    artifact = fit_bounded_event_personalization(samples)
    assert artifact["kinds"]["meal"]["accepted"]
    window = EventResponseWindow(
        [150.0], [190.0], {("meal", "meal-6"): [100.0], ("meal", "meal-7"): [100.0]}
    )
    result = gate_combined_event_personalization(artifact, samples, [window])

    assert result["combined_validation"]["accepted"] is True
    assert result["combined_validation"]["corrected_mae_mg_dl"] == pytest.approx(10.0)
    assert result["combined_validation"]["retained_validation_groups_by_kind"] == {"meal": 2}
    assert combined_event_personalization_is_valid(result)


def test_combined_validation_never_robust_clips_heldout_effects_or_targets():
    artifact, samples, windows = _cohort(
        validation_residual=(250.0,), heldout_effect=500.0, reference=150.0
    )
    result = gate_combined_event_personalization(artifact, samples, windows)

    diagnostic = result["combined_validation"]
    assert diagnostic["accepted"] is True
    assert diagnostic["prior_mae_mg_dl"] == 250.0
    assert diagnostic["corrected_mae_mg_dl"] == pytest.approx(250.0 - 100.0 / 3.0)
    assert combined_event_personalization_is_valid(result)


def test_combined_low_safety_cannot_regress_when_overall_mean_improves():
    artifact, samples, windows = _cohort(
        validation_residual=(10.0, 3.0), safety_mask=[False, True]
    )
    result = gate_combined_event_personalization(artifact, samples, windows)

    diagnostic = result["combined_validation"]
    assert diagnostic["absolute_improvement_mg_dl"] > 0.25
    assert diagnostic["safety_points"] == 4
    assert diagnostic["safety_corrected_mae_mg_dl"] > diagnostic["safety_prior_mae_mg_dl"]
    assert diagnostic["safety_noninferior"] is False
    _assert_all_prior(result)


def test_low_glucose_safety_is_inferred_even_when_callers_mask_omits_it():
    artifact, samples, windows = _cohort(validation_residual=(10.0, 3.0))
    changed = []
    for window in windows[-2:]:
        changed.append(
            EventResponseWindow(
                [150.0, 60.0], [160.0, 63.0], window.event_effects, safety_mask=[False, False]
            )
        )
    result = gate_combined_event_personalization(artifact, samples, changed)

    assert result["combined_validation"]["safety_points"] == 4
    assert result["combined_validation"]["safety_noninferior"] is False
    _assert_all_prior(result)


def test_missing_joint_windows_or_changed_sample_provenance_reverts_all_learned_kinds():
    artifact, samples, windows = _cohort()
    _assert_all_prior(gate_combined_event_personalization(artifact, samples, []))
    changed = gate_combined_event_personalization(artifact, samples[:-1], windows)
    _assert_all_prior(changed)
    assert "does not match" in changed["combined_validation"]["reason"]


def test_error_metrics_balance_real_event_groups_instead_of_repeated_windows():
    artifact, samples, windows = _cohort()
    early = windows[-2]
    late = windows[-1]
    varied = [
        EventResponseWindow(early.reference_mg_dl, [154.0], early.event_effects),
        EventResponseWindow(late.reference_mg_dl, [170.0], late.event_effects),
    ]
    once = gate_combined_event_personalization(artifact, samples, varied)
    repeated = gate_combined_event_personalization(artifact, samples, [varied[0]] * 100 + [varied[1]])

    assert once["combined_validation"]["accepted"] is True
    assert repeated["combined_validation"]["accepted"] is True
    for metric in ("prior_mae_mg_dl", "corrected_mae_mg_dl", "absolute_improvement_mg_dl"):
        assert repeated["combined_validation"][metric] == pytest.approx(once["combined_validation"][metric])
    assert repeated["combined_validation"]["retained_validation_groups_by_kind"] == {"meal": 2, "rapid": 2}
    assert combined_event_personalization_is_valid(repeated)


def test_malformed_nonfinite_and_unusable_horizons_never_enter_combined_metrics():
    artifact, samples, windows = _cohort(validation_residual=(10.0, 10.0, 10.0))
    changed = []
    for window in windows[-2:]:
        effects = dict(window.event_effects)
        effects[("long", "invalid-prior")] = [np.nan, 0.0, 0.0]
        changed.append(
            EventResponseWindow(
                window.reference_mg_dl,
                window.target_mg_dl,
                effects,
                usable_mask=[True, False, True],
            )
        )
    changed.append(EventResponseWindow([150.0], [160.0, 160.0], {}))
    result = gate_combined_event_personalization(artifact, samples, changed)

    assert result["combined_validation"]["accepted"] is True
    assert result["combined_validation"]["scored_points"] == 2
    assert result["combined_validation"]["excluded_invalid_points"] == 4
    assert result["combined_validation"]["rejected_windows"] == 1
    assert combined_event_personalization_is_valid(result)


def test_generic_individual_helper_remains_valid_but_cannot_bypass_joint_forecast_gate():
    artifact, samples, windows = _cohort()
    assert event_personalization_artifact_is_valid(artifact)
    assert not combined_event_personalization_is_valid(artifact)
    prior = fit_bounded_event_personalization([])
    assert combined_event_personalization_is_valid(prior)
    _assert_all_prior(gate_combined_event_personalization(prior, [], []))
    accepted = gate_combined_event_personalization(artifact, samples, windows)
    assert combined_event_personalization_is_valid(accepted)


def test_overflowing_aggregate_effect_fails_closed_without_poisoning_other_horizons():
    artifact, samples, windows = _cohort(validation_residual=(10.0, 10.0))
    changed = EventResponseWindow(
        [150.0, 150.0],
        [160.0, 160.0],
        {
            ("meal", "meal-6"): [1e308, 50.0],
            ("meal", "meal-7"): [1e308, 50.0],
            ("rapid", "rapid-6"): [-50.0, -50.0],
            ("rapid", "rapid-7"): [-50.0, -50.0],
        },
    )
    result = gate_combined_event_personalization(artifact, samples, [changed])

    assert result["combined_validation"]["accepted"] is True
    assert result["combined_validation"]["scored_points"] == 1
    assert result["combined_validation"]["excluded_invalid_points"] == 1
    assert combined_event_personalization_is_valid(result)


@pytest.mark.parametrize(
    "change",
    [
        lambda d: d.update(accepted=False),
        lambda d: d.update(protocol="unvalidated"),
        lambda d: d.update(candidate_kinds=["meal"]),
        lambda d: d["thresholds"].update(minimum_absolute_improvement_mg_dl=0.0),
        lambda d: d.update(applied_parameters_sha256="0" * 64),
        lambda d: d.update(valid_window_evidence_sha256="bad"),
        lambda d: d.update(scored_points=True),
        lambda d: d.update(corrected_mae_mg_dl=0.0),
        lambda d: d.update(absolute_improvement_mg_dl=999.0),
        lambda d: d.update(relative_improvement=np.nan),
        lambda d: d.update(maximum_group_regression_mg_dl=-999.0),
        lambda d: d.update(safety_noninferior=False),
        lambda d: d.update(safety_points=1),
        lambda d: d["frozen_splits"]["meal"].update(evidence_sha256="0" * 64),
        lambda d: d["retained_validation_groups_by_kind"].update(meal=1),
        lambda d: d["groups"][0].update(event_id_sha256="not-a-sha"),
        lambda d: d["groups"][0].update(corrected_mae_mg_dl=-1.0),
        lambda d: d["groups"][0].update(scored_windows=0),
        lambda d: d["groups"].pop(),
    ],
)
def test_combined_validator_rejects_tampered_thresholds_counts_metrics_or_provenance(change):
    artifact, samples, windows = _cohort()
    accepted = gate_combined_event_personalization(artifact, samples, windows)
    assert combined_event_personalization_is_valid(accepted)
    change(accepted["combined_validation"])

    assert event_personalization_artifact_is_valid(accepted)
    assert not combined_event_personalization_is_valid(accepted)


def test_combined_validator_rejects_duplicate_real_group_rows():
    artifact, samples, windows = _cohort()
    accepted = gate_combined_event_personalization(artifact, samples, windows)
    accepted["combined_validation"]["groups"][1] = copy.deepcopy(
        accepted["combined_validation"]["groups"][0]
    )

    assert not combined_event_personalization_is_valid(accepted)
