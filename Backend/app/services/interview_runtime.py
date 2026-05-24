from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.entities import InterviewMaterial, InterviewReport, InterviewSession, InterviewTurn
from app.schemas.interviews import (
    InterviewQuestion,
    InterviewReportDimension,
    InterviewReportResponse,
    InterviewReportTurn,
    InterviewType,
)
from app.services.interview_products import get_interview_product
from app.services.interview_material_context import InterviewMaterialContext


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
    material_context: InterviewMaterialContext | None = None


@dataclass
class InterviewHistoryRecord:
    session_id: str
    interview_type: InterviewType
    status: str
    current_step_index: int
    total_steps: int
    report_total_score: int | None
    created_at: datetime


class InterviewSessionNotFoundError(LookupError):
    """Raised when a session is not owned by the current user or does not exist."""


def build_interview_steps(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
) -> list[InterviewStep]:
    material_keywords = "、".join(material_context.keywords[:4]) if material_context else ""
    plans: dict[InterviewType, list[InterviewStep]] = {
        InterviewType.JOB: [
            InterviewStep("专业一面", "请用 90 秒按 STAR 结构介绍一个最能体现岗位匹配度的项目，并说明你的职责边界。"),
            InterviewStep("专业一面", "请展开这个项目里的一个关键技术决策，说明你比较过哪些方案、为什么这样取舍。"),
            InterviewStep("专业一面追问", "如果面试官要求你用量化结果证明贡献，你会拿出哪些指标、日志或用户反馈？"),
            InterviewStep("专业二面", "请解释你遇到过的一个复杂线上或工程问题，以及你如何定位根因。"),
            InterviewStep("专业二面", "如果同样问题在更高并发、更低预算或更短周期下出现，你会如何调整方案？"),
            InterviewStep("专业二面追问", "当面试官质疑你的方案成本过高或风险过大时，你会怎样回应并给出备选方案？"),
            InterviewStep("HR 面", "你为什么选择这个岗位？请结合岗位 JD、个人经历和长期目标说明匹配点。"),
            InterviewStep("HR 面追问", "如果入职后发现业务节奏和预期不同，你会如何保持产出、沟通预期并持续学习？"),
        ],
        InterviewType.POSTGRADUATE: [
            InterviewStep("复试开场", "请做一段 90 秒以内的中文自我介绍，突出专业背景、复试动机和你能带来的研究基础。"),
            InterviewStep("专业基础", "请说明你本科阶段最熟悉的一门专业课，并举例说明它如何支撑你的研究兴趣。"),
            InterviewStep("科研潜力", "请介绍一个课程设计、毕业设计或竞赛项目，并说明其中值得继续研究的问题。"),
            InterviewStep("文献与英文", "如果导师要求你快速阅读一篇英文论文，你会如何拆解摘要、方法、实验和结论并做汇报？"),
            InterviewStep("导师沟通", "如果你的毕业设计方向和导师课题不完全一致，你会怎样建立连接并提出可执行的研究切入点？"),
            InterviewStep("学术规范", "如果你发现实验结果和预期不一致，甚至影响论文进度，你会如何处理科研诚信、数据记录和沟通汇报？"),
        ],
        InterviewType.CIVIL_SERVICE: [
            InterviewStep("综合分析", "请谈谈你对基层数字化治理的理解，并从群众获得感、依法行政和风险防控三个角度分析。"),
            InterviewStep("组织协调", "如果让你组织一次社区政策宣讲活动，你会如何明确对象、资源、流程、分工和应急预案？"),
            InterviewStep("应急应变", "活动现场群众对政策产生误解并情绪激动，你会如何稳控现场、核实事实并依法依规处理？"),
            InterviewStep("人际沟通", "如果同事临时无法完成配合工作，影响整体进度，你会如何沟通、补位并复盘协作机制？"),
            InterviewStep("岗位匹配", "请结合公务员岗位职责，说明你如何理解服务意识、纪律意识和长期基层工作的压力。"),
        ],
        InterviewType.IELTS: [
            InterviewStep("Part 1", "Let's talk about your hometown. What do you like most about it?"),
            InterviewStep("Part 1", "Do you usually study or work better alone or with other people? Why?"),
            InterviewStep("Part 1 Follow-up", "Do you think your hometown is a good place for young people? Why?"),
            InterviewStep("Part 2", "Describe a skill you would like to improve. You should say what it is, why you want to improve it, how you plan to improve it, and explain how this skill may help you in the future."),
            InterviewStep("Part 3", "Let's discuss this topic in a more abstract way. Why do some adults find it difficult to keep learning new skills?"),
            InterviewStep("Part 3 Follow-up", "How can schools and workplaces help people become better lifelong learners?"),
        ],
    }
    steps = plans[interview_type]
    if interview_type == InterviewType.JOB and material_context is not None:
        job_title = material_context.job_title or "目标岗位"
        jd_focus = material_keywords or _compact_prompt_hint(material_context.job_requirements)
        steps = [
            InterviewStep(
                "专业一面",
                f"你正在面试「{job_title}」。请用 90 秒按 STAR 结构介绍一个最能匹配该岗位的项目，并优先覆盖这些要求：{jd_focus}。",
            ),
            InterviewStep(
                "专业一面",
                f"结合你的简历和「{job_title}」岗位 JD，请展开一个关键技术决策，说明你比较过哪些方案、为什么这样取舍。",
            ),
            *steps[2:],
        ]
    if interview_type == InterviewType.POSTGRADUATE and material_context is not None:
        major = material_context.major or "报考专业"
        direction = material_context.research_direction or "你的研究兴趣"
        steps = [
            InterviewStep(
                "复试开场",
                f"请做一段 90 秒以内的中文自我介绍，围绕「{major}」和「{direction}」突出专业背景、复试动机和你能带来的研究基础。",
            ),
            InterviewStep(
                "专业基础",
                f"请说明「{major}」里你本科阶段最熟悉的一门专业课，并举例说明它如何支撑「{direction}」。",
            ),
            *steps[2:],
        ]
    return steps


def build_report(session_id: str, interview_type: InterviewType, turns: list[dict[str, str]]) -> InterviewReportResponse:
    product = get_interview_product(interview_type)
    answer_text = " ".join(turn["answer"].strip() for turn in turns)
    answer_lengths = [len(turn["answer"].strip()) for turn in turns]
    average_length = sum(answer_lengths) / max(len(answer_lengths), 1)
    base_score = _base_report_score(answer_text, average_length, len(turns))

    dimensions: list[InterviewReportDimension] = []
    for index, name in enumerate(product.report_focus):
        score = _dimension_score(interview_type, name, answer_text, base_score, index)
        dimensions.append(
            InterviewReportDimension(
                name=name,
                score=score,
                comment=_dimension_comment(interview_type=interview_type, name=name, score=score),
            )
        )

    total_score = round(sum(dimension.score for dimension in dimensions) / max(len(dimensions), 1))
    short_answers = sum(1 for length in answer_lengths if length < 40)
    weakest_dimension = min(dimensions, key=lambda dimension: dimension.score).name if dimensions else product.report_focus[0]

    report_turns = [
        InterviewReportTurn(round_name=turn["round_name"], question=turn["question"], answer=turn["answer"])
        for turn in turns
    ]
    return InterviewReportResponse(
        session_id=session_id,
        interview_type=interview_type,
        total_score=total_score,
        summary=_scenario_summary(interview_type, product.name, len(turns), total_score),
        dimensions=dimensions,
        strengths=_scenario_strengths(interview_type, answer_text),
        improvements=_scenario_improvements(interview_type, weakest_dimension, short_answers),
        next_plan=_scenario_next_plan(interview_type, weakest_dimension),
        turns=report_turns,
    )


def _base_report_score(answer_text: str, average_length: float, answered_turns: int) -> int:
    structure_bonus = 5 if _contains_any(answer_text, ["首先", "其次", "最后", "第一", "第二", "第三", "because", "first", "second", "finally"]) else 0
    evidence_bonus = 5 if _contains_any(answer_text, ["数据", "指标", "量化", "%", "提升", "降低", "日志", "用户", "for example", "result"]) else 0
    completeness_bonus = min(answered_turns * 2, 10)
    length_bonus = min(int(average_length / 18), 14)
    return max(58, min(64 + structure_bonus + evidence_bonus + completeness_bonus + length_bonus, 88))


def _dimension_score(interview_type: InterviewType, name: str, answer_text: str, base_score: int, index: int) -> int:
    signal_keywords = _dimension_signal_keywords(interview_type).get(name, [])
    signal_bonus = min(sum(1 for keyword in signal_keywords if keyword.lower() in answer_text.lower()) * 3, 12)
    offset = ((index * 5) % 9) - 4
    return max(55, min(base_score + signal_bonus + offset, 95))


def _dimension_signal_keywords(interview_type: InterviewType) -> dict[str, list[str]]:
    return {
        InterviewType.JOB: {
            "岗位匹配与动机": ["岗位", "JD", "业务", "目标", "动机", "长期"],
            "专业解释准确性": ["架构", "技术", "方案", "接口", "缓存", "并发", "数据库"],
            "项目证据与量化结果": ["STAR", "指标", "量化", "%", "提升", "降低", "用户", "日志"],
            "问题定位与复盘": ["根因", "定位", "监控", "灰度", "复盘", "排查"],
            "压力追问应对": ["成本", "风险", "备选", "取舍", "预算", "沟通"],
        },
        InterviewType.POSTGRADUATE: {
            "专业基础扎实度": ["专业课", "理论", "模型", "公式", "实验", "基础"],
            "科研兴趣与问题意识": ["研究", "问题", "假设", "创新", "课题", "方法"],
            "文献阅读与英文表达": ["英文", "论文", "摘要", "方法", "实验", "结论", "literature"],
            "导师沟通适配": ["导师", "方向", "沟通", "切入点", "课题", "计划"],
            "学术规范意识": ["诚信", "数据", "记录", "复现", "规范", "如实"],
        },
        InterviewType.CIVIL_SERVICE: {
            "审题与综合分析": ["背景", "原因", "影响", "风险", "对策", "分析"],
            "结构化表达": ["第一", "第二", "第三", "首先", "其次", "最后"],
            "计划组织协调": ["对象", "资源", "流程", "分工", "预案", "协调"],
            "应急应变": ["稳定", "核实", "上报", "现场", "应急", "处置"],
            "公共服务价值观": ["群众", "依法", "服务", "公平", "纪律", "基层"],
        },
        InterviewType.IELTS: {
            "Fluency and coherence": ["because", "however", "first", "second", "finally", "for example"],
            "Lexical resource": ["reliable", "community", "energetic", "public", "organise", "logically"],
            "Grammatical range and accuracy": ["would", "could", "because", "although", "which", "that"],
            "Pronunciation": ["recorded", "speaking", "practice", "rhythm", "stress", "intonation"],
        },
    }[interview_type]


def _scenario_summary(interview_type: InterviewType, product_name: str, answered_turns: int, total_score: int) -> str:
    templates = {
        InterviewType.JOB: f"本次{product_name}已完成 {answered_turns} 个问答节点，重点复盘岗位匹配、项目证据、技术取舍和压力追问，当前综合分 {total_score}。",
        InterviewType.POSTGRADUATE: f"本次{product_name}已完成 {answered_turns} 个问答节点，重点复盘专业基础、科研潜力、文献阅读和导师沟通，当前综合分 {total_score}。",
        InterviewType.CIVIL_SERVICE: f"本次{product_name}按结构化面试口径完成 {answered_turns} 个问答节点，重点复盘审题、组织协调、应急处置和公共服务价值观，当前综合分 {total_score}。",
        InterviewType.IELTS: f"This IELTS speaking rehearsal covered {answered_turns} turns across the official speaking flow, with feedback aligned to fluency, lexical resource, grammar and pronunciation. Overall score: {total_score}.",
    }
    return templates[interview_type]


def _scenario_strengths(interview_type: InterviewType, answer_text: str) -> list[str]:
    common = ["能围绕问题给出直接回应", "已完成完整流程，具备复盘基础"]
    if interview_type == InterviewType.JOB:
        return ["能够把经历放进项目或岗位语境里表达", "具备继续深挖技术细节和量化结果的基础"]
    if interview_type == InterviewType.POSTGRADUATE:
        return ["能够围绕专业背景和研究动机展开", "已经覆盖复试常见的自我介绍、专业和导师沟通环节"]
    if interview_type == InterviewType.CIVIL_SERVICE:
        return ["能够尝试从公共治理和群众视角回应问题", "具备按照结构化题型继续训练的基础"]
    if _contains_any(answer_text, ["because", "for example", "would", "could"]):
        return ["Can extend answers beyond one-sentence responses", "Shows usable connectors and examples for spoken development"]
    return common


def _scenario_improvements(interview_type: InterviewType, weakest_dimension: str, short_answers: int) -> list[str]:
    improvements = {
        InterviewType.JOB: [
            f"最低维度是「{weakest_dimension}」，下一轮要把答案改成结论、证据、取舍、结果四段式。",
            "项目回答需要主动补充指标、失败案例和复盘，不要只讲职责。",
            "HR 面要把个人动机和岗位 JD 连接起来，避免泛泛说喜欢或感兴趣。",
        ],
        InterviewType.POSTGRADUATE: [
            f"最低维度是「{weakest_dimension}」，下一轮要把专业概念、研究问题和可执行计划连起来。",
            "复试回答要减少背稿感，多补充课程、毕设、论文或实验中的真实证据。",
            "导师沟通题要体现边界感、主动性和学术规范意识。",
        ],
        InterviewType.CIVIL_SERVICE: [
            f"最低维度是「{weakest_dimension}」，下一轮要先审题，再按背景、问题、对策、落实复盘展开。",
            "结构化回答要明确群众立场、依法行政和风险意识。",
            "应急题要先稳控现场和核实事实，再谈处置、上报和复盘。",
        ],
        InterviewType.IELTS: [
            f"The weakest criterion is {weakest_dimension}; practise one-minute answers with a clear opening, example and wrap-up.",
            "Part 2 answers need more concrete story details instead of general opinions.",
            "Part 3 answers should compare causes, effects and possible solutions in a more abstract way.",
        ],
    }[interview_type]
    if short_answers:
        prefix = "部分回答偏短，下一轮建议把关键例子讲到 60 秒以上。" if interview_type != InterviewType.IELTS else "Some answers are short; aim for longer turns with examples and reasons."
        return [prefix, *improvements]
    return improvements


def _scenario_next_plan(interview_type: InterviewType, weakest_dimension: str) -> list[str]:
    return {
        InterviewType.JOB: [
            "把一个核心项目整理成 STAR + 指标 + 失败复盘版本，限定 90 秒讲完。",
            "对照目标岗位 JD，准备 3 个匹配证据和 2 个可能被追问的风险点。",
            f"重练「{weakest_dimension}」相关问题，回答时必须说出一个取舍和一个备选方案。",
        ],
        InterviewType.POSTGRADUATE: [
            "准备一份 90 秒研究兴趣陈述，包含专业基础、问题来源和初步方法。",
            "找一篇英文论文做摘要、方法、实验、结论四段式口头汇报。",
            f"重练「{weakest_dimension}」维度，把导师可能追问的问题提前写成提纲。",
        ],
        InterviewType.CIVIL_SERVICE: [
            "每天选一个政策或社会热点，按是什么、为什么、怎么办做 2 分钟结构化表达。",
            "组织协调题固定训练对象、资源、流程、风险、复盘五个节点。",
            f"重练「{weakest_dimension}」维度，回答必须体现群众立场、政策依据和落实闭环。",
        ],
        InterviewType.IELTS: [
            "Record one Part 2 long turn every day and check whether it lasts 90-120 seconds.",
            "Build a band-focused notebook for linking words, paraphrases and self-correction patterns.",
            f"Practise {weakest_dimension} with one Part 1 answer, one Part 2 cue card and one Part 3 abstract discussion.",
        ],
    }[interview_type]


def _contains_any(text: str, keywords: list[str]) -> bool:
    lowered = text.lower()
    return any(keyword.lower() in lowered for keyword in keywords)


def _compact_prompt_hint(text: str | None, max_chars: int = 80) -> str:
    if not text:
        return "岗位匹配度、项目证据和问题定位"
    compacted = " ".join(text.split())
    return compacted[:max_chars]


def _dimension_comment(interview_type: InterviewType, name: str, score: int) -> str:
    band = "稳定" if score >= 85 else "可提升" if score >= 75 else "需强化"
    scenario_hint = {
        InterviewType.JOB: "请继续用岗位、项目证据和技术取舍支撑回答。",
        InterviewType.POSTGRADUATE: "请继续把专业基础、研究问题和导师沟通连成一条线。",
        InterviewType.CIVIL_SERVICE: "请继续坚持审题、结构、群众立场和依法行政。",
        InterviewType.IELTS: "Keep improving length, coherence and accuracy across all speaking parts.",
    }[interview_type]
    if score >= 85:
        return f"{name}表现{band}，{scenario_hint}"
    if score >= 75:
        return f"{name}已有基础但仍{band}，建议补充更具体的证据或例子。"
    return f"{name}{band}，先建立清晰结构，再补充可验证细节。"


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

    def create_session(
        self,
        user_email: str,
        session_id: str,
        interview_type: InterviewType,
        material_context: InterviewMaterialContext | None = None,
    ) -> InterviewState:
        steps = build_interview_steps(interview_type, material_context)
        record = {
            "session_id": session_id,
            "user_email": user_email,
            "interview_type": interview_type,
            "material_context": material_context,
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

    def list_user_sessions(self, user_email: str, limit: int = 20) -> list[InterviewHistoryRecord]:
        records = [
            record
            for record in self._sessions.values()
            if record["user_email"] == user_email
        ]
        sorted_records = sorted(records, key=lambda item: item["created_at"], reverse=True)[:limit]
        return [
            InterviewHistoryRecord(
                session_id=record["session_id"],
                interview_type=record["interview_type"],
                status=record["status"],
                current_step_index=record["current_step_index"],
                total_steps=len(record["steps"]),
                report_total_score=record["report"].total_score if record["report"] is not None else None,
                created_at=record["created_at"],
            )
            for record in sorted_records
        ]

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
            material_context=record.get("material_context"),
        )


class DatabaseInterviewRuntimeStore:
    def __init__(self, session: Session, commit_on_write: bool = True) -> None:
        self._session = session
        self._commit_on_write = commit_on_write

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

    def create_session(
        self,
        user_email: str,
        session_id: str,
        interview_type: InterviewType,
        material_context: InterviewMaterialContext | None = None,
    ) -> InterviewState:
        steps = build_interview_steps(interview_type, material_context)
        session_model = InterviewSession(
            id=session_id,
            user_email=user_email,
            interview_type=str(interview_type),
            material_id=material_context.id if material_context is not None else None,
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
        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
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

        if self._commit_on_write:
            self._session.commit()
        else:
            self._session.flush()
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

    def list_user_sessions(self, user_email: str, limit: int = 20) -> list[InterviewHistoryRecord]:
        sessions = list(
            self._session.execute(
                select(InterviewSession)
                .where(InterviewSession.user_email == user_email)
                .order_by(InterviewSession.created_at.desc())
                .limit(limit)
            ).scalars()
        )
        return [
            InterviewHistoryRecord(
                session_id=session_model.id,
                interview_type=InterviewType(session_model.interview_type),
                status=session_model.status,
                current_step_index=session_model.current_step_index,
                total_steps=session_model.total_steps,
                report_total_score=self._latest_report_score(session_model.id),
                created_at=session_model.created_at,
            )
            for session_model in sessions
        ]

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

    def _latest_report_score(self, session_id: str) -> int | None:
        report = self._session.execute(
            select(InterviewReport.total_score)
            .where(InterviewReport.session_id == session_id)
            .order_by(InterviewReport.created_at.desc())
            .limit(1)
        ).scalar_one_or_none()
        return report

    def _material_context(self, material_id: str | None) -> InterviewMaterialContext | None:
        if not material_id:
            return None
        material = self._session.execute(
            select(InterviewMaterial).where(InterviewMaterial.id == material_id)
        ).scalar_one_or_none()
        if material is None:
            return None
        return InterviewMaterialContext(
            id=material.id,
            user_email=material.user_email,
            interview_type=InterviewType(material.interview_type),
            resume_filename=material.resume_filename,
            resume_content_type=material.resume_content_type,
            resume_text=material.resume_text,
            job_title=material.job_title,
            job_requirements=material.job_requirements,
            major=material.major,
            research_direction=material.research_direction,
            profile_summary=material.profile_summary,
            keywords=list(material.keywords_json or []),
            created_at=material.created_at,
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
            material_context=self._material_context(session_model.material_id),
        )


memory_interview_store = InMemoryInterviewRuntimeStore()
