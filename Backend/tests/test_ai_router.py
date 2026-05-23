import pytest

from app.services.ai_router import AIProviderConfig, AIServiceRouter, NoProviderAvailableError


def test_router_selects_enabled_provider_by_purpose_and_priority():
    router = AIServiceRouter(
        [
            AIProviderConfig(id="slow", provider_type="llm", purpose="interview", enabled=True, priority=20),
            AIProviderConfig(id="fast", provider_type="llm", purpose="interview", enabled=True, priority=10),
            AIProviderConfig(id="disabled", provider_type="llm", purpose="interview", enabled=False, priority=1),
        ]
    )

    selected = router.select_provider(provider_type="llm", purpose="interview")

    assert selected.id == "fast"


def test_router_falls_back_to_general_purpose_provider():
    router = AIServiceRouter(
        [
            AIProviderConfig(id="general", provider_type="llm", purpose="general", enabled=True, priority=1),
        ]
    )

    selected = router.select_provider(provider_type="llm", purpose="report")

    assert selected.id == "general"


def test_router_raises_when_no_provider_matches_type():
    router = AIServiceRouter(
        [
            AIProviderConfig(id="asr", provider_type="asr", purpose="general", enabled=True, priority=1),
        ]
    )

    with pytest.raises(NoProviderAvailableError):
        router.select_provider(provider_type="llm", purpose="interview")

