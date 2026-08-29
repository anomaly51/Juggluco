FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS pwa-build

WORKDIR /source/pwa

RUN corepack enable && corepack prepare pnpm@11.19.0 --activate

COPY pwa/package.json pwa/pnpm-lock.yaml pwa/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY pwa/ ./
RUN pnpm build


FROM python:3.13-slim@sha256:7ce4b6dfe35e55397b7cda544f8a13f191b7ae28dc5aad71fe664dbc9bc2623f

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    JUGGLUCO_DATABASE_PATH=/data/juggluco.db \
    JUGGLUCO_PWA_DIST_PATH=/app/pwa/dist

WORKDIR /app

COPY backend/pyproject.toml backend/README.md ./
COPY backend/app ./app

RUN python -m pip install --no-cache-dir . \
    && groupadd --gid 10001 juggluco \
    && useradd --uid 10001 --gid juggluco --no-create-home --shell /usr/sbin/nologin juggluco \
    && mkdir -p /data \
    && chown juggluco:juggluco /data

COPY --from=pwa-build /source/pwa/dist ./pwa/dist

USER 10001:10001

EXPOSE 8765

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8765"]
