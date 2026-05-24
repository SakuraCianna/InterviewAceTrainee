import redis

from app.core.config import get_settings


def get_redis_client() -> redis.Redis | None:
    settings = get_settings()
    if not settings.redis_url:
        return None

    client = redis.Redis.from_url(
        settings.redis_url,
        decode_responses=True,
        socket_connect_timeout=0.5,
        socket_timeout=0.5,
    )
    try:
        client.ping()
    except redis.RedisError:
        return None
    return client
