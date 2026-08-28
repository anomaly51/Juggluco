from __future__ import annotations

from sqlalchemy import Float, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from .database import Base


class AnalysisRecord(Base):
    __tablename__ = "analyses"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    model: Mapped[str] = mapped_column(String(160), nullable=False)
    manual_text: Mapped[str | None] = mapped_column(Text)
    transcription: Mapped[str] = mapped_column(Text, nullable=False, default="")
    result_json: Mapped[str] = mapped_column(Text, nullable=False)


class IntakeEventRecord(Base):
    __tablename__ = "intake_events"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    client_event_id: Mapped[str] = mapped_column(String(36), nullable=False, unique=True)
    occurred_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    meal_text: Mapped[str | None] = mapped_column(Text)
    carbs_g: Mapped[float | None] = mapped_column(Float)
    # Actual consumed mass. The immutable linked analysis retains the originally
    # estimated full portion and carbohydrate amount, so later edits can always
    # be recalculated from the same baseline instead of compounding rounding.
    portion_g: Mapped[float | None] = mapped_column(Float)
    # Manual meals have no linked immutable AI analysis. Persist their initial
    # full-portion baseline so later consumed-portion edits never compound.
    original_portion_g: Mapped[float | None] = mapped_column(Float)
    original_carbs_g: Mapped[float | None] = mapped_column(Float)
    carbs_source: Mapped[str | None] = mapped_column(String(64))
    insulin_units: Mapped[float | None] = mapped_column(Float)
    insulin_type: Mapped[str | None] = mapped_column(String(80))
    insulin_name: Mapped[str | None] = mapped_column(String(120))
    analysis_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("analyses.id", ondelete="SET NULL")
    )
    payload_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    deleted_at_ms: Mapped[int | None] = mapped_column(Integer)
    sync_version: Mapped[int] = mapped_column(Integer, nullable=False, index=True)

    __table_args__ = (
        Index("ix_intake_events_occurred_at_ms", "occurred_at_ms"),
    )


class SyncChangeRecord(Base):
    __tablename__ = "sync_changes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    event_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("intake_events.id", ondelete="CASCADE"), nullable=False
    )
    operation: Mapped[str] = mapped_column(String(16), nullable=False)
    changed_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)


class MealChatSessionRecord(Base):
    __tablename__ = "meal_chat_sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    client_event_id: Mapped[str] = mapped_column(
        String(36), nullable=False, unique=True
    )
    occurred_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="active")
    latest_analysis_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("analyses.id", ondelete="SET NULL")
    )
    ready_to_confirm: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    confirmed_intake_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("intake_events.id", ondelete="SET NULL")
    )
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        Index("ix_meal_chat_sessions_updated_at_ms", "updated_at_ms"),
    )


class MealChatMessageRecord(Base):
    __tablename__ = "meal_chat_messages"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    session_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("meal_chat_sessions.id", ondelete="CASCADE"),
        nullable=False,
    )
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    photo_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    had_audio: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    analysis_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("analyses.id", ondelete="SET NULL")
    )
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        UniqueConstraint("session_id", "sequence", name="uq_meal_chat_message_sequence"),
        Index("ix_meal_chat_messages_session_id", "session_id"),
    )


class IntakeChatSessionRecord(Base):
    __tablename__ = "intake_chat_sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    client_session_id: Mapped[str] = mapped_column(
        String(36), nullable=False, unique=True
    )
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        Index("ix_intake_chat_sessions_updated_at_ms", "updated_at_ms"),
    )


class IntakeChatTurnReservationRecord(Base):
    """Durable pre-provider binding for one idempotent chat request."""

    __tablename__ = "intake_chat_turn_reservations"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    session_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("intake_chat_sessions.id", ondelete="CASCADE"),
        nullable=False,
    )
    client_turn_id: Mapped[str] = mapped_column(String(36), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    context_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        UniqueConstraint(
            "session_id",
            "client_turn_id",
            name="uq_intake_chat_turn_reservation_client",
        ),
        Index("ix_intake_chat_turn_reservations_session", "session_id"),
    )


class IntakeChatActionRecord(Base):
    """One reversible, atomically-applied set of intake mutations."""

    __tablename__ = "intake_chat_actions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    session_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("intake_chat_sessions.id", ondelete="CASCADE"),
        nullable=False,
    )
    intent: Mapped[str] = mapped_column(String(24), nullable=False)
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    undone_at_ms: Mapped[int | None] = mapped_column(Integer)

    __table_args__ = (
        UniqueConstraint(
            "session_id", "sequence", name="uq_intake_chat_action_sequence"
        ),
        Index("ix_intake_chat_actions_session_sequence", "session_id", "sequence"),
    )


class IntakeChatActionEventRecord(Base):
    """Forward mutation membership used to calculate an exact inverse."""

    __tablename__ = "intake_chat_action_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    action_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("intake_chat_actions.id", ondelete="CASCADE"),
        nullable=False,
    )
    event_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("intake_events.id", ondelete="CASCADE"),
        nullable=False,
    )
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    operation: Mapped[str] = mapped_column(String(16), nullable=False)

    __table_args__ = (
        UniqueConstraint(
            "action_id", "event_id", name="uq_intake_chat_action_event"
        ),
        UniqueConstraint(
            "action_id", "sequence", name="uq_intake_chat_action_event_sequence"
        ),
        Index("ix_intake_chat_action_events_action", "action_id"),
    )


class IntakeChatTurnRecord(Base):
    """Durable response cache and privacy-preserving conversation history."""

    __tablename__ = "intake_chat_turns"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    session_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("intake_chat_sessions.id", ondelete="CASCADE"),
        nullable=False,
    )
    client_turn_id: Mapped[str] = mapped_column(String(36), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    sequence: Mapped[int] = mapped_column(Integer, nullable=False)
    occurred_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    user_text: Mapped[str] = mapped_column(Text, nullable=False)
    transcript: Mapped[str] = mapped_column(Text, nullable=False, default="")
    photo_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    assistant_message: Mapped[str] = mapped_column(Text, nullable=False)
    outcome: Mapped[str] = mapped_column(String(24), nullable=False)
    action_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("intake_chat_actions.id", ondelete="SET NULL")
    )
    response_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        UniqueConstraint(
            "session_id", "client_turn_id", name="uq_intake_chat_turn_client"
        ),
        UniqueConstraint(
            "session_id", "sequence", name="uq_intake_chat_turn_sequence"
        ),
        Index("ix_intake_chat_turns_session", "session_id"),
    )


class GlucoseReadingRecord(Base):
    """A source reading, keyed by the stable identifier supplied by the phone.

    The payload hash makes retries harmless while still rejecting an accidental reuse of
    an identifier for different sensor data.
    """

    __tablename__ = "glucose_readings"

    reading_id: Mapped[str] = mapped_column(String(160), primary_key=True)
    measured_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    glucose_mg_dl: Mapped[float] = mapped_column(Float, nullable=False)
    trend_mg_dl_min: Mapped[float | None] = mapped_column(Float)
    sensor_id: Mapped[str | None] = mapped_column(String(160))
    sensor_generation: Mapped[str | None] = mapped_column(String(80))
    quality: Mapped[float | None] = mapped_column(Float)
    utc_offset_minutes: Mapped[int | None] = mapped_column(Integer)
    payload_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    received_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        Index("ix_glucose_readings_time_id", "measured_at_ms", "reading_id"),
    )


class ForecastModelRecord(Base):
    __tablename__ = "forecast_models"

    version: Mapped[str] = mapped_column(String(96), primary_key=True)
    status: Mapped[str] = mapped_column(String(24), nullable=False, index=True)
    architecture: Mapped[str] = mapped_column(String(80), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    trained_at_ms: Mapped[int | None] = mapped_column(Integer)
    promoted_at_ms: Mapped[int | None] = mapped_column(Integer)
    training_cutoff_ms: Mapped[int | None] = mapped_column(Integer)
    sample_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    parameters_json: Mapped[str] = mapped_column(Text, nullable=False)
    metrics_json: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    decision_reason: Mapped[str | None] = mapped_column(Text)


class ForecastRunRecord(Base):
    """Immutable snapshot of a forecast and the exact causal input boundary."""

    __tablename__ = "forecast_runs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    generated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    based_on_reading_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    model_version: Mapped[str] = mapped_column(
        String(96), ForeignKey("forecast_models.version", ondelete="RESTRICT"), nullable=False
    )
    horizon_minutes: Mapped[int] = mapped_column(Integer, nullable=False, default=120)
    confidence: Mapped[float] = mapped_column(Float, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    conditional_notice: Mapped[str] = mapped_column(Text, nullable=False)
    input_hash: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    activities_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")

    __table_args__ = (
        Index(
            "ix_forecast_runs_current",
            "based_on_reading_at_ms",
            "generated_at_ms",
        ),
        Index("ix_forecast_runs_model_generated", "model_version", "generated_at_ms"),
    )


class ForecastPointRecord(Base):
    __tablename__ = "forecast_points"

    run_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("forecast_runs.id", ondelete="CASCADE"), primary_key=True
    )
    step_minutes: Mapped[int] = mapped_column(Integer, primary_key=True)
    at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    median_mg_dl: Mapped[float] = mapped_column(Float, nullable=False)
    low_mg_dl: Mapped[float] = mapped_column(Float, nullable=False)
    high_mg_dl: Mapped[float] = mapped_column(Float, nullable=False)


class ForecastScoreRecord(Base):
    # v2 adds model scoping. Keeping a versioned derived table lets create_all()
    # upgrade databases made by an earlier forecast preview without touching CGM
    # readings or intake history.
    __tablename__ = "forecast_scores_v2"

    run_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("forecast_runs.id", ondelete="CASCADE"), primary_key=True
    )
    step_minutes: Mapped[int] = mapped_column(Integer, primary_key=True)
    model_version: Mapped[str] = mapped_column(
        String(96), ForeignKey("forecast_models.version", ondelete="RESTRICT"), nullable=False
    )
    forecast_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    reading_id: Mapped[str] = mapped_column(
        String(160), ForeignKey("glucose_readings.reading_id", ondelete="RESTRICT"), nullable=False
    )
    actual_mg_dl: Mapped[float] = mapped_column(Float, nullable=False)
    residual_mg_dl: Mapped[float] = mapped_column(Float, nullable=False)
    absolute_error_mg_dl: Mapped[float] = mapped_column(Float, nullable=False)
    squared_error: Mapped[float] = mapped_column(Float, nullable=False)
    inside_interval: Mapped[int] = mapped_column(Integer, nullable=False)
    scored_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        Index(
            "ix_forecast_scores_model_step_scored",
            "model_version",
            "step_minutes",
            "scored_at_ms",
        ),
    )


class ForecastCalibrationRecord(Base):
    __tablename__ = "forecast_calibration_v2"

    model_version: Mapped[str] = mapped_column(
        String(96),
        ForeignKey("forecast_models.version", ondelete="CASCADE"),
        primary_key=True,
    )
    step_minutes: Mapped[int] = mapped_column(Integer, primary_key=True)
    residual_bias_mg_dl: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    residual_variance: Mapped[float] = mapped_column(Float, nullable=False, default=400.0)
    sample_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False)


class ForecastMaintenanceRecord(Base):
    __tablename__ = "forecast_maintenance"

    key: Mapped[str] = mapped_column(String(80), primary_key=True)
    value_ms: Mapped[int] = mapped_column(Integer, nullable=False)


class BackendMetadataRecord(Base):
    __tablename__ = "backend_metadata"

    key: Mapped[str] = mapped_column(String(80), primary_key=True)
    value_text: Mapped[str] = mapped_column(Text, nullable=False)
