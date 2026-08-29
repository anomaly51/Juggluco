from __future__ import annotations

import mimetypes
from pathlib import Path

from fastapi import FastAPI, HTTPException, status
from fastapi.responses import RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException as StarletteHTTPException


mimetypes.add_type("application/manifest+json", ".webmanifest")


class SPAStaticFiles(StaticFiles):
    """Serve fingerprinted PWA files and an index fallback for UI-only routes."""

    async def get_response(self, path: str, scope) -> Response:
        normalized_path = path.lstrip("/")
        request_path = str(scope.get("path", "")).lstrip("/")
        is_asset_request = normalized_path.startswith("assets/") or request_path.startswith(
            ("assets/", "viewer/assets/")
        )
        try:
            response = await super().get_response(normalized_path, scope)
        except StarletteHTTPException as error:
            if error.status_code != status.HTTP_404_NOT_FOUND:
                raise
            response = None

        if response is not None and response.status_code != status.HTTP_404_NOT_FOUND:
            return response
        if (
            scope.get("method") not in {"GET", "HEAD"}
            or is_asset_request
            or Path(normalized_path).suffix
        ):
            if response is not None:
                return response
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)
        return await super().get_response("index.html", scope)


def mount_viewer_pwa(application: FastAPI, dist_path: Path) -> bool:
    """Mount the built React viewer when present without breaking backend-only tests."""

    resolved = dist_path.expanduser().resolve()
    if not (resolved / "index.html").is_file():

        @application.get("/viewer", include_in_schema=False)
        @application.get("/viewer/", include_in_schema=False)
        def viewer_not_built() -> None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="viewer PWA is not built; run the pwa production build first",
            )

        return False

    @application.get("/", include_in_schema=False)
    def viewer_home() -> RedirectResponse:
        return RedirectResponse(url="/viewer/", status_code=status.HTTP_307_TEMPORARY_REDIRECT)

    application.mount(
        "/viewer",
        SPAStaticFiles(directory=resolved, html=True),
        name="viewer-pwa",
    )
    return True
