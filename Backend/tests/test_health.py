import unittest
from unittest.mock import patch

from app.api import health


class HealthReadinessTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
