FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY pyproject.toml README.md ./
COPY app ./app

RUN python -m pip install --no-cache-dir . \
    && groupadd --gid 10001 juggluco \
    && useradd --uid 10001 --gid juggluco --no-create-home --shell /usr/sbin/nologin juggluco \
    && mkdir -p /data \
    && chown juggluco:juggluco /data

USER 10001:10001

EXPOSE 8765

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8765"]
