from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from dataclasses import dataclass

from fastapi import Header, HTTPException, Request, status


VIEWER_SESSION_COOKIE = "__Host-juggluco-viewer"
VIEWER_SESSION_VERSION = 1
VIEWER_SESSION_MAX_TTL_SECONDS = 365 * 24 * 60 * 60
VIEWER_SESSION_CLOCK_SKEW_SECONDS = 60
VIEWER_SESSION_MAX_ENCODED_BYTES = 2_048
PUBLIC_VIEWER_PATHS = frozenset(
    {
        "/v1/viewer/snapshot",
        "/v1/viewer/glucose",
        "/v1/viewer/stream",
    }
)


@dataclass(frozen=True, slots=True)
class ViewerAccess:
    method: str
    expires_at_ms: int | None = None


def _bearer_value(authorization: str | None) -> str | None:
    scheme, separator, supplied = (authorization or "").partition(" ")
    if not separator or scheme.lower() != "bearer" or not supplied:
        return None
    return supplied


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _b64decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    decoded = base64.b64decode(
        value + padding,
        altchars=b"-_",
        validate=True,
    )
    if _b64encode(decoded) != value:
        raise ValueError("non-canonical base64url encoding")
    return decoded


def _viewer_session_key(settings) -> bytes:
    if not settings.viewer_auth_configured:
        raise ValueError("viewer authentication is not configured")
    return hashlib.sha256(
        b"juggluco-viewer-session-v1\0" + settings.viewer_token.encode("utf-8")
    ).digest()


def viewer_token_matches(settings, supplied: str) -> bool:
    """Check only the least-privilege viewer token, never the admin token."""

    return bool(
        settings.viewer_auth_configured
        and _secret_matches(supplied, settings.viewer_token)
    )


def _secret_matches(supplied: str, expected: str) -> bool:
    """Compare arbitrary Unicode credentials without ``compare_digest`` TypeErrors."""

    return secrets.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8"))


def issue_viewer_session(settings, *, now_seconds: int | None = None) -> tuple[str, int]:
    """Create a bounded signed browser session without embedding the viewer token."""

    issued_at = int(time.time()) if now_seconds is None else int(now_seconds)
    max_age = int(settings.viewer_session_days) * 24 * 60 * 60
    expires_at = issued_at + max_age
    payload = json.dumps(
        {"exp": expires_at, "iat": issued_at, "v": VIEWER_SESSION_VERSION},
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    signature = hmac.digest(_viewer_session_key(settings), payload, "sha256")
    return f"{_b64encode(payload)}.{_b64encode(signature)}", expires_at * 1_000


def viewer_session_expiry_ms(
    settings,
    encoded: str | None,
    *,
    now_seconds: int | None = None,
) -> int | None:
    """Return the authenticated session expiry, or ``None`` for any invalid input."""

    if (
        not settings.viewer_auth_configured
        or not encoded
        or len(encoded.encode("utf-8")) > VIEWER_SESSION_MAX_ENCODED_BYTES
    ):
        return None
    try:
        encoded_payload, encoded_signature = encoded.split(".", 1)
        payload = _b64decode(encoded_payload)
        signature = _b64decode(encoded_signature)
        expected = hmac.digest(_viewer_session_key(settings), payload, "sha256")
        if not hmac.compare_digest(signature, expected):
            return None
        decoded = json.loads(payload)
        if not isinstance(decoded, dict) or set(decoded) != {"exp", "iat", "v"}:
            return None
        version = decoded["v"]
        issued_at = decoded["iat"]
        expires_at = decoded["exp"]
        if any(type(value) is not int for value in (version, issued_at, expires_at)):
            return None
        if version != VIEWER_SESSION_VERSION:
            return None
        now = int(time.time()) if now_seconds is None else int(now_seconds)
        ttl = expires_at - issued_at
        if (
            issued_at > now + VIEWER_SESSION_CLOCK_SKEW_SECONDS
            or expires_at <= now
            or ttl <= 0
            or ttl > VIEWER_SESSION_MAX_TTL_SECONDS
        ):
            return None
        return expires_at * 1_000
    except (UnicodeError, ValueError, TypeError, KeyError, json.JSONDecodeError):
        return None


def _invalid_viewer_credentials() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="invalid viewer credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )


def require_api_token(
    request: Request,
    authorization: str | None = Header(default=None),
) -> None:
    settings = request.app.state.settings
    if not settings.auth_configured:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="backend authentication is not configured",
        )
    supplied = _bearer_value(authorization)
    if supplied is None or not _secret_matches(supplied, settings.api_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )


def require_viewer_token(
    request: Request,
    authorization: str | None = Header(default=None),
) -> ViewerAccess:
    """Authorize a read-only companion client without granting write access.

    The existing API token remains a superuser credential for backwards
    compatibility and operations/debugging.  The optional viewer token is
    deliberately accepted only by the separate GET-only viewer router.  An
    explicitly enabled public viewer bypasses credentials only on that same
    GET-only router; it never changes ``require_api_token``.
    """

    settings = request.app.state.settings
    if settings.viewer_public:
        if (
            request.method not in {"GET", "HEAD"}
            or request.url.path not in PUBLIC_VIEWER_PATHS
        ):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="public viewer access is limited to glucose",
            )
        return ViewerAccess(method="public")

    admin_configured = settings.auth_configured
    viewer_configured = settings.viewer_auth_configured
    if not admin_configured and not viewer_configured:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="backend viewer authentication is not configured",
        )

    # An explicitly supplied Authorization header must stand on its own.  Never
    # let a malformed or stale bearer silently fall back to a browser cookie.
    if authorization is not None:
        supplied = _bearer_value(authorization)
        admin_matches = bool(
            supplied is not None
            and admin_configured
            and _secret_matches(supplied, settings.api_token)
        )
        viewer_matches = bool(
            supplied is not None
            and viewer_configured
            and _secret_matches(supplied, settings.viewer_token)
        )
        if not admin_matches and not viewer_matches:
            raise _invalid_viewer_credentials()
        return ViewerAccess(method="bearer")

    expires_at_ms = viewer_session_expiry_ms(
        settings,
        request.cookies.get(VIEWER_SESSION_COOKIE),
    )
    if expires_at_ms is None:
        raise _invalid_viewer_credentials()
    return ViewerAccess(method="session", expires_at_ms=expires_at_ms)
