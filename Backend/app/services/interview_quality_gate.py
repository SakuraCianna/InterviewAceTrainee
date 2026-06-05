from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from app.schemas.interviews import InterviewType
from app.services.interview_ai import assess_answer_quality
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_question_bank import question_bank_inventory
from app.services.interview_runtime import InterviewStep, build_interview_steps


MIN_CHOICES_BY_SCENARIO = {
    "job": 14,
    "postgraduate": 16,
    "civil_service": 14,
    "ielts": 15,
}

EXPECTED_ROUNDS = {
    InterviewType.JOB: ["岗位理解", "项目证据", "方案取舍", "指标复盘", "根因定位", "压力追问", "协作与动机", "终面收束"],
    InterviewType.POSTGRADUATE: ["复试开场", "专业基础", "项目与科研潜力", "文献与英文", "导师方向适配", "学术规范与压力"],
    InterviewType.CIVIL_SERVICE: ["岗位认知", "综合分析", "计划组织", "人际沟通", "应急处置", "现场追问收束"],
    InterviewType.IELTS: ["Part 1", "Part 1", "Part 1 Follow-up", "Part 2", "Part 2 Follow-up", "Part 3", "Part 3 Follow-up"],
}

SCENARIO_LABELS = {
    InterviewType.JOB: "工作面试",
    InterviewType.POSTGRADUATE: "研究生复试",
    InterviewType.CIVIL_SERVICE: "考公面试",
    InterviewType.IELTS: "雅思口语",
}


@dataclass(frozen=True)
class QualityScenarioCase:
    name: str
    interview_type: InterviewType
    session_id: str
    material_context: InterviewMaterialContext | None
    required_terms: tuple[str, ...]
    forbidden_terms: tuple[str, ...] = ()


@dataclass(frozen=True)
class AnswerQualityCase:
    name: str
    interview_type: InterviewType
    round_name: str
    question: str
    answer: str
    should_accept: bool


@dataclass
class ScenarioQualityMetric:
    label: str
    case_count: int = 0
    flow_failure_count: int = 0
    question_mismatch_count: int = 0
    answer_quality_failure_count: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "case_count": self.case_count,
            "flow_failure_count": self.flow_failure_count,
            "question_mismatch_count": self.question_mismatch_count,
            "answer_quality_failure_count": self.answer_quality_failure_count,
        }


@dataclass
class InterviewQualityReport:
    passed: bool
    wrong_question_risk_rate: float
    flow_error_risk_rate: float
    scenario_count: int
    sample_case_count: int
    total_question_candidates: int
    inventory_failures: list[str] = field(default_factory=list)
    flow_failures: list[str] = field(default_factory=list)
    question_mismatch_failures: list[str] = field(default_factory=list)
    answer_quality_failures: list[str] = field(default_factory=list)
    scenario_metrics: dict[str, ScenarioQualityMetric] = field(default_factory=dict)

    @property
    def failure_summary(self) -> str:
        failures = [
            *self.inventory_failures,
            *self.flow_failures,
            *self.question_mismatch_failures,
            *self.answer_quality_failures,
        ]
        return "；".join(failures) if failures else "质量门禁通过"

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "wrong_question_risk_rate": self.wrong_question_risk_rate,
            "flow_error_risk_rate": self.flow_error_risk_rate,
            "scenario_count": self.scenario_count,
            "sample_case_count": self.sample_case_count,
            "total_question_candidates": self.total_question_candidates,
            "inventory_failures": self.inventory_failures,
            "flow_failures": self.flow_failures,
            "question_mismatch_failures": self.question_mismatch_failures,
            "answer_quality_failures": self.answer_quality_failures,
            "scenario_metrics": {
                scenario: metric.to_dict()
                for scenario, metric in self.scenario_metrics.items()
            },
            "failure_summary": self.failure_summary,
        }


def evaluate_interview_quality() -> InterviewQualityReport:
    inventory = question_bank_inventory()
    inventory_failures, total_question_candidates = _evaluate_inventory(inventory)
    scenario_metrics = {
        interview_type.value: ScenarioQualityMetric(label=SCENARIO_LABELS[interview_type])
        for interview_type in InterviewType
    }
    flow_failures: list[str] = []
    question_mismatch_failures: list[str] = []
    answer_quality_failures: list[str] = []

    question_cases = _quality_scenario_cases()
    for case in question_cases:
        metric = scenario_metrics[case.interview_type.value]
        metric.case_count += 1
        steps = build_interview_steps(case.interview_type, case.material_context, session_id=case.session_id)
        round_names = [step.round_name for step in steps]
        if round_names != EXPECTED_ROUNDS[case.interview_type]:
            metric.flow_failure_count += 1
            flow_failures.append(f"{case.name} flow mismatch: {round_names}")

        question_text = _join_steps(steps)
        missing_terms = [term for term in case.required_terms if term not in question_text]
        forbidden_hits = [term for term in case.forbidden_terms if term in question_text]
        if missing_terms or forbidden_hits:
            metric.question_mismatch_count += 1
            question_mismatch_failures.append(
                f"{case.name} missing={missing_terms or 'none'} forbidden={forbidden_hits or 'none'}"
            )

    answer_cases = _answer_quality_cases()
    for case in answer_cases:
        metric = scenario_metrics[case.interview_type.value]
        metric.case_count += 1
        decision = assess_answer_quality(case.interview_type, case.question, case.answer, case.round_name)
        if decision.acceptable != case.should_accept:
            metric.answer_quality_failure_count += 1
            answer_quality_failures.append(
                f"{case.name} expected acceptable={case.should_accept} got={decision.acceptable}"
            )

    sample_case_count = len(question_cases) + len(answer_cases)
    wrong_question_risk_rate = len(question_mismatch_failures) / max(len(question_cases), 1)
    flow_error_numerator = len(inventory_failures) + len(flow_failures) + len(answer_quality_failures)
    flow_error_risk_rate = flow_error_numerator / max(sample_case_count, 1)
    passed = (
        not inventory_failures
        and not flow_failures
        and not question_mismatch_failures
        and not answer_quality_failures
        and wrong_question_risk_rate <= 0.01
        and flow_error_risk_rate <= 0.02
    )
    return InterviewQualityReport(
        passed=passed,
        wrong_question_risk_rate=round(wrong_question_risk_rate, 4),
        flow_error_risk_rate=round(flow_error_risk_rate, 4),
        scenario_count=len(scenario_metrics),
        sample_case_count=sample_case_count,
        total_question_candidates=total_question_candidates,
        inventory_failures=inventory_failures,
        flow_failures=flow_failures,
        question_mismatch_failures=question_mismatch_failures,
        answer_quality_failures=answer_quality_failures,
        scenario_metrics=scenario_metrics,
    )


def _evaluate_inventory(inventory: dict[str, dict[str, Any]]) -> tuple[list[str], int]:
    failures: list[str] = []
    total_question_candidates = 0
    for scenario, min_choices in MIN_CHOICES_BY_SCENARIO.items():
        scenario_inventory = inventory.get(scenario)
        if not scenario_inventory:
            failures.append(f"{scenario} inventory missing")
            continue
        rounds = scenario_inventory.get("rounds", [])
        total_question_candidates += sum(int(item.get("choice_count", 0)) for item in rounds)
        for round_info in rounds:
            choice_count = int(round_info.get("choice_count", 0))
            if choice_count < min_choices:
                failures.append(f"{scenario} {round_info.get('round_name')} choices={choice_count} < {min_choices}")
        difficulty_scores = scenario_inventory.get("difficulty_scores", [])
        if difficulty_scores != sorted(difficulty_scores):
            failures.append(f"{scenario} difficulty progression is not ordered")
    return failures, total_question_candidates


def _quality_scenario_cases() -> list[QualityScenarioCase]:
    return [
        QualityScenarioCase(
            name="job-python-project",
            interview_type=InterviewType.JOB,
            session_id="quality-job-python",
            material_context=_job_context(
                job_title="Python 后端工程师",
                job_requirements="负责 FastAPI 服务、PostgreSQL 建模、Redis 缓存、接口稳定性和 RAG 检索链路。",
                resume_text="智能客服 RAG 项目：我负责 FastAPI 接口、Redis 缓存、向量库召回和接口延迟优化。",
                keywords=["FastAPI", "Redis", "RAG", "接口延迟"],
            ),
            required_terms=("智能客服 RAG 项目", "FastAPI", "Redis"),
            forbidden_terms=("法条体系", "Part 1", "基层群众"),
        ),
        QualityScenarioCase(
            name="job-design",
            interview_type=InterviewType.JOB,
            session_id="quality-job-design",
            material_context=_job_context(
                job_title="UI/UX 设计师",
                job_requirements="负责用户旅程、交互逻辑、设计系统、可用性验证和高保真原型。",
                resume_text="招聘后台体验改版项目：我负责用户旅程梳理、组件规范和可用性测试。",
                keywords=["用户旅程", "设计系统", "可用性验证"],
            ),
            required_terms=("用户旅程", "设计系统"),
            forbidden_terms=(
                "Redis 缓存",
                "法条体系",
                "Part 1",
                "招聘专员",
                "岗位画像",
                "保密合规",
                "用户、业务和团队",
                "用户旅程、交互逻辑、设计系统、可用性验证、用户旅程",
            ),
        ),
        QualityScenarioCase(
            name="job-design-title-priority",
            interview_type=InterviewType.JOB,
            session_id="designer-priority-case",
            material_context=_job_context(
                job_title="UI/UX 设计师",
                job_requirements="负责用户旅程、交互逻辑、设计系统、可用性验证和高保真原型。",
                resume_text="招聘后台体验改版项目：我负责用户旅程梳理、组件规范和可用性测试。",
                keywords=["用户旅程", "设计系统", "可用性验证"],
            ),
            required_terms=("UI/UX 设计师", "用户旅程", "设计系统"),
            forbidden_terms=(
                "招聘专员",
                "岗位画像",
                "保密合规",
                "用户、业务和团队",
                "用户旅程、交互逻辑、设计系统、可用性验证、用户旅程",
            ),
        ),
        QualityScenarioCase(
            name="job-clinical-nurse",
            interview_type=InterviewType.JOB,
            session_id="job-nurse-audit",
            material_context=_job_context(
                job_title="临床护士",
                job_requirements="负责病区护理、医嘱执行、患者沟通、护理文书和风险预警。",
                resume_text="三甲医院实习：我负责生命体征记录、患者宣教和护理交接。",
                keywords=["护理", "医嘱", "患者沟通", "风险预警"],
            ),
            required_terms=("患者", "护理", "医嘱"),
            forbidden_terms=("用户、业务和团队", "技术或业务取舍", "业务方"),
        ),
        QualityScenarioCase(
            name="job-product",
            interview_type=InterviewType.JOB,
            session_id="quality-job-product",
            material_context=_job_context(
                job_title="产品经理",
                job_requirements="负责需求洞察、用户场景、指标定义、PRD 与原型、跨团队推进。",
                resume_text="校园招聘投递转化项目：我负责需求调研、漏斗指标设计和原型推进。",
                keywords=["需求洞察", "指标定义", "PRD"],
            ),
            required_terms=("校园招聘投递转化项目", "需求洞察", "指标定义"),
            forbidden_terms=("数据结构与算法复杂度", "群众"),
        ),
        QualityScenarioCase(
            name="job-algorithm",
            interview_type=InterviewType.JOB,
            session_id="quality-job-algorithm",
            material_context=_job_context(
                job_title="AI 算法工程师",
                job_requirements="负责数据集构建、模型评估、实验对比、误差分析和上线效果监控。",
                resume_text="缺陷识别模型项目：我负责数据清洗、模型评估、消融实验和误报分析。",
                keywords=["模型评估", "消融实验", "误差分析"],
            ),
            required_terms=("缺陷识别模型项目", "模型评估", "消融实验"),
            forbidden_terms=("雅思", "群众"),
        ),
        QualityScenarioCase(
            name="postgraduate-computer",
            interview_type=InterviewType.POSTGRADUATE,
            session_id="quality-postgraduate-computer",
            material_context=_postgraduate_context("清华大学", "计算机科学与技术", "大模型教育应用"),
            required_terms=("数据结构与算法复杂度", "模型评估", "文献差异"),
            forbidden_terms=("群众", "Part 1"),
        ),
        QualityScenarioCase(
            name="postgraduate-law",
            interview_type=InterviewType.POSTGRADUATE,
            session_id="quality-postgraduate-law",
            material_context=_postgraduate_context("浙江大学", "法学", "民商法案例研究"),
            required_terms=("法条体系", "法律适用", "案例争点"),
            forbidden_terms=("FastAPI", "Part 1"),
        ),
        QualityScenarioCase(
            name="postgraduate-law-humanities",
            interview_type=InterviewType.POSTGRADUATE,
            session_id="pg-law-audit",
            material_context=_postgraduate_context("浙江大学", "法学", "民商法案例研究"),
            required_terms=("法条体系", "案例争点"),
            forbidden_terms=("技术路线", "实验验证", "工程实现"),
        ),
        QualityScenarioCase(
            name="postgraduate-medicine",
            interview_type=InterviewType.POSTGRADUATE,
            session_id="quality-postgraduate-medicine",
            material_context=_postgraduate_context("中山大学", "临床医学", "循证医学与临床路径"),
            required_terms=("循证证据", "临床路径", "伦理边界"),
            forbidden_terms=("Redis", "PRD"),
        ),
        QualityScenarioCase(
            name="postgraduate-design",
            interview_type=InterviewType.POSTGRADUATE,
            session_id="quality-postgraduate-design",
            material_context=_postgraduate_context("同济大学", "设计学", "服务设计与用户研究"),
            required_terms=("作品集逻辑", "设计研究", "用户或场域验证"),
            forbidden_terms=("法条体系", "FastAPI"),
        ),
        QualityScenarioCase(
            name="civil-service-general",
            interview_type=InterviewType.CIVIL_SERVICE,
            session_id="quality-civil-general",
            material_context=None,
            required_terms=("群众", "依法", "追问"),
            forbidden_terms=("Part 1", "FastAPI"),
        ),
        QualityScenarioCase(
            name="civil-service-emergency",
            interview_type=InterviewType.CIVIL_SERVICE,
            session_id="quality-civil-emergency",
            material_context=None,
            required_terms=("应急", "风险", "现场"),
            forbidden_terms=("模型评估", "IELTS"),
        ),
        QualityScenarioCase(
            name="ielts-theme-a",
            interview_type=InterviewType.IELTS,
            session_id="quality-ielts-a",
            material_context=None,
            required_terms=("Let's talk", "Describe", "Part 2"),
            forbidden_terms=("群众", "复试", "岗位"),
        ),
        QualityScenarioCase(
            name="ielts-theme-b",
            interview_type=InterviewType.IELTS,
            session_id="quality-ielts-b",
            material_context=None,
            required_terms=("Let's talk", "You described", "Describe"),
            forbidden_terms=("群众", "法条", "岗位"),
        ),
    ]


def _answer_quality_cases() -> list[AnswerQualityCase]:
    return [
        AnswerQualityCase(
            name="job-filler-blocked",
            interview_type=InterviewType.JOB,
            round_name="项目证据",
            question="请介绍一个最能证明岗位匹配的项目。",
            answer="你好你好",
            should_accept=False,
        ),
        AnswerQualityCase(
            name="job-structured-accepted",
            interview_type=InterviewType.JOB,
            round_name="项目证据",
            question="请介绍一个最能证明岗位匹配的项目。",
            answer="我负责过智能客服 RAG 项目，背景是接口延迟高。我负责 FastAPI 接口、Redis 缓存和向量召回优化，最终把平均延迟降低，并用日志指标复盘。",
            should_accept=True,
        ),
        AnswerQualityCase(
            name="postgraduate-filler-blocked",
            interview_type=InterviewType.POSTGRADUATE,
            round_name="复试开场",
            question="请做一段复试自我介绍。",
            answer="嗯嗯你好",
            should_accept=False,
        ),
        AnswerQualityCase(
            name="postgraduate-research-accepted",
            interview_type=InterviewType.POSTGRADUATE,
            round_name="专业基础",
            question="请说明你的专业基础和研究兴趣。",
            answer="我本科系统学习过数据结构、机器学习和数据库课程，研究问题聚焦大模型教育反馈。我准备用实验数据、模型评估和文献对比验证方案，并关注学术规范。",
            should_accept=True,
        ),
        AnswerQualityCase(
            name="civil-slogan-blocked",
            interview_type=InterviewType.CIVIL_SERVICE,
            round_name="应急处置",
            question="群众对新政策理解不一致并出现情绪波动，你会如何处理？",
            answer="我会坚持为人民服务，保持耐心，听从领导安排，积极沟通协调，把工作做好，维护群众利益。",
            should_accept=False,
        ),
        AnswerQualityCase(
            name="civil-execution-accepted",
            interview_type=InterviewType.CIVIL_SERVICE,
            round_name="应急处置",
            question="群众对新政策理解不一致并出现情绪波动，你会如何处理？",
            answer="我会先安抚现场情绪，分类记录群众诉求；其次核实政策依据并请示上报统一口径；最后安排专人回访，形成台账复盘，防止同类风险再次出现。",
            should_accept=True,
        ),
        AnswerQualityCase(
            name="ielts-short-blocked",
            interview_type=InterviewType.IELTS,
            round_name="Part 1",
            question="Do you like studying alone?",
            answer="Yes.",
            should_accept=False,
        ),
        AnswerQualityCase(
            name="ielts-developed-accepted",
            interview_type=InterviewType.IELTS,
            round_name="Part 2",
            question="Describe a project you completed successfully.",
            answer="I completed a course project with three classmates. I was responsible for planning the tasks and checking the final presentation. It was successful because we solved problems early and used feedback to improve the result.",
            should_accept=True,
        ),
    ]


def _job_context(job_title: str, job_requirements: str, resume_text: str, keywords: list[str]) -> InterviewMaterialContext:
    return InterviewMaterialContext(
        id=f"quality-{job_title}",
        user_email="quality@example.com",
        interview_type=InterviewType.JOB,
        resume_filename="resume.pdf",
        resume_content_type="application/pdf",
        resume_text=resume_text,
        job_title=job_title,
        job_requirements=job_requirements,
        target_school=None,
        major=None,
        research_direction=None,
        profile_summary=resume_text,
        keywords=keywords,
        created_at=datetime(2026, 6, 5),
    )


def _postgraduate_context(target_school: str, major: str, research_direction: str) -> InterviewMaterialContext:
    return InterviewMaterialContext(
        id=f"quality-{target_school}-{major}",
        user_email="quality@example.com",
        interview_type=InterviewType.POSTGRADUATE,
        resume_filename=None,
        resume_content_type=None,
        resume_text=None,
        job_title=None,
        job_requirements=None,
        target_school=target_school,
        major=major,
        research_direction=research_direction,
        profile_summary="质量门禁样例。",
        keywords=[major, research_direction],
        created_at=datetime(2026, 6, 5),
    )


def _join_steps(steps: list[InterviewStep]) -> str:
    return " ".join(f"{step.round_name} {step.question_text}" for step in steps)
