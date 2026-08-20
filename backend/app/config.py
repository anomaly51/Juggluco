from __future__ import annotations

import os
import secrets
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


BACKEND_ROOT = Path(__file__).resolve().parents[1]


def _positive_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def _positive_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be a number") from error
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


@dataclass(frozen=True, slots=True)
class Settings:
    database_path: Path
    api_token: str | None
    openrouter_api_key: str | None
    openrouter_vision_model: str
    openrouter_audio_model: str
    openrouter_base_url: str
    openrouter_timeout_seconds: float
    allowed_hosts: tuple[str, ...]
    max_image_bytes: int
    max_audio_bytes: int
    openrouter_meal_chat_model: str = "qwen/qwen3-vl-8b-instruct"
    meal_chat_max_photos: int = 24
    meal_chat_max_aggregate_image_bytes: int = 32 * 1024 * 1024
    meal_chat_max_history_messages: int = 40
    # Optional least-privilege credential for GET-only viewer routes.  Keeping
    # it separate from ``api_token`` means a companion device never needs the
    # Android/admin credential that can create or edit health records.
    viewer_token: str | None = None

    def __post_init__(self) -> None:
        if self.viewer_token is not None and len(self.viewer_token) < 32:
            raise ValueError("JUGGLUCO_VIEWER_TOKEN must be at least 32 characters")
        if (
            self.viewer_token is not None
            and self.api_token is not None
            and secrets.compare_digest(self.viewer_token, self.api_token)
        ):
            raise ValueError(
                "JUGGLUCO_VIEWER_TOKEN must differ from JUGGLUCO_API_TOKEN"
            )

    @classmethod
    def from_env(cls) -> "Settings":
        load_dotenv(BACKEND_ROOT / ".env", override=False)

        configured_path = os.getenv("JUGGLUCO_DATABASE_PATH", "./data/juggluco.db")
        database_path = Path(configured_path).expanduser()
        if not database_path.is_absolute():
            database_path = (BACKEND_ROOT / database_path).resolve()

        hosts = tuple(
            host.strip()
            for host in os.getenv(
                "JUGGLUCO_ALLOWED_HOSTS",
                "127.0.0.1,localhost,testserver,10.0.2.2",
            ).split(",")
            if host.strip()
        )
        if not hosts:
            raise ValueError("JUGGLUCO_ALLOWED_HOSTS must contain at least one host")

        return cls(
            database_path=database_path,
            api_token=os.getenv("JUGGLUCO_API_TOKEN") or None,
            openrouter_api_key=os.getenv("OPENROUTER_API_KEY") or None,
            openrouter_vision_model=os.getenv(
                "OPENROUTER_VISION_MODEL", "qwen/qwen3-vl-8b-instruct"
            ),
            openrouter_audio_model=os.getenv(
                "OPENROUTER_AUDIO_MODEL", "google/gemini-2.5-flash-lite"
            ),
            openrouter_base_url=os.getenv(
                "OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"
            ).rstrip("/"),
            openrouter_timeout_seconds=_positive_float(
                "OPENROUTER_TIMEOUT_SECONDS", 90.0
            ),
            allowed_hosts=hosts,
            max_image_bytes=_positive_int("JUGGLUCO_MAX_IMAGE_BYTES", 8 * 1024 * 1024),
            max_audio_bytes=_positive_int(
                "JUGGLUCO_MAX_AUDIO_BYTES", 15 * 1024 * 1024
            ),
            openrouter_meal_chat_model=os.getenv(
                "OPENROUTER_MEAL_CHAT_MODEL", "qwen/qwen3-vl-8b-instruct"
            ),
            meal_chat_max_photos=_positive_int(
                "JUGGLUCO_MEAL_CHAT_MAX_PHOTOS", 24
            ),
            meal_chat_max_aggregate_image_bytes=_positive_int(
                "JUGGLUCO_MEAL_CHAT_MAX_AGGREGATE_IMAGE_BYTES",
                32 * 1024 * 1024,
            ),
            meal_chat_max_history_messages=_positive_int(
                "JUGGLUCO_MEAL_CHAT_MAX_HISTORY_MESSAGES", 40
            ),
            viewer_token=os.getenv("JUGGLUCO_VIEWER_TOKEN") or None,
        )

    @property
    def auth_configured(self) -> bool:
        return self.api_token is not None and len(self.api_token) >= 32

    @property
    def openrouter_configured(self) -> bool:
        return bool(self.openrouter_api_key)

    @property
    def viewer_auth_configured(self) -> bool:
        return self.viewer_token is not None and len(self.viewer_token) >= 32
