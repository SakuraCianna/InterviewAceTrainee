from __future__ import annotations

from dataclasses import dataclass
import hashlib
import re

from app.schemas.interviews import InterviewType
from app.services.interview_material_context import InterviewMaterialContext
from app.services.interview_presets import best_preset_hint


@dataclass(frozen=True)
class QuestionBankStep:
    round_name: str
    question_text: str


ELITE_SCHOOL_MARKERS = (
    "清华大学",
    "北京大学",
    "复旦大学",
    "上海交通大学",
    "浙江大学",
    "南京大学",
    "中国科学技术大学",
    "哈尔滨工业大学",
    "西安交通大学",
    "中国人民大学",
)

ADVANCED_SCHOOL_MARKERS = (
    "985",
    "211",
    "双一流",
    "北京航空航天大学",
    "北京理工大学",
    "北京师范大学",
    "南开大学",
    "天津大学",
    "同济大学",
    "华东师范大学",
    "武汉大学",
    "华中科技大学",
    "中山大学",
    "东南大学",
    "厦门大学",
    "四川大学",
    "电子科技大学",
    "华南理工大学",
    "中南大学",
    "湖南大学",
    "重庆大学",
    "山东大学",
    "吉林大学",
    "大连理工大学",
    "东北大学",
    "兰州大学",
    "西北工业大学",
    "中国农业大学",
    "中央民族大学",
    "国防科技大学",
    "中国海洋大学",
)


def build_question_bank_steps(
    interview_type: InterviewType,
    material_context: InterviewMaterialContext | None = None,
    session_id: str | None = None,
) -> list[QuestionBankStep]:
    seed = session_id or "default-session"
    if interview_type == InterviewType.JOB:
        return _job_steps(material_context, seed)
    if interview_type == InterviewType.POSTGRADUATE:
        return _postgraduate_steps(material_context, seed)
    if interview_type == InterviewType.CIVIL_SERVICE:
        return _civil_service_steps(seed)
    if interview_type == InterviewType.IELTS:
        return _ielts_steps(seed)
    return []


def postgraduate_school_tier(target_school: str | None) -> str:
    """按用户填写的院校信息调节训练压力, 不宣称这是官方排名。"""

    normalized = _normalize_school_name(target_school or "")
    if not normalized:
        return "standard"
    if any(_normalize_school_name(marker) in normalized for marker in ELITE_SCHOOL_MARKERS):
        return "elite"
    if any(_normalize_school_name(marker) in normalized for marker in ADVANCED_SCHOOL_MARKERS):
        return "advanced"
    return "standard"


def _job_steps(material_context: InterviewMaterialContext | None, seed: str) -> list[QuestionBankStep]:
    job_title = material_context.job_title if material_context else None
    job_requirements = material_context.job_requirements if material_context else None
    material_keywords = "、".join((material_context.keywords or [])[:4]) if material_context else ""
    preset_title, question_angles, _ = best_preset_hint(InterviewType.JOB, material_context, "专业一面")
    role_label = preset_title or job_title or "目标岗位"
    angle_hint = "、".join(question_angles[:4]) if question_angles else material_keywords or _compact_hint(job_requirements, "岗位匹配度、项目证据和问题定位")

    banks = [
        (
            "专业一面",
            [
                f"你正在面试「{job_title or '目标岗位'}」。请用 90 到 120 秒介绍一个最能证明你胜任「{role_label}」的项目，必须说清背景、你的职责、关键动作和结果。",
                f"请从你的简历里选一个和「{role_label}」最贴近的经历，按 STAR 结构讲清：任务目标、你负责的边界、最终结果和复盘。",
            ],
        ),
        (
            "专业一面",
            [
                f"围绕「{angle_hint}」，请展开一个关键技术或业务取舍：你比较过哪些方案，为什么这样选，风险怎么控制？",
                f"如果面试官只允许你用一个项目证明岗位匹配，请说明项目目标、你的核心贡献、可验证指标和失败复盘。",
            ],
        ),
        (
            "专业一面追问",
            [
                "如果面试官质疑这是团队成果而不是你的贡献，你会拿哪些证据说明个人行动和不可替代性？",
                "请补充一个你在项目中判断失误或方案调整的细节，并说明你后来如何复盘。",
            ],
        ),
        (
            "专业二面",
            [
                "请解释一个复杂问题的根因定位过程：最初现象是什么，你如何排除干扰项，最后证据链是什么？",
                "请讲一次你把模糊需求拆成可执行方案的经历，重点说明优先级、边界和交付风险。",
            ],
        ),
        (
            "专业二面",
            [
                "如果同样方案放到更高并发、更低预算或更短周期下，你会优先改哪三处，为什么？",
                "请把刚才的方案从性能、成本、稳定性和可维护性四个角度重新权衡一次。",
            ],
        ),
        (
            "专业二面追问",
            [
                "当面试官质疑你的方案成本过高或风险过大时，你会怎样给出备选方案并明确取舍？",
                "如果业务方临时改变目标，你会怎样重新确认需求、调整排期并保护核心质量？",
            ],
        ),
        (
            "HR 面",
            [
                f"你为什么选择「{job_title or role_label}」？请结合岗位职责、个人经历和长期目标说明匹配点。",
                "请说明你过去一次高压协作经历：你如何沟通预期、处理冲突并保证交付？",
            ],
        ),
        (
            "HR 面追问",
            [
                "如果入职后发现业务节奏和预期不同，你会如何保持产出、沟通预期并持续学习？",
                "如果直属负责人给出和你判断相反的方向，你会如何验证事实、表达不同意见并推进共识？",
            ],
        ),
    ]
    return _select_steps(banks, seed)


def _postgraduate_steps(material_context: InterviewMaterialContext | None, seed: str) -> list[QuestionBankStep]:
    target_school = material_context.target_school if material_context and material_context.target_school else "目标院校"
    major = material_context.major if material_context and material_context.major else "报考专业"
    direction = material_context.research_direction if material_context and material_context.research_direction else "你的研究兴趣"
    preset_title, question_angles, _ = best_preset_hint(InterviewType.POSTGRADUATE, material_context, "专业基础")
    major_label = preset_title or major
    angle_hint = "、".join(question_angles[:4]) if question_angles else direction
    tier = postgraduate_school_tier(target_school)
    tier_label = {"elite": "顶尖院校复试", "advanced": "高水平院校复试", "standard": "基础复试"}[tier]

    banks_by_tier = {
        "elite": [
            (
                "复试开场",
                [
                    f"你正在准备「{target_school}」{tier_label}。请用 90 秒完成自我介绍，不只讲经历，还要把「{major_label}」的核心问题、项目证据和未来研究假设连起来。",
                    f"请做一段适合「{target_school}」{tier_label}的自我介绍，重点说明你为什么能在「{major_label}」方向承受高强度科研训练。",
                ],
            ),
            (
                "专业基础",
                [
                    f"请从「{major_label}」里选一个基础概念，解释它的适用条件、失效边界，并说明它如何支撑「{direction}」。",
                    f"围绕「{angle_hint}」，请讲清一个核心理论或模型的前提、推导直觉、局限和工程/实验含义。",
                ],
            ),
            (
                "科研潜力",
                [
                    "请把一个课程项目、毕设或竞赛改写成研究问题：变量是什么、数据从哪里来、如何验证，失败时怎么解释？",
                    "如果导师要求你把现有项目提升为可发表的研究工作，你会补哪些实验、对照组和误差分析？",
                ],
            ),
            (
                "文献与英文",
                [
                    "如果导师追问你最近两篇相关文献差异、贡献和局限，你会如何比较并提出自己的切入点？",
                    "请说明你读一篇英文论文时如何判断问题是否重要、方法是否可靠、实验是否充分。",
                ],
            ),
            (
                "导师沟通",
                [
                    "如果导师课题组方向与你原方向不完全一致，请提出一个 3 个月内可验证的小课题计划。",
                    f"请把「{direction}」和「{target_school}」可能的导师方向连接起来，说明你第一学期准备补哪些基础。",
                ],
            ),
            (
                "学术规范",
                [
                    "如果实验结果和预期相反，请说明你如何排查数据、复现实验、记录负结果并汇报风险。",
                    "如果同组成员建议删掉不利实验结果，你会如何坚持学术规范并推动团队复盘？",
                ],
            ),
        ],
        "advanced": [
            (
                "复试开场",
                [
                    f"你正在准备「{target_school}」{tier_label}。请用 90 秒自我介绍，突出「{major_label}」基础、项目证据和报考动机。",
                    f"请做一段复试自我介绍，说明你为什么选择「{target_school}」「{major_label}」，以及你目前最能证明基础的一段经历。",
                ],
            ),
            (
                "专业基础",
                [
                    f"请说明「{major_label}」里你最熟悉的一门核心课，并举例解释它如何支撑「{direction}」。",
                    f"围绕「{angle_hint}」，请解释一个本科阶段掌握较扎实的概念，并补一个项目或实验例子。",
                ],
            ),
            (
                "科研潜力",
                [
                    "请介绍一个课程设计、毕设或竞赛项目，并指出其中一个可以继续研究的问题。",
                    "如果复试老师要求你把项目讲得更学术一点，你会如何说明问题来源、方法和验证结果？",
                ],
            ),
            (
                "文献与英文",
                [
                    "如果导师给你一篇英文论文，请说明你会如何拆解摘要、方法、实验和结论，并做 3 分钟汇报。",
                    "请讲一次你读论文或技术资料的经历，重点说明你如何判断它和自己方向的关系。",
                ],
            ),
            (
                "导师沟通",
                [
                    "如果你的毕业设计方向和导师课题不完全一致，你会怎样建立连接并提出可执行的研究切入点？",
                    f"请结合「{target_school}」和「{direction}」，说明你希望导师如何指导你补齐科研短板。",
                ],
            ),
            (
                "学术规范",
                [
                    "如果实验结果和预期不一致，甚至影响论文进度，你会如何处理科研诚信、数据记录和沟通汇报？",
                    "如果复试老师追问你项目里的数据是否可靠，你会如何说明采集、清洗和验证过程？",
                ],
            ),
        ],
        "standard": [
            (
                "复试开场",
                [
                    f"你正在准备「{target_school}」{tier_label}。请用 90 秒自我介绍，清楚说明本科背景、报考「{major_label}」的原因和一段能证明基础的经历。",
                    f"请做一段复试自我介绍，重点讲清你学过什么、做过什么、为什么想继续读「{major_label}」。",
                ],
            ),
            (
                "专业基础",
                [
                    f"请说明你本科阶段最熟悉的一门专业课，并举一个例子说明它如何帮助你理解「{major_label}」。",
                    f"围绕「{direction}」，请选一个你能讲清楚的基础概念，说明定义、用途和一个简单例子。",
                ],
            ),
            (
                "科研潜力",
                [
                    "请介绍一个课程设计、毕业设计或竞赛项目，重点说明你负责了什么、学到了什么。",
                    "如果老师问你未来想研究什么，请结合已有课程或项目说出一个具体方向。",
                ],
            ),
            (
                "文献与英文",
                [
                    "如果老师让你阅读一篇英文论文，你会如何先看摘要、图表和结论，再整理汇报？",
                    "请说明你平时如何学习英文专业资料，以及遇到看不懂的地方会怎么处理。",
                ],
            ),
            (
                "导师沟通",
                [
                    "如果导师方向和你原来的项目不完全一致，你会如何补基础并尽快跟上课题组节奏？",
                    f"请结合「{target_school}」和「{major_label}」，说明你入学前最想补强的三块能力。",
                ],
            ),
            (
                "学术规范",
                [
                    "如果实验或项目结果和预期不一致，你会如何记录过程、查找原因并向老师说明？",
                    "如果同学建议你把不确定的数据写成确定结论，你会如何处理？",
                ],
            ),
        ],
    }
    return _select_steps(banks_by_tier[tier], seed)


def _civil_service_steps(seed: str) -> list[QuestionBankStep]:
    banks = [
        (
            "综合分析",
            [
                "某地上线基层政务服务数字化平台后，群众办事更方便，但也有人担心老年群体不会使用。请谈谈你的理解。",
                "有观点认为，基层治理既要追求效率，也要保留面对面服务温度。请结合群众获得感谈谈你的看法。",
                "围绕基层减负和服务群众，有人说“数据多跑路，干部少填表”。请从现实意义、风险和落实路径分析。",
            ],
        ),
        (
            "组织协调",
            [
                "如果让你组织一次面向社区老年人的医保政策宣讲和现场答疑，你会如何确定对象、资源、流程和应急预案？",
                "单位准备开展一次基层政务服务满意度调研，你负责统筹执行，请说明调研对象、方式、分工和结果运用。",
                "如果让你组织一次窗口服务规范培训，请说明前期摸底、课程安排、现场管理和效果评估。",
            ],
        ),
        (
            "应急应变",
            [
                "办事大厅系统临时故障，群众排队时间较长并出现情绪激动，你作为现场工作人员会如何处理？",
                "政策宣讲现场有人拍视频质疑工作人员解释不一致，引发围观讨论，你会如何稳控现场并核实回应？",
                "你负责的材料发放环节出现漏发，影响部分群众办理业务，你会如何补救并防止再次发生？",
            ],
        ),
        (
            "人际沟通",
            [
                "如果同事习惯按老办法处理业务，但新政策要求已经调整，你提醒后对方不太接受，你会如何沟通？",
                "领导临时安排你协助其他科室，导致你原本任务可能延期，你会如何协调优先级并汇报进度？",
                "群众认为你解释政策是在推诿，态度比较急躁，你会如何倾听、解释并守住原则边界？",
            ],
        ),
        (
            "岗位匹配",
            [
                "请结合公务员岗位职责，说明你如何理解服务意识、纪律意识和长期基层工作的压力。",
                "如果未来岗位工作重复、事务性强、短期成就感不明显，你会如何保持稳定投入？",
                "请谈谈你为什么选择公共服务岗位，并说明你的经历中哪些细节能证明责任感和抗压能力。",
            ],
        ),
    ]
    return _select_steps(banks, seed)


def _ielts_steps(seed: str) -> list[QuestionBankStep]:
    theme_sets = [
        {
            "part1": [
                "Let's talk about your hometown. What do you like most about it?",
                "Let's talk about study. Do you prefer studying alone or with other people?",
            ],
            "part1_followup": "Do you think your hometown is a good place for young people? Why?",
            "part2": "Describe a place in your city that you like to visit. You should say where it is, what people do there, how often you go there, and explain why you like it.",
            "part3": "Why do some cities invest a lot in public spaces?",
            "part3_followup": "How can city planners make public spaces useful for both young and old people?",
        },
        {
            "part1": [
                "Let's talk about learning. What skill would you like to learn in the future?",
                "Let's talk about technology. Do you often use apps to learn new things?",
            ],
            "part1_followup": "Do you think it is easier to learn skills online or in a classroom?",
            "part2": "Describe a skill you would like to improve. You should say what it is, why you want to improve it, how you plan to improve it, and explain how it may help you in the future.",
            "part3": "Why do some adults find it difficult to keep learning new skills?",
            "part3_followup": "How can schools and workplaces help people become better lifelong learners?",
        },
        {
            "part1": [
                "Let's talk about friends. How often do you meet your friends?",
                "Let's talk about helping others. Do you like helping people with small problems?",
            ],
            "part1_followup": "What kind of help do young people usually need from friends?",
            "part2": "Describe a person who helped you with something important. You should say who this person is, what they did, when it happened, and explain why this help was important.",
            "part3": "Why do people sometimes hesitate to ask others for help?",
            "part3_followup": "Do you think communities should provide more support for people who live alone?",
        },
    ]
    theme = _pick(theme_sets, seed, "ielts-theme")
    return [
        QuestionBankStep("Part 1", theme["part1"][0]),
        QuestionBankStep("Part 1", theme["part1"][1]),
        QuestionBankStep("Part 1 Follow-up", theme["part1_followup"]),
        QuestionBankStep("Part 2", theme["part2"]),
        QuestionBankStep("Part 3", theme["part3"]),
        QuestionBankStep("Part 3 Follow-up", theme["part3_followup"]),
    ]


def _select_steps(banks: list[tuple[str, list[str]]], seed: str) -> list[QuestionBankStep]:
    return [
        QuestionBankStep(round_name, _pick(questions, seed, round_name, str(index)))
        for index, (round_name, questions) in enumerate(banks)
    ]


def _pick[T](items: list[T], *seed_parts: str) -> T:
    if not items:
        raise ValueError("question bank cannot pick from empty items")
    seed_text = ":".join(seed_parts).encode("utf-8")
    digest = hashlib.sha256(seed_text).hexdigest()
    return items[int(digest[:8], 16) % len(items)]


def _normalize_school_name(value: str) -> str:
    return re.sub(r"[\s·・.。()（）-]+", "", value).lower()


def _compact_hint(text: str | None, fallback: str, max_chars: int = 80) -> str:
    if not text:
        return fallback
    compacted = " ".join(text.split())
    return compacted[:max_chars] or fallback
