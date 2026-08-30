from __future__ import annotations

from collections import Counter
from collections.abc import Sequence

import numpy as np
import pytest

import app.forecast as forecast_module
from app.forecast import (
    EVENT_KINDS,
    HORIZON_STEPS,
    STEP_MS,
    ForecastService,
    _Event,
    _default_parameters,
    _event_glucose_increment,
    _static_reference_prediction,
)
from app.forecast_events import (
    EventEffectSample,
    apply_bounded_event_personalization,
    combined_event_personalization_is_valid,
    fit_bounded_event_personalization,
)
from app.models import GlucoseReadingRecord


BASE_MS = 1_800_000_000_000
DAY_MS = 86_400_000


def _readings(
    offsets_minutes: Sequence[int], *, glucose: float = 150.0
) -> list[GlucoseReadingRecord]:
    return [
        GlucoseReadingRecord(
            reading_id=f"event-integration-{minute}",
            measured_at_ms=BASE_MS + minute * 60_000,
            glucose_mg_dl=glucose,
            trend_mg_dl_min=0.0,
            quality=1.0,
            utc_offset_minutes=0,
            payload_hash=f"{index:064x}",
            received_at_ms=BASE_MS + minute * 60_000,
        )
        for index, minute in enumerate(offsets_minutes)
    ]


def _event(
    event_id: str,
    occurred_at_ms: int,
    *,
    kind: str = "meal",
    amount: float = 40.0,
    known_at_ms: int | None = None,
) -> _Event:
    return _Event(
        event_id=event_id,
        occurred_at_ms=occurred_at_ms,
        kind=kind,
        label=event_id,
        amount=amount,
        known_at_ms=known_at_ms,
    )


def _capture_samples(monkeypatch: pytest.MonkeyPatch) -> list[EventEffectSample]:
    captured: list[EventEffectSample] = []

    def capture(samples):
        captured.extend(samples)
        return fit_bounded_event_personalization(samples)

    monkeypatch.setattr(forecast_module, "fit_bounded_event_personalization", capture)
    return captured


def _accepted_meal_artifact() -> dict:
    effect = np.asarray([20.0, 40.0, 60.0, 80.0])
    artifact = fit_bounded_event_personalization(
        [
            EventEffectSample(
                event_id=f"learned-meal-{index}",
                kind="meal",
                occurred_at_ms=BASE_MS + index * DAY_MS,
                population_effect_mg_dl=effect,
                observed_residual_mg_dl=effect * 2.0,
            )
            for index in range(10)
        ]
    )
    assert artifact["kinds"]["meal"]["accepted"] is True
    assert artifact["kinds"]["meal"]["scale"] == pytest.approx(1.35)
    return artifact


def test_fit_uses_only_supplied_training_windows(monkeypatch):
    readings = _readings([0, 5, 10, 7 * 24 * 60, 7 * 24 * 60 + 5])
    events = [
        _event("training-meal", readings[1].measured_at_ms),
        _event("unsupplied-holdout-meal", readings[3].measured_at_ms),
    ]
    captured = _capture_samples(monkeypatch)
    reference_calls = []

    def reference(history, causal_events, anchor_ms, parameters):
        reference_calls.append((history[-1].measured_at_ms, anchor_ms))
        return np.full(HORIZON_STEPS, 150.0)

    monkeypatch.setattr(forecast_module, "_static_reference_prediction", reference)
    ForecastService._fit_static_event_personalization(
        readings,
        events,
        [(1, np.full(HORIZON_STEPS, 161.0))],
        _default_parameters(),
    )

    assert reference_calls == [(readings[1].measured_at_ms,) * 2]
    assert [sample.event_id for sample in captured] == ["training-meal"]
    np.testing.assert_allclose(captured[0].observed_residual_mg_dl, 11.0)


def test_fit_filters_future_late_known_and_old_events_at_each_anchor(monkeypatch):
    readings = _readings([0, 5, 10])
    early, later = readings[1].measured_at_ms, readings[2].measured_at_ms
    events = [
        _event("known-at-anchor", early),
        _event("future-at-first-anchor", later, kind="rapid", amount=4.0),
        _event(
            "late-known-at-first-anchor",
            early,
            kind="long",
            amount=10.0,
            known_at_ms=later,
        ),
        _event("too-old", early - 96 * 60 * 60_000 - 1),
        _event("not-yet-recorded", early, known_at_ms=later + 1),
        _event("unsupported-kind", early, kind="unknown"),
    ]
    captured = _capture_samples(monkeypatch)
    reference_calls = []

    def reference(history, causal_events, anchor_ms, parameters):
        reference_calls.append((anchor_ms, {event.event_id for event in causal_events}))
        return np.full(HORIZON_STEPS, 150.0)

    monkeypatch.setattr(forecast_module, "_static_reference_prediction", reference)
    # A constant nonzero contribution makes accidental inclusion observable even
    # for a physiologically expired or unsupported event.
    monkeypatch.setattr(forecast_module, "_event_glucose_increment", lambda *args: 1.0)
    ForecastService._fit_static_event_personalization(
        readings,
        events,
        [(1, np.full(HORIZON_STEPS, 160.0)), (2, np.full(HORIZON_STEPS, 162.0))],
        _default_parameters(),
    )

    assert reference_calls == [
        (early, {"known-at-anchor"}),
        (
            later,
            {"known-at-anchor", "future-at-first-anchor", "late-known-at-first-anchor"},
        ),
    ]
    assert Counter(sample.event_id for sample in captured) == {
        "known-at-anchor": 1,
        "future-at-first-anchor": 1,
        "late-known-at-first-anchor": 1,
    }


def test_late_imported_labels_are_used_only_by_explicit_retrospective_training(
    monkeypatch,
):
    readings = _readings([0, 5, 10, 7 * 24 * 60])
    anchor_ms = readings[1].measured_at_ms
    label_cutoff_ms = BASE_MS + 6 * DAY_MS
    events = [
        _event("imported-training-meal", anchor_ms, known_at_ms=label_cutoff_ms),
        _event(
            "not-in-frozen-snapshot",
            anchor_ms,
            kind="rapid",
            known_at_ms=label_cutoff_ms + 1,
        ),
        _event("outside-training-partition", readings[-1].measured_at_ms),
    ]
    captured = _capture_samples(monkeypatch)
    windows = [(1, np.full(HORIZON_STEPS, 170.0))]
    ForecastService._fit_static_event_personalization(
        readings, events, windows, _default_parameters()
    )
    assert captured == []

    result = ForecastService._fit_static_event_personalization(
        readings,
        events,
        windows,
        _default_parameters(),
        retrospective_label_cutoff_ms=label_cutoff_ms,
    )
    assert {sample.event_id for sample in captured} == {"imported-training-meal"}
    assert result["kinds"]["meal"]["evidence_groups"] == 1
    assert result["kinds"]["rapid"]["evidence_groups"] == 0


@pytest.mark.parametrize("retrospective", [False, True])
def test_later_intake_excludes_affected_training_targets_across_all_kinds(
    monkeypatch, retrospective,
):
    readings = _readings([0])
    events = [
        _event("earlier-meal", BASE_MS),
        _event("later-rapid", BASE_MS + 15 * 60_000, kind="rapid", amount=5.0),
    ]
    captured = _capture_samples(monkeypatch)
    ForecastService._fit_static_event_personalization(
        readings,
        events,
        [(0, np.full(HORIZON_STEPS, 170.0))],
        _default_parameters(),
        retrospective_label_cutoff_ms=BASE_MS + DAY_MS if retrospective else None,
    )
    assert len(captured) == 1
    assert captured[0].event_id == "earlier-meal"
    # +15 and every later target can be influenced by the later injection.
    assert len(captured[0].population_effect_mg_dl) == 2


def test_repeated_training_windows_keep_one_stable_event_group(monkeypatch):
    readings = _readings(range(0, 65, 5))
    event = _event("one-injection", BASE_MS + 30 * 60_000, kind="rapid", amount=5.0)
    windows = [(index, np.full(HORIZON_STEPS, 140.0)) for index in range(6, 13)]
    captured = _capture_samples(monkeypatch)

    artifact = ForecastService._fit_static_event_personalization(
        readings, [event], windows, _default_parameters()
    )

    assert len(captured) == len(windows)
    assert {sample.event_id for sample in captured} == {event.event_id}
    assert {sample.occurred_at_ms for sample in captured} == {event.occurred_at_ms}
    rapid = artifact["kinds"]["rapid"]
    assert rapid["valid_samples"] == len(windows)
    assert rapid["evidence_groups"] == 1
    assert rapid["accepted"] is False


def test_same_kind_overlap_is_excluded_per_horizon_not_per_window(monkeypatch):
    readings = _readings([0])
    events = [_event("meal-a", BASE_MS), _event("meal-b", BASE_MS)]
    effects = {
        "meal-a": np.asarray([2.0, 3.0, 0.0] + [0.0] * (HORIZON_STEPS - 3)),
        "meal-b": np.asarray([0.0, 4.0, 5.0] + [0.0] * (HORIZON_STEPS - 3)),
    }
    captured = _capture_samples(monkeypatch)
    monkeypatch.setattr(
        forecast_module,
        "_static_reference_prediction",
        lambda *args: np.full(HORIZON_STEPS, 150.0),
    )
    monkeypatch.setattr(
        forecast_module,
        "_event_glucose_increment",
        lambda event, start, end, parameters: effects[event.event_id][
            (end - start) // STEP_MS - 1
        ],
    )
    target = 150.0 + np.arange(1, HORIZON_STEPS + 1, dtype=np.float64)
    ForecastService._fit_static_event_personalization(
        readings, events, [(0, target)], _default_parameters()
    )

    assert [sample.event_id for sample in captured] == ["meal-a", "meal-b"]
    np.testing.assert_array_equal(captured[0].population_effect_mg_dl, [2.0])
    np.testing.assert_array_equal(captured[0].observed_residual_mg_dl, [1.0])
    np.testing.assert_array_equal(captured[1].population_effect_mg_dl, [5.0])
    np.testing.assert_array_equal(captured[1].observed_residual_mg_dl, [3.0])


def test_residual_subtracts_complete_unpersonalized_reference_including_other_kinds(
    monkeypatch,
):
    readings = _readings(range(0, 65, 5))
    anchor = readings[-1].measured_at_ms
    events = [
        _event("meal", anchor),
        _event("rapid", anchor, kind="rapid", amount=4.0),
        _event("long", anchor, kind="long", amount=10.0),
    ]
    population_parameters = _default_parameters()
    parameters = dict(population_parameters, event_personalization=_accepted_meal_artifact())
    individual_effects = {
        event.kind: np.asarray(
            [
                _event_glucose_increment(event, anchor, anchor + h * STEP_MS, parameters)
                for h in range(1, HORIZON_STEPS + 1)
            ]
        )
        for event in events
    }
    complete_population_reference = 150.0 + sum(individual_effects.values())
    np.testing.assert_allclose(
        _static_reference_prediction(readings, events, anchor, population_parameters),
        complete_population_reference,
    )
    assert not np.allclose(
        _static_reference_prediction(readings, events, anchor, parameters),
        complete_population_reference,
    )
    captured = _capture_samples(monkeypatch)
    ForecastService._fit_static_event_personalization(
        readings,
        events,
        [(len(readings) - 1, complete_population_reference + 11.25)],
        parameters,
    )

    assert {sample.kind for sample in captured} == set(EVENT_KINDS)
    for sample in captured:
        # Removing the target event, omitting another kind, or applying an old
        # personalization a second time would all produce a different residual.
        np.testing.assert_allclose(sample.observed_residual_mg_dl, 11.25)
        np.testing.assert_allclose(
            sample.population_effect_mg_dl, individual_effects[sample.kind]
        )


def test_fit_excludes_invalid_clipped_and_zero_energy_horizons_and_marks_low_safety(
    monkeypatch,
):
    readings = _readings([0])
    reference = np.full(HORIZON_STEPS, 150.0)
    reference[:6] = [70.0, 90.0, 80.0, 20.0, 600.0, np.nan]
    target = np.full(HORIZON_STEPS, 155.0)
    target[:3] = [90.0, 70.0, 80.0]
    target[6:10] = [20.0, 600.0, np.nan, np.inf]
    effect = np.full(HORIZON_STEPS, 10.0)
    effect[10:12] = [0.0, np.nan]
    captured = _capture_samples(monkeypatch)
    monkeypatch.setattr(forecast_module, "_static_reference_prediction", lambda *args: reference)
    monkeypatch.setattr(
        forecast_module,
        "_event_glucose_increment",
        lambda event, start, end, parameters: effect[(end - start) // STEP_MS - 1],
    )
    ForecastService._fit_static_event_personalization(
        readings, [_event("meal", BASE_MS)], [(0, target)], _default_parameters()
    )

    assert len(captured) == 1
    sample = captured[0]
    selected = [0, 1, 2] + list(range(12, HORIZON_STEPS))
    np.testing.assert_array_equal(sample.population_effect_mg_dl, effect[selected])
    np.testing.assert_array_equal(
        sample.observed_residual_mg_dl, (target - reference)[selected]
    )
    np.testing.assert_array_equal(
        sample.safety_mask, [True, True, False] + [False] * (HORIZON_STEPS - 12)
    )


@pytest.mark.parametrize("target", [[], np.zeros(HORIZON_STEPS - 1), np.zeros((1, HORIZON_STEPS))])
def test_fit_skips_malformed_target_shapes(monkeypatch, target):
    captured = _capture_samples(monkeypatch)
    artifact = ForecastService._fit_static_event_personalization(
        _readings([0]), [_event("meal", BASE_MS)], [(0, target)], _default_parameters()
    )

    assert captured == []
    assert artifact == fit_bounded_event_personalization([])


def test_reference_applies_personalization_once_per_aggregate_kind_and_caps_adjustment(
    monkeypatch,
):
    readings = _readings(range(0, 65, 5))
    anchor = readings[-1].measured_at_ms
    events = [
        _event("meal-a", anchor, amount=200.0),
        _event("meal-b", anchor, amount=200.0),
        _event("rapid", anchor, kind="rapid", amount=5.0),
        _event("long", anchor, kind="long", amount=10.0),
    ]
    artifact = _accepted_meal_artifact()
    parameters = dict(_default_parameters(), event_personalization=artifact)
    population = {kind: np.zeros(HORIZON_STEPS) for kind in EVENT_KINDS}
    for event in events:
        population[event.kind] += [
            _event_glucose_increment(event, anchor, anchor + h * STEP_MS, parameters)
            for h in range(1, HORIZON_STEPS + 1)
        ]
    calls = []

    def apply(kind, effect, supplied_artifact):
        calls.append((kind, np.asarray(effect).copy(), supplied_artifact))
        return apply_bounded_event_personalization(kind, effect, supplied_artifact)

    monkeypatch.setattr(forecast_module, "apply_bounded_event_personalization", apply)
    actual = _static_reference_prediction(readings, events, anchor, parameters)
    corrected = {
        kind: apply_bounded_event_personalization(kind, population[kind], artifact)
        for kind in EVENT_KINDS
    }

    assert [kind for kind, _effect, _artifact in calls] == list(EVENT_KINDS)
    for kind, effect, supplied_artifact in calls:
        np.testing.assert_allclose(effect, population[kind])
        assert supplied_artifact is artifact
    np.testing.assert_allclose(actual, np.clip(150.0 + sum(corrected.values()), 20.0, 600.0))
    assert corrected["meal"][-1] - population["meal"][-1] == pytest.approx(30.0)
    # Capping each overlapping meal separately would incorrectly double the cap.
    incorrectly_per_event = 2.0 * apply_bounded_event_personalization(
        "meal", population["meal"] / 2.0, artifact
    )
    assert incorrectly_per_event[-1] > corrected["meal"][-1] + 20.0
    np.testing.assert_array_equal(corrected["long"], population["long"])


def test_two_long_injections_remain_population_prior_despite_many_training_windows():
    episode_offsets = [0, 7 * 24 * 60]
    readings = _readings(
        [episode + minute for episode in episode_offsets for minute in range(0, 65, 5)]
    )
    events = [
        _event(
            f"long-{index}",
            BASE_MS + episode * 60_000,
            kind="long",
            amount=20.0,
        )
        for index, episode in enumerate(episode_offsets)
    ]
    parameters = _default_parameters()
    artifact = ForecastService._fit_static_event_personalization(
        readings,
        events,
        [(index, np.full(HORIZON_STEPS, 140.0)) for index in range(len(readings))],
        parameters,
    )

    long = artifact["kinds"]["long"]
    assert long["evidence_groups"] == 2
    assert long["valid_samples"] == len(readings)
    assert long["accepted"] is False
    assert long["scale"] == 1.0
    anchor = readings[-1].measured_at_ms
    np.testing.assert_array_equal(
        _static_reference_prediction(
            readings[-13:], [events[-1]], anchor, dict(parameters, event_personalization=artifact)
        ),
        _static_reference_prediction(readings[-13:], [events[-1]], anchor, parameters),
    )


@pytest.mark.parametrize("heldout_residual, accepted", [(3.0, False), (10.0, True)])
def test_fit_checks_combined_meal_and_insulin_response_before_applying_scales(
    monkeypatch, heldout_residual, accepted,
):
    readings = _readings([index * 24 * 60 for index in range(8)])
    events = [
        _event(
            f"{kind}-{index}", row.measured_at_ms, kind=kind, amount=5.0,
        )
        for index, row in enumerate(readings)
        for kind in ("meal", "rapid")
    ]
    monkeypatch.setattr(
        forecast_module,
        "_static_reference_prediction",
        lambda *args: np.full(HORIZON_STEPS, 150.0),
    )

    def effect(event, anchor_ms, target_ms, parameters):
        if event.occurred_at_ms != anchor_ms:
            return 0.0
        return 100.0 if event.kind == "meal" else -100.0

    monkeypatch.setattr(forecast_module, "_event_glucose_increment", effect)
    artifact = ForecastService._fit_static_event_personalization(
        readings,
        events,
        [
            (index, np.full(HORIZON_STEPS, 150.0 + (10.0 if index < 6 else heldout_residual)))
            for index in range(8)
        ],
        _default_parameters(),
    )

    assert artifact["combined_validation"]["accepted"] is accepted
    assert combined_event_personalization_is_valid(artifact)
    for kind in ("meal", "rapid"):
        assert artifact["kinds"][kind]["accepted"] is accepted
        if not accepted:
            assert artifact["kinds"][kind]["scale"] == 1.0
    if not accepted:
        # Each channel alone improved error, but their summed correction did not.
        combined = artifact["combined_validation"]
        assert combined["corrected_mae_mg_dl"] > combined["prior_mae_mg_dl"]
