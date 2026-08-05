from __future__ import annotations

import time
from uuid import uuid4

from fastapi.testclient import TestClient

from app.main import create_app
from conftest import TEST_TOKEN, make_settings


def test_legacy_analyze_returns_a_draft_but_cannot_bypass_meal_chat_confirmation(
    client, auth_headers, fake_analyzer, jpeg_bytes
):
    response = client.post(
        "/v1/analyze",
        headers=auth_headers,
        data={"meal_text": "A rice bowl"},
        files=[
            ("photos", ("meal.jpg", jpeg_bytes, "image/jpeg")),
            ("photos", ("label.jpg", jpeg_bytes, "image/jpeg")),
        ],
    )
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {
        "analysis_id",
        "meal_name",
        "meal_description",
        "estimated_carbs_g",
        "carbs_low_g",
        "carbs_high_g",
        "confidence",
        "items",
        "assumptions",
        "warnings",
        "transcription",
    }
    assert body["estimated_carbs_g"] == 52
    assert body["carbs_low_g"] <= body["estimated_carbs_g"] <= body["carbs_high_g"]
    assert 0 <= body["confidence"] <= 1
    assert len(fake_analyzer.calls) == 1
    assert len(fake_analyzer.calls[0][1]) == 2
    assert all(image.media_type == "image/jpeg" for image in fake_analyzer.calls[0][1])

    intake = {
        "client_event_id": str(uuid4()),
        "occurred_at_ms": int(time.time() * 1000),
        "meal_text": body["meal_description"],
        "carbs_g": body["estimated_carbs_g"],
        "carbs_source": "ai_estimate",
        "insulin_units": None,
        "insulin_type": None,
        "insulin_name": None,
        "analysis_id": body["analysis_id"],
    }
    blocked = client.post("/v1/intakes", headers=auth_headers, json=intake)
    assert blocked.status_code == 405
    assert client.get(
        "/v1/intakes", headers=auth_headers
    ).json()["items"] == []


def test_audio_is_validated_and_forwarded_for_transcription(
    client, auth_headers, fake_analyzer
):
    response = client.post(
        "/v1/analyze",
        headers=auth_headers,
        files={"audio": ("meal.m4a", b"synthetic-audio", "audio/mp4")},
    )
    assert response.status_code == 200
    assert response.json()["transcription"] == "I ate a rice bowl"
    assert fake_analyzer.calls[0][2].format == "m4a"


def test_analyze_requires_input_and_limits_photos(client, auth_headers, jpeg_bytes):
    missing = client.post("/v1/analyze", headers=auth_headers)
    assert missing.status_code == 422

    three = client.post(
        "/v1/analyze",
        headers=auth_headers,
        files=[
            ("photos", (f"{index}.jpg", jpeg_bytes, "image/jpeg"))
            for index in range(3)
        ],
    )
    assert three.status_code == 422
    assert three.json()["detail"] == "at most two photos are allowed"


def test_invalid_or_oversized_media_is_rejected_before_analysis(
    client, auth_headers, fake_analyzer
):
    invalid = client.post(
        "/v1/analyze",
        headers=auth_headers,
        files={"photos": ("fake.jpg", b"not-an-image", "image/jpeg")},
    )
    assert invalid.status_code == 415

    oversized = client.post(
        "/v1/analyze",
        headers=auth_headers,
        files={"photos": ("large.jpg", b"x" * 1_000_001, "image/jpeg")},
    )
    assert oversized.status_code == 413
    assert fake_analyzer.calls == []


def test_unsupported_audio_type_is_rejected(client, auth_headers, fake_analyzer):
    response = client.post(
        "/v1/analyze",
        headers=auth_headers,
        files={"audio": ("meal.txt", b"hello", "text/plain")},
    )
    assert response.status_code == 415
    assert fake_analyzer.calls == []


def test_analysis_fails_closed_when_openrouter_key_is_missing(tmp_path):
    application = create_app(make_settings(tmp_path))
    with TestClient(application) as local_client:
        response = local_client.post(
            "/v1/analyze",
            headers={"Authorization": f"Bearer {TEST_TOKEN}"},
            data={"meal_text": "one apple"},
        )
        assert response.status_code == 503
        assert response.json()["detail"] == "AI service is not configured"
