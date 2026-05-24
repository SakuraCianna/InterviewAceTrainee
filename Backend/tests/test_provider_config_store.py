from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.models import entities  # noqa: F401
from app.models.base import Base
from app.schemas.providers import ProviderConfigCreateRequest, ProviderConfigUpdateRequest
from app.services.audit_logs import DatabaseAuditLogStore
from app.services.provider_configs import DatabaseProviderConfigStore


def test_database_provider_config_store_seeds_defaults_and_persists_update():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseProviderConfigStore(session)

    providers = store.list_configs()
    assert providers[0].id == "glm-4.7-flash"
    assert providers[-1].id == "deepseek-v4-flash"

    updated = store.update_config("glm-4.7-flash", ProviderConfigUpdateRequest(enabled=False, priority=99))

    assert updated.enabled is False
    assert updated.priority == 99
    assert store.list_configs()[-1].id == "glm-4.7-flash"


def test_database_provider_config_store_creates_new_provider():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseProviderConfigStore(session)

    created = store.create_config(
        ProviderConfigCreateRequest(
            id="moonshot-flash",
            provider_type="llm",
            purpose="general",
            provider_name="moonshot",
            model_name="kimi-k2-flash",
            priority=35,
            region="cn",
        )
    )

    assert created.id == "moonshot-flash"
    assert created.enabled is True


def test_database_provider_config_changes_can_join_outer_transaction_and_roll_back():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    session = session_factory()
    provider_store = DatabaseProviderConfigStore(session, commit_on_write=False)
    audit_store = DatabaseAuditLogStore(session, commit_on_write=False)

    created = provider_store.create_config(
        ProviderConfigCreateRequest(
            id="rollback-provider",
            provider_type="llm",
            purpose="general",
            provider_name="rollback",
            model_name="rollback-flash",
            priority=40,
            region="cn",
        )
    )
    audit_store.record(
        admin_email="admin@example.com",
        action="provider_create",
        target_type="ai_provider_config",
        target_id=created.id,
        after_snapshot={"id": created.id},
    )
    session.rollback()

    verify_session = session_factory()
    provider_ids = [provider.id for provider in DatabaseProviderConfigStore(verify_session).list_configs()]
    assert "rollback-provider" not in provider_ids
    assert DatabaseAuditLogStore(verify_session).list_recent(limit=1) == []
