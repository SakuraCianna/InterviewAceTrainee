from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.models import entities  # noqa: F401
from app.models.base import Base
from app.services.ai_call_logs import DatabaseAICallLogStore, InMemoryAICallLogStore
from app.services.ai_router import AIProviderAttempt


def test_database_ai_call_log_store_records_router_attempts():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseAICallLogStore(session)

    store.record_attempts(
        session_id="session-1",
        provider_type="llm",
        purpose="interview",
        attempts=[
            AIProviderAttempt(
                provider_id="glm",
                provider_name="zhipu",
                model_name="glm-4.7",
                success=False,
                error_message="timeout",
            ),
            AIProviderAttempt(
                provider_id="deepseek",
                provider_name="deepseek",
                model_name="deepseek-v4-flash",
                success=True,
            ),
        ],
    )

    records = store.list_recent(limit=10)
    assert [record.provider_name for record in records] == ["zhipu", "deepseek"]
    assert records[0].success is False
    assert records[1].success is True


def test_in_memory_ai_call_log_store_keeps_recent_attempts():
    store = InMemoryAICallLogStore()

    store.record_attempts(
        session_id="session-2",
        provider_type="llm",
        purpose="interview",
        attempts=[
            AIProviderAttempt(
                provider_id="glm",
                provider_name="zhipu",
                model_name="glm-4.7",
                success=False,
                error_message="missing key",
            )
        ],
    )

    assert store.list_recent(limit=1)[0].error_message == "missing key"
