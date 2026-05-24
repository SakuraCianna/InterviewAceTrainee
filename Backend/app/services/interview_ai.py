from app.schemas.interviews import InterviewType
from app.services.ai_call_logs import AICallLogStore
from app.services.ai_router import AIServiceRouter, AllProvidersFailedError, NoProviderAvailableError
from app.services.llm_gateway import OpenAICompatibleLLMClient


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
    messages = [
        {
            "role": "system",
            "content": (
                "你是中文 AI 面试官。请基于用户上一轮回答，为下一轮生成一个自然、具体、"
                "适合语音播报的面试问题。只输出问题本身，不要编号，不要解释。"
            ),
        },
        {
            "role": "user",
            "content": (
                f"面试类型：{interview_type.value}\n"
                f"当前问题：{current_question}\n"
                f"用户回答：{answer_text[:1200]}\n"
                f"下一轮名称：{next_round_name}\n"
                f"原始下一题：{next_static_question}\n"
                "请保持问题简洁，避免隐私采集，不要求用户透露身份证、电话、住址等敏感信息。"
            ),
        },
    ]

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
