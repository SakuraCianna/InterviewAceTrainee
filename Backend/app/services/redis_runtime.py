import redis
from time import monotonic

from app.core.config import get_settings

_redis_client: redis.Redis | None = None
_redis_last_checked_at = 0.0
_REDIS_HEALTH_CHECK_INTERVAL_SECONDS = 3.0


def get_redis_client() -> redis.Redis | None:
    global _redis_client, _redis_last_checked_at

    settings = get_settings()
    if not settings.redis_url:
        return None

    now = monotonic()
    if _redis_client is not None and now - _redis_last_checked_at < _REDIS_HEALTH_CHECK_INTERVAL_SECONDS:
        return _redis_client

    client = _redis_client or redis.Redis.from_url(
        settings.redis_url,
        decode_responses=True,
        socket_connect_timeout=0.5,
        socket_timeout=0.5,
        health_check_interval=30,
    )
    try:
        client.ping()
    except redis.RedisError:
        _redis_client = None
        _redis_last_checked_at = now
        return None
    _redis_client = client
    _redis_last_checked_at = now
    return client
