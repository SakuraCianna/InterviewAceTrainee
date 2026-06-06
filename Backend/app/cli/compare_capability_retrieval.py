import argparse
from dataclasses import dataclass
from datetime import datetime
import json
import sys

from app.schemas.interviews import InterviewType
from app.services.interview_capability_retrieval import (
    CapabilityCardMatch,
    LocalHashEmbeddingProvider,
    TextEmbeddingProvider,
    create_embedding_provider,
    retrieve_capability_cards,
)
from app.services.interview_material_context import InterviewMaterialContext


@dataclass(frozen=True)
class RetrievalQueryCase:
    name: str
    interview_type: InterviewType
    material_context: InterviewMaterialContext
    round_name: str


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare lexical, local-hash and real embedding capability retrieval.")
    parser.add_argument("--provider", default=None, help="Embedding provider, defaults to CAPABILITY_EMBEDDING_PROVIDER.")
    parser.add_argument("--model", default=None, help="Hugging Face model name, defaults to CAPABILITY_EMBEDDING_MODEL.")
    parser.add_argument("--device", default=None, help="Optional sentence-transformers device, such as cpu or cuda.")
    parser.add_argument("--batch-size", type=int, default=None, help="Embedding batch size.")
    parser.add_argument("--limit", type=int, default=5, help="Top matches to print for each method.")
    parser.add_argument(
        "--no-local-hash",
        action="store_true",
        help="Skip the explicit local-hash-v1 baseline.",
    )
    args = parser.parse_args()

    try:
        provider = create_embedding_provider(
            provider_name=args.provider,
            model_name=args.model,
            device=args.device,
            batch_size=args.batch_size,
        )
        payload = compare_default_queries(
            embedding_provider=provider,
            limit=max(1, args.limit),
            include_local_hash=not args.no_local_hash,
        )
    except (RuntimeError, ValueError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1

    print(json.dumps({"ok": True, "results": payload}, ensure_ascii=False, indent=2))
    return 0


def compare_default_queries(
    embedding_provider: TextEmbeddingProvider,
    limit: int = 5,
    include_local_hash: bool = True,
) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    local_hash_provider = LocalHashEmbeddingProvider() if include_local_hash else None
    for query_case in default_query_cases():
        lexical_matches = retrieve_capability_cards(
            query_case.interview_type,
            material_context=query_case.material_context,
            round_name=query_case.round_name,
            limit=limit,
        )
        embedding_matches = retrieve_capability_cards(
            query_case.interview_type,
            material_context=query_case.material_context,
            round_name=query_case.round_name,
            limit=limit,
            embedding_provider=embedding_provider,
        )
        case_payload: dict[str, object] = {
            "query": query_case.name,
            "interview_type": query_case.interview_type.value,
            "round_name": query_case.round_name,
            "embedding_model": embedding_provider.model_name,
            "lexical": _serialize_matches(lexical_matches),
            "embedding": _serialize_matches(embedding_matches),
            "embedding_vs_lexical_overlap": _top_id_overlap(embedding_matches, lexical_matches),
        }
        if local_hash_provider is not None:
            local_hash_matches = retrieve_capability_cards(
                query_case.interview_type,
                material_context=query_case.material_context,
                round_name=query_case.round_name,
                limit=limit,
                embedding_provider=local_hash_provider,
            )
            case_payload["local_hash"] = _serialize_matches(local_hash_matches)
            case_payload["embedding_vs_local_hash_overlap"] = _top_id_overlap(
                embedding_matches,
                local_hash_matches,
            )
        results.append(case_payload)
    return results


def default_query_cases() -> list[RetrievalQueryCase]:
    return [
        RetrievalQueryCase(
            name="AI 全栈开发",
            interview_type=InterviewType.JOB,
            round_name="专业一面",
            material_context=_material_context(
                case_id="compare-ai-fullstack",
                interview_type=InterviewType.JOB,
                job_title="AI 全栈开发工程师",
                job_requirements="负责 AI 应用、RAG 检索、React 前端、FastAPI 后端、PostgreSQL、部署监控和端到端交付。",
                profile_summary="做过大模型应用、向量检索、前后端联调、接口设计和上线运维。",
                keywords=["AI", "全栈", "RAG", "React", "FastAPI", "PostgreSQL", "部署"],
            ),
        ),
        RetrievalQueryCase(
            name="传统后端开发",
            interview_type=InterviewType.JOB,
            round_name="专业一面",
            material_context=_material_context(
                case_id="compare-traditional-backend",
                interview_type=InterviewType.JOB,
                job_title="Java 后端开发工程师",
                job_requirements="负责 Spring Boot 接口、MySQL 事务、Redis 缓存、消息队列、慢 SQL 优化和线上稳定性。",
                profile_summary="后端业务系统开发，关注接口、数据库、中间件、并发和排障。",
                keywords=["Java", "后端", "Spring Boot", "MySQL", "Redis", "消息队列"],
            ),
        ),
        RetrievalQueryCase(
            name="法学复试",
            interview_type=InterviewType.POSTGRADUATE,
            round_name="专业基础",
            material_context=_material_context(
                case_id="compare-law-postgraduate",
                interview_type=InterviewType.POSTGRADUATE,
                target_school="中国政法大学",
                major="法学",
                research_direction="民商法与案例分析",
                profile_summary="准备法学研究生复试，关注法理基础、部门法、法条适用和案例争点。",
                keywords=["法学", "法律硕士", "民商法", "案例分析", "法条适用"],
            ),
        ),
    ]


def _material_context(
    case_id: str,
    interview_type: InterviewType,
    job_title: str | None = None,
    job_requirements: str | None = None,
    target_school: str | None = None,
    major: str | None = None,
    research_direction: str | None = None,
    profile_summary: str = "",
    keywords: list[str] | None = None,
) -> InterviewMaterialContext:
    return InterviewMaterialContext(
        id=case_id,
        user_email="offline-compare@example.com",
        interview_type=interview_type,
        resume_filename=None,
        resume_content_type=None,
        resume_text=None,
        job_title=job_title,
        job_requirements=job_requirements,
        target_school=target_school,
        major=major,
        research_direction=research_direction,
        profile_summary=profile_summary,
        keywords=keywords or [],
        created_at=datetime(2026, 6, 6),
    )


def _serialize_matches(matches: list[CapabilityCardMatch]) -> list[dict[str, object]]:
    return [
        {
            "id": match.card.id,
            "title": match.card.title,
            "score": match.score,
            "lexical_score": match.lexical_score,
            "vector_score": match.vector_score,
        }
        for match in matches
    ]


def _top_id_overlap(left: list[CapabilityCardMatch], right: list[CapabilityCardMatch]) -> list[str]:
    right_ids = {match.card.id for match in right}
    return [match.card.id for match in left if match.card.id in right_ids]


if __name__ == "__main__":
    sys.exit(main())
