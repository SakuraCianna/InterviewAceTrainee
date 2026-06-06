import math
import unittest

from app.cli.compare_capability_retrieval import compare_default_queries, default_query_cases
from app.schemas.interviews import InterviewType
from app.services.interview_capability_retrieval import (
    DEFAULT_EMBEDDING_MODEL,
    LOCAL_HASH_EMBEDDING_MODEL,
    LocalHashEmbeddingProvider,
    create_embedding_provider,
    embedding_model_name,
    load_capability_cards,
    retrieve_capability_cards,
)
from app.services.interview_presets import normalize_match_text


class KeywordEmbeddingProvider:
    model_name = "test-keyword-embedding"

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [_keyword_vector(text) for text in texts]


class InterviewCapabilityRetrievalTests(unittest.TestCase):
    def test_default_embedding_model_is_real_hugging_face_model(self) -> None:
        self.assertEqual(DEFAULT_EMBEDDING_MODEL, "BAAI/bge-small-zh-v1.5")
        self.assertNotEqual(embedding_model_name(), LOCAL_HASH_EMBEDDING_MODEL)

    def test_local_hash_provider_is_explicit_baseline_only(self) -> None:
        provider = create_embedding_provider(provider_name="local-hash")

        self.assertIsInstance(provider, LocalHashEmbeddingProvider)
        self.assertEqual(provider.model_name, LOCAL_HASH_EMBEDDING_MODEL)

    def test_loads_capability_cards_from_current_presets(self) -> None:
        cards = {card.id: card for card in load_capability_cards()}

        self.assertIn("fullstack-engineer", cards)
        self.assertIn("backend-java-engineer", cards)
        self.assertIn("law-major", cards.keys())
        self.assertIn(InterviewType.POSTGRADUATE, cards["law-major"].interview_types)

    def test_in_memory_embedding_retrieval_can_find_law_reexamination(self) -> None:
        law_case = compare_default_queries(KeywordEmbeddingProvider(), limit=3, include_local_hash=False)[2]

        embedding_matches = law_case["embedding"]
        self.assertIsInstance(embedding_matches, list)
        self.assertEqual(embedding_matches[0]["id"], "law-major")
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

def _keyword_vector(text: str) -> list[float]:
    normalized = normalize_match_text(text)
    features = [
        _contains_any(normalized, ("ai", "人工智能", "大模型", "rag", "向量")),
        _contains_any(normalized, ("全栈", "前端", "react", "端到端")),
        _contains_any(normalized, ("后端", "java", "spring", "fastapi", "redis", "mysql", "接口")),
        _contains_any(normalized, ("法学", "法律", "法条", "案例", "民商法", "部门法")),
    ]
    vector = [1.0 if feature else 0.0 for feature in features]
    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 0:
        return vector
    return [value / norm for value in vector]


def _contains_any(text: str, terms: tuple[str, ...]) -> bool:
    return any(term in text for term in terms)


if __name__ == "__main__":
    unittest.main()
