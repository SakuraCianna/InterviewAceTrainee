from dataclasses import dataclass


class NoProviderAvailableError(LookupError):
    """Raised when no enabled provider can serve a provider type and purpose."""


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
        enabled_configs = [config for config in self._configs if config.enabled and config.provider_type == provider_type]
        purpose_matches = [config for config in enabled_configs if config.purpose == purpose]
        general_matches = [config for config in enabled_configs if config.purpose == "general"]
        candidates = purpose_matches or general_matches

        if not candidates:
            raise NoProviderAvailableError(f"no provider available for {provider_type}:{purpose}")

        return sorted(candidates, key=lambda config: config.priority)[0]
