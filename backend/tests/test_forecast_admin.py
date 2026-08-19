from __future__ import annotations

import csv
import hashlib
import json
import os
import sqlite3
import stat
from pathlib import Path

from app.database import Database
from app.models import ForecastModelRecord, GlucoseReadingRecord, IntakeEventRecord
from app.schemas import ForecastTrainingStatus, GlucoseReadingsResponse
from scripts import forecast_admin


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _seed_private_source(database_path: Path) -> None:
    database = Database(database_path)
    database.create_all()
    with database.session_factory() as session:
        session.add(
            GlucoseReadingRecord(
                reading_id="private-reading-id",
                measured_at_ms=1_700_000_000_000,
                glucose_mg_dl=237.5,
                trend_mg_dl_min=1.25,
                sensor_id="private-sensor",
                sensor_generation="test",
                quality=0.9,
                utc_offset_minutes=120,
                payload_hash="a" * 64,
                received_at_ms=1_700_000_000_500,
            )
        )
        session.add(
            IntakeEventRecord(
                id="00000000-0000-0000-0000-000000000001",
                client_event_id="00000000-0000-0000-0000-000000000002",
                occurred_at_ms=1_700_000_000_000,
                meal_text="PRIVATE_MEAL_TEXT",
                carbs_g=48.0,
                portion_g=250.0,
                original_portion_g=250.0,
                original_carbs_g=48.0,
                carbs_source="manual",
                insulin_units=None,
                insulin_type=None,
                insulin_name=None,
                analysis_id=None,
                payload_hash="b" * 64,
                created_at_ms=1_700_000_000_500,
                updated_at_ms=1_700_000_000_500,
                deleted_at_ms=None,
                sync_version=1,
            )
        )
        session.commit()
    database.dispose()


def test_export_uses_consistent_snapshot_and_hash_manifest(tmp_path):
    source = tmp_path / "source.sqlite"
    destination = tmp_path / "export"
    _seed_private_source(source)

    summary = forecast_admin.export_snapshot(source, destination)

    assert summary["glucose_reading_count"] == 1
    assert summary["intake_event_count"] == 1
    with sqlite3.connect(destination / "training-snapshot.sqlite") as connection:
        assert connection.execute("SELECT count(*) FROM glucose_readings").fetchone()[0] == 1
        assert connection.execute("SELECT count(*) FROM intake_events").fetchone()[0] == 1
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        assert forecast_admin.TRAINING_SNAPSHOT_TABLES <= tables
        assert "meal_chat_messages" not in tables
        assert "meal_chat_sessions" not in tables
        assert "forecast_runs" not in tables

    with (destination / "glucose-readings.csv").open(
        encoding="utf-8", newline=""
    ) as stream:
        glucose_rows = list(csv.DictReader(stream))
    with (destination / "intake-events.csv").open(
        encoding="utf-8", newline=""
    ) as stream:
        intake_rows = list(csv.DictReader(stream))
    assert glucose_rows[0]["glucose_mg_dl"] == "237.5"
    assert intake_rows[0]["meal_text"] == "PRIVATE_MEAL_TEXT"

    manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["counts"] == {"glucose_readings": 1, "intake_events": 1}
    for name in (
        "training-snapshot.sqlite",
        "glucose-readings.csv",
        "intake-events.csv",
    ):
        assert manifest["files"][name]["sha256"] == _sha256(destination / name)
    if os.name != "nt":
        assert stat.S_IMODE(destination.stat().st_mode) == 0o700
        for name in (
            "training-snapshot.sqlite",
            "glucose-readings.csv",
            "intake-events.csv",
            "manifest.json",
        ):
            assert stat.S_IMODE((destination / name).stat().st_mode) == 0o600


def test_export_cli_never_prints_raw_health_values(tmp_path, capsys):
    source = tmp_path / "source.sqlite"
    destination = tmp_path / "export"
    _seed_private_source(source)

    exit_code = forecast_admin.main(
        ["--database", str(source), "export", "--output", str(destination)]
    )

    output = capsys.readouterr()
    assert exit_code == 0
    assert "PRIVATE_MEAL_TEXT" not in output.out
    assert "237.5" not in output.out
    assert output.err == ""


def test_admin_commands_bind_only_to_explicit_service_methods(tmp_path):
    database_path = tmp_path / "admin.sqlite"

    class FakeService:
        def __init__(self):
            self.calls = []

        def status(self, session):
            self.calls.append(("status",))
            return {"model_version": "static-v1"}

        def train_static_model(
            self,
            session,
            data_cutoff_ms=None,
            candidate_version=None,
            stage_pending=True,
        ):
            self.calls.append(
                ("train", data_cutoff_ms, candidate_version, stage_pending)
            )
            return {"model_version": candidate_version, "state": "candidate"}

        def evaluate_static_candidate(self, session, version):
            self.calls.append(("evaluate", version))
            return {"model_version": version, "state": "candidate"}

        def activate_model(self, session, version):
            self.calls.append(("activate", version))
            return {"model_version": version, "state": "frozen"}

        def rollback_model(self, session, version=None):
            self.calls.append(("rollback", version))
            return {"model_version": version or "previous-static"}

    service = FakeService()
    factory = lambda: service

    forecast_admin.execute(
        ["--database", str(database_path), "status"], service_factory=factory
    )
    forecast_admin.execute(
        [
            "--database",
            str(database_path),
            "train",
            "--data-cutoff-ms",
            "1700000000000",
            "--candidate-version",
            "candidate-static-v2",
        ],
        service_factory=factory,
    )
    forecast_admin.execute(
        ["--database", str(database_path), "evaluate", "candidate-static-v2"],
        service_factory=factory,
    )
    forecast_admin.execute(
        ["--database", str(database_path), "activate", "candidate-static-v2"],
        service_factory=factory,
    )
    forecast_admin.execute(
        ["--database", str(database_path), "rollback", "static-v1"],
        service_factory=factory,
    )

    assert service.calls == [
        ("status",),
        ("train", 1_700_000_000_000, "candidate-static-v2", True),
        ("evaluate", "candidate-static-v2"),
        ("activate", "candidate-static-v2"),
        ("rollback", "static-v1"),
    ]


def test_admin_serializes_activation_record_without_parameters():
    record = ForecastModelRecord(
        version="static-approved",
        status="champion",
        architecture="test",
        created_at_ms=1,
        trained_at_ms=2,
        promoted_at_ms=3,
        training_cutoff_ms=1,
        sample_count=42,
        parameters_json='{"secret_weight":123}',
        metrics_json="{}",
        decision_reason="approved",
    )

    assert forecast_admin._jsonable(record) == {
        "model_version": "static-approved",
        "status": "champion",
        "trained_at_ms": 2,
        "promoted_at_ms": 3,
        "sample_count": 42,
        "decision_reason": "approved",
    }


def test_training_status_exposes_manual_frozen_contract():
    value = ForecastTrainingStatus(
        state="frozen",
        last_trained_at_ms=1_700_000_000_000,
        next_eligible_at_ms=None,
        sample_count=500,
        minimum_samples=48,
        data_changed_since_training=True,
    )

    assert value.mode == "manual"
    assert value.automatic_enabled is False
    assert value.data_changed_since_training is True


def test_mobile_api_has_no_training_route_and_ingest_has_no_training_hook(
    client, auth_headers
):
    assert "/v1/forecast/train" not in {
        route.path for route in client.app.routes if hasattr(route, "path")
    }

    class IngestOnlyService:
        def ingest(self, session, payload):
            return GlucoseReadingsResponse(
                inserted=0,
                unchanged=0,
                updated=0,
                latest_reading_at_ms=None,
                forecast_generated=False,
            )

    client.app.state.forecast_service = IngestOnlyService()
    response = client.post(
        "/v1/glucose/readings",
        headers=auth_headers,
        json={"readings": [], "backfill_complete": True},
    )
    assert response.status_code == 200
