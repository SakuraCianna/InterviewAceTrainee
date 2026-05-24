from sqlalchemy import create_engine

from app.models import entities  # noqa: F401
from app.models.base import Base


def test_database_models_can_create_sqlite_schema_for_isolated_tests():
    engine = create_engine("sqlite:///:memory:")

    Base.metadata.create_all(engine)

    assert "users" in Base.metadata.tables
    assert "interview_materials" in Base.metadata.tables
    assert "interview_sessions" in Base.metadata.tables
    assert "interview_turns" in Base.metadata.tables
    assert "interview_reports" in Base.metadata.tables
