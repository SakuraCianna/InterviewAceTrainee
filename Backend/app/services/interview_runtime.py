from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.entities import InterviewReport, InterviewSession, InterviewTurn
from app.schemas.interviews import (
    InterviewQuestion,
    InterviewReportDimension,
    InterviewReportResponse,
    InterviewReportTurn,
    InterviewType,
)
from app.services.interview_products import get_interview_product


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


@dataclass(frozen=True)
class InterviewStep:
    round_name: str
    question_text: str


@dataclass
class InterviewState:
    session_id: str
    user_email: str
    interview_type: InterviewType
    status: str
    current_step_index: int
    total_steps: int
    current_question: InterviewQuestion | None
    report: InterviewReportResponse | None = None


class InterviewSessionNotFoundError(LookupError):
    """Raised when a session is not owned by the current user or does not exist."""


def build_interview_steps(interview_type: InterviewType) -> list[InterviewStep]:
    plans: dict[InterviewType, list[InterviewStep]] = {
        InterviewType.JOB: [
            InterviewStep("专业一面", "请用 90 秒介绍一个最能体现你能力的项目，并说明你负责的关键部分。"),
            InterviewStep("专业一面追问", "如果这个项目重新做一次，你会优先优化哪个技术决策，为什么？"),
            InterviewStep("专业二面", "请解释你在项目中遇到的一个复杂问题，以及你如何定位和解决。"),
            InterviewStep("专业二面追问", "当面试官质疑你的方案成本过高时，你会如何回应？"),
            InterviewStep("HR 面", "你为什么选择这个岗位？请结合经历说明你的匹配点。"),
            InterviewStep("HR 面追问", "如果入职后发现业务节奏和预期不同，你会如何调整？"),
        ],
        InterviewType.POSTGRADUATE: [
            InterviewStep("复试开场", "请做一段 90 秒以内的中文自我介绍，突出专业背景和复试动机。"),
            InterviewStep("专业基础", "请说明你本科阶段最熟悉的一门专业课，以及它如何支撑你的研究兴趣。"),
            InterviewStep("科研潜力", "如果导师要求你快速阅读一篇英文论文，你会如何拆解和汇报？"),
            InterviewStep("导师沟通", "如果你的毕业设计方向和导师课题不完全一致，你会怎样建立连接？"),
        ],
        InterviewType.CIVIL_SERVICE: [
            InterviewStep("综合分析", "请谈谈你对基层数字化治理的理解，并说明可能带来的积极影响和风险。"),
            InterviewStep("组织协调", "如果让你组织一次社区政策宣讲活动，你会如何安排流程？"),
            InterviewStep("应急应变", "活动现场群众对政策产生误解并情绪激动，你会如何处理？"),
            InterviewStep("人际沟通", "如果同事临时无法完成配合工作，影响了整体进度，你会怎么办？"),
        ],
        InterviewType.IELTS: [
            InterviewStep("Part 1", "Let's talk about your hometown. What do you like most about it?"),
            InterviewStep("Part 1 Follow-up", "Do you think your hometown is a good place for young people? Why?"),
            InterviewStep("Part 2", "Describe a skill you would like to improve. You should say what it is, why you want to improve it, and how you plan to do that."),
            InterviewStep("Part 3", "Why do some people find it hard to keep learning new skills as adults?"),
            InterviewStep("Part 3 Follow-up", "How can schools help students become better lifelong learners?"),
        ],
    }
    return plans[interview_type]


def build_report(session_id: str, interview_type: InterviewType, turns: list[dict[str, str]]) -> InterviewReportResponse:
    product = get_interview_product(interview_type)
    answer_lengths = [len(turn["answer"].strip()) for turn in turns]
    average_length = sum(answer_lengths) / max(len(answer_lengths), 1)
    base_score = 62 + min(int(average_length / 12), 18) + min(len(turns), 6)
    total_score = max(60, min(base_score, 92))

    dimensions: list[InterviewReportDimension] = []
    for index, name in enumerate(product.report_focus):
        offset = ((index * 7) % 11) - 4
        score = max(55, min(total_score + offset, 95))
        dimensions.append(
            InterviewReportDimension(
                name=name,
                score=score,
                comment=_dimension_comment(name=name, score=score),
            )
        )

    short_answers = sum(1 for length in answer_lengths if length < 40)
    improvements = [
        "回答前先用一句话给出结论，再展开背景、行动和结果。",
        "追问阶段要主动补充取舍依据，避免只描述过程。",
    ]
    if short_answers:
        improvements.insert(0, "部分回答偏短，下一轮建议把关键例子讲到 60 秒以上。")

    report_turns = [
        InterviewReportTurn(round_name=turn["round_name"], question=turn["question"], answer=turn["answer"])
        for turn in turns
    ]
    return InterviewReportResponse(
        session_id=session_id,
        interview_type=interview_type,
        total_score=total_score,
        summary=f"本次{product.name}已完成 {len(turns)} 个问答节点，整体表现处于可继续打磨区间。",
        dimensions=dimensions,
        strengths=["能围绕问题给出直接回应", "已完成完整流程，具备复盘基础"],
        improvements=improvements,
        next_plan=["复听每一轮回答，标记卡顿和空泛表达", "重练最低分维度对应的问题", "下一次训练优先压缩开场并强化案例细节"],
        turns=report_turns,
    )


def _dimension_comment(name: str, score: int) -> str:
    if score >= 85:
        return f"{name}表现稳定，可以进一步提高表达密度。"
    if score >= 75:
        return f"{name}已有基础，建议补充更具体的例子。"
    return f"{name}需要重点训练，先建立清晰的回答结构。"


class InMemoryInterviewRuntimeStore:
    def __init__(self) -> None:
        self._sessions: dict[str, dict[str, Any]] = {}

    def get_session(self, user_email: str, session_id: str) -> InterviewState | None:
        record = self._sessions.get(session_id)
        if record is None or record["user_email"] != user_email:
            return None
        return self._to_state(record)

    def get_active_session(self, user_email: str) -> InterviewState | None:
        candidates = [
            record
            for record in self._sessions.values()
            if record["user_email"] == user_email and record["status"] != "completed"
        ]
        if not candidates:
            return None
        return self._to_state(sorted(candidates, key=lambda item: item["created_at"], reverse=True)[0])

    def create_session(self, user_email: str, session_id: str, interview_type: InterviewType) -> InterviewState:
        steps = build_interview_steps(interview_type)
        record = {
            "session_id": session_id,
            "user_email": user_email,
            "interview_type": interview_type,
            "status": "in_progress",
            "current_step_index": 0,
            "steps": steps,
            "answers": [None for _ in steps],
            "created_at": utc_now(),
            "report": None,
        }
        self._sessions[session_id] = record
        return self._to_state(record)

    def answer_current_question(
        self,
        user_email: str,
        session_id: str,
        answer_text: str,
        next_question_override: str | None = None,
    ) -> InterviewState:
        record = self._sessions.get(session_id)
        if record is None or record["user_email"] != user_email:
            raise InterviewSessionNotFoundError("interview session not found")
        if record["status"] == "completed":
            return self._to_state(record)

        index = record["current_step_index"]
        record["answers"][index] = answer_text
        if index + 1 >= len(record["steps"]):
            record["status"] = "completed"
            record["report"] = build_report(
                session_id=session_id,
                interview_type=record["interview_type"],
                turns=self._turn_dicts(record),
            )
        else:
            if next_question_override:
                next_step = record["steps"][index + 1]
                record["steps"][index + 1] = InterviewStep(next_step.round_name, next_question_override)
            record["current_step_index"] = index + 1
        return self._to_state(record)

    def get_report(self, user_email: str, session_id: str) -> InterviewReportResponse | None:
        state = self.get_session(user_email, session_id)
        return None if state is None else state.report

    def _turn_dicts(self, record: dict[str, Any]) -> list[dict[str, str]]:
        turns: list[dict[str, str]] = []
        for index, step in enumerate(record["steps"]):
            answer = record["answers"][index]
            if answer is None:
                continue
            turns.append({"round_name": step.round_name, "question": step.question_text, "answer": answer})
        return turns

    def _to_state(self, record: dict[str, Any]) -> InterviewState:
        index = record["current_step_index"]
        current_question = None
        if record["status"] != "completed":
            step = record["steps"][index]
            current_question = InterviewQuestion(turn_index=index, round_name=step.round_name, text=step.question_text)
        return InterviewState(
            session_id=record["session_id"],
            user_email=record["user_email"],
            interview_type=record["interview_type"],
            status=record["status"],
            current_step_index=index,
            total_steps=len(record["steps"]),
            current_question=current_question,
            report=record["report"],
        )


class DatabaseInterviewRuntimeStore:
    def __init__(self, session: Session) -> None:
        self._session = session

    def get_session(self, user_email: str, session_id: str) -> InterviewState | None:
        session_model = self._find_session(user_email, session_id)
        if session_model is None:
            return None
        return self._to_state(session_model)

    def get_active_session(self, user_email: str) -> InterviewState | None:
        session_model = self._session.execute(
            select(InterviewSession)
            .where(InterviewSession.user_email == user_email, InterviewSession.status != "completed")
            .order_by(InterviewSession.created_at.desc())
            .limit(1)
        ).scalar_one_or_none()
        if session_model is None:
            return None
        return self._to_state(session_model)

    def create_session(self, user_email: str, session_id: str, interview_type: InterviewType) -> InterviewState:
        steps = build_interview_steps(interview_type)
        session_model = InterviewSession(
            id=session_id,
            user_email=user_email,
            interview_type=str(interview_type),
            status="in_progress",
            current_step_index=0,
            total_steps=len(steps),
            charged_credit=True,
            started_at=utc_now(),
        )
        self._session.add(session_model)
        for index, step in enumerate(steps):
            self._session.add(
                InterviewTurn(
                    session_id=session_id,
                    turn_index=index,
                    round_name=step.round_name,
                    question_text=step.question_text,
                )
            )
        self._session.commit()
        return self._to_state(session_model)

    def answer_current_question(
        self,
        user_email: str,
        session_id: str,
        answer_text: str,
        next_question_override: str | None = None,
    ) -> InterviewState:
        session_model = self._find_session(user_email, session_id)
        if session_model is None:
            raise InterviewSessionNotFoundError("interview session not found")
        if session_model.status == "completed":
            return self._to_state(session_model)

        turn = self._session.execute(
            select(InterviewTurn).where(
                InterviewTurn.session_id == session_id,
                InterviewTurn.turn_index == session_model.current_step_index,
            )
        ).scalar_one()
        turn.answer_text = answer_text
        turn.status = "answered"
        turn.answered_at = utc_now()

        if session_model.current_step_index + 1 >= session_model.total_steps:
            session_model.status = "completed"
            session_model.ended_at = utc_now()
            report = build_report(
                session_id=session_id,
                interview_type=InterviewType(session_model.interview_type),
                turns=self._turn_dicts(session_id),
            )
            self._session.add(
                InterviewReport(session_id=session_id, total_score=report.total_score, report_json=report.model_dump(mode="json"))
            )
        else:
            if next_question_override:
                next_turn = self._session.execute(
                    select(InterviewTurn).where(
                        InterviewTurn.session_id == session_id,
                        InterviewTurn.turn_index == session_model.current_step_index + 1,
                    )
                ).scalar_one()
                next_turn.question_text = next_question_override
            session_model.current_step_index += 1

        self._session.commit()
        return self._to_state(session_model)

    def get_report(self, user_email: str, session_id: str) -> InterviewReportResponse | None:
        if self._find_session(user_email, session_id) is None:
            return None
        report = self._session.execute(
            select(InterviewReport).where(InterviewReport.session_id == session_id).order_by(InterviewReport.created_at.desc()).limit(1)
        ).scalar_one_or_none()
        if report is None:
            return None
        return InterviewReportResponse.model_validate(report.report_json)

    def _find_session(self, user_email: str, session_id: str) -> InterviewSession | None:
        return self._session.execute(
            select(InterviewSession).where(InterviewSession.id == session_id, InterviewSession.user_email == user_email)
        ).scalar_one_or_none()

    def _turns(self, session_id: str) -> list[InterviewTurn]:
        return list(
            self._session.execute(
                select(InterviewTurn).where(InterviewTurn.session_id == session_id).order_by(InterviewTurn.turn_index)
            ).scalars()
        )

    def _turn_dicts(self, session_id: str) -> list[dict[str, str]]:
        return [
            {"round_name": turn.round_name, "question": turn.question_text, "answer": turn.answer_text or ""}
            for turn in self._turns(session_id)
            if turn.answer_text
        ]

    def _to_state(self, session_model: InterviewSession) -> InterviewState:
        current_question = None
        if session_model.status != "completed":
            turn = self._session.execute(
                select(InterviewTurn).where(
                    InterviewTurn.session_id == session_model.id,
                    InterviewTurn.turn_index == session_model.current_step_index,
                )
            ).scalar_one()
            current_question = InterviewQuestion(
                turn_index=turn.turn_index,
                round_name=turn.round_name,
                text=turn.question_text,
            )

        return InterviewState(
            session_id=session_model.id,
            user_email=session_model.user_email,
            interview_type=InterviewType(session_model.interview_type),
            status=session_model.status,
            current_step_index=session_model.current_step_index,
            total_steps=session_model.total_steps,
            current_question=current_question,
            report=self.get_report(session_model.user_email, session_model.id) if session_model.status == "completed" else None,
        )


memory_interview_store = InMemoryInterviewRuntimeStore()
