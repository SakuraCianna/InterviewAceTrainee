from app.schemas.interviews import InterviewType
from app.services.interview_ai import build_followup_messages
from app.services.interview_runtime import build_interview_steps, build_report


def _turn(round_name: str, question: str, answer: str) -> dict[str, str]:
    return {"round_name": round_name, "question": question, "answer": answer}


def test_each_interview_type_has_distinct_professional_flow():
    job_steps = build_interview_steps(InterviewType.JOB)
    postgraduate_steps = build_interview_steps(InterviewType.POSTGRADUATE)
    civil_steps = build_interview_steps(InterviewType.CIVIL_SERVICE)
    ielts_steps = build_interview_steps(InterviewType.IELTS)

    assert len(job_steps) >= 7
    assert [step.round_name for step in job_steps].count("专业一面") >= 2
    assert [step.round_name for step in job_steps].count("专业二面") >= 2
    assert any("HR" in step.round_name for step in job_steps)
    assert any("STAR" in step.question_text or "量化" in step.question_text for step in job_steps)

    assert len(postgraduate_steps) >= 5
    assert any("文献" in step.round_name or "英文" in step.question_text for step in postgraduate_steps)
    assert any("科研诚信" in step.question_text or "学术规范" in step.question_text for step in postgraduate_steps)

    assert len(civil_steps) >= 5
    assert any("综合分析" in step.round_name for step in civil_steps)
    assert any("组织协调" in step.round_name for step in civil_steps)
    assert any("应急应变" in step.round_name for step in civil_steps)
    assert any("依法" in step.question_text or "群众" in step.question_text for step in civil_steps)

    assert len(ielts_steps) >= 5
    assert any(step.round_name == "Part 2" and "You should say" in step.question_text for step in ielts_steps)
    assert any(step.round_name == "Part 3" and "abstract" in step.question_text.lower() for step in ielts_steps)


def test_reports_are_scenario_specific_and_actionable():
    job_report = build_report(
        session_id="quality-job",
        interview_type=InterviewType.JOB,
        turns=[
            _turn("专业一面", "介绍项目", "我先给结论，然后用 STAR 讲背景、任务、行动和结果，项目指标提升 30%，也复盘了取舍。"),
            _turn("专业二面", "复杂问题", "这个问题的根因是缓存一致性，我通过日志、监控和灰度定位，最后把失败率从 8% 降到 1%。"),
        ],
    )
    civil_report = build_report(
        session_id="quality-civil",
        interview_type=InterviewType.CIVIL_SERVICE,
        turns=[
            _turn("综合分析", "基层治理", "我会坚持群众立场和依法行政，先分析政策背景，再说明执行风险、资源协调和兜底预案。"),
            _turn("应急应变", "现场冲突", "第一步稳定情绪，第二步核实事实，第三步按制度解释并同步上报，最后复盘改进。"),
        ],
    )
    ielts_report = build_report(
        session_id="quality-ielts",
        interview_type=InterviewType.IELTS,
        turns=[
            _turn("Part 1", "Hometown", "My hometown is compact but energetic, and I enjoy it because the public transport is reliable and the community feels close."),
            _turn("Part 2", "Skill", "I would like to improve public speaking. I started practising with recorded answers, and this helped me organise ideas more logically."),
        ],
    )

    assert any(dimension.name == "项目证据与量化结果" for dimension in job_report.dimensions)
    assert any("STAR" in item or "岗位 JD" in item for item in job_report.next_plan)
    assert "工作面试" in job_report.summary

    assert any(dimension.name == "公共服务价值观" for dimension in civil_report.dimensions)
    assert any("政策" in item or "群众" in item for item in civil_report.next_plan)
    assert "结构化" in civil_report.summary

    assert [dimension.name for dimension in ielts_report.dimensions] == [
        "Fluency and coherence",
        "Lexical resource",
        "Grammatical range and accuracy",
        "Pronunciation",
    ]
    assert any("Part 2" in item or "band" in item.lower() for item in ielts_report.next_plan)


def test_followup_prompt_respects_scenario_language_and_rubric():
    ielts_messages = build_followup_messages(
        interview_type=InterviewType.IELTS,
        current_question="What do you like most about your hometown?",
        answer_text="I like the transport because it is convenient.",
        next_round_name="Part 2",
        next_static_question="Describe a skill you would like to improve.",
    )
    civil_messages = build_followup_messages(
        interview_type=InterviewType.CIVIL_SERVICE,
        current_question="请谈谈基层治理。",
        answer_text="我会关注群众诉求和政策落实。",
        next_round_name="组织协调",
        next_static_question="请组织一次政策宣讲。",
    )

    assert "English only" in ielts_messages[0]["content"]
    assert "IELTS speaking examiner" in ielts_messages[0]["content"]
    assert "中文 AI 面试官" not in ielts_messages[0]["content"]
    assert "Part 2" in ielts_messages[1]["content"]

    assert "结构化面试" in civil_messages[0]["content"]
    assert "群众" in civil_messages[0]["content"]
    assert "依法行政" in civil_messages[0]["content"]
