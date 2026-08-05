"""Local-only ASGI server used by Android UI smoke tests.

Run from ``backend`` with ``uvicorn e2e_server:app --app-dir tests``.  It keeps
the real HTTP, SQLite, validation, idempotency, and confirmation paths while
replacing only the paid model provider with deterministic test analyzers.
"""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

from app.config import Settings
from app.main import create_app
from conftest import FakeAnalyzer, FakeChatAnalyzer


settings = replace(
    Settings.from_env(),
    database_path=(Path(__file__).resolve().parents[2]
                   / "work" / "meal-chat-e2e.db"),
    # A non-secret marker makes /health describe the deterministic provider as
    # configured. No request from this test server is sent to OpenRouter.
    openrouter_api_key="local-e2e-provider-not-a-real-key",
    openrouter_vision_model="test/vision-model",
    openrouter_audio_model="test/audio-model",
    openrouter_meal_chat_model="test/meal-chat-model",
)

app = create_app(
    settings,
    analyzer=FakeAnalyzer(),
    chat_analyzer=FakeChatAnalyzer(),
)
