from __future__ import annotations

import time
from typing import Literal
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    field_validator,
    model_validator,
)


class AnalysisItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=200)
    portion_g: float = Field(ge=0, le=10_000)
    carbs_g: float = Field(ge=0, le=1_000)
    estimated_protein_g: float | None = Field(default=None, ge=0, le=1_000)
    estimated_fat_g: float | None = Field(default=None, ge=0, le=1_000)
    estimated_fiber_g: float | None = Field(default=None, ge=0, le=1_000)


class MealAnalysis(BaseModel):
    model_config = ConfigDict(extra="forbid")

    meal_name: str = Field(min_length=1, max_length=200)
    meal_description: str = Field(max_length=2_000)
    estimated_carbs_g: float = Field(ge=0, le=1_000)
    carbs_low_g: float = Field(ge=0, le=1_000)
    carbs_high_g: float = Field(ge=0, le=1_000)
    confidence: float = Field(ge=0, le=1)
    absorption_speed: float | None = Field(default=None, ge=0, le=1)
    absorption_peak_minutes: int | None = Field(default=None, ge=5, le=720)
    absorption_duration_minutes: int | None = Field(default=None, ge=15, le=1_440)
    absorption_confidence: float | None = Field(default=None, ge=0, le=1)
    estimated_protein_g: float | None = Field(default=None, ge=0, le=1_000)
    estimated_fat_g: float | None = Field(default=None, ge=0, le=1_000)
    estimated_fiber_g: float | None = Field(default=None, ge=0, le=1_000)
    items: list[AnalysisItem] = Field(max_length=24)
    assumptions: list[str] = Field(max_length=24)
    warnings: list[str] = Field(max_length=24)

    @field_validator("assumptions", "warnings")
    @classmethod
    def validate_text_list(cls, values: list[str]) -> list[str]:
        cleaned: list[str] = []
        for value in values:
            value = value.strip()
            if not value:
                continue
            if len(value) > 500:
                raise ValueError("list entries must not exceed 500 characters")
            cleaned.append(value)
        return cleaned

    @model_validator(mode="after")
    def validate_range(self) -> "MealAnalysis":
        if not self.carbs_low_g <= self.estimated_carbs_g <= self.carbs_high_g:
            raise ValueError(
                "carbohydrate estimate must be inside the low/high interval"
            )
        return self


class AnalysisResponse(MealAnalysis):
    analysis_id: UUID
    transcription: str = Field(default="", max_length=8_000)


class TranscriptionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(max_length=8_000)


class IntakeCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    client_event_id: UUID
    occurred_at_ms: int = Field(gt=0)
    meal_text: str | None = Field(default=None, max_length=4_000)
    carbs_g: float | None = Field(default=None, ge=0, le=1_000)
    portion_g: float | None = Field(default=None, gt=0, le=10_000)
    carbs_source: str | None = Field(default=None, max_length=64)
    insulin_units: float | None = Field(default=None, gt=0, le=500)
    insulin_type: str | None = Field(default=None, max_length=80)
    insulin_name: str | None = Field(default=None, max_length=120)
    analysis_id: UUID | None = None

    @field_validator(
        "meal_text", "carbs_source", "insulin_type", "insulin_name", mode="before"
    )
    @classmethod
    def blank_to_none(cls, value):
        if isinstance(value, str):
            value = value.strip()
            return value or None
        return value

    @model_validator(mode="after")
    def validate_event(self) -> "IntakeCreate":
        now_ms = int(time.time() * 1_000)
        if self.occurred_at_ms > now_ms + 10 * 60 * 1_000:
            raise ValueError("occurred_at_ms cannot be more than 10 minutes in the future")
        has_meal = self.meal_text is not None or self.carbs_g is not None
        has_insulin = any(
            value is not None
            for value in (self.insulin_units, self.insulin_type, self.insulin_name)
        )
        if not has_meal and not has_insulin:
            raise ValueError("an intake needs a meal or insulin")
        if has_meal and has_insulin:
            raise ValueError("meal and insulin must be separate events")
        if self.portion_g is not None and not has_meal:
            raise ValueError("portion_g is only valid for a meal event")
        if self.carbs_g is not None and self.carbs_source is None:
            raise ValueError("carbs_source is required when carbs_g is present")
        if self.carbs_g is None and self.carbs_source is not None:
            raise ValueError("carbs_source requires carbs_g")
        if self.carbs_source == "ai_estimate" and self.analysis_id is None:
            raise ValueError("analysis_id is required for ai_estimate carbohydrates")
        if self.analysis_id is not None and self.carbs_source != "ai_estimate":
            raise ValueError("analysis_id is only valid for ai_estimate carbohydrates")
        if has_insulin:
            if self.insulin_units is None:
                raise ValueError("insulin_units is required for an insulin event")
            allowed_pair = {
                ("rapid", "NovoRapid"),
                ("long", "Tresiba"),
            }
            if (self.insulin_type, self.insulin_name) not in allowed_pair:
                raise ValueError(
                    "insulin must be NovoRapid/rapid or Tresiba/long"
                )
        return self


class ManualMealEventCreate(BaseModel):
    """Structured meal command that remains usable without an AI session."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    client_event_id: UUID
    occurred_at_ms: int = Field(gt=0)
    meal_text: str = Field(min_length=1, max_length=4_000)
    carbs_g: float = Field(ge=0, le=1_000)
    portion_g: float | None = Field(default=None, gt=0, le=10_000)

    @field_validator("occurred_at_ms")
    @classmethod
    def validate_occurred_at_ms(cls, value: int) -> int:
        if value > int(time.time() * 1_000) + 10 * 60 * 1_000:
            raise ValueError("occurred_at_ms cannot be more than 10 minutes in the future")
        return value


class InsulinEventCreate(BaseModel):
    """Public command for the independent insulin-only write path."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    client_event_id: UUID
    occurred_at_ms: int = Field(gt=0)
    insulin_units: float = Field(gt=0, le=500)
    insulin_name: Literal["NovoRapid", "Tresiba"]

    @field_validator("occurred_at_ms")
    @classmethod
    def validate_occurred_at_ms(cls, value: int) -> int:
        if value > int(time.time() * 1_000) + 10 * 60 * 1_000:
            raise ValueError("occurred_at_ms cannot be more than 10 minutes in the future")
        return value


class IntakeEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    client_event_id: UUID
    occurred_at_ms: int
    meal_text: str | None
    carbs_g: float | None
    portion_g: float | None = Field(default=None, ge=0, le=10_000)
    original_portion_g: float | None = Field(default=None, ge=0, le=10_000)
    original_carbs_g: float | None = Field(default=None, ge=0, le=1_000)
    carbs_source: str | None
    insulin_units: float | None
    insulin_type: str | None
    insulin_name: str | None
    analysis_id: UUID | None
    ai_confidence: float = Field(ge=0, le=1)
    # Meal-level absorption estimates are persisted in the immutable linked
    # analysis and projected onto every event response.  Keeping them on the
    # sync object lets clients render two meals at the same timestamp without
    # collapsing their different response profiles into one marker.
    absorption_speed: float | None = Field(default=None, ge=0, le=1)
    absorption_peak_minutes: int | None = Field(default=None, ge=5, le=720)
    absorption_duration_minutes: int | None = Field(default=None, ge=15, le=1_440)
    absorption_confidence: float | None = Field(default=None, ge=0, le=1)
    created_at_ms: int
    updated_at_ms: int
    deleted_at_ms: int | None
    deleted: bool
    sync_version: int


class MealPortionUpdate(BaseModel):
    """Idempotent correction of how much of an analyzed meal was consumed."""

    model_config = ConfigDict(extra="forbid")

    portion_g: float = Field(ge=0, le=10_000)


class IntakeListResponse(BaseModel):
    items: list[IntakeEvent]
    next_sync_version: int


class MealChatSessionCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    client_event_id: UUID
    occurred_at_ms: int = Field(gt=0)

    @field_validator("occurred_at_ms")
    @classmethod
    def validate_occurred_at(cls, value: int) -> int:
        if value > int(time.time() * 1_000) + 10 * 60 * 1_000:
            raise ValueError("occurred_at_ms cannot be more than 10 minutes in the future")
        return value


class MealChatSessionTimeUpdate(BaseModel):
    """Move an active meal draft without changing its identity or proposal."""

    model_config = ConfigDict(extra="forbid")

    occurred_at_ms: int = Field(gt=0)

    @field_validator("occurred_at_ms")
    @classmethod
    def validate_occurred_at(cls, value: int) -> int:
        if value > int(time.time() * 1_000) + 10 * 60 * 1_000:
            raise ValueError("occurred_at_ms cannot be more than 10 minutes in the future")
        return value


class MealChatProposal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    meal_name: str = Field(min_length=1, max_length=200)
    meal_description: str = Field(max_length=2_000)
    total_portion_g: float = Field(ge=0, le=10_000)
    items: list[AnalysisItem] = Field(max_length=24)
    estimated_carbs_g: float = Field(ge=0, le=1_000)
    carbs_low_g: float = Field(ge=0, le=1_000)
    carbs_high_g: float = Field(ge=0, le=1_000)
    confidence: float = Field(ge=0, le=1)
    absorption_speed: float | None = Field(default=None, ge=0, le=1)
    absorption_peak_minutes: int | None = Field(default=None, ge=5, le=720)
    absorption_duration_minutes: int | None = Field(default=None, ge=15, le=1_440)
    absorption_confidence: float | None = Field(default=None, ge=0, le=1)
    estimated_protein_g: float | None = Field(default=None, ge=0, le=1_000)
    estimated_fat_g: float | None = Field(default=None, ge=0, le=1_000)
    estimated_fiber_g: float | None = Field(default=None, ge=0, le=1_000)
    warnings: list[str] = Field(max_length=24)

    @field_validator("warnings")
    @classmethod
    def validate_warnings(cls, values: list[str]) -> list[str]:
        cleaned: list[str] = []
        for value in values:
            value = value.strip()
            if not value:
                continue
            if len(value) > 500:
                raise ValueError("warnings must not exceed 500 characters")
            cleaned.append(value)
        return cleaned

    @model_validator(mode="after")
    def validate_range(self) -> "MealChatProposal":
        if not self.carbs_low_g <= self.estimated_carbs_g <= self.carbs_high_g:
            raise ValueError(
                "carbohydrate estimate must be inside the low/high interval"
            )
        return self


class MealChatModelResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    assistant_message: str = Field(min_length=1, max_length=4_000)
    proposal: MealChatProposal | None
    ready_to_confirm: bool

    @model_validator(mode="after")
    def validate_readiness(self) -> "MealChatModelResult":
        if self.ready_to_confirm and self.proposal is None:
            raise ValueError("a proposal is required when ready_to_confirm is true")
        return self


class MealChatMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    role: Literal["user", "assistant"]
    text: str = Field(max_length=16_000)
    photo_count: int = Field(ge=0)
    had_audio: bool
    created_at_ms: int


class MealChatSessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    client_event_id: UUID
    occurred_at_ms: int
    status: Literal["active", "confirmed"]
    created_at_ms: int
    updated_at_ms: int
    messages: list[MealChatMessage]
    proposal: MealChatProposal | None
    ready_to_confirm: bool
    confirmed_intake_id: UUID | None


class MealChatTurnResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    session_id: UUID
    assistant_message: MealChatMessage
    proposal: MealChatProposal | None
    ready_to_confirm: bool


class IntakeChatSessionCreate(BaseModel):
    """Idempotent identity supplied by one Android composer instance."""

    model_config = ConfigDict(extra="forbid")

    client_session_id: UUID


class IntakeChatSessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    client_session_id: UUID
    created_at_ms: int
    updated_at_ms: int


class IntakeChatModelResult(BaseModel):
    """Strict provider output for the non-insulin part of a unified turn.

    Insulin is excluded from this meal contract.  Deterministic parsing and the
    separate evidence-only semantic contract are both independently validated
    by the orchestrator; neither model may calculate or recommend a dose.
    """

    model_config = ConfigDict(extra="forbid")

    intent: Literal["create", "replace_last", "undo_last", "clarify"]
    assistant_message: str = Field(min_length=1, max_length=4_000)
    meal: MealChatProposal | None
    meal_event_status: Literal[
        "completed",
        "planned",
        "question",
        "negated",
        "uncertain",
        "not_applicable",
    ] = "not_applicable"
    meal_actor: Literal["self", "other", "unknown"] = "unknown"
    meal_action_evidence: str | None = Field(
        default=None,
        min_length=1,
        max_length=160,
    )
    meal_food_evidence: str | None = Field(
        default=None,
        min_length=1,
        max_length=500,
    )
    meal_semantic_confidence: float = Field(
        default=0,
        ge=0,
        le=1,
        allow_inf_nan=False,
    )

    @model_validator(mode="after")
    def validate_intent_payload(self) -> "IntakeChatModelResult":
        for field_name in ("meal_action_evidence", "meal_food_evidence"):
            value = getattr(self, field_name)
            if value is None:
                continue
            normalized = value.strip()
            if not normalized or "\n" in normalized or "\r" in normalized:
                raise ValueError("meal semantic evidence must be one nonempty line")
            setattr(self, field_name, normalized)
        if (self.meal_action_evidence is None) != (
            self.meal_food_evidence is None
        ):
            raise ValueError("meal action and food evidence must be paired")
        writes_meal = self.intent in ("create", "replace_last")
        if writes_meal and self.meal is None:
            raise ValueError("create and replace_last require a meal")
        if not writes_meal and self.meal is not None:
            raise ValueError("clarify and undo_last cannot include a meal")
        return self


class IntakeChatControlResult(BaseModel):
    """Non-mutating classification for free-form conversation controls."""

    model_config = ConfigDict(extra="forbid")

    intent: Literal["revise_last", "none"]
    assistant_message: str = Field(min_length=1, max_length=300)

    @model_validator(mode="after")
    def validate_revision_question(self) -> "IntakeChatControlResult":
        message = self.assistant_message.strip()
        if "\n" in message or "\r" in message:
            raise ValueError("control assistant_message must be one line")
        if self.intent == "revise_last" and (
            not message.endswith("?") or message.count("?") != 1
        ):
            raise ValueError("revise_last requires exactly one short question")
        self.assistant_message = message
        return self


class IntakeChatInsulinSemanticResult(BaseModel):
    """Strict semantic extraction of a self-reported insulin fact.

    This is evidence extraction, never dose advice.  Evidence fragments must be
    copied verbatim from the current user turn and are independently checked by
    the orchestrator before any mutation is authorized.
    """

    model_config = ConfigDict(extra="forbid")

    intent: Literal[
        "create",
        "replace_last",
        "revise_last",
        "delete_last",
        "none",
    ]
    event_status: Literal[
        "completed",
        "planned",
        "question",
        "negated",
        "uncertain",
        "not_applicable",
    ]
    actor: Literal["self", "other", "unknown"]
    context_scope: Literal["none", "recent_single_insulin"]
    insulin_name: Literal["NovoRapid", "Tresiba"] | None
    insulin_type: Literal["rapid", "long"] | None
    insulin_units: float | None = Field(
        default=None,
        gt=0,
        le=500,
        allow_inf_nan=False,
    )
    action_evidence: str | None = Field(default=None, min_length=1, max_length=120)
    product_evidence: str | None = Field(default=None, min_length=1, max_length=80)
    dose_evidence: str | None = Field(default=None, min_length=1, max_length=80)
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)

    @model_validator(mode="after")
    def validate_semantic_payload(self) -> "IntakeChatInsulinSemanticResult":
        for field_name in (
            "action_evidence",
            "product_evidence",
            "dose_evidence",
        ):
            value = getattr(self, field_name)
            if value is None:
                continue
            normalized = value.strip()
            if not normalized or "\n" in normalized or "\r" in normalized:
                raise ValueError("semantic evidence must be one nonempty line")
            setattr(self, field_name, normalized)

        if self.intent == "none":
            # Some schema-constrained providers harmlessly echo an evidence
            # fragment while still choosing the non-mutating `none` intent.
            # Canonicalize that result to the sole server meaning of `none`:
            # no extracted payload and therefore no possible write authority.
            self.insulin_name = None
            self.insulin_type = None
            self.insulin_units = None
            self.action_evidence = None
            self.product_evidence = None
            self.dose_evidence = None
            return self

        if self.intent in ("revise_last", "delete_last"):
            if (
                self.event_status != "not_applicable"
                or self.actor != "self"
                or self.context_scope != "recent_single_insulin"
                or self.action_evidence is None
                or any(
                    value is not None
                    for value in (
                        self.insulin_name,
                        self.insulin_type,
                        self.insulin_units,
                        self.product_evidence,
                        self.dose_evidence,
                    )
                )
            ):
                raise ValueError(
                    "revision/deletion requires only recent-context evidence"
                )
            return self

        if (
            self.event_status != "completed"
            or self.actor != "self"
            or self.action_evidence is None
            or self.insulin_units is None
            or self.dose_evidence is None
        ):
            raise ValueError("create and replace_last require dose evidence")
        product_values = (
            self.insulin_name,
            self.insulin_type,
            self.product_evidence,
        )
        if self.intent == "create":
            if (
                self.context_scope != "none"
                or any(value is None for value in product_values)
            ):
                raise ValueError("create requires explicit product evidence")
        elif self.context_scope != "recent_single_insulin":
            raise ValueError("replace_last requires recent single-insulin context")
        if self.intent == "replace_last" and any(
            value is None for value in product_values
        ) != all(value is None for value in product_values):
            raise ValueError("replacement product fields must be all present or all null")
        if (
            self.insulin_name == "NovoRapid" and self.insulin_type != "rapid"
        ) or (
            self.insulin_name == "Tresiba" and self.insulin_type != "long"
        ):
            raise ValueError("insulin product and type do not match")
        return self


class IntakeChatReservedPendingProduct(BaseModel):
    model_config = ConfigDict(extra="forbid")

    insulin_name: Literal["NovoRapid", "Tresiba"]
    insulin_type: Literal["rapid", "long"]
    cyrillic: bool


class IntakeChatReservedRevisionContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    pending_action_id: UUID
    target_action_id: UUID | None
    cyrillic: bool
    expired: bool
    single_insulin_name: Literal["NovoRapid", "Tresiba"] | None
    single_insulin_type: Literal["rapid", "long"] | None


class IntakeChatReservedInsulinContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    target_action_id: UUID
    target_event_id: UUID
    target_updated_at_ms: int = Field(gt=0)
    insulin_name: Literal["NovoRapid", "Tresiba"]
    insulin_type: Literal["rapid", "long"]
    insulin_units: float = Field(gt=0, le=500, allow_inf_nan=False)
    cyrillic: bool


class IntakeChatReservedMealContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    target_action_id: UUID
    target_event_id: UUID
    target_updated_at_ms: int = Field(gt=0)
    meal_text: str | None = Field(default=None, max_length=4_000)
    portion_g: float | None = Field(default=None, ge=0, le=10_000)
    carbs_g: float | None = Field(default=None, ge=0, le=1_000)
    cyrillic: bool


class IntakeChatReservedDeleteContext(IntakeChatReservedInsulinContext):
    target_action_sequence: int = Field(gt=0)


class IntakeChatReservedVisibleEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: UUID
    updated_at_ms: int = Field(gt=0)


class IntakeChatReservedVisibleAction(BaseModel):
    model_config = ConfigDict(extra="forbid")

    target_action_id: UUID
    target_action_sequence: int = Field(gt=0)
    events: list[IntakeChatReservedVisibleEvent] = Field(min_length=1, max_length=24)


class IntakeChatReservedReplacementEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    kind: Literal["meal", "rapid", "long"]
    event_id: UUID
    updated_at_ms: int = Field(gt=0)


class IntakeChatTurnReservationSnapshot(BaseModel):
    """Strict internal snapshot that prevents retry target rebinding."""

    model_config = ConfigDict(extra="forbid")

    version: Literal[1]
    expected_session_updated_at: int = Field(gt=0)
    expected_last_turn_sequence: int = Field(ge=0)
    context_created_at_ms: int = Field(gt=0)
    pending_product: IntakeChatReservedPendingProduct | None
    pending_revision: IntakeChatReservedRevisionContext | None
    implicit_insulin: IntakeChatReservedInsulinContext | None
    implicit_meal: IntakeChatReservedMealContext | None = None
    semantic_delete: IntakeChatReservedDeleteContext | None
    visible_action: IntakeChatReservedVisibleAction | None
    replacement_events: list[IntakeChatReservedReplacementEvent] = Field(
        max_length=3
    )


class IntakeChatTurnResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    session_id: UUID
    client_turn_id: UUID
    assistant_message: str = Field(min_length=1, max_length=4_000)
    transcript: str = Field(default="", max_length=8_000)
    outcome: Literal["applied", "clarification", "undone", "no_change"]
    action_id: UUID | None
    events: list[IntakeEvent] = Field(max_length=24)
    deleted_event_ids: list[UUID] = Field(max_length=24)


class IntakeChatUndoResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action_id: UUID
    outcome: Literal["undone", "already_undone"]
    events: list[IntakeEvent] = Field(max_length=24)
    deleted_event_ids: list[UUID] = Field(max_length=24)


class HealthResponse(BaseModel):
    status: str
    api_version: str
    version: str
    database: str
    auth_configured: bool
    viewer_auth_configured: bool = False
    ai_configured: bool


class GlucoseReadingCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    reading_id: str = Field(min_length=1, max_length=160)
    measured_at_ms: int = Field(gt=0)
    glucose_mg_dl: float = Field(ge=20, le=600, allow_inf_nan=False)
    trend_mg_dl_min: float | None = Field(
        default=None, ge=-30, le=30, allow_inf_nan=False
    )
    sensor_id: str | None = Field(default=None, max_length=160)
    sensor_generation: str | None = Field(default=None, max_length=80)
    quality: float | None = Field(default=None, ge=0, le=1, allow_inf_nan=False)
    # Prefer the offset that applied when this sample was measured.  The
    # enclosing batch value remains a compatibility fallback for older clients.
    utc_offset_minutes: int | None = Field(
        default=None, ge=-14 * 60, le=14 * 60
    )

    @field_validator("sensor_id", "sensor_generation", mode="before")
    @classmethod
    def blank_sensor_values_to_none(cls, value):
        if isinstance(value, str):
            value = value.strip()
            return value or None
        return value


class GlucoseReadingsCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    readings: list[GlucoseReadingCreate] = Field(max_length=20_000)
    utc_offset_minutes: int | None = Field(default=None, ge=-14 * 60, le=14 * 60)
    backfill_complete: bool | None = None

    @model_validator(mode="after")
    def validate_batch(self) -> "GlucoseReadingsCreate":
        if not self.readings and self.backfill_complete is not True:
            raise ValueError("readings can be empty only when backfill_complete is true")
        now_ms = int(time.time() * 1_000)
        seen: dict[str, GlucoseReadingCreate] = {}
        for reading in self.readings:
            if reading.measured_at_ms > now_ms + 10 * 60 * 1_000:
                raise ValueError(
                    "measured_at_ms cannot be more than 10 minutes in the future"
                )
            previous = seen.get(reading.reading_id)
            material = (
                reading.measured_at_ms,
                reading.glucose_mg_dl,
                reading.trend_mg_dl_min,
            )
            previous_material = (
                previous.measured_at_ms,
                previous.glucose_mg_dl,
                previous.trend_mg_dl_min,
            ) if previous is not None else None
            canonical_timestamp_id = reading.reading_id == f"cgm-{reading.measured_at_ms}"
            if (
                previous is not None
                and previous_material != material
                and not canonical_timestamp_id
            ):
                raise ValueError(
                    "the same reading_id cannot contain different data in one batch"
                )
            seen[reading.reading_id] = reading
        return self


class GlucoseReadingsResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    inserted: int = Field(ge=0)
    unchanged: int = Field(ge=0)
    updated: int = Field(ge=0)
    latest_reading_at_ms: int | None
    forecast_generated: bool


class ViewerSessionCreate(BaseModel):
    """One-time browser login payload; the secret is never serialized back."""

    model_config = ConfigDict(extra="forbid")

    token: SecretStr


class ViewerSessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    authenticated: Literal[True] = True
    access_mode: Literal["session", "public"]
    expires_at_ms: int | None = Field(default=None, gt=0)


class ViewerTargetRange(BaseModel):
    """The application's fixed display target in both supported units."""

    model_config = ConfigDict(extra="forbid")

    low_mg_dl: Literal[75.6] = 75.6
    high_mg_dl: Literal[162.0] = 162.0
    low_mmol_l: Literal[4.2] = 4.2
    high_mmol_l: Literal[9.0] = 9.0


class ViewerGlucoseReading(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reading_id: str = Field(min_length=1, max_length=160)
    measured_at_ms: int = Field(gt=0)
    glucose_mg_dl: float = Field(ge=20, le=600, allow_inf_nan=False)
    trend_mg_dl_min: float | None = Field(
        default=None, ge=-30, le=30, allow_inf_nan=False
    )
    sensor_id: str | None = Field(default=None, max_length=160)
    sensor_generation: str | None = Field(default=None, max_length=80)
    quality: float | None = Field(default=None, ge=0, le=1, allow_inf_nan=False)
    utc_offset_minutes: int | None = Field(
        default=None, ge=-14 * 60, le=14 * 60
    )
    received_at_ms: int = Field(gt=0)


class ViewerCurrentGlucose(ViewerGlucoseReading):
    age_ms: int = Field(ge=0)
    is_stale: bool


class ViewerIntakeEvent(BaseModel):
    """Minimized, active-only event projection for a read-only timeline."""

    model_config = ConfigDict(extra="forbid")

    id: UUID
    kind: Literal["meal", "rapid", "long"]
    occurred_at_ms: int = Field(gt=0)
    meal_text: str | None = Field(default=None, max_length=4_000)
    carbs_g: float | None = Field(default=None, ge=0, le=1_000)
    portion_g: float | None = Field(default=None, ge=0, le=10_000)
    original_portion_g: float | None = Field(default=None, ge=0, le=10_000)
    original_carbs_g: float | None = Field(default=None, ge=0, le=1_000)
    carbs_source: str | None = Field(default=None, max_length=64)
    insulin_units: float | None = Field(default=None, gt=0, le=500)
    insulin_type: Literal["rapid", "long"] | None = None
    insulin_name: str | None = Field(default=None, max_length=120)
    ai_confidence: float = Field(ge=0, le=1)
    absorption_speed: float | None = Field(default=None, ge=0, le=1)
    absorption_peak_minutes: int | None = Field(default=None, ge=5, le=720)
    absorption_duration_minutes: int | None = Field(
        default=None, ge=15, le=1_440
    )
    absorption_confidence: float | None = Field(default=None, ge=0, le=1)
    updated_at_ms: int = Field(gt=0)


class ViewerInsulinEvent(BaseModel):
    """Identifier-free insulin projection safe for a shared viewer link."""

    model_config = ConfigDict(extra="forbid")

    occurred_at_ms: int = Field(gt=0)
    insulin_units: float = Field(gt=0, le=500, allow_inf_nan=False)
    insulin_type: Literal["rapid", "long"]
    insulin_name: str | None = Field(default=None, max_length=120)


class ViewerGlucosePage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[ViewerGlucoseReading]
    next_cursor: str | None
    has_more: bool
    order: Literal["newest_first"] = "newest_first"


class ViewerIntakePage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[ViewerIntakeEvent]
    next_cursor: str | None
    has_more: bool
    order: Literal["newest_first"] = "newest_first"


class ForecastPoint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    at_ms: int
    median_mg_dl: float = Field(allow_inf_nan=False)
    low_mg_dl: float = Field(allow_inf_nan=False)
    high_mg_dl: float = Field(allow_inf_nan=False)


class ForecastActivityPoint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    at_ms: int
    minutes_from_anchor: int = Field(ge=0, le=120)
    contribution_mg_dl: float = Field(ge=-600, le=600, allow_inf_nan=False)
    activity: float = Field(ge=0, le=1, allow_inf_nan=False)


class ForecastActivity(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: UUID
    kind: Literal["meal", "rapid", "long"]
    label: str = Field(min_length=1, max_length=200)
    start_ms: int
    peak_ms: int
    end_ms: int
    strength: float = Field(ge=0, le=1)
    confidence: float = Field(ge=0, le=1)
    absorption_speed: float | None = Field(default=None, ge=0, le=1)
    amount: float = Field(gt=0, le=1_000, allow_inf_nan=False)
    unit: Literal["g", "U"]
    profile_source: Literal[
        "ai_estimate", "nutrient_estimate", "personalized", "population_prior"
    ]
    profile_confidence: float = Field(ge=0, le=1, allow_inf_nan=False)
    points: list[ForecastActivityPoint] = Field(min_length=25, max_length=25)
    # Additive v2 explanation metadata.  Defaults keep immutable forecast runs
    # written by older backend versions readable while new responses always fill
    # these fields.  ``peak_ms``/``end_ms`` above remain the compact legacy
    # representatives; the ranges below explicitly carry timing uncertainty.
    onset_ms: int | None = None
    peak_low_ms: int | None = None
    peak_high_ms: int | None = None
    end_low_ms: int | None = None
    end_high_ms: int | None = None
    attribution_confidence: float | None = Field(
        default=None, ge=0, le=1, allow_inf_nan=False
    )
    identifiability: Literal[
        "low", "medium", "high", "not_identifiable"
    ] = "not_identifiable"
    action_model: Literal[
        "population_prior",
        "personalized_kernel",
        "contextual_counterfactual",
        "basal_depot",
    ] = "population_prior"
    overlap_count: int = Field(default=0, ge=0)


class ForecastAlertCrossing(BaseModel):
    """A bounded target-crossing signal, never a treatment recommendation."""

    model_config = ConfigDict(extra="forbid")

    direction: Literal["low", "high"]
    # These are qualitative evidence tiers. They are deliberately not exposed
    # as probabilities because the forecast bands are marginal intervals, not
    # calibrated path-crossing probabilities.
    evidence: Literal["possible", "likely"]
    crossing_at_ms: int = Field(gt=0)
    lead_minutes: int = Field(ge=5, le=60, multiple_of=5)
    predicted_median_mg_dl: float = Field(
        ge=20, le=600, allow_inf_nan=False
    )
    interval_edge_mg_dl: float = Field(ge=20, le=600, allow_inf_nan=False)


class ForecastAlertAssessment(BaseModel):
    """Read-only forecast assessment used by the phone's notification UI."""

    model_config = ConfigDict(extra="forbid")

    monitoring_status: Literal["unavailable", "shadow", "eligible"]
    delivery_eligible: bool
    target_low_mg_dl: Literal[75.6] = 75.6
    target_high_mg_dl: Literal[162.0] = 162.0
    target_low_mmol_l: Literal[4.2] = 4.2
    target_high_mmol_l: Literal[9.0] = 9.0
    suppressed_reasons: list[str]
    # Evidence-specific candidates are additive so policy layers can apply
    # different horizons without losing an earlier interval-only crossing or a
    # later median crossing. ``low``/``high`` remain the conservative legacy
    # summaries (likely when present, otherwise possible).
    low_possible: ForecastAlertCrossing | None = None
    low_likely: ForecastAlertCrossing | None = None
    high_possible: ForecastAlertCrossing | None = None
    high_likely: ForecastAlertCrossing | None = None
    low: ForecastAlertCrossing | None = None
    high: ForecastAlertCrossing | None = None

    @model_validator(mode="after")
    def validate_delivery_state(self) -> "ForecastAlertAssessment":
        eligible = self.monitoring_status == "eligible"
        if self.delivery_eligible != eligible:
            raise ValueError(
                "delivery_eligible must match monitoring_status=eligible"
            )
        crossings = (
            self.low_possible,
            self.low_likely,
            self.high_possible,
            self.high_likely,
            self.low,
            self.high,
        )
        if self.monitoring_status == "unavailable" and any(
            crossing is not None for crossing in crossings
        ):
            raise ValueError("unavailable assessments cannot expose crossings")
        if eligible and self.suppressed_reasons:
            raise ValueError("eligible assessments cannot be suppressed")
        if not eligible and not self.suppressed_reasons:
            raise ValueError("non-eligible assessments require a suppression reason")
        return self


class ForecastCurrentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal[
        "no_data", "stale", "low_confidence", "cold_start", "learning", "ready"
    ]
    generated_at_ms: int
    based_on_reading_at_ms: int | None
    based_on_glucose_mg_dl: float | None = Field(
        default=None, ge=20, le=600, allow_inf_nan=False
    )
    horizon_minutes: Literal[120] = 120
    model_version: str
    confidence: float = Field(ge=0, le=1)
    points: list[ForecastPoint]
    activities: list[ForecastActivity]
    conditional_notice: str
    # Nullable keeps deserialization compatible with immutable responses made
    # before the predictive-alert contract existed. New backend responses fill
    # it for every status, including explicit fail-closed states.
    alert_assessment: ForecastAlertAssessment | None = None


class ViewerSnapshot(BaseModel):
    """Bounded initial payload for a companion dashboard and timeline."""

    model_config = ConfigDict(extra="forbid")

    api_version: Literal["v1"] = "v1"
    stream_id: str = Field(min_length=1, max_length=64)
    glucose_revision: int = Field(ge=0)
    server_time_ms: int = Field(gt=0)
    from_ms: int = Field(ge=0)
    to_ms: int = Field(gt=0)
    target_range: ViewerTargetRange
    current_glucose: ViewerCurrentGlucose | None
    glucose_history: list[ViewerGlucoseReading]
    glucose_history_order: Literal["oldest_first"] = "oldest_first"
    glucose_history_truncated: bool
    intake_events: list[ViewerIntakeEvent]
    intake_events_order: Literal["oldest_first"] = "oldest_first"
    intake_events_truncated: bool
    insulin_events: list[ViewerInsulinEvent]
    insulin_events_order: Literal["oldest_first"] = "oldest_first"
    insulin_events_truncated: bool
    forecast: ForecastCurrentResponse


class ForecastTrainingStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")

    state: Literal[
        "not_started",
        "insufficient_data",
        "candidate_rejected",
        "trained",
        "manual_only",
        "frozen",
    ]
    mode: Literal["manual"] = "manual"
    automatic_enabled: Literal[False] = False
    data_changed_since_training: bool = False
    last_trained_at_ms: int | None
    next_eligible_at_ms: int | None
    sample_count: int = Field(ge=0)
    minimum_samples: int = Field(ge=1)


class ForecastDataStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reading_count: int = Field(ge=0)
    days_covered: float = Field(ge=0)
    coverage_density: float = Field(ge=0, le=1)
    confirmed_meals: int = Field(ge=0)
    rapid_events: int = Field(ge=0)
    long_events: int = Field(ge=0)
    last_reading_at_ms: int | None


class ForecastAccuracyStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scored_points: int = Field(ge=0)
    mae_30_mg_dl: float | None
    mae_60_mg_dl: float | None
    mae_120_mg_dl: float | None
    mae_7d_mg_dl: float | None
    mae_30d_mg_dl: float | None
    coverage_80: float | None


class ForecastCapabilityStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")

    personal_model_active: bool
    ready_for_display: bool
    occupied_5m_bins: int = Field(ge=0)
    training_days_required: float = Field(gt=0)
    ready_days_required: float = Field(gt=0)
    meal_response_samples: int = Field(ge=0)
    rapid_response_samples: int = Field(ge=0)
    long_response_samples: int = Field(ge=0)
    meal_profile_confidence: float = Field(ge=0, le=1)
    rapid_profile_confidence: float = Field(ge=0, le=1)
    long_profile_confidence: float = Field(ge=0, le=1)


class ForecastStatusResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal[
        "no_data", "stale", "low_confidence", "cold_start", "learning", "ready"
    ]
    server_instance_id: UUID
    model_version: str
    training: ForecastTrainingStatus
    data: ForecastDataStatus
    accuracy: ForecastAccuracyStatus
    capabilities: ForecastCapabilityStatus


class ForecastTrainResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal[
        "skipped", "pending", "inconclusive", "rejected", "accepted", "promoted"
    ]
    promoted: bool
    model_version: str
    reason: str
    sample_count: int = Field(ge=0)
    metrics: dict[str, float | int | None]


AI_ANALYSIS_JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "meal_name": {"type": "string", "minLength": 1, "maxLength": 200},
        "meal_description": {"type": "string", "maxLength": 2000},
        "estimated_carbs_g": {"type": "number", "minimum": 0, "maximum": 1000},
        "carbs_low_g": {"type": "number", "minimum": 0, "maximum": 1000},
        "carbs_high_g": {"type": "number", "minimum": 0, "maximum": 1000},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "items": {
            "type": "array",
            "maxItems": 24,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string", "minLength": 1, "maxLength": 200},
                    "portion_g": {"type": "number", "minimum": 0, "maximum": 10000},
                    "carbs_g": {"type": "number", "minimum": 0, "maximum": 1000},
                    "estimated_protein_g": {
                        "anyOf": [
                            {"type": "number", "minimum": 0, "maximum": 1000},
                            {"type": "null"},
                        ]
                    },
                    "estimated_fat_g": {
                        "anyOf": [
                            {"type": "number", "minimum": 0, "maximum": 1000},
                            {"type": "null"},
                        ]
                    },
                    "estimated_fiber_g": {
                        "anyOf": [
                            {"type": "number", "minimum": 0, "maximum": 1000},
                            {"type": "null"},
                        ]
                    },
                },
                "required": [
                    "name",
                    "portion_g",
                    "carbs_g",
                    "estimated_protein_g",
                    "estimated_fat_g",
                    "estimated_fiber_g",
                ],
            },
        },
        "absorption_speed": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1},
                {"type": "null"},
            ]
        },
        "absorption_peak_minutes": {
            "anyOf": [
                {"type": "integer", "minimum": 5, "maximum": 720},
                {"type": "null"},
            ]
        },
        "absorption_duration_minutes": {
            "anyOf": [
                {"type": "integer", "minimum": 15, "maximum": 1440},
                {"type": "null"},
            ]
        },
        "absorption_confidence": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1},
                {"type": "null"},
            ]
        },
        "estimated_protein_g": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1000},
                {"type": "null"},
            ]
        },
        "estimated_fat_g": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1000},
                {"type": "null"},
            ]
        },
        "estimated_fiber_g": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1000},
                {"type": "null"},
            ]
        },
        "assumptions": {
            "type": "array",
            "maxItems": 24,
            "items": {"type": "string", "maxLength": 500},
        },
        "warnings": {
            "type": "array",
            "maxItems": 24,
            "items": {"type": "string", "maxLength": 500},
        },
    },
    "required": [
        "meal_name",
        "meal_description",
        "estimated_carbs_g",
        "carbs_low_g",
        "carbs_high_g",
        "confidence",
        "absorption_speed",
        "absorption_peak_minutes",
        "absorption_duration_minutes",
        "absorption_confidence",
        "estimated_protein_g",
        "estimated_fat_g",
        "estimated_fiber_g",
        "items",
        "assumptions",
        "warnings",
    ],
}


_MEAL_CHAT_PROPOSAL_JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "meal_name": {"type": "string", "minLength": 1, "maxLength": 200},
        "meal_description": {"type": "string", "maxLength": 2000},
        "total_portion_g": {"type": "number", "minimum": 0, "maximum": 10000},
        "items": {
            "type": "array",
            "maxItems": 24,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "name": {"type": "string", "minLength": 1, "maxLength": 200},
                    "portion_g": {"type": "number", "minimum": 0, "maximum": 10000},
                    "carbs_g": {"type": "number", "minimum": 0, "maximum": 1000},
                    "estimated_protein_g": {
                        "anyOf": [
                            {"type": "number", "minimum": 0, "maximum": 1000},
                            {"type": "null"},
                        ]
                    },
                    "estimated_fat_g": {
                        "anyOf": [
                            {"type": "number", "minimum": 0, "maximum": 1000},
                            {"type": "null"},
                        ]
                    },
                    "estimated_fiber_g": {
                        "anyOf": [
                            {"type": "number", "minimum": 0, "maximum": 1000},
                            {"type": "null"},
                        ]
                    },
                },
                "required": [
                    "name",
                    "portion_g",
                    "carbs_g",
                    "estimated_protein_g",
                    "estimated_fat_g",
                    "estimated_fiber_g",
                ],
            },
        },
        "estimated_carbs_g": {"type": "number", "minimum": 0, "maximum": 1000},
        "carbs_low_g": {"type": "number", "minimum": 0, "maximum": 1000},
        "carbs_high_g": {"type": "number", "minimum": 0, "maximum": 1000},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "absorption_speed": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1},
                {"type": "null"},
            ]
        },
        "absorption_peak_minutes": {
            "anyOf": [
                {"type": "integer", "minimum": 5, "maximum": 720},
                {"type": "null"},
            ]
        },
        "absorption_duration_minutes": {
            "anyOf": [
                {"type": "integer", "minimum": 15, "maximum": 1440},
                {"type": "null"},
            ]
        },
        "absorption_confidence": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1},
                {"type": "null"},
            ]
        },
        "estimated_protein_g": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1000},
                {"type": "null"},
            ]
        },
        "estimated_fat_g": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1000},
                {"type": "null"},
            ]
        },
        "estimated_fiber_g": {
            "anyOf": [
                {"type": "number", "minimum": 0, "maximum": 1000},
                {"type": "null"},
            ]
        },
        "warnings": {
            "type": "array",
            "maxItems": 24,
            "items": {"type": "string", "maxLength": 500},
        },
    },
    "required": [
        "meal_name",
        "meal_description",
        "total_portion_g",
        "items",
        "estimated_carbs_g",
        "carbs_low_g",
        "carbs_high_g",
        "confidence",
        "absorption_speed",
        "absorption_peak_minutes",
        "absorption_duration_minutes",
        "absorption_confidence",
        "estimated_protein_g",
        "estimated_fat_g",
        "estimated_fiber_g",
        "warnings",
    ],
}


MEAL_CHAT_JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "assistant_message": {
            "type": "string",
            "minLength": 1,
            "maxLength": 4000,
        },
        "proposal": {
            "anyOf": [
                _MEAL_CHAT_PROPOSAL_JSON_SCHEMA,
                {"type": "null"},
            ]
        },
        "ready_to_confirm": {"type": "boolean"},
    },
    "required": ["assistant_message", "proposal", "ready_to_confirm"],
}


INTAKE_CHAT_JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "intent": {
            "type": "string",
            "enum": ["create", "replace_last", "undo_last", "clarify"],
        },
        "assistant_message": {
            "type": "string",
            "minLength": 1,
            "maxLength": 4000,
        },
        "meal": {
            "anyOf": [
                _MEAL_CHAT_PROPOSAL_JSON_SCHEMA,
                {"type": "null"},
            ]
        },
        "meal_event_status": {
            "type": "string",
            "enum": [
                "completed",
                "planned",
                "question",
                "negated",
                "uncertain",
                "not_applicable",
            ],
        },
        "meal_actor": {
            "type": "string",
            "enum": ["self", "other", "unknown"],
        },
        "meal_action_evidence": {
            "anyOf": [
                {"type": "string", "minLength": 1, "maxLength": 160},
                {"type": "null"},
            ]
        },
        "meal_food_evidence": {
            "anyOf": [
                {"type": "string", "minLength": 1, "maxLength": 500},
                {"type": "null"},
            ]
        },
        "meal_semantic_confidence": {
            "type": "number",
            "minimum": 0,
            "maximum": 1,
        },
    },
    "required": [
        "intent",
        "assistant_message",
        "meal",
        "meal_event_status",
        "meal_actor",
        "meal_action_evidence",
        "meal_food_evidence",
        "meal_semantic_confidence",
    ],
}


INTAKE_CHAT_CONTROL_JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "intent": {
            "type": "string",
            "enum": ["revise_last", "none"],
        },
        "assistant_message": {
            "type": "string",
            "minLength": 1,
            "maxLength": 300,
        },
    },
    "required": ["intent", "assistant_message"],
}


INTAKE_CHAT_INSULIN_SEMANTIC_JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "intent": {
            "type": "string",
            "description": (
                "create/replace_last require a non-null numeric insulin_units; "
                "revise_last is forbidden when an exact replacement quantity is present; "
                "revise_last/delete_last carry action_evidence only; none carries no payload"
            ),
            "enum": [
                "create",
                "replace_last",
                "revise_last",
                "delete_last",
                "none",
            ],
        },
        "event_status": {
            "type": "string",
            "enum": [
                "completed",
                "planned",
                "question",
                "negated",
                "uncertain",
                "not_applicable",
            ],
        },
        "actor": {
            "type": "string",
            "enum": ["self", "other", "unknown"],
        },
        "context_scope": {
            "type": "string",
            "enum": ["none", "recent_single_insulin"],
        },
        "insulin_name": {
            "anyOf": [
                {"type": "string", "enum": ["NovoRapid", "Tresiba"]},
                {"type": "null"},
            ]
        },
        "insulin_type": {
            "anyOf": [
                {"type": "string", "enum": ["rapid", "long"]},
                {"type": "null"},
            ]
        },
        "insulin_units": {
            "description": (
                "Required non-null number for create/replace_last; null for all other intents"
            ),
            "anyOf": [
                {
                    "type": "number",
                    "exclusiveMinimum": 0,
                    "maximum": 500,
                },
                {"type": "null"},
            ]
        },
        "action_evidence": {
            "anyOf": [
                {"type": "string", "minLength": 1, "maxLength": 120},
                {"type": "null"},
            ]
        },
        "product_evidence": {
            "anyOf": [
                {"type": "string", "minLength": 1, "maxLength": 80},
                {"type": "null"},
            ]
        },
        "dose_evidence": {
            "description": (
                "Exact transcript dose required with non-null insulin_units for create/replace_last"
            ),
            "anyOf": [
                {"type": "string", "minLength": 1, "maxLength": 80},
                {"type": "null"},
            ]
        },
        "confidence": {
            "type": "number",
            "minimum": 0,
            "maximum": 1,
        },
    },
    "required": [
        "intent",
        "event_status",
        "actor",
        "context_scope",
        "insulin_name",
        "insulin_type",
        "insulin_units",
        "action_evidence",
        "product_evidence",
        "dose_evidence",
        "confidence",
    ],
}
