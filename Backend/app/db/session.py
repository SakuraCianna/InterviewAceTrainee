from collections.abc import Generator
from time import monotonic

from sqlalchemy import create_engine
from sqlalchemy import inspect
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import get_settings

settings = get_settings()

engine = create_engine(settings.database_url, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)
_database_ready_cache: dict[tuple[str, ...], tuple[bool, float]] = {}
_DATABASE_READY_CACHE_SECONDS = 3.0


def get_db_session() -> Generator[Session, None, None]:
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


def is_database_ready(required_tables: tuple[str, ...] = ()) -> bool:
    cache_key = tuple(sorted(required_tables))
    cached = _database_ready_cache.get(cache_key)
    now = monotonic()
    if cached is not None and cached[1] > now:
        return cached[0]
    try:
        inspector = inspect(engine)
        ready = all(inspector.has_table(table_name) for table_name in required_tables)
    except SQLAlchemyError:
        ready = False
    _database_ready_cache[cache_key] = (ready, now + _DATABASE_READY_CACHE_SECONDS)
    return ready


def get_optional_db_session(required_tables: tuple[str, ...]) -> Generator[Session | None, None, None]:
    if not is_database_ready(required_tables):
        yield None
        return

    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()
