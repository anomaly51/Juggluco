from __future__ import annotations

import time
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


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


class HealthResponse(BaseModel):
    status: str
    api_version: str
    database: str
    auth_configured: bool
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


class ForecastCurrentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal[
        "no_data", "stale", "low_confidence", "cold_start", "learning", "ready"
    ]
    generated_at_ms: int
    based_on_reading_at_ms: int | None
    horizon_minutes: Literal[120] = 120
    model_version: str
    confidence: float = Field(ge=0, le=1)
    points: list[ForecastPoint]
    activities: list[ForecastActivity]
    conditional_notice: str


class ForecastTrainingStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")

    state: Literal[
        "not_started", "insufficient_data", "candidate_rejected", "trained"
    ]
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

    status: Literal["skipped", "rejected", "promoted"]
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
