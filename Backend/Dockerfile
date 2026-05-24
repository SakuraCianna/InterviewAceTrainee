FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim

WORKDIR /app
ENV DATABASE_BACKUP_DIR=/app/database_backups

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml uv.lock* ./
RUN uv sync --no-dev

COPY app ./app
COPY alembic.ini ./alembic.ini
COPY migrations ./migrations

EXPOSE 8000
CMD ["sh", "-c", "uv run python -m app.cli.safe_migrate && uv run uvicorn app.main:app --host 0.0.0.0 --port 8000"]
