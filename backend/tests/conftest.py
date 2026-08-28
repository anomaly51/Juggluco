from __future__ import annotations

from collections.abc import Sequence
from io import BytesIO
import wave

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.config import Settings
from app.main import create_app
from app.media import PreparedAudio, PreparedImage
from app.schemas import (
    AnalysisItem,
    MealAnalysis,
    MealChatModelResult,
    MealChatProposal,
)


TEST_TOKEN = "test-token-that-is-intentionally-longer-than-thirty-two-characters"


def valid_wav_bytes(duration_seconds: float = 0.25, sample_rate: int = 8_000) -> bytes:
    frame_count = int(duration_seconds * sample_rate)
    output = BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(1)
        audio.setframerate(sample_rate)
        audio.writeframes(b"\x80" * frame_count)
    return output.getvalue()


class FakeAnalyzer:
    def __init__(self):
        self.calls: list[tuple[str, Sequence[PreparedImage], PreparedAudio | None]] = []
        self.closed = False

    @property
    def model_name(self) -> str:
        return "test/vision-model"

    async def analyze(self, meal_text, images, audio):
        self.calls.append((meal_text, images, audio))
        return (
            MealAnalysis(
                meal_name="Rice bowl",
                meal_description="Rice with chicken and vegetables",
                estimated_carbs_g=52,
                carbs_low_g=42,
                carbs_high_g=66,
                confidence=0.76,
                items=[AnalysisItem(name="Cooked rice", portion_g=180, carbs_g=50)],
                assumptions=["The bowl contains about 180 g of cooked rice."],
                warnings=["Confirm the portion before saving."],
            ),
            "I ate a rice bowl" if audio else "",
        )

    async def transcribe(self, audio, language_hint=None):
        self.calls.append(("", [], audio))
        return "I ate a rice bowl"

    async def aclose(self):
        self.closed = True


class FakeChatAnalyzer:
    def __init__(self):
        self.calls = []
        self.closed = False
        self.results: list[MealChatModelResult] = []

    @property
    def model_name(self) -> str:
        return "test/meal-chat-model"

    @staticmethod
    def default_result() -> MealChatModelResult:
        return MealChatModelResult(
            assistant_message="I found a rice bowl. Please confirm the portion.",
            proposal=MealChatProposal(
                meal_name="Rice bowl",
                meal_description="Rice with chicken and vegetables",
                total_portion_g=350,
                estimated_carbs_g=52,
                carbs_low_g=42,
                carbs_high_g=66,
                confidence=0.76,
                items=[
                    AnalysisItem(name="Cooked rice", portion_g=180, carbs_g=50)
                ],
                warnings=["Confirm the portion before saving."],
            ),
            ready_to_confirm=True,
        )

    async def chat(self, history, meal_text, images, audio):
        self.calls.append((list(history), meal_text, images, audio))
        result = self.results.pop(0) if self.results else self.default_result()
        transcript = "I ate a rice bowl" if audio else ""
        return result, transcript

    async def aclose(self):
        self.closed = True


class FakeTranscriber:
    def __init__(self):
        self.calls: list[PreparedAudio] = []
        self.language_hints: list[str | None] = []
        self.closed = False

    async def transcribe(
        self,
        audio: PreparedAudio,
        language_hint: str | None = None,
    ) -> str:
        self.calls.append(audio)
        self.language_hints.append(language_hint)
        return "I drank one glass of milk"

    async def aclose(self):
        self.closed = True


def make_settings(tmp_path, **overrides) -> Settings:
    values = {
        "database_path": tmp_path / "juggluco-test.db",
        "api_token": TEST_TOKEN,
        "openrouter_api_key": None,
        "openrouter_vision_model": "test/vision-model",
        "openrouter_audio_model": "test/audio-model",
        "openrouter_base_url": "https://openrouter.invalid/api/v1",
        "openrouter_timeout_seconds": 5.0,
        "allowed_hosts": ("testserver", "127.0.0.1", "localhost"),
        "max_image_bytes": 1_000_000,
        "max_audio_bytes": 1_000_000,
        "openrouter_meal_chat_model": "test/meal-chat-model",
        "meal_chat_max_photos": 24,
        "meal_chat_max_aggregate_image_bytes": 4_000_000,
        "meal_chat_max_history_messages": 40,
    }
    values.update(overrides)
    return Settings(**values)


@pytest.fixture
def fake_analyzer():
    return FakeAnalyzer()


@pytest.fixture
def fake_chat_analyzer():
    return FakeChatAnalyzer()


@pytest.fixture
def fake_transcriber():
    return FakeTranscriber()


@pytest.fixture
def app(tmp_path, fake_analyzer, fake_chat_analyzer, fake_transcriber):
    return create_app(
        make_settings(tmp_path),
        analyzer=fake_analyzer,
        chat_analyzer=fake_chat_analyzer,
        transcriber=fake_transcriber,
    )


@pytest.fixture
def client(app):
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def auth_headers():
    return {"Authorization": f"Bearer {TEST_TOKEN}"}


@pytest.fixture
def jpeg_bytes():
    output = BytesIO()
    Image.new("RGB", (32, 24), (180, 100, 60)).save(output, format="JPEG")
    return output.getvalue()
