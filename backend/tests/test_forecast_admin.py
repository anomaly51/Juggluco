from __future__ import annotations

import csv
import hashlib
import json
import os
import sqlite3
import stat
from pathlib import Path

from app.database import Database
from app.forecast import STATIC_DISPLAY_PROTOCOL
from app.models import ForecastModelRecord, GlucoseReadingRecord, IntakeEventRecord
from app.schemas import (
    ForecastTrainingStatus,
    ForecastTrainResponse,
    GlucoseReadingsResponse,
)
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


def _display_candidate_record(
    version: str,
    *,
    status: str = "candidate",
    accepted: bool = True,
    alert_approved: bool = False,
) -> ForecastModelRecord:
    accepted_value = 1 if accepted else 0
    return ForecastModelRecord(
        version=version,
        status=status,
        architecture="display-test",
        created_at_ms=1,
        trained_at_ms=2,
        promoted_at_ms=None,
        training_cutoff_ms=1,
        sample_count=42,
        parameters_json=json.dumps(
            {
                "artifact": {
                    "accepted": accepted,
                    "evaluation": {
                        "accepted": accepted_value,
                        "display_only": 1,
                        "exploratory": 1,
                        "unbiased_holdout": 0,
                        "receipt_causal_validation": 0,
                        "prospective": 0,
                    },
                    "approval": {
                        "state": "exploratory_retrospective_display",
                        "protocol": STATIC_DISPLAY_PROTOCOL,
                        "alert_approved": alert_approved,
                        "unbiased_holdout": False,
                        "receipt_causal_validation": False,
                        "use_scope": "chart_only_not_for_dosing_or_alerts",
                    },
                }
            }
        ),
        metrics_json=json.dumps(
            {
                "accepted": accepted_value,
                "display_only": 1,
                "exploratory": 1,
                "unbiased_holdout": 0,
                "receipt_causal_validation": 0,
                "prospective": 0,
            }
        ),
        decision_reason="display bootstrap test fixture",
    )


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


def test_deploy_display_retries_source_change_then_explicitly_activates(tmp_path):
    database_path = tmp_path / "display.sqlite"
    candidate_version = "display-sha-test"

    class FakeService:
        def __init__(self):
            self.train_calls = []
            self.activation_calls = []

        def train_static_model(
            self,
            session,
            data_cutoff_ms=None,
            candidate_version=None,
            stage_pending=True,
            allow_display_activation=False,
        ):
            self.train_calls.append(
                (
                    candidate_version,
                    stage_pending,
                    allow_display_activation,
                )
            )
            if len(self.train_calls) == 1:
                return ForecastTrainResponse(
                    status="skipped",
                    promoted=False,
                    model_version="event-aware-persistence-v3",
                    reason="Source glucose/intake data changed during training",
                    sample_count=42,
                    metrics={"source_revision_changed": 1},
                )
            session.add(_display_candidate_record(candidate_version))
            session.commit()
            return ForecastTrainResponse(
                status="accepted",
                promoted=False,
                model_version=candidate_version,
                reason="Display gates accepted",
                sample_count=42,
                metrics={"accepted": 1},
            )

        def activate_model(self, session, version):
            self.activation_calls.append(version)
            record = session.get(ForecastModelRecord, version)
            record.status = "champion"
            record.promoted_at_ms = 3
            session.commit()
            return record

    service = FakeService()
    result = forecast_admin.execute(
        [
            "--database",
            str(database_path),
            "deploy-display",
            "--candidate-version",
            candidate_version,
            "--source-change-retries",
            "2",
            "--retry-delay-seconds",
            "0",
        ],
        service_factory=lambda: service,
    )

    assert service.train_calls == [
        (candidate_version, False, True),
        (candidate_version, False, True),
    ]
    assert service.activation_calls == [candidate_version]
    assert result["result"]["status"] == "promoted"
    assert result["result"]["promoted"] is True
    assert result["result"]["attempts"] == 2
    assert result["result"]["activation"]["status"] == "champion"


def test_deploy_display_reuses_rejected_version_without_training_or_activation(
    tmp_path,
):
    database_path = tmp_path / "display-existing.sqlite"
    candidate_version = "display-existing-rejected"
    database = Database(database_path)
    database.create_all()
    with database.session_factory() as session:
        session.add(
            _display_candidate_record(
                candidate_version,
                status="rejected",
                accepted=False,
            )
        )
        session.commit()
    database.dispose()

    class NoCallService:
        def train_static_model(self, *args, **kwargs):
            raise AssertionError("an existing deterministic version must not retrain")

        def activate_model(self, *args, **kwargs):
            raise AssertionError("a rejected candidate must not activate")

    result = forecast_admin.execute(
        [
            "--database",
            str(database_path),
            "deploy-display",
            "--candidate-version",
            candidate_version,
        ],
        service_factory=NoCallService,
    )

    assert result["result"] == {
        "status": "retained",
        "promoted": False,
        "model_version": candidate_version,
        "reason": (
            "Existing display bootstrap candidate is rejected; "
            "the active model was retained"
        ),
        "attempts": 0,
        "reused_existing": True,
    }


def test_deploy_display_fresh_rejection_retains_active_model(tmp_path):
    database_path = tmp_path / "display-fresh-rejected.sqlite"

    class RejectingService:
        def __init__(self):
            self.activated = False

        def train_static_model(self, session, **kwargs):
            assert kwargs == {
                "candidate_version": "display-fresh-rejected",
                "stage_pending": False,
                "allow_display_activation": True,
            }
            return ForecastTrainResponse(
                status="rejected",
                promoted=False,
                model_version="display-fresh-rejected",
                reason="Display gates retained the baseline",
                sample_count=42,
                metrics={"accepted": 0},
            )

        def activate_model(self, *args, **kwargs):
            self.activated = True
            raise AssertionError("a rejected result must not activate")

    service = RejectingService()
    result = forecast_admin.execute(
        [
            "--database",
            str(database_path),
            "deploy-display",
            "--candidate-version",
            "display-fresh-rejected",
        ],
        service_factory=lambda: service,
    )

    assert service.activated is False
    assert result["result"]["status"] == "retained"
    assert result["result"]["promoted"] is False
    assert result["result"]["training"]["status"] == "rejected"


def test_deploy_display_resumes_existing_accepted_candidate(tmp_path):
    database_path = tmp_path / "display-resume.sqlite"
    candidate_version = "display-resume"
    database = Database(database_path)
    database.create_all()
    with database.session_factory() as session:
        session.add(_display_candidate_record(candidate_version))
        session.commit()
    database.dispose()

    class ResumingService:
        def __init__(self):
            self.activation_calls = []

        def train_static_model(self, *args, **kwargs):
            raise AssertionError("an already persisted candidate must not retrain")

        def activate_model(self, session, version):
            self.activation_calls.append(version)
            record = session.get(ForecastModelRecord, version)
            record.status = "champion"
            record.promoted_at_ms = 3
            session.commit()
            return record

    service = ResumingService()
    result = forecast_admin.execute(
        [
            "--database",
            str(database_path),
            "deploy-display",
            "--candidate-version",
            candidate_version,
        ],
        service_factory=lambda: service,
    )

    assert service.activation_calls == [candidate_version]
    assert result["result"]["status"] == "promoted"
    assert result["result"]["reused_existing"] is True


def test_deploy_display_revalidates_an_existing_active_candidate(tmp_path):
    database_path = tmp_path / "display-active.sqlite"
    candidate_version = "display-already-active"
    database = Database(database_path)
    database.create_all()
    with database.session_factory() as session:
        session.add(
            _display_candidate_record(candidate_version, status="champion")
        )
        session.commit()
    database.dispose()

    class RevalidatingService:
        def __init__(self):
            self.activation_calls = []

        def activate_model(self, session, version):
            self.activation_calls.append(version)
            return session.get(ForecastModelRecord, version)

    service = RevalidatingService()
    result = forecast_admin.execute(
        [
            "--database",
            str(database_path),
            "deploy-display",
            "--candidate-version",
            candidate_version,
            "--require-activation",
        ],
        service_factory=lambda: service,
    )

    assert service.activation_calls == [candidate_version]
    assert result["result"]["status"] == "already_active"
    assert result["result"]["reused_existing"] is True


def test_deploy_display_versions_a_retry_when_rejected_source_has_changed(tmp_path):
    database_path = tmp_path / "display-source-revision.sqlite"
    base_version = "display-sha-test"
    old_revision = (1, 1, 1, 1, 1, 1)
    new_revision = (2, 2, 2, 2, 2, 2)
    database = Database(database_path)
    database.create_all()
    with database.session_factory() as session:
        rejected = _display_candidate_record(
            base_version,
            status="rejected",
            accepted=False,
        )
        parameters = json.loads(rejected.parameters_json)
        parameters["artifact"]["snapshot"] = {
            "source_revision": list(old_revision)
        }
        rejected.parameters_json = json.dumps(parameters, separators=(",", ":"))
        session.add(rejected)
        session.commit()
    database.dispose()

    class RevisionAwareService:
        def __init__(self):
            self.train_versions = []

        @staticmethod
        def _source_revision(session):
            return new_revision

        def train_static_model(self, session, **kwargs):
            version = kwargs["candidate_version"]
            self.train_versions.append(version)
            session.add(_display_candidate_record(version))
            session.commit()
            return ForecastTrainResponse(
                status="accepted",
                promoted=False,
                model_version=version,
                reason="Display gates accepted",
                sample_count=42,
                metrics={"accepted": 1},
            )

        @staticmethod
        def activate_model(session, version):
            record = session.get(ForecastModelRecord, version)
            record.status = "champion"
            record.promoted_at_ms = 3
            session.commit()
            return record

    service = RevisionAwareService()
    expected_version = forecast_admin._revisioned_candidate_version(
        base_version, new_revision
    )
    result = forecast_admin.execute(
        [
            "--database",
            str(database_path),
            "deploy-display",
            "--candidate-version",
            base_version,
            "--require-activation",
        ],
        service_factory=lambda: service,
    )

    assert service.train_versions == [expected_version]
    assert result["result"]["model_version"] == expected_version
    assert result["result"]["status"] == "promoted"


def test_deploy_display_never_activates_alert_approved_existing_candidate(tmp_path):
    database_path = tmp_path / "display-alert.sqlite"
    candidate_version = "display-alert-approved"
    database = Database(database_path)
    database.create_all()
    with database.session_factory() as session:
        session.add(
            _display_candidate_record(
                candidate_version,
                alert_approved=True,
            )
        )
        session.commit()
    database.dispose()

    class NoActivationService:
        def activate_model(self, *args, **kwargs):
            raise AssertionError("alert-approved candidates must never activate here")

    try:
        forecast_admin.execute(
            [
                "--database",
                str(database_path),
                "deploy-display",
                "--candidate-version",
                candidate_version,
            ],
            service_factory=NoActivationService,
        )
    except ValueError as error:
        assert "alert-disabled" in str(error)
    else:  # pragma: no cover - documents the fail-closed safety expectation.
        raise AssertionError("unsafe existing candidate was not rejected")


def test_deploy_display_cli_exits_zero_when_empty_data_retains_baseline(
    tmp_path,
    capsys,
):
    exit_code = forecast_admin.main(
        [
            "--database",
            str(tmp_path / "empty.sqlite"),
            "deploy-display",
            "--candidate-version",
            "display-empty",
            "--retry-delay-seconds",
            "0",
        ]
    )

    output = capsys.readouterr()
    assert exit_code == 0
    assert output.err == ""
    assert json.loads(output.out)["result"]["status"] == "retained"


def test_deploy_display_cli_exits_nonzero_when_gitops_requires_activation(
    tmp_path,
    capsys,
):
    exit_code = forecast_admin.main(
        [
            "--database",
            str(tmp_path / "empty-required.sqlite"),
            "deploy-display",
            "--candidate-version",
            "display-empty-required",
            "--retry-delay-seconds",
            "0",
            "--require-activation",
        ]
    )

    output = capsys.readouterr()
    assert exit_code == 1
    assert output.out == ""
    assert "activation required" in output.err


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
