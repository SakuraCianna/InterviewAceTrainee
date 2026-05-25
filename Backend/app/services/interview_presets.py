from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import re
from functools import lru_cache

from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext


PRESET_ROOT = Path(__file__).resolve().parents[1] / "interview_presets"
PRESET_INDEX_GLOB = "preset_index*.json"


@dataclass(frozen=True)
class InterviewPreset:
    id: str
    interview_type: InterviewType
    title: str
    file: str
    aliases: tuple[str, ...]
    question_angles: tuple[str, ...]
    report_focus: tuple[str, ...]
    default_for_rounds: tuple[str, ...]
    priority: int = 50


@dataclass(frozen=True)
class PresetMatch:
    preset: InterviewPreset
    score: int

    @property
    def id(self) -> str:
        return self.preset.id

    @property
    def title(self) -> str:
        return self.preset.title


def match_interview_presets(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    round_name: str | None = None,
    limit: int = 3,
) -> list[PresetMatch]:
    candidates = [preset for preset in load_interview_presets() if preset.interview_type == interview_type]
    query = _query_text(interview_type, material_context, round_name)
    matches: list[PresetMatch] = []
    for preset in candidates:
        score = _preset_score(preset, query, round_name)
        if score > 0:
            matches.append(PresetMatch(preset=preset, score=score))
    if not matches:
        matches = [PresetMatch(preset=preset, score=_round_score(preset, round_name) or 1) for preset in candidates if preset.default_for_rounds]
    return sorted(matches, key=lambda item: (-item.score, item.preset.priority, item.preset.id))[:limit]


def build_preset_prompt_context(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    round_name: str | None = None,
    max_chars: int = 5200,
) -> str:
    matches = match_interview_presets(interview_type, material_context, round_name)
    if not matches:
        return ""
    sections: list[str] = []
    for match in matches:
        content = read_preset_markdown(match.preset)
        if not content:
            continue
        sections.append(f"## {match.title}\n{content.strip()}")
    text = "\n\n---\n\n".join(sections)
    return text[:max_chars]


def best_preset_hint(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    round_name: str | None = None,
) -> tuple[str | None, list[str], list[str]]:
    matches = match_interview_presets(interview_type, material_context, round_name, limit=1)
    if not matches:
        return None, [], []
    preset = matches[0].preset
    return preset.title, list(preset.question_angles), list(preset.report_focus)


@lru_cache(maxsize=1)
def load_interview_presets() -> tuple[InterviewPreset, ...]:
    index_files = sorted(PRESET_ROOT.glob(PRESET_INDEX_GLOB))
    if not index_files:
        return ()
    presets: list[InterviewPreset] = []
    seen_ids: set[str] = set()
    for index_file in index_files:
        payload = json.loads(index_file.read_text(encoding="utf-8"))
        for item in payload.get("presets", []):
            preset_id = str(item["id"])
            if preset_id in seen_ids:
                continue
            seen_ids.add(preset_id)
            presets.append(
                InterviewPreset(
                    id=preset_id,
                    interview_type=InterviewType(str(item["interview_type"])),
                    title=str(item["title"]),
                    file=str(item["file"]),
                    aliases=tuple(str(alias) for alias in item.get("aliases", [])),
                    question_angles=tuple(str(angle) for angle in item.get("question_angles", [])),
                    report_focus=tuple(str(focus) for focus in item.get("report_focus", [])),
                    default_for_rounds=tuple(str(round_name) for round_name in item.get("default_for_rounds", [])),
                    priority=int(item.get("priority", 50)),
                )
            )
    return tuple(presets)


@lru_cache(maxsize=128)
def read_preset_markdown(preset: InterviewPreset) -> str:
    path = (PRESET_ROOT / preset.file).resolve()
    if PRESET_ROOT.resolve() not in path.parents:
        return ""
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")


def _query_text(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None,
    round_name: str | None,
) -> str:
    if material_context is None:
        return normalize_match_text(f"{interview_type.value} {round_name or ''}")
    parts = [
        str(material_context.job_title or ""),
        str(material_context.job_requirements or ""),
        str(material_context.target_school or ""),
        str(material_context.major or ""),
        str(material_context.research_direction or ""),
        str(material_context.profile_summary or ""),
        " ".join(material_context.keywords or []),
        str(round_name or ""),
    ]
    return normalize_match_text(" ".join(parts))


def _preset_score(preset: InterviewPreset, query: str, round_name: str | None) -> int:
    score = _round_score(preset, round_name)
    for alias in preset.aliases:
        normalized_alias = normalize_match_text(alias)
        if not normalized_alias:
            continue
        if normalized_alias in query:
            score += 100 + min(len(normalized_alias), 24)
            continue
        alias_tokens = [token for token in normalized_alias.split() if len(token) >= 2]
        if alias_tokens and all(token in query for token in alias_tokens):
            score += 42 + len(alias_tokens) * 3
    for angle in preset.question_angles:
        for token in _meaningful_tokens(angle):
            if token in query:
                score += 4
    return score


def _round_score(preset: InterviewPreset, round_name: str | None) -> int:
    if not round_name:
        return 0
    normalized_round = normalize_match_text(round_name)
    if any(normalize_match_text(item) in normalized_round for item in preset.default_for_rounds):
        return 20
    return 0


def normalize_match_text(value: str) -> str:
    lowered = value.lower()
    lowered = re.sub(r"[\u3000\s/_.,;:|()（）【】\[\]{}<>《》+＋-]+", " ", lowered)
    return lowered.strip()


def _meaningful_tokens(value: str) -> list[str]:
    normalized = normalize_match_text(value)
    return [token for token in normalized.split() if len(token) >= 2][:8]
