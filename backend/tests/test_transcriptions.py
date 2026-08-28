from __future__ import annotations

import wave
from io import BytesIO
from types import SimpleNamespace

from fastapi.testclient import TestClient
from mutagen.mp4 import MP4
from sqlalchemy import func, select

from app.database import Base
from app.main import create_app
from conftest import TEST_TOKEN, make_settings


def _wav_bytes(duration_seconds: float, sample_rate: int = 8_000) -> bytes:
    frame_count = int(duration_seconds * sample_rate)
    output = BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(1)
        audio.setframerate(sample_rate)
        audio.writeframes(b"\x80" * frame_count)
    return output.getvalue()


def _mp4_atom(atom_type: bytes, payload: bytes) -> bytes:
    return (len(payload) + 8).to_bytes(4, "big") + atom_type + payload


def _synthetic_m4a(
    *,
    sample_rate: int = 16_000,
    channels: int = 1,
    handler_type: bytes = b"soun",
) -> bytes:
    # Minimal structural fixture for the metadata fallback. Mutagen itself is
    # stubbed in these tests; no captured user audio is retained in the suite.
    sample_entry = (
        b"\x00" * 6
        + (1).to_bytes(2, "big")
        + b"\x00" * 8
        + channels.to_bytes(2, "big")
        + (16).to_bytes(2, "big")
        + b"\x00" * 4
        + (sample_rate << 16).to_bytes(4, "big")
    )
    stsd = _mp4_atom(
        b"stsd",
        b"\x00" * 4
        + (1).to_bytes(4, "big")
        + _mp4_atom(b"mp4a", sample_entry),
    )
    hdlr = _mp4_atom(b"hdlr", b"\x00" * 8 + handler_type + b"\x00" * 12)
    mdia = _mp4_atom(
        b"mdia",
        hdlr + _mp4_atom(b"minf", _mp4_atom(b"stbl", stsd)),
    )
    return _mp4_atom(b"ftyp", b"M4A \x00\x00\x02\x00isomM4A ") + _mp4_atom(
        b"moov", _mp4_atom(b"trak", mdia)
    )


def _zero_metadata_mp4(duration: float = 1.728) -> MP4:
    parsed = MP4.__new__(MP4)
    parsed.info = SimpleNamespace(
        length=duration,
        sample_rate=0,
        channels=0,
    )
    return parsed


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
    valid_audio = _wav_bytes(0.25)
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("note.wav", valid_audio, "audio/wav")},
    )

    assert response.status_code == 200
    assert response.json() == {"text": "I drank one glass of milk"}
    assert len(fake_transcriber.calls) == 1
    assert fake_transcriber.calls[0].data == valid_audio
    assert fake_transcriber.calls[0].format == "wav"
    assert fake_transcriber.calls[0].duration_seconds == 0.25


def test_transcription_forwards_optional_language_hint(
    client, auth_headers, fake_transcriber
):
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        data={"language": "ru-RU"},
        files={"audio": ("note.wav", _wav_bytes(0.25), "audio/wav")},
    )

    assert response.status_code == 200
    assert fake_transcriber.language_hints == ["ru-RU"]


def test_transcription_rejects_invalid_language_before_provider_call(
    client, auth_headers, fake_transcriber
):
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        data={"language": "russian"},
        files={"audio": ("note.wav", _wav_bytes(0.25), "audio/wav")},
    )

    assert response.status_code == 422
    assert response.json()["detail"] == (
        "audio language must be auto or a valid language tag"
    )
    assert fake_transcriber.calls == []


def test_transcription_accepts_android_m4a_when_mutagen_omits_stream_fields(
    client, auth_headers, fake_transcriber, monkeypatch
):
    valid_audio = _synthetic_m4a()
    monkeypatch.setattr(
        "app.media.MutagenFile", lambda _source: _zero_metadata_mp4()
    )

    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("recording.m4a", valid_audio, "audio/mp4")},
    )

    assert response.status_code == 200
    assert len(fake_transcriber.calls) == 1
    assert fake_transcriber.calls[0].data == valid_audio
    assert fake_transcriber.calls[0].format == "m4a"
    assert fake_transcriber.calls[0].duration_seconds == 1.728


def test_transcription_m4a_fallback_requires_audio_track_atom_path(
    client, auth_headers, fake_transcriber, monkeypatch
):
    monkeypatch.setattr(
        "app.media.MutagenFile", lambda _source: _zero_metadata_mp4()
    )
    loose_mp4a = _mp4_atom(b"ftyp", b"isom\x00\x00\x00\x00") + _mp4_atom(
        b"mp4a", b"\x00" * 64
    )

    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("recording.m4a", loose_mp4a, "audio/mp4")},
    )

    assert response.status_code == 415
    assert response.json()["detail"] == "audio has invalid stream metadata"
    assert fake_transcriber.calls == []


def test_transcription_m4a_fallback_rejects_non_audio_track(
    client, auth_headers, fake_transcriber, monkeypatch
):
    monkeypatch.setattr(
        "app.media.MutagenFile", lambda _source: _zero_metadata_mp4()
    )

    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={
            "audio": (
                "recording.m4a",
                _synthetic_m4a(handler_type=b"vide"),
                "audio/mp4",
            )
        },
    )

    assert response.status_code == 415
    assert response.json()["detail"] == "audio has invalid stream metadata"
    assert fake_transcriber.calls == []


def test_transcription_m4a_fallback_rejects_invalid_channel_metadata(
    client, auth_headers, fake_transcriber, monkeypatch
):
    monkeypatch.setattr(
        "app.media.MutagenFile", lambda _source: _zero_metadata_mp4()
    )

    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={
            "audio": (
                "recording.m4a",
                _synthetic_m4a(channels=0),
                "audio/mp4",
            )
        },
    )

    assert response.status_code == 415
    assert response.json()["detail"] == "audio has invalid stream metadata"
    assert fake_transcriber.calls == []


def test_transcription_m4a_fallback_rejects_invalid_sample_rate_metadata(
    client, auth_headers, fake_transcriber, monkeypatch
):
    monkeypatch.setattr(
        "app.media.MutagenFile", lambda _source: _zero_metadata_mp4()
    )

    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={
            "audio": (
                "recording.m4a",
                _synthetic_m4a(sample_rate=1_000),
                "audio/mp4",
            )
        },
    )

    assert response.status_code == 415
    assert response.json()["detail"] == "audio has invalid stream metadata"
    assert fake_transcriber.calls == []


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


def test_transcription_rejects_corrupt_audio_before_provider_call(
    client, auth_headers, fake_transcriber
):
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("note.wav", b"not-a-wave-container", "audio/wav")},
    )

    assert response.status_code == 415
    assert response.json()["detail"] == (
        "audio is not a supported valid audio container"
    )
    assert fake_transcriber.calls == []


def test_transcription_rejects_container_mime_mismatch_before_provider_call(
    client, auth_headers, fake_transcriber
):
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("note.m4a", _wav_bytes(0.25), "audio/mp4")},
    )

    assert response.status_code == 415
    assert response.json()["detail"] == (
        "audio content does not match its declared media type"
    )
    assert fake_transcriber.calls == []


def test_transcription_rejects_audio_over_ninety_seconds_before_provider_call(
    client, auth_headers, fake_transcriber
):
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("long.wav", _wav_bytes(91), "audio/wav")},
    )

    assert response.status_code == 413
    assert response.json()["detail"] == "audio duration exceeds the 90 second limit"
    assert fake_transcriber.calls == []


def test_transcription_rejects_zero_duration_audio_before_provider_call(
    client, auth_headers, fake_transcriber
):
    response = client.post(
        "/v1/transcriptions",
        headers=auth_headers,
        files={"audio": ("empty.wav", _wav_bytes(0), "audio/wav")},
    )

    assert response.status_code == 415
    assert response.json()["detail"] == "audio has invalid stream metadata"
    assert fake_transcriber.calls == []


def test_transcription_fails_closed_when_ai_key_is_missing(tmp_path):
    application = create_app(make_settings(tmp_path, openrouter_api_key=None))
    with TestClient(application) as local_client:
        response = local_client.post(
            "/v1/transcriptions",
            headers={"Authorization": f"Bearer {TEST_TOKEN}"},
            files={"audio": ("note.wav", _wav_bytes(0.25), "audio/wav")},
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
        files={"audio": ("note.wav", _wav_bytes(0.25), "audio/wav")},
    )

    assert response.status_code == 200
    assert _database_row_counts(app) == before
