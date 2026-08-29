from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from collections.abc import Callable, Generator
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
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
    ViewerInsulinEvent,
    ViewerIntakeEvent,
    ViewerIntakePage,
    ViewerSnapshot,
    ViewerSessionCreate,
    ViewerSessionResponse,
    ViewerTargetRange,
)
from .security import (
    VIEWER_SESSION_COOKIE,
    issue_viewer_session,
    require_viewer_token,
    viewer_session_expiry_ms,
    viewer_token_matches,
)


DEFAULT_SNAPSHOT_MS = 24 * 60 * 60_000
DEFAULT_PAGE_MS = 31 * 24 * 60 * 60_000
MAX_WINDOW_MS = 31 * 24 * 60 * 60_000
MAX_FUTURE_MS = 10 * 60_000
CURSOR_VERSION = 1

SessionProvider = Callable[..., Generator[Session, None, None]]
EventResponseFactory = Callable[[IntakeEventRecord, str | None], IntakeEvent]


def _require_same_origin(request: Request) -> None:
    """Reject cross-origin browser session mutations without enabling CORS."""

    origin = request.headers.get("origin")
    if origin is None:
        return
    try:
        parsed = urlsplit(origin)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="cross-origin viewer sessions are not allowed",
        ) from None
    request_host = request.headers.get("host", "").casefold()
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
        or parsed.netloc.casefold() != request_host
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="cross-origin viewer sessions are not allowed",
        )


def _set_viewer_session_cookie(
    response: Response,
    encoded: str,
    expires_at_ms: int,
) -> None:
    expires_at = datetime.fromtimestamp(expires_at_ms / 1_000, tz=UTC)
    max_age = max(1, int(expires_at.timestamp() - time.time()))
    response.set_cookie(
        key=VIEWER_SESSION_COOKIE,
        value=encoded,
        max_age=max_age,
        expires=expires_at,
        path="/",
        secure=True,
        httponly=True,
        samesite="strict",
    )


def create_viewer_session_router() -> APIRouter:
    router = APIRouter(prefix="/v1/viewer", tags=["read-only viewer session"])

    @router.post("/session", response_model=ViewerSessionResponse)
    def create_session(
        payload: ViewerSessionCreate,
        request: Request,
        response: Response,
    ) -> ViewerSessionResponse:
        _require_same_origin(request)
        settings = request.app.state.settings
        if settings.viewer_public:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="viewer public access is enabled; no session is required",
            )
        if not settings.viewer_auth_configured:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="backend viewer authentication is not configured",
            )
        supplied = payload.token.get_secret_value()
        if not 32 <= len(supplied) <= 512 or not viewer_token_matches(
            settings, supplied
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid viewer credentials",
            )
        encoded, expires_at_ms = issue_viewer_session(settings)
        _set_viewer_session_cookie(response, encoded, expires_at_ms)
        return ViewerSessionResponse(
            access_mode="session",
            expires_at_ms=expires_at_ms,
        )

    @router.get("/session", response_model=ViewerSessionResponse)
    def session_status(request: Request, response: Response) -> ViewerSessionResponse:
        settings = request.app.state.settings
        if settings.viewer_public:
            # Public mode has no browser session. Remove any cookie left by an
            # earlier private deployment so switching modes cannot silently
            # revive that old session later.
            if VIEWER_SESSION_COOKIE in request.cookies:
                response.delete_cookie(
                    key=VIEWER_SESSION_COOKIE,
                    path="/",
                    secure=True,
                    httponly=True,
                    samesite="strict",
                )
            return ViewerSessionResponse(
                access_mode="public",
                expires_at_ms=None,
            )
        expires_at_ms = viewer_session_expiry_ms(
            settings,
            request.cookies.get(VIEWER_SESSION_COOKIE),
        )
        if expires_at_ms is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid viewer credentials",
            )
        # A successful foreground check rolls the session, so regularly used
        # installed PWAs do not ask for the read-only token every month.
        encoded, expires_at_ms = issue_viewer_session(settings)
        _set_viewer_session_cookie(response, encoded, expires_at_ms)
        return ViewerSessionResponse(
            access_mode="session",
            expires_at_ms=expires_at_ms,
        )

    @router.delete("/session", status_code=status.HTTP_204_NO_CONTENT)
    def delete_session(request: Request, response: Response) -> Response:
        _require_same_origin(request)
        response.delete_cookie(
            key=VIEWER_SESSION_COOKIE,
            path="/",
            secure=True,
            httponly=True,
            samesite="strict",
        )
        response.status_code = status.HTTP_204_NO_CONTENT
        return response

    return router


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


def _validate_public_window(request: Request, from_ms: int, to_ms: int) -> None:
    if (
        request.app.state.settings.viewer_public
        and to_ms - from_ms > DEFAULT_SNAPSHOT_MS
    ):
        raise _http_422("public viewer windows cannot exceed 24 hours")


def _cursor_key(request: Request) -> bytes:
    settings = request.app.state.settings
    if settings.viewer_public:
        # An internal process-local key keeps anonymous cursors tamper-resistant
        # without introducing a user-visible login secret. Cursors are allowed
        # to expire across a backend restart; clients can restart pagination.
        return hashlib.sha256(
            b"juggluco-viewer-public-cursor-v1\0"
            + request.app.state.viewer_public_cursor_key
        ).digest()
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
    encoded_before_id = before_id
    if request.app.state.settings.viewer_public and kind == "glucose":
        encoded_before_id = _public_reading_id(
            request.app.state.viewer_public_cursor_key,
            before_id,
        )
    payload = json.dumps(
        {
            "v": CURSOR_VERSION,
            "kind": kind,
            "from_ms": from_ms,
            "to_ms": to_ms,
            "before_at_ms": before_at_ms,
            "before_id": encoded_before_id,
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
        default_page_ms = (
            DEFAULT_SNAPSHOT_MS
            if request.app.state.settings.viewer_public
            else DEFAULT_PAGE_MS
        )
        resolved_from = (
            max(0, resolved_to - default_page_ms) if from_ms is None else from_ms
        )
        before_at_ms = None
        before_id = None
    _validate_window(resolved_from, resolved_to, now_ms)
    _validate_public_window(request, resolved_from, resolved_to)
    return resolved_from, resolved_to, before_at_ms, before_id


def _public_reading_id(key: bytes, reading_id: str) -> str:
    digest = hmac.digest(key, reading_id.encode("utf-8"), "sha256").hex()
    return f"reading-{digest[:24]}"


def _private_reading_id_for_public_cursor(
    session: Session,
    *,
    key: bytes,
    measured_at_ms: int,
    opaque_id: str,
) -> str:
    candidates = session.scalars(
        select(GlucoseReadingRecord.reading_id).where(
            GlucoseReadingRecord.measured_at_ms == measured_at_ms
        )
    )
    matches = [
        candidate
        for candidate in candidates
        if _public_reading_id(key, candidate) == opaque_id
    ]
    if len(matches) != 1:
        raise _http_422("invalid or mismatched viewer cursor")
    return matches[0]


def _glucose_response(
    record: GlucoseReadingRecord,
    *,
    public_key: bytes | None = None,
) -> ViewerGlucoseReading:
    is_public = public_key is not None
    return ViewerGlucoseReading(
        reading_id=(
            _public_reading_id(public_key, record.reading_id)
            if public_key is not None
            else record.reading_id
        ),
        measured_at_ms=record.measured_at_ms,
        glucose_mg_dl=record.glucose_mg_dl,
        trend_mg_dl_min=record.trend_mg_dl_min,
        sensor_id=None if is_public else record.sensor_id,
        sensor_generation=None if is_public else record.sensor_generation,
        quality=record.quality,
        utc_offset_minutes=record.utc_offset_minutes,
        received_at_ms=record.received_at_ms,
    )


def _current_glucose_response(
    record: GlucoseReadingRecord,
    now_ms: int,
    *,
    public_key: bytes | None = None,
) -> ViewerCurrentGlucose:
    return ViewerCurrentGlucose(
        **_glucose_response(record, public_key=public_key).model_dump(),
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


def _insulin_response(record: IntakeEventRecord) -> ViewerInsulinEvent:
    insulin_type = record.insulin_type
    insulin_units = record.insulin_units
    if insulin_type not in {"rapid", "long"} or insulin_units is None:
        # The query below excludes this state. Keep construction fail-closed in
        # case a legacy or manually modified record reaches this projection.
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="stored insulin event has an unsupported type",
        )
    return ViewerInsulinEvent(
        occurred_at_ms=record.occurred_at_ms,
        insulin_units=insulin_units,
        insulin_type=insulin_type,
        insulin_name=record.insulin_name,
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


def _active_insulin_filter():
    return and_(
        IntakeEventRecord.deleted_at_ms.is_(None),
        IntakeEventRecord.insulin_type.in_(("rapid", "long")),
        IntakeEventRecord.insulin_units.is_not(None),
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
        is_public = request.app.state.settings.viewer_public
        public_key = (
            request.app.state.viewer_public_cursor_key if is_public else None
        )
        resolved_to = now if to_ms is None else to_ms
        resolved_from = (
            max(0, resolved_to - DEFAULT_SNAPSHOT_MS)
            if from_ms is None
            else from_ms
        )
        _validate_window(resolved_from, resolved_to, now)
        _validate_public_window(request, resolved_from, resolved_to)

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

        insulin_rows = list(
            session.scalars(
                select(IntakeEventRecord)
                .where(
                    _active_insulin_filter(),
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
        insulin_truncated = len(insulin_rows) > event_limit
        insulin_rows = insulin_rows[:event_limit]
        insulin_rows.reverse()

        intake_rows: list[IntakeEventRecord] = []
        intake_truncated = False
        analysis_json: dict[str, str] = {}
        if not is_public:
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
        if is_public and forecast.activities:
            forecast = forecast.model_copy(update={"activities": []})
        return ViewerSnapshot(
            server_time_ms=now,
            from_ms=resolved_from,
            to_ms=resolved_to,
            target_range=ViewerTargetRange(),
            current_glucose=(
                _current_glucose_response(
                    current,
                    now,
                    public_key=public_key,
                )
                if current is not None
                else None
            ),
            glucose_history=[
                _glucose_response(row, public_key=public_key)
                for row in glucose_rows
            ],
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
            insulin_events=[_insulin_response(row) for row in insulin_rows],
            insulin_events_truncated=insulin_truncated,
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
        public_key = (
            request.app.state.viewer_public_cursor_key
            if request.app.state.settings.viewer_public
            else None
        )
        resolved_from, resolved_to, before_at_ms, before_id = _page_window(
            request,
            kind="glucose",
            cursor=cursor,
            from_ms=from_ms,
            to_ms=to_ms,
            now_ms=now,
        )
        if public_key is not None and before_at_ms is not None and before_id is not None:
            before_id = _private_reading_id_for_public_cursor(
                session,
                key=public_key,
                measured_at_ms=before_at_ms,
                opaque_id=before_id,
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
            items=[
                _glucose_response(row, public_key=public_key)
                for row in rows
            ],
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
