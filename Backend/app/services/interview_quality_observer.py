from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
import json
from typing import Any

from sqlalchemy import MetaData, Table, func, inspect, select
from sqlalchemy.engine import Connection
from sqlalchemy.exc import SQLAlchemyError

from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import (
    PRESET_INDEX_FILE,
    load_interview_presets,
    match_interview_presets,
    read_preset_markdown,
)
from app.services.interview_quality_gate import EXPECTED_ROUNDS, SCENARIO_LABELS


WRONG_QUESTION_RISK_LIMIT = 0.01
FLOW_ERROR_RISK_LIMIT = 0.02
CAPABILITY_VECTOR_TABLE = "interview_capability_vectors"

CAPABILITY_SEED_MINIMUMS: dict[str, int] = {
    InterviewType.JOB.value: 200,
    InterviewType.POSTGRADUATE.value: 100,
    InterviewType.CIVIL_SERVICE.value: 5,
    InterviewType.IELTS.value: 4,
}

VECTOR_SEED_ID_COLUMNS = ("preset_id", "capability_id", "card_id", "seed_id", "source_id")
VECTOR_VALUE_COLUMNS = ("embedding_vector", "embedding", "vector", "vector_json")
VECTOR_MODEL_COLUMNS = ("embedding_model", "embedding_model_name", "model_name")
VECTOR_STATUS_COLUMNS = ("status", "ready_status", "state")
READY_VECTOR_STATUSES = {"ready", "active", "enabled", "completed", "ok"}

SCENARIO_REQUIRED_TERMS: dict[InterviewType, tuple[str, ...]] = {
    InterviewType.JOB: ("岗位", "项目"),
    InterviewType.POSTGRADUATE: ("复试", "专业"),
    InterviewType.CIVIL_SERVICE: ("群众", "政策", "依法", "基层", "应急", "追问"),
    InterviewType.IELTS: ("Let's talk", "Describe", "You described", "Why", "How", "What"),
}

SCENARIO_FORBIDDEN_TERMS: dict[InterviewType, tuple[str, ...]] = {
    InterviewType.JOB: ("Part 1", "Part 2", "Part 3", "复试开场", "法条体系", "基层群众"),
    InterviewType.POSTGRADUATE: ("Part 1", "Part 2", "岗位理解", "工作面试", "Let's talk"),
    InterviewType.CIVIL_SERVICE: ("Part 1", "Part 2", "FastAPI", "Redis", "模型评估", "导师方向"),
    InterviewType.IELTS: ("岗位", "复试", "群众", "依法", "法条", "FastAPI"),
}

LEGACY_ROUNDS = {
    InterviewType.POSTGRADUATE: (
        ("复试开场", "专业基础", "科研潜力", "文献与英文", "导师沟通", "学术规范"),
    ),
    InterviewType.CIVIL_SERVICE: (
        ("综合分析", "组织协调", "应急应变", "人际沟通", "岗位匹配"),
    ),
}


@dataclass(frozen=True)
class ObservedInterviewTurn:
    turn_index: int
    round_name: str
    question_text: str
    status: str = "waiting_answer"


@dataclass(frozen=True)
class ObservedInterviewSession:
    session_id: str
    interview_type: InterviewType
    material_context: InterviewMaterialContext | None
    turns: list[ObservedInterviewTurn]


@dataclass
class ObservedScenarioMetric:
    label: str
    session_count: int = 0
    current_session_count: int = 0
    legacy_session_count: int = 0
    turn_count: int = 0
    wrong_question_count: int = 0
    flow_error_count: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "session_count": self.session_count,
            "current_session_count": self.current_session_count,
            "legacy_session_count": self.legacy_session_count,
            "turn_count": self.turn_count,
            "wrong_question_count": self.wrong_question_count,
            "flow_error_count": self.flow_error_count,
        }


@dataclass
class ObservedQualityReport:
    passed: bool
    sample_status: str
    session_count: int
    current_session_count: int
    legacy_session_count: int
    turn_count: int
    wrong_question_risk_rate: float
    flow_error_risk_rate: float
    min_samples: int
    wrong_question_failures: list[str] = field(default_factory=list)
    flow_failures: list[str] = field(default_factory=list)
    scenario_metrics: dict[str, ObservedScenarioMetric] = field(default_factory=dict)

    @property
    def failure_summary(self) -> str:
        if self.sample_status == "insufficient":
            if self.legacy_session_count:
                return (
                    f"样本不足：当前流程 {self.current_session_count} 场，至少需要 {self.min_samples} 场真实记录；"
                    f"另有 {self.legacy_session_count} 场旧流程记录已排除"
                )
            return f"样本不足：当前流程 {self.current_session_count} 场，至少需要 {self.min_samples} 场真实记录"
        failures = [*self.flow_failures, *self.wrong_question_failures]
        return "；".join(failures) if failures else "线上观测指标通过"

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "sample_status": self.sample_status,
            "session_count": self.session_count,
            "current_session_count": self.current_session_count,
            "legacy_session_count": self.legacy_session_count,
            "turn_count": self.turn_count,
            "wrong_question_risk_rate": self.wrong_question_risk_rate,
            "flow_error_risk_rate": self.flow_error_risk_rate,
            "min_samples": self.min_samples,
            "wrong_question_failures": self.wrong_question_failures,
            "flow_failures": self.flow_failures,
            "failure_summary": self.failure_summary,
            "scenario_metrics": {
                scenario: metric.to_dict()
                for scenario, metric in self.scenario_metrics.items()
            },
        }


def evaluate_observed_interviews(
    sessions: list[ObservedInterviewSession],
    min_samples: int = 30,
) -> ObservedQualityReport:
    scenario_metrics = {
        interview_type.value: ObservedScenarioMetric(label=SCENARIO_LABELS[interview_type])
        for interview_type in InterviewType
    }
    flow_failures: list[str] = []
    wrong_question_failures: list[str] = []
    wrong_question_count = 0
    flow_error_count = 0
    turn_count = 0
    current_session_count = 0
    legacy_session_count = 0

    for session in sessions:
        metric = scenario_metrics[session.interview_type.value]
        metric.session_count += 1
        ordered_turns = sorted(session.turns, key=lambda turn: turn.turn_index)
        expected_rounds = EXPECTED_ROUNDS[session.interview_type]
        observed_rounds = [turn.round_name for turn in ordered_turns]
        if _is_legacy_flow(session.interview_type, observed_rounds):
            legacy_session_count += 1
            metric.legacy_session_count += 1
            continue

        current_session_count += 1
        metric.current_session_count += 1
        turn_count += len(ordered_turns)
        metric.turn_count += len(ordered_turns)
        if observed_rounds != expected_rounds[: len(observed_rounds)]:
            flow_error_count += 1
            metric.flow_error_count += 1
            flow_failures.append(f"{session.session_id} round sequence mismatch: {observed_rounds}")

        session_text = " ".join(f"{turn.round_name} {turn.question_text}" for turn in ordered_turns)
        if _has_wrong_question_risk(session.interview_type, session_text):
            wrong_question_count += 1
            metric.wrong_question_count += 1
            wrong_question_failures.append(f"{session.session_id} question text does not match {session.interview_type.value}")

    sample_status = "sufficient" if current_session_count >= min_samples else "insufficient"
    wrong_question_risk_rate = wrong_question_count / max(current_session_count, 1)
    flow_error_risk_rate = flow_error_count / max(current_session_count, 1)
    passed = (
        sample_status == "sufficient"
        and wrong_question_risk_rate <= WRONG_QUESTION_RISK_LIMIT
        and flow_error_risk_rate <= FLOW_ERROR_RISK_LIMIT
        and not wrong_question_failures
        and not flow_failures
    )
    return ObservedQualityReport(
        passed=passed,
        sample_status=sample_status,
        session_count=len(sessions),
        current_session_count=current_session_count,
        legacy_session_count=legacy_session_count,
        turn_count=turn_count,
        wrong_question_risk_rate=round(wrong_question_risk_rate, 4),
        flow_error_risk_rate=round(flow_error_risk_rate, 4),
        min_samples=min_samples,
        wrong_question_failures=wrong_question_failures,
        flow_failures=flow_failures,
        scenario_metrics=scenario_metrics,
    )


def _is_legacy_flow(interview_type: InterviewType, observed_rounds: list[str]) -> bool:
    if not observed_rounds:
        return False
    for legacy_rounds in LEGACY_ROUNDS.get(interview_type, ()):
        if tuple(observed_rounds) == legacy_rounds[: len(observed_rounds)]:
            return True
    return False


def _has_wrong_question_risk(interview_type: InterviewType, session_text: str) -> bool:
    if not session_text.strip():
        return True
    forbidden_hits = [term for term in SCENARIO_FORBIDDEN_TERMS[interview_type] if term.lower() in session_text.lower()]
    if forbidden_hits:
        return True
    required_terms = SCENARIO_REQUIRED_TERMS[interview_type]
    return not any(term.lower() in session_text.lower() for term in required_terms)


@dataclass
class CapabilitySeedObservation:
    ready: bool
    source_version: str | None
    source_policy: str | None
    total_seed_count: int
    counts_by_interview_type: dict[str, int]
    expected_minimums: dict[str, int]
    missing_preset_files: list[str] = field(default_factory=list)
    duplicate_seed_ids: list[str] = field(default_factory=list)
    error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "ready": self.ready,
            "source_version": self.source_version,
            "source_policy": self.source_policy,
            "total_seed_count": self.total_seed_count,
            "counts_by_interview_type": self.counts_by_interview_type,
            "expected_minimums": self.expected_minimums,
            "missing_preset_files": self.missing_preset_files,
            "duplicate_seed_ids": self.duplicate_seed_ids,
            "error": self.error,
        }


@dataclass
class CapabilityVectorObservation:
    ready: bool
    table_name: str
    table_exists: bool
    expected_seed_count: int
    total_vector_count: int = 0
    non_empty_vector_count: int = 0
    distinct_seed_count: int | None = None
    coverage_rate: float = 0.0
    seed_id_column: str | None = None
    vector_column: str | None = None
    embedding_model_column: str | None = None
    embedding_models: list[dict[str, Any]] = field(default_factory=list)
    status_column: str | None = None
    status_counts: list[dict[str, Any]] = field(default_factory=list)
    missing_observation_columns: list[str] = field(default_factory=list)
    detail: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "ready": self.ready,
            "table_name": self.table_name,
            "table_exists": self.table_exists,
            "expected_seed_count": self.expected_seed_count,
            "total_vector_count": self.total_vector_count,
            "non_empty_vector_count": self.non_empty_vector_count,
            "distinct_seed_count": self.distinct_seed_count,
            "coverage_rate": self.coverage_rate,
            "seed_id_column": self.seed_id_column,
            "vector_column": self.vector_column,
            "embedding_model_column": self.embedding_model_column,
            "embedding_models": self.embedding_models,
            "status_column": self.status_column,
            "status_counts": self.status_counts,
            "missing_observation_columns": self.missing_observation_columns,
            "detail": self.detail,
        }


@dataclass
class RecallProbeObservation:
    name: str
    interview_type: str
    expected_preset_id: str
    matched_preset_id: str | None
    matched_title: str | None
    top_score: int
    runner_up_score: int | None
    top_score_gap: int | None
    match_count: int
    ready: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "interview_type": self.interview_type,
            "expected_preset_id": self.expected_preset_id,
            "matched_preset_id": self.matched_preset_id,
            "matched_title": self.matched_title,
            "top_score": self.top_score,
            "runner_up_score": self.runner_up_score,
            "top_score_gap": self.top_score_gap,
            "match_count": self.match_count,
            "ready": self.ready,
        }


@dataclass
class RecallQualityObservation:
    ready: bool
    probe_count: int
    passed_probe_count: int
    probes: list[RecallProbeObservation]

    def to_dict(self) -> dict[str, Any]:
        return {
            "ready": self.ready,
            "probe_count": self.probe_count,
            "passed_probe_count": self.passed_probe_count,
            "probes": [probe.to_dict() for probe in self.probes],
        }


@dataclass
class InterviewCoreReadinessObservation:
    ready: bool
    capability_cards: CapabilitySeedObservation
    capability_vectors: CapabilityVectorObservation
    recall_quality: RecallQualityObservation
    failure_reasons: list[str]

    @property
    def failure_summary(self) -> str:
        return "；".join(self.failure_reasons) if self.failure_reasons else "核心面试业务观测通过"

    def to_dict(self) -> dict[str, Any]:
        return {
            "ready": self.ready,
            "capability_cards": self.capability_cards.to_dict(),
            "capability_vectors": self.capability_vectors.to_dict(),
            "recall_quality": self.recall_quality.to_dict(),
            "failure_reasons": self.failure_reasons,
            "failure_summary": self.failure_summary,
        }


@dataclass(frozen=True)
class RecallProbeSpec:
    name: str
    interview_type: InterviewType
    material_context: InterviewMaterialContext | None
    round_name: str
    expected_preset_id: str


def observe_interview_core_readiness(
    connection: Connection | None = None,
    database_error: str | None = None,
) -> InterviewCoreReadinessObservation:
    capability_cards = observe_capability_card_seeds()
    capability_vectors = inspect_capability_vectors(
        connection,
        expected_seed_count=capability_cards.total_seed_count,
        database_error=database_error,
    )
    recall_quality = observe_recall_quality()
    failure_reasons = _core_failure_reasons(capability_cards, capability_vectors, recall_quality)
    return InterviewCoreReadinessObservation(
        ready=not failure_reasons,
        capability_cards=capability_cards,
        capability_vectors=capability_vectors,
        recall_quality=recall_quality,
        failure_reasons=failure_reasons,
    )


def observe_capability_card_seeds() -> CapabilitySeedObservation:
    try:
        payload = _read_preset_index_payload()
    except (OSError, ValueError) as exc:
        return CapabilitySeedObservation(
            ready=False,
            source_version=None,
            source_policy=None,
            total_seed_count=0,
            counts_by_interview_type={interview_type.value: 0 for interview_type in InterviewType},
            expected_minimums=dict(CAPABILITY_SEED_MINIMUMS),
            error=str(exc),
        )

    raw_ids = [str(item.get("id", "")) for item in payload.get("presets", [])]
    duplicate_seed_ids = sorted(seed_id for seed_id, count in Counter(raw_ids).items() if seed_id and count > 1)
    presets = load_interview_presets()
    counts = Counter(preset.interview_type.value for preset in presets)
    counts_by_interview_type = {interview_type.value: counts.get(interview_type.value, 0) for interview_type in InterviewType}
    missing_preset_files = [
        preset.file
        for preset in presets
        if not read_preset_markdown(preset).strip()
    ]
    enough_seed_counts = all(
        counts_by_interview_type.get(interview_type, 0) >= minimum
        for interview_type, minimum in CAPABILITY_SEED_MINIMUMS.items()
    )
    ready = enough_seed_counts and not missing_preset_files and not duplicate_seed_ids
    return CapabilitySeedObservation(
        ready=ready,
        source_version=_string_or_none(payload.get("version")),
        source_policy=_string_or_none(payload.get("source_policy")),
        total_seed_count=len(presets),
        counts_by_interview_type=counts_by_interview_type,
        expected_minimums=dict(CAPABILITY_SEED_MINIMUMS),
        missing_preset_files=missing_preset_files,
        duplicate_seed_ids=duplicate_seed_ids,
    )


def inspect_capability_vectors(
    connection: Connection | None,
    expected_seed_count: int,
    database_error: str | None = None,
) -> CapabilityVectorObservation:
    if connection is None:
        return CapabilityVectorObservation(
            ready=False,
            table_name=CAPABILITY_VECTOR_TABLE,
            table_exists=False,
            expected_seed_count=expected_seed_count,
            detail=f"database_unavailable: {database_error[:240]}" if database_error else "database_unavailable",
        )

    try:
        inspector = inspect(connection)
        if not inspector.has_table(CAPABILITY_VECTOR_TABLE):
            return CapabilityVectorObservation(
                ready=False,
                table_name=CAPABILITY_VECTOR_TABLE,
                table_exists=False,
                expected_seed_count=expected_seed_count,
                detail="table_missing",
            )

        vector_table = Table(CAPABILITY_VECTOR_TABLE, MetaData(), autoload_with=connection)
        columns = set(vector_table.c.keys())
        seed_id_column = _first_existing_column(columns, VECTOR_SEED_ID_COLUMNS)
        vector_column = _first_existing_column(columns, VECTOR_VALUE_COLUMNS)
        embedding_model_column = _first_existing_column(columns, VECTOR_MODEL_COLUMNS)
        status_column = _first_existing_column(columns, VECTOR_STATUS_COLUMNS)
        total_vector_count = _count_rows(connection, vector_table)
        non_empty_vector_count = (
            _count_non_null_rows(connection, vector_table, vector_column)
            if vector_column is not None
            else 0
        )
        distinct_seed_count = (
            _count_distinct_values(connection, vector_table, seed_id_column)
            if seed_id_column is not None
            else None
        )
        embedding_models = (
            _group_counts(connection, vector_table, embedding_model_column, "model")
            if embedding_model_column is not None
            else []
        )
        status_counts = (
            _group_counts(connection, vector_table, status_column, "status")
            if status_column is not None
            else []
        )
    except SQLAlchemyError as exc:
        return CapabilityVectorObservation(
            ready=False,
            table_name=CAPABILITY_VECTOR_TABLE,
            table_exists=True,
            expected_seed_count=expected_seed_count,
            detail=str(exc)[:240],
        )

    coverage_basis = distinct_seed_count if distinct_seed_count is not None else total_vector_count
    coverage_rate = _ratio(coverage_basis, expected_seed_count)
    model_count = sum(int(item["count"]) for item in embedding_models)
    all_status_ready = _all_vector_statuses_ready(status_counts, total_vector_count)
    missing_observation_columns = _missing_vector_columns(seed_id_column, vector_column, embedding_model_column)
    ready = (
        total_vector_count > 0
        and seed_id_column is not None
        and coverage_basis >= expected_seed_count
        and vector_column is not None
        and non_empty_vector_count == total_vector_count
        and embedding_model_column is not None
        and model_count == total_vector_count
        and all_status_ready
    )
    return CapabilityVectorObservation(
        ready=ready,
        table_name=CAPABILITY_VECTOR_TABLE,
        table_exists=True,
        expected_seed_count=expected_seed_count,
        total_vector_count=total_vector_count,
        non_empty_vector_count=non_empty_vector_count,
        distinct_seed_count=distinct_seed_count,
        coverage_rate=coverage_rate,
        seed_id_column=seed_id_column,
        vector_column=vector_column,
        embedding_model_column=embedding_model_column,
        embedding_models=embedding_models,
        status_column=status_column,
        status_counts=status_counts,
        missing_observation_columns=missing_observation_columns,
    )


def observe_recall_quality() -> RecallQualityObservation:
    probes = [_run_recall_probe(spec) for spec in _recall_probe_specs()]
    passed_probe_count = sum(1 for probe in probes if probe.ready)
    return RecallQualityObservation(
        ready=passed_probe_count == len(probes),
        probe_count=len(probes),
        passed_probe_count=passed_probe_count,
        probes=probes,
    )


def _run_recall_probe(spec: RecallProbeSpec) -> RecallProbeObservation:
    matches = match_interview_presets(
        spec.interview_type,
        spec.material_context,
        round_name=spec.round_name,
        limit=3,
    )
    top_match = matches[0] if matches else None
    runner_up_score = matches[1].score if len(matches) > 1 else None
    top_score = top_match.score if top_match else 0
    top_score_gap = top_score - runner_up_score if runner_up_score is not None else top_score
    ready = (
        top_match is not None
        and top_match.id == spec.expected_preset_id
        and top_score > 0
        and (runner_up_score is None or top_score > runner_up_score)
    )
    return RecallProbeObservation(
        name=spec.name,
        interview_type=spec.interview_type.value,
        expected_preset_id=spec.expected_preset_id,
        matched_preset_id=top_match.id if top_match else None,
        matched_title=top_match.title if top_match else None,
        top_score=top_score,
        runner_up_score=runner_up_score,
        top_score_gap=top_score_gap,
        match_count=len(matches),
        ready=ready,
    )


def _recall_probe_specs() -> list[RecallProbeSpec]:
    return [
        RecallProbeSpec(
            name="job_python_backend",
            interview_type=InterviewType.JOB,
            material_context=InterviewMaterialContext(
                id="core-observe-job",
                user_email="observe@example.com",
                interview_type=InterviewType.JOB,
                resume_filename=None,
                resume_content_type=None,
                resume_text="智能客服 RAG 项目：我负责检索链路、向量库召回、Redis 缓存和接口延迟优化。",
                job_title="Python 后端工程师",
                job_requirements="负责 FastAPI 服务、PostgreSQL 建模、Redis 缓存、RAG 检索链路和接口稳定性。",
                target_school=None,
                major=None,
                research_direction=None,
                profile_summary="FastAPI、Redis、RAG 检索链路和线上稳定性。",
                keywords=["FastAPI", "Redis", "RAG", "向量库"],
                created_at=datetime(2026, 6, 5),
            ),
            round_name="专业一面",
            expected_preset_id="backend-python-engineer",
        ),
        RecallProbeSpec(
            name="postgraduate_computer_science",
            interview_type=InterviewType.POSTGRADUATE,
            material_context=InterviewMaterialContext(
                id="core-observe-postgraduate",
                user_email="observe@example.com",
                interview_type=InterviewType.POSTGRADUATE,
                resume_filename=None,
                resume_content_type=None,
                resume_text=None,
                job_title=None,
                job_requirements=None,
                target_school="北京大学",
                major="计算机科学与技术",
                research_direction="大模型教育应用",
                profile_summary="计算机科学与技术、大模型教育应用。",
                keywords=["计算机", "大模型", "教育"],
                created_at=datetime(2026, 6, 5),
            ),
            round_name="专业基础",
            expected_preset_id="computer-science",
        ),
        RecallProbeSpec(
            name="civil_service_comprehensive_analysis",
            interview_type=InterviewType.CIVIL_SERVICE,
            material_context=None,
            round_name="综合分析",
            expected_preset_id="civil-comprehensive-analysis",
        ),
        RecallProbeSpec(
            name="ielts_speaking_part2",
            interview_type=InterviewType.IELTS,
            material_context=None,
            round_name="Part 2",
            expected_preset_id="ielts-speaking-part2",
        ),
    ]


def _core_failure_reasons(
    capability_cards: CapabilitySeedObservation,
    capability_vectors: CapabilityVectorObservation,
    recall_quality: RecallQualityObservation,
) -> list[str]:
    reasons: list[str] = []
    if not capability_cards.ready:
        low_seed_types = [
            f"{interview_type}:{capability_cards.counts_by_interview_type.get(interview_type, 0)}/{minimum}"
            for interview_type, minimum in capability_cards.expected_minimums.items()
            if capability_cards.counts_by_interview_type.get(interview_type, 0) < minimum
        ]
        if low_seed_types:
            reasons.append(f"能力卡片 seed 数不足 {', '.join(low_seed_types)}")
        if capability_cards.missing_preset_files:
            reasons.append(f"能力卡片文件缺失 {len(capability_cards.missing_preset_files)} 个")
        if capability_cards.duplicate_seed_ids:
            reasons.append(f"能力卡片 seed id 重复 {', '.join(capability_cards.duplicate_seed_ids[:5])}")
        if capability_cards.error:
            reasons.append(f"能力卡片索引读取失败 {capability_cards.error[:120]}")
    if not capability_vectors.ready:
        reasons.append(_capability_vector_failure_reason(capability_vectors))
    if not recall_quality.ready:
        failed_probes = [probe.name for probe in recall_quality.probes if not probe.ready]
        reasons.append(f"能力卡片召回探针失败 {', '.join(failed_probes)}")
    return reasons


def _capability_vector_failure_reason(observation: CapabilityVectorObservation) -> str:
    if not observation.table_exists:
        return f"{observation.table_name} 未就绪：{observation.detail or 'table_missing'}"
    if observation.total_vector_count <= 0:
        return f"{observation.table_name} 未就绪：向量记录为空"
    if observation.seed_id_column is None:
        return f"{observation.table_name} 未就绪：缺少能力卡片 seed 标识列"
    if observation.coverage_rate < 1:
        return (
            f"{observation.table_name} 未覆盖全部能力卡片 seed："
            f"{observation.distinct_seed_count or observation.total_vector_count}/{observation.expected_seed_count}"
        )
    if observation.vector_column is None:
        return f"{observation.table_name} 未就绪：缺少 embedding 向量列"
    if observation.non_empty_vector_count != observation.total_vector_count:
        return (
            f"{observation.table_name} 未就绪：非空向量 "
            f"{observation.non_empty_vector_count}/{observation.total_vector_count}"
        )
    if observation.embedding_model_column is None:
        return f"{observation.table_name} 未就绪：缺少 embedding 模型列"
    model_count = sum(int(item["count"]) for item in observation.embedding_models)
    if model_count != observation.total_vector_count:
        return (
            f"{observation.table_name} 未就绪：embedding 模型信息 "
            f"{model_count}/{observation.total_vector_count}"
        )
    if not _all_vector_statuses_ready(observation.status_counts, observation.total_vector_count):
        return f"{observation.table_name} 未就绪：存在非 ready 状态向量"
    return f"{observation.table_name} 未就绪：{observation.detail or 'unknown'}"


def _read_preset_index_payload() -> dict[str, Any]:
    if not PRESET_INDEX_FILE.exists():
        raise OSError(f"{PRESET_INDEX_FILE} does not exist")
    payload = PRESET_INDEX_FILE.read_text(encoding="utf-8")
    return json.loads(payload)


def _first_existing_column(columns: set[str], candidates: tuple[str, ...]) -> str | None:
    for candidate in candidates:
        if candidate in columns:
            return candidate
    return None


def _count_rows(connection: Connection, vector_table: Table) -> int:
    return int(connection.execute(select(func.count()).select_from(vector_table)).scalar_one())


def _count_non_null_rows(connection: Connection, vector_table: Table, column_name: str) -> int:
    column = vector_table.c[column_name]
    return int(
        connection.execute(
            select(func.count()).select_from(vector_table).where(column.is_not(None))
        ).scalar_one()
    )


def _count_distinct_values(connection: Connection, vector_table: Table, column_name: str) -> int:
    column = vector_table.c[column_name]
    return int(
        connection.execute(
            select(func.count(func.distinct(column))).select_from(vector_table).where(column.is_not(None))
        ).scalar_one()
    )


def _group_counts(
    connection: Connection,
    vector_table: Table,
    column_name: str,
    label: str,
) -> list[dict[str, Any]]:
    column = vector_table.c[column_name]
    count_label = func.count().label("count")
    rows = connection.execute(
        select(column, count_label)
        .select_from(vector_table)
        .where(column.is_not(None))
        .group_by(column)
        .order_by(count_label.desc())
    ).all()
    return [
        {label: str(row[0]), "count": int(row[1])}
        for row in rows
        if row[0] is not None and str(row[0]).strip()
    ]


def _missing_vector_columns(
    seed_id_column: str | None,
    vector_column: str | None,
    embedding_model_column: str | None,
) -> list[str]:
    missing: list[str] = []
    if seed_id_column is None:
        missing.append("seed_id")
    if vector_column is None:
        missing.append("embedding_vector")
    if embedding_model_column is None:
        missing.append("embedding_model")
    return missing


def _all_vector_statuses_ready(status_counts: list[dict[str, Any]], total_vector_count: int) -> bool:
    if not status_counts:
        return True
    ready_count = sum(
        int(item["count"])
        for item in status_counts
        if str(item.get("status", "")).strip().lower() in READY_VECTOR_STATUSES
    )
    return ready_count == total_vector_count


def _ratio(value: int, total: int) -> float:
    if total <= 0:
        return 0.0
    return round(min(value / total, 1.0), 4)


def _string_or_none(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
