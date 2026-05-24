from app.schemas.interviews import InterviewType
from app.services.ai_call_logs import AICallLogStore
from app.services.ai_router import AIServiceRouter, AllProvidersFailedError, NoProviderAvailableError
from app.services.llm_gateway import OpenAICompatibleLLMClient


def build_followup_messages(
    interview_type: InterviewType,
    current_question: str,
    answer_text: str,
    next_round_name: str,
    next_static_question: str,
) -> list[dict[str, str]]:
    system_prompts = {
        InterviewType.JOB: (
            "你是中文 AI 工作面试官。追问要像真实技术面/HR 面：围绕岗位匹配、项目证据、"
            "技术取舍、量化结果和压力追问生成一个自然、具体、适合语音播报的问题。"
        ),
        InterviewType.POSTGRADUATE: (
            "你是中文 AI 研究生复试面试官。追问要围绕专业基础、科研兴趣、文献阅读、"
            "导师沟通和学术规范生成一个自然、具体、适合语音播报的问题。"
        ),
        InterviewType.CIVIL_SERVICE: (
            "你是中文 AI 公务员结构化面试考官。追问要体现结构化面试口径，关注审题、"
            "群众立场、依法行政、组织协调、应急处置和公共服务价值观。"
        ),
        InterviewType.IELTS: (
            "You are an IELTS speaking examiner. Ask in English only. Create one natural, concise follow-up "
            "question aligned with the IELTS Speaking flow and the next part. Do not explain or number it."
        ),
    }
    output_rule = (
        "只输出问题本身，不要编号，不要解释。"
        if interview_type != InterviewType.IELTS
        else "Output only the question itself. Do not add numbering, comments or Chinese text."
    )
    safety_rule = (
        "请保持问题简洁，避免隐私采集，不要求用户透露身份证、电话、住址等敏感信息。"
        if interview_type != InterviewType.IELTS
        else "Keep the question concise and do not ask for private identifiers, phone numbers or addresses."
    )
    return [
        {
            "role": "system",
            "content": f"{system_prompts[interview_type]}{output_rule}",
        },
        {
            "role": "user",
            "content": (
                f"面试类型：{interview_type.value}\n"
                f"当前问题：{current_question}\n"
                f"用户回答：{answer_text[:1200]}\n"
                f"下一轮名称：{next_round_name}\n"
                f"原始下一题：{next_static_question}\n"
                f"{safety_rule}"
            ),
        },
    ]


def generate_next_interview_question(
    router: AIServiceRouter,
    llm_client: OpenAICompatibleLLMClient,
    interview_type: InterviewType,
    current_question: str,
    answer_text: str,
    next_round_name: str,
    next_static_question: str,
    call_log_store: AICallLogStore | None = None,
    session_id: str | None = None,
) -> str | None:
    messages = build_followup_messages(
        interview_type=interview_type,
        current_question=current_question,
        answer_text=answer_text,
        next_round_name=next_round_name,
        next_static_question=next_static_question,
    )

    try:
        result = router.run_with_fallback(
            provider_type="llm",
            purpose="interview",
            operation=lambda config: llm_client.complete(config, messages).content,
        )
    except AllProvidersFailedError as exc:
        if call_log_store is not None:
            call_log_store.record_attempts(session_id=session_id, provider_type="llm", purpose="interview", attempts=exc.attempts)
        return None
    except NoProviderAvailableError:
        return None
    if call_log_store is not None:
        call_log_store.record_attempts(session_id=session_id, provider_type="llm", purpose="interview", attempts=result.attempts)
    return result.value
