import os
from pathlib import Path
from app.cli.safe_migrate import (
    build_pg_dump_command,
    prune_old_backups,
    timestamped_backup_path,
)


def test_build_pg_dump_command_uses_pg_env_password_without_putting_password_in_args():
    command, environment = build_pg_dump_command(
        database_url="postgresql+psycopg://postgres:secret@127.0.0.1:5432/mianba",
        backup_path=Path("database_backups/mianba.dump"),
        pg_dump_path="pg_dump",
        base_environment={"PATH": "test-path"},
    )

    assert command == [
        "pg_dump",
        "--format=custom",
        "--file",
        "database_backups\\mianba.dump",
        "--host",
        "127.0.0.1",
        "--port",
        "5432",
        "--username",
        "postgres",
        "--dbname",
        "mianba",
    ]
    assert environment["PGPASSWORD"] == "secret"
    assert "secret" not in " ".join(command)


def test_timestamped_backup_path_uses_database_name_and_timestamp(tmp_path):
    path = timestamped_backup_path(
        database_url="postgresql+psycopg://postgres:secret@127.0.0.1:5432/mianba",
        backup_root=tmp_path,
        timestamp="20260524_143012",
    )

    assert path == tmp_path / "mianba_20260524_143012.dump"


def test_prune_old_backups_keeps_latest_five_matching_dump_files_by_default(tmp_path):
    backups = []
    for index in range(7):
        path = tmp_path / f"mianba_20260524_1430{index}.dump"
        path.write_text("backup", encoding="utf-8")
        timestamp = 1_800_000_000 + index
        path.touch()
        os.utime(path, (timestamp, timestamp))
        backups.append(path)

    prune_old_backups(tmp_path)

    remaining = sorted(item.name for item in tmp_path.glob("*.dump"))
    assert remaining == [
        "mianba_20260524_14302.dump",
        "mianba_20260524_14303.dump",
        "mianba_20260524_14304.dump",
        "mianba_20260524_14305.dump",
        "mianba_20260524_14306.dump",
    ]
