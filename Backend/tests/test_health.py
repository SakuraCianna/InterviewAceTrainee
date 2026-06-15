import unittest
from unittest.mock import patch

from app.api import health


class HealthReadinessTests(unittest.TestCase):
    def tearDown(self) -> None:
        if hasattr(health, "clear_health_check_cache"):
            health.clear_health_check_cache()

    def test_readiness_includes_interview_core_and_degrades_when_not_ready(self) -> None:
        with (
            patch.object(health, "check_database", return_value={"ready": True}),
            patch.object(health, "check_redis", return_value={"ready": True}),
            patch.object(health, "check_email", return_value={"ready": True}),
            patch.object(health, "check_auth", return_value={"ready": True}),
            patch.object(
                health,
                "check_interview_core",
                return_value={"ready": False, "failure_summary": "interview_capability_vectors 未就绪"},
            ),
        ):
            payload = health.read_readiness()

        self.assertEqual(payload["status"], "degraded")
        self.assertIn("interview_core", payload["checks"])
        self.assertFalse(payload["checks"]["interview_core"]["ready"])

    def test_readiness_is_ready_when_required_checks_pass(self) -> None:
        with (
            patch.object(health, "check_database", return_value={"ready": True}),
            patch.object(health, "check_redis", return_value={"ready": False}),
            patch.object(health, "check_email", return_value={"ready": True}),
            patch.object(health, "check_auth", return_value={"ready": True}),
            patch.object(health, "check_interview_core", return_value={"ready": True}),
        ):
            payload = health.read_readiness()

        self.assertEqual(payload["status"], "ready")
        self.assertFalse(payload["checks"]["redis"]["ready"])

    def test_interview_core_endpoint_returns_dedicated_observation(self) -> None:
        with patch.object(
            health,
            "check_interview_core",
            return_value={"ready": True, "failure_summary": "核心面试业务观测通过"},
        ):
            payload = health.read_interview_core_readiness()

        self.assertTrue(payload["ready"])
        self.assertEqual(payload["failure_summary"], "核心面试业务观测通过")

    def test_database_check_reuses_short_lived_cache(self) -> None:
        if hasattr(health, "clear_health_check_cache"):
            health.clear_health_check_cache()

        with patch.object(health, "inspect", return_value=_FakeInspector()) as inspect_database:
            first_payload = health.check_database()
            second_payload = health.check_database()

        self.assertTrue(first_payload["ready"])
        self.assertTrue(second_payload["ready"])
        self.assertEqual(inspect_database.call_count, 1)

    def test_interview_core_check_reuses_short_lived_cache_and_returns_copy(self) -> None:
        if hasattr(health, "clear_health_check_cache"):
            health.clear_health_check_cache()

        with (
            patch.object(health, "engine", _FakeEngine()),
            patch.object(
                health,
                "observe_interview_core_readiness",
                return_value=_FakeObservation({"ready": True, "failure_summary": "核心面试业务观测通过"}),
            ) as observe,
        ):
            first_payload = health.check_interview_core()
            first_payload["ready"] = False
            second_payload = health.check_interview_core()

        self.assertTrue(second_payload["ready"])
        self.assertEqual(observe.call_count, 1)


class _FakeInspector:
    def has_table(self, table_name: str) -> bool:
        return True


class _FakeEngine:
    def connect(self) -> "_FakeConnectionContext":
        return _FakeConnectionContext()


class _FakeConnectionContext:
    def __enter__(self) -> object:
        return object()

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        return None


class _FakeObservation:
    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def to_dict(self) -> dict[str, object]:
        return dict(self._payload)


if __name__ == "__main__":
    unittest.main()
