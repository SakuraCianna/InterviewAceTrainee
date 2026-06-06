from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import hashlib
import json
import math
import re
from typing import Any, Protocol

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.core.config import get_settings
from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import (
    load_interview_presets,
    normalize_match_text,
    read_preset_markdown,
)


CAPABILITY_VECTOR_TABLE = "interview_capability_vectors"
DEFAULT_EMBEDDING_PROVIDER = "sentence-transformers"
DEFAULT_EMBEDDING_MODEL = "BAAI/bge-small-zh-v1.5"
LOCAL_HASH_EMBEDDING_MODEL = "local-hash-v1"
DEFAULT_LOCAL_HASH_DIMENSIONS = 384
TOKEN_PATTERN = re.compile(r"[a-z0-9+#.]+|[\u4e00-\u9fff]+", re.IGNORECASE)


class ExecuteConnection(Protocol):
    def execute(self, statement: Any, parameters: dict[str, Any] | None = None) -> Any:
        ...


class TextEmbeddingProvider(Protocol):
    @property
    def model_name(self) -> str:
        ...

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        ...


@dataclass(frozen=True)
class SentenceTransformerEmbeddingProvider:
    model_name_value: str = DEFAULT_EMBEDDING_MODEL
    device: str | None = None
    batch_size: int = 32
    query_instruction: str = "为这个句子生成表示以用于检索相关文章："

    @property
    def model_name(self) -> str:
        return self.model_name_value

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as exc:
            raise RuntimeError(
                "缺少 sentence-transformers，可执行 `uv sync --extra embeddings` 后再生成真实能力卡片向量"
            ) from exc

        model = _load_sentence_transformer(self.model_name_value, self.device)
        embeddings = model.encode(
            texts,
            batch_size=max(1, self.batch_size),
            convert_to_numpy=True,
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        return [
            [round(float(value), 8) for value in row]
            for row in embeddings.tolist()
        ]

    def embed_query(self, text_value: str) -> list[float]:
        query_text = f"{self.query_instruction}{text_value}" if self.query_instruction else text_value
        return self.embed_texts([query_text])[0]


@dataclass(frozen=True)
class LocalHashEmbeddingProvider:
    dimensions: int = DEFAULT_LOCAL_HASH_DIMENSIONS

    @property
    def model_name(self) -> str:
        return LOCAL_HASH_EMBEDDING_MODEL

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [local_text_embedding(text_value, self.dimensions) for text_value in texts]

    def embed_query(self, text_value: str) -> list[float]:
        return self.embed_texts([text_value])[0]


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
    source_file: str
    source_text: str
    priority: int = 50

    @property
    def search_text(self) -> str:
        parts = [
            self.title,
            " ".join(self.aliases),
            " ".join(self.rounds),
            " ".join(self.capability_tags),
            " ".join(self.question_angles),
            " ".join(self.scoring_focus),
            " ".join(self.safety_notes),
            self.source_text,
        ]
        return "\n".join(part for part in parts if part.strip())


@dataclass(frozen=True)
class CapabilityCardMatch:
    card: CapabilityCard
    score: float
    lexical_score: int = 0
    vector_score: float = 0.0

    @property
    def id(self) -> str:
        return self.card.id


def create_embedding_provider(
    provider_name: str | None = None,
    model_name: str | None = None,
    device: str | None = None,
    batch_size: int | None = None,
) -> TextEmbeddingProvider:
    settings = get_settings()
    provider = (provider_name or settings.capability_embedding_provider or DEFAULT_EMBEDDING_PROVIDER).strip().lower()
    configured_model = (model_name or settings.capability_embedding_model or DEFAULT_EMBEDDING_MODEL).strip()
    configured_device = device if device is not None else settings.capability_embedding_device.strip() or None
    configured_batch_size = batch_size or settings.capability_embedding_batch_size
    query_instruction = settings.capability_embedding_query_instruction

    if provider in {"sentence-transformers", "sentence_transformers", "huggingface", "hugging-face", "hf"}:
        return SentenceTransformerEmbeddingProvider(
            model_name_value=configured_model or DEFAULT_EMBEDDING_MODEL,
            device=configured_device,
            batch_size=configured_batch_size,
            query_instruction=query_instruction,
        )
    if provider in {"local-hash", "local_hash", LOCAL_HASH_EMBEDDING_MODEL, "hash"}:
        return LocalHashEmbeddingProvider()
    raise ValueError(f"不支持的能力卡片 embedding provider: {provider_name}")


def retrieve_capability_cards(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    round_name: str | None = None,
    limit: int = 4,
    db_connection: ExecuteConnection | None = None,
    embedding_provider: TextEmbeddingProvider | None = None,
) -> list[CapabilityCardMatch]:
    cards = [
        card
        for card in load_capability_cards()
        if interview_type in card.interview_types
    ]
    query = _query_text(interview_type, material_context, round_name)
    vector_scores: dict[str, float] = {}

    if db_connection is not None and capability_vector_table_ready(db_connection):
        provider = embedding_provider or create_embedding_provider()
        query_embedding = embed_query_text(provider, query)
        vector_scores = search_capability_vectors(
            db_connection,
            interview_type=interview_type,
            query_embedding=query_embedding,
            limit=max(limit * 3, limit),
            embedding_model=provider.model_name,
        )
    elif embedding_provider is not None and cards:
        query_embedding = embed_query_text(embedding_provider, query)
        card_embeddings = embedding_provider.embed_texts([card.search_text for card in cards])
        vector_scores = {
            card.id: cosine_similarity(query_embedding, embedding)
            for card, embedding in zip(cards, card_embeddings, strict=True)
        }

    matches: list[CapabilityCardMatch] = []
    for card in cards:
        lexical_score = _lexical_score(card, query, round_name)
        vector_score = vector_scores.get(card.id, 0.0)
        if lexical_score <= 0 and vector_score <= 0:
            continue
        score = lexical_score + round(max(vector_score, 0.0) * 160, 4)
        matches.append(
            CapabilityCardMatch(
                card=card,
                score=round(score, 4),
                lexical_score=lexical_score,
                vector_score=round(vector_score, 6),
            )
        )

    if not matches:
        matches = [
            CapabilityCardMatch(
                card=card,
                score=float(_round_score(card, round_name) or 1),
                lexical_score=_round_score(card, round_name) or 1,
                vector_score=0.0,
            )
            for card in cards
            if card.rounds
        ]
    return sorted(matches, key=lambda item: (-item.score, item.card.priority, item.card.id))[:limit]


def capability_round_hint(
    matches: list[CapabilityCardMatch],
    round_name: str,
    max_terms: int = 6,
) -> str:
    terms: list[str] = []
    seen: set[str] = set()
    normalized_round = normalize_match_text(round_name)
    for match in matches:
        for hint in _matching_round_hints(match.card, normalized_round):
            normalized_hint = normalize_match_text(hint)
            if normalized_hint and normalized_hint not in seen:
                seen.add(normalized_hint)
                terms.append(hint)
            if len(terms) >= max_terms:
                return "、".join(terms)
    return "、".join(terms)


def capability_questions_for_round(
    matches: list[CapabilityCardMatch],
    round_name: str,
    limit: int = 4,
) -> list[str]:
    questions: list[str] = []
    seen: set[str] = set()
    normalized_round = normalize_match_text(round_name)
    for match in matches:
        for current_round, items in match.card.questions_by_round.items():
            if normalize_match_text(current_round) and normalize_match_text(current_round) not in normalized_round:
                continue
            for question in items:
                normalized_question = normalize_match_text(question)
                if normalized_question and normalized_question not in seen:
                    seen.add(normalized_question)
                    questions.append(question)
                if len(questions) >= limit:
                    return questions
    return questions


def build_capability_prompt_context(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    round_name: str | None = None,
    max_chars: int = 2600,
    db_connection: ExecuteConnection | None = None,
) -> str:
    matches = retrieve_capability_cards(
        interview_type,
        material_context=material_context,
        round_name=round_name,
        limit=3,
        db_connection=db_connection,
    )
    sections: list[str] = []
    for match in matches:
        card = match.card
        lines = [
            f"## {card.title}",
            f"- 能力标签：{'、'.join(card.capability_tags[:8])}",
            f"- 追问角度：{'、'.join(card.question_angles[:8])}",
            f"- 评分重点：{'、'.join(card.scoring_focus[:8])}",
        ]
        if card.safety_notes:
            lines.append(f"- 安全边界：{'、'.join(card.safety_notes[:4])}")
        sections.append("\n".join(line for line in lines if not line.endswith("：")))
    return "\n\n".join(sections)[:max_chars]


def capability_card_inventory() -> dict[str, Any]:
    cards = load_capability_cards()
    counts = {
        interview_type.value: sum(1 for card in cards if interview_type in card.interview_types)
        for interview_type in InterviewType
    }
    return {
        "card_count": len(cards),
        "counts_by_interview_type": counts,
        "embedding_provider": get_settings().capability_embedding_provider,
        "embedding_model": embedding_model_name(),
        "embedding_dimensions": embedding_dimensions(),
        "query_instruction_enabled": bool(get_settings().capability_embedding_query_instruction.strip()),
    }


def seed_capability_vector_store(
    connection: ExecuteConnection,
    embedding_provider: TextEmbeddingProvider | None = None,
    batch_size: int | None = None,
) -> int:
    if not capability_vector_table_ready(connection):
        raise RuntimeError("interview_capability_vectors table or pgvector extension is not ready")

    provider = embedding_provider or create_embedding_provider()
    cards = list(load_capability_cards())
    effective_batch_size = max(1, batch_size or get_settings().capability_embedding_batch_size)
    count = 0
    for offset in range(0, len(cards), effective_batch_size):
        batch = cards[offset : offset + effective_batch_size]
        embeddings = provider.embed_texts([card.search_text for card in batch])
        for card, embedding in zip(batch, embeddings, strict=True):
            vector_id = _vector_row_id(card.id, provider.model_name)
            metadata = {
                "aliases": list(card.aliases),
                "rounds": list(card.rounds),
                "capability_tags": list(card.capability_tags),
                "question_angles": list(card.question_angles),
                "source_file": card.source_file,
            }
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
                        status,
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
                        'ready',
                        NOW()
                    )
                    ON CONFLICT (card_id, embedding_model) DO UPDATE SET
                        title = EXCLUDED.title,
                        interview_types = EXCLUDED.interview_types,
                        content_hash = EXCLUDED.content_hash,
                        embedding_dimensions = EXCLUDED.embedding_dimensions,
                        source_text = EXCLUDED.source_text,
                        metadata_json = EXCLUDED.metadata_json,
                        embedding = EXCLUDED.embedding,
                        status = 'ready',
                        updated_at = NOW()
                    """
                ),
                {
                    "id": vector_id,
                    "card_id": card.id,
                    "title": card.title,
                    "interview_types": json.dumps([item.value for item in card.interview_types], ensure_ascii=False),
                    "content_hash": _content_hash(card.search_text),
                    "embedding_model": provider.model_name,
                    "embedding_dimensions": len(embedding),
                    "source_text": card.search_text,
                    "metadata_json": json.dumps(metadata, ensure_ascii=False),
                    "embedding": vector_sql_literal(embedding),
                },
            )
            count += 1
    return count


def capability_vector_table_ready(connection: ExecuteConnection) -> bool:
    try:
        return bool(
            connection.execute(
                text(
                    """
                    SELECT
                        EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')
                        AND to_regclass('public.interview_capability_vectors') IS NOT NULL
                    """
                )
            ).scalar()
        )
    except SQLAlchemyError:
        return False


def search_capability_vectors(
    connection: ExecuteConnection,
    interview_type: InterviewType,
    query_embedding: list[float],
    limit: int = 8,
    embedding_model: str | None = None,
) -> dict[str, float]:
    model_name = embedding_model or embedding_model_name()
    try:
        rows = connection.execute(
            text(
                """
                SELECT
                    card_id,
                    1 - (embedding <=> CAST(:query_embedding AS vector)) AS vector_score
                FROM interview_capability_vectors
                WHERE interview_types @> CAST(:interview_filter AS jsonb)
                    AND embedding_model = :embedding_model
                    AND status = 'ready'
                ORDER BY embedding <=> CAST(:query_embedding AS vector)
                LIMIT :limit
                """
            ),
            {
                "query_embedding": vector_sql_literal(query_embedding),
                "interview_filter": json.dumps([interview_type.value], ensure_ascii=False),
                "embedding_model": model_name,
                "limit": max(1, limit),
            },
        ).mappings()
    except SQLAlchemyError:
        return {}

    return {
        str(row["card_id"]): float(row["vector_score"] or 0.0)
        for row in rows
        if row.get("card_id")
    }


def local_text_embedding(text_value: str, dimensions: int | None = None) -> list[float]:
    effective_dimensions = max(8, dimensions or DEFAULT_LOCAL_HASH_DIMENSIONS)
    vector = [0.0 for _ in range(effective_dimensions)]
    for token in tokenize_embedding_text(text_value):
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        index = int.from_bytes(digest[:4], "big") % effective_dimensions
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        vector[index] += sign
    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 0:
        return vector
    return [round(value / norm, 8) for value in vector]


def tokenize_embedding_text(text_value: str) -> list[str]:
    tokens: list[str] = []
    for match in TOKEN_PATTERN.finditer(normalize_match_text(text_value)):
        token = match.group(0).lower()
        if not token:
            continue
        tokens.append(token)
        if _contains_chinese(token) and len(token) >= 2:
            tokens.extend(token[index : index + 2] for index in range(len(token) - 1))
    return tokens


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or not left:
        return 0.0
    return round(sum(left[index] * right[index] for index in range(len(left))), 8)


def vector_sql_literal(vector: list[float]) -> str:
    return "[" + ",".join(str(round(float(value), 8)) for value in vector) + "]"


def embed_query_text(provider: TextEmbeddingProvider, text_value: str) -> list[float]:
    embed_query = getattr(provider, "embed_query", None)
    if callable(embed_query):
        return embed_query(text_value)
    return provider.embed_texts([text_value])[0]


@lru_cache(maxsize=1)
def load_capability_cards() -> tuple[CapabilityCard, ...]:
    cards: list[CapabilityCard] = []
    for preset in load_interview_presets():
        markdown = read_preset_markdown(preset)
        rounds = tuple(preset.default_for_rounds)
        question_angles = tuple(preset.question_angles)
        scoring_focus = tuple(preset.report_focus)
        capability_tags = _dedupe((*question_angles, *scoring_focus))
        round_hints = {
            round_name: question_angles
            for round_name in rounds
        }
        questions_by_round = {
            round_name: tuple(f"请围绕{angle}展开一个可验证的项目或经历。" for angle in question_angles[:4])
            for round_name in rounds
        }
        cards.append(
            CapabilityCard(
                id=preset.id,
                title=preset.title,
                interview_types=(preset.interview_type,),
                aliases=tuple(preset.aliases),
                rounds=rounds,
                capability_tags=capability_tags,
                question_angles=question_angles,
                round_hints=round_hints,
                questions_by_round=questions_by_round,
                scoring_focus=scoring_focus,
                safety_notes=_extract_safety_notes(markdown),
                source_file=preset.file,
                source_text=_compact_markdown(markdown),
                priority=preset.priority,
            )
        )
    return tuple(cards)


def embedding_model_name(provider: TextEmbeddingProvider | None = None) -> str:
    if provider is not None:
        return provider.model_name
    settings = get_settings()
    if settings.capability_embedding_provider.strip().lower() in {"local-hash", "local_hash", LOCAL_HASH_EMBEDDING_MODEL, "hash"}:
        return LOCAL_HASH_EMBEDDING_MODEL
    return settings.capability_embedding_model or DEFAULT_EMBEDDING_MODEL


def embedding_dimensions(provider: TextEmbeddingProvider | None = None) -> int:
    if isinstance(provider, LocalHashEmbeddingProvider):
        return provider.dimensions
    if provider is None and embedding_model_name() == LOCAL_HASH_EMBEDDING_MODEL:
        return DEFAULT_LOCAL_HASH_DIMENSIONS
    return 0


def _query_text(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None,
    round_name: str | None,
) -> str:
    parts = [interview_type.value, round_name or ""]
    if material_context is not None:
        parts.extend(
            [
                material_context.job_title or "",
                material_context.job_requirements or "",
                (material_context.resume_text or "")[:1600],
                material_context.target_school or "",
                material_context.major or "",
                material_context.research_direction or "",
                material_context.profile_summary or "",
                " ".join(material_context.keywords or []),
            ]
        )
    return "\n".join(part for part in parts if str(part).strip())


def _lexical_score(card: CapabilityCard, query: str, round_name: str | None) -> int:
    normalized_query = normalize_match_text(query)
    score = _round_score(card, round_name)
    title = normalize_match_text(card.title)
    if title and title in normalized_query:
        score += 80 + min(len(title), 24)
    for alias in card.aliases:
        normalized_alias = normalize_match_text(alias)
        if not normalized_alias:
            continue
        if normalized_alias in normalized_query:
            score += 100 + min(len(normalized_alias), 24)
    for term in (*card.capability_tags, *card.question_angles, *card.scoring_focus):
        for token in _meaningful_tokens(term):
            if token in normalized_query:
                score += 6
    return score


def _round_score(card: CapabilityCard, round_name: str | None) -> int:
    if not round_name:
        return 0
    normalized_round = normalize_match_text(round_name)
    if any(normalize_match_text(item) in normalized_round for item in card.rounds):
        return 24
    if _matching_round_hints(card, normalized_round):
        return 12
    return 0


def _matching_round_hints(card: CapabilityCard, normalized_round: str) -> tuple[str, ...]:
    hints: list[str] = []
    for round_name, round_hints in card.round_hints.items():
        normalized_card_round = normalize_match_text(round_name)
        if normalized_card_round and normalized_card_round not in normalized_round:
            continue
        hints.extend(round_hints)
    return tuple(hints)


def _meaningful_tokens(value: str) -> list[str]:
    normalized = normalize_match_text(value)
    return [token for token in normalized.split() if len(token) >= 2][:8]


def _contains_chinese(value: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in value)


def _dedupe(values: tuple[str, ...]) -> tuple[str, ...]:
    seen: set[str] = set()
    items: list[str] = []
    for value in values:
        text_value = str(value).strip()
        if text_value and text_value not in seen:
            seen.add(text_value)
            items.append(text_value)
    return tuple(items)


def _compact_markdown(markdown: str, max_chars: int = 1800) -> str:
    lines = [
        line.strip(" -#\t")
        for line in markdown.splitlines()
        if line.strip() and not line.strip().startswith("## 资料来源")
    ]
    return "\n".join(lines)[:max_chars]


def _extract_safety_notes(markdown: str) -> tuple[str, ...]:
    notes = []
    for line in markdown.splitlines():
        if "扣分" in line or "不" in line and ("追问" in line or "评价" in line or "避免" in line):
            notes.append(line.strip(" -"))
    return tuple(notes[:4])


def _content_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _vector_row_id(card_id: str, model_name: str) -> str:
    digest = hashlib.sha256(f"{model_name}:{card_id}".encode("utf-8")).hexdigest()
    return f"capability-{digest[:24]}"


@lru_cache(maxsize=4)
def _load_sentence_transformer(model_name: str, device: str | None) -> Any:
    from sentence_transformers import SentenceTransformer

    model_kwargs: dict[str, Any] = {}
    if device:
        model_kwargs["device"] = device
    return SentenceTransformer(model_name, **model_kwargs)
