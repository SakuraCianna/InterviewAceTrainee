import pytest

from app.services.ai_router import (
    AIProviderConfig,
    AIServiceRouter,
    AllProvidersFailedError,
    NoProviderAvailableError,
)


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


def test_router_runs_next_provider_when_primary_fails():
    router = AIServiceRouter(
        [
            AIProviderConfig(id="glm", provider_type="llm", purpose="general", enabled=True, priority=10),
            AIProviderConfig(id="deepseek", provider_type="llm", purpose="general", enabled=True, priority=20),
        ]
    )
    attempted_ids: list[str] = []

    def call_model(config: AIProviderConfig) -> str:
        attempted_ids.append(config.id)
        if config.id == "glm":
            raise RuntimeError("provider timeout")
        return "fallback-answer"

    result = router.run_with_fallback(provider_type="llm", purpose="interview", operation=call_model)

    assert result.value == "fallback-answer"
    assert result.provider.id == "deepseek"
    assert attempted_ids == ["glm", "deepseek"]
    assert [attempt.provider_id for attempt in result.attempts] == ["glm", "deepseek"]
    assert result.attempts[0].success is False
    assert result.attempts[1].success is True


def test_router_reports_all_attempts_when_every_provider_fails():
    router = AIServiceRouter(
        [
            AIProviderConfig(id="glm", provider_type="llm", purpose="general", enabled=True, priority=10),
            AIProviderConfig(id="deepseek", provider_type="llm", purpose="general", enabled=True, priority=20),
        ]
    )

    with pytest.raises(AllProvidersFailedError) as error:
        router.run_with_fallback(
            provider_type="llm",
            purpose="interview",
            operation=lambda _config: (_ for _ in ()).throw(RuntimeError("model down")),
        )

    assert [attempt.provider_id for attempt in error.value.attempts] == ["glm", "deepseek"]
    assert all(not attempt.success for attempt in error.value.attempts)
