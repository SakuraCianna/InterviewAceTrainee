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
    provider_request_id: str | None = None

    @property
    def call_metadata(self) -> dict[str, Any]:
        return {
            "provider_request_id": self.provider_request_id,
            "input_tokens": self.usage.get("prompt_tokens"),
            "output_tokens": self.usage.get("completion_tokens"),
            "usage_json": self.usage,
        }


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
        return LLMCompletionResponse(
            content=content,
            usage=data.get("usage") or {},
            provider_request_id=response.headers.get("x-ds-request-id") or response.headers.get("x-request-id"),
        )

    def _resolve_endpoint(self, config: AIProviderConfig) -> str:
        provider_name = config.provider_name.strip().lower()
        if provider_name == "deepseek":
            return "https://api.deepseek.com/chat/completions"
        raise LLMProviderError("provider_not_supported")

    def _resolve_api_key(self, config: AIProviderConfig) -> str:
        if config.api_key:
            return config.api_key
        provider_name = config.provider_name.strip().lower()
        if provider_name == "deepseek":
            return self._settings.deepseek_api_key
        return ""
