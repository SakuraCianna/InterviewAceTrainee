from dataclasses import dataclass
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
                    )
                )
                continue

            attempts.append(
                AIProviderAttempt(
                    provider_id=config.id,
                    provider_name=config.provider_name,
                    model_name=config.model_name,
                    success=True,
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
