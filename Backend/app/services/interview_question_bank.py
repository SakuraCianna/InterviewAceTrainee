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
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import best_preset_hint


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
) -> list[QuestionBankStep]:
    seed = session_id or "default-session"
    if interview_type == InterviewType.JOB:
        return _select_steps(_job_banks(material_context), seed)
    if interview_type == InterviewType.POSTGRADUATE:
        return _select_steps(_postgraduate_banks(material_context), seed)
    if interview_type == InterviewType.CIVIL_SERVICE:
        return _select_steps(_civil_service_banks(), seed)
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
    return {
        "job": _inventory_from_banks(_job_banks(None)),
        "postgraduate": _inventory_from_banks(_postgraduate_banks(sample_postgraduate_context)),
        "civil_service": _inventory_from_banks(_civil_service_banks()),
        "ielts": _ielts_inventory(),
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
    angle_hint = "、".join(question_angles[:4]) if question_angles else material_keywords or _compact_hint(job_requirements, "岗位匹配度、项目证据和问题定位")
    title = job_title or role_label
    return [
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


def _postgraduate_banks(material_context: InterviewMaterialContext | None) -> list[QuestionRoundBank]:
    target_school = material_context.target_school if material_context and material_context.target_school else "目标院校"
    major = material_context.major if material_context and material_context.major else "报考专业"
    direction = material_context.research_direction if material_context and material_context.research_direction else "你的研究兴趣"
    preset_title, question_angles, _ = best_preset_hint(InterviewType.POSTGRADUATE, material_context, "专业基础")
    major_label = preset_title or major
    angle_hint = "、".join(question_angles[:4]) if question_angles else direction
    tier = postgraduate_school_tier(target_school)
    tier_label = _postgraduate_tier_label(tier)
    return _postgraduate_banks_by_tier(tier, tier_label, target_school, major_label, direction, angle_hint)


def _postgraduate_banks_by_tier(
    tier: str,
    tier_label: str,
    target_school: str,
    major_label: str,
    direction: str,
    angle_hint: str,
) -> list[QuestionRoundBank]:
    pressure = {
        "elite": "问题意识、方法边界、文献差异和可验证研究假设",
        "high": "专业基础、项目证据、文献理解和研究计划",
        "advanced": "课程基础、项目经历、方向理解和表达稳定性",
        "standard": "基础概念、报考动机、学习计划和项目表达",
    }.get(tier, "基础概念、报考动机、学习计划和项目表达")
    return [
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


def _civil_service_banks() -> list[QuestionRoundBank]:
    return [
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
    ]


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
]


def _ielts_steps(seed: str) -> list[QuestionBankStep]:
    theme = _pick(IELTS_THEME_SETS, seed, "ielts-theme")
    return [
        QuestionBankStep("Part 1", theme["part1"][0]),
        QuestionBankStep("Part 1", theme["part1"][1]),
        QuestionBankStep("Part 1 Follow-up", theme["part1"][2]),
        QuestionBankStep("Part 2", theme["part2"]),
        QuestionBankStep("Part 3", theme["part3"][0]),
        QuestionBankStep("Part 3 Follow-up", theme["part3"][1]),
    ]


def _select_steps(banks: list[QuestionRoundBank], seed: str) -> list[QuestionBankStep]:
    return [
        QuestionBankStep(round_name, _pick(questions, seed, round_name, str(index)))
        for index, (round_name, _difficulty, questions) in enumerate(banks)
    ]


def _inventory_from_banks(banks: list[QuestionRoundBank]) -> dict[str, Any]:
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
    }


def _ielts_inventory() -> dict[str, Any]:
    rounds = [
        {"round_name": "Part 1", "difficulty": "easy", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 1", "difficulty": "easy", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 1 Follow-up", "difficulty": "easy", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 2", "difficulty": "medium", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 3", "difficulty": "hard", "choice_count": len(IELTS_THEME_SETS)},
        {"round_name": "Part 3 Follow-up", "difficulty": "hard", "choice_count": len(IELTS_THEME_SETS)},
    ]
    return {
        "rounds": rounds,
        "difficulty_scores": [DIFFICULTY_SCORES[item["difficulty"]] for item in rounds],
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
