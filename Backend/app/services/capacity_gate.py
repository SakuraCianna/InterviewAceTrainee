from __future__ import annotations

from contextlib import AbstractContextManager
from dataclasses import dataclass
from threading import Lock
from time import monotonic, time
from uuid import uuid4
from typing import Any

import redis

from app.core.config import get_settings
from app.services.redis_runtime import get_redis_client

_ACQUIRE_SCRIPT = """
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1] - ARGV[3])
local count = redis.call('ZCARD', KEYS[1])
if count >= tonumber(ARGV[2]) then
  local retry_after = 1
  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  if oldest[2] ~= nil then
    retry_after = math.max(1, math.ceil((tonumber(oldest[2]) + tonumber(ARGV[3])) - tonumber(ARGV[1])))
  end
  return {0, count, retry_after}
end
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return {1, count + 1, 0}
"""

_local_lock = Lock()
_local_leases: dict[str, dict[str, float]] = {}


@dataclass
class CapacityLease(AbstractContextManager["CapacityLease"]):
    name: str
    key: str
    lease_id: str
    acquired: bool
    retry_after_seconds: int = 0
    active_count: int = 0
    _redis_client: Any | None = None

    def release(self) -> None:
        if not self.acquired:
            return
        if self._redis_client is not None:
            try:
                self._redis_client.zrem(self.key, self.lease_id)
            except redis.RedisError:
                return
            return
        with _local_lock:
            _local_leases.get(self.key, {}).pop(self.lease_id, None)

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.release()


def acquire_capacity(name: str, limit: int, lease_seconds: int) -> CapacityLease:
    if limit <= 0:
        return CapacityLease(name=name, key=name, lease_id="", acquired=False, retry_after_seconds=max(1, lease_seconds))

    settings = get_settings()
    key = f"{settings.capacity_key_prefix}:{name}"
    lease_id = str(uuid4())
    lease_seconds = max(1, lease_seconds)
    redis_client = get_redis_client()
    if redis_client is not None:
        try:
            result = redis_client.eval(_ACQUIRE_SCRIPT, 1, key, int(time()), limit, lease_seconds, lease_id)
        except redis.RedisError:
            result = None
        if result is not None:
            acquired = bool(int(result[0]))
            return CapacityLease(
                name=name,
                key=key,
                lease_id=lease_id if acquired else "",
                acquired=acquired,
                active_count=int(result[1]),
                retry_after_seconds=int(result[2] or 0),
                _redis_client=redis_client if acquired else None,
            )

    return _acquire_local_capacity(name=name, key=key, lease_id=lease_id, limit=limit, lease_seconds=lease_seconds)


def _acquire_local_capacity(name: str, key: str, lease_id: str, limit: int, lease_seconds: int) -> CapacityLease:
    now = monotonic()
    expires_before = now - lease_seconds
    with _local_lock:
        leases = _local_leases.setdefault(key, {})
        expired_ids = [item_id for item_id, started_at in leases.items() if started_at <= expires_before]
        for item_id in expired_ids:
            leases.pop(item_id, None)
        if len(leases) >= limit:
            return CapacityLease(
                name=name,
                key=key,
                lease_id="",
                acquired=False,
                active_count=len(leases),
                retry_after_seconds=1,
            )
        leases[lease_id] = now
        return CapacityLease(name=name, key=key, lease_id=lease_id, acquired=True, active_count=len(leases))
