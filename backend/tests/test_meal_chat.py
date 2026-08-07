from __future__ import annotations

import sqlite3
import time
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

from fastapi.testclient import TestClient

from app.main import create_app
from app.schemas import MealChatModelResult
from conftest import FakeAnalyzer, FakeChatAnalyzer, TEST_TOKEN, make_settings


def create_session(client, auth_headers, *, client_event_id=None, occurred_at_ms=None):
    payload = {
        "client_event_id": str(client_event_id or uuid4()),
        "occurred_at_ms": occurred_at_ms or int(time.time() * 1_000) - 30_000,
    }
    response = client.post(
        "/v1/meal-chat/sessions", headers=auth_headers, json=payload
    )
    assert response.status_code == 200, response.text
    return response.json(), payload


def test_create_session_is_authenticated_idempotent_and_reserves_event_identity(
    client, auth_headers
):
    unauthorized = client.post(
        "/v1/meal-chat/sessions",
        json={
            "client_event_id": str(uuid4()),
            "occurred_at_ms": int(time.time() * 1_000),
        },
    )
    assert unauthorized.status_code == 401

    created, payload = create_session(client, auth_headers)
    assert created["client_event_id"] == payload["client_event_id"]
    assert created["status"] == "active"
    assert created["messages"] == []
    assert created["proposal"] is None
    assert created["ready_to_confirm"] is False
    assert created["confirmed_intake_id"] is None

    repeated = client.post(
        "/v1/meal-chat/sessions", headers=auth_headers, json=payload
    )
    assert repeated.status_code == 200
    assert repeated.json() == created

    changed = client.post(
        "/v1/meal-chat/sessions",
        headers=auth_headers,
        json={**payload, "occurred_at_ms": payload["occurred_at_ms"] - 1},
    )
    assert changed.status_code == 409

    direct_intake = client.post(
        "/v1/insulin-events",
        headers=auth_headers,
        json={
            **payload,
            "insulin_units": 1,
            "insulin_name": "NovoRapid",
        },
    )
    assert direct_intake.status_code == 409
    assert "reserved" in direct_intake.json()["detail"]


def test_multiturn_chat_persists_corrections_but_never_raw_media(
    client,
    app,
    auth_headers,
    fake_chat_analyzer,
    jpeg_bytes,
):
    initial = FakeChatAnalyzer.default_result().model_copy(
        update={
            "assistant_message": "Is that one bowl or two?",
            "ready_to_confirm": False,
        }
    )
    corrected_proposal = initial.proposal.model_copy(
        update={
            "meal_description": "Half a rice bowl with chicken",
            "total_portion_g": 210,
            "estimated_carbs_g": 31,
            "carbs_low_g": 25,
            "carbs_high_g": 39,
            "confidence": 0.88,
        }
    )
    corrected = MealChatModelResult(
        assistant_message="Updated to half a bowl. Ready to save.",
        proposal=corrected_proposal,
        ready_to_confirm=True,
    )
    fake_chat_analyzer.results = [initial, corrected]

    chat, _ = create_session(client, auth_headers)
    first = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "Rice and chicken"},
        files=[
            ("photos", (f"food-{index}.jpg", jpeg_bytes, "image/jpeg"))
            for index in range(3)
        ]
        + [("audio", ("meal.m4a", b"synthetic-audio", "audio/mp4"))],
    )
    assert first.status_code == 200, first.text
    assert first.json()["ready_to_confirm"] is False
    assert first.json()["proposal"]["estimated_carbs_g"] == 52

    premature = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
    )
    assert premature.status_code == 409

    second = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "Correction: I ate only half of the bowl."},
    )
    assert second.status_code == 200, second.text
    assert second.json()["ready_to_confirm"] is True
    assert second.json()["proposal"]["estimated_carbs_g"] == 31

    assert len(fake_chat_analyzer.calls) == 2
    first_call = fake_chat_analyzer.calls[0]
    assert len(first_call[2]) == 3
    assert all(image.source_bytes == len(jpeg_bytes) for image in first_call[2])
    assert first_call[3].format == "m4a"
    second_history = fake_chat_analyzer.calls[1][0]
    assert [entry.role for entry in second_history] == ["user", "assistant"]
    assert second_history[1].proposal_json is not None
    assert '"estimated_carbs_g":52.0' in second_history[1].proposal_json

    fetched = client.get(
        f"/v1/meal-chat/sessions/{chat['id']}", headers=auth_headers
    )
    assert fetched.status_code == 200
    history = fetched.json()
    assert history["proposal"]["estimated_carbs_g"] == 31
    assert history["ready_to_confirm"] is True
    assert [message["role"] for message in history["messages"]] == [
        "user",
        "assistant",
        "user",
        "assistant",
    ]
    assert history["messages"][0]["photo_count"] == 3
    assert history["messages"][0]["had_audio"] is True
    assert "Voice transcript" in history["messages"][0]["text"]

    database_path = app.state.settings.database_path
    with sqlite3.connect(database_path) as connection:
        columns = {
            row[1]
            for row in connection.execute("PRAGMA table_info(meal_chat_messages)")
        }
        assert columns == {
            "id",
            "session_id",
            "sequence",
            "role",
            "text",
            "photo_count",
            "had_audio",
            "analysis_id",
            "created_at_ms",
        }
        assert connection.execute(
            "SELECT COUNT(*) FROM meal_chat_messages"
        ).fetchone()[0] == 4
    sqlite_files = [database_path, database_path.with_name(database_path.name + "-wal")]
    persisted_bytes = b"".join(
        path.read_bytes() for path in sqlite_files if path.exists()
    )
    assert jpeg_bytes not in persisted_bytes
    assert b"synthetic-audio" not in persisted_bytes


def test_confirm_is_explicit_idempotent_and_creates_meal_only_intake(
    client, auth_headers
):
    chat, payload = create_session(client, auth_headers)
    turn = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "A rice bowl"},
    )
    assert turn.status_code == 200
    assert turn.json()["ready_to_confirm"] is True

    # An assistant proposal alone must never write a graph/intake event.
    before = client.get("/v1/intakes", headers=auth_headers)
    assert before.json()["items"] == []

    confirmed = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
    )
    assert confirmed.status_code == 200, confirmed.text
    event = confirmed.json()
    assert "item" not in event
    assert event["client_event_id"] == payload["client_event_id"]
    assert event["occurred_at_ms"] == payload["occurred_at_ms"]
    assert event["meal_text"] == "Rice with chicken and vegetables"
    assert event["carbs_g"] == 52
    assert event["carbs_source"] == "ai_estimate"
    assert event["analysis_id"] is not None
    assert event["ai_confidence"] == 0.76
    assert event["insulin_units"] is None
    assert event["insulin_type"] is None
    assert event["insulin_name"] is None

    repeated = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
    )
    assert repeated.status_code == 200
    assert repeated.json() == event
    assert len(client.get("/v1/intakes", headers=auth_headers).json()["items"]) == 1

    fetched = client.get(
        f"/v1/meal-chat/sessions/{chat['id']}", headers=auth_headers
    ).json()
    assert fetched["status"] == "confirmed"
    assert fetched["ready_to_confirm"] is False
    assert fetched["confirmed_intake_id"] == event["id"]

    after_confirm = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "another correction"},
    )
    assert after_confirm.status_code == 409


def test_confirmed_meal_portion_can_be_corrected_without_compounding(
    client, auth_headers
):
    chat, _ = create_session(client, auth_headers)
    turn = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "A rice bowl"},
    )
    assert turn.status_code == 200
    confirmed = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm",
        headers=auth_headers,
    )
    assert confirmed.status_code == 200
    original = confirmed.json()
    assert original["portion_g"] == 350
    assert original["original_portion_g"] == 350
    assert original["original_carbs_g"] == 52
    assert original["carbs_g"] == 52

    path = f"/v1/intakes/{original['id']}/meal-portion"
    unauthorized = client.put(path, json={"portion_g": 175})
    assert unauthorized.status_code == 401

    half = client.put(path, headers=auth_headers, json={"portion_g": 175})
    assert half.status_code == 200, half.text
    edited = half.json()
    assert edited["portion_g"] == 175
    assert edited["original_portion_g"] == 350
    assert edited["original_carbs_g"] == 52
    assert edited["carbs_g"] == 26
    assert edited["sync_version"] > original["sync_version"]
    assert edited["updated_at_ms"] >= original["updated_at_ms"]

    # Repeating the idempotent command must not create another sync revision.
    repeated = client.put(path, headers=auth_headers, json={"portion_g": 175})
    assert repeated.status_code == 200
    assert repeated.json() == edited

    # Every correction uses the immutable 350 g / 52 g baseline, not the
    # already rounded 175 g / 26 g result.
    quarter = client.put(path, headers=auth_headers, json={"portion_g": 87.5})
    assert quarter.status_code == 200
    assert quarter.json()["carbs_g"] == 13
    assert quarter.json()["portion_g"] == 87.5

    too_much = client.put(path, headers=auth_headers, json={"portion_g": 351})
    assert too_much.status_code == 422
    fetched = client.get(
        f"/v1/intakes/{original['id']}", headers=auth_headers
    )
    assert fetched.status_code == 200
    assert fetched.json() == quarter.json()


def test_ready_draft_time_can_change_before_confirm_without_losing_proposal_or_history(
    client, auth_headers
):
    chat, payload = create_session(client, auth_headers)
    turn = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "A rice bowl"},
    )
    assert turn.status_code == 200, turn.text
    assert turn.json()["ready_to_confirm"] is True

    before = client.get(
        f"/v1/meal-chat/sessions/{chat['id']}", headers=auth_headers
    ).json()
    moved_time = payload["occurred_at_ms"] - 40 * 60 * 1_000
    moved = client.put(
        f"/v1/meal-chat/sessions/{chat['id']}/time",
        headers=auth_headers,
        json={"occurred_at_ms": moved_time},
    )
    assert moved.status_code == 200, moved.text
    moved_session = moved.json()
    assert moved_session["occurred_at_ms"] == moved_time
    assert moved_session["status"] == "active"
    assert moved_session["ready_to_confirm"] is True
    assert moved_session["proposal"] == before["proposal"]
    assert moved_session["messages"] == before["messages"]

    repeated_move = client.put(
        f"/v1/meal-chat/sessions/{chat['id']}/time",
        headers=auth_headers,
        json={"occurred_at_ms": moved_time},
    )
    assert repeated_move.status_code == 200, repeated_move.text
    assert repeated_move.json()["occurred_at_ms"] == moved_time
    assert repeated_move.json()["proposal"] == before["proposal"]
    assert repeated_move.json()["messages"] == before["messages"]

    confirmed = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
    )
    assert confirmed.status_code == 200, confirmed.text
    assert confirmed.json()["occurred_at_ms"] == moved_time

    immutable = client.put(
        f"/v1/meal-chat/sessions/{chat['id']}/time",
        headers=auth_headers,
        json={"occurred_at_ms": moved_time - 10 * 60 * 1_000},
    )
    assert immutable.status_code == 409
    assert "cannot be changed" in immutable.json()["detail"]


def test_meal_chat_time_update_is_authenticated_and_validates_future_time(
    client, auth_headers
):
    chat, _ = create_session(client, auth_headers)
    endpoint = f"/v1/meal-chat/sessions/{chat['id']}/time"

    unauthorized = client.put(
        endpoint,
        json={"occurred_at_ms": int(time.time() * 1_000) - 60_000},
    )
    assert unauthorized.status_code == 401

    invalid = client.put(
        endpoint,
        headers=auth_headers,
        json={"occurred_at_ms": int(time.time() * 1_000) + 20 * 60_000},
    )
    assert invalid.status_code == 422


def test_time_update_and_confirm_are_serialized_without_timestamp_divergence(
    client, auth_headers
):
    chat, payload = create_session(client, auth_headers)
    turn = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "A rice bowl"},
    )
    assert turn.status_code == 200, turn.text

    moved_time = payload["occurred_at_ms"] - 60 * 60 * 1_000
    start = Barrier(2)

    def move_time():
        start.wait()
        return client.put(
            f"/v1/meal-chat/sessions/{chat['id']}/time",
            headers=auth_headers,
            json={"occurred_at_ms": moved_time},
        )

    def confirm():
        start.wait()
        return client.post(
            f"/v1/meal-chat/sessions/{chat['id']}/confirm",
            headers=auth_headers,
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        move_future = executor.submit(move_time)
        confirm_future = executor.submit(confirm)
        moved = move_future.result(timeout=10)
        confirmed = confirm_future.result(timeout=10)

    assert moved.status_code in (200, 409), moved.text
    assert confirmed.status_code == 200, confirmed.text
    final_session = client.get(
        f"/v1/meal-chat/sessions/{chat['id']}", headers=auth_headers
    )
    assert final_session.status_code == 200
    assert final_session.json()["status"] == "confirmed"
    # Whichever writer acquired BEGIN IMMEDIATE first wins, but the confirmed event and
    # session can never end up with different physiological timestamps.
    assert final_session.json()["occurred_at_ms"] == confirmed.json()["occurred_at_ms"]
    if moved.status_code == 200:
        assert confirmed.json()["occurred_at_ms"] == moved_time


def test_ready_proposal_can_be_revised_repeatedly_and_only_latest_is_confirmed(
    client,
    app,
    auth_headers,
    fake_chat_analyzer,
):
    initial = FakeChatAnalyzer.default_result()
    fifty_grams = initial.proposal.model_copy(
        update={
            "meal_description": "50 g eaten from a 180 g serving of cooked rice",
            "total_portion_g": 50,
            "items": [
                initial.proposal.items[0].model_copy(
                    update={"portion_g": 50, "carbs_g": 14}
                )
            ],
            "estimated_carbs_g": 14,
            "carbs_low_g": 12,
            "carbs_high_g": 16,
            "confidence": 0.9,
        }
    )
    needs_detail = fifty_grams.model_copy(
        update={
            "meal_description": "50 g of cooked rice; sauce amount not yet known",
            "estimated_carbs_g": 15,
            "carbs_low_g": 12,
            "carbs_high_g": 20,
            "confidence": 0.62,
        }
    )
    final_proposal = needs_detail.model_copy(
        update={
            "meal_description": "50 g of cooked rice with 10 g sweet sauce",
            "total_portion_g": 60,
            "estimated_carbs_g": 18,
            "carbs_low_g": 16,
            "carbs_high_g": 21,
            "confidence": 0.86,
        }
    )
    fake_chat_analyzer.results = [
        initial,
        MealChatModelResult(
            assistant_message="Updated: you ate 50 g out of the 180 g serving.",
            proposal=fifty_grams,
            ready_to_confirm=True,
        ),
        MealChatModelResult(
            assistant_message="How much sauce did you eat?",
            proposal=needs_detail,
            ready_to_confirm=False,
        ),
        MealChatModelResult(
            assistant_message="Added 10 g of sauce. This revised meal is ready to save.",
            proposal=final_proposal,
            ready_to_confirm=True,
        ),
    ]

    chat, payload = create_session(client, auth_headers)

    first = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "A 180 g serving of cooked rice"},
    )
    assert first.status_code == 200, first.text
    assert first.json()["ready_to_confirm"] is True
    assert first.json()["proposal"]["estimated_carbs_g"] == 52

    correction = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "Correction: I ate only 50 g out of those 180 g."},
    )
    assert correction.status_code == 200, correction.text
    assert correction.json()["ready_to_confirm"] is True
    assert correction.json()["proposal"]["total_portion_g"] == 50
    assert correction.json()["proposal"]["estimated_carbs_g"] == 14

    clarification = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "There was also some sauce, but I do not know how much yet."},
    )
    assert clarification.status_code == 200, clarification.text
    assert clarification.json()["ready_to_confirm"] is False
    assert clarification.json()["proposal"]["estimated_carbs_g"] == 15

    # The original 52 g and the intermediate ready 14 g drafts are both stale.
    blocked = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
    )
    assert blocked.status_code == 409
    assert client.get("/v1/intakes", headers=auth_headers).json()["items"] == []

    final_turn = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "It was exactly 10 g of sweet sauce."},
    )
    assert final_turn.status_code == 200, final_turn.text
    assert final_turn.json()["ready_to_confirm"] is True
    assert final_turn.json()["proposal"]["estimated_carbs_g"] == 18

    # Every refinement receives the preceding structured proposal as history.
    assert len(fake_chat_analyzer.calls) == 4
    assert '"estimated_carbs_g":52.0' in (
        fake_chat_analyzer.calls[1][0][-1].proposal_json or ""
    )
    assert '"estimated_carbs_g":14.0' in (
        fake_chat_analyzer.calls[2][0][-1].proposal_json or ""
    )
    assert '"estimated_carbs_g":15.0' in (
        fake_chat_analyzer.calls[3][0][-1].proposal_json or ""
    )

    current = client.get(
        f"/v1/meal-chat/sessions/{chat['id']}", headers=auth_headers
    )
    assert current.status_code == 200
    assert current.json()["ready_to_confirm"] is True
    assert current.json()["proposal"]["estimated_carbs_g"] == 18

    confirmed = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
    )
    assert confirmed.status_code == 200, confirmed.text
    assert confirmed.json()["client_event_id"] == payload["client_event_id"]
    assert confirmed.json()["meal_text"] == final_proposal.meal_description
    assert confirmed.json()["carbs_g"] == 18

    with sqlite3.connect(app.state.settings.database_path) as connection:
        latest_analysis_id = connection.execute(
            "SELECT latest_analysis_id FROM meal_chat_sessions WHERE id = ?",
            (chat["id"],),
        ).fetchone()[0]
    assert confirmed.json()["analysis_id"] == latest_analysis_id


def test_correction_without_a_replacement_proposal_revokes_stale_ready_draft(
    client,
    app,
    auth_headers,
    fake_chat_analyzer,
):
    fake_chat_analyzer.results = [
        FakeChatAnalyzer.default_result(),
        MealChatModelResult(
            assistant_message="Which food should replace the rice bowl?",
            proposal=None,
            ready_to_confirm=False,
        ),
    ]
    chat, _ = create_session(client, auth_headers)

    ready = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "A rice bowl"},
    )
    assert ready.status_code == 200
    assert ready.json()["ready_to_confirm"] is True

    cleared = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "No, remove that meal. I meant something else."},
    )
    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["ready_to_confirm"] is False
    assert cleared.json()["proposal"] is None

    fetched = client.get(
        f"/v1/meal-chat/sessions/{chat['id']}", headers=auth_headers
    ).json()
    assert fetched["ready_to_confirm"] is False
    assert fetched["proposal"] is None
    assert (
        client.post(
            f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
        ).status_code
        == 409
    )

    with sqlite3.connect(app.state.settings.database_path) as connection:
        latest_analysis_id, ready_to_confirm = connection.execute(
            "SELECT latest_analysis_id, ready_to_confirm "
            "FROM meal_chat_sessions WHERE id = ?",
            (chat["id"],),
        ).fetchone()
    assert latest_analysis_id is None
    assert ready_to_confirm == 0


def test_no_proposal_cannot_be_confirmed(client, auth_headers, fake_chat_analyzer):
    fake_chat_analyzer.results = [
        MealChatModelResult(
            assistant_message="What food did you eat?",
            proposal=None,
            ready_to_confirm=False,
        )
    ]
    chat, _ = create_session(client, auth_headers)
    turn = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        data={"text": "Something"},
    )
    assert turn.status_code == 200
    assert turn.json()["proposal"] is None
    assert turn.json()["ready_to_confirm"] is False
    confirm = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=auth_headers
    )
    assert confirm.status_code == 409


def test_photo_count_and_aggregate_limits_are_explicit(
    client, auth_headers, fake_chat_analyzer, jpeg_bytes, tmp_path
):
    chat, _ = create_session(client, auth_headers)
    too_many = client.post(
        f"/v1/meal-chat/sessions/{chat['id']}/messages",
        headers=auth_headers,
        files=[
            ("photos", (f"{index}.jpg", jpeg_bytes, "image/jpeg"))
            for index in range(25)
        ],
    )
    assert too_many.status_code == 422
    assert "24 photos" in too_many.json()["detail"]
    assert fake_chat_analyzer.calls == []

    aggregate_limit = len(jpeg_bytes) * 2 - 1
    settings = make_settings(
        tmp_path,
        database_path=tmp_path / "aggregate.db",
        meal_chat_max_aggregate_image_bytes=aggregate_limit,
    )
    aggregate_analyzer = FakeChatAnalyzer()
    aggregate_app = create_app(
        settings,
        analyzer=FakeAnalyzer(),
        chat_analyzer=aggregate_analyzer,
    )
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}
    with TestClient(aggregate_app) as aggregate_client:
        aggregate_chat, _ = create_session(aggregate_client, headers)
        response = aggregate_client.post(
            f"/v1/meal-chat/sessions/{aggregate_chat['id']}/messages",
            headers=headers,
            files=[
                ("photos", (f"{index}.jpg", jpeg_bytes, "image/jpeg"))
                for index in range(2)
            ],
        )
        assert response.status_code == 413
        assert str(aggregate_limit) in response.json()["detail"]
        assert aggregate_analyzer.calls == []


def test_meal_chat_history_survives_restart_and_can_then_be_confirmed(
    tmp_path, jpeg_bytes
):
    settings = make_settings(tmp_path)
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}
    first_app = create_app(
        settings,
        analyzer=FakeAnalyzer(),
        chat_analyzer=FakeChatAnalyzer(),
    )
    with TestClient(first_app) as first_client:
        chat, _ = create_session(first_client, headers)
        turn = first_client.post(
            f"/v1/meal-chat/sessions/{chat['id']}/messages",
            headers=headers,
            files={"photos": ("food.jpg", jpeg_bytes, "image/jpeg")},
        )
        assert turn.status_code == 200

    second_app = create_app(
        settings,
        analyzer=FakeAnalyzer(),
        chat_analyzer=FakeChatAnalyzer(),
    )
    with TestClient(second_app) as second_client:
        restored = second_client.get(
            f"/v1/meal-chat/sessions/{chat['id']}", headers=headers
        )
        assert restored.status_code == 200
        assert len(restored.json()["messages"]) == 2
        assert restored.json()["proposal"]["estimated_carbs_g"] == 52
        confirmed = second_client.post(
            f"/v1/meal-chat/sessions/{chat['id']}/confirm", headers=headers
        )
        assert confirmed.status_code == 200
        assert confirmed.json()["insulin_units"] is None


def test_meal_chat_fails_closed_without_openrouter_key_and_stores_no_turn(tmp_path):
    settings = make_settings(tmp_path, openrouter_api_key=None)
    application = create_app(settings, analyzer=FakeAnalyzer())
    headers = {"Authorization": f"Bearer {TEST_TOKEN}"}
    with TestClient(application) as local_client:
        chat, _ = create_session(local_client, headers)
        failed = local_client.post(
            f"/v1/meal-chat/sessions/{chat['id']}/messages",
            headers=headers,
            data={"text": "one apple"},
        )
        assert failed.status_code == 503
        assert failed.json()["detail"] == "AI service is not configured"
        restored = local_client.get(
            f"/v1/meal-chat/sessions/{chat['id']}", headers=headers
        )
        assert restored.json()["messages"] == []
        assert restored.json()["proposal"] is None
