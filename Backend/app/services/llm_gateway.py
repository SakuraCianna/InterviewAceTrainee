from dataclasses import dataclass
from typing import Any

import httpx

from app.core.config import Settings
from app.services.ai_router import AIProviderConfig


class LLMProviderError(RuntimeError):
    """Raised when an LLM provider cannot complete a request."""


@dataclass(frozen=True)
class LLMCompletionResponse:
    content: str
    usage: dict[str, Any]


class OpenAICompatibleLLMClient:
    def __init__(self, settings: Settings, transport: httpx.BaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    def complete(
        self,
        config: AIProviderConfig,
        messages: list[dict[str, str]],
        temperature: float = 0.7,
        max_tokens: int = 360,
        timeout_seconds: float = 12.0,
    ) -> LLMCompletionResponse:
        endpoint = self._resolve_endpoint(config)
        api_key = self._resolve_api_key(config)
        if not api_key:
            raise LLMProviderError("provider_api_key_missing")

        payload = {
            "model": config.model_name,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": False,
        }
        try:
            with httpx.Client(transport=self._transport, timeout=timeout_seconds) as client:
                response = client.post(
                    endpoint,
                    headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                    json=payload,
                )
                response.raise_for_status()
        except httpx.HTTPError as exc:
            raise LLMProviderError("provider_http_error") from exc

        data = response.json()
        try:
            content = data["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError, TypeError, AttributeError) as exc:
            raise LLMProviderError("provider_response_invalid") from exc
        if not content:
            raise LLMProviderError("provider_response_empty")
        return LLMCompletionResponse(content=content, usage=data.get("usage") or {})

    def _resolve_endpoint(self, config: AIProviderConfig) -> str:
        provider_name = config.provider_name.strip().lower()
        if provider_name in {"zhipu", "zai", "z.ai", "bigmodel"}:
            return "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        if provider_name == "deepseek":
            return "https://api.deepseek.com/chat/completions"
        if provider_name in {"aliyun-bailian", "qwen", "tongyi"}:
            return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        if provider_name in {"volcengine-ark", "doubao"}:
            return "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        raise LLMProviderError("provider_not_supported")

    def _resolve_api_key(self, config: AIProviderConfig) -> str:
        provider_name = config.provider_name.strip().lower()
        if provider_name in {"zhipu", "zai", "z.ai", "bigmodel"}:
            return self._settings.zhipu_api_key
        if provider_name == "deepseek":
            return self._settings.deepseek_api_key
        if provider_name in {"aliyun-bailian", "qwen", "tongyi"}:
            return self._settings.aliyun_bailian_api_key
        if provider_name in {"volcengine-ark", "doubao"}:
            return self._settings.volcengine_ark_api_key
        return ""
