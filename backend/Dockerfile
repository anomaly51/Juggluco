FROM node:22-alpine AS pwa-build

WORKDIR /source/pwa

RUN corepack enable && corepack prepare pnpm@11.19.0 --activate

COPY pwa/package.json pwa/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY pwa/ ./
RUN pnpm build


FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    JUGGLUCO_DATABASE_PATH=/data/juggluco.db \
    JUGGLUCO_PWA_DIST_PATH=/app/pwa/dist

WORKDIR /app

COPY backend/pyproject.toml backend/README.md ./
COPY backend/app ./app
COPY --from=pwa-build /source/pwa/dist ./pwa/dist

RUN python -m pip install --no-cache-dir . \
    && groupadd --gid 10001 juggluco \
    && useradd --uid 10001 --gid juggluco --no-create-home --shell /usr/sbin/nologin juggluco \
    && mkdir -p /data \
    && chown juggluco:juggluco /data

USER 10001:10001

EXPOSE 8765

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8765"]
