import unittest
from unittest.mock import patch

from app.schemas.interviews import InterviewType
from app.services import interview_quality_gate as quality_gate
from app.services.interview_quality_gate import QualityScenarioCase, evaluate_interview_quality
from app.services.interview_runtime import InterviewStep


class InterviewQualityGateTests(unittest.TestCase):
    def test_quality_gate_reports_product_metric_thresholds(self) -> None:
        report = evaluate_interview_quality()

        self.assertTrue(report.passed, report.failure_summary)
        self.assertLessEqual(report.wrong_question_risk_rate, 0.01)
        self.assertLessEqual(report.flow_error_risk_rate, 0.02)
        self.assertGreaterEqual(report.sample_case_count, 23)
        self.assertEqual(report.scenario_count, 4)
        self.assertGreaterEqual(report.total_question_candidates, 350)
        self.assertGreaterEqual(report.scenario_metrics["job"].case_count, 7)
        self.assertGreaterEqual(report.scenario_metrics["postgraduate"].case_count, 7)
        self.assertFalse(report.flow_failures)
        self.assertFalse(report.question_mismatch_failures)
        self.assertFalse(report.answer_quality_failures)

    def test_quality_gate_serializes_for_cli_and_admin_visibility(self) -> None:
        payload = evaluate_interview_quality().to_dict()

        self.assertIn("passed", payload)
        self.assertIn("wrong_question_risk_rate", payload)
        self.assertIn("flow_error_risk_rate", payload)
        self.assertIn("scenario_metrics", payload)
        self.assertEqual(set(payload["scenario_metrics"]), {"job", "postgraduate", "civil_service", "ielts"})
        self.assertIn("工作面试", payload["scenario_metrics"]["job"]["label"])

    def test_quality_gate_fails_when_one_session_repeats_question_text(self) -> None:
        repeated_steps = [
            InterviewStep(round_name, "请说明你如何定位线上问题？")
            for round_name in quality_gate.EXPECTED_ROUNDS[InterviewType.JOB]
        ]

        with (
            patch(
                "app.services.interview_quality_gate._quality_scenario_cases",
                return_value=[
                    QualityScenarioCase(
                        name="duplicate-job-session",
                        interview_type=InterviewType.JOB,
                        session_id="duplicate-job-session",
                        material_context=None,
                        required_terms=(),
                    )
                ],
            ),
            patch("app.services.interview_quality_gate._answer_quality_cases", return_value=[]),
            patch("app.services.interview_quality_gate.build_interview_steps", return_value=repeated_steps),
        ):
            report = evaluate_interview_quality()

        self.assertFalse(report.passed)
        self.assertTrue(
            any("duplicate question" in failure for failure in report.flow_failures),
            report.failure_summary,
        )


if __name__ == "__main__":
    unittest.main()
