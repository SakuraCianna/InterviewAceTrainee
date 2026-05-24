from __future__ import annotations

import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError

from app.core.config import get_settings

BACKUP_DIR_NAME = "database_backups"
BACKUP_KEEP_COUNT = 3


def main() -> int:
    backend_dir = Path(__file__).resolve().parents[2]
    project_root = backend_dir.parent
    settings = get_settings()

    try:
        run_safe_migration(database_url=settings.database_url, project_root=project_root, backend_dir=backend_dir)
    except RuntimeError as exc:
        print(f"safe migration failed: {exc}", file=sys.stderr)
        return 1
    return 0


def run_safe_migration(database_url: str, project_root: Path, backend_dir: Path) -> Path:
    ensure_database_reachable(database_url)

    backup_root = project_root / BACKUP_DIR_NAME
    backup_root.mkdir(parents=True, exist_ok=True)
    backup_path = timestamped_backup_path(
        database_url=database_url,
        backup_root=backup_root,
        timestamp=datetime.now().strftime("%Y%m%d_%H%M%S"),
    )
    create_database_backup(database_url=database_url, backup_path=backup_path)
    prune_old_backups(backup_root, keep_count=BACKUP_KEEP_COUNT)
    run_alembic_upgrade(backend_dir)
    return backup_path


def ensure_database_reachable(database_url: str) -> None:
    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except SQLAlchemyError as exc:
        raise RuntimeError("database is not reachable; backup and migration were not run") from exc
    finally:
        engine.dispose()


def create_database_backup(database_url: str, backup_path: Path) -> None:
    pg_dump_path = os.environ.get("PG_DUMP_PATH") or shutil.which("pg_dump")
    if not pg_dump_path:
        raise RuntimeError("pg_dump was not found; install PostgreSQL client tools or set PG_DUMP_PATH")

    command_args, environment = build_pg_dump_command(
        database_url=database_url,
        backup_path=backup_path,
        pg_dump_path=pg_dump_path,
        base_environment=os.environ,
    )
    completed = subprocess.run(command_args, env=environment, capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        message = completed.stderr.strip() or completed.stdout.strip() or "pg_dump failed"
        raise RuntimeError(message)


def build_pg_dump_command(
    database_url: str,
    backup_path: Path,
    pg_dump_path: str,
    base_environment: os._Environ[str] | dict[str, str],
) -> tuple[list[str], dict[str, str]]:
    url = make_url(database_url)
    environment = dict(base_environment)
    if url.password:
        environment["PGPASSWORD"] = url.password

    command_args = [
        pg_dump_path,
        "--format=custom",
        "--file",
        str(backup_path),
    ]
    if url.host:
        command_args.extend(["--host", url.host])
    if url.port:
        command_args.extend(["--port", str(url.port)])
    if url.username:
        command_args.extend(["--username", url.username])
    if not url.database:
        raise RuntimeError("database name is missing in DATABASE_URL")
    command_args.extend(["--dbname", url.database])
    return command_args, environment


def timestamped_backup_path(database_url: str, backup_root: Path, timestamp: str) -> Path:
    url = make_url(database_url)
    if not url.database:
        raise RuntimeError("database name is missing in DATABASE_URL")
    safe_database_name = "".join(char if char.isalnum() or char in {"-", "_"} else "_" for char in url.database)
    return backup_root / f"{safe_database_name}_{timestamp}.dump"


def prune_old_backups(backup_root: Path, keep_count: int = BACKUP_KEEP_COUNT) -> None:
    backup_files = sorted(backup_root.glob("*.dump"), key=lambda path: path.stat().st_mtime, reverse=True)
    for backup_file in backup_files[keep_count:]:
        backup_file.unlink()


def run_alembic_upgrade(backend_dir: Path) -> None:
    config = Config(str(backend_dir / "alembic.ini"))
    command.upgrade(config, "head")


if __name__ == "__main__":
    raise SystemExit(main())
