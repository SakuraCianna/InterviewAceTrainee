import unittest

from app.api.interviews import answer_interview_question
from app.core.config import Settings
from app.schemas.interviews import InterviewAnswerRequest, InterviewType
from app.services.ai_call_logs import InMemoryAICallLogStore
from app.services.ai_router import AIProviderConfig
from app.services.content_safety_logs import InMemoryContentSafetyLogStore
from app.services.interview_ai import assess_answer_quality
from app.services.interview_runtime import InMemoryInterviewRuntimeStore
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
