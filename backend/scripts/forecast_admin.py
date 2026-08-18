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
import shutil
import sqlite3
import sys
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterator, Sequence
from uuid import uuid4

from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import BACKEND_ROOT, Settings
from app.database import Database
from app.forecast import ForecastService
from app.models import ForecastModelRecord


EXPORT_SCHEMA_VERSION = 1
DEFAULT_EXPORT_ROOT = BACKEND_ROOT / "data" / "exports"

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
        "train", help="Train one static candidate; does not enable background training."
    )
    train.add_argument("--data-cutoff-ms", type=int)
    train.add_argument("--candidate-version")

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
            )
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
