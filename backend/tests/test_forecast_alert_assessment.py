from __future__ import annotations

import time

from sqlalchemy import select

from app.forecast import (
    ALERT_TARGET_HIGH_MG_DL,
    ALERT_TARGET_HIGH_MMOL_L,
    ALERT_TARGET_LOW_MG_DL,
    ALERT_TARGET_LOW_MMOL_L,
    BASELINE_VERSION,
    STEP_MS,
    _alert_assessment,
)
from app.models import ForecastRunRecord, GlucoseReadingRecord
from app.schemas import ForecastAlertAssessment, ForecastPoint


def _point(
    anchor_ms: int,
    minute: int,
    *,
    median: float = 118.0,
    low: float = 100.0,
    high: float = 136.0,
) -> ForecastPoint:
    return ForecastPoint(
        at_ms=anchor_ms + minute * 60_000,
        median_mg_dl=median,
        low_mg_dl=low,
        high_mg_dl=high,
    )


def _assessment(
    points: list[ForecastPoint],
    *,
    status: str = "ready",
    model_version: str = "approved-static",
    reading_fresh: bool = True,
    alert_approved: bool = True,
) -> ForecastAlertAssessment:
    return _alert_assessment(
        status=status,
        anchor_ms=1_800_000_000_000,
        points=points,
        model_version=model_version,
        reading_fresh=reading_fresh,
        alert_approved=alert_approved,
    )


def test_alert_contract_has_exact_target_and_no_treatment_fields():
    assessment = _assessment([])
    assert assessment.target_low_mmol_l == ALERT_TARGET_LOW_MMOL_L == 4.2
    assert assessment.target_high_mmol_l == ALERT_TARGET_HIGH_MMOL_L == 9.0
    assert assessment.target_low_mg_dl == ALERT_TARGET_LOW_MG_DL == 75.6
    assert assessment.target_high_mg_dl == ALERT_TARGET_HIGH_MG_DL == 162.0

    schema_text = str(ForecastAlertAssessment.model_json_schema()).lower()
    for forbidden in (
        "recommended_dose",
        "recommended_carbs",
        "insulin_units",
        "carbs_g",
    ):
        assert forbidden not in schema_text


def test_exact_boundary_is_in_target_and_interval_crossing_needs_two_points():
    anchor = 1_800_000_000_000
    possible = _alert_assessment(
        status="ready",
        anchor_ms=anchor,
        points=[
            _point(
                anchor,
                5,
                median=ALERT_TARGET_LOW_MG_DL,
                low=ALERT_TARGET_LOW_MG_DL,
            ),
            _point(
                anchor,
                10,
                median=ALERT_TARGET_LOW_MG_DL,
                low=ALERT_TARGET_LOW_MG_DL - 0.1,
            ),
            _point(
                anchor,
                15,
                median=ALERT_TARGET_LOW_MG_DL,
                low=ALERT_TARGET_LOW_MG_DL - 0.1,
            ),
        ],
        model_version="approved-static",
        reading_fresh=True,
        alert_approved=True,
    )
    assert possible.low is not None
    assert possible.low.evidence == "possible"
    assert possible.low.lead_minutes == 10
    assert possible.low.crossing_at_ms == anchor + 10 * 60_000

    one_point_only = _assessment(
        [
            _point(anchor, 5, low=70.0),
            _point(anchor, 10, low=ALERT_TARGET_LOW_MG_DL),
        ]
    )
    assert one_point_only.low is None

    high_possible = _assessment(
        [
            _point(
                anchor,
                5,
                median=ALERT_TARGET_HIGH_MG_DL,
                high=ALERT_TARGET_HIGH_MG_DL,
            ),
            _point(
                anchor,
                10,
                median=ALERT_TARGET_HIGH_MG_DL,
                high=ALERT_TARGET_HIGH_MG_DL + 0.1,
            ),
            _point(
                anchor,
                15,
                median=ALERT_TARGET_HIGH_MG_DL,
                high=ALERT_TARGET_HIGH_MG_DL + 0.1,
            ),
        ]
    )
    assert high_possible.high is not None
    assert high_possible.high.evidence == "possible"
    assert high_possible.high.lead_minutes == 10


def test_likely_crossing_takes_precedence_and_is_bounded_to_first_hour():
    anchor = 1_800_000_000_000
    assessment = _alert_assessment(
        status="ready",
        anchor_ms=anchor,
        points=[
            _point(anchor, 5, median=118.0, low=70.0),
            _point(anchor, 10, median=118.0, low=70.0),
            _point(anchor, 55, median=74.0, low=65.0),
            _point(anchor, 60, median=73.0, low=64.0),
            _point(anchor, 65, median=170.0, high=180.0),
            _point(anchor, 70, median=171.0, high=181.0),
        ],
        model_version="approved-static",
        reading_fresh=True,
        alert_approved=True,
    )
    assert assessment.low is not None
    assert assessment.low.evidence == "likely"
    assert assessment.low.lead_minutes == 55
    assert assessment.high is None


def test_early_possible_and_later_likely_are_both_preserved():
    anchor = 1_800_000_000_000
    assessment = _assessment(
        [
            _point(anchor, 5),
            _point(anchor, 10, median=100.0, low=70.0),
            _point(anchor, 15, median=99.0, low=69.0),
            _point(anchor, 50, median=74.0, low=62.0),
            _point(anchor, 55, median=73.0, low=61.0),
        ]
    )

    assert assessment.low_possible is not None
    assert assessment.low_possible.evidence == "possible"
    assert assessment.low_possible.lead_minutes == 10
    assert assessment.low_likely is not None
    assert assessment.low_likely.evidence == "likely"
    assert assessment.low_likely.lead_minutes == 50
    # Existing clients retain the former conservative likely-first summary.
    assert assessment.low == assessment.low_likely


def test_points_must_be_adjacent_five_minute_forecast_steps():
    anchor = 1_800_000_000_000
    assessment = _assessment(
        [
            _point(anchor, 5, median=70.0, low=60.0),
            _point(anchor, 15, median=69.0, low=59.0),
        ]
    )
    assert assessment.low is None


def test_unavailable_and_shadow_states_fail_closed():
    anchor = 1_800_000_000_000
    crossing = [
        _point(anchor, 5, median=170.0, high=180.0),
        _point(anchor, 10, median=171.0, high=181.0),
    ]
    for status in ("no_data", "stale", "low_confidence"):
        assessment = _assessment(crossing, status=status)
        assert assessment.monitoring_status == "unavailable"
        assert assessment.delivery_eligible is False
        assert assessment.low is None
        assert assessment.high is None
        assert assessment.low_possible is None
        assert assessment.low_likely is None
        assert assessment.high_possible is None
        assert assessment.high_likely is None

    baseline = _assessment(
        crossing,
        status="cold_start",
        model_version=BASELINE_VERSION,
        alert_approved=False,
    )
    assert baseline.monitoring_status == "shadow"
    assert baseline.delivery_eligible is False
    assert baseline.suppressed_reasons == ["baseline_model"]
    assert baseline.high is not None
    assert baseline.high.evidence == "likely"

    unapproved = _assessment(crossing, alert_approved=False)
    assert unapproved.monitoring_status == "shadow"
    assert unapproved.delivery_eligible is False
    assert unapproved.suppressed_reasons == ["alert_not_approved"]

    delayed = _assessment(crossing, reading_fresh=False)
    assert delayed.monitoring_status == "unavailable"
    assert delayed.suppressed_reasons == ["reading_not_fresh"]


def test_explicit_approved_ready_assessment_is_delivery_eligible():
    anchor = 1_800_000_000_000
    assessment = _assessment(
        [
            _point(anchor, 20, median=163.0, high=168.0),
            _point(anchor, 25, median=164.0, high=169.0),
        ]
    )
    assert assessment.monitoring_status == "eligible"
    assert assessment.delivery_eligible is True
    assert assessment.suppressed_reasons == []
    assert assessment.high is not None
    assert assessment.high.direction == "high"
    assert assessment.high.evidence == "likely"
    assert assessment.high.lead_minutes == 20


def test_current_no_data_assessment_is_explicitly_unavailable(
    client, auth_headers
):
    response = client.get("/v1/forecast/current", headers=auth_headers)
    assert response.status_code == 200
    payload = response.json()
    assert payload["based_on_glucose_mg_dl"] is None
    assert payload["alert_assessment"]["monitoring_status"] == "unavailable"
    assert payload["alert_assessment"]["delivery_eligible"] is False
    assert payload["alert_assessment"]["suppressed_reasons"] == ["no_data"]


def test_current_stale_assessment_keeps_raw_anchor_but_no_crossings(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 16 * 60_000
    readings = [
        {
            "reading_id": f"alert-stale-{index}",
            "measured_at_ms": anchor - (23 - index) * STEP_MS,
            "glucose_mg_dl": 108.0 + index * 0.1,
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
    assert payload["status"] == "stale"
    assert payload["based_on_glucose_mg_dl"] == readings[-1]["glucose_mg_dl"]
    assessment = payload["alert_assessment"]
    assert assessment["monitoring_status"] == "unavailable"
    assert assessment["delivery_eligible"] is False
    assert assessment["low"] is None
    assert assessment["high"] is None


def test_current_response_populates_raw_glucose_and_reuses_cached_assessment(
    client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    readings = [
        {
            "reading_id": f"alert-cache-{index}",
            "measured_at_ms": anchor - (23 - index) * STEP_MS,
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

    first = client.get("/v1/forecast/current", headers=auth_headers)
    second = client.get("/v1/forecast/current", headers=auth_headers)
    assert first.status_code == second.status_code == 200
    first_payload = first.json()
    second_payload = second.json()
    assert first_payload["based_on_glucose_mg_dl"] == readings[-1]["glucose_mg_dl"]
    assert second_payload["based_on_glucose_mg_dl"] == readings[-1]["glucose_mg_dl"]
    assert first_payload["alert_assessment"] == second_payload["alert_assessment"]
    assert first_payload["alert_assessment"]["monitoring_status"] == "shadow"
    assert first_payload["alert_assessment"]["delivery_eligible"] is False


def test_cached_response_with_corrupt_legacy_glucose_fails_closed(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 1_000
    readings = [
        {
            "reading_id": f"alert-corrupt-cache-{index}",
            "measured_at_ms": anchor - (23 - index) * STEP_MS,
            "glucose_mg_dl": 112.0,
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
    assert client.get(
        "/v1/forecast/current", headers=auth_headers
    ).status_code == 200

    with app.state.database.session_factory() as session:
        latest = session.get(GlucoseReadingRecord, readings[-1]["reading_id"])
        assert latest is not None
        # Simulate a row from a legacy/manual import that bypassed API validation.
        latest.glucose_mg_dl = 999.0
        session.commit()
        run = session.scalar(
            select(ForecastRunRecord).order_by(
                ForecastRunRecord.generated_at_ms.desc()
            )
        )
        assert run is not None
        cached = app.state.forecast_service._run_response(
            session, run, now_ms=int(time.time() * 1_000)
        )
        assert cached.based_on_glucose_mg_dl is None
        assert cached.alert_assessment is not None
        assert cached.alert_assessment.monitoring_status == "unavailable"

    response = client.get("/v1/forecast/current", headers=auth_headers)
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "low_confidence"
    assert payload["based_on_glucose_mg_dl"] is None
    assessment = payload["alert_assessment"]
    assert assessment["monitoring_status"] == "unavailable"
    assert assessment["delivery_eligible"] is False
    assert assessment["suppressed_reasons"] == ["low_confidence"]
    for name in (
        "low",
        "high",
        "low_possible",
        "low_likely",
        "high_possible",
        "high_likely",
    ):
        assert assessment[name] is None


def test_stale_response_with_corrupt_legacy_glucose_does_not_500(
    app, client, auth_headers
):
    anchor = int(time.time() * 1_000) - 16 * 60_000
    reading = {
        "reading_id": "alert-corrupt-stale",
        "measured_at_ms": anchor,
        "glucose_mg_dl": 108.0,
        "trend_mg_dl_min": 0.0,
        "quality": 1.0,
    }
    uploaded = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [reading]},
    )
    assert uploaded.status_code == 200
    with app.state.database.session_factory() as session:
        stored = session.get(GlucoseReadingRecord, reading["reading_id"])
        assert stored is not None
        stored.glucose_mg_dl = float("inf")
        session.commit()

    response = client.get("/v1/forecast/current", headers=auth_headers)
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "stale"
    assert payload["based_on_glucose_mg_dl"] is None
    assert payload["alert_assessment"]["monitoring_status"] == "unavailable"
