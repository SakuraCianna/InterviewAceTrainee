from collections.abc import Generator
from functools import lru_cache

from sqlalchemy import create_engine
from sqlalchemy import inspect
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import get_settings

settings = get_settings()

engine = create_engine(settings.database_url, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)


def get_db_session() -> Generator[Session, None, None]:
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


@lru_cache
def is_database_ready(required_tables: tuple[str, ...] = ()) -> bool:
    try:
        inspector = inspect(engine)
        return all(inspector.has_table(table_name) for table_name in required_tables)
    except SQLAlchemyError:
        return False


def get_optional_db_session(required_tables: tuple[str, ...]) -> Generator[Session | None, None, None]:
    if not is_database_ready(required_tables):
        yield None
        return

    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()
