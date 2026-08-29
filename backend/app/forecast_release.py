"""Durable release fencing for out-of-process forecast maintenance jobs."""

from __future__ import annotations

import re

from sqlalchemy import text
from sqlalchemy.orm import Session

from .models import BackendMetadataRecord


FORECAST_RUNTIME_RELEASE_METADATA_KEY = "forecast_runtime_release_v1"
MAX_FORECAST_RUNTIME_RELEASE_LENGTH = 66
_RELEASE_RE = re.compile(r"^[A-Za-z0-9._-]+$")


def normalize_forecast_runtime_release(value: str) -> str:
    normalized = str(value).strip()
    if (
        not normalized
        or len(normalized) > MAX_FORECAST_RUNTIME_RELEASE_LENGTH
        or _RELEASE_RE.fullmatch(normalized) is None
    ):
        raise ValueError(
            "forecast runtime release must match [A-Za-z0-9._-]{1,66}"
        )
    return normalized


def register_forecast_runtime_release(session: Session, release_version: str) -> str:
    """Publish the release served by the ready backend into the shared DB."""

    normalized = normalize_forecast_runtime_release(release_version)
    session.rollback()
    session.execute(text("BEGIN IMMEDIATE"))
    try:
        marker = session.get(
            BackendMetadataRecord, FORECAST_RUNTIME_RELEASE_METADATA_KEY
        )
        if marker is None:
            session.add(
                BackendMetadataRecord(
                    key=FORECAST_RUNTIME_RELEASE_METADATA_KEY,
                    value_text=normalized,
                )
            )
        else:
            marker.value_text = normalized
        session.commit()
        return normalized
    except Exception:
        session.rollback()
        raise
