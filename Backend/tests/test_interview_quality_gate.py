import unittest

from app.services.interview_quality_gate import evaluate_interview_quality


class InterviewQualityGateTests(unittest.TestCase):
    def test_quality_gate_reports_product_metric_thresholds(self) -> None:
        report = evaluate_interview_quality()

        self.assertTrue(report.passed, report.failure_summary)
        self.assertLessEqual(report.wrong_question_risk_rate, 0.01)
        self.assertLessEqual(report.flow_error_risk_rate, 0.02)
        self.assertGreaterEqual(report.sample_case_count, 16)
        self.assertEqual(report.scenario_count, 4)
        self.assertGreaterEqual(report.total_question_candidates, 350)
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


if __name__ == "__main__":
    unittest.main()
