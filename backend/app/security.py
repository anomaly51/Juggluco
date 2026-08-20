from __future__ import annotations

import secrets

from fastapi import Header, HTTPException, Request, status


def _bearer_value(authorization: str | None) -> str | None:
    scheme, separator, supplied = (authorization or "").partition(" ")
    if not separator or scheme.lower() != "bearer" or not supplied:
        return None
    return supplied


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
    if supplied is None or not secrets.compare_digest(supplied, settings.api_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )


def require_viewer_token(
    request: Request,
    authorization: str | None = Header(default=None),
) -> None:
    """Authorize a read-only companion client without granting write access.

    The existing API token remains a superuser credential for backwards
    compatibility and operations/debugging.  The optional viewer token is
    deliberately accepted only by the separate GET-only viewer router.
    """

    settings = request.app.state.settings
    admin_configured = settings.auth_configured
    viewer_configured = settings.viewer_auth_configured
    if not admin_configured and not viewer_configured:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="backend viewer authentication is not configured",
        )

    supplied = _bearer_value(authorization)
    admin_matches = bool(
        supplied is not None
        and admin_configured
        and secrets.compare_digest(supplied, settings.api_token)
    )
    viewer_matches = bool(
        supplied is not None
        and viewer_configured
        and secrets.compare_digest(supplied, settings.viewer_token)
    )
    if not admin_matches and not viewer_matches:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
