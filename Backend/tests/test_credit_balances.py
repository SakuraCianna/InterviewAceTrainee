import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.models import entities  # noqa: F401
from app.models.base import Base
from app.services.credit_ledger import DatabaseCreditLedgerStore, InMemoryCreditLedgerStore
from app.services.credit_balances import DatabaseCreditBalanceStore
from app.services.credits import InsufficientCreditsError
from app.services.audit_logs import DatabaseAuditLogStore


def test_database_credit_balance_store_creates_user_balance_and_adjusts_it():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseCreditBalanceStore(session)

    assert store.get_balance("student@example.com") == 0
    assert store.adjust("student@example.com", 3) == 3
    assert store.adjust("student@example.com", -1) == 2
    assert store.get_balance("student@example.com") == 2


def test_database_credit_balance_store_rejects_negative_balance():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseCreditBalanceStore(session)

    with pytest.raises(InsufficientCreditsError):
        store.adjust("student@example.com", -1)


def test_database_credit_ledger_store_records_balance_history():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseCreditLedgerStore(session)

    entry = store.record(
        user_email="Student@Example.com",
        change_amount=3,
        balance_after=3,
        reason="manual_grant",
        operator_admin_email="admin@example.com",
        note="小范围内测发放",
    )

    entries = store.list_for_user("student@example.com", limit=10)
    assert entry.user_email == "student@example.com"
    assert entries[0].reason == "manual_grant"
    assert entries[0].operator_admin_email == "admin@example.com"


def test_in_memory_credit_ledger_store_keeps_recent_entries_per_user():
    store = InMemoryCreditLedgerStore()

    store.record(user_email="a@example.com", change_amount=1, balance_after=1, reason="manual_grant")
    store.record(user_email="b@example.com", change_amount=2, balance_after=2, reason="manual_grant")
    store.record(user_email="a@example.com", change_amount=-1, balance_after=0, reason="interview_start:job")

    entries = store.list_for_user("a@example.com", limit=10)
    assert [entry.change_amount for entry in entries] == [-1, 1]


def test_database_audit_log_store_records_admin_operation():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    store = DatabaseAuditLogStore(session)

    entry = store.record(
        admin_email="admin@example.com",
        action="credit_adjust",
        target_type="user_credit",
        target_id="student@example.com",
        before_snapshot={"balance": 0},
        after_snapshot={"balance": 3},
    )

    assert entry.action == "credit_adjust"
    assert entry.admin_email == "admin@example.com"
    assert store.list_recent(limit=1)[0].target_id == "student@example.com"
