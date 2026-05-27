from __future__ import annotations

import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError

from app.core.config import get_settings

BACKUP_DIR_NAME = "database_backups"
BACKUP_KEEP_COUNT = 5
BASELINE_REVISION = "20260523_0001"
REMOVED_DEV_REVISIONS = {"20260525_0002", "20260525_0003"}


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


def run_safe_migration(database_url: str, project_root: Path, backend_dir: Path) -> Path | None:
    ensure_database_reachable(database_url)

    backup_root = resolve_backup_root(project_root)
    backup_root.mkdir(parents=True, exist_ok=True)
    should_backup = has_pending_database_operations(database_url=database_url, backend_dir=backend_dir)
    backup_path: Path | None = None
    if should_backup:
        backup_path = timestamped_backup_path(
            database_url=database_url,
            backup_root=backup_root,
            timestamp=datetime.now().strftime("%Y%m%d_%H%M%S"),
        )
        create_database_backup(database_url=database_url, backup_path=backup_path)
        prune_old_backups(backup_root, keep_count=BACKUP_KEEP_COUNT)
        normalize_removed_dev_revisions(database_url)
        print(f"database backup created before migration: {backup_path}")
    else:
        print("database backup skipped: alembic version is already up to date")

    run_alembic_upgrade(backend_dir)
    return backup_path


def resolve_backup_root(project_root: Path) -> Path:
    configured_backup_dir = os.environ.get("DATABASE_BACKUP_DIR")
    if configured_backup_dir:
        return Path(configured_backup_dir)
    return project_root / BACKUP_DIR_NAME


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


def has_pending_database_operations(database_url: str, backend_dir: Path) -> bool:
    current_versions, has_existing_schema = inspect_database_migration_state(database_url)
    if current_versions & REMOVED_DEV_REVISIONS:
        return True

    head_versions = get_script_head_versions(backend_dir)
    if current_versions:
        return current_versions != head_versions

    return has_existing_schema


def inspect_database_migration_state(database_url: str) -> tuple[set[str], bool]:
    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            inspector = inspect(connection)
            table_names = set(inspector.get_table_names())
            business_tables = table_names - {"alembic_version"}
            if "alembic_version" not in table_names:
                return set(), bool(business_tables)

            rows = connection.execute(text("SELECT version_num FROM alembic_version")).scalars().all()
            return {str(row) for row in rows if row}, bool(business_tables)
    finally:
        engine.dispose()


def get_script_head_versions(backend_dir: Path) -> set[str]:
    config = Config(str(backend_dir / "alembic.ini"))
    script = ScriptDirectory.from_config(config)
    return set(script.get_heads())


def normalize_removed_dev_revisions(database_url: str) -> None:
    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with engine.begin() as connection:
            inspector = inspect(connection)
            if "alembic_version" not in inspector.get_table_names():
                return

            version = connection.execute(text("SELECT version_num FROM alembic_version LIMIT 1")).scalar()
            if version not in REMOVED_DEV_REVISIONS:
                return

            if inspector.has_table("interview_materials"):
                columns = {column["name"] for column in inspector.get_columns("interview_materials")}
                if "target_school" not in columns:
                    connection.execute(text("ALTER TABLE interview_materials ADD COLUMN target_school VARCHAR(160)"))

            connection.execute(text("UPDATE alembic_version SET version_num = :version"), {"version": BASELINE_REVISION})
    finally:
        engine.dispose()


def run_alembic_upgrade(backend_dir: Path) -> None:
    config = Config(str(backend_dir / "alembic.ini"))
    command.upgrade(config, "head")


if __name__ == "__main__":
    raise SystemExit(main())
