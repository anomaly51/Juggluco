from __future__ import annotations

import hashlib
import json
import logging
import math
import time
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import (
    APIRouter,
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from pydantic import ValidationError
from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError, OperationalError, SQLAlchemyError
from sqlalchemy.orm import Session

from .config import Settings
from .database import Database
from .forecast import ForecastService
from .media import MediaValidationError, prepare_audio, prepare_image
from .models import (
    AnalysisRecord,
    IntakeEventRecord,
    MealChatMessageRecord,
    MealChatSessionRecord,
    SyncChangeRecord,
)
from .openrouter import (
    AnalysisError,
    AudioTranscriber,
    MealAnalyzer,
    MealChatAnalyzer,
    MealChatHistoryEntry,
    OpenRouterMealAnalyzer,
    OpenRouterMealChatAnalyzer,
)
from .schemas import (
    AnalysisResponse,
    ForecastCurrentResponse,
    ForecastStatusResponse,
    GlucoseReadingsCreate,
    GlucoseReadingsResponse,
    HealthResponse,
    InsulinEventCreate,
    IntakeCreate,
    IntakeEvent,
    IntakeListResponse,
    ManualMealEventCreate,
    MealPortionUpdate,
    MealChatMessage,
    MealChatProposal,
    MealChatSessionCreate,
    MealChatSessionResponse,
    MealChatSessionTimeUpdate,
    MealChatTurnResponse,
    TranscriptionResponse,
)
from .security import require_api_token
from .viewer import create_viewer_router


logger = logging.getLogger(__name__)


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _begin_immediate(session: Session, operation: str) -> None:
    """Serialize SQLite workflows that must read state before writing it."""

    try:
        session.connection().exec_driver_sql("BEGIN IMMEDIATE")
    except OperationalError as error:
        session.rollback()
        logger.warning("%s could not acquire the database write lock", operation)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=(
                f"{operation} is temporarily unavailable; "
                "fetch the meal-chat session before retrying"
            ),
        ) from error


def _payload_hash(payload: IntakeCreate) -> str:
    # Preserve hashes created before manual portion support was added. A null
    # portion is semantically the old payload and must keep byte-for-byte
    # idempotency across an in-place backend upgrade.
    exclude = {"portion_g"} if payload.portion_g is None else None
    canonical = payload.model_dump_json(exclude_none=False, exclude=exclude)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _analysis_confidence(result_json: str | None) -> float:
    if not result_json:
        return 0.0
    try:
        value = float(json.loads(result_json).get("confidence", 0.0))
    except (AttributeError, TypeError, ValueError, json.JSONDecodeError):
        return 0.0
    if not math.isfinite(value):
        return 0.0
    return max(0.0, min(1.0, value))


def _analysis_absorption(
    result_json: str | None,
) -> tuple[float | None, int | None, int | None, float | None]:
    """Read the optional meal profile without trusting legacy database JSON.

    New analyses are already validated by ``MealAnalysis``/``MealChatProposal``.
    This defensive projection also keeps list/sync usable if an older or manually
    edited database contains malformed values.
    """

    if not result_json:
        return None, None, None, None
    try:
        payload = json.loads(result_json)
    except (TypeError, json.JSONDecodeError):
        return None, None, None, None
    if not isinstance(payload, dict):
        return None, None, None, None

    def bounded_float(key: str, low: float, high: float) -> float | None:
        value = payload.get(key)
        if value is None or isinstance(value, bool):
            return None
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return None
        return parsed if math.isfinite(parsed) and low <= parsed <= high else None

    def bounded_int(key: str, low: int, high: int) -> int | None:
        value = bounded_float(key, float(low), float(high))
        if value is None or not value.is_integer():
            return None
        return int(value)

    return (
        bounded_float("absorption_speed", 0.0, 1.0),
        bounded_int("absorption_peak_minutes", 5, 720),
        bounded_int("absorption_duration_minutes", 15, 1_440),
        bounded_float("absorption_confidence", 0.0, 1.0),
    )


def _analysis_nutrition(
    result_json: str | None,
) -> tuple[float | None, float | None]:
    """Return the immutable analyzed full portion and carbohydrate baseline."""

    if not result_json:
        return None, None
    try:
        payload = json.loads(result_json)
    except (TypeError, json.JSONDecodeError):
        return None, None
    if not isinstance(payload, dict):
        return None, None

    def bounded_number(key: str, maximum: float) -> float | None:
        value = payload.get(key)
        if value is None or isinstance(value, bool):
            return None
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return None
        if not math.isfinite(parsed) or parsed < 0 or parsed > maximum:
            return None
        return parsed

    return (
        bounded_number("total_portion_g", 10_000.0),
        bounded_number("estimated_carbs_g", 1_000.0),
    )


def _event_response(
    record: IntakeEventRecord,
    analysis_result_json: str | None = None,
) -> IntakeEvent:
    (
        absorption_speed,
        absorption_peak_minutes,
        absorption_duration_minutes,
        absorption_confidence,
    ) = _analysis_absorption(analysis_result_json)
    analysis_portion_g, analysis_carbs_g = _analysis_nutrition(
        analysis_result_json
    )
    original_portion_g = record.original_portion_g
    original_carbs_g = record.original_carbs_g
    if original_portion_g is None:
        original_portion_g = analysis_portion_g
    if original_carbs_g is None:
        original_carbs_g = analysis_carbs_g
    portion_g = record.portion_g
    if portion_g is None and original_portion_g is not None:
        # Existing confirmed meals predate the consumed-portion column. Treat
        # them as fully eaten until the user explicitly corrects the record.
        portion_g = original_portion_g
    return IntakeEvent(
        id=UUID(record.id),
        client_event_id=UUID(record.client_event_id),
        occurred_at_ms=record.occurred_at_ms,
        meal_text=record.meal_text,
        carbs_g=record.carbs_g,
        portion_g=portion_g,
        original_portion_g=original_portion_g,
        original_carbs_g=original_carbs_g,
        carbs_source=record.carbs_source,
        insulin_units=record.insulin_units,
        insulin_type=record.insulin_type,
        insulin_name=record.insulin_name,
        analysis_id=UUID(record.analysis_id) if record.analysis_id else None,
        ai_confidence=_analysis_confidence(analysis_result_json),
        absorption_speed=absorption_speed,
        absorption_peak_minutes=absorption_peak_minutes,
        absorption_duration_minutes=absorption_duration_minutes,
        absorption_confidence=absorption_confidence,
        created_at_ms=record.created_at_ms,
        updated_at_ms=record.updated_at_ms,
        deleted_at_ms=record.deleted_at_ms,
        deleted=record.deleted_at_ms is not None,
        sync_version=record.sync_version,
    )


def _soft_delete_intake_record(
    session: Session,
    event_id: str,
    *,
    now_ms: int | None = None,
) -> tuple[IntakeEventRecord | None, bool]:
    """Atomically create exactly one tombstone revision.

    Starting with the conditional UPDATE (rather than a SELECT) lets SQLite serialize
    concurrent writers before either request takes a stale read snapshot. The loser then
    observes the committed tombstone and returns it as an idempotent success.
    """

    now = _now_ms() if now_ms is None else now_ms
    claimed_id = session.scalar(
        update(IntakeEventRecord)
        .where(
            IntakeEventRecord.id == event_id,
            IntakeEventRecord.deleted_at_ms.is_(None),
        )
        .values(deleted_at_ms=now, updated_at_ms=now)
        .returning(IntakeEventRecord.id)
        .execution_options(synchronize_session=False)
    )
    if claimed_id is None:
        # End the failed CAS transaction before re-reading so a concurrent winner's
        # commit is visible on SQLite/WAL.
        session.rollback()
        return session.get(IntakeEventRecord, event_id), False

    record = session.get(IntakeEventRecord, event_id)
    if record is None:  # Defensive: the claimed row cannot disappear with FK-safe writes.
        session.rollback()
        raise RuntimeError("claimed intake event disappeared during deletion")
    change = SyncChangeRecord(
        event_id=record.id,
        operation="delete",
        changed_at_ms=now,
    )
    session.add(change)
    session.flush()
    record.sync_version = change.id

    session.commit()
    return record, True


def _store_intake(
    payload: IntakeCreate,
    session: Session,
    originating_chat_session_id: str | None = None,
) -> IntakeEvent:
    reserved_chat = session.scalar(
        select(MealChatSessionRecord).where(
            MealChatSessionRecord.client_event_id == str(payload.client_event_id)
        )
    )
    if reserved_chat is not None and reserved_chat.id != originating_chat_session_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="client_event_id is reserved by a meal-chat session",
        )
    digest = _payload_hash(payload)
    existing = session.scalar(
        select(IntakeEventRecord).where(
            IntakeEventRecord.client_event_id == str(payload.client_event_id)
        )
    )
    if existing is not None:
        if existing.payload_hash != digest:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="client_event_id is already used for different data",
            )
        existing_analysis = (
            session.get(AnalysisRecord, existing.analysis_id)
            if existing.analysis_id
            else None
        )
        return _event_response(
            existing,
            existing_analysis.result_json if existing_analysis else None,
        )

    analysis = None
    if payload.analysis_id is not None:
        analysis = session.get(AnalysisRecord, str(payload.analysis_id))
        if analysis is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="analysis_id does not exist",
            )

    analyzed_portion_g, analyzed_carbs_g = _analysis_nutrition(
        analysis.result_json if analysis is not None else None
    )
    original_portion_g = payload.portion_g or analyzed_portion_g
    original_carbs_g = (
        payload.carbs_g if payload.portion_g is not None else analyzed_carbs_g
    )

    now = _now_ms()
    record = IntakeEventRecord(
        id=str(uuid4()),
        client_event_id=str(payload.client_event_id),
        occurred_at_ms=payload.occurred_at_ms,
        meal_text=payload.meal_text,
        carbs_g=payload.carbs_g,
        portion_g=original_portion_g,
        original_portion_g=original_portion_g,
        original_carbs_g=original_carbs_g,
        carbs_source=payload.carbs_source,
        insulin_units=payload.insulin_units,
        insulin_type=payload.insulin_type,
        insulin_name=payload.insulin_name,
        analysis_id=str(payload.analysis_id) if payload.analysis_id else None,
        payload_hash=digest,
        created_at_ms=now,
        updated_at_ms=now,
        deleted_at_ms=None,
        sync_version=0,
    )
    try:
        session.add(record)
        session.flush()
        change = SyncChangeRecord(
            event_id=record.id,
            operation="upsert",
            changed_at_ms=now,
        )
        session.add(change)
        session.flush()
        record.sync_version = change.id
        session.commit()
    except IntegrityError as error:
        session.rollback()
        raced = session.scalar(
            select(IntakeEventRecord).where(
                IntakeEventRecord.client_event_id == str(payload.client_event_id)
            )
        )
        if raced is not None and raced.payload_hash == digest:
            raced_analysis = (
                session.get(AnalysisRecord, raced.analysis_id)
                if raced.analysis_id
                else None
            )
            return _event_response(
                raced,
                raced_analysis.result_json if raced_analysis is not None else None,
            )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="intake could not be stored because its identity conflicts",
        ) from error
    return _event_response(
        record,
        analysis.result_json if analysis is not None else None,
    )


def _chat_message_response(record: MealChatMessageRecord) -> MealChatMessage:
    return MealChatMessage(
        id=UUID(record.id),
        role=record.role,
        text=record.text,
        photo_count=record.photo_count,
        had_audio=bool(record.had_audio),
        created_at_ms=record.created_at_ms,
    )


def _proposal_from_analysis(
    analysis: AnalysisRecord | None,
) -> MealChatProposal | None:
    if analysis is None:
        return None
    try:
        return MealChatProposal.model_validate_json(analysis.result_json)
    except ValidationError:
        return None


def _chat_session_response(
    record: MealChatSessionRecord,
    session: Session,
) -> MealChatSessionResponse:
    messages = list(
        session.scalars(
            select(MealChatMessageRecord)
            .where(MealChatMessageRecord.session_id == record.id)
            .order_by(MealChatMessageRecord.sequence.asc())
        )
    )
    analysis = (
        session.get(AnalysisRecord, record.latest_analysis_id)
        if record.latest_analysis_id
        else None
    )
    proposal = _proposal_from_analysis(analysis)
    return MealChatSessionResponse(
        id=UUID(record.id),
        client_event_id=UUID(record.client_event_id),
        occurred_at_ms=record.occurred_at_ms,
        status=record.status,
        created_at_ms=record.created_at_ms,
        updated_at_ms=record.updated_at_ms,
        messages=[_chat_message_response(message) for message in messages],
        proposal=proposal,
        ready_to_confirm=bool(record.ready_to_confirm) and proposal is not None,
        confirmed_intake_id=(
            UUID(record.confirmed_intake_id) if record.confirmed_intake_id else None
        ),
    )


def get_session(request: Request):
    yield from request.app.state.database.sessions()


SessionDependency = Annotated[Session, Depends(get_session)]


def create_app(
    settings: Settings | None = None,
    analyzer: MealAnalyzer | None = None,
    chat_analyzer: MealChatAnalyzer | None = None,
    transcriber: AudioTranscriber | None = None,
    forecast_service: ForecastService | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    database = Database(settings.database_path)
    analyzer = analyzer or OpenRouterMealAnalyzer(settings)
    chat_analyzer = chat_analyzer or OpenRouterMealChatAnalyzer(settings)
    transcriber = transcriber or analyzer
    forecast_service = forecast_service or ForecastService()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        database.create_all()
        yield
        await application.state.analyzer.aclose()
        if application.state.chat_analyzer is not application.state.analyzer:
            await application.state.chat_analyzer.aclose()
        if (
            application.state.transcriber is not application.state.analyzer
            and application.state.transcriber is not application.state.chat_analyzer
        ):
            await application.state.transcriber.aclose()
        database.dispose()

    application = FastAPI(
        title="Juggluco Intake Backend",
        version="0.1.0",
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
        lifespan=lifespan,
    )
    application.state.settings = settings
    application.state.database = database
    application.state.analyzer = analyzer
    application.state.chat_analyzer = chat_analyzer
    application.state.transcriber = transcriber
    application.state.forecast_service = forecast_service
    application.add_middleware(
        TrustedHostMiddleware,
        allowed_hosts=list(settings.allowed_hosts),
    )

    @application.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith("/v1/viewer/"):
            # Viewer responses contain health data and may traverse a remote
            # reverse proxy.  Explicitly forbid both shared and private caches
            # and keep credential-dependent representations separated.
            response.headers["Cache-Control"] = "no-store, private"
            response.headers["Vary"] = "Authorization"
        else:
            response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Request-ID"] = request.headers.get(
            "X-Request-ID", str(uuid4())
        )[:128]
        return response

    @application.get("/v1/health", response_model=HealthResponse)
    def health(request: Request) -> HealthResponse:
        current_settings: Settings = request.app.state.settings
        database_state = "ok" if request.app.state.database.ping() else "error"
        ready = database_state == "ok" and current_settings.auth_configured
        return HealthResponse(
            status="ok" if ready else "degraded",
            api_version="v1",
            database=database_state,
            auth_configured=current_settings.auth_configured,
            viewer_auth_configured=current_settings.viewer_auth_configured,
            ai_configured=current_settings.openrouter_configured,
        )

    router = APIRouter(
        prefix="/v1",
        dependencies=[Depends(require_api_token)],
    )

    @router.post("/glucose/readings", response_model=GlucoseReadingsResponse)
    def ingest_glucose_readings(
        payload: GlucoseReadingsCreate,
        request: Request,
        session: SessionDependency,
    ) -> GlucoseReadingsResponse:
        try:
            result = request.app.state.forecast_service.ingest(session, payload)
        except ValueError as error:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT, detail=str(error)
            ) from error
        return result

    @router.get("/forecast/current", response_model=ForecastCurrentResponse)
    def current_forecast(
        request: Request,
        session: SessionDependency,
    ) -> ForecastCurrentResponse:
        return request.app.state.forecast_service.current(session)

    @router.get("/forecast/status", response_model=ForecastStatusResponse)
    def forecast_status(
        request: Request,
        session: SessionDependency,
    ) -> ForecastStatusResponse:
        return request.app.state.forecast_service.status(session)

    @router.post("/transcriptions", response_model=TranscriptionResponse)
    async def transcribe_audio(
        request: Request,
        audio: Annotated[UploadFile, File()],
    ) -> TranscriptionResponse:
        current_settings: Settings = request.app.state.settings
        try:
            prepared_audio = await prepare_audio(
                audio, current_settings.max_audio_bytes
            )
            text = await request.app.state.transcriber.transcribe(prepared_audio)
        except MediaValidationError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error
        except AnalysisError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error
        finally:
            await audio.close()
        return TranscriptionResponse(text=text)

    @router.post(
        "/analyze",
        response_model=AnalysisResponse,
        response_model_exclude_none=True,
    )
    async def analyze_meal(
        request: Request,
        session: SessionDependency,
        meal_text: Annotated[str, Form(max_length=4_000)] = "",
        photos: Annotated[list[UploadFile] | None, File()] = None,
        audio: Annotated[UploadFile | None, File()] = None,
    ) -> AnalysisResponse:
        meal_text = meal_text.strip()
        photo_uploads = photos or []
        if len(photo_uploads) > 2:
            for upload in photo_uploads:
                await upload.close()
            if audio is not None:
                await audio.close()
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="at most two photos are allowed",
            )
        if not meal_text and not photo_uploads and audio is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="meal_text, at least one photo, or audio is required",
            )

        current_settings: Settings = request.app.state.settings
        try:
            prepared_images = [
                await prepare_image(upload, current_settings.max_image_bytes)
                for upload in photo_uploads
            ]
            prepared_audio = (
                await prepare_audio(audio, current_settings.max_audio_bytes)
                if audio is not None
                else None
            )
            result, transcription = await request.app.state.analyzer.analyze(
                meal_text, prepared_images, prepared_audio
            )
        except MediaValidationError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error
        except AnalysisError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error

        analysis_id = uuid4()
        session.add(
            AnalysisRecord(
                id=str(analysis_id),
                created_at_ms=_now_ms(),
                model=request.app.state.analyzer.model_name,
                manual_text=meal_text or None,
                transcription=transcription,
                result_json=result.model_dump_json(),
            )
        )
        session.commit()
        return AnalysisResponse(
            analysis_id=analysis_id,
            transcription=transcription,
            **result.model_dump(),
        )

    @router.post(
        "/meal-chat/sessions",
        response_model=MealChatSessionResponse,
    )
    def create_meal_chat_session(
        payload: MealChatSessionCreate,
        session: SessionDependency,
    ) -> MealChatSessionResponse:
        client_event_id = str(payload.client_event_id)
        existing = session.scalar(
            select(MealChatSessionRecord).where(
                MealChatSessionRecord.client_event_id == client_event_id
            )
        )
        if existing is not None:
            if existing.occurred_at_ms != payload.occurred_at_ms:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="client_event_id is already used with a different occurred_at_ms",
                )
            return _chat_session_response(existing, session)

        existing_intake = session.scalar(
            select(IntakeEventRecord.id).where(
                IntakeEventRecord.client_event_id == client_event_id
            )
        )
        if existing_intake is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="client_event_id is already used by an intake",
            )

        now = _now_ms()
        record = MealChatSessionRecord(
            id=str(uuid4()),
            client_event_id=client_event_id,
            occurred_at_ms=payload.occurred_at_ms,
            status="active",
            latest_analysis_id=None,
            ready_to_confirm=0,
            confirmed_intake_id=None,
            created_at_ms=now,
            updated_at_ms=now,
        )
        try:
            session.add(record)
            session.commit()
        except IntegrityError as error:
            session.rollback()
            raced = session.scalar(
                select(MealChatSessionRecord).where(
                    MealChatSessionRecord.client_event_id == client_event_id
                )
            )
            if raced is not None and raced.occurred_at_ms == payload.occurred_at_ms:
                return _chat_session_response(raced, session)
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="meal-chat session identity conflicts",
            ) from error
        return _chat_session_response(record, session)

    @router.get(
        "/meal-chat/sessions/{chat_session_id}",
        response_model=MealChatSessionResponse,
    )
    def get_meal_chat_session(
        chat_session_id: UUID,
        session: SessionDependency,
    ) -> MealChatSessionResponse:
        record = session.get(MealChatSessionRecord, str(chat_session_id))
        if record is None:
            raise HTTPException(status_code=404, detail="meal-chat session not found")
        return _chat_session_response(record, session)

    @router.put(
        "/meal-chat/sessions/{chat_session_id}/time",
        response_model=MealChatSessionResponse,
    )
    def update_meal_chat_session_time(
        chat_session_id: UUID,
        payload: MealChatSessionTimeUpdate,
        session: SessionDependency,
    ) -> MealChatSessionResponse:
        """Move an active draft while preserving its messages and latest proposal.

        The dedicated ``PUT /time`` route is intentionally compatible with Android's
        platform ``HttpURLConnection``.  Once confirmed, the intake timestamp is
        immutable through this draft endpoint.
        """

        _begin_immediate(session, "meal-chat session time update")
        record_id = str(chat_session_id)
        try:
            changed = session.execute(
                update(MealChatSessionRecord)
                .where(
                    MealChatSessionRecord.id == record_id,
                    MealChatSessionRecord.status == "active",
                )
                .values(
                    occurred_at_ms=payload.occurred_at_ms,
                    updated_at_ms=_now_ms(),
                )
                .execution_options(synchronize_session=False)
            )
            if changed.rowcount == 1:
                session.commit()
            else:
                session.rollback()
        except SQLAlchemyError as error:
            session.rollback()
            logger.warning("meal-chat time update failed", exc_info=True)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=(
                    "meal-chat session time update is temporarily unavailable; "
                    "fetch the session before retrying"
                ),
            ) from error

        record = session.get(MealChatSessionRecord, record_id)
        if record is None:
            raise HTTPException(status_code=404, detail="meal-chat session not found")
        if record.status != "active":
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="confirmed meal-chat session time cannot be changed",
            )
        if changed.rowcount != 1:
            # Defensive fallback for databases/drivers that report no matched row even
            # though the active record exists. The GET response is the source of truth.
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="meal-chat session time was not changed; fetch and retry",
            )
        return _chat_session_response(record, session)

    @router.post(
        "/meal-chat/sessions/{chat_session_id}/messages",
        response_model=MealChatTurnResponse,
    )
    async def send_meal_chat_message(
        chat_session_id: UUID,
        request: Request,
        session: SessionDependency,
        text: Annotated[str, Form(max_length=4_000)] = "",
        photos: Annotated[list[UploadFile] | None, File()] = None,
        audio: Annotated[UploadFile | None, File()] = None,
    ) -> MealChatTurnResponse:
        text = text.strip()
        photo_uploads = photos or []
        current_settings: Settings = request.app.state.settings

        async def close_uploads() -> None:
            for upload in photo_uploads:
                await upload.close()
            if audio is not None:
                await audio.close()

        if len(photo_uploads) > current_settings.meal_chat_max_photos:
            await close_uploads()
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "too many photos: the configured limit is "
                    f"{current_settings.meal_chat_max_photos} photos"
                ),
            )
        if not text and not photo_uploads and audio is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="text, at least one photo, or audio is required",
            )

        chat_record = session.get(MealChatSessionRecord, str(chat_session_id))
        if chat_record is None:
            await close_uploads()
            raise HTTPException(status_code=404, detail="meal-chat session not found")
        if chat_record.status != "active":
            await close_uploads()
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="confirmed meal-chat sessions cannot accept new messages",
            )

        last_sequence = session.scalar(
            select(func.max(MealChatMessageRecord.sequence)).where(
                MealChatMessageRecord.session_id == chat_record.id
            )
        ) or 0
        history_desc = list(
            session.scalars(
                select(MealChatMessageRecord)
                .where(MealChatMessageRecord.session_id == chat_record.id)
                .order_by(MealChatMessageRecord.sequence.desc())
                .limit(current_settings.meal_chat_max_history_messages)
            )
        )
        history_rows = list(reversed(history_desc))
        history_analysis_ids = {
            row.analysis_id for row in history_rows if row.analysis_id is not None
        }
        history_analysis_json: dict[str, str] = {}
        if history_analysis_ids:
            history_analysis_json = dict(
                session.execute(
                    select(AnalysisRecord.id, AnalysisRecord.result_json).where(
                        AnalysisRecord.id.in_(history_analysis_ids)
                    )
                ).all()
            )
        history = [
            MealChatHistoryEntry(
                role=row.role,
                text=row.text,
                proposal_json=(
                    history_analysis_json.get(row.analysis_id)
                    if row.analysis_id is not None
                    else None
                ),
            )
            for row in history_rows
        ]
        # Do not hold a SQLite read transaction while waiting on the model provider.
        session.rollback()

        try:
            prepared_images = []
            aggregate_source_bytes = 0
            aggregate_prepared_bytes = 0
            for upload in photo_uploads:
                prepared = await prepare_image(
                    upload, current_settings.max_image_bytes
                )
                aggregate_source_bytes += prepared.source_bytes
                aggregate_prepared_bytes += len(prepared.data)
                if (
                    aggregate_source_bytes
                    > current_settings.meal_chat_max_aggregate_image_bytes
                    or aggregate_prepared_bytes
                    > current_settings.meal_chat_max_aggregate_image_bytes
                ):
                    raise MediaValidationError(
                        "photos exceed the configured aggregate limit of "
                        f"{current_settings.meal_chat_max_aggregate_image_bytes} bytes",
                        413,
                    )
                prepared_images.append(prepared)
            prepared_audio = (
                await prepare_audio(audio, current_settings.max_audio_bytes)
                if audio is not None
                else None
            )
            result, transcription = await request.app.state.chat_analyzer.chat(
                history, text, prepared_images, prepared_audio
            )
        except MediaValidationError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error
        except AnalysisError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error
        finally:
            await close_uploads()

        # Re-check after the model call so simultaneous turns cannot reorder history.
        chat_record = session.get(MealChatSessionRecord, str(chat_session_id))
        current_last_sequence = session.scalar(
            select(func.max(MealChatMessageRecord.sequence)).where(
                MealChatMessageRecord.session_id == str(chat_session_id)
            )
        ) or 0
        if chat_record is None:
            raise HTTPException(status_code=404, detail="meal-chat session not found")
        if chat_record.status != "active" or current_last_sequence != last_sequence:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="meal-chat session changed while processing; retry the message",
            )

        user_parts: list[str] = []
        if text:
            user_parts.append(text)
        if transcription:
            user_parts.append(f"Voice transcript:\n{transcription}")
        elif audio is not None:
            user_parts.append("[Voice message attached; transcript unavailable]")
        if photo_uploads:
            user_parts.append(f"[{len(photo_uploads)} photo(s) attached]")
        persisted_user_text = "\n\n".join(user_parts)

        now = _now_ms()
        analysis_id: str | None = None
        if result.proposal is not None:
            analysis_id = str(uuid4())
            session.add(
                AnalysisRecord(
                    id=analysis_id,
                    created_at_ms=now,
                    model=request.app.state.chat_analyzer.model_name,
                    manual_text=text or None,
                    transcription=transcription,
                    result_json=result.proposal.model_dump_json(),
                )
            )
        user_message = MealChatMessageRecord(
            id=str(uuid4()),
            session_id=chat_record.id,
            sequence=last_sequence + 1,
            role="user",
            text=persisted_user_text,
            photo_count=len(photo_uploads),
            had_audio=1 if audio is not None else 0,
            analysis_id=None,
            created_at_ms=now,
        )
        assistant_message = MealChatMessageRecord(
            id=str(uuid4()),
            session_id=chat_record.id,
            sequence=last_sequence + 2,
            role="assistant",
            text=result.assistant_message,
            photo_count=0,
            had_audio=0,
            analysis_id=analysis_id,
            created_at_ms=now,
        )
        session.add_all([user_message, assistant_message])
        # An accepted turn always replaces the session's current draft. In
        # particular, a correction after a ready proposal must not leave the old
        # analysis confirmable when the replacement is incomplete or not ready.
        chat_record.latest_analysis_id = analysis_id
        chat_record.ready_to_confirm = (
            1 if result.ready_to_confirm and analysis_id is not None else 0
        )
        chat_record.updated_at_ms = now
        try:
            session.commit()
        except IntegrityError as error:
            session.rollback()
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="meal-chat session changed while processing; retry the message",
            ) from error
        return MealChatTurnResponse(
            session_id=chat_session_id,
            assistant_message=_chat_message_response(assistant_message),
            proposal=result.proposal,
            ready_to_confirm=bool(chat_record.ready_to_confirm),
        )

    @router.post(
        "/meal-chat/sessions/{chat_session_id}/confirm",
        response_model=IntakeEvent,
    )
    def confirm_meal_chat_session(
        chat_session_id: UUID,
        session: SessionDependency,
    ) -> IntakeEvent:
        _begin_immediate(session, "meal-chat confirmation")
        chat_record = session.get(MealChatSessionRecord, str(chat_session_id))
        if chat_record is None:
            raise HTTPException(status_code=404, detail="meal-chat session not found")
        if chat_record.status == "confirmed":
            if not chat_record.confirmed_intake_id:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="confirmed meal-chat session has no intake record",
                )
            intake = session.get(IntakeEventRecord, chat_record.confirmed_intake_id)
            if intake is None:
                raise HTTPException(status_code=404, detail="confirmed intake not found")
            analysis = (
                session.get(AnalysisRecord, intake.analysis_id)
                if intake.analysis_id
                else None
            )
            return _event_response(
                intake,
                analysis.result_json if analysis is not None else None,
            )

        analysis = (
            session.get(AnalysisRecord, chat_record.latest_analysis_id)
            if chat_record.latest_analysis_id
            else None
        )
        proposal = _proposal_from_analysis(analysis)
        if not chat_record.ready_to_confirm or analysis is None or proposal is None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="meal-chat session is not ready to confirm",
            )

        payload = IntakeCreate(
            client_event_id=UUID(chat_record.client_event_id),
            occurred_at_ms=chat_record.occurred_at_ms,
            meal_text=proposal.meal_description or proposal.meal_name,
            carbs_g=proposal.estimated_carbs_g,
            carbs_source="ai_estimate",
            insulin_units=None,
            insulin_type=None,
            insulin_name=None,
            analysis_id=UUID(analysis.id),
        )
        event = _store_intake(
            payload,
            session,
            originating_chat_session_id=chat_record.id,
        )
        chat_record.confirmed_intake_id = str(event.id)
        chat_record.status = "confirmed"
        chat_record.ready_to_confirm = 0
        chat_record.updated_at_ms = _now_ms()
        session.commit()
        return event

    @router.post("/insulin-events", response_model=IntakeEvent)
    def create_insulin_event(
        payload: InsulinEventCreate,
        session: SessionDependency,
    ) -> IntakeEvent:
        insulin_type = "rapid" if payload.insulin_name == "NovoRapid" else "long"
        return _store_intake(
            IntakeCreate(
                client_event_id=payload.client_event_id,
                occurred_at_ms=payload.occurred_at_ms,
                meal_text=None,
                carbs_g=None,
                portion_g=None,
                carbs_source=None,
                insulin_units=payload.insulin_units,
                insulin_type=insulin_type,
                insulin_name=payload.insulin_name,
                analysis_id=None,
            ),
            session,
        )

    @router.post("/meal-events", response_model=IntakeEvent)
    def create_manual_meal_event(
        payload: ManualMealEventCreate,
        session: SessionDependency,
    ) -> IntakeEvent:
        return _store_intake(
            IntakeCreate(
                client_event_id=payload.client_event_id,
                occurred_at_ms=payload.occurred_at_ms,
                meal_text=payload.meal_text,
                carbs_g=payload.carbs_g,
                portion_g=payload.portion_g,
                carbs_source="manual",
                insulin_units=None,
                insulin_type=None,
                insulin_name=None,
                analysis_id=None,
            ),
            session,
        )

    @router.get("/intakes", response_model=IntakeListResponse)
    def list_intakes(
        session: SessionDependency,
        after_sync_version: Annotated[int | None, Query(ge=0)] = None,
        from_ms: Annotated[int | None, Query(ge=0)] = None,
        to_ms: Annotated[int | None, Query(ge=0)] = None,
        include_deleted: bool = False,
        limit: Annotated[int, Query(ge=1, le=500)] = 200,
    ) -> IntakeListResponse:
        if from_ms is not None and to_ms is not None and from_ms > to_ms:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="from_ms must be less than or equal to to_ms",
            )
        statement = select(IntakeEventRecord)
        if after_sync_version is not None:
            # Delta synchronization must include tombstones so a client can remove
            # an event that was deleted on another run/device.
            statement = statement.where(
                IntakeEventRecord.sync_version > after_sync_version
            )
        elif not include_deleted:
            statement = statement.where(IntakeEventRecord.deleted_at_ms.is_(None))
        if from_ms is not None:
            statement = statement.where(IntakeEventRecord.occurred_at_ms >= from_ms)
        if to_ms is not None:
            statement = statement.where(IntakeEventRecord.occurred_at_ms <= to_ms)
        statement = statement.order_by(IntakeEventRecord.sync_version.asc()).limit(limit)
        records = list(session.scalars(statement))
        analysis_ids = {
            record.analysis_id for record in records if record.analysis_id is not None
        }
        analysis_json_by_id: dict[str, str] = {}
        if analysis_ids:
            analysis_json_by_id = dict(
                session.execute(
                    select(AnalysisRecord.id, AnalysisRecord.result_json).where(
                        AnalysisRecord.id.in_(analysis_ids)
                    )
                ).all()
            )
        next_version = after_sync_version or 0
        if records:
            next_version = max(record.sync_version for record in records)
        return IntakeListResponse(
            items=[
                _event_response(
                    record,
                    analysis_json_by_id.get(record.analysis_id)
                    if record.analysis_id
                    else None,
                )
                for record in records
            ],
            next_sync_version=next_version,
        )

    @router.get("/intakes/{event_id}", response_model=IntakeEvent)
    def get_intake(event_id: UUID, session: SessionDependency) -> IntakeEvent:
        record = session.get(IntakeEventRecord, str(event_id))
        if record is None:
            raise HTTPException(status_code=404, detail="intake not found")
        analysis = (
            session.get(AnalysisRecord, record.analysis_id)
            if record.analysis_id
            else None
        )
        return _event_response(
            record,
            analysis.result_json if analysis is not None else None,
        )

    @router.put(
        "/intakes/{event_id}/meal-portion",
        response_model=IntakeEvent,
    )
    def update_meal_portion(
        event_id: UUID,
        payload: MealPortionUpdate,
        request: Request,
        session: SessionDependency,
    ) -> IntakeEvent:
        _begin_immediate(session, "meal portion update")
        record = session.get(IntakeEventRecord, str(event_id))
        if record is None:
            session.rollback()
            raise HTTPException(status_code=404, detail="intake event not found")
        if record.deleted_at_ms is not None:
            session.rollback()
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="deleted intake event cannot be edited",
            )
        if record.meal_text is None and record.carbs_g is None:
            session.rollback()
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="only meal events have an editable portion",
            )
        analysis = (
            session.get(AnalysisRecord, record.analysis_id)
            if record.analysis_id
            else None
        )
        analysis_json = analysis.result_json if analysis is not None else None
        original_portion_g = record.original_portion_g
        original_carbs_g = record.original_carbs_g
        if original_portion_g is None or original_carbs_g is None:
            original_portion_g, original_carbs_g = _analysis_nutrition(analysis_json)
        if (
            original_portion_g is None
            or original_portion_g <= 0
            or original_carbs_g is None
        ):
            session.rollback()
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="meal has no analyzed portion baseline",
            )
        if payload.portion_g > original_portion_g + 0.01:
            session.rollback()
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="consumed portion cannot exceed the recorded full portion",
            )

        consumed = min(payload.portion_g, original_portion_g)
        recalculated_carbs = round(
            original_carbs_g * consumed / original_portion_g,
            2,
        )
        effective_portion = (
            record.portion_g
            if record.portion_g is not None
            else original_portion_g
        )
        if (
            math.isclose(effective_portion, consumed, abs_tol=0.005)
            and record.carbs_g is not None
            and math.isclose(record.carbs_g, recalculated_carbs, abs_tol=0.005)
        ):
            session.rollback()
            return _event_response(record, analysis_json)

        now = _now_ms()
        record.portion_g = consumed
        record.carbs_g = recalculated_carbs
        record.updated_at_ms = now
        change = SyncChangeRecord(
            event_id=record.id,
            operation="upsert",
            changed_at_ms=now,
        )
        session.add(change)
        session.flush()
        record.sync_version = change.id
        session.commit()
        try:
            request.app.state.forecast_service.current(session)
        except Exception:
            session.rollback()
            logger.exception(
                "could not regenerate current forecast after meal portion update"
            )
        return _event_response(record, analysis_json)

    @router.delete("/intakes/{event_id}", response_model=IntakeEvent)
    def delete_intake(
        event_id: UUID,
        request: Request,
        session: SessionDependency,
    ) -> IntakeEvent:
        record, deleted_now = _soft_delete_intake_record(session, str(event_id))
        if record is None:
            raise HTTPException(status_code=404, detail="intake event not found")
        analysis = (
            session.get(AnalysisRecord, record.analysis_id)
            if record.analysis_id
            else None
        )
        if deleted_now:
            try:
                # Runs stay immutable. The event revision included in the causal hash
                # forces a fresh current snapshot instead of reviving a pre-event run.
                request.app.state.forecast_service.current(session)
            except Exception:
                session.rollback()
                logger.exception(
                    "could not regenerate current forecast after intake deletion"
                )
        return _event_response(
            record,
            analysis.result_json if analysis is not None else None,
        )

    application.include_router(router)
    application.include_router(create_viewer_router(get_session, _event_response))
    return application


app = create_app()
