from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from collections.abc import Callable, Generator
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from .forecast import STALE_AFTER_MS
from .models import AnalysisRecord, GlucoseReadingRecord, IntakeEventRecord
from .schemas import (
    ForecastCurrentResponse,
    IntakeEvent,
    ViewerCurrentGlucose,
    ViewerGlucosePage,
    ViewerGlucoseReading,
    ViewerIntakeEvent,
    ViewerIntakePage,
    ViewerSnapshot,
    ViewerTargetRange,
)
from .security import require_viewer_token


DEFAULT_SNAPSHOT_MS = 24 * 60 * 60_000
DEFAULT_PAGE_MS = 31 * 24 * 60 * 60_000
MAX_WINDOW_MS = 31 * 24 * 60 * 60_000
MAX_FUTURE_MS = 10 * 60_000
CURSOR_VERSION = 1

SessionProvider = Callable[..., Generator[Session, None, None]]
EventResponseFactory = Callable[[IntakeEventRecord, str | None], IntakeEvent]


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _http_422(detail: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=detail,
    )


def _validate_window(from_ms: int, to_ms: int, now_ms: int) -> None:
    if from_ms > to_ms:
        raise _http_422("from_ms must be less than or equal to to_ms")
    if to_ms > now_ms + MAX_FUTURE_MS:
        raise _http_422("to_ms cannot be more than 10 minutes in the future")
    if to_ms - from_ms > MAX_WINDOW_MS:
        raise _http_422("viewer time windows cannot exceed 31 days")


def _cursor_key(request: Request) -> bytes:
    settings = request.app.state.settings
    secret = (
        settings.viewer_token
        if settings.viewer_auth_configured
        else settings.api_token
    )
    # Authentication runs before every viewer route, so this is defensive only.
    if secret is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="backend viewer authentication is not configured",
        )
    return hashlib.sha256(b"juggluco-viewer-cursor-v1\0" + secret.encode()).digest()


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _b64decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    decoded = base64.b64decode(
        value + padding,
        altchars=b"-_",
        validate=True,
    )
    if _b64encode(decoded) != value:
        raise ValueError("non-canonical base64url encoding")
    return decoded


def _encode_cursor(
    request: Request,
    *,
    kind: str,
    from_ms: int,
    to_ms: int,
    before_at_ms: int,
    before_id: str,
) -> str:
    payload = json.dumps(
        {
            "v": CURSOR_VERSION,
            "kind": kind,
            "from_ms": from_ms,
            "to_ms": to_ms,
            "before_at_ms": before_at_ms,
            "before_id": before_id,
        },
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    signature = hmac.digest(_cursor_key(request), payload, "sha256")
    return f"{_b64encode(payload)}.{_b64encode(signature)}"


def _decode_cursor(request: Request, cursor: str, expected_kind: str) -> dict[str, Any]:
    try:
        encoded_payload, encoded_signature = cursor.split(".", 1)
        payload = _b64decode(encoded_payload)
        supplied_signature = _b64decode(encoded_signature)
        expected_signature = hmac.digest(_cursor_key(request), payload, "sha256")
        if not hmac.compare_digest(supplied_signature, expected_signature):
            raise ValueError("cursor signature mismatch")
        decoded = json.loads(payload.decode("utf-8"))
        if not isinstance(decoded, dict) or set(decoded) != {
            "v",
            "kind",
            "from_ms",
            "to_ms",
            "before_at_ms",
            "before_id",
        }:
            raise ValueError("unexpected cursor shape")
        if decoded["v"] != CURSOR_VERSION or decoded["kind"] != expected_kind:
            raise ValueError("cursor endpoint mismatch")
        if any(
            type(decoded[field]) is not int
            for field in ("from_ms", "to_ms", "before_at_ms")
        ):
            raise ValueError("invalid cursor timestamp")
        if (
            decoded["from_ms"] < 0
            or decoded["to_ms"] <= 0
            or decoded["before_at_ms"] <= 0
            or not isinstance(decoded["before_id"], str)
            or not decoded["before_id"]
            or len(decoded["before_id"]) > 160
        ):
            raise ValueError("invalid cursor value")
        return decoded
    except (UnicodeError, ValueError, TypeError, json.JSONDecodeError):
        raise _http_422("invalid or mismatched viewer cursor") from None


def _page_window(
    request: Request,
    *,
    kind: str,
    cursor: str | None,
    from_ms: int | None,
    to_ms: int | None,
    now_ms: int,
) -> tuple[int, int, int | None, str | None]:
    if cursor is not None:
        decoded = _decode_cursor(request, cursor, kind)
        cursor_from = decoded["from_ms"]
        cursor_to = decoded["to_ms"]
        if (from_ms is not None and from_ms != cursor_from) or (
            to_ms is not None and to_ms != cursor_to
        ):
            raise _http_422("invalid or mismatched viewer cursor")
        resolved_from = cursor_from
        resolved_to = cursor_to
        before_at_ms = decoded["before_at_ms"]
        before_id = decoded["before_id"]
    else:
        resolved_to = now_ms if to_ms is None else to_ms
        resolved_from = (
            max(0, resolved_to - DEFAULT_PAGE_MS) if from_ms is None else from_ms
        )
        before_at_ms = None
        before_id = None
    _validate_window(resolved_from, resolved_to, now_ms)
    return resolved_from, resolved_to, before_at_ms, before_id


def _glucose_response(record: GlucoseReadingRecord) -> ViewerGlucoseReading:
    return ViewerGlucoseReading(
        reading_id=record.reading_id,
        measured_at_ms=record.measured_at_ms,
        glucose_mg_dl=record.glucose_mg_dl,
        trend_mg_dl_min=record.trend_mg_dl_min,
        sensor_id=record.sensor_id,
        sensor_generation=record.sensor_generation,
        quality=record.quality,
        utc_offset_minutes=record.utc_offset_minutes,
        received_at_ms=record.received_at_ms,
    )


def _current_glucose_response(
    record: GlucoseReadingRecord,
    now_ms: int,
) -> ViewerCurrentGlucose:
    return ViewerCurrentGlucose(
        **_glucose_response(record).model_dump(),
        age_ms=max(0, now_ms - record.measured_at_ms),
        is_stale=now_ms - record.measured_at_ms > STALE_AFTER_MS,
    )


def _analysis_json_by_id(
    session: Session,
    records: list[IntakeEventRecord],
) -> dict[str, str]:
    analysis_ids = {
        record.analysis_id for record in records if record.analysis_id is not None
    }
    if not analysis_ids:
        return {}
    return dict(
        session.execute(
            select(AnalysisRecord.id, AnalysisRecord.result_json).where(
                AnalysisRecord.id.in_(analysis_ids)
            )
        ).all()
    )


def _intake_response(
    record: IntakeEventRecord,
    analysis_json: str | None,
    event_response_factory: EventResponseFactory,
) -> ViewerIntakeEvent:
    event = event_response_factory(record, analysis_json)
    kind = "meal" if event.meal_text is not None or event.carbs_g is not None else event.insulin_type
    if kind not in {"meal", "rapid", "long"}:
        # The public write path prevents this.  Fail closed if a legacy or
        # manually modified database nevertheless contains an invalid event.
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="stored intake event has an unsupported type",
        )
    is_meal = kind == "meal"
    return ViewerIntakeEvent(
        id=event.id,
        kind=kind,
        occurred_at_ms=event.occurred_at_ms,
        meal_text=event.meal_text if is_meal else None,
        carbs_g=event.carbs_g if is_meal else None,
        portion_g=event.portion_g if is_meal else None,
        original_portion_g=event.original_portion_g if is_meal else None,
        original_carbs_g=event.original_carbs_g if is_meal else None,
        carbs_source=event.carbs_source if is_meal else None,
        insulin_units=event.insulin_units if not is_meal else None,
        insulin_type=kind if not is_meal else None,
        insulin_name=event.insulin_name if not is_meal else None,
        ai_confidence=event.ai_confidence if is_meal else 0.0,
        absorption_speed=event.absorption_speed if is_meal else None,
        absorption_peak_minutes=(
            event.absorption_peak_minutes if is_meal else None
        ),
        absorption_duration_minutes=(
            event.absorption_duration_minutes if is_meal else None
        ),
        absorption_confidence=event.absorption_confidence if is_meal else None,
        updated_at_ms=event.updated_at_ms,
    )


def _active_intake_filter():
    return and_(
        IntakeEventRecord.deleted_at_ms.is_(None),
        or_(
            IntakeEventRecord.meal_text.is_not(None),
            IntakeEventRecord.carbs_g.is_not(None),
            IntakeEventRecord.insulin_type.in_(("rapid", "long")),
        ),
    )


def create_viewer_router(
    session_provider: SessionProvider,
    event_response_factory: EventResponseFactory,
) -> APIRouter:
    router = APIRouter(
        prefix="/v1/viewer",
        dependencies=[Depends(require_viewer_token)],
        tags=["read-only viewer"],
    )

    @router.get("/snapshot", response_model=ViewerSnapshot)
    def snapshot(
        request: Request,
        session: Session = Depends(session_provider),
        from_ms: int | None = Query(default=None, ge=0),
        to_ms: int | None = Query(default=None, ge=0),
        # 1,500 covers a full 24-hour graph for one-minute CGM sources, with
        # room for an inclusive boundary and irregular duplicate timestamps.
        glucose_limit: int = Query(default=1_500, ge=1, le=2_500),
        event_limit: int = Query(default=100, ge=1, le=500),
    ) -> ViewerSnapshot:
        now = _now_ms()
        resolved_to = now if to_ms is None else to_ms
        resolved_from = (
            max(0, resolved_to - DEFAULT_SNAPSHOT_MS)
            if from_ms is None
            else from_ms
        )
        _validate_window(resolved_from, resolved_to, now)

        current = session.scalar(
            select(GlucoseReadingRecord).order_by(
                GlucoseReadingRecord.measured_at_ms.desc(),
                GlucoseReadingRecord.reading_id.desc(),
            ).limit(1)
        )
        glucose_rows = list(
            session.scalars(
                select(GlucoseReadingRecord)
                .where(
                    GlucoseReadingRecord.measured_at_ms >= resolved_from,
                    GlucoseReadingRecord.measured_at_ms <= resolved_to,
                )
                .order_by(
                    GlucoseReadingRecord.measured_at_ms.desc(),
                    GlucoseReadingRecord.reading_id.desc(),
                )
                .limit(glucose_limit + 1)
            )
        )
        glucose_truncated = len(glucose_rows) > glucose_limit
        glucose_rows = glucose_rows[:glucose_limit]
        glucose_rows.reverse()

        intake_rows = list(
            session.scalars(
                select(IntakeEventRecord)
                .where(
                    _active_intake_filter(),
                    IntakeEventRecord.occurred_at_ms >= resolved_from,
                    IntakeEventRecord.occurred_at_ms <= resolved_to,
                )
                .order_by(
                    IntakeEventRecord.occurred_at_ms.desc(),
                    IntakeEventRecord.id.desc(),
                )
                .limit(event_limit + 1)
            )
        )
        intake_truncated = len(intake_rows) > event_limit
        intake_rows = intake_rows[:event_limit]
        intake_rows.reverse()
        analysis_json = _analysis_json_by_id(session, intake_rows)

        forecast: ForecastCurrentResponse = (
            request.app.state.forecast_service.current(session, now_ms=now)
        )
        return ViewerSnapshot(
            server_time_ms=now,
            from_ms=resolved_from,
            to_ms=resolved_to,
            target_range=ViewerTargetRange(),
            current_glucose=(
                _current_glucose_response(current, now) if current is not None else None
            ),
            glucose_history=[_glucose_response(row) for row in glucose_rows],
            glucose_history_truncated=glucose_truncated,
            intake_events=[
                _intake_response(
                    row,
                    analysis_json.get(row.analysis_id) if row.analysis_id else None,
                    event_response_factory,
                )
                for row in intake_rows
            ],
            intake_events_truncated=intake_truncated,
            forecast=forecast,
        )

    @router.get("/glucose", response_model=ViewerGlucosePage)
    def glucose_history(
        request: Request,
        session: Session = Depends(session_provider),
        cursor: str | None = Query(default=None, min_length=1, max_length=1_024),
        from_ms: int | None = Query(default=None, ge=0),
        to_ms: int | None = Query(default=None, ge=0),
        limit: int = Query(default=200, ge=1, le=500),
    ) -> ViewerGlucosePage:
        now = _now_ms()
        resolved_from, resolved_to, before_at_ms, before_id = _page_window(
            request,
            kind="glucose",
            cursor=cursor,
            from_ms=from_ms,
            to_ms=to_ms,
            now_ms=now,
        )
        statement = select(GlucoseReadingRecord).where(
            GlucoseReadingRecord.measured_at_ms >= resolved_from,
            GlucoseReadingRecord.measured_at_ms <= resolved_to,
        )
        if before_at_ms is not None and before_id is not None:
            statement = statement.where(
                or_(
                    GlucoseReadingRecord.measured_at_ms < before_at_ms,
                    and_(
                        GlucoseReadingRecord.measured_at_ms == before_at_ms,
                        GlucoseReadingRecord.reading_id < before_id,
                    ),
                )
            )
        rows = list(
            session.scalars(
                statement.order_by(
                    GlucoseReadingRecord.measured_at_ms.desc(),
                    GlucoseReadingRecord.reading_id.desc(),
                ).limit(limit + 1)
            )
        )
        has_more = len(rows) > limit
        rows = rows[:limit]
        next_cursor = None
        if has_more and rows:
            last = rows[-1]
            next_cursor = _encode_cursor(
                request,
                kind="glucose",
                from_ms=resolved_from,
                to_ms=resolved_to,
                before_at_ms=last.measured_at_ms,
                before_id=last.reading_id,
            )
        return ViewerGlucosePage(
            items=[_glucose_response(row) for row in rows],
            next_cursor=next_cursor,
            has_more=has_more,
        )

    @router.get("/intakes", response_model=ViewerIntakePage)
    def intake_history(
        request: Request,
        session: Session = Depends(session_provider),
        cursor: str | None = Query(default=None, min_length=1, max_length=1_024),
        from_ms: int | None = Query(default=None, ge=0),
        to_ms: int | None = Query(default=None, ge=0),
        limit: int = Query(default=100, ge=1, le=500),
    ) -> ViewerIntakePage:
        now = _now_ms()
        resolved_from, resolved_to, before_at_ms, before_id = _page_window(
            request,
            kind="intakes",
            cursor=cursor,
            from_ms=from_ms,
            to_ms=to_ms,
            now_ms=now,
        )
        statement = select(IntakeEventRecord).where(
            _active_intake_filter(),
            IntakeEventRecord.occurred_at_ms >= resolved_from,
            IntakeEventRecord.occurred_at_ms <= resolved_to,
        )
        if before_at_ms is not None and before_id is not None:
            statement = statement.where(
                or_(
                    IntakeEventRecord.occurred_at_ms < before_at_ms,
                    and_(
                        IntakeEventRecord.occurred_at_ms == before_at_ms,
                        IntakeEventRecord.id < before_id,
                    ),
                )
            )
        rows = list(
            session.scalars(
                statement.order_by(
                    IntakeEventRecord.occurred_at_ms.desc(),
                    IntakeEventRecord.id.desc(),
                ).limit(limit + 1)
            )
        )
        has_more = len(rows) > limit
        rows = rows[:limit]
        analysis_json = _analysis_json_by_id(session, rows)
        next_cursor = None
        if has_more and rows:
            last = rows[-1]
            next_cursor = _encode_cursor(
                request,
                kind="intakes",
                from_ms=resolved_from,
                to_ms=resolved_to,
                before_at_ms=last.occurred_at_ms,
                before_id=last.id,
            )
        return ViewerIntakePage(
            items=[
                _intake_response(
                    row,
                    analysis_json.get(row.analysis_id) if row.analysis_id else None,
                    event_response_factory,
                )
                for row in rows
            ],
            next_cursor=next_cursor,
            has_more=has_more,
        )

    return router
