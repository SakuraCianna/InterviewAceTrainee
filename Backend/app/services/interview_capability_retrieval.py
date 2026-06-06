from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import re
from typing import Any, Protocol

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import normalize_match_text


PRESET_ROOT = Path(__file__).resolve().parents[1] / "interview_presets"
CAPABILITY_CARDS_FILE = PRESET_ROOT / "capability_cards.json"
DEFAULT_EMBEDDING_MODEL = "local-hash-v1"
DEFAULT_EMBEDDING_DIMENSIONS = 384
TOKEN_PATTERN = re.compile(r"[a-z0-9+#.]+|[\u4e00-\u9fff]+", re.IGNORECASE)


class ExecuteConnection(Protocol):
    def execute(self, statement: Any, parameters: dict[str, Any] | None = None) -> Any:
        ...


@dataclass(frozen=True)
class CapabilityCard:
    id: str
    title: str
    interview_types: tuple[InterviewType, ...]
    aliases: tuple[str, ...]
    rounds: tuple[str, ...]
    capability_tags: tuple[str, ...]
    question_angles: tuple[str, ...]
    round_hints: dict[str, tuple[str, ...]]
    questions_by_round: dict[str, tuple[str, ...]]
    scoring_focus: tuple[str, ...]
    safety_notes: tuple[str, ...]

    @property
    def search_text(self) -> str:
        parts = [
            self.title,
            " ".join(self.aliases),
            " ".join(self.rounds),
            " ".join(self.capability_tags),
            " ".join(self.question_angles),
            " ".join(item for values in self.round_hints.values() for item in values),
            " ".join(item for values in self.questions_by_round.values() for item in values),
        ]
        return " ".join(part for part in parts if part)


@dataclass(frozen=True)
class CapabilityCardMatch:
    card: CapabilityCard
    score: int
    vector_score: float = 0.0

    @property
    def id(self) -> str:
        return self.card.id


def retrieve_capability_cards(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    round_name: str | None = None,
    limit: int = 4,
    db_connection: ExecuteConnection | None = None,
) -> list[CapabilityCardMatch]:
    query = _query_text(interview_type, material_context, round_name)
    query_embedding = local_text_embedding(query)
    database_vector_scores = (
        search_capability_vectors(db_connection, interview_type, query_embedding, limit=max(limit * 3, 8))
        if db_connection is not None
        else {}
    )

    matches: list[CapabilityCardMatch] = []
    for card in load_capability_cards():
        if interview_type not in card.interview_types:
            continue
        lexical_score = _lexical_score(card, query, round_name)
        if lexical_score <= 0:
            continue
        local_vector_score = cosine_similarity(query_embedding, local_text_embedding(card.search_text))
        vector_score = max(database_vector_scores.get(card.id, 0.0), local_vector_score)
        score = lexical_score + int(max(vector_score, 0.0) * 90)
        if score > 0:
            matches.append(CapabilityCardMatch(card=card, score=score, vector_score=round(vector_score, 4)))

    return sorted(matches, key=lambda item: (-item.score, item.card.id))[:limit]


def capability_round_hint(matches: list[CapabilityCardMatch], round_name: str, max_terms: int = 6) -> str:
    terms: list[str] = []
    seen: set[str] = set()
    normalized_round = normalize_match_text(round_name)
    for match in matches:
        hints = _matching_round_hints(match.card, normalized_round)
        if not hints:
            hints = match.card.question_angles[:2]
        for hint in hints:
            key = normalize_match_text(hint)
            if not key or key in seen:
                continue
            seen.add(key)
            terms.append(hint)
            if len(terms) >= max_terms:
                return "、".join(terms)
    return "、".join(terms)


def capability_questions_for_round(matches: list[CapabilityCardMatch], round_name: str, limit: int = 4) -> list[str]:
    questions: list[str] = []
    seen: set[str] = set()
    normalized_round = normalize_match_text(round_name)
    for match in matches:
        for card_round, card_questions in match.card.questions_by_round.items():
            if normalize_match_text(card_round) != normalized_round:
                continue
            for question in card_questions:
                key = normalize_match_text(question)
                if not key or key in seen:
                    continue
                seen.add(key)
                questions.append(question)
                if len(questions) >= limit:
                    return questions
    return questions


def build_capability_prompt_context(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    round_name: str | None = None,
    max_chars: int = 2600,
) -> str:
    matches = retrieve_capability_cards(interview_type, material_context, round_name=round_name, limit=3)
    if not matches:
        return ""
    sections: list[str] = []
    for match in matches:
        card = match.card
        sections.append(
            "\n".join(
                [
                    f"## 能力卡片：{card.title}",
                    f"- 能力标签：{'、'.join(card.capability_tags[:6])}",
                    f"- 提问角度：{'、'.join(card.question_angles[:5])}",
                    f"- 评分重点：{'、'.join(card.scoring_focus[:4])}",
                    f"- 安全边界：{'、'.join(card.safety_notes[:2])}",
                ]
            )
        )
    return "\n\n".join(sections)[:max_chars]


def capability_card_inventory() -> dict[str, Any]:
    cards = load_capability_cards()
    by_type = {interview_type.value: 0 for interview_type in InterviewType}
    total_questions = 0
    for card in cards:
        total_questions += sum(len(questions) for questions in card.questions_by_round.values())
        for interview_type in card.interview_types:
            by_type[interview_type.value] += 1
    return {
        "card_count": len(cards),
        "question_count": total_questions,
        "embedding_model": embedding_model_name(),
        "embedding_dimensions": embedding_dimensions(),
        "by_interview_type": by_type,
    }


def seed_capability_vector_store(connection: ExecuteConnection) -> int:
    if not capability_vector_table_ready(connection):
        raise RuntimeError("interview_capability_vectors table or pgvector extension is not ready")
    count = 0
    for card in load_capability_cards():
        embedding = local_text_embedding(card.search_text)
        connection.execute(
            text(
                """
                INSERT INTO interview_capability_vectors (
                    id,
                    card_id,
                    title,
                    interview_types,
                    content_hash,
                    embedding_model,
                    embedding_dimensions,
                    source_text,
                    metadata_json,
                    embedding,
                    updated_at
                )
                VALUES (
                    :id,
                    :card_id,
                    :title,
                    CAST(:interview_types AS jsonb),
                    :content_hash,
                    :embedding_model,
                    :embedding_dimensions,
                    :source_text,
                    CAST(:metadata_json AS jsonb),
                    CAST(:embedding AS vector),
                    NOW()
                )
                ON CONFLICT (card_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    interview_types = EXCLUDED.interview_types,
                    content_hash = EXCLUDED.content_hash,
                    embedding_model = EXCLUDED.embedding_model,
                    embedding_dimensions = EXCLUDED.embedding_dimensions,
                    source_text = EXCLUDED.source_text,
                    metadata_json = EXCLUDED.metadata_json,
                    embedding = EXCLUDED.embedding,
                    updated_at = NOW()
                """
            ),
            {
                "id": f"capability-{card.id}",
                "card_id": card.id,
                "title": card.title,
                "interview_types": json.dumps([interview_type.value for interview_type in card.interview_types], ensure_ascii=False),
                "content_hash": hashlib.sha256(card.search_text.encode("utf-8")).hexdigest(),
                "embedding_model": embedding_model_name(),
                "embedding_dimensions": embedding_dimensions(),
                "source_text": card.search_text,
                "metadata_json": json.dumps(
                    {
                        "aliases": list(card.aliases),
                        "rounds": list(card.rounds),
                        "capability_tags": list(card.capability_tags),
                        "question_angles": list(card.question_angles),
                    },
                    ensure_ascii=False,
                ),
                "embedding": vector_sql_literal(embedding),
            },
        )
        count += 1
    return count


def capability_vector_table_ready(connection: ExecuteConnection) -> bool:
    try:
        result = connection.execute(
            text(
                """
                SELECT
                    to_regclass('public.interview_capability_vectors') IS NOT NULL
                    AND EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')
                """
            )
        ).scalar()
    except SQLAlchemyError:
        return False
    return bool(result)


def search_capability_vectors(
    connection: ExecuteConnection,
    interview_type: InterviewType,
    query_embedding: list[float],
    limit: int = 8,
) -> dict[str, float]:
    if not capability_vector_table_ready(connection):
        return {}
    try:
        rows = connection.execute(
            text(
                """
                SELECT
                    card_id,
                    1 - (embedding <=> CAST(:query_embedding AS vector)) AS vector_score
                FROM interview_capability_vectors
                WHERE interview_types @> CAST(:interview_filter AS jsonb)
                ORDER BY embedding <=> CAST(:query_embedding AS vector)
                LIMIT :limit
                """
            ),
            {
                "query_embedding": vector_sql_literal(query_embedding),
                "interview_filter": json.dumps([interview_type.value]),
                "limit": limit,
            },
        ).mappings()
    except SQLAlchemyError:
        return {}
    return {str(row["card_id"]): float(row["vector_score"] or 0.0) for row in rows}


def local_text_embedding(text_value: str, dimensions: int | None = None) -> list[float]:
    size = dimensions or embedding_dimensions()
    values = [0.0 for _ in range(size)]
    for token in tokenize_embedding_text(text_value):
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        bucket = int.from_bytes(digest[:4], "big") % size
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        values[bucket] += sign
    norm = math.sqrt(sum(value * value for value in values))
    if norm <= 0:
        return values
    return [round(value / norm, 6) for value in values]


def tokenize_embedding_text(text_value: str) -> list[str]:
    normalized = normalize_match_text(text_value)
    tokens: list[str] = []
    for match in TOKEN_PATTERN.finditer(normalized):
        token = match.group(0).lower()
        if not token:
            continue
        tokens.append(token)
        if _contains_chinese(token) and len(token) > 2:
            tokens.extend(token[index : index + 2] for index in range(len(token) - 1))
            if len(token) > 3:
                tokens.extend(token[index : index + 3] for index in range(len(token) - 2))
    return tokens


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    return sum(left[index] * right[index] for index in range(len(left)))


def vector_sql_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{value:.6f}" for value in vector) + "]"


@lru_cache(maxsize=1)
def load_capability_cards() -> tuple[CapabilityCard, ...]:
    if not CAPABILITY_CARDS_FILE.exists():
        return ()
    payload = json.loads(CAPABILITY_CARDS_FILE.read_text(encoding="utf-8"))
    cards: list[CapabilityCard] = []
    for item in payload.get("cards", []):
        cards.append(
            CapabilityCard(
                id=str(item["id"]),
                title=str(item["title"]),
                interview_types=tuple(InterviewType(str(value)) for value in item.get("interview_types", [])),
                aliases=tuple(str(value) for value in item.get("aliases", [])),
                rounds=tuple(str(value) for value in item.get("rounds", [])),
                capability_tags=tuple(str(value) for value in item.get("capability_tags", [])),
                question_angles=tuple(str(value) for value in item.get("question_angles", [])),
                round_hints={
                    str(round_name): tuple(str(hint) for hint in hints)
                    for round_name, hints in item.get("round_hints", {}).items()
                },
                questions_by_round={
                    str(round_name): tuple(str(question) for question in questions)
                    for round_name, questions in item.get("questions_by_round", {}).items()
                },
                scoring_focus=tuple(str(value) for value in item.get("scoring_focus", [])),
                safety_notes=tuple(str(value) for value in item.get("safety_notes", [])),
            )
        )
    return tuple(cards)


@lru_cache(maxsize=1)
def embedding_model_name() -> str:
    if not CAPABILITY_CARDS_FILE.exists():
        return DEFAULT_EMBEDDING_MODEL
    payload = json.loads(CAPABILITY_CARDS_FILE.read_text(encoding="utf-8"))
    return str(payload.get("embedding_model") or DEFAULT_EMBEDDING_MODEL)


@lru_cache(maxsize=1)
def embedding_dimensions() -> int:
    if not CAPABILITY_CARDS_FILE.exists():
        return DEFAULT_EMBEDDING_DIMENSIONS
    payload = json.loads(CAPABILITY_CARDS_FILE.read_text(encoding="utf-8"))
    return int(payload.get("embedding_dimensions") or DEFAULT_EMBEDDING_DIMENSIONS)


def _query_text(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None,
    round_name: str | None,
) -> str:
    if material_context is None:
        return normalize_match_text(f"{interview_type.value} {round_name or ''}")
    parts = [
        interview_type.value,
        material_context.job_title or "",
        material_context.job_requirements or "",
        material_context.resume_text or "",
        material_context.target_school or "",
        material_context.major or "",
        material_context.research_direction or "",
        material_context.profile_summary or "",
        " ".join(material_context.keywords or []),
        round_name or "",
    ]
    return normalize_match_text(" ".join(parts))


def _lexical_score(card: CapabilityCard, query: str, round_name: str | None) -> int:
    score = 0
    normalized_round = normalize_match_text(round_name or "")
    if normalized_round:
        for card_round in card.rounds:
            if normalize_match_text(card_round) == normalized_round:
                score += 35
                break
    for alias in card.aliases:
        normalized_alias = normalize_match_text(alias)
        if normalized_alias and normalized_alias in query:
            score += 80 + min(len(normalized_alias), 24)
    for tag in (*card.capability_tags, *card.question_angles):
        normalized_tag = normalize_match_text(tag)
        if normalized_tag and normalized_tag in query:
            score += 18
    return score


def _matching_round_hints(card: CapabilityCard, normalized_round: str) -> tuple[str, ...]:
    for round_name, hints in card.round_hints.items():
        if normalize_match_text(round_name) == normalized_round:
            return hints
    return ()


def _contains_chinese(value: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in value)
