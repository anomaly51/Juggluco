from __future__ import annotations

import ipaddress
import os
import re
import secrets
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


BACKEND_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = BACKEND_ROOT.parent
DEFAULT_PWA_DIST_PATH = (REPOSITORY_ROOT / "pwa" / "dist").resolve()


_AUDIO_LANGUAGE_TAG = re.compile(
    r"^[A-Za-z]{2}(?:[-_][A-Za-z0-9]{2,8})*$"
)
_VIEWER_TOKEN = re.compile(r"^[A-Za-z0-9._~-]{32,512}$")


def normalize_audio_language(value: str | None) -> str | None:
    """Return the ISO-639-1 primary language for a configured locale hint.

    OpenRouter's transcription endpoint accepts a two-letter language.  The
    Android/API boundary may naturally supply a BCP-47 locale such as
    ``ru-RU``, so accept a deliberately small tag shape and send only its
    primary subtag.  Blank values and ``auto`` preserve provider detection.
    """

    clean = (value or "").strip()
    if not clean or clean.casefold() == "auto":
        return None
    if len(clean) > 35 or _AUDIO_LANGUAGE_TAG.fullmatch(clean) is None:
        raise ValueError(
            "audio language must be auto or a two-letter/BCP-47 language tag"
        )
    return re.split(r"[-_]", clean, maxsplit=1)[0].lower()


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


def _boolean(name: str, default: bool = False) -> bool:
    """Parse an explicit boolean environment flag without truthy surprises."""

    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    normalized = raw.strip().casefold()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    raise ValueError(f"{name} must be true or false")


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
    openrouter_audio_language: str | None = None
    openrouter_meal_chat_model: str = "qwen/qwen3-vl-8b-instruct"
    meal_chat_max_photos: int = 24
    meal_chat_max_aggregate_image_bytes: int = 32 * 1024 * 1024
    meal_chat_max_history_messages: int = 40
    # Optional least-privilege credential for GET-only viewer routes.  Keeping
    # it separate from ``api_token`` means a companion device never needs the
    # Android/admin credential that can create or edit health records.
    viewer_token: str | None = None
    # Deliberate opt-in for link-only, anonymous GET access to health data.
    # It never changes authentication on the admin/write router.
    viewer_public: bool = False
    viewer_session_days: int = 30
    pwa_dist_path: Path = DEFAULT_PWA_DIST_PATH
    viewer_trusted_proxy_cidrs: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        try:
            normalized_audio_language = normalize_audio_language(
                self.openrouter_audio_language
            )
        except ValueError as error:
            raise ValueError(
                "OPENROUTER_AUDIO_LANGUAGE must be auto or a valid language tag"
            ) from error
        object.__setattr__(
            self, "openrouter_audio_language", normalized_audio_language
        )
        if (
            self.viewer_token is not None
            and _VIEWER_TOKEN.fullmatch(self.viewer_token) is None
        ):
            raise ValueError(
                "JUGGLUCO_VIEWER_TOKEN must contain between 32 and 512 "
                "URL-safe ASCII characters"
            )
        if (
            self.viewer_token is not None
            and self.api_token is not None
            and secrets.compare_digest(
                self.viewer_token.encode("utf-8"), self.api_token.encode("utf-8")
            )
        ):
            raise ValueError(
                "JUGGLUCO_VIEWER_TOKEN must differ from JUGGLUCO_API_TOKEN"
            )
        if not 1 <= self.viewer_session_days <= 365:
            raise ValueError("JUGGLUCO_VIEWER_SESSION_DAYS must be between 1 and 365")
        normalized_proxy_cidrs: list[str] = []
        for configured_cidr in self.viewer_trusted_proxy_cidrs:
            try:
                network = ipaddress.ip_network(configured_cidr, strict=False)
            except ValueError as error:
                raise ValueError(
                    "JUGGLUCO_VIEWER_TRUSTED_PROXY_CIDRS must contain only IP networks"
                ) from error
            normalized_proxy_cidrs.append(str(network))
        object.__setattr__(
            self,
            "viewer_trusted_proxy_cidrs",
            tuple(normalized_proxy_cidrs),
        )
        object.__setattr__(self, "pwa_dist_path", self.pwa_dist_path.expanduser().resolve())

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

        configured_pwa_dist = Path(
            os.getenv("JUGGLUCO_PWA_DIST_PATH", str(DEFAULT_PWA_DIST_PATH))
        ).expanduser()
        if not configured_pwa_dist.is_absolute():
            configured_pwa_dist = (REPOSITORY_ROOT / configured_pwa_dist).resolve()

        return cls(
            database_path=database_path,
            api_token=os.getenv("JUGGLUCO_API_TOKEN") or None,
            openrouter_api_key=os.getenv("OPENROUTER_API_KEY") or None,
            openrouter_vision_model=os.getenv(
                "OPENROUTER_VISION_MODEL", "qwen/qwen3-vl-8b-instruct"
            ),
            openrouter_audio_model=os.getenv(
                "OPENROUTER_AUDIO_MODEL", "openai/whisper-large-v3-turbo"
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
            openrouter_audio_language=os.getenv(
                "OPENROUTER_AUDIO_LANGUAGE"
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
            viewer_public=_boolean("JUGGLUCO_VIEWER_PUBLIC"),
            viewer_session_days=_positive_int("JUGGLUCO_VIEWER_SESSION_DAYS", 30),
            pwa_dist_path=configured_pwa_dist,
            viewer_trusted_proxy_cidrs=tuple(
                value.strip()
                for value in os.getenv(
                    "JUGGLUCO_VIEWER_TRUSTED_PROXY_CIDRS", ""
                ).split(",")
                if value.strip()
            ),
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

    @property
    def viewer_access_configured(self) -> bool:
        return self.viewer_public or self.viewer_auth_configured or self.auth_configured
