import httpx
import pytest

from app.core.config import Settings
from app.services.ai_router import AIProviderConfig
from app.services.llm_gateway import LLMProviderError, OpenAICompatibleLLMClient


def test_openai_compatible_client_rejects_missing_provider_key():
    client = OpenAICompatibleLLMClient(Settings())
    config = AIProviderConfig(
        id="glm",
        provider_type="llm",
        purpose="interview",
        enabled=True,
        priority=1,
        provider_name="zhipu",
        model_name="glm-4.7",
    )

    with pytest.raises(LLMProviderError) as error:
        client.complete(config, [{"role": "user", "content": "hello"}])

    assert str(error.value) == "provider_api_key_missing"


def test_openai_compatible_client_calls_zhipu_chat_completion_endpoint():
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["authorization"] = request.headers.get("authorization")
        captured["json"] = request.read().decode("utf-8")
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "下一题请说明你如何复盘失败经验。"}}],
                "usage": {"total_tokens": 23},
            },
        )

    transport = httpx.MockTransport(handler)
    client = OpenAICompatibleLLMClient(Settings(zhipu_api_key="test-key"), transport=transport)
    config = AIProviderConfig(
        id="glm",
        provider_type="llm",
        purpose="interview",
        enabled=True,
        priority=1,
        provider_name="zhipu",
        model_name="glm-4.7",
    )

    response = client.complete(config, [{"role": "user", "content": "hello"}])

    assert captured["url"] == "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    assert captured["authorization"] == "Bearer test-key"
    assert response.content == "下一题请说明你如何复盘失败经验。"
    assert response.usage["total_tokens"] == 23


def test_openai_compatible_client_prefers_provider_config_api_key():
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["authorization"] = request.headers.get("authorization")
        return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

    client = OpenAICompatibleLLMClient(Settings(zhipu_api_key="env-key"), transport=httpx.MockTransport(handler))
    config = AIProviderConfig(
        id="glm-custom",
        provider_type="llm",
        purpose="interview",
        enabled=True,
        priority=1,
        provider_name="zhipu",
        model_name="glm-4.7",
        api_key="config-key",
    )

    response = client.complete(config, [{"role": "user", "content": "hello"}])

    assert captured["authorization"] == "Bearer config-key"
    assert response.content == "ok"
