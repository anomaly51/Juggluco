from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CHART = REPOSITORY_ROOT / "deploy" / "kubernetes" / "chart"


def _helm(*args: str) -> str:
    executable = shutil.which("helm")
    if executable is None:
        pytest.skip("Helm is not installed")
    completed = subprocess.run(
        [executable, *args],
        cwd=REPOSITORY_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout


def test_production_chart_lints_strictly_and_renders_daily_forecast_retry():
    _helm("lint", "--strict", str(CHART))
    rendered = _helm(
        "template",
        "juggluco",
        str(CHART),
        "--namespace",
        "juggluco",
        "--set",
        "image.tag=sha-chart-test",
        "--show-only",
        "templates/forecast-retry-cronjob.yaml",
    )

    assert "kind: CronJob" in rendered
    assert "name: juggluco-forecast-retry" in rendered
    assert 'schedule: "17 3 * * *"' in rendered
    assert 'timeZone: "Etc/UTC"' in rendered
    assert "concurrencyPolicy: Forbid" in rendered
    assert "successfulJobsHistoryLimit: 1" in rendered
    assert "failedJobsHistoryLimit: 2" in rendered
    assert "backoffLimit: 0" in rendered
    assert "ttlSecondsAfterFinished: 172800" in rendered
    assert "serviceAccountName: juggluco" in rendered
    assert "automountServiceAccountToken: false" in rendered
    assert "harbor.internal.api-api-api.com/applications/juggluco:sha-chart-test" in rendered
    assert "retry-display-daily" in rendered
    assert 'value: "sha-chart-test"' in rendered
    assert "readOnlyRootFilesystem: true" in rendered
    assert "allowPrivilegeEscalation: false" in rendered
    assert "claimName: juggluco-data" in rendered
    assert "secretKeyRef:" not in rendered
    assert "--require-activation" not in rendered


def test_postsync_display_attempt_does_not_leave_argo_failed_on_gate_rejection():
    rendered = _helm(
        "template",
        "juggluco",
        str(CHART),
        "--namespace",
        "juggluco",
        "--show-only",
        "templates/forecast-bootstrap-job.yaml",
    )

    assert "argocd.argoproj.io/hook: PostSync" in rendered
    assert "serviceAccountName: juggluco" in rendered
    assert "deploy-display" in rendered
    assert "--require-activation" not in rendered
    assert "secretKeyRef:" not in rendered
