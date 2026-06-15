import unittest
from unittest.mock import patch

from app.services import admin_dashboard


class AdminDashboardCacheTests(unittest.TestCase):
    def tearDown(self) -> None:
        if hasattr(admin_dashboard, "clear_admin_dashboard_stats_cache"):
            admin_dashboard.clear_admin_dashboard_stats_cache()

    def test_dashboard_stats_reuses_short_lived_cache_and_returns_copy(self) -> None:
        if hasattr(admin_dashboard, "clear_admin_dashboard_stats_cache"):
            admin_dashboard.clear_admin_dashboard_stats_cache()

        with (
            patch.object(admin_dashboard, "recent_day_labels", return_value=[]),
            patch.object(admin_dashboard, "count_scalar", return_value=1) as count_scalar,
            patch.object(admin_dashboard, "optional_float_scalar", return_value=88.8),
            patch.object(admin_dashboard, "series_by_day", return_value=[]),
            patch.object(admin_dashboard, "distribution_by_column", return_value=[]),
            patch.object(admin_dashboard, "read_top_users", return_value=[]),
        ):
            first_payload = admin_dashboard.build_admin_dashboard_stats(object())
            first_payload.overview.total_users = 999
            second_payload = admin_dashboard.build_admin_dashboard_stats(object())

        self.assertEqual(count_scalar.call_count, 16)
        self.assertEqual(second_payload.overview.total_users, 1)

    def test_dashboard_stats_does_not_cache_database_unavailable_payload(self) -> None:
        if hasattr(admin_dashboard, "clear_admin_dashboard_stats_cache"):
            admin_dashboard.clear_admin_dashboard_stats_cache()

        with patch.object(admin_dashboard, "empty_dashboard_stats", wraps=admin_dashboard.empty_dashboard_stats) as empty_stats:
            first_payload = admin_dashboard.build_admin_dashboard_stats(None)
            second_payload = admin_dashboard.build_admin_dashboard_stats(None)

        self.assertFalse(first_payload.database_ready)
        self.assertFalse(second_payload.database_ready)
        self.assertEqual(empty_stats.call_count, 2)


if __name__ == "__main__":
    unittest.main()
