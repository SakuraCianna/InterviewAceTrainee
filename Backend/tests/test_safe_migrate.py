import os
import tempfile
import unittest
from pathlib import Path

from sqlalchemy import create_engine, text

from app.cli.safe_migrate import (
    BASELINE_REVISION,
    REMOVED_DEV_REVISIONS,
    has_pending_database_operations_for_state,
    normalize_removed_dev_revisions,
    prune_old_backups,
)


class SafeMigrateTests(unittest.TestCase):
    def test_head_version_present_with_stale_rows_does_not_trigger_backup(self) -> None:
        self.assertFalse(
            has_pending_database_operations_for_state(
                current_versions={"20260528_0002", "20260523_0001", *REMOVED_DEV_REVISIONS},
                has_existing_schema=True,
                head_versions={"20260528_0002"},
            )
        )

    def test_missing_head_triggers_backup(self) -> None:
        self.assertTrue(
            has_pending_database_operations_for_state(
                current_versions={"20260523_0001"},
                has_existing_schema=True,
                head_versions={"20260528_0002"},
            )
        )

    def test_prune_old_backups_keeps_newest_five(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backup_root = Path(temp_dir)
            for index in range(8):
                path = backup_root / f"mianba_20260528_00000{index}.dump"
                path.write_text("backup", encoding="utf-8")
                os.utime(path, (index, index))

            prune_old_backups(backup_root, keep_count=5)

            remaining_names = sorted(path.name for path in backup_root.glob("*.dump"))
            self.assertEqual(
                remaining_names,
                [f"mianba_20260528_00000{index}.dump" for index in range(3, 8)],
            )

    def test_normalize_removed_dev_revisions_deletes_stale_rows_when_head_exists(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database_url = self._create_sqlite_database(temp_dir, ["20260528_0002", *REMOVED_DEV_REVISIONS])

            normalize_removed_dev_revisions(database_url)

            self.assertEqual(self._read_versions(database_url), {"20260528_0002"})

    def test_normalize_removed_dev_revisions_inserts_baseline_when_only_removed_versions_exist(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database_url = self._create_sqlite_database(temp_dir, list(REMOVED_DEV_REVISIONS))

            normalize_removed_dev_revisions(database_url)

            self.assertEqual(self._read_versions(database_url), {BASELINE_REVISION})

    def _create_sqlite_database(self, temp_dir: str, versions: list[str]) -> str:
        database_path = Path(temp_dir) / "mianba.db"
        database_url = f"sqlite+pysqlite:///{database_path.as_posix()}"
        engine = create_engine(database_url)
        try:
            with engine.begin() as connection:
                connection.execute(text("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)"))
                for version in versions:
                    connection.execute(
                        text("INSERT INTO alembic_version (version_num) VALUES (:version)"),
                        {"version": version},
                    )
        finally:
            engine.dispose()
        return database_url

    def _read_versions(self, database_url: str) -> set[str]:
        engine = create_engine(database_url)
        try:
            with engine.connect() as connection:
                return set(connection.execute(text("SELECT version_num FROM alembic_version")).scalars())
        finally:
            engine.dispose()


if __name__ == "__main__":
    unittest.main()
