import unittest
from datetime import datetime

from app.schemas.interviews import InterviewType
from app.services.interview_capability_retrieval import (
    build_capability_prompt_context,
    capability_card_inventory,
    local_text_embedding,
    retrieve_capability_cards,
)
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_runtime import build_interview_steps


class InterviewCapabilityRetrievalTests(unittest.TestCase):
    def job_context(self, job_title: str, job_requirements: str, resume_text: str, keywords: list[str]) -> InterviewMaterialContext:
        return InterviewMaterialContext(
            id=f"material-{job_title}",
            user_email="candidate@example.com",
            interview_type=InterviewType.JOB,
            resume_filename="resume.pdf",
            resume_content_type="application/pdf",
            resume_text=resume_text,
            job_title=job_title,
            job_requirements=job_requirements,
            target_school=None,
            major=None,
            research_direction=None,
            profile_summary=resume_text,
            keywords=keywords,
            created_at=datetime(2026, 6, 6),
        )

    def postgraduate_context(self, major: str, research_direction: str) -> InterviewMaterialContext:
        return InterviewMaterialContext(
            id=f"material-{major}",
            user_email="student@example.com",
            interview_type=InterviewType.POSTGRADUATE,
            resume_filename=None,
            resume_content_type=None,
            resume_text=None,
            job_title=None,
            job_requirements=None,
            target_school="清华大学",
            major=major,
            research_direction=research_direction,
            profile_summary=f"报考专业是{major}，方向是{research_direction}。",
            keywords=[major, research_direction],
            created_at=datetime(2026, 6, 6),
        )

    def test_ai_fullstack_hits_shared_foundations_and_agent_rag_cards(self) -> None:
        context = self.job_context(
            job_title="AI 全栈开发工程师",
            job_requirements="负责 React、FastAPI、RAG 检索、Agent 工具调用和提示词注入防护。",
            resume_text="我做过智能面试 Agent 项目，负责向量库召回、工具调用编排和安全评测。",
            keywords=["RAG", "Agent", "FastAPI", "提示词注入"],
        )

        matches = retrieve_capability_cards(InterviewType.JOB, context, limit=4)
        match_ids = {match.id for match in matches}
        questions = " ".join(step.question_text for step in build_interview_steps(InterviewType.JOB, context, session_id="ai-fullstack-card-case"))

        self.assertIn("ai-agent-rag", match_ids)
        self.assertIn("computer-foundations-shared", match_ids)
        self.assertIn("RAG 召回边界", questions)
        self.assertIn("Agent 工具调用", questions)
        self.assertIn("提示词注入防护", questions)
        self.assertIn("计算机基础", questions)

    def test_backend_hits_shared_foundations_without_design_leakage(self) -> None:
        context = self.job_context(
            job_title="Python 后端工程师",
            job_requirements="负责 FastAPI、PostgreSQL、Redis、接口幂等、事务一致性和线上稳定性。",
            resume_text="订单系统项目：我负责缓存策略、数据库索引优化、链路追踪和灰度回滚。",
            keywords=["FastAPI", "PostgreSQL", "Redis", "接口幂等"],
        )

        questions = " ".join(step.question_text for step in build_interview_steps(InterviewType.JOB, context, session_id="backend-card-case"))

        self.assertIn("接口稳定性", questions)
        self.assertIn("事务边界", questions)
        self.assertIn("计算机基础", questions)
        self.assertNotIn("作品集逻辑", questions)
        self.assertNotIn("法条体系", questions)

    def test_postgraduate_computer_reuses_foundations_and_ai_method_cards(self) -> None:
        context = self.postgraduate_context("计算机科学与技术", "大模型教育应用与 RAG 评测")

        prompt_context = build_capability_prompt_context(InterviewType.POSTGRADUATE, context, round_name="专业基础")

        self.assertIn("计算机通用基础", prompt_context)
        self.assertIn("AI 应用与 Agent/RAG", prompt_context)
        self.assertIn("数据结构与复杂度", prompt_context)
        self.assertIn("提示词注入防护", prompt_context)

    def test_capability_inventory_and_local_embedding_are_stable(self) -> None:
        inventory = capability_card_inventory()
        embedding = local_text_embedding("AI 全栈 RAG Agent 数据库索引")

        self.assertGreaterEqual(inventory["card_count"], 9)
        self.assertGreaterEqual(inventory["by_interview_type"]["job"], 6)
        self.assertEqual(inventory["embedding_dimensions"], 384)
        self.assertEqual(len(embedding), 384)
        self.assertGreater(sum(abs(value) for value in embedding), 0)


if __name__ == "__main__":
    unittest.main()
