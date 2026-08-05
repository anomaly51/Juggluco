from __future__ import annotations

from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.database import Base
from app.main import create_app
from conftest import TEST_TOKEN, make_settings


def _database_row_counts(application) -> dict[str, int]:
    with application.state.database.session_factory() as session:
        return {
            table.name: session.scalar(select(func.count()).select_from(table)) or 0
            for table in Base.metadata.sorted_tables
        }


def test_transcription_requires_backend_authentication(client, fake_transcriber):
    response = client.post(
        "/v1/transcriptions",
        files={"audio": ("note.m4a", b"synthetic-audio", "audio/mp4")},
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid bearer token"
    assert fake_transcriber.calls == []


def test_transcription_returns_provider_neutral_editable_text(
    client, auth_headers, fake_transcriber
):
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("note.m4a", b"synthetic-audio", "audio/mp4")},
    )

    assert response.status_code == 200
    assert response.json() == {"text": "I drank one glass of milk"}
    assert len(fake_transcriber.calls) == 1
    assert fake_transcriber.calls[0].data == b"synthetic-audio"
    assert fake_transcriber.calls[0].format == "m4a"


def test_transcription_rejects_invalid_type_and_oversized_audio(
    client, auth_headers, fake_transcriber
):
    invalid = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("note.txt", b"not-audio", "text/plain")},
    )
    oversized = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("large.m4a", b"x" * 1_000_001, "audio/mp4")},
    )

    assert invalid.status_code == 415
    assert invalid.json()["detail"] == "audio format is not supported"
    assert oversized.status_code == 413
    assert "configured size limit" in oversized.json()["detail"]
    assert fake_transcriber.calls == []


def test_transcription_fails_closed_when_ai_key_is_missing(tmp_path):
    application = create_app(make_settings(tmp_path, openrouter_api_key=None))
    with TestClient(application) as local_client:
        response = local_client.post(
            "/v1/transcriptions",
            headers={"Authorization": f"Bearer {TEST_TOKEN}"},
            files={"audio": ("note.m4a", b"synthetic-audio", "audio/mp4")},
        )

    assert response.status_code == 503
    assert response.json()["detail"] == "AI service is not configured"


def test_transcription_has_no_database_side_effect(
    app, client, auth_headers, fake_transcriber
):
    before = _database_row_counts(app)

    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("note.wav", b"synthetic-wave", "audio/wav")},
    )

    assert response.status_code == 200
    assert _database_row_counts(app) == before
