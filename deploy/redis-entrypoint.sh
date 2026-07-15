#!/bin/sh
set -eu

# 密码由 Compose secret 挂载，避免进入容器环境变量和 inspect 输出。
password="$(cat /run/secrets/redis_app_password)"
if [ "${#password}" -lt 32 ]; then
  echo "redis_app_password must contain at least 32 characters" >&2
  exit 1
fi

umask 077
cat > /run/redis/users.acl <<EOF
user default off
user mianba_app on >${password} ~mianba:* &mianba:* -@all +@connection +@read +@write +@transaction +eval +evalsha +scan -keys -flushall -flushdb -config -shutdown -module -acl
EOF
chown redis:redis /run/redis/users.acl

exec docker-entrypoint.sh redis-server \
  --aclfile /run/redis/users.acl \
  --appendonly yes \
  --appendfsync everysec \
  --maxmemory 160mb \
  --maxmemory-policy noeviction \
  --save 300 10
