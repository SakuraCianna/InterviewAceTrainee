from dataclasses import dataclass
from time import perf_counter
from typing import Callable, Generic, TypeVar


class NoProviderAvailableError(LookupError):
    """Raised when no enabled provider can serve a provider type and purpose."""


T = TypeVar("T")


@dataclass(frozen=True)
class AIProviderAttempt:
    provider_id: str
    provider_name: str
    model_name: str
    success: bool
    error_message: str = ""
    latency_ms: int | None = None
    provider_request_id: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    audio_duration_ms: int | None = None
    characters: int | None = None
    estimated_cost_cents: int | None = None
    usage_json: dict | None = None


@dataclass(frozen=True)
class AIRouteResult(Generic[T]):
    provider: "AIProviderConfig"
    value: T
    attempts: list[AIProviderAttempt]


class AllProvidersFailedError(RuntimeError):
    """Raised when every candidate provider failed during fallback routing."""

    def __init__(self, attempts: list[AIProviderAttempt]) -> None:
        super().__init__("all candidate AI providers failed")
        self.attempts = attempts


@dataclass(frozen=True)
class AIProviderConfig:
    id: str
    provider_type: str
    purpose: str
    enabled: bool
    priority: int
    provider_name: str = "custom"
    model_name: str = "custom"
    region: str = "cn"
    api_key: str = ""
    api_key_preview: str | None = None


class AIServiceRouter:
    def __init__(self, configs: list[AIProviderConfig]) -> None:
        self._configs = configs

    def select_provider(self, provider_type: str, purpose: str) -> AIProviderConfig:
        return self._candidate_configs(provider_type=provider_type, purpose=purpose)[0]

    def run_with_fallback(
        self,
        provider_type: str,
        purpose: str,
        operation: Callable[[AIProviderConfig], T],
    ) -> AIRouteResult[T]:
        attempts: list[AIProviderAttempt] = []

        for config in self._candidate_configs(provider_type=provider_type, purpose=purpose):
            started_at = perf_counter()
            try:
                value = operation(config)
            except Exception as exc:
                attempts.append(
                    AIProviderAttempt(
                        provider_id=config.id,
                        provider_name=config.provider_name,
                        model_name=config.model_name,
                        success=False,
                        error_message=str(exc),
                        latency_ms=int((perf_counter() - started_at) * 1000),
                    )
                )
                continue

            metadata = _call_metadata(value)
            attempts.append(
                AIProviderAttempt(
                    provider_id=config.id,
                    provider_name=config.provider_name,
                    model_name=config.model_name,
                    success=True,
                    latency_ms=int((perf_counter() - started_at) * 1000),
                    provider_request_id=metadata.get("provider_request_id"),
                    input_tokens=metadata.get("input_tokens"),
                    output_tokens=metadata.get("output_tokens"),
                    audio_duration_ms=metadata.get("audio_duration_ms"),
                    characters=metadata.get("characters"),
                    estimated_cost_cents=metadata.get("estimated_cost_cents"),
                    usage_json=metadata.get("usage_json"),
                )
            )
            return AIRouteResult(provider=config, value=value, attempts=attempts)

        raise AllProvidersFailedError(attempts)

    def _candidate_configs(self, provider_type: str, purpose: str) -> list[AIProviderConfig]:
        enabled_configs = [config for config in self._configs if config.enabled and config.provider_type == provider_type]
        purpose_matches = sorted([config for config in enabled_configs if config.purpose == purpose], key=lambda config: config.priority)
        general_matches = sorted(
            [config for config in enabled_configs if config.purpose == "general" and config.purpose != purpose],
            key=lambda config: config.priority,
        )
        candidates = purpose_matches + general_matches

        if not candidates:
            raise NoProviderAvailableError(f"no provider available for {provider_type}:{purpose}")

        return candidates


def _call_metadata(value: object) -> dict:
    metadata = getattr(value, "call_metadata", None)
    return metadata if isinstance(metadata, dict) else {}
