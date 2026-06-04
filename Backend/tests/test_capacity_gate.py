import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app.services.capacity_gate import acquire_capacity


class CapacityGateTests(unittest.TestCase):
    def test_local_capacity_fallback_releases_slots(self) -> None:
        settings = SimpleNamespace(capacity_key_prefix="test-capacity")
        with (
            patch("app.services.capacity_gate.get_redis_client", return_value=None),
            patch("app.services.capacity_gate.get_settings", return_value=settings),
        ):
            first = acquire_capacity("llm", limit=1, lease_seconds=30)
            self.assertTrue(first.acquired)

            blocked = acquire_capacity("llm", limit=1, lease_seconds=30)
            self.assertFalse(blocked.acquired)
            self.assertEqual(blocked.active_count, 1)

            first.release()
            second = acquire_capacity("llm", limit=1, lease_seconds=30)
            self.assertTrue(second.acquired)
            second.release()


if __name__ == "__main__":
    unittest.main()
