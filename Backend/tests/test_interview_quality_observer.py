import unittest

from app.schemas.interviews import InterviewType
from app.services.interview_quality_observer import (
    ObservedInterviewSession,
    ObservedInterviewTurn,
    evaluate_observed_interviews,
)
from app.cli.interview_quality_observe import database_unavailable_payload


class InterviewQualityObserverTests(unittest.TestCase):
    def test_observer_reports_real_record_risk_rates(self) -> None:
        report = evaluate_observed_interviews(
            [
                ObservedInterviewSession(
                    session_id="good-job",
                    interview_type=InterviewType.JOB,
                    material_context=None,
                    turns=[
                        ObservedInterviewTurn(0, "岗位理解", "请说明你对 Python 后端工程师岗位的理解。"),
                        ObservedInterviewTurn(1, "项目证据", "请介绍一个后端项目，说明你的职责和结果。"),
                        ObservedInterviewTurn(2, "方案取舍", "请说明一次技术方案取舍。"),
                        ObservedInterviewTurn(3, "指标复盘", "请用指标复盘项目结果。"),
                        ObservedInterviewTurn(4, "根因定位", "请讲一次线上问题根因定位。"),
                        ObservedInterviewTurn(5, "压力追问", "如果面试官质疑你的贡献，你如何回应？"),
                        ObservedInterviewTurn(6, "协作与动机", "请说明一次跨团队协作经历。"),
                        ObservedInterviewTurn(7, "终面收束", "请做一次终面总结。"),
                    ],
                ),
                ObservedInterviewSession(
                    session_id="bad-job-cross-scene",
                    interview_type=InterviewType.JOB,
                    material_context=None,
                    turns=[
                        ObservedInterviewTurn(0, "Part 1", "Let's talk about your hometown."),
                    ],
                ),
            ],
            min_samples=1,
        )

        self.assertFalse(report.passed)
        self.assertEqual(report.session_count, 2)
        self.assertGreater(report.wrong_question_risk_rate, 0.01)
        self.assertGreater(report.flow_error_risk_rate, 0.02)
        self.assertIn("bad-job-cross-scene", report.failure_summary)

    def test_observer_does_not_claim_pass_when_sample_is_too_small(self) -> None:
        report = evaluate_observed_interviews([], min_samples=5)

        self.assertFalse(report.passed)
        self.assertEqual(report.session_count, 0)
        self.assertEqual(report.current_session_count, 0)
        self.assertEqual(report.sample_status, "insufficient")
        self.assertIn("样本不足", report.failure_summary)

    def test_observer_excludes_known_legacy_flow_from_current_metrics(self) -> None:
        report = evaluate_observed_interviews(
            [
                ObservedInterviewSession(
                    session_id="legacy-postgraduate",
                    interview_type=InterviewType.POSTGRADUATE,
                    material_context=None,
                    turns=[
                        ObservedInterviewTurn(0, "复试开场", "请做一段 90 秒左右的中文自我介绍。"),
                        ObservedInterviewTurn(1, "专业基础", "请说明本科阶段最熟悉的一门专业课。"),
                        ObservedInterviewTurn(2, "科研潜力", "请介绍课程设计或竞赛项目。"),
                        ObservedInterviewTurn(3, "文献与英文", "请说明如何阅读英文论文。"),
                        ObservedInterviewTurn(4, "导师沟通", "请说明如何建立研究切入点。"),
                        ObservedInterviewTurn(5, "学术规范", "请说明实验结果不一致时如何处理。"),
                    ],
                ),
                ObservedInterviewSession(
                    session_id="legacy-civil",
                    interview_type=InterviewType.CIVIL_SERVICE,
                    material_context=None,
                    turns=[
                        ObservedInterviewTurn(0, "综合分析", "请谈谈你对基层数字化治理的理解。"),
                        ObservedInterviewTurn(1, "组织协调", "如果让你组织社区政策宣讲活动，你会如何做？"),
                        ObservedInterviewTurn(2, "应急应变", "现场群众情绪激动，你会如何处理？"),
                        ObservedInterviewTurn(3, "人际沟通", "如果同事无法配合，你会如何沟通？"),
                        ObservedInterviewTurn(4, "岗位匹配", "请说明你如何理解服务意识。"),
                    ],
                ),
            ],
            min_samples=1,
        )

        self.assertFalse(report.passed)
        self.assertEqual(report.session_count, 2)
        self.assertEqual(report.legacy_session_count, 2)
        self.assertEqual(report.current_session_count, 0)
        self.assertEqual(report.flow_error_risk_rate, 0.0)
        self.assertFalse(report.flow_failures)
        postgraduate_metric = report.scenario_metrics[InterviewType.POSTGRADUATE.value]
        civil_metric = report.scenario_metrics[InterviewType.CIVIL_SERVICE.value]
        self.assertEqual(postgraduate_metric.current_session_count, 0)
        self.assertEqual(postgraduate_metric.legacy_session_count, 1)
        self.assertEqual(postgraduate_metric.turn_count, 0)
        self.assertEqual(civil_metric.current_session_count, 0)
        self.assertEqual(civil_metric.legacy_session_count, 1)
        self.assertEqual(civil_metric.turn_count, 0)
        self.assertIn("旧流程", report.failure_summary)

    def test_observer_serializes_metrics_for_cli(self) -> None:
        report = evaluate_observed_interviews([], min_samples=1).to_dict()

        self.assertIn("wrong_question_risk_rate", report)
        self.assertIn("flow_error_risk_rate", report)
        self.assertIn("sample_status", report)
        self.assertIn("current_session_count", report)
        self.assertIn("legacy_session_count", report)
        self.assertIn("scenario_metrics", report)
        self.assertIn("current_session_count", report["scenario_metrics"][InterviewType.JOB.value])
        self.assertIn("legacy_session_count", report["scenario_metrics"][InterviewType.JOB.value])

    def test_observer_cli_serializes_database_unavailable(self) -> None:
        payload = database_unavailable_payload("database does not exist")

        self.assertFalse(payload["passed"])
        self.assertEqual(payload["sample_status"], "database_unavailable")
        self.assertIn("database", payload["failure_summary"])


if __name__ == "__main__":
    unittest.main()
