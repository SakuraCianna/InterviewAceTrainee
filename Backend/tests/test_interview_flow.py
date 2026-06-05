import unittest
from datetime import datetime

from app.api.interviews import answer_interview_question
from app.core.config import Settings
from app.schemas.interviews import InterviewAnswerRequest, InterviewType
from app.services.ai_call_logs import InMemoryAICallLogStore
from app.services.ai_router import AIProviderConfig
from app.services.content_safety_logs import InMemoryContentSafetyLogStore
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_ai import assess_answer_quality
from app.services.interview_question_bank import question_bank_inventory, postgraduate_school_tier
from app.services.interview_runtime import InMemoryInterviewRuntimeStore, build_interview_steps
from app.services.provider_configs import InMemoryProviderConfigStore


class InterviewFlowTests(unittest.TestCase):
    def disabled_provider_store(self) -> InMemoryProviderConfigStore:
        return InMemoryProviderConfigStore(
            [
                AIProviderConfig(
                    id="disabled-test-llm",
                    provider_type="llm",
                    purpose="interview",
                    enabled=False,
                    priority=10,
                    provider_name="deepseek",
                    model_name="deepseek-v4-flash",
                )
            ]
        )

    def postgraduate_context(self, target_school: str) -> InterviewMaterialContext:
        return InterviewMaterialContext(
            id=f"material-{target_school}",
            user_email="student@example.com",
            interview_type=InterviewType.POSTGRADUATE,
            resume_filename=None,
            resume_content_type=None,
            resume_text=None,
            job_title=None,
            job_requirements=None,
            target_school=target_school,
            major="计算机科学与技术",
            research_direction="大模型教育应用",
            profile_summary="目标院校与报考专业已填写。",
            keywords=["计算机", "大模型", "教育"],
            created_at=datetime(2026, 6, 5),
        )

    def test_filler_answer_stays_on_current_question(self) -> None:
        decision = assess_answer_quality(
            InterviewType.POSTGRADUATE,
            "请做一段 90 秒左右的中文自我介绍。",
            "你好你好",
            "复试开场",
        )

        self.assertFalse(decision.acceptable)
        self.assertEqual(decision.reason_code, "filler")
        self.assertIn("不会进入下一题", decision.retry_question or "")

    def test_generic_job_answer_requires_structured_signals(self) -> None:
        decision = assess_answer_quality(
            InterviewType.JOB,
            "请介绍一个最能证明你能胜任这个岗位的项目。",
            "我之前参加过一个项目，整体过程比较顺利，我也比较努力，和团队配合得还可以，最后大家觉得效果不错。",
            "专业一面",
        )

        self.assertFalse(decision.acceptable)
        self.assertEqual(decision.reason_code, "too_generic")
        self.assertIn("岗位匹配", decision.retry_question or "")

    def test_preset_question_bank_separates_exam_scenarios(self) -> None:
        civil_steps = build_interview_steps(InterviewType.CIVIL_SERVICE, session_id="civil-bank-case")
        ielts_steps = build_interview_steps(InterviewType.IELTS, session_id="ielts-bank-case")

        self.assertIn("基层", civil_steps[0].question_text)
        self.assertIn("群众", civil_steps[0].question_text)
        self.assertTrue(ielts_steps[0].question_text.startswith("Let's talk"))
        self.assertNotEqual(civil_steps[0].question_text, ielts_steps[0].question_text)

    def test_postgraduate_question_difficulty_follows_school_tier(self) -> None:
        elite_steps = build_interview_steps(
            InterviewType.POSTGRADUATE,
            self.postgraduate_context("清华大学"),
            session_id="postgraduate-elite-case",
        )
        standard_steps = build_interview_steps(
            InterviewType.POSTGRADUATE,
            self.postgraduate_context("普通地方学院"),
            session_id="postgraduate-standard-case",
        )

        self.assertIn("顶尖院校复试", elite_steps[0].question_text)
        self.assertIn("文献差异", elite_steps[3].question_text)
        self.assertIn("基础复试", standard_steps[0].question_text)
        self.assertNotEqual(elite_steps[1].question_text, standard_steps[1].question_text)

    def test_postgraduate_school_tier_uses_formal_school_config(self) -> None:
        self.assertEqual(postgraduate_school_tier("北京大学"), "elite")
        self.assertEqual(postgraduate_school_tier("华南理工大学"), "high")
        self.assertEqual(postgraduate_school_tier("湖南师范大学"), "advanced")
        self.assertEqual(postgraduate_school_tier("普通地方学院"), "standard")

    def test_question_bank_is_rich_and_ordered_by_difficulty(self) -> None:
        inventory = question_bank_inventory()
        expected_min_choices = {
            "job": 10,
            "postgraduate": 10,
            "civil_service": 10,
            "ielts": 10,
        }

        for scenario, min_choices in expected_min_choices.items():
            self.assertIn(scenario, inventory)
            for round_info in inventory[scenario]["rounds"]:
                self.assertGreaterEqual(
                    round_info["choice_count"],
                    min_choices,
                    f"{scenario} {round_info['round_name']} choices are too thin",
                )
            self.assertEqual(
                inventory[scenario]["difficulty_scores"],
                sorted(inventory[scenario]["difficulty_scores"]),
                f"{scenario} questions must progress from easier to harder",
            )
            if scenario != "ielts":
                self.assertGreaterEqual(
                    inventory[scenario]["min_question_chars"],
                    28,
                    f"{scenario} question bank contains rough short questions",
                )

    def test_answer_api_requires_supplement_before_advancing(self) -> None:
        store = InMemoryInterviewRuntimeStore()
        store.create_session("student@example.com", "retry-session", InterviewType.POSTGRADUATE)
        claims = {"sub": "student@example.com", "role": "user", "session_id": "auth-session"}

        retry_response = answer_interview_question(
            session_id="retry-session",
            payload=InterviewAnswerRequest(answer_text="你好你好"),
            claims=claims,
            interview_store=store,
            provider_store=self.disabled_provider_store(),
            ai_call_log_store=InMemoryAICallLogStore(),
            content_safety_log_store=InMemoryContentSafetyLogStore(),
            settings=Settings(),
        )

        self.assertEqual(retry_response.status, "in_progress")
        self.assertEqual(retry_response.current_step_index, 0)
        self.assertIsNotNone(retry_response.current_question)
        self.assertIn("不会进入下一题", retry_response.current_question.text)

        accepted_response = answer_interview_question(
            session_id="retry-session",
            payload=InterviewAnswerRequest(
                answer_text=(
                    "我本科阶段系统学习过数据结构、计算机网络和机器学习课程，也做过智能教育方向的课程项目。"
                    "报考这个专业是因为我希望继续研究大模型在学习反馈和个性化训练中的应用。"
                )
            ),
            claims=claims,
            interview_store=store,
            provider_store=self.disabled_provider_store(),
            ai_call_log_store=InMemoryAICallLogStore(),
            content_safety_log_store=InMemoryContentSafetyLogStore(),
            settings=Settings(),
        )

        self.assertEqual(accepted_response.current_step_index, 1)
        self.assertIsNotNone(accepted_response.current_question)
        self.assertEqual(accepted_response.current_question.round_name, "专业基础")
        self.assertIn("你刚才提到", accepted_response.current_question.text)


if __name__ == "__main__":
    unittest.main()
