from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_quality_gate import EXPECTED_ROUNDS, SCENARIO_LABELS


WRONG_QUESTION_RISK_LIMIT = 0.01
FLOW_ERROR_RISK_LIMIT = 0.02

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
    turn_count: int = 0
    wrong_question_count: int = 0
    flow_error_count: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "session_count": self.session_count,
            "turn_count": self.turn_count,
            "wrong_question_count": self.wrong_question_count,
            "flow_error_count": self.flow_error_count,
        }


@dataclass
class ObservedQualityReport:
    passed: bool
    sample_status: str
    session_count: int
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
            return f"样本不足：当前 {self.session_count} 场，至少需要 {self.min_samples} 场真实记录"
        failures = [*self.flow_failures, *self.wrong_question_failures]
        return "；".join(failures) if failures else "线上观测指标通过"

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "sample_status": self.sample_status,
            "session_count": self.session_count,
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

    for session in sessions:
        metric = scenario_metrics[session.interview_type.value]
        metric.session_count += 1
        ordered_turns = sorted(session.turns, key=lambda turn: turn.turn_index)
        turn_count += len(ordered_turns)
        metric.turn_count += len(ordered_turns)
        expected_rounds = EXPECTED_ROUNDS[session.interview_type]
        observed_rounds = [turn.round_name for turn in ordered_turns]
        if observed_rounds != expected_rounds[: len(observed_rounds)]:
            flow_error_count += 1
            metric.flow_error_count += 1
            flow_failures.append(f"{session.session_id} round sequence mismatch: {observed_rounds}")

        session_text = " ".join(f"{turn.round_name} {turn.question_text}" for turn in ordered_turns)
        if _has_wrong_question_risk(session.interview_type, session_text):
            wrong_question_count += 1
            metric.wrong_question_count += 1
            wrong_question_failures.append(f"{session.session_id} question text does not match {session.interview_type.value}")

    sample_status = "sufficient" if len(sessions) >= min_samples else "insufficient"
    wrong_question_risk_rate = wrong_question_count / max(len(sessions), 1)
    flow_error_risk_rate = flow_error_count / max(len(sessions), 1)
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
        turn_count=turn_count,
        wrong_question_risk_rate=round(wrong_question_risk_rate, 4),
        flow_error_risk_rate=round(flow_error_risk_rate, 4),
        min_samples=min_samples,
        wrong_question_failures=wrong_question_failures,
        flow_failures=flow_failures,
        scenario_metrics=scenario_metrics,
    )


def _has_wrong_question_risk(interview_type: InterviewType, session_text: str) -> bool:
    if not session_text.strip():
        return True
    forbidden_hits = [term for term in SCENARIO_FORBIDDEN_TERMS[interview_type] if term.lower() in session_text.lower()]
    if forbidden_hits:
        return True
    required_terms = SCENARIO_REQUIRED_TERMS[interview_type]
    return not any(term.lower() in session_text.lower() for term in required_terms)
