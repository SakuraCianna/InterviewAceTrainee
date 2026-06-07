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

from app.core.config import get_settings
from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import normalize_match_text


PRESET_ROOT = Path(__file__).resolve().parents[1] / "interview_presets"
CAPABILITY_CARDS_FILE = PRESET_ROOT / "capability_cards.json"
CAPABILITY_VECTOR_EXPORT_FILE = PRESET_ROOT / "capability_vectors.json"
CAPABILITY_VECTOR_TABLE = "interview_capability_vectors"
CAPABILITY_VECTOR_EXPORT_SCHEMA_VERSION = 1
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
            " ".join(self.scoring_focus),
            " ".join(self.safety_notes),
        ]
        return " ".join(part for part in parts if part.strip())


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

    if provider in {"sentence-transformers", "sentence_transformers", "huggingface", "hugging-face", "hf"}:
        return SentenceTransformerEmbeddingProvider(
            model_name_value=configured_model or DEFAULT_EMBEDDING_MODEL,
            device=configured_device,
            batch_size=configured_batch_size,
            query_instruction=settings.capability_embedding_query_instruction,
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
    cards = [card for card in load_capability_cards() if interview_type in card.interview_types]
    query = _query_text(interview_type, material_context, round_name)
    lexical_scores = {card.id: _lexical_score(card, query, round_name) for card in cards}
    vector_scores: dict[str, float] = {}

    if db_connection is not None and capability_vector_table_ready(db_connection):
        embedding_model_for_search = embedding_model_name()
        try:
            provider = embedding_provider or create_embedding_provider()
            embedding_model_for_search = provider.model_name
            query_embedding = embed_query_text(provider, query)
            vector_scores = search_capability_vectors(
                db_connection,
                interview_type,
                query_embedding,
                limit=max(limit * 3, 8),
                embedding_model=embedding_model_for_search,
            )
        except (RuntimeError, ValueError):
            vector_scores = search_capability_vectors_from_anchor_cards(
                db_connection,
                interview_type,
                lexical_scores,
                limit=max(limit * 3, 8),
                embedding_model=embedding_model_for_search,
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
        lexical_score = lexical_scores.get(card.id, 0)
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
    db_connection: ExecuteConnection | None = None,
) -> str:
    matches = retrieve_capability_cards(
        interview_type,
        material_context,
        round_name=round_name,
        limit=3,
        db_connection=db_connection,
    )
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
        "embedding_provider": get_settings().capability_embedding_provider,
        "embedding_model": embedding_model_name(),
        "embedding_dimensions": embedding_dimensions(),
        "local_hash_dimensions": DEFAULT_LOCAL_HASH_DIMENSIONS,
        "query_instruction_enabled": bool(get_settings().capability_embedding_query_instruction.strip()),
        "by_interview_type": by_type,
        "counts_by_interview_type": by_type,
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
        if len(embeddings) != len(batch):
            raise RuntimeError("embedding provider returned an unexpected vector count")
        for card, embedding in zip(batch, embeddings, strict=True):
            insert_capability_vector_row(
                connection,
                capability_vector_row_parameters(
                    card=card,
                    embedding_model=provider.model_name,
                    embedding=embedding,
                ),
            )
            count += 1
    return count


def build_capability_vector_export(
    embedding_provider: TextEmbeddingProvider,
    batch_size: int | None = None,
) -> dict[str, Any]:
    cards = list(load_capability_cards())
    effective_batch_size = max(1, batch_size or get_settings().capability_embedding_batch_size)
    vectors: list[dict[str, Any]] = []
    for offset in range(0, len(cards), effective_batch_size):
        batch = cards[offset : offset + effective_batch_size]
        embeddings = embedding_provider.embed_texts([card.search_text for card in batch])
        if len(embeddings) != len(batch):
            raise RuntimeError("embedding provider returned an unexpected vector count")
        for card, embedding in zip(batch, embeddings, strict=True):
            normalized_embedding = normalize_embedding_vector(embedding)
            vectors.append(
                {
                    "card_id": card.id,
                    "title": card.title,
                    "interview_types": [interview_type.value for interview_type in card.interview_types],
                    "content_hash": capability_card_content_hash(card),
                    "embedding_model": embedding_provider.model_name,
                    "embedding_dimensions": len(normalized_embedding),
                    "source_text": card.search_text,
                    "metadata": capability_card_metadata(card),
                    "embedding": normalized_embedding,
                }
            )
    dimensions = vectors[0]["embedding_dimensions"] if vectors else 0
    return {
        "schema_version": CAPABILITY_VECTOR_EXPORT_SCHEMA_VERSION,
        "embedding_model": embedding_provider.model_name,
        "embedding_dimensions": dimensions,
        "card_count": len(cards),
        "source_cards_file": CAPABILITY_CARDS_FILE.name,
        "vectors": vectors,
    }


def write_capability_vector_export(
    output_path: str | Path,
    embedding_provider: TextEmbeddingProvider,
    batch_size: int | None = None,
) -> dict[str, Any]:
    payload = build_capability_vector_export(embedding_provider, batch_size=batch_size)
    target_path = Path(output_path)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    target_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return payload


def import_capability_vector_store_from_export(
    connection: ExecuteConnection,
    export_path: str | Path | None = None,
    *,
    required: bool = False,
) -> int:
    if not capability_vector_table_ready(connection):
        raise RuntimeError("interview_capability_vectors table or pgvector extension is not ready")

    source_path = Path(export_path) if export_path is not None else CAPABILITY_VECTOR_EXPORT_FILE
    if not source_path.exists():
        if required:
            raise RuntimeError(f"offline capability vector export not found: {source_path}")
        return 0

    payload = json.loads(source_path.read_text(encoding="utf-8"))
    vector_records = validate_capability_vector_export(payload, source_path)
    for card, embedding_model, embedding in vector_records:
        insert_capability_vector_row(
            connection,
            capability_vector_row_parameters(
                card=card,
                embedding_model=embedding_model,
                embedding=embedding,
            ),
        )
    return len(vector_records)


def validate_capability_vector_export(
    payload: dict[str, Any],
    source_path: Path | None = None,
) -> list[tuple[CapabilityCard, str, list[float]]]:
    source_label = str(source_path or CAPABILITY_VECTOR_EXPORT_FILE)
    if not isinstance(payload, dict):
        raise RuntimeError(f"offline capability vector export is invalid: {source_label}")
    if payload.get("schema_version") != CAPABILITY_VECTOR_EXPORT_SCHEMA_VERSION:
        raise RuntimeError(f"offline capability vector export schema is unsupported: {source_label}")

    vectors = payload.get("vectors")
    if not isinstance(vectors, list):
        raise RuntimeError(f"offline capability vector export is missing vectors: {source_label}")

    cards_by_id = {card.id: card for card in load_capability_cards()}
    expected_ids = set(cards_by_id)
    exported_ids: set[str] = set()
    records: list[tuple[CapabilityCard, str, list[float]]] = []
    payload_model = str(payload.get("embedding_model") or "").strip()
    try:
        payload_dimensions = int(payload.get("embedding_dimensions") or 0)
    except (TypeError, ValueError) as exc:
        raise RuntimeError(f"offline capability vector export has invalid embedding dimensions: {source_label}") from exc

    for item in vectors:
        if not isinstance(item, dict):
            raise RuntimeError(f"offline capability vector export contains an invalid vector row: {source_label}")
        card_id = str(item.get("card_id") or "").strip()
        if not card_id or card_id not in cards_by_id:
            raise RuntimeError(f"offline capability vector export contains unknown capability card: {card_id}")
        if card_id in exported_ids:
            raise RuntimeError(f"offline capability vector export contains duplicate capability card: {card_id}")
        exported_ids.add(card_id)

        card = cards_by_id[card_id]
        if str(item.get("content_hash") or "") != capability_card_content_hash(card):
            raise RuntimeError(f"offline capability vector export is stale for capability card: {card_id}")

        embedding_model = str(item.get("embedding_model") or payload_model).strip()
        if not embedding_model:
            raise RuntimeError(f"offline capability vector export is missing embedding model for card: {card_id}")

        embedding = normalize_embedding_vector(item.get("embedding"))
        try:
            row_dimensions = int(item.get("embedding_dimensions") or len(embedding))
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"offline capability vector export has invalid dimensions for card: {card_id}") from exc
        if row_dimensions != len(embedding):
            raise RuntimeError(f"offline capability vector export has mismatched dimensions for card: {card_id}")
        if payload_dimensions and payload_dimensions != len(embedding):
            raise RuntimeError(f"offline capability vector export has mixed dimensions for card: {card_id}")
        records.append((card, embedding_model, embedding))

    if exported_ids != expected_ids:
        missing = sorted(expected_ids - exported_ids)
        extra = sorted(exported_ids - expected_ids)
        details = []
        if missing:
            details.append(f"missing={','.join(missing[:5])}")
        if extra:
            details.append(f"extra={','.join(extra[:5])}")
        raise RuntimeError(f"offline capability vector export does not match capability cards: {';'.join(details)}")

    return records


def capability_card_content_hash(card: CapabilityCard) -> str:
    return hashlib.sha256(card.search_text.encode("utf-8")).hexdigest()


def capability_card_metadata(card: CapabilityCard) -> dict[str, list[str]]:
    return {
        "aliases": list(card.aliases),
        "rounds": list(card.rounds),
        "capability_tags": list(card.capability_tags),
        "question_angles": list(card.question_angles),
    }


def normalize_embedding_vector(value: Any) -> list[float]:
    if not isinstance(value, list):
        raise RuntimeError("embedding vector must be a list")
    normalized: list[float] = []
    for item in value:
        try:
            number = float(item)
        except (TypeError, ValueError) as exc:
            raise RuntimeError("embedding vector contains non-numeric values") from exc
        if not math.isfinite(number):
            raise RuntimeError("embedding vector contains non-finite values")
        normalized.append(round(number, 8))
    if not normalized:
        raise RuntimeError("embedding vector must not be empty")
    return normalized


def capability_vector_row_parameters(
    *,
    card: CapabilityCard,
    embedding_model: str,
    embedding: list[float],
) -> dict[str, Any]:
    normalized_embedding = normalize_embedding_vector(embedding)
    return {
        "id": _vector_row_id(card.id, embedding_model),
        "card_id": card.id,
        "title": card.title,
        "interview_types": json.dumps([interview_type.value for interview_type in card.interview_types], ensure_ascii=False),
        "content_hash": capability_card_content_hash(card),
        "embedding_model": embedding_model,
        "embedding_dimensions": len(normalized_embedding),
        "source_text": card.search_text,
        "metadata_json": json.dumps(capability_card_metadata(card), ensure_ascii=False),
        "embedding": vector_sql_literal(normalized_embedding),
    }


def insert_capability_vector_row(connection: ExecuteConnection, parameters: dict[str, Any]) -> None:
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
        parameters,
    )


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
    embedding_model: str | None = None,
) -> dict[str, float]:
    if not capability_vector_table_ready(connection):
        return {}
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
                "interview_filter": json.dumps([interview_type.value]),
                "embedding_model": model_name,
                "limit": max(1, limit),
            },
        ).mappings()
    except SQLAlchemyError:
        return {}
    return {str(row["card_id"]): float(row["vector_score"] or 0.0) for row in rows if row.get("card_id")}


def search_capability_vectors_from_anchor_cards(
    connection: ExecuteConnection,
    interview_type: InterviewType,
    lexical_scores: dict[str, int],
    limit: int = 8,
    embedding_model: str | None = None,
    max_anchor_cards: int = 3,
) -> dict[str, float]:
    anchor_card_ids = [
        card_id
        for card_id, score in sorted(lexical_scores.items(), key=lambda item: (-item[1], item[0]))
        if score > 0
    ][: max(1, max_anchor_cards)]
    if not anchor_card_ids:
        return {}

    model_name = embedding_model or embedding_model_name()
    stored_embeddings = load_capability_vector_embeddings(
        connection,
        interview_type=interview_type,
        card_ids=anchor_card_ids,
        embedding_model=model_name,
    )
    query_embedding = average_embedding_vectors(
        [stored_embeddings[card_id] for card_id in anchor_card_ids if card_id in stored_embeddings]
    )
    if not query_embedding:
        return {}
    return search_capability_vectors(
        connection,
        interview_type=interview_type,
        query_embedding=query_embedding,
        limit=limit,
        embedding_model=model_name,
    )


def load_capability_vector_embeddings(
    connection: ExecuteConnection,
    interview_type: InterviewType,
    card_ids: list[str],
    embedding_model: str,
) -> dict[str, list[float]]:
    if not card_ids or not capability_vector_table_ready(connection):
        return {}
    try:
        rows = connection.execute(
            text(
                """
                SELECT
                    card_id,
                    embedding::text AS embedding
                FROM interview_capability_vectors
                WHERE interview_types @> CAST(:interview_filter AS jsonb)
                    AND embedding_model = :embedding_model
                    AND status = 'ready'
                    AND card_id IN (
                        SELECT jsonb_array_elements_text(CAST(:card_ids AS jsonb))
                    )
                """
            ),
            {
                "interview_filter": json.dumps([interview_type.value]),
                "embedding_model": embedding_model,
                "card_ids": json.dumps(card_ids, ensure_ascii=False),
            },
        ).mappings()
    except (RuntimeError, ValueError, SQLAlchemyError):
        return {}

    embeddings: dict[str, list[float]] = {}
    for row in rows:
        card_id = str(row.get("card_id") or "")
        if not card_id:
            continue
        try:
            embeddings[card_id] = parse_vector_sql_literal(str(row.get("embedding") or ""))
        except RuntimeError:
            continue
    return embeddings


def average_embedding_vectors(vectors: list[list[float]]) -> list[float]:
    if not vectors:
        return []
    dimensions = len(vectors[0])
    if dimensions <= 0:
        return []
    sums = [0.0 for _ in range(dimensions)]
    used_count = 0
    for vector in vectors:
        if len(vector) != dimensions:
            continue
        for index, value in enumerate(vector):
            sums[index] += float(value)
        used_count += 1
    if used_count <= 0:
        return []
    averaged = [value / used_count for value in sums]
    norm = math.sqrt(sum(value * value for value in averaged))
    if norm <= 0:
        return []
    return [round(value / norm, 8) for value in averaged]


def local_text_embedding(text_value: str, dimensions: int | None = None) -> list[float]:
    size = max(8, dimensions or DEFAULT_LOCAL_HASH_DIMENSIONS)
    values = [0.0 for _ in range(size)]
    for token in tokenize_embedding_text(text_value):
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        bucket = int.from_bytes(digest[:4], "big") % size
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        values[bucket] += sign
    norm = math.sqrt(sum(value * value for value in values))
    if norm <= 0:
        return values
    return [round(value / norm, 8) for value in values]


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
    return round(sum(left[index] * right[index] for index in range(len(left))), 8)


def vector_sql_literal(vector: list[float]) -> str:
    return "[" + ",".join(str(round(float(value), 8)) for value in vector) + "]"


def parse_vector_sql_literal(value: str) -> list[float]:
    normalized = value.strip()
    if not normalized.startswith("[") or not normalized.endswith("]"):
        raise RuntimeError("invalid vector literal")
    body = normalized[1:-1].strip()
    if not body:
        raise RuntimeError("invalid empty vector literal")
    return normalize_embedding_vector([float(item.strip()) for item in body.split(",") if item.strip()])


def embed_query_text(provider: TextEmbeddingProvider, text_value: str) -> list[float]:
    embed_query = getattr(provider, "embed_query", None)
    if callable(embed_query):
        return embed_query(text_value)
    return provider.embed_texts([text_value])[0]


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
    if material_context is None:
        return f"{interview_type.value} {round_name or ''}"
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
    return " ".join(part for part in parts if str(part).strip())


def _lexical_score(card: CapabilityCard, query: str, round_name: str | None) -> int:
    score = 0
    normalized_query = normalize_match_text(query)
    normalized_round = normalize_match_text(round_name or "")
    if normalized_round:
        for card_round in card.rounds:
            if normalize_match_text(card_round) == normalized_round:
                score += 35
                break
    for alias in card.aliases:
        normalized_alias = normalize_match_text(alias)
        if normalized_alias and normalized_alias in normalized_query:
            score += 80 + min(len(normalized_alias), 24)
    for tag in (*card.capability_tags, *card.question_angles, *card.scoring_focus):
        normalized_tag = normalize_match_text(tag)
        if normalized_tag and normalized_tag in normalized_query:
            score += 18
    return score


def _matching_round_hints(card: CapabilityCard, normalized_round: str) -> tuple[str, ...]:
    for round_name, hints in card.round_hints.items():
        if normalize_match_text(round_name) == normalized_round:
            return hints
    return ()


def _contains_chinese(value: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in value)


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
