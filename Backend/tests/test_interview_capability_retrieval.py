from datetime import datetime
import math
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.cli.compare_capability_retrieval import compare_default_queries, default_query_cases
from app.schemas.interviews import InterviewType
from app.services.interview_capability_retrieval import (
    DEFAULT_EMBEDDING_MODEL,
    LOCAL_HASH_EMBEDDING_MODEL,
    LocalHashEmbeddingProvider,
    build_capability_vector_export,
    build_capability_prompt_context,
    capability_card_inventory,
    create_embedding_provider,
    embedding_model_name,
    import_capability_vector_store_from_export,
    load_capability_cards,
    local_text_embedding,
    retrieve_capability_cards,
)
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import normalize_match_text
from app.services.interview_runtime import build_interview_steps


class KeywordEmbeddingProvider:
    model_name = "test-keyword-embedding"

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [_keyword_vector(text) for text in texts]


class UnavailableEmbeddingProvider:
    model_name = DEFAULT_EMBEDDING_MODEL

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        raise RuntimeError("缺少 sentence-transformers")


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

    def test_default_embedding_model_is_real_hugging_face_model(self) -> None:
        self.assertEqual(DEFAULT_EMBEDDING_MODEL, "BAAI/bge-small-zh-v1.5")
        self.assertNotEqual(embedding_model_name(), LOCAL_HASH_EMBEDDING_MODEL)

    def test_local_hash_provider_is_explicit_baseline_only(self) -> None:
        provider = create_embedding_provider(provider_name="local-hash")

        self.assertIsInstance(provider, LocalHashEmbeddingProvider)
        self.assertEqual(provider.model_name, LOCAL_HASH_EMBEDDING_MODEL)

    def test_loads_reviewed_capability_cards(self) -> None:
        cards = {card.id: card for card in load_capability_cards()}

        self.assertIn("ai-agent-rag", cards)
        self.assertIn("backend-systems", cards)
        self.assertIn("law-humanities-method", cards)
        self.assertIn(InterviewType.POSTGRADUATE, cards["law-humanities-method"].interview_types)

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
        self.assertEqual(inventory["embedding_model"], "BAAI/bge-small-zh-v1.5")
        self.assertEqual(inventory["local_hash_dimensions"], 384)
        self.assertEqual(len(embedding), 384)
        self.assertGreater(sum(abs(value) for value in embedding), 0)

    def test_in_memory_embedding_retrieval_can_find_law_reexamination(self) -> None:
        law_case = compare_default_queries(KeywordEmbeddingProvider(), limit=3, include_local_hash=False)[2]

        embedding_matches = law_case["embedding"]
        self.assertIsInstance(embedding_matches, list)
        self.assertEqual(embedding_matches[0]["id"], "law-humanities-method")
        self.assertGreater(embedding_matches[0]["vector_score"], 0)

    def test_offline_compare_covers_expected_query_cases(self) -> None:
        results = compare_default_queries(KeywordEmbeddingProvider(), limit=3)
        queries = {item["query"]: item for item in results}

        self.assertEqual(set(queries), {"AI 全栈开发", "传统后端开发", "法学复试"})
        self.assertTrue(queries["AI 全栈开发"]["embedding"])
        self.assertTrue(queries["传统后端开发"]["embedding"])
        self.assertTrue(queries["法学复试"]["embedding"])
        self.assertIn("local_hash", queries["AI 全栈开发"])

    def test_retrieve_capability_cards_keeps_vector_score_visible(self) -> None:
        query_case = default_query_cases()[0]
        matches = retrieve_capability_cards(
            query_case.interview_type,
            material_context=query_case.material_context,
            round_name=query_case.round_name,
            limit=5,
            embedding_provider=KeywordEmbeddingProvider(),
        )

        self.assertTrue(matches)
        self.assertTrue(any(match.vector_score > 0 for match in matches))

    def test_retrieve_uses_stored_vector_anchor_when_runtime_provider_is_unavailable(self) -> None:
        query_case = default_query_cases()[0]

        with (
            patch("app.services.interview_capability_retrieval.capability_vector_table_ready", return_value=True),
            patch(
                "app.services.interview_capability_retrieval.search_capability_vectors_from_anchor_cards",
                return_value={"ai-agent-rag": 0.92},
            ) as anchor_search,
        ):
            matches = retrieve_capability_cards(
                query_case.interview_type,
                material_context=query_case.material_context,
                round_name=query_case.round_name,
                limit=5,
                db_connection=_RecordingConnection(),
                embedding_provider=UnavailableEmbeddingProvider(),
            )

        anchor_search.assert_called_once()
        self.assertTrue(any(match.id == "ai-agent-rag" and match.vector_score == 0.92 for match in matches))

    def test_offline_vector_export_can_be_imported_without_embedding_provider(self) -> None:
        payload = build_capability_vector_export(KeywordEmbeddingProvider(), batch_size=3)

        self.assertEqual(payload["schema_version"], 1)
        self.assertEqual(payload["embedding_model"], "test-keyword-embedding")
        self.assertEqual(len(payload["vectors"]), len(load_capability_cards()))
        self.assertEqual(payload["vectors"][0]["embedding_dimensions"], 4)

        with tempfile.TemporaryDirectory() as temp_dir:
            export_path = Path(temp_dir) / "capability_vectors.json"
            export_path.write_text(json_dumps(payload), encoding="utf-8")
            connection = _RecordingConnection()

            with patch("app.services.interview_capability_retrieval.capability_vector_table_ready", return_value=True):
                imported_count = import_capability_vector_store_from_export(connection, export_path)

        self.assertEqual(imported_count, len(load_capability_cards()))
        self.assertEqual(len(connection.parameters), imported_count)
        self.assertEqual(connection.parameters[0]["embedding_model"], "test-keyword-embedding")
        self.assertTrue(connection.parameters[0]["embedding"].startswith("["))


def _keyword_vector(text: str) -> list[float]:
    normalized = normalize_match_text(text)
    features = [
        _contains_any(normalized, ("ai", "人工智能", "大模型", "rag", "向量", "智能体", "agent")),
        _contains_any(normalized, ("全栈", "前端", "react", "端到端")),
        _contains_any(normalized, ("后端", "java", "spring", "fastapi", "redis", "mysql", "接口", "事务")),
        _contains_any(normalized, ("法学", "法律", "法条", "案例", "民商法", "部门法", "价值衡量")),
    ]
    vector = [1.0 if feature else 0.0 for feature in features]
    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 0:
        return vector
    return [value / norm for value in vector]


def _contains_any(text: str, terms: tuple[str, ...]) -> bool:
    return any(term in text for term in terms)


def json_dumps(payload: object) -> str:
    import json

    return json.dumps(payload, ensure_ascii=False)


class _RecordingConnection:
    def __init__(self) -> None:
        self.parameters: list[dict[str, object]] = []

    def execute(self, statement: object, parameters: dict[str, object] | None = None) -> object:
        if parameters is not None:
            self.parameters.append(parameters)
        return object()


if __name__ == "__main__":
    unittest.main()
