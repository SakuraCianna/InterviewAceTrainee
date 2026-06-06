from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re
from typing import Any

from app.schemas.interviews import InterviewType
from app.services.interview_capability_retrieval import (
    ExecuteConnection,
    capability_card_inventory,
    capability_questions_for_round,
    capability_round_hint,
    retrieve_capability_cards,
)
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import best_preset_hint, load_interview_presets


PRESET_ROOT = Path(__file__).resolve().parents[1] / "interview_presets"
SCHOOL_TIER_CONFIG_FILE = PRESET_ROOT / "school_tiers.json"
DIFFICULTY_SCORES = {"easy": 1, "medium": 2, "hard": 3, "expert": 4}


@dataclass(frozen=True)
class QuestionBankStep:
    round_name: str
    question_text: str


QuestionRoundBank = tuple[str, str, list[str]]


def build_question_bank_steps(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    session_id: str | None = None,
    db_connection: ExecuteConnection | None = None,
) -> list[QuestionBankStep]:
    seed = session_id or "default-session"
    if interview_type == InterviewType.JOB:
        return _select_capability_aware_steps(_job_banks(material_context), seed, interview_type, material_context, db_connection)
    if interview_type == InterviewType.POSTGRADUATE:
        return _select_capability_aware_steps(_postgraduate_banks(material_context), seed, interview_type, material_context, db_connection)
    if interview_type == InterviewType.CIVIL_SERVICE:
        return _select_capability_aware_steps(_civil_service_banks(), seed, interview_type, material_context, db_connection)
    if interview_type == InterviewType.IELTS:
        return _ielts_steps(seed)
    return []


def question_bank_inventory() -> dict[str, dict[str, Any]]:
    sample_postgraduate_context = InterviewMaterialContext(
        id="inventory-sample",
        user_email="sample@example.com",
        interview_type=InterviewType.POSTGRADUATE,
        resume_filename=None,
        resume_content_type=None,
        resume_text=None,
        job_title=None,
        job_requirements=None,
        target_school="北京大学",
        major="计算机科学与技术",
        research_direction="大模型教育应用",
        profile_summary="题库统计样例。",
        keywords=["计算机", "大模型", "教育"],
        created_at=datetime(2026, 6, 5),
    )
    job_inventory = _inventory_from_banks(_job_banks(None))
    job_inventory["role_bank_count"] = _preset_bank_count(InterviewType.JOB)
    postgraduate_inventory = _inventory_from_banks(_postgraduate_banks(sample_postgraduate_context))
    postgraduate_inventory["major_bank_count"] = _preset_bank_count(InterviewType.POSTGRADUATE)
    capability_inventory = capability_card_inventory()
    return {
        "job": job_inventory,
        "postgraduate": postgraduate_inventory,
        "civil_service": _inventory_from_banks(_civil_service_banks()),
        "ielts": _ielts_inventory(),
        "capability_cards": capability_inventory,
    }


def postgraduate_school_tier(target_school: str | None) -> str:
    normalized = _normalize_school_name(target_school or "")
    if not normalized:
        return "standard"

    config = _school_tier_config()
    for tier in config.get("tiers", []):
        tier_id = str(tier.get("id", "standard"))
        if tier_id == "standard":
            continue
        for keyword in tier.get("keywords", []):
            if _normalize_school_name(str(keyword)) in normalized:
                return tier_id
        for school in tier.get("schools", []):
            normalized_school = _normalize_school_name(str(school))
            if not normalized_school:
                continue
            if normalized_school in normalized or (len(normalized) >= 5 and normalized in normalized_school):
                return tier_id
    return "standard"


def _postgraduate_tier_label(tier_id: str) -> str:
    for tier in _school_tier_config().get("tiers", []):
        if str(tier.get("id")) == tier_id:
            return str(tier.get("label") or "标准复试")
    return "标准复试"


@lru_cache(maxsize=1)
def _school_tier_config() -> dict[str, Any]:
    if not SCHOOL_TIER_CONFIG_FILE.exists():
        return {"tiers": [{"id": "standard", "label": "标准复试", "difficulty_score": 1, "schools": []}]}
    return json.loads(SCHOOL_TIER_CONFIG_FILE.read_text(encoding="utf-8"))


def _job_banks(material_context: InterviewMaterialContext | None) -> list[QuestionRoundBank]:
    job_title = material_context.job_title if material_context else None
    job_requirements = material_context.job_requirements if material_context else None
    material_keywords = "、".join((material_context.keywords or [])[:4]) if material_context else ""
    preset_title, question_angles, _ = best_preset_hint(InterviewType.JOB, material_context, "专业一面")
    role_label = preset_title or job_title or "目标岗位"
    material_hint = material_keywords or _compact_hint(job_requirements, "岗位匹配度、项目证据和问题定位")
    angle_hint = _merge_focus_terms("、".join(question_angles[:4]), material_hint, fallback=material_hint)
    title = job_title or role_label
    project_hint = _project_context_hint(material_context)
    banks = [
        (
            "岗位理解",
            "easy",
            [
                f"你正在面试「{title}」。请用 90 秒说明你对这个岗位核心职责、业务目标和候选人能力要求的理解。",
                f"请把「{title}」拆成三类能力要求：业务理解、专业技能和协作方式，并说明你目前最匹配哪一类。",
                f"如果面试官问你为什么适合「{title}」，请先不讲口号，直接用岗位职责和你的经历建立对应关系。",
                f"请结合「{role_label}」的真实工作场景，说明这个岗位最容易被忽视的一项要求是什么。",
                f"请从用户、业务和团队三个角度说明「{title}」岗位每天真正要解决的问题。",
                f"如果你只能用一个关键词概括自己和「{title}」的匹配点，你会选什么，并用一个经历支撑？",
            ],
        ),
        (
            "项目证据",
            "easy",
            [
                f"请选择一个最能证明你胜任「{role_label}」的项目，按背景、任务、行动、结果讲清楚你的职责边界。",
                "请讲一个你亲自推进过的项目，不只讲团队成果，要明确你的决策、执行动作和可验证结果。",
                "请用 STAR 结构介绍一次你从零到一完成任务的经历，重点说明你如何定义目标和验收标准。",
                "请讲一个你参与度最高的项目，并说明如果删掉你的工作，项目会在哪些地方受影响。",
                f"围绕「{angle_hint}」，请选一个具体经历说明你如何把需求转成可交付方案。",
                "请介绍一次你需要快速学习新工具或新业务后完成交付的经历，说明学习路径和结果。",
            ],
        ),
        (
            "方案取舍",
            "medium",
            [
                "请展开一个关键技术或业务取舍：你比较过哪些方案，为什么最终这样选，放弃了什么？",
                "请讲一次你在资源有限时做优先级排序的经历，说明判断标准、沟通对象和最终效果。",
                "如果你当时有两种方案都能解决问题，请说明你如何评估风险、成本、可维护性和上线节奏。",
                "请回忆一次你改变原计划的经历：触发信号是什么，你如何验证新方案更合适？",
                "请讲一个你没有选择“最炫技方案”的场景，说明你如何说服团队接受更稳的方案。",
                "如果面试官要求你把这个方案迁移到更复杂场景，你会保留什么、重写什么、先验证什么？",
            ],
        ),
        (
            "指标复盘",
            "medium",
            [
                "如果面试官要求你用量化结果证明贡献，你会拿哪些指标、日志、用户反馈或业务数据？",
                "请讲一次项目上线后你如何做复盘：看了哪些数据，发现了什么问题，下一版怎么改。",
                "请说明你如何判断一个交付结果是真的有效，而不是刚好赶上业务或外部环境变化。",
                "如果项目没有明显增长指标，你会如何用质量、效率、风险降低或用户体验证明价值？",
                "请讲一次结果不符合预期的经历，说明你如何定位原因并修正后续方案。",
                "请用一个项目说明你如何把模糊的“做得更好”转化成可跟踪的指标。",
            ],
        ),
        (
            "根因定位",
            "hard",
            [
                "请解释一个复杂问题的根因定位过程：最初现象是什么，你如何排除干扰项，最后证据链是什么？",
                "请讲一次线上、流程或交付事故的处理过程，重点说明你如何先止损、再定位、最后复盘。",
                "如果同一问题在不同用户或不同环境下偶发出现，你会如何设计排查路径？",
                "请讲一次你发现团队最初判断方向错误的经历，你如何提出证据并推动重新分析？",
                "请说明你如何区分表面问题、流程问题和系统性问题，并给出你做过的一次处理案例。",
                "如果问题根因涉及跨团队协作，你会如何拿到必要信息并避免互相甩锅？",
            ],
        ),
        (
            "压力追问",
            "hard",
            [
                "如果面试官质疑这是团队成果而不是你的贡献，你会拿哪些证据说明个人行动和不可替代性？",
                "如果面试官认为你的方案成本过高或风险过大，你会如何回应并给出可执行备选方案？",
                "如果业务方临时改变目标，你会如何重新确认需求、调整排期并保护核心质量？",
                "如果你负责的模块拖慢整体进度，你会如何向上同步风险并组织补救？",
                "如果上线后用户反馈与你的预期相反，你会如何承认问题、复盘假设并推进修正？",
                "如果团队成员不同意你的判断，你会如何用数据、实验或小范围验证推动共识？",
            ],
        ),
        (
            "协作与动机",
            "expert",
            [
                f"你为什么选择「{title}」？请结合岗位职责、个人经历和长期目标说明匹配点。",
                "请说明一次高压协作经历：你如何沟通预期、处理冲突并保证交付？",
                "如果直属负责人给出和你判断相反的方向，你会如何验证事实、表达不同意见并推进共识？",
                "请讲一次你主动补位但不抢功的经历，说明你如何处理团队边界。",
                "如果入职后发现业务节奏和预期不同，你会如何保持产出、沟通预期并持续学习？",
                "请说明你希望在这个岗位前 90 天达成什么结果，以及你会如何验证自己进入状态。",
            ],
        ),
        (
            "终面收束",
            "expert",
            [
                "如果终面只给你 2 分钟，请用岗位匹配、项目证据、学习能力和稳定性完成一次收束陈述。",
                "请说明你最可能被面试官担心的短板是什么，你准备如何降低这个风险。",
                "如果同批候选人都有类似项目经历，你希望面试官记住你的哪个差异化证据？",
                "请从业务贡献、工程质量和团队协作三个维度总结你能带来的价值。",
                "如果拿到 offer 后进入试用期，你会如何制定前 30 天、60 天、90 天计划？",
                "请用一次失败经历收尾：你学到了什么，下一次你会如何提前预防？",
            ],
        ),
    ]
    banks = _extend_job_banks(banks, title, role_label)
    banks = _extend_role_specific_job_banks(banks, title, role_label, angle_hint)
    if project_hint:
        banks = _anchor_job_project_banks(banks, title, role_label, angle_hint, project_hint)
    banks = _apply_job_domain_language(banks, title, role_label, angle_hint)
    return _contextualize_job_banks(banks, role_label, angle_hint)


def _postgraduate_banks(material_context: InterviewMaterialContext | None) -> list[QuestionRoundBank]:
    target_school = material_context.target_school if material_context and material_context.target_school else "目标院校"
    major = material_context.major if material_context and material_context.major else "报考专业"
    direction = material_context.research_direction if material_context and material_context.research_direction else "你的研究兴趣"
    preset_title, question_angles, _ = best_preset_hint(InterviewType.POSTGRADUATE, material_context, "专业基础")
    major_label = preset_title or major
    angle_hint = "、".join(question_angles[:4]) if question_angles else direction
    tier = postgraduate_school_tier(target_school)
    tier_label = _postgraduate_tier_label(tier)
    discipline_focus = _postgraduate_discipline_focus(major, direction, major_label, angle_hint)
    return _postgraduate_banks_by_tier(tier, tier_label, target_school, major_label, direction, angle_hint, discipline_focus)


def _postgraduate_banks_by_tier(
    tier: str,
    tier_label: str,
    target_school: str,
    major_label: str,
    direction: str,
    angle_hint: str,
    discipline_focus: str,
) -> list[QuestionRoundBank]:
    pressure = {
        "elite": "问题意识、方法边界、文献差异和可验证研究假设",
        "high": "专业基础、项目证据、文献理解和研究计划",
        "advanced": "课程基础、项目经历、方向理解和表达稳定性",
        "standard": "基础概念、报考动机、学习计划和项目表达",
    }.get(tier, "基础概念、报考动机、学习计划和项目表达")
    banks = [
        (
            "复试开场",
            "easy",
            [
                f"你正在准备「{target_school}」{tier_label}。请用 90 秒自我介绍，围绕「{major_label}」讲清本科基础、报考动机和一个最有说服力的证据。",
                f"请做一段「{target_school}」{tier_label}开场陈述，必须覆盖专业背景、关键项目、未来方向和你目前最明确的短板。",
                f"请把你的复试开场压缩到三层：为什么是「{major_label}」、为什么是「{target_school}」、为什么现在的你具备继续深造基础。",
                f"如果「{target_school}」{tier_label}老师只听你 60 秒，请用一段话证明你不是泛泛报考，而是围绕「{direction}」做过准备。",
                f"请做一段更像「{target_school}」{tier_label}现场的自我介绍，不罗列履历，而是把课程、项目和「{major_label}」连接起来。",
                f"请用「过去准备、现在能力、未来计划」三个部分完成「{target_school}」{tier_label}自我介绍，并体现{pressure}。",
            ],
        ),
        (
            "专业基础",
            "easy",
            [
                f"请从「{major_label}」里选一个基础概念，解释定义、适用条件、常见误区，并说明它如何支撑「{direction}」。",
                f"围绕「{angle_hint}」，请讲清一门本科核心课中你掌握最扎实的知识点，并举一个项目或实验例子。",
                f"如果老师追问一个基础概念的边界条件，你会如何从定义、例子、反例三个层次回答？",
                f"请说明你在「{major_label}」方向最薄弱的一块基础是什么，你准备如何在入学前补齐。",
                f"请选一个你能讲明白的理论或方法，说明它解决什么问题、不适合什么问题、为什么重要。",
                f"如果复试老师要求你现场解释一个专业术语，你会如何避免背定义，转而讲清直觉和应用？",
            ],
        ),
        (
            "项目与科研潜力",
            "medium",
            [
                "请把一个课程项目、毕业设计或竞赛经历改写成研究问题：变量是什么、数据从哪里来、如何验证？",
                "请介绍一个你亲自负责的项目环节，重点说明问题来源、方法选择、结果可信度和不足。",
                "如果老师要求你把现有项目提升为研究工作，你会补哪些实验、对照组、误差分析或消融分析？",
                "请讲一次你遇到技术或实验瓶颈的经历，说明你如何查资料、设计验证并调整方向。",
                "如果你的项目结果并不显著，你会如何解释原因，并提出下一步可做的改进？",
                f"请围绕「{direction}」提出一个你能在三个月内启动的小研究问题，并说明验证路径。",
            ],
        ),
        (
            "文献与英文",
            "medium",
            [
                "如果导师追问你两篇相关文献差异、贡献和局限，你会如何比较并提出自己的切入点？",
                "请说明你读一篇英文论文时如何判断问题是否重要、方法是否可靠、实验是否充分。",
                "如果老师让你 10 分钟内读完两篇论文摘要和实验部分，请说明你如何提炼文献差异并形成汇报结构。",
                "请讲一次你读文献或技术资料的经历，重点说明你如何识别文献差异，以及它如何改变了你的项目或研究想法。",
                "如果两篇文献结论冲突，你会如何从数据、方法、样本和评价指标上判断差异来源？",
                "请用一篇你了解的文献说明：它和同方向工作的文献差异是什么，还留下了什么可以继续做的空白。",
            ],
        ),
        (
            "导师方向适配",
            "hard",
            [
                f"请把「{direction}」和「{target_school}」可能的导师方向连接起来，提出一个第一学期可验证的小课题计划。",
                "如果导师课题组方向与你原方向不完全一致，请说明你如何补基础、找切入点并尽快产出阶段性结果。",
                "如果老师追问你为什么不是换个学校也可以，请从平台资源、导师方向和个人准备回答。",
                f"请说明你选择「{target_school}」时做过哪些调研，哪些信息和「{major_label}」培养目标直接相关。",
                "如果导师要求你进入一个陌生方向，你会如何用 4 周时间建立最低可用的知识框架？",
                "请提出一个你入学后希望和导师确认的问题，并说明这个问题为什么会影响你的研究计划。",
            ],
        ),
        (
            "学术规范与压力",
            "hard",
            [
                "如果实验结果和预期相反，请说明你如何排查数据、复现实验、记录负结果并汇报风险。",
                "如果同组成员建议删掉不利实验结果，你会如何坚持学术规范并推动团队复盘？",
                "如果复试老师质疑你项目中的数据可靠性，你会如何说明采集、清洗、验证和记录过程？",
                "如果你的研究计划三个月没有进展，你会如何向导师汇报、调整问题并保留有效证据？",
                "如果老师指出你的回答像项目汇报而不是科研思考，你会如何补充问题意识和方法边界？",
                "如果复试现场被追问到不会的问题，你会如何承认边界、给出推理路径并说明后续学习计划？",
            ],
        ),
    ]
    banks = _extend_postgraduate_banks(banks, tier_label, target_school, major_label, direction)
    banks = _extend_postgraduate_discipline_banks(banks, major_label, direction, discipline_focus)
    banks = _apply_postgraduate_discipline_language(banks, discipline_focus)
    return _contextualize_postgraduate_banks(banks, major_label, discipline_focus)


def _civil_service_banks() -> list[QuestionRoundBank]:
    banks = [
        (
            "岗位认知",
            "easy",
            [
                "请结合基层群众服务场景，说明你如何理解公务员岗位的服务意识、纪律意识和长期稳定投入。",
                "如果未来在基层窗口岗位长期面对群众咨询和重复性事务，你会如何保持耐心、规范和责任感？",
                "请谈谈你为什么选择公共服务岗位，并用一段经历证明你能在基层群众工作中承担压力。",
                "有人说基层工作琐碎但重要，请结合群众获得感说明你对这句话的理解。",
                "如果被分配到离预期较远的基层岗位，你会如何调整心态并尽快熟悉群众需求？",
                "请结合一个真实或模拟经历，说明你如何在规则边界内帮助群众解决问题。",
            ],
        ),
        (
            "综合分析",
            "medium",
            [
                "某地上线基层政务服务数字化平台后，群众办事更方便，但老年群体使用困难。请从意义、问题和对策谈理解。",
                "有观点认为基层治理既要追求效率，也要保留面对面服务温度。请结合群众获得感谈谈看法。",
                "围绕基层减负和服务群众，有人说“数据多跑路，干部少填表”。请从现实意义、风险和落实路径分析。",
                "某地推行社区积分激励志愿服务，有人支持也有人担心形式主义。请谈谈你的理解。",
                "对于政务公开，有群众认为信息越多越好，也有人担心看不懂、用不上。你怎么看？",
                "面对网络舆论中的基层治理争议，你认为政府部门应如何做到依法回应、及时沟通和持续改进？",
            ],
        ),
        (
            "计划组织",
            "medium",
            [
                "如果让你组织一次面向社区老年人的医保政策宣讲和现场答疑，你会如何确定对象、资源、流程和应急预案？",
                "单位准备开展基层政务服务满意度调研，你负责统筹执行，请说明调研对象、方式、分工和结果运用。",
                "如果让你组织一次窗口服务规范培训，请说明前期摸底、课程安排、现场管理和效果评估。",
                "领导安排你牵头开展一次政策进校园活动，你会如何协调学校、资料、人员分工和现场秩序？",
                "如果要对辖区困难群众开展走访摸排，你会如何保证信息真实、过程合规和后续帮扶闭环？",
                "单位要做一次线上线下结合的便民服务宣传，你会如何设计渠道、话术、时间节点和复盘指标？",
            ],
        ),
        (
            "人际沟通",
            "hard",
            [
                "如果同事习惯按老办法处理业务，但新政策要求已经调整，你提醒后对方不太接受，你会如何沟通？",
                "领导临时安排你协助其他科室，导致原本任务可能延期，你会如何协调优先级并汇报进度？",
                "群众认为你解释政策是在推诿，态度比较急躁，你会如何倾听、解释并守住原则边界？",
                "如果新同事多次在材料中出现同类错误，影响科室效率，你会如何帮助而不是简单指责？",
                "如果你和同事对一项工作口径理解不一致，且群众正在等待办理，你会如何先处理现场再统一口径？",
                "如果领导批评你工作考虑不周，但你认为还有客观原因，你会如何回应并改进？",
            ],
        ),
        (
            "应急处置",
            "hard",
            [
                "办事大厅系统临时故障，群众排队时间较长并出现情绪激动，你作为现场工作人员会如何处理？",
                "政策宣讲现场有人拍视频质疑工作人员解释不一致，引发围观讨论，你会如何稳控现场并核实回应？",
                "你负责的材料发放环节出现漏发，影响部分群众办理业务，你会如何补救并防止再次发生？",
                "突发暴雨导致原定群众活动无法按计划进行，现场已有人员到达，你会如何调整安排？",
                "窗口出现群众因材料不全反复往返而投诉，你会如何依法依规处理并优化提醒机制？",
                "如果舆情已经在网上发酵，而事实还未完全核实，你会如何把握回应节奏、上报流程和后续处置？",
            ],
        ),
        (
            "现场追问收束",
            "expert",
            [
                "追问：如果考官认为你的措施太笼统，请你用对象、流程、责任人、时限和反馈闭环重新收束这道题。",
                "追问：如果现场条件突然变化，你会保留哪些底线步骤，哪些安排可以调整，为什么？",
                "追问：请用 60 秒总结这道题的核心判断、政策依据、群众立场和最终落地路径。",
                "追问：如果群众仍不理解甚至继续投诉，你会如何在依法依规和情绪疏导之间把握尺度？",
                "追问：如果领导问你这件事最大的风险点是什么，你会如何回答并提出兜底方案？",
                "追问：如果同事认为你的处理方式增加工作量，你会如何解释必要性并争取协同？",
                "追问：请指出你刚才方案中最容易被忽视的一个群体，并说明如何补救。",
                "追问：如果媒体或群众要求马上给结论，但事实还没核清，你会如何回应才稳妥？",
                "追问：请把你的回答压缩成考场版三句话，分别对应怎么看、怎么办、如何防止复发。",
                "追问：如果要形成书面复盘，你会记录哪些证据、节点、责任和后续改进事项？",
                "追问：如果政策本身存在理解门槛，你会如何把专业口径转成群众听得懂的话？",
                "追问：如果这件事牵涉多个部门，你会如何确认牵头单位、协同机制和闭环标准？",
                "追问：请说明你的方案如何体现公平性，避免只解决现场最激烈的诉求。",
                "追问：如果考官最后只看执行可行性，你认为你方案里最可信的一步是什么？",
            ],
        ),
    ]
    return _extend_civil_service_banks(banks)


def _extend_job_banks(banks: list[QuestionRoundBank], title: str, role_label: str) -> list[QuestionRoundBank]:
    extras = {
        "岗位理解": [
            f"请说明「{title}」岗位在公司业务链路里的位置：它向谁交付价值，依赖谁协作，关键风险在哪里？",
            f"如果面试官问你对「{title}」的理解是否来自真实岗位而不是招聘文案，你会如何用 JD、产品或业务事实回答？",
            f"请把「{role_label}」拆成入门、合格、优秀三个层级，并说明你现在大概处在哪一层、证据是什么。",
            f"请说明「{title}」岗位最容易被新人误判的一项工作内容，以及你会如何提前适应。",
        ],
        "项目证据": [
            "请讲一个你从需求不清晰到结果交付的完整经历，重点说明你如何确认目标、拆解任务和收敛范围。",
            "请选择一个能体现学习速度的项目，说明你原本不会什么、如何补齐、最终交付是否经得起验证。",
            "请讲一个你处理多方需求冲突的项目，说明你如何识别真正优先级并推动相关方达成一致。",
            "请选一个你最愿意被深入追问的项目，先说明项目背景，再说明你准备接受追问的三个关键细节。",
        ],
        "方案取舍": [
            "请讲一次你在短期收益和长期可维护性之间做选择的经历，说明你如何评估代价。",
            "如果你的方案需要牺牲一部分体验、成本或进度，请说明你如何向团队解释取舍并设置止损线。",
            "请回忆一次你主动否定自己原方案的经历，说明证据是什么、你如何调整并复盘。",
            "如果面试官要求你把方案讲给非技术或非专业同事听，你会如何解释核心逻辑和风险？",
        ],
        "指标复盘": [
            "请讲一次你在项目开始前就设定评估指标的经历，说明指标为什么能反映真实价值。",
            "如果项目结果看起来不错，但样本量很小或周期很短，你会如何判断结论是否可靠？",
            "请说明你如何区分过程指标、结果指标和风险指标，并用一个真实经历举例。",
            "如果复盘发现最大问题不是技术能力而是协作流程，你会如何提出改进并验证有效性？",
        ],
        "根因定位": [
            "请讲一次你通过日志、数据、访谈或复现实验定位问题的经历，说明每一步排除了什么假设。",
            "如果问题只能在特定用户、特定时间或特定环境出现，你会如何设计最小复现路径？",
            "请说明一次你发现根因不在自己负责模块的经历，你如何继续推动问题闭环而不是停止在甩锅阶段。",
            "如果业务方只看到表面现象并要求立刻修复，你会如何在速度和根因分析之间取得平衡？",
        ],
        "压力追问": [
            "如果面试官指出你的回答缺少业务结果，你会如何立刻补充可验证证据？",
            "如果面试官认为你的项目难度不够，你会如何说明真正的复杂性来自哪里？",
            "如果面试官连续追问你没有准备过的细节，你会如何承认边界并保持回答可信？",
            "如果你发现自己前一轮回答有遗漏或表述不准，你会如何在后续回答中修正而不显得慌乱？",
        ],
        "协作与动机": [
            "请讲一次你主动承担灰色地带工作的经历，说明你如何避免越界并推动事情完成。",
            "如果团队节奏很快且反馈直接，你会如何保持稳定输出并主动校准预期？",
            f"请说明你选择「{title}」的长期动机，除了兴趣之外，还要讲清你愿意承受的成本。",
            "请讲一次你接受负面反馈后的调整过程，说明你具体改了什么、结果有没有变好。",
        ],
        "终面收束": [
            "请用 2 分钟完成一次终面总结：你能解决什么问题、凭什么相信你、入职后如何快速产生价值。",
            "如果面试官最后只问你还有什么要补充，你会补哪一个最能提升录用信心的证据？",
            "请说明你希望面试官在候选人评审会上如何概括你，并给出支撑这个概括的证据。",
            "如果你没有拿到这次机会，你认为最可能的原因是什么，你会如何改进下一场面试？",
        ],
    }
    return _append_questions(banks, extras)


def _extend_role_specific_job_banks(
    banks: list[QuestionRoundBank],
    title: str,
    role_label: str,
    angle_hint: str,
) -> list[QuestionRoundBank]:
    role_focus = _compact_hint(angle_hint, "岗位核心能力、真实交付证据和业务结果", max_chars=110)
    extras = {
        "岗位理解": [
            f"请按「{role_label}」面试标准说明这个岗位的关键交付物是什么，并把交付物和{role_focus}逐项对应。",
            f"如果你入职「{title}」第一周就要接手真实任务，你会先确认哪些业务边界、技术边界或协作边界？",
            f"请选出「{role_label}」最容易被简历包装掩盖的一项能力，说明面试官应该如何验证。",
            f"请从初级、独立负责、核心骨干三个层级说明「{title}」能力要求的差异。",
        ],
        "项目证据": [
            f"请用一个真实项目证明你具备「{role_label}」能力，回答必须落到{role_focus}中的具体动作和结果。",
            f"围绕「{title}」岗位，请说明你做过的项目里哪一段最能经得起专业追问，为什么？",
            f"请把一个项目拆成「{role_label}」视角下的输入、处理、输出、验收标准和复盘结论。",
            f"如果面试官只追问{role_focus}，你准备用哪个项目作为主证据？请先讲清项目边界。",
        ],
        "方案取舍": [
            f"围绕{role_focus}，请讲一次你做方案取舍的经历：比较项、决策依据、放弃项和后果分别是什么？",
            f"如果「{role_label}」场景下短期上线和长期质量冲突，你会如何定优先级并向团队解释？",
            f"请说明一次你为了「{title}」相关目标调整方案的经历，重点讲验证方法而不是只讲结论。",
            f"如果面试官要求你现场重做方案，请按{role_focus}给出一个更稳的备选路径。",
        ],
        "指标复盘": [
            f"请说明「{role_label}」项目里哪些指标最能证明真实贡献，并解释这些指标为什么不是虚荣指标。",
            f"围绕{role_focus}，请讲一次你如何设计验收指标、观察结果并推动下一轮改进。",
            f"如果你负责的「{title}」任务没有直接增长数据，你会用哪些质量、效率或风险指标证明价值？",
            f"请说明一次你通过复盘发现「{role_label}」能力短板的经历，以及下一次如何修正。",
        ],
        "根因定位": [
            f"请讲一次和{role_focus}相关的复杂问题定位：现象、假设、验证、证据链和最终修复分别是什么？",
            f"如果「{title}」场景里的问题跨越业务、技术和协作边界，你会如何拆分责任和排查路径？",
            f"请说明一次你没有停在表面现象，而是找到「{role_label}」深层原因的经历。",
            f"如果面试官质疑你只是执行别人安排，请用一次根因定位过程证明你的独立判断。",
        ],
        "压力追问": [
            f"如果面试官指出你的经历和「{role_label}」核心要求不完全匹配，你会用哪条证据补齐？",
            f"如果对方围绕{role_focus}连续追问细节，你最可能被问倒的一点是什么，你准备如何回答？",
            f"如果你的项目成果主要依赖团队资源，你如何证明自己在「{title}」能力上不可替代？",
            f"如果入职后第一个月就遇到「{role_label}」高压交付，你会如何控制风险并同步预期？",
        ],
        "协作与动机": [
            f"请说明你为什么选择「{title}」而不是相邻岗位，回答要落到{role_focus}和长期成长路径。",
            f"请讲一次你在「{role_label}」相关任务里和非同专业同事协作的经历，说明如何减少误解。",
            f"如果团队对{role_focus}的判断与你不同，你会如何提出证据、推进讨论并接受结果？",
            f"请说明你希望在「{title}」岗位前 90 天拿到什么可验证结果。",
        ],
        "终面收束": [
            f"请用 2 分钟总结你和「{role_label}」的匹配度：岗位理解、项目证据、{role_focus}和风险补齐。",
            f"如果终面官只记住一个「{title}」相关证据，你希望是哪一个，为什么它足够可信？",
            f"请说明你相对同岗位候选人的差异化，不讲性格标签，只讲{role_focus}里的证据。",
            f"如果这次面试失败，你认为「{role_label}」哪项能力最需要补，下一周会怎么练？",
        ],
    }
    return _append_questions(banks, extras)


def _anchor_job_project_banks(
    banks: list[QuestionRoundBank],
    title: str,
    role_label: str,
    angle_hint: str,
    project_hint: str,
) -> list[QuestionRoundBank]:
    role_focus = _compact_hint(angle_hint, "岗位核心能力、业务结果和专业细节", max_chars=96)
    project_questions = {
        "项目证据": [
            f"你的材料里提到「{project_hint}」。请按背景、目标、你的职责、关键动作、结果指标讲清楚这个项目。",
            f"请围绕「{project_hint}」说明你个人最核心的贡献，不要讲团队概述，要讲你亲自做了什么。",
            f"如果面试官要验证「{project_hint}」是否真实，请你先给出项目时间线、上下游角色和验收标准。",
            f"请把「{project_hint}」拆成业务问题、技术或专业方案、协作对象和最终结果四部分。",
            f"围绕「{title}」岗位，请说明「{project_hint}」中最能证明{role_focus}的一段经历。",
            f"如果删掉你在「{project_hint}」里的工作，项目会在哪些环节受影响？请给出可验证细节。",
            f"请讲「{project_hint}」里一个你曾经判断失误或返工的点，以及你后面如何修正。",
            f"请说明「{project_hint}」里你掌握得最扎实、也最愿意被追问的三个细节。",
            f"如果面试官只给 90 秒，请用「问题-行动-结果」讲完「{project_hint}」。",
            f"请把「{project_hint}」和「{role_label}」岗位要求逐项对应，指出最强证据和最弱证据。",
        ],
        "方案取舍": [
            f"在「{project_hint}」里，你做过哪一个关键取舍？请说明备选方案、选择理由、风险和结果。",
            f"请围绕「{project_hint}」讲一次你没有选择更复杂方案的经历，为什么它对「{title}」更合适？",
            f"如果重新做「{project_hint}」，你会保留什么、重做什么、先验证什么？",
            f"请说明「{project_hint}」中一次需求、成本、进度或质量之间的冲突，你如何权衡。",
            f"面试官如果质疑「{project_hint}」方案不够专业，你会用哪些证据解释当时的约束？",
            f"围绕{role_focus}，请说明「{project_hint}」里最难的一次方案比较。",
            f"如果「{project_hint}」要放大到更高并发、更大用户量或更复杂业务，你会先改哪部分？",
            f"请讲「{project_hint}」里你和团队意见不一致的一次技术或业务选择，最后如何达成一致。",
            f"如果「{project_hint}」里有一次临时变更，你如何判断是否接受变更并保护核心质量？",
            f"请把「{project_hint}」的一个方案选择讲给非专业同事听，你会如何表达取舍逻辑？",
        ],
        "指标复盘": [
            f"请说明「{project_hint}」最终用哪些指标验收，例如质量、效率、延迟、转化、准确率或用户反馈。",
            f"如果「{project_hint}」结果看起来变好了，你如何排除偶然因素并证明是你的方案产生作用？",
            f"请讲「{project_hint}」上线或交付后的复盘：数据怎么看，问题怎么定位，下一版怎么改。",
            f"围绕「{role_label}」能力，请说明「{project_hint}」里最能证明你贡献的一个量化结果。",
            f"如果「{project_hint}」没有明确指标，你会如何补设计一套复盘指标？",
            f"请说明「{project_hint}」里一个结果不符合预期的指标，以及你如何解释原因。",
            f"如果面试官追问「{project_hint}」的投入产出比，你会如何用事实回答？",
            f"请说明你如何从「{project_hint}」里沉淀出可复用方法，而不是只完成一次交付。",
            f"围绕{role_focus}，请说明「{project_hint}」的过程指标和结果指标分别是什么。",
            f"如果要把「{project_hint}」写进简历，你会保留哪三个最可信的指标？",
        ],
        "根因定位": [
            f"请讲「{project_hint}」里一次真实问题定位过程：现象、假设、排查顺序、证据链和修复结果。",
            f"如果「{project_hint}」出现偶发问题，你会如何设计复现路径并避免误判根因？",
            f"请说明「{project_hint}」中最容易被忽视的风险点，你当时如何发现或补救。",
            f"如果面试官认为「{project_hint}」的问题只是执行细节，你如何说明背后的系统性原因？",
            f"请围绕{role_focus}，讲一次你在「{project_hint}」里从数据、日志、访谈或实验中找到证据。",
            f"如果「{project_hint}」的根因涉及跨团队协作，你如何拿到信息并推动闭环？",
            f"请讲一次「{project_hint}」里你最初判断错误的排查方向，以及后来如何纠正。",
            f"如果「{project_hint}」上线后用户反馈异常，你会如何先止损、再定位、最后复盘？",
            f"请说明「{project_hint}」里一个你现在仍然觉得可以优化的风险点。",
            f"如果让你指导新人接手「{project_hint}」的问题排查，你会给出怎样的检查清单？",
        ],
    }
    return _replace_questions(banks, project_questions)


def _contextualize_job_banks(
    banks: list[QuestionRoundBank],
    role_label: str,
    angle_hint: str,
) -> list[QuestionRoundBank]:
    role_focus = _compact_hint(angle_hint, "岗位核心能力、项目证据和业务结果", max_chars=120)
    suffix = f"回答时请按「{role_label}」真实面试口径展开，至少落到{role_focus}中的一个具体点。"
    return _append_suffix_to_questions(banks, suffix)


def _extend_postgraduate_banks(
    banks: list[QuestionRoundBank],
    tier_label: str,
    target_school: str,
    major_label: str,
    direction: str,
) -> list[QuestionRoundBank]:
    extras = {
        "复试开场": [
            f"请用一段更正式的复试开场说明你为什么选择「{target_school}」{tier_label}，并把动机落到课程、项目或文献准备上。",
            f"如果「{target_school}」{tier_label}老师追问你报考「{major_label}」是不是临时决定，你会如何用过去经历证明这是连续选择？",
            f"请把你的本科经历筛选成 3 个和「{target_school}」{tier_label}最相关的证据，并说明每个证据对应哪项能力。",
            f"请用 90 秒说明你对「{direction}」的理解，不讲空泛兴趣，并对齐「{target_school}」{tier_label}需要的准备深度。",
        ],
        "专业基础": [
            f"请从「{major_label}」选择一个你最熟悉的基础知识点，分别用定义、应用场景和局限性解释。",
            "如果老师要求你现场推导或口头解释一个本科核心概念，你会如何组织语言避免只背书？",
            f"请说明「{major_label}」中一个你曾经理解错误的概念，以及你后来如何纠正。",
            "如果复试老师把基础题和项目经历连起来追问，你会如何从理论、实现和结果三层回答？",
        ],
        "项目与科研潜力": [
            "请把你的一个项目拆成研究价值、技术路线、实验验证和不足四部分进行说明。",
            "如果老师问这个项目是否只是工程实现，你会如何说明其中的分析、假设或可研究问题？",
            "请提出一个和你现有项目相关但更适合研究生阶段深入的问题，并说明为什么值得做。",
            "如果你的项目没有论文或竞赛奖项，你会如何证明它仍然能体现科研潜力？",
        ],
        "文献与英文": [
            f"围绕「{direction}」，请说明你会如何建立一个 10 篇文献的阅读清单，并按主题归类。",
            "如果老师让你比较综述论文和实验论文的价值，你会如何判断各自对研究计划的帮助？",
            "请讲一篇你真正读过的英文资料，说明你抓住了什么问题、方法和不足。",
            "如果英文论文里的方法看懂了但实验细节不清楚，你会如何继续查证而不是模糊带过？",
        ],
        "导师方向适配": [
            f"请说明你对「{target_school}」相关培养平台或导师方向做过哪些调研，哪些信息影响了你的选择。",
            f"如果导师问你为什么适合当前课题组，请用「{major_label}」基础、项目经验和学习计划回答。",
            "如果入学后导师安排的方向与你原计划不同，你会如何判断是否接受并制定补课计划？",
            "请给出一个第一学期可以完成的阶段性成果设想，并说明它如何服务后续研究。",
        ],
        "学术规范与压力": [
            "如果复试老师指出你项目数据来源不够严谨，你会如何补充说明并承认边界？",
            "如果你在研究中发现已有假设不成立，你会如何记录过程、调整问题并向导师汇报？",
            "请说明你如何看待负结果、重复实验和数据留痕，它们为什么也是科研训练的一部分？",
            "如果复试现场被追问到完全不会的问题，请给出一个可信的回应框架，而不是直接沉默或乱猜。",
        ],
    }
    return _append_questions(banks, extras)


def _extend_postgraduate_discipline_banks(
    banks: list[QuestionRoundBank],
    major_label: str,
    direction: str,
    discipline_focus: str,
) -> list[QuestionRoundBank]:
    extras = {
        "复试开场": [
            f"请用「{major_label}」专业语言重写自我介绍，说明你过去准备和{discipline_focus}之间的关系。",
            f"如果老师要求你证明不是跨专业泛泛报考，请用课程、项目或阅读经历连接{discipline_focus}。",
            f"请说明你对「{direction}」的兴趣来自哪个具体问题，并解释它和{discipline_focus}的关系。",
            f"请用一段复试开场说明你目前最扎实和最薄弱的专业模块，专业模块必须落到{discipline_focus}。",
            f"如果老师要求你说明本科训练和「{major_label}」研究生学习之间的连续性，你会如何用{discipline_focus}证明？",
            f"请把你的复试开场改成「事实证据-专业判断-后续计划」三段，并让每段都能对应{discipline_focus}。",
        ],
        "专业基础": [
            f"请围绕{discipline_focus}选择一个基础概念，讲清定义、边界、典型应用和常见误区。",
            f"如果老师从{discipline_focus}里抽一个口头推导或案例分析题，你会如何分层作答？",
            f"请把「{major_label}」的一门核心课和「{direction}」连接起来，说明能支撑哪类研究问题。",
            f"请说明{discipline_focus}中你最容易答浅的一块，并给出入学前补基础的计划。",
            f"如果老师要求你用例子解释{discipline_focus}，请给出一个你真实准备过的例子。",
            f"请从「概念、方法、评价」三个层次说明{discipline_focus}为什么是「{major_label}」复试重点。",
        ],
        "项目与科研潜力": [
            f"请把一个项目或课程作业改写成「{major_label}」研究问题，必须包含{discipline_focus}里的方法或变量。",
            f"围绕「{direction}」，请提出一个小课题，并说明如何用{discipline_focus}设计验证路径。",
            f"如果老师质疑你的项目只是应用实现，请从{discipline_focus}角度说明其中的研究价值。",
            f"请说明一个项目结果的可信度如何判断，回答要覆盖{discipline_focus}里的数据、方法或评价标准。",
            f"如果你的项目要继续做成研究生课题，请说明下一步最该补的实验、案例、样本或理论分析。",
            f"请讲一次你围绕{discipline_focus}查资料、做实验或分析案例后改变原判断的经历。",
        ],
        "文献与英文": [
            f"请说明你会如何围绕{discipline_focus}建立文献清单：检索词、筛选标准和阅读顺序是什么？",
            f"如果两篇文献都研究「{direction}」，请从问题定义、方法路径、评价标准和局限性比较。",
            f"请用「{major_label}」视角说明你读英文文献时最先看什么，如何判断它是否服务{discipline_focus}。",
            f"如果老师让你现场概括一篇相关英文论文，请用问题、方法、结果、局限四段式回答。",
            f"请说明你如何避免只背文献结论，而是把文献差异转化成自己的研究切入点。",
            f"如果文献结论和你的项目经验冲突，你会如何从数据、样本、方法或案例边界分析原因？",
        ],
        "导师方向适配": [
            f"请把「{direction}」和导师可能关注的{discipline_focus}连接起来，提出第一学期可验证的小计划。",
            f"如果导师方向偏离你原先准备，请说明你如何用{discipline_focus}建立最低可用知识框架。",
            f"请说明你调研目标院校时看过哪些和「{major_label}」培养、平台或课题组直接相关的信息。",
            f"如果老师追问你为什么适合这个课题组，请用{discipline_focus}、项目经历和学习计划作答。",
            f"请提出一个你想向导师确认的问题，并说明它为什么会影响「{direction}」的研究路线。",
            f"如果导师要求你承担基础性工作，你会如何把它和{discipline_focus}训练结合起来？",
        ],
        "学术规范与压力": [
            f"如果围绕{discipline_focus}得到的结果和预期相反，你会如何记录、复核并向导师汇报？",
            f"如果老师质疑你对{discipline_focus}的理解只停留在名词层面，你会如何补充推理过程？",
            f"请说明「{major_label}」研究中哪些数据、案例、引用或实验记录最容易出现规范风险。",
            f"如果同学建议只展示有利结果，你会如何坚持学术规范并说明负结果的价值？",
            f"如果复试现场被追问到{discipline_focus}中不会的细节，你会如何承认边界并给出学习路径？",
            f"请说明你如何判断一个「{direction}」研究计划是否过大、过空或不可验证。",
        ],
    }
    return _append_questions(banks, extras)


def _contextualize_postgraduate_banks(
    banks: list[QuestionRoundBank],
    major_label: str,
    discipline_focus: str,
) -> list[QuestionRoundBank]:
    suffix = f"回答时请按「{major_label}」正式复试标准展开，专业边界至少落到{discipline_focus}。"
    contextualized: list[QuestionRoundBank] = []
    for round_name, difficulty, questions in banks:
        round_suffix = suffix
        if round_name == "文献与英文":
            round_suffix = f"{suffix} 同时必须说明文献差异、贡献和局限。"
        contextualized.append((round_name, difficulty, [f"{question.strip()} {round_suffix}" for question in questions]))
    return contextualized


def _extend_civil_service_banks(banks: list[QuestionRoundBank]) -> list[QuestionRoundBank]:
    extras = {
        "岗位认知": [
            "请结合依法行政和群众路线，说明你认为基层公务员最重要的三项能力是什么。",
            "如果岗位长期需要处理细碎但关系群众切身利益的事务，你会如何保证工作标准不下降？",
            "请谈谈你如何理解组织纪律和主动服务之间的关系，避免只讲服从或只讲热情。",
            "如果你发现群众诉求合理但暂时不符合办理条件，你会如何解释、引导并记录后续可能性？",
            "如果你被安排到窗口一线岗位，请说明如何在办事效率、材料规范和群众体验之间取得平衡。",
            "请结合一个政策落地场景，说明基层干部为什么既要懂政策，也要会用群众听得懂的话表达。",
            "如果长期面对重复性工作，你会如何通过台账、复盘和流程优化保持责任心和稳定性？",
            "请说明你如何理解“权责边界”，在热情服务群众时不突破政策和纪律红线。",
        ],
        "综合分析": [
            "某地推行一网通办后，部分群众仍然习惯线下窗口。请从便利化、包容性和服务质量谈谈看法。",
            "有人认为基层干部应该多进社区少坐办公室，也有人担心影响正常业务办理。你怎么看？",
            "针对公共服务中的形式主义问题，请从制度设计、执行监督和群众评价三个角度分析。",
            "对于基层治理中使用人工智能工具辅助办理业务，你认为机会、风险和边界分别是什么？",
            "某地为提升办事效率取消部分纸质材料，但基层工作人员担心核验风险上升。你怎么看？",
            "有人说政务服务要追求速度，也有人说规范程序更重要。请结合依法行政谈谈你的理解。",
            "针对社区治理中居民参与度不高的问题，请从利益关联、表达渠道和反馈机制分析原因。",
            "某地开展文明城市创建时出现突击整治争议，请从长效治理、群众感受和考核导向谈看法。",
        ],
        "计划组织": [
            "如果让你组织一次面向新就业群体的政策服务活动，你会如何确定需求、场地、宣传和后续跟进？",
            "单位要开展一次矛盾纠纷风险排查，你会如何设计摸排范围、信息记录、协同部门和保密要求？",
            "如果你负责一次政务服务流程优化调研，请说明你如何收集群众意见并推动结果落地。",
            "领导安排你组织跨部门联席会议解决群众反映的高频问题，你会如何准备议题和推动闭环？",
            "如果让你组织一次老旧小区安全隐患排查，请说明人员分工、入户沟通、数据汇总和整改跟踪。",
            "单位要开展青年干部廉政教育活动，你会如何设计主题、案例、互动环节和效果评估？",
            "如果要做一次线上政策问答直播，请说明前期问题收集、口径审核、现场控场和后续答疑。",
            "领导让你牵头建立群众诉求办理台账，你会如何设置分类、时限、责任人和回访机制？",
        ],
        "人际沟通": [
            "如果群众坚持要求特殊办理，但政策规定不能突破，你会如何表达同理心并守住原则？",
            "如果同事认为你推进流程优化是在增加工作量，你会如何沟通共同目标和实际收益？",
            "如果上级部门和本单位对同一事项口径不同，你会如何核实、请示并避免给群众错误承诺？",
            "如果群众投诉你的沟通态度，但你认为自己按流程办理，你会如何复盘并改进表达？",
            "如果老同志认为你提出的新办法不切实际，你会如何请教经验、解释依据并小范围验证？",
            "如果群众在大厅情绪激动并打断你解释政策，你会如何先稳情绪再讲清办理路径？",
            "如果协作部门回复不及时影响群众事项办理，你会如何催办、留痕并同步风险？",
            "如果领导临时调整任务优先级导致原计划受影响，你会如何汇报取舍并争取资源支持？",
        ],
        "应急处置": [
            "如果政务系统故障期间有人急需办理限时事项，你会如何分类处理、上报协调并保留记录？",
            "如果活动现场出现人员身体不适，同时群众秩序开始混乱，你会如何分工处置？",
            "如果突发舆情中出现不实信息，你会如何在核实事实、依法回应和情绪疏导之间安排步骤？",
            "如果群众集中反映同一政策理解偏差，你会如何先稳现场，再推动后续统一解释和流程优化？",
            "如果办事大厅突然停电且排队群众较多，你会如何保障秩序、解释安排、分流办理并记录情况？",
            "如果政策窗口出现材料遗失争议，群众情绪激烈，你会如何核查证据、安抚群众并启动补救？",
            "如果线上预约系统被大量重复提交影响正常业务，你会如何先恢复服务，再排查原因和防范复发？",
            "如果突发暴雨导致服务活动无法继续，但现场仍有老人等待，你会如何调整地点、通知家属和做好安全保障？",
        ],
    }
    return _append_questions(banks, extras)


def _append_questions(banks: list[QuestionRoundBank], extras: dict[str, list[str]]) -> list[QuestionRoundBank]:
    return [
        (round_name, difficulty, [*questions, *extras.get(round_name, [])])
        for round_name, difficulty, questions in banks
    ]


def _replace_questions(banks: list[QuestionRoundBank], replacements: dict[str, list[str]]) -> list[QuestionRoundBank]:
    return [
        (round_name, difficulty, replacements.get(round_name, questions))
        for round_name, difficulty, questions in banks
    ]


def _append_suffix_to_questions(banks: list[QuestionRoundBank], suffix: str) -> list[QuestionRoundBank]:
    return [
        (
            round_name,
            difficulty,
            [f"{question.strip()} {suffix}" for question in questions],
        )
        for round_name, difficulty, questions in banks
    ]


def _apply_job_domain_language(
    banks: list[QuestionRoundBank],
    title: str,
    role_label: str,
    angle_hint: str,
) -> list[QuestionRoundBank]:
    query = f"{title} {role_label} {angle_hint}"
    replacements: dict[str, str] = {}
    if _text_matches_any(query, ("护士", "护理", "临床", "医嘱", "患者")):
        replacements.update(
            {
                "用户、业务和团队": "患者、护理质量和团队协作",
                "业务目标": "护理质量目标",
                "业务理解": "患者需求理解",
                "新业务": "新护理流程",
                "关键技术或业务取舍": "关键护理处置或风险控制取舍",
                "技术或业务取舍": "护理处置或风险控制取舍",
                "可维护性和上线节奏": "安全性、规范性和执行节奏",
                "业务方": "科室或患者家属",
                "上线后用户反馈": "执行后患者反馈",
                "用户反馈": "患者反馈",
                "业务数据": "护理记录",
                "业务问题、技术或专业方案": "护理问题、专业处置方案",
                "更高并发、更大用户量或更复杂业务": "更高患者量、更复杂病情或更严格质控",
                "数据、日志、访谈或实验": "护理记录、医嘱、患者反馈或风险事件复盘",
                "高压交付": "高压护理任务",
                "公司业务链路": "医疗护理服务链路",
                "交付价值": "保障护理质量",
                "产品或业务事实": "护理规范或患者安全事实",
                "短期上线和长期质量冲突": "短期处理效率和长期护理质量冲突",
            }
        )
    if _text_matches_any(query, ("ui/ux", "ui ux", "用户体验", "交互设计", "设计系统", "可用性", "视觉设计")):
        replacements.update(
            {
                "用户、业务和团队": "用户体验、产品目标和协作团队",
                "业务目标": "产品体验目标",
                "业务理解": "用户场景理解",
                "业务方": "产品方或需求方",
                "关键技术或业务取舍": "关键交互方案或产品目标取舍",
                "技术或业务取舍": "交互方案或产品目标取舍",
                "数据、实验或小范围验证": "用户反馈、可用性测试或小范围验证",
                "产品或业务事实": "产品场景或用户研究证据",
                "公司业务链路": "产品体验链路",
                "交付价值": "提升用户体验和产品效率",
            }
        )
    if not replacements:
        return banks
    return _replace_question_text(banks, replacements)


def _apply_postgraduate_discipline_language(
    banks: list[QuestionRoundBank],
    discipline_focus: str,
) -> list[QuestionRoundBank]:
    non_engineering_markers = (
        "法条体系",
        "文本细读",
        "教育测量",
        "变量识别",
        "作品集逻辑",
    )
    if not _text_matches_any(discipline_focus, non_engineering_markers):
        return banks
    replacements = {
        "研究价值、技术路线、实验验证和不足": "研究价值、方法路径、材料证据和不足",
        "技术路线": "方法路径",
        "实验验证": "证据验证",
        "工程实现": "应用整理",
        "实验、对照组、误差分析或消融分析": "案例材料、样本边界、方法比较或理论分析",
        "技术或实验瓶颈": "材料、方法或论证瓶颈",
        "实验论文": "实证论文",
        "实验部分": "方法部分",
        "实验结果": "研究结果",
        "复现实验": "复核材料",
        "重复实验": "复核材料",
        "做实验或分析案例": "查资料或分析案例",
        "实验记录": "研究记录",
    }
    return _replace_question_text(banks, replacements)


def _replace_question_text(banks: list[QuestionRoundBank], replacements: dict[str, str]) -> list[QuestionRoundBank]:
    replaced_banks: list[QuestionRoundBank] = []
    for round_name, difficulty, questions in banks:
        replaced_questions = []
        for question in questions:
            next_question = question
            for source, target in replacements.items():
                next_question = next_question.replace(source, target)
            replaced_questions.append(next_question)
        replaced_banks.append((round_name, difficulty, replaced_questions))
    return replaced_banks


def _merge_focus_terms(*hints: str | None, fallback: str) -> str:
    terms: list[str] = []
    seen: set[str] = set()
    for hint in hints:
        if not hint:
            continue
        for term in re.split(r"[、，,；;\s]+", hint):
            compacted = term.strip()
            if not compacted:
                continue
            normalized = compacted.lower()
            if normalized in seen:
                continue
            seen.add(normalized)
            terms.append(compacted)
    return "、".join(terms) or fallback


def _project_context_hint(material_context: InterviewMaterialContext | None) -> str:
    if material_context is None:
        return ""
    text_blocks = [
        material_context.resume_text or "",
        material_context.profile_summary or "",
        " ".join(material_context.keywords or []),
    ]
    combined = "\n".join(block for block in text_blocks if block.strip())
    if not combined.strip():
        return ""
    project_markers = (
        "项目",
        "系统",
        "平台",
        "小程序",
        "网站",
        "app",
        "应用",
        "服务",
        "模型",
        "实验",
        "论文",
        "课题",
        "毕业设计",
        "竞赛",
        "作品",
    )
    for candidate in re.split(r"[。！？!?；;\r\n]+", combined):
        candidate = candidate.strip(" ：:，,")
        if len(candidate) < 8:
            continue
        if _text_matches_any(candidate, project_markers):
            return _compact_hint(candidate, "", max_chars=120)
    return ""


def _postgraduate_discipline_focus(
    major: str,
    direction: str,
    major_label: str,
    angle_hint: str,
) -> str:
    query = f"{major} {direction} {major_label} {angle_hint}".lower()
    focus_rules: tuple[tuple[tuple[str, ...], str], ...] = (
        (
            ("计算机", "软件", "人工智能", "大模型", "机器学习", "算法", "数据科学", "网络空间", "信息安全"),
            "数据结构与算法复杂度、系统边界、模型评估、数据集偏差",
        ),
        (
            ("法学", "法律", "法硕", "民商法", "刑法", "行政法", "知识产权"),
            "法条体系、法律适用、案例争点、价值衡量",
        ),
        (
            ("医学", "临床", "护理", "公共卫生", "药学", "口腔", "中医"),
            "循证证据、临床路径、伦理边界、样本与指南",
        ),
        (
            ("教育", "心理", "学科教学", "应用心理", "课程与教学"),
            "教育测量、课堂观察、干预设计、研究伦理",
        ),
        (
            ("经济", "金融", "管理", "会计", "工商", "公共管理", "mpa", "mba"),
            "变量识别、因果推断、组织决策、数据解释",
        ),
        (
            ("新闻", "传播", "中文", "文学", "外语", "翻译", "历史", "哲学", "马克思"),
            "文本细读、理论脉络、语料或史料证据、问题意识",
        ),
        (
            ("数学", "统计", "物理", "化学", "生物", "地理", "资源环境"),
            "模型假设、实验或样本设计、误差分析、结果解释",
        ),
        (
            ("机械", "控制", "电子", "通信", "土木", "材料", "电气", "交通", "能源"),
            "工程建模、实验验证、误差控制、工艺或系统安全",
        ),
        (
            ("设计", "艺术", "美术", "视觉", "工业设计", "音乐", "戏剧"),
            "作品集逻辑、设计研究、视觉叙事、用户或场域验证",
        ),
        (
            ("农业", "林学", "生态", "食品", "环境"),
            "样本采集、生态或生产场景、实验设计、应用转化",
        ),
    )
    for keywords, focus in focus_rules:
        if _text_matches_any(query, keywords):
            return focus
    compacted_angles = _compact_hint(angle_hint, "专业基础、研究问题、方法路径、文献边界", max_chars=72)
    return f"{compacted_angles}、研究问题、方法路径"


def _preset_bank_count(interview_type: InterviewType) -> int:
    return sum(1 for preset in load_interview_presets() if preset.interview_type == interview_type)


def _text_matches_any(text: str, keywords: tuple[str, ...]) -> bool:
    lowered = text.lower()
    return any(keyword.lower() in lowered for keyword in keywords)


IELTS_THEME_SETS = [
    {
        "part1": [
            "Let's talk about your hometown. What do you like most about it?",
            "Let's talk about study. Do you prefer studying alone or with other people?",
            "Do you think your hometown is a good place for young people? Why?",
        ],
        "part2": "Describe a place in your city that you like to visit. You should say where it is, what people do there, how often you go there, and explain why you like it.",
        "part3": [
            "Why do some cities invest a lot in public spaces?",
            "How can city planners make public spaces useful for both young and old people?",
        ],
    },
    {
        "part1": [
            "Let's talk about learning. What skill would you like to learn in the future?",
            "Let's talk about technology. Do you often use apps to learn new things?",
            "Do you think it is easier to learn skills online or in a classroom?",
        ],
        "part2": "Describe a skill you would like to improve. You should say what it is, why you want to improve it, how you plan to improve it, and explain how it may help you in the future.",
        "part3": [
            "Why do some adults find it difficult to keep learning new skills?",
            "How can schools and workplaces help people become better lifelong learners?",
        ],
    },
    {
        "part1": [
            "Let's talk about friends. How often do you meet your friends?",
            "Let's talk about helping others. Do you like helping people with small problems?",
            "What kind of help do young people usually need from friends?",
        ],
        "part2": "Describe a person who helped you with something important. You should say who this person is, what they did, when it happened, and explain why this help was important.",
        "part3": [
            "Why do people sometimes hesitate to ask others for help?",
            "Do you think communities should provide more support for people who live alone?",
        ],
    },
    {
        "part1": [
            "Let's talk about daily routines. What part of your day do you enjoy most?",
            "Let's talk about time management. Are you usually punctual?",
            "Do you think young people today are busier than before?",
        ],
        "part2": "Describe a time when you had to manage several tasks in one day. You should say what the tasks were, why they were important, how you managed them, and explain how you felt afterwards.",
        "part3": [
            "Why do some people find it hard to manage their time well?",
            "Should schools teach students how to plan their time more effectively?",
        ],
    },
    {
        "part1": [
            "Let's talk about food. What kind of food do you usually eat at home?",
            "Let's talk about cooking. Do you enjoy cooking?",
            "Do you think eating habits have changed in your country?",
        ],
        "part2": "Describe a meal that you enjoyed with other people. You should say what you ate, who you were with, where it happened, and explain why this meal was memorable.",
        "part3": [
            "Why do people in many countries like eating together?",
            "How can governments encourage healthier eating habits?",
        ],
    },
    {
        "part1": [
            "Let's talk about reading. What do you like to read?",
            "Let's talk about news. How do you usually get news?",
            "Do you think people read less than they used to?",
        ],
        "part2": "Describe a book, article, or story that influenced you. You should say what it was about, when you read it, what you learned, and explain why it influenced you.",
        "part3": [
            "Why is reading still important in the age of short videos?",
            "How can schools help students develop a long-term reading habit?",
        ],
    },
    {
        "part1": [
            "Let's talk about transport. How do you usually travel around your city?",
            "Let's talk about walking. Do you like walking for short trips?",
            "Do you think public transport is convenient where you live?",
        ],
        "part2": "Describe a journey that took longer than expected. You should say where you were going, what caused the delay, what you did during the journey, and explain how you felt about it.",
        "part3": [
            "What are the main transport problems in large cities?",
            "How can governments encourage people to use public transport more often?",
        ],
    },
    {
        "part1": [
            "Let's talk about your home. Which room do you spend the most time in?",
            "Let's talk about decoration. Do you like changing things in your room?",
            "What makes a place comfortable to live in?",
        ],
        "part2": "Describe a home or apartment you would like to live in. You should say where it would be, what it would look like, who you would live with, and explain why you would like it.",
        "part3": [
            "Why do housing preferences change as people get older?",
            "What should cities do to make housing more affordable and comfortable?",
        ],
    },
    {
        "part1": [
            "Let's talk about sports. Do you prefer watching sports or playing sports?",
            "Let's talk about exercise. How often do you exercise?",
            "Do you think schools should give students more time for physical activities?",
        ],
        "part2": "Describe a sport or physical activity you tried for the first time. You should say what it was, where you tried it, who you were with, and explain whether you would do it again.",
        "part3": [
            "Why do some adults stop exercising after they start working?",
            "How can communities make it easier for people to stay active?",
        ],
    },
    {
        "part1": [
            "Let's talk about plans. Do you like making plans for your week?",
            "Let's talk about changes. How do you feel when plans suddenly change?",
            "Do you think it is better to plan everything or leave some things flexible?",
        ],
        "part2": "Describe a plan you made that changed later. You should say what the plan was, why it changed, what you did instead, and explain what you learned from the experience.",
        "part3": [
            "Why do organisations sometimes need to change their plans quickly?",
            "How can people balance careful planning with the ability to adapt?",
        ],
    },
    {
        "part1": [
            "Let's talk about work and study. What kind of task do you usually enjoy doing?",
            "Let's talk about teamwork. Do you prefer working with a team or by yourself?",
            "What makes a person easy to work with?",
        ],
        "part2": "Describe a project or assignment that you completed successfully. You should say what it was, what your role was, what difficulties you had, and explain why it was successful.",
        "part2_followup": "You described a project. What part of it would you improve if you did it again?",
        "part3": [
            "Why do some teams perform better than others?",
            "How can schools or companies teach people to cooperate more effectively?",
        ],
    },
    {
        "part1": [
            "Let's talk about technology. What device do you use most often?",
            "Let's talk about online services. Do you prefer doing things online or in person?",
            "Do you think people rely too much on technology now?",
        ],
        "part2": "Describe an app or website that you find useful. You should say what it is, how often you use it, what you use it for, and explain why it is useful.",
        "part2_followup": "You described an app or website. Would you recommend it to older people?",
        "part3": [
            "What are the advantages and disadvantages of moving public services online?",
            "How can technology companies make their products more inclusive?",
        ],
    },
    {
        "part1": [
            "Let's talk about celebrations. What events do people usually celebrate in your family?",
            "Let's talk about gifts. Do you like choosing gifts for other people?",
            "Are traditional celebrations still important to young people?",
        ],
        "part2": "Describe a celebration that you enjoyed. You should say what the celebration was, who was there, what happened, and explain why you enjoyed it.",
        "part2_followup": "You described a celebration. Would you like to experience it again?",
        "part3": [
            "Why do societies keep traditional celebrations?",
            "How have modern lifestyles changed the way people celebrate important events?",
        ],
    },
    {
        "part1": [
            "Let's talk about the environment. Do you often notice environmental problems around you?",
            "Let's talk about saving resources. What do you do to save water or electricity?",
            "Do you think young people care more about the environment than older people?",
        ],
        "part2": "Describe an environmental problem that you noticed. You should say what it was, where you saw it, what caused it, and explain what could be done about it.",
        "part2_followup": "You described an environmental problem. Has it changed your daily habits?",
        "part3": [
            "Who should take more responsibility for environmental protection, individuals or governments?",
            "How can cities balance economic development with environmental protection?",
        ],
    },
    {
        "part1": [
            "Let's talk about decisions. Do you usually make decisions quickly?",
            "Let's talk about advice. Who do you ask for advice when you have an important choice?",
            "Do you think young people should make big decisions by themselves?",
        ],
        "part2": "Describe an important decision you made. You should say what the decision was, why you had to make it, who helped you, and explain whether it was a good decision.",
        "part2_followup": "You described an important decision. Would you make the same choice today?",
        "part3": [
            "Why do some people find it difficult to make decisions?",
            "How can education help young people make more responsible decisions?",
        ],
    },
]


def _ielts_steps(seed: str) -> list[QuestionBankStep]:
    theme = _pick(IELTS_THEME_SETS, seed, "ielts-theme")
    return [
        QuestionBankStep("Part 1", theme["part1"][0]),
        QuestionBankStep("Part 1", theme["part1"][1]),
        QuestionBankStep("Part 1 Follow-up", theme["part1"][2]),
        QuestionBankStep("Part 2", theme["part2"]),
        QuestionBankStep("Part 2 Follow-up", theme.get("part2_followup", "You described something in Part 2. What made this experience important to you?")),
        QuestionBankStep("Part 3", theme["part3"][0]),
        QuestionBankStep("Part 3 Follow-up", theme["part3"][1]),
    ]


def _select_capability_aware_steps(
    banks: list[QuestionRoundBank],
    seed: str,
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None,
    db_connection: ExecuteConnection | None,
) -> list[QuestionBankStep]:
    matches = retrieve_capability_cards(
        interview_type=interview_type,
        material_context=material_context,
        limit=4,
        db_connection=db_connection,
    )
    enriched_banks = _prepend_capability_questions(banks, matches)
    selected_steps = _select_steps(enriched_banks, seed)
    if interview_type == InterviewType.IELTS:
        return selected_steps
    return _append_capability_round_hints(selected_steps, matches)


def _prepend_capability_questions(
    banks: list[QuestionRoundBank],
    matches: list[Any],
) -> list[QuestionRoundBank]:
    if not matches:
        return banks
    enriched: list[QuestionRoundBank] = []
    for round_name, difficulty, questions in banks:
        capability_questions = capability_questions_for_round(matches, round_name, limit=3)
        enriched.append((round_name, difficulty, _dedupe_questions([*capability_questions, *questions])))
    return enriched


def _append_capability_round_hints(
    steps: list[QuestionBankStep],
    matches: list[Any],
) -> list[QuestionBankStep]:
    if not matches:
        return steps
    enriched_steps: list[QuestionBankStep] = []
    for step in steps:
        hint = capability_round_hint(matches, step.round_name)
        if not hint or _question_mentions_hint(step.question_text, hint):
            enriched_steps.append(step)
            continue
        enriched_steps.append(
            QuestionBankStep(
                round_name=step.round_name,
                question_text=f"{step.question_text} 请回答时优先落到：{hint}。",
            )
        )
    return enriched_steps


def _dedupe_questions(questions: list[str]) -> list[str]:
    deduped: list[str] = []
    seen: set[str] = set()
    for question in questions:
        normalized = re.sub(r"\s+", "", question).lower()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        deduped.append(question)
    return deduped


def _question_mentions_hint(question: str, hint: str) -> bool:
    terms = [term.strip() for term in re.split(r"[、，,；;]+", hint) if term.strip()]
    return any(term in question for term in terms)


def _select_steps(banks: list[QuestionRoundBank], seed: str) -> list[QuestionBankStep]:
    steps: list[QuestionBankStep] = []
    selected_questions: set[str] = set()
    for index, (round_name, _difficulty, questions) in enumerate(banks):
        question = _pick_unique_session_question(questions, selected_questions, seed, round_name, str(index))
        selected_questions.add(_normalize_question_for_session(question))
        steps.append(QuestionBankStep(round_name, question))
    return steps


def _pick_unique_session_question(questions: list[str], selected_questions: set[str], *seed_parts: str) -> str:
    picked_question = _pick(questions, *seed_parts)
    start_index = questions.index(picked_question)
    for offset in range(len(questions)):
        candidate = questions[(start_index + offset) % len(questions)]
        if _normalize_question_for_session(candidate) not in selected_questions:
            return candidate
    return picked_question


def _normalize_question_for_session(question: str) -> str:
    return re.sub(r"\s+", "", question).lower()


def _inventory_from_banks(banks: list[QuestionRoundBank]) -> dict[str, Any]:
    all_questions = [question for _round_name, _difficulty, questions in banks for question in questions]
    rounds = [
        {
            "round_name": round_name,
            "difficulty": difficulty,
            "choice_count": len(questions),
        }
        for round_name, difficulty, questions in banks
    ]
    return {
        "rounds": rounds,
        "difficulty_scores": [DIFFICULTY_SCORES[item["difficulty"]] for item in rounds],
        "min_question_chars": min((len(question) for question in all_questions), default=0),
    }


def _ielts_inventory() -> dict[str, Any]:
    rounds = [
        {"round_name": "Part 1", "difficulty": "easy", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 1", "difficulty": "easy", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 1 Follow-up", "difficulty": "easy", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 2", "difficulty": "medium", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 2 Follow-up", "difficulty": "medium", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 3", "difficulty": "hard", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 3 Follow-up", "difficulty": "hard", "choice_count": len(IELTS_THEME_SETS)},
    ]
    return {
        "rounds": rounds,
        "difficulty_scores": [DIFFICULTY_SCORES[item["difficulty"]] for item in rounds],
        "min_question_chars": min(
            (
                len(question)
                for theme in IELTS_THEME_SETS
                for question in [
                    *theme["part1"],
                    theme["part2"],
                    theme.get("part2_followup", "You described something in Part 2. What made this experience important to you?"),
                    *theme["part3"],
                ]
            ),
            default=0,
        ),
    }


def _pick[T](items: list[T], *seed_parts: str) -> T:
    if not items:
        raise ValueError("question bank cannot pick from empty items")
    seed_text = ":".join(seed_parts).encode("utf-8")
    digest = hashlib.sha256(seed_text).hexdigest()
    return items[int(digest[:8], 16) % len(items)]


def _normalize_school_name(value: str) -> str:
    return re.sub(r"[\s·・.。()（）\[\]【】《》-]+", "", value).lower()


def _compact_hint(text: str | None, fallback: str, max_chars: int = 80) -> str:
    if not text:
        return fallback
    compacted = " ".join(text.split())
    return compacted[:max_chars] or fallback
