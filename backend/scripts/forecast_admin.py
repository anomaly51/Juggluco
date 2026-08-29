"""Local-only administration for frozen, versioned forecast models.

Run from the ``backend`` directory with ``python -m scripts.forecast_admin``.
The command deliberately has no HTTP client and accepts no API token: model training and
activation are operator actions performed against the local SQLite database.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import sys
import time
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterator, Sequence
from uuid import uuid4

from pydantic import BaseModel
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.config import BACKEND_ROOT, Settings
from app.database import Database
from app.forecast import STATIC_DISPLAY_PROTOCOL, ForecastService
from app.forecast_release import (
    FORECAST_RUNTIME_RELEASE_METADATA_KEY,
    normalize_forecast_runtime_release,
)
from app.models import BackendMetadataRecord, ForecastModelRecord


EXPORT_SCHEMA_VERSION = 1
DEFAULT_EXPORT_ROOT = BACKEND_ROOT / "data" / "exports"
DEFAULT_DISPLAY_SOURCE_CHANGE_RETRIES = 2
DEFAULT_DISPLAY_RETRY_DELAY_SECONDS = 2.0
DISPLAY_RETRY_STATE_KEY = "forecast_display_retry_state_v1"

GLUCOSE_COLUMNS = (
    "reading_id",
    "measured_at_ms",
    "glucose_mg_dl",
    "trend_mg_dl_min",
    "sensor_id",
    "sensor_generation",
    "quality",
    "utc_offset_minutes",
    "payload_hash",
    "received_at_ms",
)
INTAKE_COLUMNS = (
    "id",
    "client_event_id",
    "occurred_at_ms",
    "meal_text",
    "carbs_g",
    "portion_g",
    "original_portion_g",
    "original_carbs_g",
    "carbs_source",
    "insulin_units",
    "insulin_type",
    "insulin_name",
    "analysis_id",
    "payload_hash",
    "created_at_ms",
    "updated_at_ms",
    "deleted_at_ms",
    "sync_version",
)
TRAINING_SNAPSHOT_TABLES = {
    "analyses",
    "glucose_readings",
    "intake_events",
    "sync_changes",
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_query_csv(
    connection: sqlite3.Connection,
    path: Path,
    *,
    table: str,
    columns: Sequence[str],
    order_by: str,
) -> int:
    # Table/column identifiers are module constants, never user input.
    query = f"SELECT {', '.join(columns)} FROM {table} ORDER BY {order_by}"
    cursor = connection.execute(query)
    count = 0
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(columns)
        for row in cursor:
            writer.writerow(row)
            count += 1
    return count


def _minimize_training_snapshot(connection: sqlite3.Connection) -> None:
    """Remove unrelated chats/forecast audit state from the private DB copy."""

    connection.execute("PRAGMA foreign_keys=OFF")
    table_names = [
        str(row[0])
        for row in connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    ]
    for table_name in table_names:
        if table_name in TRAINING_SNAPSHOT_TABLES:
            continue
        quoted = '"' + table_name.replace('"', '""') + '"'
        connection.execute(f"DROP TABLE {quoted}")
    # Only analyses referenced by exported intake records are useful to the
    # event parser. Voice/manual transcripts are redundant with intake fields.
    connection.execute(
        "DELETE FROM analyses WHERE id NOT IN "
        "(SELECT analysis_id FROM intake_events WHERE analysis_id IS NOT NULL)"
    )
    connection.execute("UPDATE analyses SET manual_text=NULL, transcription=''")
    connection.commit()
    # Rebuild pages so dropped chat/transcription content is not recoverable from
    # the snapshot freelist.
    connection.execute("VACUUM")


def _default_export_directory() -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return DEFAULT_EXPORT_ROOT / f"{stamp}-manual-training"


def export_snapshot(database_path: Path, output_directory: Path) -> dict[str, Any]:
    """Create one consistent SQLite backup and deterministic CSVs derived from it.

    SQLite's online backup API includes committed WAL pages and gives all exported files one
    transactional source. The caller receives aggregate metadata only; glucose and intake
    values are written to local files and are never returned or printed.
    """

    source = Path(database_path).expanduser().resolve()
    target = Path(output_directory).expanduser().resolve()
    if not source.is_file():
        raise FileNotFoundError(f"database does not exist: {source}")
    if target.exists():
        raise FileExistsError(f"export directory already exists: {target}")

    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(f".{target.name}.partial-{uuid4().hex}")
    partial.mkdir(mode=0o700)
    # chmod is explicit because the host's umask may otherwise make private
    # health exports world-readable (for example 0755/0644 with umask 022).
    partial.chmod(0o700)
    snapshot_path = partial / "training-snapshot.sqlite"
    glucose_csv = partial / "glucose-readings.csv"
    intake_csv = partial / "intake-events.csv"
    try:
        with closing(sqlite3.connect(source)) as source_connection:
            with closing(sqlite3.connect(snapshot_path)) as snapshot_connection:
                source_connection.backup(snapshot_connection)

        with closing(sqlite3.connect(snapshot_path)) as exported:
            _minimize_training_snapshot(exported)
            glucose_count = _write_query_csv(
                exported,
                glucose_csv,
                table="glucose_readings",
                columns=GLUCOSE_COLUMNS,
                order_by="measured_at_ms, reading_id",
            )
            intake_count = _write_query_csv(
                exported,
                intake_csv,
                table="intake_events",
                columns=INTAKE_COLUMNS,
                order_by="occurred_at_ms, id",
            )

        artifact_paths = (snapshot_path, glucose_csv, intake_csv)
        for artifact_path in artifact_paths:
            artifact_path.chmod(0o600)
        manifest: dict[str, Any] = {
            "schema_version": EXPORT_SCHEMA_VERSION,
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
            "counts": {
                "glucose_readings": glucose_count,
                "intake_events": intake_count,
            },
            "files": {
                path.name: {
                    "sha256": _sha256(path),
                    "size_bytes": path.stat().st_size,
                }
                for path in artifact_paths
            },
        }
        manifest_path = partial / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        manifest_path.chmod(0o600)
        partial.rename(target)
        return {
            "output_directory": str(target),
            "glucose_reading_count": glucose_count,
            "intake_event_count": intake_count,
            "manifest": str(target / "manifest.json"),
        }
    except BaseException:
        # `partial` is an exact UUID-suffixed directory created above, never a broad path.
        if partial.exists():
            shutil.rmtree(partial)
        raise


def _jsonable(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json")
    if isinstance(value, ForecastModelRecord):
        return {
            "model_version": value.version,
            "status": value.status,
            "trained_at_ms": value.trained_at_ms,
            "promoted_at_ms": value.promoted_at_ms,
            "sample_count": value.sample_count,
            "decision_reason": value.decision_reason,
        }
    if hasattr(value, "model_dump"):
        return value.model_dump(mode="json")
    if isinstance(value, dict):
        return value
    if value is None or isinstance(value, (str, int, float, bool, list)):
        return value
    return {"result": str(value)}


@contextmanager
def _database_session(path: Path) -> Iterator[Session]:
    database = Database(path)
    try:
        database.create_all()
        with database.session_factory() as session:
            yield session
    finally:
        database.dispose()


def _database_path(configured: str | None) -> Path:
    if configured:
        return Path(configured).expanduser().resolve()
    return Settings.from_env().database_path


def _nonnegative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("value must be greater than or equal to zero")
    return parsed


def _nonnegative_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed) or parsed < 0:
        raise argparse.ArgumentTypeError("value must be a finite nonnegative number")
    return parsed


def _display_candidate_is_accepted(record: ForecastModelRecord) -> bool:
    """Fail closed unless a stored candidate is display-eligible and alert-safe."""

    try:
        parameters = json.loads(record.parameters_json)
        metrics = json.loads(record.metrics_json)
    except (TypeError, json.JSONDecodeError):
        return False
    if not isinstance(parameters, dict) or not isinstance(metrics, dict):
        return False
    artifact = parameters.get("artifact")
    if not isinstance(artifact, dict):
        return False
    evaluation = artifact.get("evaluation")
    approval = artifact.get("approval")
    return bool(
        record.status in {"candidate", "champion", "retired"}
        and artifact.get("accepted") is True
        and isinstance(evaluation, dict)
        and evaluation.get("accepted") in {1, True}
        and evaluation.get("display_only") in {1, True}
        and evaluation.get("prospective") in {0, False}
        and metrics.get("accepted") in {1, True}
        and metrics.get("display_only") in {1, True}
        and metrics.get("exploratory") in {1, True}
        and metrics.get("unbiased_holdout") in {0, False}
        and metrics.get("receipt_causal_validation") in {0, False}
        and metrics.get("prospective") in {0, False}
        and isinstance(approval, dict)
        and approval.get("state") == "exploratory_retrospective_display"
        and approval.get("protocol") == STATIC_DISPLAY_PROTOCOL
        and approval.get("unbiased_holdout") is False
        and approval.get("receipt_causal_validation") is False
        and approval.get("use_scope") == "chart_only_not_for_dosing_or_alerts"
        # Forecast display approval must never imply notification approval.
        and approval.get("alert_approved") is False
    )


def _current_source_revision(
    service: ForecastService, session: Session
) -> tuple[int, ...] | None:
    getter = getattr(service, "_source_revision", None)
    if not callable(getter):
        return None
    try:
        revision = tuple(int(value) for value in getter(session))
    except (TypeError, ValueError):
        return None
    return revision if revision else None


def _stored_source_revision(record: ForecastModelRecord) -> tuple[int, ...] | None:
    try:
        parameters = json.loads(record.parameters_json)
        values = parameters["artifact"]["snapshot"]["source_revision"]
        revision = tuple(int(value) for value in values)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        return None
    return revision if revision else None


def _revisioned_candidate_version(base: str, revision: tuple[int, ...]) -> str:
    digest = hashlib.sha256(
        json.dumps(revision, separators=(",", ":")).encode("ascii")
    ).hexdigest()[:10]
    suffix = f"-r{digest}"
    return f"{base[: 96 - len(suffix)]}{suffix}"


def _display_release_base(release_version: str) -> str:
    """Return the stable candidate prefix for one immutable application image."""

    normalized = normalize_forecast_runtime_release(release_version)
    return f"display-{normalized}"


def _daily_display_candidate_version(
    release_version: str, *, now_utc: datetime | None = None
) -> str:
    instant = now_utc or datetime.now(timezone.utc)
    if instant.tzinfo is None:
        raise ValueError("daily display retry time must be timezone-aware")
    utc_day = instant.astimezone(timezone.utc).strftime("%Y%m%d")
    return f"{_display_release_base(release_version)}-d{utc_day}"


def _release_candidate_day(version: str, release_base: str) -> str | None:
    match = re.fullmatch(
        rf"{re.escape(release_base)}-d(?P<day>[0-9]{{8}})(?:-r[0-9a-f]{{10}})?",
        version,
    )
    return match.group("day") if match is not None else None


def _belongs_to_display_release(version: str, release_base: str) -> bool:
    return bool(
        re.fullmatch(
            rf"{re.escape(release_base)}(?:-r[0-9a-f]{{10}}|-d[0-9]{{8}}(?:-r[0-9a-f]{{10}})?)?",
            version,
        )
    )


def _record_utc_day(record: ForecastModelRecord, release_base: str) -> str:
    encoded_day = _release_candidate_day(record.version, release_base)
    if encoded_day is not None:
        return encoded_day
    timestamp_ms = record.trained_at_ms or record.created_at_ms
    return datetime.fromtimestamp(timestamp_ms / 1000, timezone.utc).strftime(
        "%Y%m%d"
    )


def _claim_daily_display_attempt(
    session: Session, *, release_version: str, utc_day: str
) -> bool:
    """Atomically claim one attempt; an interrupted claim stays visibly failed."""

    session.rollback()
    session.execute(text("BEGIN IMMEDIATE"))
    try:
        marker = session.get(BackendMetadataRecord, DISPLAY_RETRY_STATE_KEY)
        state: Any = None
        if marker is not None:
            try:
                state = json.loads(marker.value_text)
            except (TypeError, json.JSONDecodeError):
                state = None
        same_attempt = bool(
            isinstance(state, dict)
            and state.get("release_version") == release_version
            and state.get("utc_day") == utc_day
        )
        if same_attempt:
            if state.get("state") == "completed":
                session.commit()
                return False
            raise ValueError(
                "current release UTC-day display attempt is already running or "
                "was interrupted; refusing to hide the failed attempt"
            )
        value = json.dumps(
            {
                "release_version": release_version,
                "state": "claimed",
                "utc_day": utc_day,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
        if marker is None:
            session.add(
                BackendMetadataRecord(key=DISPLAY_RETRY_STATE_KEY, value_text=value)
            )
        else:
            marker.value_text = value
        session.commit()
        return True
    except Exception:
        session.rollback()
        raise


def _complete_daily_display_attempt(
    session: Session, *, release_version: str, utc_day: str
) -> None:
    """Mark a claimed attempt complete without masking an interrupted process."""

    session.rollback()
    session.execute(text("BEGIN IMMEDIATE"))
    try:
        marker = session.get(BackendMetadataRecord, DISPLAY_RETRY_STATE_KEY)
        try:
            state = json.loads(marker.value_text) if marker is not None else None
        except (TypeError, json.JSONDecodeError):
            state = None
        if not (
            isinstance(state, dict)
            and state.get("release_version") == release_version
            and state.get("utc_day") == utc_day
            and state.get("state") == "claimed"
        ):
            raise ValueError("daily display attempt claim changed before completion")
        state["state"] = "completed"
        marker.value_text = json.dumps(
            state, separators=(",", ":"), sort_keys=True
        )
        session.commit()
    except Exception:
        session.rollback()
        raise


def _daily_display_attempt_is_completed(
    session: Session, *, release_version: str, utc_day: str
) -> bool:
    marker = session.get(BackendMetadataRecord, DISPLAY_RETRY_STATE_KEY)
    try:
        state = json.loads(marker.value_text) if marker is not None else None
    except (TypeError, json.JSONDecodeError):
        return False
    return bool(
        isinstance(state, dict)
        and state.get("release_version") == release_version
        and state.get("utc_day") == utc_day
        and state.get("state") == "completed"
    )


def _require_runtime_release(session: Session, expected_release: str) -> str:
    expected = normalize_forecast_runtime_release(expected_release)
    current = session.scalar(
        select(BackendMetadataRecord.value_text).where(
            BackendMetadataRecord.key == FORECAST_RUNTIME_RELEASE_METADATA_KEY
        )
    )
    if current != expected:
        raise ValueError(
            "stale forecast job refused: the shared database belongs to runtime "
            f"release {current or 'unregistered'}, not {expected}"
        )
    return expected


def _activate_display_model_fenced(
    service: ForecastService,
    session: Session,
    version: str,
    *,
    expected_runtime_release: str | None,
) -> ForecastModelRecord:
    """Serialize runtime-release validation and activation in one write lock."""

    if expected_runtime_release is None:
        return service.activate_model(session, version)
    session.rollback()
    session.execute(text("BEGIN IMMEDIATE"))
    try:
        _require_runtime_release(session, expected_runtime_release)
        return service.activate_model(session, version)
    except Exception:
        session.rollback()
        raise


def _existing_display_result(
    service: ForecastService,
    session: Session,
    record: ForecastModelRecord,
    *,
    expected_runtime_release: str | None = None,
) -> dict[str, Any]:
    """Resume or safely no-op a previously attempted deterministic version."""

    if record.status == "champion":
        if not _display_candidate_is_accepted(record):
            raise ValueError(
                "existing active version is not an exploratory display-only, "
                "alert-disabled artifact"
            )
        # Re-pin idempotently through the full artifact/checksum/comparator
        # validator; a status string alone is not proof that the model is active.
        activated = _activate_display_model_fenced(
            service,
            session,
            record.version,
            expected_runtime_release=expected_runtime_release,
        )
        return {
            "status": "already_active",
            "promoted": False,
            "model_version": record.version,
            "reason": "Display-only forecast model is already active",
            "attempts": 0,
            "reused_existing": True,
            "activation": _jsonable(activated),
        }
    if record.status != "candidate":
        return {
            "status": "retained",
            "promoted": False,
            "model_version": record.version,
            "reason": (
                f"Existing display bootstrap candidate is {record.status}; "
                "the active model was retained"
            ),
            "attempts": 0,
            "reused_existing": True,
        }
    if not _display_candidate_is_accepted(record):
        raise ValueError(
            "existing candidate is not an exploratory display-only, alert-disabled artifact"
        )
    activated = _activate_display_model_fenced(
        service,
        session,
        record.version,
        expected_runtime_release=expected_runtime_release,
    )
    return {
        "status": "promoted",
        "promoted": True,
        "model_version": activated.version,
        "reason": "Resumed explicit activation of an exploratory display-only candidate",
        "attempts": 0,
        "reused_existing": True,
        "activation": _jsonable(activated),
    }


def deploy_display_model(
    service: ForecastService,
    session: Session,
    *,
    candidate_version: str,
    source_change_retries: int = DEFAULT_DISPLAY_SOURCE_CHANGE_RETRIES,
    retry_delay_seconds: float = DEFAULT_DISPLAY_RETRY_DELAY_SECONDS,
    require_activation: bool = False,
    expected_runtime_release: str | None = None,
) -> dict[str, Any]:
    """Train once for display, retry source races, then explicitly activate.

    The deterministic version makes retries idempotent. Callers may inspect a
    safe retained result while data accrues; strict operator workflows can set
    ``require_activation=True`` when a reject/skip must fail the command.
    """

    if expected_runtime_release is not None:
        _require_runtime_release(session, expected_runtime_release)
    base_candidate_version = candidate_version
    current_revision = _current_source_revision(service, session)
    existing = session.get(ForecastModelRecord, candidate_version)
    if (
        existing is not None
        and existing.status not in {"candidate", "champion"}
        and current_revision is not None
        and _stored_source_revision(existing) != current_revision
    ):
        candidate_version = _revisioned_candidate_version(
            candidate_version, current_revision
        )
        existing = session.get(ForecastModelRecord, candidate_version)
    if existing is not None:
        result = _existing_display_result(
            service,
            session,
            existing,
            expected_runtime_release=expected_runtime_release,
        )
        if require_activation and result.get("status") not in {
            "promoted",
            "already_active",
        }:
            raise ValueError(
                f"display forecast activation required but model was retained: {result['reason']}"
            )
        return result

    attempts = 0
    while True:
        attempts += 1
        trained = service.train_static_model(
            session,
            candidate_version=candidate_version,
            stage_pending=False,
            allow_display_activation=True,
        )
        if trained.promoted:
            raise ValueError("display training activated a model implicitly")

        source_changed = bool(
            trained.status == "skipped"
            and trained.metrics.get("source_revision_changed") == 1
        )
        if source_changed and attempts <= source_change_retries:
            session.expire_all()
            refreshed_revision = _current_source_revision(service, session)
            if refreshed_revision is not None:
                candidate_version = _revisioned_candidate_version(
                    base_candidate_version, refreshed_revision
                )
            if retry_delay_seconds:
                time.sleep(retry_delay_seconds)
            continue

        training_result = _jsonable(trained)
        if trained.status != "accepted":
            retained = {
                "status": "retained",
                "promoted": False,
                "model_version": trained.model_version,
                "reason": trained.reason,
                "attempts": attempts,
                "reused_existing": False,
                "training": training_result,
            }
            if require_activation:
                raise ValueError(
                    "display forecast activation required but training retained the "
                    f"active model: {trained.reason}"
                )
            return retained

        if trained.model_version != candidate_version:
            raise ValueError("accepted display training returned an unexpected model version")
        candidate = session.get(ForecastModelRecord, candidate_version)
        if candidate is None or not _display_candidate_is_accepted(candidate):
            raise ValueError(
                "accepted display training did not persist an alert-disabled candidate"
            )
        activated = _activate_display_model_fenced(
            service,
            session,
            candidate_version,
            expected_runtime_release=expected_runtime_release,
        )
        return {
            "status": "promoted",
            "promoted": True,
            "model_version": activated.version,
            "reason": "Exploratory display-only forecast model activated explicitly",
            "attempts": attempts,
            "reused_existing": False,
            "training": training_result,
            "activation": _jsonable(activated),
        }


def retry_display_model_daily(
    service: ForecastService,
    session: Session,
    *,
    release_version: str,
    source_change_retries: int = DEFAULT_DISPLAY_SOURCE_CHANGE_RETRIES,
    retry_delay_seconds: float = DEFAULT_DISPLAY_RETRY_DELAY_SECONDS,
    now_utc: datetime | None = None,
) -> dict[str, Any]:
    """Run at most one display-only model attempt per release and UTC day.

    A release that already owns an accepted active champion is a permanent
    no-op. A terminal attempt from the same UTC day is also a no-op, so a gate
    rejection is retried only after new receipt-causal data has had until the
    next day to accumulate. Promotion remains centralized in
    :func:`deploy_display_model`; scheduled rejection is an expected success.
    """

    instant = now_utc or datetime.now(timezone.utc)
    if instant.tzinfo is None:
        raise ValueError("daily display retry time must be timezone-aware")
    utc_day = instant.astimezone(timezone.utc).strftime("%Y%m%d")
    release_base = _display_release_base(release_version)
    normalized_release_version = release_base.removeprefix("display-")
    _require_runtime_release(session, normalized_release_version)
    candidate_version = _daily_display_candidate_version(
        normalized_release_version, now_utc=instant
    )
    release_records = [
        record
        for record in session.scalars(select(ForecastModelRecord)).all()
        if _belongs_to_display_release(record.version, release_base)
    ]

    champions = [
        record for record in release_records if record.status == "champion"
    ]
    if champions:
        if len(champions) != 1:
            raise ValueError(
                "current release does not have a unique display champion"
            )
        existing = _existing_display_result(
            service,
            session,
            champions[0],
            expected_runtime_release=normalized_release_version,
        )
        return {
            **existing,
            "release_version": normalized_release_version,
            "utc_day": utc_day,
        }

    prior_activations = [
        record
        for record in release_records
        if record.promoted_at_ms is not None and record.status == "retired"
    ]
    if prior_activations:
        if any(
            not _display_candidate_is_accepted(record)
            for record in prior_activations
        ):
            raise ValueError(
                "current release activation history contains an unsafe display artifact"
            )
        latest = max(
            prior_activations,
            key=lambda record: (int(record.promoted_at_ms or 0), record.version),
        )
        return {
            "status": "retained",
            "promoted": False,
            "model_version": latest.version,
            "release_version": normalized_release_version,
            "utc_day": utc_day,
            "reason": (
                "Current application release activated previously; automatic retry "
                "will not override a later rollback"
            ),
            "attempts": 0,
            "reused_existing": True,
        }

    daily_today_records = [
        record
        for record in release_records
        if _release_candidate_day(record.version, release_base) == utc_day
    ]
    bootstrap_today_records = [
        record
        for record in release_records
        if _release_candidate_day(record.version, release_base) is None
        and _record_utc_day(record, release_base) == utc_day
    ]
    today_records = daily_today_records + bootstrap_today_records
    resumable = [record for record in today_records if record.status == "candidate"]
    if len(resumable) > 1:
        raise ValueError("multiple resumable display candidates exist for one UTC day")
    if resumable:
        raise ValueError(
            "current release UTC-day display candidate was left by an interrupted "
            "attempt; refusing an untracked same-day activation"
        )
    if daily_today_records:
        if not _daily_display_attempt_is_completed(
            session,
            release_version=normalized_release_version,
            utc_day=utc_day,
        ):
            raise ValueError(
                "current release UTC-day display record has no completed attempt "
                "marker; refusing to hide an interrupted job"
            )
    if today_records:
        terminal = max(
            today_records,
            key=lambda record: (
                record.trained_at_ms or record.created_at_ms,
                record.version,
            ),
        )
        return {
            "status": "retained",
            "promoted": False,
            "model_version": terminal.version,
            "release_version": normalized_release_version,
            "utc_day": utc_day,
            "reason": "Current release already completed its UTC-day display attempt",
            "attempts": 0,
            "reused_existing": True,
        }

    if not _claim_daily_display_attempt(
        session,
        release_version=normalized_release_version,
        utc_day=utc_day,
    ):
        return {
            "status": "retained",
            "promoted": False,
            "model_version": candidate_version,
            "release_version": normalized_release_version,
            "utc_day": utc_day,
            "reason": "Current release already claimed its UTC-day display attempt",
            "attempts": 0,
            "reused_existing": True,
        }

    result = deploy_display_model(
        service,
        session,
        candidate_version=candidate_version,
        source_change_retries=source_change_retries,
        retry_delay_seconds=retry_delay_seconds,
        # A development-gate rejection is expected while causal data accrues;
        # the CronJob must remain healthy and try a new deterministic day later.
        require_activation=False,
        expected_runtime_release=normalized_release_version,
    )
    _complete_daily_display_attempt(
        session,
        release_version=normalized_release_version,
        utc_day=utc_day,
    )
    return {
        **result,
        "release_version": normalized_release_version,
        "utc_day": utc_day,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Manage frozen forecast models in the local backend database."
    )
    parser.add_argument(
        "--database",
        help="SQLite database path (defaults to JUGGLUCO_DATABASE_PATH/.env).",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status", help="Show model version and aggregate training state.")

    export = commands.add_parser(
        "export", help="Create a consistent local training snapshot and CSV export."
    )
    export.add_argument(
        "--output",
        help="New output directory (defaults to data/exports/<UTC>-manual-training).",
    )

    train = commands.add_parser(
        "train",
        help=(
            "Freeze one static candidate pending preregistered future evaluation; "
            "does not enable background training."
        ),
    )
    train.add_argument("--data-cutoff-ms", type=int)
    train.add_argument("--candidate-version")

    deploy_display = commands.add_parser(
        "deploy-display",
        help=(
            "One-shot GitOps bootstrap: train an alert-disabled display candidate, "
            "activate it only when development gates accept it, and otherwise "
            "retain the active model."
        ),
    )
    deploy_display.add_argument("--candidate-version", required=True)
    deploy_display.add_argument(
        "--source-change-retries",
        type=_nonnegative_int,
        default=DEFAULT_DISPLAY_SOURCE_CHANGE_RETRIES,
        help="Retries after a concurrent glucose/intake source revision change.",
    )
    deploy_display.add_argument(
        "--retry-delay-seconds",
        type=_nonnegative_float,
        default=DEFAULT_DISPLAY_RETRY_DELAY_SECONDS,
        help="Delay between source-change retries.",
    )
    deploy_display.add_argument(
        "--require-activation",
        action="store_true",
        help=(
            "Fail the command unless this run leaves the requested display model "
            "active; intended for deployment verification."
        ),
    )

    retry_display = commands.add_parser(
        "retry-display-daily",
        help=(
            "Bounded daily retry for the current immutable app release; stops "
            "after an alert-disabled display champion activates."
        ),
    )
    retry_display.add_argument(
        "--release-version",
        help="Immutable app/image version (defaults to APP_VERSION).",
    )
    retry_display.add_argument(
        "--source-change-retries",
        type=_nonnegative_int,
        default=DEFAULT_DISPLAY_SOURCE_CHANGE_RETRIES,
        help="Bounded retries inside today's attempt after a source-data race.",
    )
    retry_display.add_argument(
        "--retry-delay-seconds",
        type=_nonnegative_float,
        default=DEFAULT_DISPLAY_RETRY_DELAY_SECONDS,
        help="Delay between source-change retries.",
    )

    evaluate = commands.add_parser(
        "evaluate",
        help=(
            "Make the one-shot decision for a pending candidate on its frozen "
            "future-day cohort; never retrains or activates."
        ),
    )
    evaluate.add_argument("version")

    activate = commands.add_parser(
        "activate", help="Pin an existing compatible model version for inference."
    )
    activate.add_argument("version")

    rollback = commands.add_parser(
        "rollback", help="Pin the prior model, or the supplied existing version."
    )
    rollback.add_argument("version", nargs="?")
    return parser


def execute(
    argv: Sequence[str] | None = None,
    *,
    service_factory: Callable[[], ForecastService] = ForecastService,
) -> dict[str, Any]:
    args = build_parser().parse_args(argv)
    database_path = _database_path(args.database)
    if args.command == "export":
        output = Path(args.output).expanduser() if args.output else _default_export_directory()
        return {"command": "export", **export_snapshot(database_path, output)}

    service = service_factory()
    with _database_session(database_path) as session:
        if args.command == "status":
            result = service.status(session)
        elif args.command == "train":
            result = service.train_static_model(
                session,
                data_cutoff_ms=args.data_cutoff_ms,
                candidate_version=args.candidate_version,
                stage_pending=True,
            )
        elif args.command == "deploy-display":
            result = deploy_display_model(
                service,
                session,
                candidate_version=args.candidate_version,
                source_change_retries=args.source_change_retries,
                retry_delay_seconds=args.retry_delay_seconds,
                require_activation=args.require_activation,
                expected_runtime_release=os.getenv("APP_VERSION") or None,
            )
        elif args.command == "retry-display-daily":
            result = retry_display_model_daily(
                service,
                session,
                release_version=args.release_version or os.getenv("APP_VERSION", ""),
                source_change_retries=args.source_change_retries,
                retry_delay_seconds=args.retry_delay_seconds,
            )
        elif args.command == "evaluate":
            result = service.evaluate_static_candidate(session, args.version)
        elif args.command == "activate":
            result = service.activate_model(session, args.version)
        elif args.command == "rollback":
            result = service.rollback_model(session, version=args.version)
        else:  # pragma: no cover - argparse enforces the command choices.
            raise AssertionError(f"unsupported command: {args.command}")
    return {"command": args.command, "result": _jsonable(result)}


def main(argv: Sequence[str] | None = None) -> int:
    try:
        result = execute(argv)
    except (FileNotFoundError, FileExistsError, ValueError, sqlite3.Error) as error:
        print(f"forecast admin failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
