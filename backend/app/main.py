from __future__ import annotations

import hashlib
import ipaddress
import json
import logging
import math
import re
import secrets
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Annotated
from uuid import UUID, uuid4, uuid5

from fastapi import (
    APIRouter,
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
    status,
)
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError, OperationalError, SQLAlchemyError
from sqlalchemy.orm import Session

from .config import Settings, normalize_audio_language
from .database import Database
from .forecast import ForecastService
from .forecast_release import register_forecast_runtime_release
from .intake_chat import (
    ExplicitInsulinCommand,
    ExplicitInsulinParse,
    IncompleteInsulinProduct,
    has_explicit_meal_consumption,
    has_ambiguous_meal_time_reference,
    has_contextual_insulin_time_correction_cue,
    has_safe_photo_meal_context,
    has_safe_meal_consumption_candidate,
    has_semantic_meal_consumption_cue,
    is_safe_semantic_insulin_text,
    is_safe_semantic_insulin_write,
    is_safe_semantic_meal_write,
    is_explicit_delete_current,
    is_explicit_meal_correction,
    is_explicit_additional_meal_report,
    is_safe_terse_meal_revision_text,
    is_explicit_new_insulin_report,
    is_explicit_pending_cancel,
    is_explicit_revision_request,
    is_explicit_undo,
    parse_contextual_insulin_dose_correction,
    parse_contextual_insulin_time_correction,
    parse_contextual_new_insulin_dose,
    parse_contextual_meal_quantity_correction,
    parse_exact_insulin_dose,
    parse_explicit_insulin,
    parse_insulin_product_missing_dose,
    parse_relative_meal_time_offset_ms,
    parse_terse_meal_portion_replacement,
    parse_terse_insulin_dose_replacement,
    semantic_dose_evidence_matches,
    semantic_dose_context_is_safe,
    semantic_dose_values_are_consistent,
    semantic_action_evidence_matches_anchored_clause,
    semantic_meal_residual,
    semantic_product_dose_evidence_is_bound,
    semantic_product_evidence_span,
    uses_cyrillic,
)
from .media import MediaValidationError, prepare_audio, prepare_image
from .models import (
    AnalysisRecord,
    IntakeChatActionEventRecord,
    IntakeChatActionRecord,
    IntakeChatSessionRecord,
    IntakeChatTurnReservationRecord,
    IntakeChatTurnRecord,
    IntakeEventRecord,
    MealChatMessageRecord,
    MealChatSessionRecord,
    SyncChangeRecord,
)
from .openrouter import (
    AnalysisError,
    AudioTranscriber,
    IntakeChatAnalyzer,
    IntakeChatHistoryEntry,
    IntakeChatMealRevisionContext,
    MealAnalyzer,
    MealChatAnalyzer,
    MealChatHistoryEntry,
    OpenRouterIntakeChatAnalyzer,
    OpenRouterMealAnalyzer,
    OpenRouterMealChatAnalyzer,
)
from .pwa import mount_viewer_pwa
from .realtime import GlucoseUpdateHub
from .request_limits import ViewerSessionBodyLimitMiddleware
from .schemas import (
    AnalysisResponse,
    ForecastCurrentResponse,
    ForecastStatusResponse,
    GlucoseReadingsCreate,
    GlucoseReadingsResponse,
    HealthResponse,
    InsulinEventCreate,
    IntakeChatControlResult,
    IntakeChatInsulinSemanticResult,
    IntakeChatModelResult,
    IntakeChatSessionCreate,
    IntakeChatSessionResponse,
    IntakeChatTurnResponse,
    IntakeChatTurnReservationSnapshot,
    IntakeChatUndoResponse,
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
from .viewer import create_viewer_router, create_viewer_session_router


logger = logging.getLogger(__name__)


def _is_secure_browser_request(request: Request) -> bool:
    if request.url.scheme == "https":
        return True
    settings: Settings = request.app.state.settings
    forwarded_proto = request.headers.get("x-forwarded-proto", "")
    peer: ipaddress.IPv4Address | ipaddress.IPv6Address | None = None
    if request.client is not None:
        try:
            peer = ipaddress.ip_address(request.client.host)
        except ValueError:
            pass
    if forwarded_proto == "https" and peer is not None:
        try:
            if any(
                peer in ipaddress.ip_network(configured)
                for configured in settings.viewer_trusted_proxy_cidrs
            ):
                return True
        except ValueError:
            pass
    # The Host header is controlled by the caller and cannot prove that an HTTP
    # request stayed on this machine.  Permit the development exception only
    # for a connection whose actual network peer is loopback.
    return bool(peer is not None and peer.is_loopback)


_PENDING_INSULIN_FOLLOWUP_WINDOW_MS = 2 * 60 * 1_000
_PENDING_REVISION_WINDOW_MS = 2 * 60 * 1_000
_IMPLICIT_INSULIN_CONTEXT_WINDOW_MS = 2 * 60 * 1_000
_IMPLICIT_MEAL_CONTEXT_WINDOW_MS = 2 * 60 * 1_000
_SEMANTIC_INSULIN_CREATE_MIN_CONFIDENCE = 0.80
_SEMANTIC_INSULIN_REPLACE_MIN_CONFIDENCE = 0.90
_SEMANTIC_INSULIN_DELETE_MIN_CONFIDENCE = 0.95


@dataclass(frozen=True, slots=True)
class _RevisionPendingContext:
    pending_action_id: str
    target_action_id: str | None
    cyrillic: bool
    expired: bool = False
    single_insulin_name: str | None = None
    single_insulin_type: str | None = None


@dataclass(frozen=True, slots=True)
class _ImplicitInsulinContext:
    """Frozen proof for interpreting the very next product-less insulin turn."""

    target_action_id: str
    target_event_id: str
    target_updated_at_ms: int
    insulin_name: str
    insulin_type: str
    insulin_units: float
    cyrillic: bool


@dataclass(frozen=True, slots=True)
class _ImplicitMealContext:
    """Frozen proof for interpreting the next terse meal refinement."""

    target_action_id: str
    target_event_id: str
    target_updated_at_ms: int
    meal_text: str | None
    portion_g: float | None
    carbs_g: float | None
    cyrillic: bool


@dataclass(frozen=True, slots=True)
class _FrozenSemanticDeleteContext:
    """Exact action/event snapshot selected before semantic classification."""

    target_action_id: str
    target_action_sequence: int
    target_event_id: str
    target_updated_at_ms: int
    insulin_name: str
    insulin_type: str
    insulin_units: float


@dataclass(frozen=True, slots=True)
class _FrozenVisibleEventContext:
    event_id: str
    updated_at_ms: int


@dataclass(frozen=True, slots=True)
class _FrozenVisibleActionContext:
    target_action_id: str
    target_action_sequence: int
    events: tuple[_FrozenVisibleEventContext, ...]


@dataclass(frozen=True, slots=True)
class _FrozenReplacementEventContext:
    kind: str
    event_id: str
    updated_at_ms: int


@dataclass(frozen=True, slots=True)
class _ReservedIntakeChatTurnContext:
    expected_session_updated_at: int
    expected_last_turn_sequence: int
    context_created_at_ms: int
    pending_insulin: tuple[IncompleteInsulinProduct, bool] | None
    pending_revision: _RevisionPendingContext | None
    implicit_insulin: _ImplicitInsulinContext | None
    implicit_meal: _ImplicitMealContext | None
    semantic_delete: _FrozenSemanticDeleteContext | None
    visible_action: _FrozenVisibleActionContext | None
    replacement_events: tuple[_FrozenReplacementEventContext, ...]


def _revision_question(cyrillic: bool) -> str:
    return (
        "Какими должны быть новые данные?"
        if cyrillic
        else "What should the new entry contain?"
    )


def _semantic_evidence_offsets(
    source: str,
    fragment: str | None,
) -> tuple[int, ...]:
    """Locate every whole occurrence of provider-quoted current-turn evidence."""

    if fragment is None:
        return ()
    normalized_source = " ".join((source or "").strip().split())
    normalized_fragment = " ".join(fragment.strip().split())
    if not normalized_fragment:
        return ()
    matches = list(
        re.finditer(
            r"(?<![\w'’\-])"
            + re.escape(normalized_fragment)
            + r"(?![\w'’\-])",
            normalized_source,
            flags=re.IGNORECASE,
        )
    )
    return tuple(match.start() for match in matches)


def _semantic_evidence_offset(source: str, fragment: str | None) -> int | None:
    """Locate one unique whole provider-quoted fragment in the current turn."""

    offsets = _semantic_evidence_offsets(source, fragment)
    return offsets[0] if len(offsets) == 1 else None


def _semantic_repeated_evidence_spans(
    source: str,
    fragment: str | None,
) -> tuple[tuple[int, int], ...]:
    normalized_fragment = " ".join((fragment or "").strip().split())
    if not normalized_fragment:
        return ()
    return tuple(
        (offset, offset + len(normalized_fragment))
        for offset in _semantic_evidence_offsets(source, fragment)
    )


def _semantic_evidence_span(
    source: str,
    *fragments: str | None,
) -> tuple[int, int] | None:
    """Return the envelope of unique whole evidence fragments."""

    located: list[tuple[int, str]] = []
    for fragment in fragments:
        if fragment is None:
            continue
        offset = _semantic_evidence_offset(source, fragment)
        if offset is None:
            return None
        located.append((offset, " ".join(fragment.strip().split())))
    if not located:
        return None
    return (
        min(offset for offset, _fragment in located),
        max(offset + len(fragment) for offset, fragment in located),
    )


def _semantic_span_envelope(
    *spans: tuple[int, int] | None,
) -> tuple[int, int] | None:
    """Return the smallest envelope containing all available trusted spans."""

    located = [span for span in spans if span is not None]
    if not located:
        return None
    return (
        min(start for start, _end in located),
        max(end for _start, end in located),
    )


def _semantic_dose_is_supported(
    source: str,
    result: IntakeChatInsulinSemanticResult,
    *,
    allow_inflected_ordinal: bool,
) -> bool:
    """Require verbatim dose evidence and deterministic bounds/corroboration."""

    if (
        result.insulin_units is None
        or result.dose_evidence is None
        or not _semantic_evidence_offsets(source, result.dose_evidence)
        or "?" in result.dose_evidence
    ):
        return False
    return semantic_dose_evidence_matches(
        result.dose_evidence,
        result.insulin_units,
        allow_inflected_ordinal=allow_inflected_ordinal,
    ) and semantic_dose_values_are_consistent(
        source,
        result.insulin_units,
    )


def _semantic_dose_evidence_span(
    source: str,
    result: IntakeChatInsulinSemanticResult,
    *,
    product_span: tuple[int, int] | None,
) -> tuple[int, int] | None:
    """Select a safe occurrence when STT repeats the same exact dose text.

    The provider still supplies one verbatim dose fragment and one numeric
    value.  Multiple occurrences are accepted only as repeated copies of that
    same corroborated fragment; a product-bearing turn must select an occurrence
    locally bound to the independently verified product span.
    """

    candidates = [
        span
        for span in _semantic_repeated_evidence_spans(
            source,
            result.dose_evidence,
        )
        if semantic_dose_context_is_safe(source, span)
    ]
    if not candidates:
        return None
    if product_span is not None:
        candidates = [
            span
            for span in candidates
            if semantic_product_dose_evidence_is_bound(
                source,
                product_span=product_span,
                dose_span=span,
            )
        ]
        if not candidates:
            return None
        return min(
            candidates,
            key=lambda span: min(
                abs(span[0] - product_span[1]),
                abs(product_span[0] - span[1]),
            ),
        )
    return candidates[0]


def _semantic_action_evidence_span(
    source: str,
    fragment: str | None,
    *,
    anchor_span: tuple[int, int] | None,
) -> tuple[int, int] | None:
    """Bind repeated identical action text to the verified dose/product clause."""

    if anchor_span is None:
        return None
    candidates = [
        span
        for span in _semantic_repeated_evidence_spans(source, fragment)
        if semantic_action_evidence_matches_anchored_clause(
            source,
            anchor_span=anchor_span,
            action_span=span,
        )
    ]
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda span: min(
            abs(span[0] - anchor_span[1]),
            abs(anchor_span[0] - span[1]),
        ),
    )


def _reserved_intake_chat_context_json(
    context: _ReservedIntakeChatTurnContext,
) -> str:
    pending_product = context.pending_insulin
    snapshot = IntakeChatTurnReservationSnapshot.model_validate(
        {
            "version": 1,
            "expected_session_updated_at": context.expected_session_updated_at,
            "expected_last_turn_sequence": context.expected_last_turn_sequence,
            "context_created_at_ms": context.context_created_at_ms,
            "pending_product": (
                {
                    "insulin_name": pending_product[0].insulin_name,
                    "insulin_type": pending_product[0].insulin_type,
                    "cyrillic": pending_product[1],
                }
                if pending_product is not None
                else None
            ),
            "pending_revision": (
                {
                    "pending_action_id": UUID(
                        context.pending_revision.pending_action_id
                    ),
                    "target_action_id": (
                        UUID(context.pending_revision.target_action_id)
                        if context.pending_revision.target_action_id is not None
                        else None
                    ),
                    "cyrillic": context.pending_revision.cyrillic,
                    "expired": context.pending_revision.expired,
                    "single_insulin_name": (
                        context.pending_revision.single_insulin_name
                    ),
                    "single_insulin_type": (
                        context.pending_revision.single_insulin_type
                    ),
                }
                if context.pending_revision is not None
                else None
            ),
            "implicit_insulin": (
                {
                    "target_action_id": UUID(
                        context.implicit_insulin.target_action_id
                    ),
                    "target_event_id": UUID(
                        context.implicit_insulin.target_event_id
                    ),
                    "target_updated_at_ms": (
                        context.implicit_insulin.target_updated_at_ms
                    ),
                    "insulin_name": context.implicit_insulin.insulin_name,
                    "insulin_type": context.implicit_insulin.insulin_type,
                    "insulin_units": context.implicit_insulin.insulin_units,
                    "cyrillic": context.implicit_insulin.cyrillic,
                }
                if context.implicit_insulin is not None
                else None
            ),
            "implicit_meal": (
                {
                    "target_action_id": UUID(
                        context.implicit_meal.target_action_id
                    ),
                    "target_event_id": UUID(
                        context.implicit_meal.target_event_id
                    ),
                    "target_updated_at_ms": (
                        context.implicit_meal.target_updated_at_ms
                    ),
                    "meal_text": context.implicit_meal.meal_text,
                    "portion_g": context.implicit_meal.portion_g,
                    "carbs_g": context.implicit_meal.carbs_g,
                    "cyrillic": context.implicit_meal.cyrillic,
                }
                if context.implicit_meal is not None
                else None
            ),
            "semantic_delete": (
                {
                    "target_action_id": UUID(
                        context.semantic_delete.target_action_id
                    ),
                    "target_action_sequence": (
                        context.semantic_delete.target_action_sequence
                    ),
                    "target_event_id": UUID(
                        context.semantic_delete.target_event_id
                    ),
                    "target_updated_at_ms": (
                        context.semantic_delete.target_updated_at_ms
                    ),
                    "insulin_name": context.semantic_delete.insulin_name,
                    "insulin_type": context.semantic_delete.insulin_type,
                    "insulin_units": context.semantic_delete.insulin_units,
                    "cyrillic": (
                        context.implicit_insulin.cyrillic
                        if context.implicit_insulin is not None
                        else bool(
                            context.pending_revision
                            and context.pending_revision.cyrillic
                        )
                    ),
                }
                if context.semantic_delete is not None
                else None
            ),
            "visible_action": (
                {
                    "target_action_id": UUID(
                        context.visible_action.target_action_id
                    ),
                    "target_action_sequence": (
                        context.visible_action.target_action_sequence
                    ),
                    "events": [
                        {
                            "event_id": UUID(item.event_id),
                            "updated_at_ms": item.updated_at_ms,
                        }
                        for item in context.visible_action.events
                    ],
                }
                if context.visible_action is not None
                else None
            ),
            "replacement_events": [
                {
                    "kind": item.kind,
                    "event_id": UUID(item.event_id),
                    "updated_at_ms": item.updated_at_ms,
                }
                for item in context.replacement_events
            ],
        },
        strict=True,
    )
    return snapshot.model_dump_json()


def _reserved_intake_chat_context(
    raw_json: str,
) -> _ReservedIntakeChatTurnContext:
    snapshot = IntakeChatTurnReservationSnapshot.model_validate_json(
        raw_json,
        strict=True,
    )
    pending_product = (
        (
            IncompleteInsulinProduct(
                snapshot.pending_product.insulin_name,
                snapshot.pending_product.insulin_type,
            ),
            snapshot.pending_product.cyrillic,
        )
        if snapshot.pending_product is not None
        else None
    )
    pending_revision = (
        _RevisionPendingContext(
            pending_action_id=str(snapshot.pending_revision.pending_action_id),
            target_action_id=(
                str(snapshot.pending_revision.target_action_id)
                if snapshot.pending_revision.target_action_id is not None
                else None
            ),
            cyrillic=snapshot.pending_revision.cyrillic,
            expired=snapshot.pending_revision.expired,
            single_insulin_name=snapshot.pending_revision.single_insulin_name,
            single_insulin_type=snapshot.pending_revision.single_insulin_type,
        )
        if snapshot.pending_revision is not None
        else None
    )
    implicit = (
        _ImplicitInsulinContext(
            target_action_id=str(snapshot.implicit_insulin.target_action_id),
            target_event_id=str(snapshot.implicit_insulin.target_event_id),
            target_updated_at_ms=snapshot.implicit_insulin.target_updated_at_ms,
            insulin_name=snapshot.implicit_insulin.insulin_name,
            insulin_type=snapshot.implicit_insulin.insulin_type,
            insulin_units=snapshot.implicit_insulin.insulin_units,
            cyrillic=snapshot.implicit_insulin.cyrillic,
        )
        if snapshot.implicit_insulin is not None
        else None
    )
    implicit_meal = (
        _ImplicitMealContext(
            target_action_id=str(snapshot.implicit_meal.target_action_id),
            target_event_id=str(snapshot.implicit_meal.target_event_id),
            target_updated_at_ms=snapshot.implicit_meal.target_updated_at_ms,
            meal_text=snapshot.implicit_meal.meal_text,
            portion_g=snapshot.implicit_meal.portion_g,
            carbs_g=snapshot.implicit_meal.carbs_g,
            cyrillic=snapshot.implicit_meal.cyrillic,
        )
        if snapshot.implicit_meal is not None
        else None
    )
    semantic_delete = (
        _FrozenSemanticDeleteContext(
            target_action_id=str(snapshot.semantic_delete.target_action_id),
            target_action_sequence=(
                snapshot.semantic_delete.target_action_sequence
            ),
            target_event_id=str(snapshot.semantic_delete.target_event_id),
            target_updated_at_ms=snapshot.semantic_delete.target_updated_at_ms,
            insulin_name=snapshot.semantic_delete.insulin_name,
            insulin_type=snapshot.semantic_delete.insulin_type,
            insulin_units=snapshot.semantic_delete.insulin_units,
        )
        if snapshot.semantic_delete is not None
        else None
    )
    visible = (
        _FrozenVisibleActionContext(
            target_action_id=str(snapshot.visible_action.target_action_id),
            target_action_sequence=snapshot.visible_action.target_action_sequence,
            events=tuple(
                _FrozenVisibleEventContext(
                    event_id=str(item.event_id),
                    updated_at_ms=item.updated_at_ms,
                )
                for item in snapshot.visible_action.events
            ),
        )
        if snapshot.visible_action is not None
        else None
    )
    replacement_events = tuple(
        _FrozenReplacementEventContext(
            kind=item.kind,
            event_id=str(item.event_id),
            updated_at_ms=item.updated_at_ms,
        )
        for item in snapshot.replacement_events
    )
    return _ReservedIntakeChatTurnContext(
        expected_session_updated_at=snapshot.expected_session_updated_at,
        expected_last_turn_sequence=snapshot.expected_last_turn_sequence,
        context_created_at_ms=snapshot.context_created_at_ms,
        pending_insulin=pending_product,
        pending_revision=pending_revision,
        implicit_insulin=implicit,
        implicit_meal=implicit_meal,
        semantic_delete=semantic_delete,
        visible_action=visible,
        replacement_events=replacement_events,
    )


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
                "fetch the latest state before retrying"
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
    *,
    commit_transaction: bool = True,
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
        if commit_transaction:
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


def _intake_chat_session_response(
    record: IntakeChatSessionRecord,
) -> IntakeChatSessionResponse:
    return IntakeChatSessionResponse(
        id=UUID(record.id),
        client_session_id=UUID(record.client_session_id),
        created_at_ms=record.created_at_ms,
        updated_at_ms=record.updated_at_ms,
    )


def _cached_intake_chat_turn(
    record: IntakeChatTurnRecord,
) -> IntakeChatTurnResponse:
    try:
        return IntakeChatTurnResponse.model_validate_json(record.response_json)
    except ValidationError as error:
        logger.error("stored intake-chat turn %s is invalid", record.id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="stored intake-chat response is invalid",
        ) from error


def _event_with_analysis(
    session: Session,
    record: IntakeEventRecord,
) -> IntakeEvent:
    analysis = (
        session.get(AnalysisRecord, record.analysis_id)
        if record.analysis_id
        else None
    )
    return _event_response(
        record, analysis.result_json if analysis is not None else None
    )


def _stage_intake_record(
    payload: IntakeCreate,
    session: Session,
    *,
    now_ms: int,
    event_id: UUID | None = None,
) -> IntakeEventRecord:
    """Stage an intake and sync revision without committing the transaction."""

    existing = session.scalar(
        select(IntakeEventRecord).where(
            IntakeEventRecord.client_event_id == str(payload.client_event_id)
        )
    )
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="intake-chat event identity is already in use",
        )
    analysis = (
        session.get(AnalysisRecord, str(payload.analysis_id))
        if payload.analysis_id is not None
        else None
    )
    if payload.analysis_id is not None and analysis is None:
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
    record = IntakeEventRecord(
        id=str(event_id or uuid4()),
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
        payload_hash=_payload_hash(payload),
        created_at_ms=now_ms,
        updated_at_ms=now_ms,
        deleted_at_ms=None,
        sync_version=0,
    )
    session.add(record)
    session.flush()
    change = SyncChangeRecord(
        event_id=record.id,
        operation="upsert",
        changed_at_ms=now_ms,
    )
    session.add(change)
    session.flush()
    record.sync_version = change.id
    return record


def _stage_intake_delete(
    session: Session,
    record: IntakeEventRecord,
    *,
    now_ms: int,
) -> bool:
    if record.deleted_at_ms is not None:
        return False
    record.deleted_at_ms = now_ms
    record.updated_at_ms = now_ms
    change = SyncChangeRecord(
        event_id=record.id,
        operation="delete",
        changed_at_ms=now_ms,
    )
    session.add(change)
    session.flush()
    record.sync_version = change.id
    return True


def _stage_intake_restore(
    session: Session,
    record: IntakeEventRecord,
    *,
    now_ms: int,
) -> bool:
    if record.deleted_at_ms is None:
        return False
    record.deleted_at_ms = None
    record.updated_at_ms = now_ms
    change = SyncChangeRecord(
        event_id=record.id,
        operation="upsert",
        changed_at_ms=now_ms,
    )
    session.add(change)
    session.flush()
    record.sync_version = change.id
    return True


def _intake_event_kind(record: IntakeEventRecord) -> str:
    if record.insulin_type in ("rapid", "long"):
        return record.insulin_type
    return "meal"


def _current_intake_chat_events(
    session: Session,
    session_id: str,
) -> list[IntakeEventRecord]:
    rows = session.execute(
        select(IntakeEventRecord, IntakeChatActionRecord.sequence)
        .join(
            IntakeChatActionEventRecord,
            IntakeChatActionEventRecord.event_id == IntakeEventRecord.id,
        )
        .join(
            IntakeChatActionRecord,
            IntakeChatActionRecord.id == IntakeChatActionEventRecord.action_id,
        )
        .where(
            IntakeChatActionRecord.session_id == session_id,
            IntakeChatActionRecord.undone_at_ms.is_(None),
            IntakeChatActionEventRecord.operation == "create",
            IntakeEventRecord.deleted_at_ms.is_(None),
        )
        .order_by(
            IntakeChatActionRecord.sequence.desc(),
            IntakeChatActionEventRecord.sequence.desc(),
        )
    ).all()
    result: list[IntakeEventRecord] = []
    seen: set[str] = set()
    for record, _sequence in rows:
        if record.id not in seen:
            seen.add(record.id)
            result.append(record)
    return result


def _latest_reversible_action(
    session: Session,
    session_id: str,
) -> IntakeChatActionRecord | None:
    return session.scalar(
        select(IntakeChatActionRecord)
        .where(
            IntakeChatActionRecord.session_id == session_id,
            IntakeChatActionRecord.undone_at_ms.is_(None),
            IntakeChatActionRecord.intent.in_(("create", "replace_last")),
        )
        .order_by(IntakeChatActionRecord.sequence.desc())
        .limit(1)
    )


def _action_links(
    session: Session,
    action_id: str,
) -> list[IntakeChatActionEventRecord]:
    return list(
        session.scalars(
            select(IntakeChatActionEventRecord)
            .where(IntakeChatActionEventRecord.action_id == action_id)
            .order_by(IntakeChatActionEventRecord.sequence.asc())
        )
    )


def _active_action_created_events(
    session: Session,
    action: IntakeChatActionRecord,
) -> list[IntakeEventRecord]:
    events: list[IntakeEventRecord] = []
    for link in _action_links(session, action.id):
        if link.operation != "create":
            continue
        record = session.get(IntakeEventRecord, link.event_id)
        if record is not None and record.deleted_at_ms is None:
            events.append(record)
    return events


def _latest_visible_action(
    session: Session,
    session_id: str,
) -> tuple[IntakeChatActionRecord, list[IntakeEventRecord]] | None:
    candidates = session.scalars(
        select(IntakeChatActionRecord)
        .where(
            IntakeChatActionRecord.session_id == session_id,
            IntakeChatActionRecord.undone_at_ms.is_(None),
            IntakeChatActionRecord.intent.in_(("create", "replace_last")),
        )
        .order_by(IntakeChatActionRecord.sequence.desc())
    )
    for action in candidates:
        events = _active_action_created_events(session, action)
        if events:
            return action, events
    return None


def _freeze_visible_action_context(
    session: Session,
    session_id: str,
) -> _FrozenVisibleActionContext | None:
    candidates = session.scalars(
        select(IntakeChatActionRecord)
        .where(
            IntakeChatActionRecord.session_id == session_id,
            IntakeChatActionRecord.undone_at_ms.is_(None),
            IntakeChatActionRecord.intent.in_(("create", "replace_last")),
        )
        .order_by(IntakeChatActionRecord.sequence.desc())
    )
    for action in candidates:
        events = _active_action_created_events(session, action)
        if not events:
            continue
        if len(events) > 24:
            return None
        return _FrozenVisibleActionContext(
            target_action_id=action.id,
            target_action_sequence=action.sequence,
            events=tuple(
                _FrozenVisibleEventContext(
                    event_id=record.id,
                    updated_at_ms=record.updated_at_ms,
                )
                for record in events
            ),
        )
    return None


def _frozen_visible_action_target(
    session: Session,
    session_id: str,
    context: _FrozenVisibleActionContext | None,
) -> tuple[IntakeChatActionRecord, list[IntakeEventRecord]] | None:
    """Revalidate the exact visible card selected for this request ID."""

    if context is None:
        return None
    action = session.get(IntakeChatActionRecord, context.target_action_id)
    if (
        action is None
        or action.session_id != session_id
        or action.sequence != context.target_action_sequence
        or action.intent not in ("create", "replace_last")
        or action.undone_at_ms is not None
    ):
        return None
    for candidate in session.scalars(
        select(IntakeChatActionRecord)
        .where(
            IntakeChatActionRecord.session_id == session_id,
            IntakeChatActionRecord.undone_at_ms.is_(None),
            IntakeChatActionRecord.intent.in_(("create", "replace_last")),
        )
        .order_by(IntakeChatActionRecord.sequence.desc())
    ):
        candidate_events = _active_action_created_events(session, candidate)
        if not candidate_events:
            continue
        if candidate.id != action.id:
            return None
        visible = (action, candidate_events)
        break
    else:
        return None
    expected_versions = {
        item.event_id: item.updated_at_ms for item in context.events
    }
    current_ids = {record.id for record in visible[1]}
    if current_ids != set(expected_versions):
        return None
    if any(
        record.updated_at_ms != expected_versions[record.id]
        or record.deleted_at_ms is not None
        for record in visible[1]
    ):
        return None
    return visible


def _undo_action_locked(
    session: Session,
    action: IntakeChatActionRecord,
    *,
    now_ms: int,
) -> tuple[list[IntakeEvent], list[UUID]]:
    latest = _latest_reversible_action(session, action.session_id)
    if latest is None or latest.id != action.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="only the latest intake-chat action can be undone",
        )
    links = _action_links(session, action.id)
    if len(links) > 24:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="intake-chat action affects too many events",
        )
    restored: list[IntakeEvent] = []
    deleted: list[UUID] = []
    for link in links:
        record = session.get(IntakeEventRecord, link.event_id)
        if record is None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="intake-chat action references a missing event",
            )
        if link.operation == "create":
            if _stage_intake_delete(session, record, now_ms=now_ms):
                deleted.append(UUID(record.id))
        elif link.operation == "delete":
            _stage_intake_restore(session, record, now_ms=now_ms)
            restored.append(_event_with_analysis(session, record))
        else:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="intake-chat action journal is invalid",
            )
    action.undone_at_ms = now_ms
    return restored, deleted


def _delete_visible_action_locked(
    session: Session,
    action: IntakeChatActionRecord,
    events: list[IntakeEventRecord],
    *,
    now_ms: int,
) -> list[UUID]:
    """Delete the current card without restoring a replaced historical version."""

    latest = _latest_visible_action(session, action.session_id)
    if latest is None or latest[0].id != action.id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="only the latest visible intake-chat entry can be deleted",
        )
    if len(events) > 24:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="intake-chat action affects too many events",
        )
    deleted: list[UUID] = []
    for record in events:
        if _stage_intake_delete(session, record, now_ms=now_ms):
            deleted.append(UUID(record.id))
    # Retire the mutation so a later inverse-undo cannot resurrect an older
    # version after the user explicitly deleted the visible current card.
    action.undone_at_ms = now_ms
    return deleted


def _already_undone_snapshot(
    session: Session,
    action: IntakeChatActionRecord,
) -> tuple[list[IntakeEvent], list[UUID]]:
    restored: list[IntakeEvent] = []
    deleted: list[UUID] = []
    links = _action_links(session, action.id)
    if len(links) > 24:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="intake-chat action affects too many events",
        )
    for link in links:
        record = session.get(IntakeEventRecord, link.event_id)
        if record is None:
            continue
        if link.operation == "create":
            deleted.append(UUID(record.id))
        elif link.operation == "delete" and record.deleted_at_ms is None:
            restored.append(_event_with_analysis(session, record))
    return restored, deleted


def _intake_chat_history(
    session: Session,
    session_id: str,
    limit: int,
) -> list[IntakeChatHistoryEntry]:
    descending = list(
        session.scalars(
            select(IntakeChatTurnRecord)
            .where(IntakeChatTurnRecord.session_id == session_id)
            .order_by(IntakeChatTurnRecord.sequence.desc())
            .limit(limit)
        )
    )
    result: list[IntakeChatHistoryEntry] = []
    for record in reversed(descending):
        response = _cached_intake_chat_turn(record)
        action = (
            session.get(IntakeChatActionRecord, record.action_id)
            if record.action_id is not None
            else None
        )
        action_was_undone = bool(
            action is not None and action.undone_at_ms is not None
        )
        # Insulin is parsed by deterministic server code.  Never expose an
        # insulin event JSON object to the model, even as historical context.
        response_meal_events = [
            event
            for event in response.events
            if event.insulin_units is None
            and event.insulin_type is None
            and event.insulin_name is None
        ]
        meal_events: list[IntakeEvent] = []
        if not action_was_undone:
            for event in response_meal_events:
                current = session.get(IntakeEventRecord, str(event.id))
                if current is not None and current.deleted_at_ms is None:
                    meal_events.append(_event_with_analysis(session, current))

        effective_outcome = record.outcome
        effective_assistant_message = record.assistant_message
        if action_was_undone:
            effective_outcome = "undone"
            effective_assistant_message += (
                "\n\n[This action was later undone; its events are not active.]"
            )
        elif response_meal_events and not meal_events:
            effective_outcome = "no_change"
            effective_assistant_message += (
                "\n\n[The meal from this turn is no longer active.]"
            )
        events_json = json.dumps(
            [
                event.model_dump(
                    mode="json",
                    exclude={"insulin_units", "insulin_type", "insulin_name"},
                )
                for event in meal_events
            ],
            ensure_ascii=False,
            separators=(",", ":"),
        )
        result.append(
            IntakeChatHistoryEntry(
                user_text=record.user_text,
                assistant_message=effective_assistant_message,
                outcome=effective_outcome,
                events_json=events_json,
            )
        )
    return result


def _pending_insulin_product(
    session: Session,
    session_id: str,
    *,
    now_ms: int,
) -> tuple[IncompleteInsulinProduct, bool] | None:
    """Recover one short-lived, dose-missing clarification from this session."""

    latest = session.scalar(
        select(IntakeChatTurnRecord)
        .where(IntakeChatTurnRecord.session_id == session_id)
        .order_by(IntakeChatTurnRecord.sequence.desc())
        .limit(1)
    )
    if latest is None or latest.outcome != "clarification":
        return None
    age_ms = now_ms - latest.created_at_ms
    if age_ms < 0 or age_ms > _PENDING_INSULIN_FOLLOWUP_WINDOW_MS:
        return None
    product = parse_insulin_product_missing_dose(latest.user_text)
    if product is None:
        return None
    return product, uses_cyrillic(latest.user_text)


def _implicit_insulin_context(
    session: Session,
    session_id: str,
    *,
    now_ms: int,
) -> _ImplicitInsulinContext | None:
    """Freeze a narrow referent for the immediately following dose-only turn.

    The referent never crosses a session, skips over another turn, or points to
    a compound action.  Only a newly created or just-replaced, still-active
    single-insulin card is eligible; meal cards and historical/global intake
    records are excluded.
    """

    latest = session.scalar(
        select(IntakeChatTurnRecord)
        .where(IntakeChatTurnRecord.session_id == session_id)
        .order_by(IntakeChatTurnRecord.sequence.desc())
        .limit(1)
    )
    if (
        latest is None
        or latest.outcome != "applied"
        or latest.action_id is None
    ):
        return None
    age_ms = now_ms - latest.created_at_ms
    if age_ms < 0 or age_ms > _IMPLICIT_INSULIN_CONTEXT_WINDOW_MS:
        return None

    action = session.get(IntakeChatActionRecord, latest.action_id)
    if (
        action is None
        or action.session_id != session_id
        or action.intent not in ("create", "replace_last")
        or action.undone_at_ms is not None
    ):
        return None
    links = _action_links(session, action.id)
    create_links = [link for link in links if link.operation == "create"]
    delete_links = [link for link in links if link.operation == "delete"]
    expected_shape = bool(
        (action.intent == "create" and len(links) == 1 and not delete_links)
        or (
            action.intent == "replace_last"
            and len(links) == 2
            and len(delete_links) == 1
        )
    )
    if not expected_shape or len(create_links) != 1:
        return None
    if delete_links:
        deleted_record = session.get(IntakeEventRecord, delete_links[0].event_id)
        if (
            deleted_record is None
            or deleted_record.deleted_at_ms is None
            or _intake_event_kind(deleted_record) not in ("rapid", "long")
        ):
            return None
    record = session.get(IntakeEventRecord, create_links[0].event_id)
    if (
        record is None
        or record.deleted_at_ms is not None
        or _intake_event_kind(record) not in ("rapid", "long")
        or record.insulin_name is None
        or record.insulin_units is None
    ):
        return None
    return _ImplicitInsulinContext(
        target_action_id=action.id,
        target_event_id=record.id,
        target_updated_at_ms=record.updated_at_ms,
        insulin_name=record.insulin_name,
        insulin_type=_intake_event_kind(record),
        insulin_units=record.insulin_units,
        cyrillic=(
            uses_cyrillic(latest.user_text)
            or uses_cyrillic(latest.assistant_message)
        ),
    )


def _implicit_insulin_target(
    session: Session,
    session_id: str,
    context: _ImplicitInsulinContext,
    *,
    now_ms: int,
) -> IntakeEventRecord | None:
    """Revalidate a frozen implicit referent while holding the writer lock."""

    latest = session.scalar(
        select(IntakeChatTurnRecord)
        .where(IntakeChatTurnRecord.session_id == session_id)
        .order_by(IntakeChatTurnRecord.sequence.desc())
        .limit(1)
    )
    if (
        latest is None
        or latest.outcome != "applied"
        or latest.action_id != context.target_action_id
    ):
        return None
    age_ms = now_ms - latest.created_at_ms
    if age_ms < 0 or age_ms > _IMPLICIT_INSULIN_CONTEXT_WINDOW_MS:
        return None

    action = session.get(IntakeChatActionRecord, context.target_action_id)
    if (
        action is None
        or action.session_id != session_id
        or action.intent not in ("create", "replace_last")
        or action.undone_at_ms is not None
    ):
        return None
    links = _action_links(session, action.id)
    create_links = [link for link in links if link.operation == "create"]
    delete_links = [link for link in links if link.operation == "delete"]
    expected_shape = bool(
        (action.intent == "create" and len(links) == 1 and not delete_links)
        or (
            action.intent == "replace_last"
            and len(links) == 2
            and len(delete_links) == 1
        )
    )
    if (
        not expected_shape
        or len(create_links) != 1
        or create_links[0].event_id != context.target_event_id
    ):
        return None
    if delete_links:
        deleted_record = session.get(IntakeEventRecord, delete_links[0].event_id)
        if (
            deleted_record is None
            or deleted_record.deleted_at_ms is None
            or _intake_event_kind(deleted_record) not in ("rapid", "long")
        ):
            return None
    record = session.get(IntakeEventRecord, context.target_event_id)
    if (
        record is None
        or record.deleted_at_ms is not None
        or record.updated_at_ms != context.target_updated_at_ms
        or record.insulin_name != context.insulin_name
        or _intake_event_kind(record) != context.insulin_type
        or record.insulin_units is None
        or not math.isclose(
            record.insulin_units,
            context.insulin_units,
            rel_tol=1e-9,
            abs_tol=1e-6,
        )
    ):
        return None
    return record


def _single_active_meal_for_action(
    session: Session,
    action: IntakeChatActionRecord,
) -> IntakeEventRecord | None:
    """Return one active meal owned by an otherwise valid visible action."""

    links = _action_links(session, action.id)
    if not links or len(links) > 24 or any(
        link.operation not in ("create", "delete") for link in links
    ):
        return None
    meals = [
        record
        for record in _active_action_created_events(session, action)
        if _intake_event_kind(record) == "meal"
    ]
    return meals[0] if len(meals) == 1 else None


def _implicit_meal_context(
    session: Session,
    session_id: str,
    *,
    now_ms: int,
) -> _ImplicitMealContext | None:
    """Freeze the meal on the card produced by the immediately prior turn."""

    latest = session.scalar(
        select(IntakeChatTurnRecord)
        .where(IntakeChatTurnRecord.session_id == session_id)
        .order_by(IntakeChatTurnRecord.sequence.desc())
        .limit(1)
    )
    if (
        latest is None
        or latest.outcome != "applied"
        or latest.action_id is None
    ):
        return None
    age_ms = now_ms - latest.created_at_ms
    if age_ms < 0 or age_ms > _IMPLICIT_MEAL_CONTEXT_WINDOW_MS:
        return None

    action = session.get(IntakeChatActionRecord, latest.action_id)
    if (
        action is None
        or action.session_id != session_id
        or action.intent not in ("create", "replace_last")
        or action.undone_at_ms is not None
    ):
        return None
    record = _single_active_meal_for_action(session, action)
    if record is None:
        return None
    return _ImplicitMealContext(
        target_action_id=action.id,
        target_event_id=record.id,
        target_updated_at_ms=record.updated_at_ms,
        meal_text=record.meal_text,
        portion_g=record.portion_g,
        carbs_g=record.carbs_g,
        cyrillic=(
            uses_cyrillic(latest.user_text)
            or uses_cyrillic(latest.assistant_message)
        ),
    )


def _implicit_meal_target(
    session: Session,
    session_id: str,
    context: _ImplicitMealContext,
    *,
    now_ms: int,
) -> IntakeEventRecord | None:
    """Revalidate the exact terse-meal referent under the writer lock."""

    latest = session.scalar(
        select(IntakeChatTurnRecord)
        .where(IntakeChatTurnRecord.session_id == session_id)
        .order_by(IntakeChatTurnRecord.sequence.desc())
        .limit(1)
    )
    if (
        latest is None
        or latest.outcome != "applied"
        or latest.action_id != context.target_action_id
    ):
        return None
    age_ms = now_ms - latest.created_at_ms
    if age_ms < 0 or age_ms > _IMPLICIT_MEAL_CONTEXT_WINDOW_MS:
        return None

    action = session.get(IntakeChatActionRecord, context.target_action_id)
    if (
        action is None
        or action.session_id != session_id
        or action.intent not in ("create", "replace_last")
        or action.undone_at_ms is not None
    ):
        return None
    record = _single_active_meal_for_action(session, action)
    if (
        record is None
        or record.id != context.target_event_id
        or record.deleted_at_ms is not None
        or record.updated_at_ms != context.target_updated_at_ms
        or record.meal_text != context.meal_text
        or record.portion_g != context.portion_g
        or record.carbs_g != context.carbs_g
    ):
        return None
    return record


def _freeze_semantic_delete_context(
    session: Session,
    session_id: str,
    *,
    implicit: _ImplicitInsulinContext | None,
    pending: _RevisionPendingContext | None,
    now_ms: int,
) -> _FrozenSemanticDeleteContext | None:
    """Select one exact same-session insulin action before the provider call."""

    action: IntakeChatActionRecord | None = None
    record: IntakeEventRecord | None = None
    if implicit is not None:
        record = _implicit_insulin_target(
            session,
            session_id,
            implicit,
            now_ms=now_ms,
        )
        action = session.get(IntakeChatActionRecord, implicit.target_action_id)
    elif pending is not None:
        bundle = _revision_target(session, session_id, pending)
        if bundle is not None and len(bundle[1]) == 1:
            candidate = bundle[1][0]
            if (
                _intake_event_kind(candidate) in ("rapid", "long")
                and candidate.insulin_name is not None
                and candidate.insulin_units is not None
            ):
                action, record = bundle[0], candidate
    if action is None or record is None:
        return None
    return _FrozenSemanticDeleteContext(
        target_action_id=action.id,
        target_action_sequence=action.sequence,
        target_event_id=record.id,
        target_updated_at_ms=record.updated_at_ms,
        insulin_name=record.insulin_name or "",
        insulin_type=_intake_event_kind(record),
        insulin_units=record.insulin_units or 0,
    )


def _frozen_semantic_delete_target(
    session: Session,
    session_id: str,
    context: _FrozenSemanticDeleteContext,
) -> tuple[IntakeChatActionRecord, IntakeEventRecord] | None:
    """Revalidate only the preselected semantic-delete target under the lock."""

    action = session.get(IntakeChatActionRecord, context.target_action_id)
    if (
        action is None
        or action.session_id != session_id
        or action.sequence != context.target_action_sequence
        or action.intent not in ("create", "replace_last")
        or action.undone_at_ms is not None
    ):
        return None
    links = _action_links(session, action.id)
    create_links = [link for link in links if link.operation == "create"]
    delete_links = [link for link in links if link.operation == "delete"]
    expected_shape = bool(
        (action.intent == "create" and len(links) == 1 and not delete_links)
        or (
            action.intent == "replace_last"
            and len(links) == 2
            and len(delete_links) == 1
        )
    )
    if (
        not expected_shape
        or len(create_links) != 1
        or create_links[0].event_id != context.target_event_id
    ):
        return None
    record = session.get(IntakeEventRecord, context.target_event_id)
    if (
        record is None
        or record.deleted_at_ms is not None
        or record.updated_at_ms != context.target_updated_at_ms
        or record.insulin_name != context.insulin_name
        or _intake_event_kind(record) != context.insulin_type
        or record.insulin_units is None
        or not math.isclose(
            record.insulin_units,
            context.insulin_units,
            rel_tol=1e-9,
            abs_tol=1e-6,
        )
    ):
        return None
    if delete_links:
        historical = session.get(IntakeEventRecord, delete_links[0].event_id)
        if historical is None or historical.deleted_at_ms is None:
            return None
    return action, record


def _delete_frozen_semantic_action_locked(
    session: Session,
    action: IntakeChatActionRecord,
    record: IntakeEventRecord,
    *,
    now_ms: int,
) -> list[UUID]:
    """Soft-delete exactly one already revalidated semantic target."""

    deleted = (
        [UUID(record.id)]
        if _stage_intake_delete(session, record, now_ms=now_ms)
        else []
    )
    action.undone_at_ms = now_ms
    return deleted


def _prior_reversible_action(
    session: Session,
    pending_action: IntakeChatActionRecord,
) -> IntakeChatActionRecord | None:
    """Bind a pending revision to the exact prior mutation, even if later retired."""

    return session.scalar(
        select(IntakeChatActionRecord)
        .where(
            IntakeChatActionRecord.session_id == pending_action.session_id,
            IntakeChatActionRecord.sequence < pending_action.sequence,
            IntakeChatActionRecord.intent.in_(("create", "replace_last")),
        )
        .order_by(IntakeChatActionRecord.sequence.desc())
        .limit(1)
    )


def _pending_revision_context(
    session: Session,
    session_id: str,
    *,
    now_ms: int,
) -> _RevisionPendingContext | None:
    latest = session.scalar(
        select(IntakeChatTurnRecord)
        .where(IntakeChatTurnRecord.session_id == session_id)
        .order_by(IntakeChatTurnRecord.sequence.desc())
        .limit(1)
    )
    if latest is None:
        return None
    age_ms = now_ms - latest.created_at_ms
    pending_action = (
        session.get(IntakeChatActionRecord, latest.action_id)
        if latest.action_id is not None
        else None
    )
    if pending_action is None or pending_action.intent != "revision_pending":
        return None

    expired = age_ms < 0 or age_ms > _PENDING_REVISION_WINDOW_MS

    target = _prior_reversible_action(session, pending_action)
    active_events = (
        _active_action_created_events(session, target)
        if not expired and target is not None and target.undone_at_ms is None
        else []
    )
    active_insulin_events = [
        record
        for record in active_events
        if _intake_event_kind(record) in ("rapid", "long")
    ]
    single_insulin = (
        active_insulin_events[0]
        if len(active_insulin_events) == 1
        else None
    )
    return _RevisionPendingContext(
        pending_action_id=pending_action.id,
        target_action_id=target.id if target is not None else None,
        cyrillic=(
            uses_cyrillic(latest.user_text)
            or uses_cyrillic(latest.assistant_message)
        ),
        expired=expired,
        single_insulin_name=(
            single_insulin.insulin_name if single_insulin is not None else None
        ),
        single_insulin_type=(
            _intake_event_kind(single_insulin)
            if single_insulin is not None
            else None
        ),
    )


def _revision_target(
    session: Session,
    session_id: str,
    context: _RevisionPendingContext,
) -> tuple[IntakeChatActionRecord, list[IntakeEventRecord]] | None:
    if context.expired or context.target_action_id is None:
        return None
    pending_action = session.get(
        IntakeChatActionRecord, context.pending_action_id
    )
    if (
        pending_action is None
        or pending_action.session_id != session_id
        or pending_action.intent != "revision_pending"
    ):
        return None
    target = session.get(IntakeChatActionRecord, context.target_action_id)
    if (
        target is None
        or target.session_id != session_id
        or target.intent not in ("create", "replace_last")
        or target.undone_at_ms is not None
    ):
        return None
    create_links = [
        link
        for link in _action_links(session, target.id)
        if link.operation == "create"
    ]
    if not create_links or len(create_links) > 24:
        return None
    events = _active_action_created_events(session, target)
    if len(events) != len(create_links):
        # A partially deleted compound card is no longer the frozen snapshot
        # the user was shown when revision started.
        return None
    if any(record.updated_at_ms > pending_action.created_at_ms for record in events):
        return None
    return target, events


def _meal_revision_model_context(
    session: Session,
    session_id: str,
    *,
    pending: _RevisionPendingContext | None,
    implicit: _ImplicitMealContext | None,
    now_ms: int,
) -> tuple[IntakeChatMealRevisionContext | None, tuple[float, ...]]:
    """Build trusted, target-bound context for one terse meal follow-up."""

    scope: str
    events: list[IntakeEventRecord]
    meal: IntakeEventRecord | None
    if pending is not None:
        bundle = _revision_target(session, session_id, pending)
        if bundle is None:
            return None, ()
        events = bundle[1]
        meals = [
            record for record in events if _intake_event_kind(record) == "meal"
        ]
        if len(meals) != 1:
            return None, ()
        meal = meals[0]
        scope = "pending_revision"
    elif implicit is not None:
        meal = _implicit_meal_target(
            session,
            session_id,
            implicit,
            now_ms=now_ms,
        )
        if meal is None:
            return None, ()
        action = session.get(IntakeChatActionRecord, implicit.target_action_id)
        if action is None:
            return None, ()
        events = _active_action_created_events(session, action)
        scope = "recent_single_meal"
    else:
        return None, ()

    return (
        IntakeChatMealRevisionContext(
            scope=scope,
            meal_text=meal.meal_text,
            portion_g=meal.portion_g,
            carbs_g=meal.carbs_g,
        ),
        tuple(
            record.insulin_units
            for record in events
            if _intake_event_kind(record) in ("rapid", "long")
            and record.insulin_units is not None
        ),
    )


def _intake_chat_request_hash(
    *,
    client_turn_id: UUID,
    occurred_at_ms: int,
    text: str,
    images,
    audio,
    audio_language: str | None,
) -> str:
    digest = hashlib.sha256()
    digest.update(str(client_turn_id).encode("ascii"))
    digest.update(b"\0")
    digest.update(str(occurred_at_ms).encode("ascii"))
    digest.update(b"\0")
    digest.update(text.encode("utf-8"))
    for prepared in images:
        digest.update(b"\0image\0")
        digest.update(prepared.media_type.encode("ascii"))
        digest.update(b"\0")
        digest.update(prepared.data)
    if audio is not None:
        digest.update(b"\0audio\0")
        digest.update(audio.format.encode("ascii"))
        digest.update(b"\0")
        digest.update(audio.data)
    language_key = _audio_language_request_key(audio_language)
    if language_key:
        digest.update(b"\0audio-language\0")
        digest.update(language_key.encode("ascii"))
    return digest.hexdigest()


def _audio_language_request_key(value: str | None) -> str:
    """Normalize request semantics while preserving an explicit `auto` override."""

    clean = (value or "").strip()
    if not clean:
        return ""
    if clean.casefold() == "auto":
        return "auto"
    return normalize_audio_language(clean) or ""


def _next_intake_chat_action_sequence(
    session: Session,
    session_id: str,
) -> int:
    return (
        session.scalar(
            select(func.max(IntakeChatActionRecord.sequence)).where(
                IntakeChatActionRecord.session_id == session_id
            )
        )
        or 0
    ) + 1


def _new_intake_chat_action(
    session: Session,
    *,
    session_id: str,
    action_id: UUID,
    intent: str,
    now_ms: int,
) -> IntakeChatActionRecord:
    action = IntakeChatActionRecord(
        id=str(action_id),
        session_id=session_id,
        intent=intent,
        sequence=_next_intake_chat_action_sequence(session, session_id),
        created_at_ms=now_ms,
        undone_at_ms=None,
    )
    session.add(action)
    session.flush()
    return action


def _link_intake_chat_event(
    session: Session,
    *,
    action_id: str,
    event_id: str,
    sequence: int,
    operation: str,
) -> None:
    session.add(
        IntakeChatActionEventRecord(
            action_id=action_id,
            event_id=event_id,
            sequence=sequence,
            operation=operation,
        )
    )


def _latest_events_by_kind(
    session: Session,
    session_id: str,
    kinds: set[str],
) -> list[IntakeEventRecord]:
    latest: dict[str, IntakeEventRecord] = {}
    for record in _current_intake_chat_events(session, session_id):
        kind = _intake_event_kind(record)
        if kind in kinds and kind not in latest:
            latest[kind] = record
    return [latest[kind] for kind in ("meal", "rapid", "long") if kind in latest]


def _freeze_replacement_event_contexts(
    session: Session,
    session_id: str,
) -> tuple[_FrozenReplacementEventContext, ...]:
    """Freeze the latest active target for each replaceable event kind."""

    return tuple(
        _FrozenReplacementEventContext(
            kind=_intake_event_kind(record),
            event_id=record.id,
            updated_at_ms=record.updated_at_ms,
        )
        for record in _latest_events_by_kind(
            session,
            session_id,
            {"meal", "rapid", "long"},
        )
    )


def _frozen_replacement_event_targets(
    session: Session,
    session_id: str,
    contexts: tuple[_FrozenReplacementEventContext, ...],
) -> list[IntakeEventRecord] | None:
    """Revalidate frozen latest-per-kind replacement targets without rebinding."""

    expected = {item.kind: item for item in contexts}
    if len(expected) != len(contexts):
        return None
    if not expected:
        return []
    current = _latest_events_by_kind(session, session_id, set(expected))
    current_by_kind = {_intake_event_kind(record): record for record in current}
    if set(current_by_kind) != set(expected):
        return None
    for kind, context in expected.items():
        record = current_by_kind[kind]
        if (
            record.id != context.event_id
            or record.updated_at_ms != context.updated_at_ms
            or record.deleted_at_ms is not None
        ):
            return None
    return [
        current_by_kind[kind]
        for kind in ("meal", "rapid", "long")
        if kind in current_by_kind
    ]


def _select_insulin_revision_target(
    records: list[IntakeEventRecord],
    *,
    insulin_name: str | None,
    insulin_type: str | None,
) -> IntakeEventRecord | None:
    """Select one insulin only from an already frozen/revalidated event set."""

    insulin_records = [
        record
        for record in records
        if _intake_event_kind(record) in ("rapid", "long")
        and record.insulin_name is not None
        and record.insulin_units is not None
    ]
    if insulin_name is None and insulin_type is None:
        return insulin_records[0] if len(insulin_records) == 1 else None
    if insulin_name is None or insulin_type is None:
        return None
    matches = [
        record
        for record in insulin_records
        if record.insulin_name == insulin_name
        and _intake_event_kind(record) == insulin_type
    ]
    return matches[0] if len(matches) == 1 else None


def _latest_action_insulin_targets(
    session: Session,
    session_id: str,
) -> list[IntakeEventRecord]:
    """Return active insulin creations owned by the latest reversible action.

    A product-less correction may use this only when the result contains exactly
    one record.  Deliberately do not fall back to a global or older-session search.
    """

    action = _latest_reversible_action(session, session_id)
    if action is None:
        return []
    targets: list[IntakeEventRecord] = []
    for link in _action_links(session, action.id):
        if link.operation != "create":
            continue
        record = session.get(IntakeEventRecord, link.event_id)
        if (
            record is not None
            and record.deleted_at_ms is None
            and _intake_event_kind(record) in ("rapid", "long")
        ):
            targets.append(record)
    return targets


def _intake_chat_namespace(session_id: str, client_turn_id: UUID) -> UUID:
    return uuid5(UUID(session_id), f"turn:{client_turn_id}")


def _insulin_confirmation(
    commands,
    *,
    replacement_kinds: set[str],
    cyrillic: bool,
) -> str:
    messages: list[str] = []
    for command in commands:
        replaced = command.insulin_type in replacement_kinds
        summary = (
            f"{command.insulin_units:g} {'ед.' if cyrillic else 'U'} "
            f"{command.insulin_name}"
        )
        if cyrillic:
            prefix = "Исправлено" if replaced else "Записано"
            messages.append(f"{prefix}: {summary}.")
        else:
            prefix = "Updated" if replaced else "Recorded"
            messages.append(f"{prefix}: {summary}.")
    messages.append(
        "Запись можно отменить." if cyrillic else "You can undo this entry."
    )
    return " ".join(messages)


def _insulin_time_confirmation(
    insulin_name: str,
    *,
    offset_ms: int,
    cyrillic: bool,
) -> str:
    minutes = max(1, round(offset_ms / 60_000))
    if cyrillic:
        return (
            f"Время {insulin_name} исправлено: {minutes:g} мин. назад. "
            "Запись можно отменить."
        )
    return (
        f"Updated {insulin_name} time to {minutes:g} min ago. "
        "You can undo this entry."
    )


def _meal_confirmation(meal, *, replaced: bool, cyrillic: bool) -> str:
    if cyrillic:
        prefix = "Еда исправлена" if replaced else "Еда записана"
        return (
            f"{prefix}: {meal.meal_name}, примерно "
            f"{meal.estimated_carbs_g:g} г углеводов. Запись можно отменить."
        )
    prefix = "Meal updated" if replaced else "Meal recorded"
    return (
        f"{prefix}: {meal.meal_name}, about "
        f"{meal.estimated_carbs_g:g} g carbs. You can undo this entry."
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
    intake_chat_analyzer: IntakeChatAnalyzer | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    database = Database(settings.database_path)
    glucose_updates = GlucoseUpdateHub()
    analyzer = analyzer or OpenRouterMealAnalyzer(settings)
    chat_analyzer = chat_analyzer or OpenRouterMealChatAnalyzer(settings)
    transcriber = transcriber or analyzer
    forecast_service = forecast_service or ForecastService()
    configure_glucose_listener = getattr(
        forecast_service, "set_glucose_commit_listener", None
    )
    if callable(configure_glucose_listener):
        configure_glucose_listener(glucose_updates.publish_threadsafe)
    intake_chat_analyzer = intake_chat_analyzer or OpenRouterIntakeChatAnalyzer(
        settings
    )

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        database.create_all()
        with database.session_factory() as session:
            register_forecast_runtime_release(session, settings.app_version)
        glucose_updates.start()
        yield
        await glucose_updates.close()
        await application.state.analyzer.aclose()
        if application.state.chat_analyzer is not application.state.analyzer:
            await application.state.chat_analyzer.aclose()
        if (
            application.state.transcriber is not application.state.analyzer
            and application.state.transcriber is not application.state.chat_analyzer
        ):
            await application.state.transcriber.aclose()
        if (
            application.state.intake_chat_analyzer
            is not application.state.analyzer
            and application.state.intake_chat_analyzer
            is not application.state.chat_analyzer
            and application.state.intake_chat_analyzer
            is not application.state.transcriber
        ):
            await application.state.intake_chat_analyzer.aclose()
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
    # Public viewer pagination still gets tamper-resistant, process-local
    # cursors without requiring a user-visible viewer credential.
    application.state.viewer_public_cursor_key = secrets.token_bytes(32)
    application.state.database = database
    application.state.glucose_updates = glucose_updates
    application.state.analyzer = analyzer
    application.state.chat_analyzer = chat_analyzer
    application.state.transcriber = transcriber
    application.state.forecast_service = forecast_service
    application.state.intake_chat_analyzer = intake_chat_analyzer
    application.add_middleware(
        TrustedHostMiddleware,
        allowed_hosts=list(settings.allowed_hosts),
    )
    application.add_middleware(ViewerSessionBodyLimitMiddleware)

    @application.exception_handler(RequestValidationError)
    async def redact_viewer_session_validation(
        request: Request,
        error: RequestValidationError,
    ):
        if request.url.path == "/v1/viewer/session":
            return JSONResponse(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                content={"detail": "invalid viewer session request"},
            )
        return await request_validation_exception_handler(request, error)

    @application.middleware("http")
    async def security_headers(request: Request, call_next):
        viewer_browser_path = (
            request.url.path == "/viewer"
            or request.url.path.startswith("/viewer/")
            or request.url.path.startswith("/v1/viewer/")
        )
        secure_browser_request = _is_secure_browser_request(request)
        if viewer_browser_path and not secure_browser_request:
            response = JSONResponse(
                status_code=status.HTTP_426_UPGRADE_REQUIRED,
                content={"detail": "the viewer requires HTTPS"},
                headers={"Upgrade": "TLS/1.2, HTTP/1.1"},
            )
        else:
            response = await call_next(request)
        if request.url.path == "/v1/viewer/stream":
            # Prevent reverse proxies from buffering or transforming the live
            # event stream while retaining the viewer's private no-store rule.
            response.headers["Cache-Control"] = "no-store, no-transform, private"
            response.headers["Vary"] = "Authorization, Cookie"
        elif request.url.path.startswith("/v1/viewer/"):
            # Viewer responses contain health data and may traverse a remote
            # reverse proxy.  Explicitly forbid both shared and private caches
            # and keep credential-dependent representations separated.
            response.headers["Cache-Control"] = "no-store, private"
            response.headers["Vary"] = "Authorization, Cookie"
        elif request.url.path.startswith("/viewer/assets/"):
            if response.status_code in {status.HTTP_200_OK, status.HTTP_304_NOT_MODIFIED}:
                response.headers["Cache-Control"] = (
                    "public, max-age=31536000, immutable"
                )
            else:
                response.headers["Cache-Control"] = "no-store"
        elif request.url.path == "/viewer" or request.url.path.startswith("/viewer/"):
            # The service worker owns versioning of the application shell.  HTML,
            # the manifest, and the worker themselves must always revalidate.
            response.headers["Cache-Control"] = "no-cache"
            if request.url.path == "/viewer/sw.js":
                response.headers["Service-Worker-Allowed"] = "/viewer/"
        else:
            response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "DENY"
        if viewer_browser_path:
            response.headers["X-Robots-Tag"] = "noindex, nofollow, noarchive"
        response.headers["Permissions-Policy"] = (
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
        )
        if request.url.path == "/viewer" or request.url.path.startswith("/viewer/"):
            response.headers["Content-Security-Policy"] = (
                "default-src 'self'; base-uri 'none'; connect-src 'self'; "
                "font-src 'self'; form-action 'self'; frame-ancestors 'none'; "
                "img-src 'self' data:; manifest-src 'self'; object-src 'none'; "
                "script-src 'self'; style-src 'self'; worker-src 'self'"
            )
        if viewer_browser_path and secure_browser_request:
            response.headers["Strict-Transport-Security"] = "max-age=31536000"
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
            version=current_settings.app_version,
            database=database_state,
            auth_configured=current_settings.auth_configured,
            viewer_auth_configured=current_settings.viewer_auth_configured,
            ai_configured=current_settings.openrouter_configured,
        )

    @application.get(
        "/v1/ready",
        response_model=HealthResponse,
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": HealthResponse}},
    )
    def readiness(request: Request, response: Response) -> HealthResponse:
        current_settings: Settings = request.app.state.settings
        database_state = "ok" if request.app.state.database.ping() else "error"
        ready = (
            database_state == "ok"
            and current_settings.auth_configured
            and current_settings.viewer_auth_configured
            and current_settings.openrouter_configured
        )
        if not ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthResponse(
            status="ok" if ready else "degraded",
            api_version="v1",
            version=current_settings.app_version,
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
        language: Annotated[str | None, Form(max_length=35)] = None,
    ) -> TranscriptionResponse:
        current_settings: Settings = request.app.state.settings
        try:
            try:
                normalize_audio_language(language)
            except ValueError as error:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail="audio language must be auto or a valid language tag",
                ) from error
            prepared_audio = await prepare_audio(
                audio, current_settings.max_audio_bytes
            )
            text = await request.app.state.transcriber.transcribe(
                prepared_audio,
                language,
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
        "/intake-chat/sessions",
        response_model=IntakeChatSessionResponse,
    )
    def create_intake_chat_session(
        payload: IntakeChatSessionCreate,
        session: SessionDependency,
    ) -> IntakeChatSessionResponse:
        """Create one durable unified composer conversation, idempotently."""

        _begin_immediate(session, "intake-chat session creation")
        client_session_id = str(payload.client_session_id)
        existing = session.scalar(
            select(IntakeChatSessionRecord).where(
                IntakeChatSessionRecord.client_session_id == client_session_id
            )
        )
        if existing is not None:
            response = _intake_chat_session_response(existing)
            session.rollback()
            return response

        now = _now_ms()
        record = IntakeChatSessionRecord(
            id=str(uuid4()),
            client_session_id=client_session_id,
            created_at_ms=now,
            updated_at_ms=now,
        )
        session.add(record)
        try:
            session.commit()
        except IntegrityError as error:
            session.rollback()
            raced = session.scalar(
                select(IntakeChatSessionRecord).where(
                    IntakeChatSessionRecord.client_session_id == client_session_id
                )
            )
            if raced is not None:
                return _intake_chat_session_response(raced)
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="intake-chat session identity conflicts",
            ) from error
        except SQLAlchemyError as error:
            session.rollback()
            logger.warning("intake-chat session creation failed", exc_info=True)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="intake-chat session creation is temporarily unavailable",
            ) from error
        return _intake_chat_session_response(record)

    @router.post(
        "/intake-chat/sessions/{chat_session_id}/turns",
        response_model=IntakeChatTurnResponse,
    )
    async def send_intake_chat_turn(
        chat_session_id: UUID,
        request: Request,
        session: SessionDependency,
        client_turn_id: Annotated[UUID, Form()],
        occurred_at_ms: Annotated[int, Form(gt=0)],
        text: Annotated[str, Form(max_length=4_000)] = "",
        photos: Annotated[list[UploadFile] | None, File()] = None,
        audio: Annotated[UploadFile | None, File()] = None,
        language: Annotated[str | None, Form(max_length=35)] = None,
    ) -> IntakeChatTurnResponse:
        """Parse and atomically apply one voice-, text-, or photo-first turn."""

        text = text.strip()
        photo_uploads = photos or []
        current_settings: Settings = request.app.state.settings

        async def close_uploads() -> None:
            for upload in photo_uploads:
                await upload.close()
            if audio is not None:
                await audio.close()

        # Fully validate and materialize every upload before touching durable state.
        try:
            try:
                _audio_language_request_key(language)
            except ValueError as error:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail="audio language must be auto or a valid language tag",
                ) from error
            if len(photo_uploads) > current_settings.meal_chat_max_photos:
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
            if occurred_at_ms > _now_ms() + 10 * 60 * 1_000:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail=(
                        "occurred_at_ms cannot be more than 10 minutes in the future"
                    ),
                )

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
        except MediaValidationError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error
        finally:
            await close_uploads()

        request_hash = _intake_chat_request_hash(
            client_turn_id=client_turn_id,
            occurred_at_ms=occurred_at_ms,
            text=text,
            images=prepared_images,
            audio=prepared_audio,
            audio_language=language,
        )
        session_id = str(chat_session_id)
        turn_identity = str(client_turn_id)

        reservation_identity = str(
            uuid5(UUID(session_id), f"turn-reservation:{turn_identity}")
        )
        _begin_immediate(session, "intake-chat turn reservation")
        try:
            chat_record = session.get(IntakeChatSessionRecord, session_id)
            if chat_record is None:
                session.rollback()
                raise HTTPException(
                    status_code=404,
                    detail="intake-chat session not found",
                )
            cached = session.scalar(
                select(IntakeChatTurnRecord).where(
                    IntakeChatTurnRecord.session_id == session_id,
                    IntakeChatTurnRecord.client_turn_id == turn_identity,
                )
            )
            if cached is not None:
                if cached.request_hash != request_hash:
                    session.rollback()
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="client_turn_id is already used for different turn data",
                    )
                response = _cached_intake_chat_turn(cached)
                session.rollback()
                return response

            current_last_turn_sequence = session.scalar(
                select(func.max(IntakeChatTurnRecord.sequence)).where(
                    IntakeChatTurnRecord.session_id == session_id
                )
            ) or 0
            context_now_ms = _now_ms()
            reservation = session.scalar(
                select(IntakeChatTurnReservationRecord).where(
                    IntakeChatTurnReservationRecord.session_id == session_id,
                    IntakeChatTurnReservationRecord.client_turn_id
                    == turn_identity,
                )
            )
            if reservation is not None:
                if reservation.request_hash != request_hash:
                    session.rollback()
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="client_turn_id is already reserved for different turn data",
                    )
                try:
                    reserved_context = _reserved_intake_chat_context(
                        reservation.context_json
                    )
                except (ValidationError, ValueError, TypeError) as error:
                    session.rollback()
                    logger.error(
                        "intake-chat turn reservation is invalid",
                        exc_info=True,
                    )
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="intake-chat request reservation is invalid",
                    ) from error
                if (
                    context_now_ms - reserved_context.context_created_at_ms < 0
                    or context_now_ms - reserved_context.context_created_at_ms
                    > _IMPLICIT_INSULIN_CONTEXT_WINDOW_MS
                    or chat_record.updated_at_ms
                    != reserved_context.expected_session_updated_at
                    or current_last_turn_sequence
                    != reserved_context.expected_last_turn_sequence
                ):
                    session.rollback()
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail=(
                            "intake-chat request context changed; submit the turn "
                            "with a new client_turn_id"
                        ),
                    )
                session.rollback()
            else:
                pending_insulin = _pending_insulin_product(
                    session,
                    session_id,
                    now_ms=context_now_ms,
                )
                pending_revision = _pending_revision_context(
                    session,
                    session_id,
                    now_ms=context_now_ms,
                )
                implicit_insulin = _implicit_insulin_context(
                    session,
                    session_id,
                    now_ms=context_now_ms,
                )
                implicit_meal = _implicit_meal_context(
                    session,
                    session_id,
                    now_ms=context_now_ms,
                )
                semantic_delete_candidate = _freeze_semantic_delete_context(
                    session,
                    session_id,
                    implicit=implicit_insulin,
                    pending=pending_revision,
                    now_ms=context_now_ms,
                )
                reserved_context = _ReservedIntakeChatTurnContext(
                    expected_session_updated_at=chat_record.updated_at_ms,
                    expected_last_turn_sequence=current_last_turn_sequence,
                    context_created_at_ms=context_now_ms,
                    pending_insulin=pending_insulin,
                    pending_revision=pending_revision,
                    implicit_insulin=implicit_insulin,
                    implicit_meal=implicit_meal,
                    semantic_delete=semantic_delete_candidate,
                    visible_action=_freeze_visible_action_context(
                        session,
                        session_id,
                    ),
                    replacement_events=_freeze_replacement_event_contexts(
                        session,
                        session_id,
                    ),
                )
                reservation = IntakeChatTurnReservationRecord(
                    id=reservation_identity,
                    session_id=session_id,
                    client_turn_id=turn_identity,
                    request_hash=request_hash,
                    context_json=_reserved_intake_chat_context_json(
                        reserved_context
                    ),
                    created_at_ms=context_now_ms,
                )
                session.add(reservation)
                session.commit()
        except HTTPException:
            session.rollback()
            raise
        except (IntegrityError, SQLAlchemyError) as error:
            session.rollback()
            logger.warning(
                "intake-chat turn reservation failed",
                exc_info=True,
            )
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="intake-chat turn reservation is temporarily unavailable",
            ) from error

        expected_session_updated_at = (
            reserved_context.expected_session_updated_at
        )
        expected_last_turn_sequence = (
            reserved_context.expected_last_turn_sequence
        )
        pending_insulin = reserved_context.pending_insulin
        pending_revision = reserved_context.pending_revision
        implicit_insulin = reserved_context.implicit_insulin
        implicit_meal = reserved_context.implicit_meal
        semantic_delete_candidate = reserved_context.semantic_delete
        reserved_visible_action = reserved_context.visible_action
        reserved_replacement_events = reserved_context.replacement_events
        history = _intake_chat_history(
            session,
            session_id,
            current_settings.meal_chat_max_history_messages,
        )
        (
            meal_revision_context,
            meal_revision_target_insulin_units,
        ) = _meal_revision_model_context(
            session,
            session_id,
            pending=pending_revision,
            implicit=implicit_meal,
            now_ms=context_now_ms,
        )
        # External speech/model calls must never hold the SQLite writer lock.
        session.rollback()

        try:
            transcript = (
                await request.app.state.transcriber.transcribe(
                    prepared_audio,
                    language,
                )
                if prepared_audio is not None
                else ""
            )
            transcript = transcript.strip()
            if len(transcript) > 8_000:
                raise AnalysisError("transcription was unexpectedly long")
        except AnalysisError as error:
            raise HTTPException(
                status_code=error.status_code, detail=error.detail
            ) from error

        evidence_parts = [part for part in (text, transcript) if part]
        full_evidence = "\n".join(evidence_parts)
        contextual_correction = parse_contextual_insulin_dose_correction(
            full_evidence
        )
        terse_insulin_replacement = parse_terse_insulin_dose_replacement(
            full_evidence
        )
        insulin_time_correction_requested = (
            has_contextual_insulin_time_correction_cue(full_evidence)
        )
        contextual_insulin_time = parse_contextual_insulin_time_correction(
            full_evidence
        )
        contextual_new_insulin_dose = parse_contextual_new_insulin_dose(
            full_evidence
        )
        contextual_meal_correction = parse_contextual_meal_quantity_correction(
            full_evidence
        )
        terse_meal_portion = (
            parse_terse_meal_portion_replacement(
                full_evidence,
                expected_current_grams=meal_revision_context.portion_g,
            )
            if meal_revision_context is not None
            else None
        )
        ambiguous_contextual_quantity = False
        if terse_meal_portion is not None and contextual_correction is not None:
            insulin_old_value_matches = any(
                math.isclose(
                    units,
                    contextual_correction.expected_units,
                    rel_tol=1e-9,
                    abs_tol=1e-6,
                )
                for units in meal_revision_target_insulin_units
            )
            if insulin_old_value_matches:
                # A compound card whose meal and insulin share the stated OLD
                # value cannot safely resolve a unitless OLD→NEW correction.
                ambiguous_contextual_quantity = True
                terse_meal_portion = None
            # Otherwise the frozen meal's current portion is the only matching
            # referent, so keep the meal evidence away from the insulin path.
            contextual_correction = None
        relative_meal_offset_ms = parse_relative_meal_time_offset_ms(
            full_evidence
        )
        ambiguous_meal_time = has_ambiguous_meal_time_reference(full_evidence)
        meal_occurred_at_ms = (
            occurred_at_ms - relative_meal_offset_ms
            if relative_meal_offset_ms is not None
            else occurred_at_ms
        )
        explicit = parse_explicit_insulin(full_evidence)
        cyrillic = uses_cyrillic(full_evidence)
        dose_only = parse_exact_insulin_dose(full_evidence)
        implicit_replacement: _ImplicitInsulinContext | None = None
        inherited_new_insulin: _ImplicitInsulinContext | None = None
        inherited_pending_new_insulin: _RevisionPendingContext | None = None
        if (
            pending_insulin is not None
            and dose_only is not None
            and not prepared_images
        ):
            pending_product, pending_was_cyrillic = pending_insulin
            explicit = ExplicitInsulinParse(
                commands=(
                    ExplicitInsulinCommand(
                        insulin_units=dose_only,
                        insulin_name=pending_product.insulin_name,
                        insulin_type=pending_product.insulin_type,
                        span=(0, len(full_evidence)),
                    ),
                ),
                ambiguous=False,
                replace_requested=False,
                meal_evidence="",
            )
            cyrillic = cyrillic or pending_was_cyrillic
        if (
            pending_revision is not None
            and not explicit.commands
            and dose_only is not None
            and pending_revision.single_insulin_name is not None
            and pending_revision.single_insulin_type is not None
            and not prepared_images
        ):
            explicit = ExplicitInsulinParse(
                commands=(
                    ExplicitInsulinCommand(
                        insulin_units=dose_only,
                        insulin_name=pending_revision.single_insulin_name,
                        insulin_type=pending_revision.single_insulin_type,
                        span=(0, len(full_evidence)),
                    ),
                ),
                ambiguous=False,
                replace_requested=False,
                meal_evidence="",
            )
        elif (
            pending_revision is not None
            and not explicit.commands
            and contextual_new_insulin_dose is not None
            and pending_revision.single_insulin_name is not None
            and pending_revision.single_insulin_type is not None
            and not prepared_images
        ):
            # "Another / injected N units" reports a separate injection even
            # when an earlier conversational revision prompt is still open.
            # The frozen pending target supplies only the explicit product;
            # it is revalidated and is never deleted by this action.
            inherited_pending_new_insulin = pending_revision
            explicit = ExplicitInsulinParse(
                commands=(
                    ExplicitInsulinCommand(
                        insulin_units=contextual_new_insulin_dose,
                        insulin_name=pending_revision.single_insulin_name,
                        insulin_type=pending_revision.single_insulin_type,
                        span=(0, len(full_evidence)),
                    ),
                ),
                ambiguous=False,
                replace_requested=False,
                meal_evidence="",
            )
        elif (
            pending_insulin is None
            and pending_revision is None
            and implicit_insulin is not None
            and len(explicit.commands) == 1
            and not explicit.meal_evidence.strip()
            and explicit.commands[0].insulin_name
            == implicit_insulin.insulin_name
            and explicit.commands[0].insulin_type
            == implicit_insulin.insulin_type
            and not is_explicit_new_insulin_report(full_evidence)
            and not prepared_images
        ):
            # A terse same-product payload ("Rapid 6") immediately after one
            # insulin-only card is the complete correction the user just gave,
            # not a request for another question and not a duplicate injection.
            # Explicit administration/additional wording is excluded above and
            # remains a separate new event.
            command = explicit.commands[0]
            implicit_replacement = implicit_insulin
            explicit = ExplicitInsulinParse(
                commands=(command,),
                ambiguous=False,
                replace_requested=True,
                meal_evidence="",
                insulin_replace_requested=True,
                insulin_replace_target_type=implicit_insulin.insulin_type,
                insulin_replace_expected_units=implicit_insulin.insulin_units,
            )
            cyrillic = cyrillic or implicit_insulin.cyrillic
        elif (
            pending_insulin is None
            and pending_revision is None
            and implicit_insulin is not None
            and not explicit.commands
            and dose_only is not None
            and not math.isclose(
                dose_only,
                implicit_insulin.insulin_units,
                rel_tol=1e-9,
                abs_tol=1e-6,
            )
            and not prepared_images
        ):
            # A completely bare bounded dose immediately following one newly
            # created insulin-only card is a conversational refinement of that
            # card.  Freeze both the product and expected old dose locally; the
            # LLM has no role in authorizing or constructing this replacement.
            implicit_replacement = implicit_insulin
            explicit = ExplicitInsulinParse(
                commands=(
                    ExplicitInsulinCommand(
                        insulin_units=dose_only,
                        insulin_name=implicit_insulin.insulin_name,
                        insulin_type=implicit_insulin.insulin_type,
                        span=(0, len(full_evidence)),
                    ),
                ),
                ambiguous=False,
                replace_requested=True,
                meal_evidence="",
                insulin_replace_requested=True,
                insulin_replace_target_type=implicit_insulin.insulin_type,
                insulin_replace_expected_units=implicit_insulin.insulin_units,
            )
            cyrillic = cyrillic or implicit_insulin.cyrillic
        elif (
            pending_insulin is None
            and pending_revision is None
            and implicit_insulin is not None
            and not explicit.commands
            and contextual_new_insulin_dose is not None
            and not prepared_images
        ):
            # Administration/additional wording means a new injection, not an
            # edit.  Product inheritance is limited to the same frozen context
            # used above and is revalidated under the transaction lock.
            inherited_new_insulin = implicit_insulin
            explicit = ExplicitInsulinParse(
                commands=(
                    ExplicitInsulinCommand(
                        insulin_units=contextual_new_insulin_dose,
                        insulin_name=implicit_insulin.insulin_name,
                        insulin_type=implicit_insulin.insulin_type,
                        span=(0, len(full_evidence)),
                    ),
                ),
                ambiguous=False,
                replace_requested=False,
                meal_evidence="",
            )
            cyrillic = cyrillic or implicit_insulin.cyrillic

        # A pending product/revision may already have converted a strict
        # dose-only answer into a complete explicit command.  Do not also run
        # the same number through the terse-correction payload path.
        if explicit.commands:
            terse_insulin_replacement = None

        explicit_undo = is_explicit_undo(full_evidence) and not prepared_images
        explicit_delete = (
            is_explicit_delete_current(full_evidence) and not prepared_images
        )
        explicit_pending_cancel = (
            is_explicit_pending_cancel(full_evidence) and not prepared_images
        )
        revision_requested = (
            is_explicit_revision_request(full_evidence) and not prepared_images
        )
        semantic_recent_product = (
            (
                implicit_insulin.insulin_name,
                implicit_insulin.insulin_type,
            )
            if implicit_insulin is not None
            else (
                (
                    pending_revision.single_insulin_name,
                    pending_revision.single_insulin_type,
                )
                if pending_revision is not None
                and pending_revision.single_insulin_name is not None
                and pending_revision.single_insulin_type is not None
                else None
            )
        )
        semantic_result: IntakeChatInsulinSemanticResult | None = None
        semantic_create_applied = False
        semantic_insulin_applied = False
        semantic_mixed_evidence_unresolved = False
        semantic_delete_target: _FrozenSemanticDeleteContext | None = None
        semantic_candidate = bool(
            not prepared_images
            and not explicit.commands
            and contextual_correction is None
            and terse_insulin_replacement is None
            and not insulin_time_correction_requested
            and contextual_meal_correction is None
            and terse_meal_portion is None
            and not ambiguous_contextual_quantity
            and not explicit_undo
            and not explicit_delete
            and not explicit_pending_cancel
            and not revision_requested
            and is_safe_semantic_insulin_text(full_evidence)
        )
        semantic_extractor = getattr(
            request.app.state.intake_chat_analyzer,
            "extract_insulin_semantics",
            None,
        )
        if semantic_candidate and semantic_extractor is not None:
            try:
                semantic_kwargs = {
                    "has_recent_insulin": semantic_recent_product is not None,
                }
                if (
                    pending_revision is not None
                    and semantic_recent_product is not None
                ):
                    semantic_kwargs["revision_pending"] = True
                semantic_result = await semantic_extractor(
                    full_evidence,
                    **semantic_kwargs,
                )
            except AnalysisError as error:
                if error.status_code in (429, 503):
                    raise HTTPException(
                        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                        detail=error.detail,
                    ) from error
                # Semantic extraction is a convenience fallback.  Provider or
                # schema failure keeps the deterministic no-write behavior.
                semantic_result = None

        if (
            semantic_result is not None
            and semantic_result.intent == "create"
            and pending_revision is not None
            and semantic_recent_product is not None
            and not is_explicit_new_insulin_report(full_evidence)
        ):
            # The frozen revision question supplies the correction action.  A
            # provider that still labels a terse product+dose answer as create
            # cannot escape that context.  Only the deterministic explicit-new
            # grammar (for example "injected another 6") may create a second
            # injection while a correction is pending; provider-labelled action
            # prose is not independent authorization for a duplicate.
            semantic_result = semantic_result.model_copy(
                update={
                    "intent": "replace_last",
                    "context_scope": "recent_single_insulin",
                }
            )

        if semantic_result is not None and semantic_result.intent == "create":
            product_offset = _semantic_evidence_offset(
                full_evidence,
                semantic_result.product_evidence,
            )
            # Action evidence is useful for corroborating the reported act but
            # is provider-controlled and may quote the entire mixed turn.  Use
            # only the exact product+dose envelope to select/redact the insulin
            # clause so a broad action quote cannot swallow an adjacent meal.
            product_span = (
                semantic_product_evidence_span(
                    full_evidence,
                    semantic_result.product_evidence,
                    semantic_result.insulin_name,
                    semantic_result.insulin_type,
                )
                if semantic_result.insulin_name is not None
                and semantic_result.insulin_type is not None
                else None
            )
            dose_span = _semantic_dose_evidence_span(
                full_evidence,
                semantic_result,
                product_span=product_span,
            )
            semantic_anchor_span = _semantic_span_envelope(
                product_span,
                dose_span,
            )
            action_span = _semantic_action_evidence_span(
                full_evidence,
                semantic_result.action_evidence,
                anchor_span=semantic_anchor_span,
            )
            action_offset = action_span[0] if action_span is not None else None
            dose_offset = dose_span[0] if dose_span is not None else None
            evidence_offsets = (
                action_offset,
                product_offset,
                dose_offset,
            )
            semantic_span = _semantic_span_envelope(
                action_span,
                product_span,
                dose_span,
            )
            if (
                semantic_result.confidence
                >= _SEMANTIC_INSULIN_CREATE_MIN_CONFIDENCE
                and is_safe_semantic_insulin_write(
                    full_evidence,
                    intent="create",
                    insulin_span=semantic_anchor_span,
                    action_span=action_span,
                )
                and semantic_result.insulin_name is not None
                and semantic_result.insulin_type is not None
                and _semantic_dose_is_supported(
                    full_evidence,
                    semantic_result,
                    allow_inflected_ordinal=True,
                )
                and all(offset is not None for offset in evidence_offsets)
                and semantic_span is not None
                and semantic_anchor_span is not None
                and action_span is not None
                and product_span is not None
                and dose_span is not None
                and max(offset for offset in evidence_offsets if offset is not None)
                - min(offset for offset in evidence_offsets if offset is not None)
                <= 160
            ):
                meal_residual = semantic_meal_residual(
                    full_evidence,
                    semantic_anchor_span,
                )
                meal_residual_is_supported = has_explicit_meal_consumption(
                    meal_residual
                )
                if not meal_residual_is_supported:
                    meal_residual = ""
                if not (
                    has_semantic_meal_consumption_cue(full_evidence)
                    and not meal_residual_is_supported
                ):
                    explicit = ExplicitInsulinParse(
                        commands=(
                            ExplicitInsulinCommand(
                                insulin_units=semantic_result.insulin_units or 0,
                                insulin_name=semantic_result.insulin_name,
                                insulin_type=semantic_result.insulin_type,
                                span=(0, len(full_evidence)),
                            ),
                        ),
                        ambiguous=False,
                        replace_requested=False,
                        meal_evidence=meal_residual,
                    )
                    semantic_create_applied = True
                    semantic_insulin_applied = True
        elif (
            semantic_result is not None
            and semantic_result.intent == "replace_last"
            and semantic_recent_product is not None
        ):
            recent_name, recent_type = semantic_recent_product
            product_is_supported = bool(
                (
                    semantic_result.insulin_name is None
                    and semantic_result.insulin_type is None
                    and semantic_result.product_evidence is None
                )
                or (
                    semantic_result.insulin_name == recent_name
                    and semantic_result.insulin_type == recent_type
                    and semantic_product_evidence_span(
                        full_evidence,
                        semantic_result.product_evidence,
                        semantic_result.insulin_name,
                        semantic_result.insulin_type,
                    )
                    is not None
                )
            )
            same_as_current = bool(
                implicit_insulin is not None
                and semantic_result.insulin_units is not None
                and math.isclose(
                    semantic_result.insulin_units,
                    implicit_insulin.insulin_units,
                    rel_tol=1e-9,
                    abs_tol=1e-6,
                )
            )
            replace_product_span = (
                semantic_product_evidence_span(
                    full_evidence,
                    semantic_result.product_evidence,
                    semantic_result.insulin_name,
                    semantic_result.insulin_type,
                )
                if semantic_result.insulin_name is not None
                and semantic_result.insulin_type is not None
                else None
            )
            replace_dose_span = _semantic_dose_evidence_span(
                full_evidence,
                semantic_result,
                product_span=replace_product_span,
            )
            replace_anchor_span = _semantic_span_envelope(
                replace_product_span,
                replace_dose_span,
            )
            replace_action_span = _semantic_action_evidence_span(
                full_evidence,
                semantic_result.action_evidence,
                anchor_span=replace_anchor_span,
            )
            replace_span = _semantic_span_envelope(
                replace_action_span,
                replace_product_span,
                replace_dose_span,
            )
            if (
                semantic_result.confidence
                >= _SEMANTIC_INSULIN_REPLACE_MIN_CONFIDENCE
                and is_safe_semantic_insulin_write(
                    full_evidence,
                    intent="replace_last",
                    insulin_span=replace_anchor_span,
                    action_span=replace_action_span,
                )
                and _semantic_dose_is_supported(
                    full_evidence,
                    semantic_result,
                    allow_inflected_ordinal=False,
                )
                and product_is_supported
                and replace_span is not None
                and replace_anchor_span is not None
                and replace_action_span is not None
                and replace_dose_span is not None
                and not same_as_current
            ):
                meal_residual = semantic_meal_residual(
                    full_evidence,
                    replace_anchor_span,
                )
                meal_residual_is_supported = has_explicit_meal_consumption(
                    meal_residual
                )
                if not meal_residual_is_supported:
                    meal_residual = ""
                if not (
                    has_semantic_meal_consumption_cue(full_evidence)
                    and not meal_residual_is_supported
                ):
                    explicit = ExplicitInsulinParse(
                        commands=(
                            ExplicitInsulinCommand(
                                insulin_units=semantic_result.insulin_units or 0,
                                insulin_name=recent_name,
                                insulin_type=recent_type,
                                span=(0, len(full_evidence)),
                            ),
                        ),
                        ambiguous=False,
                        replace_requested=implicit_insulin is not None,
                        meal_evidence=meal_residual,
                        insulin_replace_requested=implicit_insulin is not None,
                        insulin_replace_target_type=(
                            recent_type if implicit_insulin is not None else None
                        ),
                        insulin_replace_expected_units=(
                            implicit_insulin.insulin_units
                            if implicit_insulin is not None
                            else None
                        ),
                    )
                    semantic_insulin_applied = True
                    if implicit_insulin is not None:
                        implicit_replacement = implicit_insulin
                    cyrillic = cyrillic or (
                        implicit_insulin.cyrillic
                        if implicit_insulin is not None
                        else bool(pending_revision and pending_revision.cyrillic)
                    )
        elif (
            semantic_result is not None
            and semantic_result.intent == "delete_last"
            and semantic_delete_candidate is not None
            and semantic_result.confidence
            >= _SEMANTIC_INSULIN_DELETE_MIN_CONFIDENCE
            and is_safe_semantic_insulin_write(
                full_evidence,
                intent="delete_last",
                insulin_span=_semantic_evidence_span(
                    full_evidence,
                    semantic_result.action_evidence,
                ),
            )
            and _semantic_evidence_offset(
                full_evidence,
                semantic_result.action_evidence,
            )
            is not None
        ):
            # Freeze the exact action/event selected before the provider call.
            # The model never chooses an ID, and the writer path never falls
            # back to whichever action happens to be latest later.
            semantic_delete_target = semantic_delete_candidate
            explicit_delete = True
        elif (
            semantic_result is not None
            and semantic_result.intent == "revise_last"
            and semantic_recent_product is not None
            and semantic_result.confidence
            >= _SEMANTIC_INSULIN_REPLACE_MIN_CONFIDENCE
            and is_safe_semantic_insulin_write(
                full_evidence,
                intent="revise_last",
                insulin_span=_semantic_evidence_span(
                    full_evidence,
                    semantic_result.action_evidence,
                ),
            )
            and _semantic_evidence_offset(
                full_evidence,
                semantic_result.action_evidence,
            )
            is not None
        ):
            revision_requested = True

        semantic_mixed_evidence_unresolved = bool(
            semantic_result is not None
            and semantic_result.intent in ("create", "replace_last")
            and has_semantic_meal_consumption_cue(full_evidence)
            and not semantic_insulin_applied
        )

        if pending_revision is not None:
            cyrillic = cyrillic or pending_revision.cyrillic
        explicit_new_insulin = bool(
            semantic_create_applied
            or (
                explicit.commands
                and not explicit.insulin_replace_requested
                and is_explicit_new_insulin_report(full_evidence)
            )
        )
        if explicit_new_insulin:
            # An explicit report of a newly administered dose must not be
            # captured by an older "revise" prompt.  It starts a new action.
            pending_revision = None
        missing_dose_product = (
            parse_insulin_product_missing_dose(full_evidence)
            if explicit.ambiguous
            else None
        )
        meal_consumption_candidate = has_safe_meal_consumption_candidate(
            explicit.meal_evidence
        )
        additional_meal_reported = is_explicit_additional_meal_report(
            explicit.meal_evidence
        )
        meal_correction_syntax = is_explicit_meal_correction(
            explicit.meal_evidence
        )
        active_meal_revision_context = (
            None
            if meal_revision_context is not None
            and meal_revision_context.scope == "pending_revision"
            and pending_revision is None
            else meal_revision_context
        )
        if (
            active_meal_revision_context is not None
            and (
                prepared_images
                or not is_safe_terse_meal_revision_text(
                    explicit.meal_evidence,
                    expected_current_grams=(
                        active_meal_revision_context.portion_g
                    ),
                )
            )
        ):
            active_meal_revision_context = None
            terse_meal_portion = None
        if (
            active_meal_revision_context is not None
            and (
                additional_meal_reported
                or (
                    active_meal_revision_context.scope == "recent_single_meal"
                    and meal_consumption_candidate
                )
            )
        ):
            # A completed report of a separate meal is not swallowed by the
            # short-lived refinement context.  In an explicit pending flow only
            # an "additional/another" marker overrides the asked correction.
            active_meal_revision_context = None
            terse_meal_portion = None
            if additional_meal_reported:
                pending_revision = None
        safe_photo_context = has_safe_photo_meal_context(full_evidence)
        deterministic_clarification: str | None = None
        model_result: IntakeChatModelResult | None = None
        control_result: IntakeChatControlResult | None = None
        llm_revision_suspected = False

        control_text = explicit.meal_evidence
        if (
            not revision_requested
            and not explicit_undo
            and not explicit_delete
            and not explicit_pending_cancel
            and not explicit.commands
            and not explicit.ambiguous
            and contextual_correction is None
            and terse_insulin_replacement is None
            and not insulin_time_correction_requested
            and contextual_meal_correction is None
            and not meal_consumption_candidate
            and active_meal_revision_context is None
            and not prepared_images
            and bool(control_text)
        ):
            classifier = getattr(
                request.app.state.intake_chat_analyzer,
                "classify_control",
                None,
            )
            if classifier is not None:
                try:
                    control_result = await classifier(control_text)
                except AnalysisError:
                    # Control classification is non-mutating convenience.  A
                    # provider failure must retain the deterministic no-write path.
                    control_result = None
            llm_revision_suspected = bool(
                control_result is not None
                and control_result.intent == "revise_last"
                and reserved_visible_action is not None
            )
            if llm_revision_suspected:
                # This authorizes only a session-local pending question.  It
                # cannot mutate an intake; the eventual replacement still has
                # to satisfy frozen-target and evidence validation.
                revision_requested = True

        if insulin_time_correction_requested and contextual_insulin_time is None:
            deterministic_clarification = (
                "Уточните одно прошедшее время, например «этот инсулин — 5 минут назад». Ничего не изменено."
                if cyrillic
                else "State one past time, for example 'this insulin was 5 minutes ago'. Nothing was changed."
            )
        elif ambiguous_meal_time or meal_occurred_at_ms <= 0:
            deterministic_clarification = (
                "Уточните время еды как, например, «2 часа назад». Ничего не записано."
                if cyrillic
                else "State the meal time like '2 hours ago'. Nothing was recorded."
            )

        if ambiguous_contextual_quantity:
            deterministic_clarification = (
                "Уточните, меняете ли вы порцию еды или дозу инсулина. Ничего не изменено."
                if cyrillic
                else "Clarify whether you are changing the meal portion or insulin dose. Nothing was changed."
            )
        elif semantic_mixed_evidence_unresolved:
            deterministic_clarification = (
                "Не удалось безопасно разделить еду и инсулин. "
                "Повторите их отдельными сообщениями; ничего не записано."
                if cyrillic
                else "I could not safely separate the meal and insulin. "
                "Send them as separate messages; nothing was recorded."
            )
        elif revision_requested:
            deterministic_clarification = (
                control_result.assistant_message
                if control_result is not None
                else (
                    "Что изменить в последней записи?"
                    if cyrillic
                    else "What should change in the latest entry?"
                )
            )
        elif contextual_correction is not None:
            # Product resolution is allowed only from one unambiguous, active
            # event in this same session and is re-checked under the write lock.
            pass
        elif terse_insulin_replacement is not None:
            # The short correction parser supplies only bounded current-turn
            # evidence.  The exact event is selected from frozen session state.
            pass
        elif contextual_insulin_time is not None:
            # Timestamp edits use the same frozen-target rule and clone every
            # non-time insulin field inside the writer transaction.
            pass
        elif explicit.ambiguous and deterministic_clarification is None:
            if missing_dose_product is not None:
                deterministic_clarification = (
                    "Уточните точное количество единиц "
                    f"{missing_dose_product.insulin_name}."
                    if cyrillic
                    else "Please state the exact number of "
                    f"{missing_dose_product.insulin_name} units."
                )
            else:
                deterministic_clarification = (
                    "Уточните точное количество единиц и препарат: NovoRapid или Tresiba."
                    if cyrillic
                    else "Please state the exact units and product: NovoRapid or Tresiba."
                )
        elif (
            not explicit_undo
            and not explicit_delete
            and not explicit_pending_cancel
            and deterministic_clarification is None
        ):
            has_meal_evidence = bool(
                (prepared_images and safe_photo_context)
                or (explicit.meal_evidence and meal_consumption_candidate)
                or contextual_meal_correction is not None
                or (
                    active_meal_revision_context is not None
                    and bool(explicit.meal_evidence.strip())
                )
            )
            if has_meal_evidence:
                try:
                    if active_meal_revision_context is not None:
                        model_result = await request.app.state.intake_chat_analyzer.parse(
                            history,
                            explicit.meal_evidence,
                            prepared_images,
                            revision_context=active_meal_revision_context,
                        )
                    else:
                        model_result = await request.app.state.intake_chat_analyzer.parse(
                            history,
                            explicit.meal_evidence,
                            prepared_images,
                        )
                except AnalysisError as error:
                    raise HTTPException(
                        status_code=error.status_code, detail=error.detail
                    ) from error
                if (
                    model_result is not None
                    and model_result.intent == "create"
                    and active_meal_revision_context is not None
                    and (
                        active_meal_revision_context.scope == "pending_revision"
                        or terse_meal_portion is not None
                        or meal_correction_syntax
                    )
                ):
                    # The current turn answers/refines the frozen meal.  A
                    # provider cannot turn a terse payload into a duplicate;
                    # explicit separate-meal wording disabled this context above.
                    model_result = model_result.model_copy(
                        update={"intent": "replace_last"}
                    )
            elif not explicit.commands:
                deterministic_clarification = (
                    "Не удалось распознать запись. Скажите, что вы съели или какой инсулин ввели."
                    if cyrillic
                    else "I could not recognize an entry. Say what you ate or which insulin you took."
                )

        semantic_meal_write_supported = bool(
            model_result is not None
            and model_result.intent in ("create", "replace_last")
            and meal_consumption_candidate
            and is_safe_semantic_meal_write(
                explicit.meal_evidence,
                event_status=model_result.meal_event_status,
                actor=model_result.meal_actor,
                action_evidence=model_result.meal_action_evidence,
                food_evidence=model_result.meal_food_evidence,
                confidence=model_result.meal_semantic_confidence,
            )
        )
        model_mutation_intent_rejected = bool(
            model_result is not None
            and (
                model_result.intent == "undo_last"
                or (
                    model_result.intent in ("create", "replace_last")
                    and meal_consumption_candidate
                    and not semantic_meal_write_supported
                )
                or (
                    active_meal_revision_context is not None
                    and model_result.intent == "create"
                )
                or (
                    model_result.intent == "replace_last"
                    and not (
                        meal_correction_syntax
                        or contextual_meal_correction is not None
                        or pending_revision is not None
                        or active_meal_revision_context is not None
                    )
                )
                or (
                    (
                        meal_correction_syntax
                        or contextual_meal_correction is not None
                    )
                    and model_result.intent != "replace_last"
                )
                or (
                    contextual_meal_correction is not None
                    and model_result.intent == "replace_last"
                    and (
                        model_result.meal is None
                        or not math.isclose(
                            model_result.meal.total_portion_g,
                            contextual_meal_correction.replacement_grams,
                            rel_tol=1e-9,
                            abs_tol=1e-6,
                        )
                    )
                )
                or (
                    terse_meal_portion is not None
                    and model_result.intent == "replace_last"
                    and (
                        model_result.meal is None
                        or not math.isclose(
                            model_result.meal.total_portion_g,
                            terse_meal_portion,
                            rel_tol=1e-9,
                            abs_tol=1e-6,
                        )
                    )
                )
            )
        )
        meal_replace_authorized = bool(
            model_result is not None
            and model_result.intent in ("create", "replace_last")
            and (
                meal_correction_syntax
                or contextual_meal_correction is not None
                or pending_revision is not None
                or active_meal_revision_context is not None
            )
            and not model_mutation_intent_rejected
        )
        approved_meal = (
            model_result.meal
            if model_result is not None
            and model_result.intent in ("create", "replace_last")
            and not model_mutation_intent_rejected
            else None
        )
        implicit_meal_replacement = (
            implicit_meal
            if meal_replace_authorized
            and active_meal_revision_context is not None
            and active_meal_revision_context.scope == "recent_single_meal"
            else None
        )
        mixed_pair_required = bool(
            explicit.commands and meal_consumption_candidate
        )
        if mixed_pair_required and approved_meal is None:
            deterministic_clarification = (
                "Не удалось безопасно обработать еду и инсулин вместе. "
                "Ничего не записано; повторите их отдельными сообщениями."
                if cyrillic
                else "I could not safely process the meal and insulin together. "
                "Nothing was recorded; send them as separate messages."
            )
        if (
            model_mutation_intent_rejected
            and not explicit.commands
            and contextual_correction is None
            and terse_insulin_replacement is None
            and contextual_insulin_time is None
        ):
            deterministic_clarification = (
                "Ничего не изменено. Отмену или исправление нужно явно попросить в сообщении."
                if cyrillic
                else "Nothing was changed. Ask explicitly in your message to undo or correct a record."
            )

        _begin_immediate(session, "intake-chat turn")
        try:
            chat_record = session.get(IntakeChatSessionRecord, session_id)
            if chat_record is None:
                raise HTTPException(
                    status_code=404, detail="intake-chat session not found"
                )
            cached = session.scalar(
                select(IntakeChatTurnRecord).where(
                    IntakeChatTurnRecord.session_id == session_id,
                    IntakeChatTurnRecord.client_turn_id == turn_identity,
                )
            )
            if cached is not None:
                if cached.request_hash != request_hash:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="client_turn_id is already used for different turn data",
                    )
                response = _cached_intake_chat_turn(cached)
                session.rollback()
                return response

            active_reservation = session.get(
                IntakeChatTurnReservationRecord,
                reservation_identity,
            )
            if (
                active_reservation is None
                or active_reservation.session_id != session_id
                or active_reservation.client_turn_id != turn_identity
                or active_reservation.request_hash != request_hash
            ):
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=(
                        "intake-chat request reservation changed; submit the turn "
                        "with a new client_turn_id"
                    ),
                )

            current_last_turn_sequence = session.scalar(
                select(func.max(IntakeChatTurnRecord.sequence)).where(
                    IntakeChatTurnRecord.session_id == session_id
                )
            ) or 0
            if current_last_turn_sequence != expected_last_turn_sequence:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="intake-chat session changed while processing; retry the turn",
                )
            if chat_record.updated_at_ms != expected_session_updated_at:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="intake-chat session changed while processing; retry the turn",
                )

            now = max(_now_ms(), chat_record.updated_at_ms + 1)
            namespace = _intake_chat_namespace(session_id, client_turn_id)
            action_uuid = uuid5(namespace, "action")
            action: IntakeChatActionRecord
            response_events: list[IntakeEvent] = []
            deleted_event_ids: list[UUID] = []
            response_action_id: UUID | None = None

            # LLM intent is never authority for a destructive operation.
            wants_undo = explicit_undo
            wants_delete = explicit_delete
            meal = approved_meal
            has_apply_payload = bool(
                explicit.commands
                or meal is not None
                or contextual_correction is not None
                or terse_insulin_replacement is not None
                or contextual_insulin_time is not None
                or contextual_meal_correction is not None
            )
            frozen_visible = _frozen_visible_action_target(
                session,
                session_id,
                reserved_visible_action,
            )
            frozen_visible_changed = bool(
                reserved_visible_action is not None and frozen_visible is None
            )
            frozen_replacement_targets = _frozen_replacement_event_targets(
                session,
                session_id,
                reserved_replacement_events,
            )

            if wants_undo:
                target = _latest_reversible_action(session, session_id)
                if target is None:
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="undo_last",
                        now_ms=now,
                    )
                    outcome = "no_change"
                    assistant_message = (
                        model_result.assistant_message
                        if model_result is not None
                        else (
                            "Нет последней записи, которую можно отменить."
                            if cyrillic
                            else "There is no recent entry to undo."
                        )
                    )
                else:
                    response_events, deleted_event_ids = _undo_action_locked(
                        session, target, now_ms=now
                    )
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="undo_last",
                        now_ms=now,
                    )
                    outcome = "undone"
                    response_action_id = action_uuid
                    assistant_message = (
                        model_result.assistant_message
                        if model_result is not None
                        else (
                            "Последняя запись отменена."
                            if cyrillic
                            else "The latest entry was undone."
                        )
                    )
            elif wants_delete:
                frozen_delete = (
                    _frozen_semantic_delete_target(
                        session,
                        session_id,
                        semantic_delete_target,
                    )
                    if semantic_delete_target is not None
                    else None
                )
                if semantic_delete_target is not None and frozen_delete is None:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="semantic delete target changed while processing; retry the turn",
                    )
                visible = (
                    (frozen_delete[0], [frozen_delete[1]])
                    if frozen_delete is not None
                    else frozen_visible
                )
                if semantic_delete_target is None and frozen_visible_changed:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail=(
                            "delete target changed; submit the turn with a new "
                            "client_turn_id"
                        ),
                    )
                if visible is None:
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="delete_current",
                        now_ms=now,
                    )
                    outcome = "no_change"
                    assistant_message = (
                        "Нет текущей записи, которую можно удалить."
                        if cyrillic
                        else "There is no current entry to delete."
                    )
                else:
                    visible_action, visible_events = visible
                    deleted_event_ids = (
                        _delete_frozen_semantic_action_locked(
                            session,
                            visible_action,
                            visible_events[0],
                            now_ms=now,
                        )
                        if semantic_delete_target is not None
                        else _delete_visible_action_locked(
                            session,
                            visible_action,
                            visible_events,
                            now_ms=now,
                        )
                    )
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="delete_current",
                        now_ms=now,
                    )
                    for sequence, deleted_id in enumerate(
                        deleted_event_ids, start=1
                    ):
                        _link_intake_chat_event(
                            session,
                            action_id=action.id,
                            event_id=str(deleted_id),
                            sequence=sequence,
                            operation="delete",
                        )
                    outcome = "undone"
                    response_action_id = action_uuid
                    assistant_message = (
                        "Текущая запись удалена."
                        if cyrillic
                        else "The current entry was deleted."
                    )
            elif explicit_pending_cancel:
                action = _new_intake_chat_action(
                    session,
                    session_id=session_id,
                    action_id=action_uuid,
                    intent="cancel_pending",
                    now_ms=now,
                )
                outcome = "no_change"
                assistant_message = (
                    "Хорошо, ничего не изменено."
                    if cyrillic
                    else "Okay, nothing was changed."
                )
            elif revision_requested:
                if frozen_visible_changed:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail=(
                            "revision target changed; submit the turn with a new "
                            "client_turn_id"
                        ),
                    )
                visible = frozen_visible
                if visible is None:
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="clarify",
                        now_ms=now,
                    )
                    outcome = "no_change"
                    assistant_message = (
                        "Нет текущей записи для изменения."
                        if cyrillic
                        else "There is no current entry to revise."
                    )
                else:
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="revision_pending",
                        now_ms=now,
                    )
                    outcome = "clarification"
                    assistant_message = deterministic_clarification or _revision_question(
                        cyrillic
                    )
            elif pending_revision is not None and (
                deterministic_clarification is not None or not has_apply_payload
            ):
                target = _revision_target(session, session_id, pending_revision)
                if target is None:
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="clarify",
                        now_ms=now,
                    )
                    outcome = "no_change"
                    assistant_message = (
                        "Последняя запись уже недоступна для изменения."
                        if cyrillic
                        else "The latest entry is no longer available to revise."
                    )
                else:
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent="revision_pending",
                        now_ms=now,
                    )
                    outcome = "clarification"
                    assistant_message = _revision_question(cyrillic)
            elif deterministic_clarification is not None or not has_apply_payload:
                action = _new_intake_chat_action(
                    session,
                    session_id=session_id,
                    action_id=action_uuid,
                    intent="clarify",
                    now_ms=now,
                )
                outcome = "clarification"
                assistant_message = deterministic_clarification or (
                    model_result.assistant_message
                    if model_result is not None
                    else (
                        "Уточните, что нужно записать."
                        if cyrillic
                        else "Please clarify what should be recorded."
                    )
                )
            else:
                payloads: list[tuple[str, IntakeCreate, UUID]] = []
                confirmation_commands = list(explicit.commands)
                replacement_kinds: set[str] = set()
                replacement_target_kind_by_payload_kind: dict[str, str] = {}
                target_by_kind: dict[str, IntakeEventRecord] = {}
                correction_target_error = False
                inherited_context_error = False
                time_confirmation: tuple[str, int] | None = None
                preserve_payload_timestamp_kinds: set[str] = set()

                for index, command in enumerate(explicit.commands):
                    kind = command.insulin_type
                    payloads.append(
                        (
                            kind,
                            IntakeCreate(
                                client_event_id=uuid5(
                                    namespace, f"client:{kind}:{index}"
                                ),
                                occurred_at_ms=occurred_at_ms,
                                meal_text=None,
                                carbs_g=None,
                                portion_g=None,
                                carbs_source=None,
                                insulin_units=command.insulin_units,
                                insulin_type=command.insulin_type,
                                insulin_name=command.insulin_name,
                                analysis_id=None,
                            ),
                            uuid5(namespace, f"event:{kind}:{index}"),
                        )
                    )
                if explicit.insulin_replace_requested:
                    for command in explicit.commands:
                        target_kind = (
                            explicit.insulin_replace_target_type
                            or command.insulin_type
                        )
                        replacement_kinds.add(target_kind)
                        replacement_target_kind_by_payload_kind[
                            command.insulin_type
                        ] = target_kind

                if implicit_replacement is not None:
                    implicit_target = _implicit_insulin_target(
                        session,
                        session_id,
                        implicit_replacement,
                        now_ms=now,
                    )
                    if implicit_target is None:
                        correction_target_error = True
                    else:
                        target_by_kind[
                            implicit_replacement.insulin_type
                        ] = implicit_target

                if implicit_meal_replacement is not None:
                    implicit_meal_target = _implicit_meal_target(
                        session,
                        session_id,
                        implicit_meal_replacement,
                        now_ms=now,
                    )
                    if implicit_meal_target is None:
                        correction_target_error = True
                    else:
                        target_by_kind["meal"] = implicit_meal_target
                        replacement_kinds.add("meal")
                        replacement_target_kind_by_payload_kind["meal"] = "meal"

                if inherited_new_insulin is not None:
                    inherited_source = _implicit_insulin_target(
                        session,
                        session_id,
                        inherited_new_insulin,
                        now_ms=now,
                    )
                    if inherited_source is None:
                        correction_target_error = True
                        inherited_context_error = True

                if inherited_pending_new_insulin is not None:
                    pending_source_bundle = _revision_target(
                        session,
                        session_id,
                        inherited_pending_new_insulin,
                    )
                    pending_sources = (
                        [
                            record
                            for record in pending_source_bundle[1]
                            if _intake_event_kind(record) in ("rapid", "long")
                        ]
                        if pending_source_bundle is not None
                        else []
                    )
                    if (
                        len(pending_sources) != 1
                        or pending_sources[0].insulin_name
                        != inherited_pending_new_insulin.single_insulin_name
                        or _intake_event_kind(pending_sources[0])
                        != inherited_pending_new_insulin.single_insulin_type
                    ):
                        correction_target_error = True
                        inherited_context_error = True

                if contextual_correction is not None:
                    if pending_revision is not None:
                        revision_bundle = _revision_target(
                            session, session_id, pending_revision
                        )
                        contextual_targets = (
                            [
                                record
                                for record in revision_bundle[1]
                                if _intake_event_kind(record) in ("rapid", "long")
                            ]
                            if revision_bundle is not None
                            else []
                        )
                    else:
                        contextual_targets = (
                            [
                                record
                                for record in frozen_visible[1]
                                if _intake_event_kind(record)
                                in ("rapid", "long")
                            ]
                            if frozen_visible is not None
                            else []
                        )
                    if len(contextual_targets) != 1:
                        correction_target_error = True
                    else:
                        contextual_target = contextual_targets[0]
                        contextual_kind = _intake_event_kind(contextual_target)
                        contextual_command = ExplicitInsulinCommand(
                            insulin_units=contextual_correction.replacement_units,
                            insulin_name=contextual_target.insulin_name or "",
                            insulin_type=contextual_kind,
                            span=(0, 0),
                        )
                        confirmation_commands.append(contextual_command)
                        replacement_kinds.add(contextual_kind)
                        replacement_target_kind_by_payload_kind[
                            contextual_kind
                        ] = contextual_kind
                        target_by_kind[contextual_kind] = contextual_target
                        payloads.append(
                            (
                                contextual_kind,
                                IntakeCreate(
                                    client_event_id=uuid5(
                                        namespace,
                                        f"client:{contextual_kind}:contextual",
                                    ),
                                    occurred_at_ms=contextual_target.occurred_at_ms,
                                    meal_text=None,
                                    carbs_g=None,
                                    portion_g=None,
                                    carbs_source=None,
                                    insulin_units=contextual_correction.replacement_units,
                                    insulin_type=contextual_kind,
                                    insulin_name=contextual_target.insulin_name,
                                    analysis_id=None,
                                ),
                                uuid5(
                                    namespace,
                                    f"event:{contextual_kind}:contextual",
                                ),
                            )
                        )

                if terse_insulin_replacement is not None:
                    terse_target: IntakeEventRecord | None = None
                    if pending_revision is not None:
                        terse_revision_bundle = _revision_target(
                            session, session_id, pending_revision
                        )
                        terse_records = (
                            terse_revision_bundle[1]
                            if terse_revision_bundle is not None
                            else []
                        )
                        terse_target = _select_insulin_revision_target(
                            terse_records,
                            insulin_name=terse_insulin_replacement.insulin_name,
                            insulin_type=terse_insulin_replacement.insulin_type,
                        )
                    elif terse_insulin_replacement.has_explicit_referent:
                        terse_target = (
                            _select_insulin_revision_target(
                                frozen_replacement_targets,
                                insulin_name=(
                                    terse_insulin_replacement.insulin_name
                                ),
                                insulin_type=(
                                    terse_insulin_replacement.insulin_type
                                ),
                            )
                            if frozen_replacement_targets is not None
                            else None
                        )
                    elif implicit_insulin is not None:
                        terse_target = _implicit_insulin_target(
                            session,
                            session_id,
                            implicit_insulin,
                            now_ms=now,
                        )

                    if terse_target is None:
                        correction_target_error = True
                    elif (
                        terse_target.insulin_units is None
                        or math.isclose(
                            terse_target.insulin_units,
                            terse_insulin_replacement.replacement_units,
                            rel_tol=1e-9,
                            abs_tol=1e-6,
                        )
                    ):
                        # A correction must contain an actual new value.  This
                        # also avoids manufacturing a replace action when ASR
                        # merely repeats the current card.
                        correction_target_error = True
                    else:
                        terse_kind = _intake_event_kind(terse_target)
                        confirmation_commands.append(
                            ExplicitInsulinCommand(
                                insulin_units=(
                                    terse_insulin_replacement.replacement_units
                                ),
                                insulin_name=terse_target.insulin_name or "",
                                insulin_type=terse_kind,
                                span=(0, 0),
                            )
                        )
                        replacement_kinds.add(terse_kind)
                        replacement_target_kind_by_payload_kind[
                            terse_kind
                        ] = terse_kind
                        target_by_kind[terse_kind] = terse_target
                        payloads.append(
                            (
                                terse_kind,
                                IntakeCreate(
                                    client_event_id=uuid5(
                                        namespace,
                                        f"client:{terse_kind}:terse",
                                    ),
                                    occurred_at_ms=terse_target.occurred_at_ms,
                                    meal_text=None,
                                    carbs_g=None,
                                    portion_g=None,
                                    carbs_source=None,
                                    insulin_units=(
                                        terse_insulin_replacement.replacement_units
                                    ),
                                    insulin_type=terse_kind,
                                    insulin_name=terse_target.insulin_name,
                                    analysis_id=None,
                                ),
                                uuid5(
                                    namespace,
                                    f"event:{terse_kind}:terse",
                                ),
                            )
                        )

                if contextual_insulin_time is not None:
                    time_target: IntakeEventRecord | None = None
                    if pending_revision is not None:
                        time_revision_bundle = _revision_target(
                            session, session_id, pending_revision
                        )
                        time_records = (
                            time_revision_bundle[1]
                            if time_revision_bundle is not None
                            else []
                        )
                        time_target = _select_insulin_revision_target(
                            time_records,
                            insulin_name=contextual_insulin_time.insulin_name,
                            insulin_type=contextual_insulin_time.insulin_type,
                        )
                    else:
                        time_target = (
                            _select_insulin_revision_target(
                                frozen_replacement_targets,
                                insulin_name=contextual_insulin_time.insulin_name,
                                insulin_type=contextual_insulin_time.insulin_type,
                            )
                            if frozen_replacement_targets is not None
                            else None
                        )

                    corrected_time_ms = (
                        occurred_at_ms - contextual_insulin_time.offset_ms
                    )
                    if (
                        time_target is None
                        or time_target.insulin_units is None
                        or corrected_time_ms <= 0
                        or corrected_time_ms >= now
                        or corrected_time_ms == time_target.occurred_at_ms
                    ):
                        correction_target_error = True
                    else:
                        time_kind = _intake_event_kind(time_target)
                        replacement_kinds.add(time_kind)
                        replacement_target_kind_by_payload_kind[
                            time_kind
                        ] = time_kind
                        target_by_kind[time_kind] = time_target
                        payloads.append(
                            (
                                time_kind,
                                IntakeCreate(
                                    client_event_id=uuid5(
                                        namespace,
                                        f"client:{time_kind}:time",
                                    ),
                                    occurred_at_ms=corrected_time_ms,
                                    meal_text=None,
                                    carbs_g=None,
                                    portion_g=None,
                                    carbs_source=None,
                                    insulin_units=time_target.insulin_units,
                                    insulin_type=time_kind,
                                    insulin_name=time_target.insulin_name,
                                    analysis_id=None,
                                ),
                                uuid5(
                                    namespace,
                                    f"event:{time_kind}:time",
                                ),
                            )
                        )
                        time_confirmation = (
                            time_target.insulin_name or "Insulin",
                            contextual_insulin_time.offset_ms,
                        )
                        preserve_payload_timestamp_kinds.add(time_kind)

                meal_analysis: AnalysisRecord | None = None
                if meal is not None:
                    analysis_uuid = uuid5(namespace, "analysis:meal")
                    meal_analysis = AnalysisRecord(
                        id=str(analysis_uuid),
                        created_at_ms=now,
                        model=request.app.state.intake_chat_analyzer.model_name,
                        manual_text=explicit.meal_evidence or None,
                        transcription="",
                        result_json=meal.model_dump_json(),
                    )
                    payloads.append(
                        (
                            "meal",
                            IntakeCreate(
                                client_event_id=uuid5(namespace, "client:meal"),
                                occurred_at_ms=meal_occurred_at_ms,
                                meal_text=meal.meal_description or meal.meal_name,
                                carbs_g=meal.estimated_carbs_g,
                                portion_g=meal.total_portion_g or None,
                                carbs_source="ai_estimate",
                                insulin_units=None,
                                insulin_type=None,
                                insulin_name=None,
                                analysis_id=analysis_uuid,
                            ),
                            uuid5(namespace, "event:meal"),
                        )
                    )
                    if meal_replace_authorized:
                        replacement_kinds.add("meal")
                        replacement_target_kind_by_payload_kind["meal"] = "meal"

                if pending_revision is not None:
                    # A conversational revision is bound to the exact action
                    # that preceded its pending marker.  Never fall back to a
                    # newer, older, global, or merely same-kind event.
                    revision_bundle = _revision_target(
                        session, session_id, pending_revision
                    )
                    replacement_kinds.clear()
                    replacement_target_kind_by_payload_kind.clear()
                    target_by_kind.clear()
                    if revision_bundle is None:
                        correction_target_error = True
                    else:
                        _revision_action, frozen_events = revision_bundle
                        used_target_ids: set[str] = set()
                        frozen_insulin = [
                            record
                            for record in frozen_events
                            if _intake_event_kind(record) in ("rapid", "long")
                        ]
                        for payload_kind, _payload, _event_uuid in payloads:
                            preferred_kind = payload_kind
                            if (
                                payload_kind in ("rapid", "long")
                                and explicit.insulin_replace_requested
                                and explicit.insulin_replace_target_type is not None
                            ):
                                preferred_kind = (
                                    explicit.insulin_replace_target_type
                                )
                            candidates = [
                                record
                                for record in frozen_events
                                if record.id not in used_target_ids
                                and _intake_event_kind(record) == preferred_kind
                            ]
                            if (
                                not candidates
                                and payload_kind in ("rapid", "long")
                                and len(frozen_insulin) == 1
                                and frozen_insulin[0].id not in used_target_ids
                            ):
                                # A fully explicit product switch is safe only
                                # when this card owns one insulin event.
                                candidates = frozen_insulin
                            if len(candidates) != 1:
                                correction_target_error = True
                                continue
                            target_record = candidates[0]
                            target_kind = _intake_event_kind(target_record)
                            if target_kind in target_by_kind:
                                correction_target_error = True
                                continue
                            used_target_ids.add(target_record.id)
                            target_by_kind[target_kind] = target_record
                            replacement_kinds.add(target_kind)
                            replacement_target_kind_by_payload_kind[
                                payload_kind
                            ] = target_kind

                unresolved_replacement_kinds = (
                    replacement_kinds - set(target_by_kind)
                )
                if (
                    unresolved_replacement_kinds
                    and pending_revision is None
                    and implicit_replacement is None
                    and frozen_replacement_targets is None
                ):
                    correction_target_error = True
                discovered_targets = (
                    [
                        record
                        for record in frozen_replacement_targets
                        if _intake_event_kind(record)
                        in unresolved_replacement_kinds
                    ]
                    if unresolved_replacement_kinds
                    and pending_revision is None
                    and implicit_replacement is None
                    and frozen_replacement_targets is not None
                    else []
                )
                target_by_kind.update(
                    {
                        _intake_event_kind(record): record
                        for record in discovered_targets
                    }
                )
                missing_replacement_kinds = replacement_kinds - set(target_by_kind)
                expected_target_mismatch = False
                if explicit.insulin_replace_requested:
                    expected_units = explicit.insulin_replace_expected_units
                    if expected_units is None:
                        expected_target_mismatch = True
                    else:
                        for command in explicit.commands:
                            target_kind = replacement_target_kind_by_payload_kind.get(
                                command.insulin_type
                            )
                            target = (
                                target_by_kind.get(target_kind)
                                if target_kind is not None
                                else None
                            )
                            if (
                                target is None
                                or _intake_event_kind(target) != target_kind
                                or target.insulin_units is None
                                or not math.isclose(
                                    target.insulin_units,
                                    expected_units,
                                    rel_tol=1e-9,
                                    abs_tol=1e-6,
                                )
                            ):
                                expected_target_mismatch = True
                                break
                if contextual_correction is not None and target_by_kind:
                    contextual_target = next(iter(target_by_kind.values()))
                    if (
                        contextual_target.insulin_units is None
                        or not math.isclose(
                            contextual_target.insulin_units,
                            contextual_correction.expected_units,
                            rel_tol=1e-9,
                            abs_tol=1e-6,
                        )
                    ):
                        expected_target_mismatch = True
                if contextual_meal_correction is not None:
                    contextual_meal_target = target_by_kind.get("meal")
                    if (
                        contextual_meal_target is None
                        or contextual_meal_target.portion_g is None
                        or not math.isclose(
                            contextual_meal_target.portion_g,
                            contextual_meal_correction.expected_grams,
                            rel_tol=1e-9,
                            abs_tol=1e-6,
                        )
                    ):
                        expected_target_mismatch = True
                correction_target_error = (
                    correction_target_error
                    or bool(missing_replacement_kinds)
                    or expected_target_mismatch
                )

                if correction_target_error:
                    # A correction is never converted into a create when this
                    # session no longer owns the exact target.  The whole mixed
                    # action fails closed so unrelated facts cannot partially save.
                    revision_still_available = bool(
                        pending_revision is not None
                        and _revision_target(
                            session, session_id, pending_revision
                        )
                        is not None
                    )
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent=(
                            "revision_pending"
                            if revision_still_available
                            else "clarify"
                        ),
                        now_ms=now,
                    )
                    outcome = "clarification"
                    if revision_still_available:
                        assistant_message = _revision_question(cyrillic)
                    elif inherited_context_error:
                        assistant_message = (
                            "Ничего не записано: укажите препарат и дозу ещё раз."
                            if cyrillic
                            else "Nothing was recorded: state the product and dose again."
                        )
                    else:
                        assistant_message = (
                            "Ничего не изменено: исходная запись недоступна для исправления."
                            if cyrillic
                            else "Nothing was changed: the original entry is unavailable to revise."
                        )
                else:
                    replacement_targets = [
                        target_by_kind[kind]
                        for kind in ("meal", "rapid", "long")
                        if kind in target_by_kind
                    ]
                    if len(replacement_targets) + len(payloads) > 24:
                        raise HTTPException(
                            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                            detail="one intake-chat action can affect at most 24 events",
                        )

                    if meal_analysis is not None:
                        session.add(meal_analysis)
                        session.flush()

                    timestamped_payloads = [
                        (
                            kind,
                            payload.model_copy(
                                update={
                                    "occurred_at_ms": target_by_kind[
                                        replacement_target_kind_by_payload_kind[kind]
                                    ].occurred_at_ms
                                }
                            )
                            if kind in replacement_target_kind_by_payload_kind
                            and kind not in preserve_payload_timestamp_kinds
                            else payload,
                            event_uuid,
                        )
                        for kind, payload, event_uuid in payloads
                    ]
                    action_intent = (
                        "replace_last" if replacement_kinds else "create"
                    )
                    action = _new_intake_chat_action(
                        session,
                        session_id=session_id,
                        action_id=action_uuid,
                        intent=action_intent,
                        now_ms=now,
                    )
                    link_sequence = 1
                    for old_record in replacement_targets:
                        if _stage_intake_delete(session, old_record, now_ms=now):
                            deleted_event_ids.append(UUID(old_record.id))
                        _link_intake_chat_event(
                            session,
                            action_id=action.id,
                            event_id=old_record.id,
                            sequence=link_sequence,
                            operation="delete",
                        )
                        link_sequence += 1

                    for _kind, payload, event_uuid in timestamped_payloads:
                        new_record = _stage_intake_record(
                            payload,
                            session,
                            now_ms=now,
                            event_id=event_uuid,
                        )
                        _link_intake_chat_event(
                            session,
                            action_id=action.id,
                            event_id=new_record.id,
                            sequence=link_sequence,
                            operation="create",
                        )
                        link_sequence += 1
                        response_events.append(
                            _event_with_analysis(session, new_record)
                        )
                    session.flush()

                    outcome = "applied"
                    response_action_id = action_uuid
                    messages: list[str] = []
                    if confirmation_commands:
                        messages.append(
                            _insulin_confirmation(
                                confirmation_commands,
                                replacement_kinds=set(
                                    replacement_target_kind_by_payload_kind
                                ),
                                cyrillic=cyrillic,
                            )
                        )
                    if time_confirmation is not None:
                        messages.append(
                            _insulin_time_confirmation(
                                time_confirmation[0],
                                offset_ms=time_confirmation[1],
                                cyrillic=cyrillic,
                            )
                        )
                    if meal is not None:
                        messages.append(
                            _meal_confirmation(
                                meal,
                                replaced=(
                                    "meal"
                                    in replacement_target_kind_by_payload_kind
                                ),
                                cyrillic=cyrillic,
                            )
                        )
                    assistant_message = "\n\n".join(messages) or (
                        "Запись добавлена."
                        if cyrillic
                        else "The entry was recorded."
                    )

            if len(response_events) > 24 or len(deleted_event_ids) > 24:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail="one intake-chat action can affect at most 24 events",
                )

            response = IntakeChatTurnResponse(
                session_id=chat_session_id,
                client_turn_id=client_turn_id,
                assistant_message=assistant_message,
                transcript=transcript,
                outcome=outcome,
                action_id=response_action_id,
                events=response_events,
                deleted_event_ids=deleted_event_ids,
            )
            turn_record = IntakeChatTurnRecord(
                id=str(uuid5(namespace, "turn")),
                session_id=session_id,
                client_turn_id=turn_identity,
                request_hash=request_hash,
                sequence=current_last_turn_sequence + 1,
                occurred_at_ms=occurred_at_ms,
                user_text=full_evidence,
                transcript=transcript,
                photo_count=len(prepared_images),
                assistant_message=assistant_message,
                outcome=outcome,
                action_id=action.id,
                response_json=response.model_dump_json(),
                created_at_ms=now,
            )
            session.add(turn_record)
            chat_record.updated_at_ms = now
            session.delete(active_reservation)
            session.commit()
            return response
        except HTTPException:
            session.rollback()
            raise
        except IntegrityError as error:
            session.rollback()
            raced = session.scalar(
                select(IntakeChatTurnRecord).where(
                    IntakeChatTurnRecord.session_id == session_id,
                    IntakeChatTurnRecord.client_turn_id == turn_identity,
                )
            )
            if raced is not None and raced.request_hash == request_hash:
                return _cached_intake_chat_turn(raced)
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="intake-chat session changed while processing; retry the turn",
            ) from error
        except SQLAlchemyError as error:
            session.rollback()
            logger.warning("intake-chat turn transaction failed", exc_info=True)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="intake-chat turn is temporarily unavailable",
            ) from error

    @router.post(
        "/intake-chat/actions/{action_id}/undo",
        response_model=IntakeChatUndoResponse,
    )
    def undo_intake_chat_action(
        action_id: UUID,
        session: SessionDependency,
    ) -> IntakeChatUndoResponse:
        _begin_immediate(session, "intake-chat action undo")
        action = session.get(IntakeChatActionRecord, str(action_id))
        if action is None:
            session.rollback()
            raise HTTPException(status_code=404, detail="intake-chat action not found")
        if action.intent not in ("create", "replace_last"):
            session.rollback()
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="this intake-chat action cannot be undone",
            )
        if action.undone_at_ms is not None:
            events, deleted_ids = _already_undone_snapshot(session, action)
            response = IntakeChatUndoResponse(
                action_id=action_id,
                outcome="already_undone",
                events=events,
                deleted_event_ids=deleted_ids,
            )
            session.rollback()
            return response

        try:
            chat_record = session.get(IntakeChatSessionRecord, action.session_id)
            if chat_record is None:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="intake-chat action has no parent session",
                )
            now = max(_now_ms(), chat_record.updated_at_ms + 1)
            events, deleted_ids = _undo_action_locked(
                session, action, now_ms=now
            )
            chat_record.updated_at_ms = now
            response = IntakeChatUndoResponse(
                action_id=action_id,
                outcome="undone",
                events=events,
                deleted_event_ids=deleted_ids,
            )
            session.commit()
            return response
        except HTTPException:
            session.rollback()
            raise
        except SQLAlchemyError as error:
            session.rollback()
            logger.warning("intake-chat action undo failed", exc_info=True)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="intake-chat undo is temporarily unavailable",
            ) from error

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
            commit_transaction=False,
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
    application.include_router(create_viewer_session_router())
    application.include_router(create_viewer_router(get_session, _event_response))
    mount_viewer_pwa(application, settings.pwa_dist_path)
    return application


app = create_app()
