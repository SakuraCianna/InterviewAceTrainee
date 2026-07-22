# Named Volume 到项目 data 目录迁移

## 1. 目标与边界

本手册只用于把生产服务器上的三个 Docker named volume 迁移到项目根目录：

- `mianba-postgres-data` → `data/postgres`，保留全部 PostgreSQL 数据
- `mianba-redis-data` → `data/redis`，不复制缓存和向量索引，由 API 重建
- `mianba-rabbitmq-data` → `data/rabbitmq`，不复制节点数据，由 definitions 文件重建用户、vhost 和权限

迁移期间需要完整维护窗口。成功验证前不删除旧卷、不删除 `runtime-config/`、不修改 Secret；对外恢复后旧 PostgreSQL 卷只代表迁移时点快照，不能再作为在线回滚数据源。

## 2. 迁移前备份与基线

以下命令必须在仍使用旧 Compose 和 named volume 时执行：

```bash
set -Eeuo pipefail
cd /home/ubuntu/InterviewAceTrainee
export MIGRATION_SHA='<已通过CI的目标40位commit SHA>'
export BACKUP_DIR='/home/ubuntu/backups/InterviewAceTrainee'
export BACKUP_FILE="$BACKUP_DIR/mianba_prod_before_data_bind_$(date -u +%Y%m%dT%H%M%SZ).dump"

test "$(git branch --show-current)" = 'sakuracianna'
test -z "$(git status --porcelain)"
docker compose ps
install -d -m 700 "$BACKUP_DIR"

docker volume inspect mianba-postgres-data >/dev/null
test "$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql"}}{{.Name}}{{end}}{{end}}' mianba-postgres-1)" = 'mianba-postgres-data'
docker run --rm --entrypoint sh \
  --mount type=volume,source=mianba-postgres-data,target=/source,readonly \
  pgvector/pgvector:0.8.5-pg18-bookworm@sha256:12a379b47ad65289572ea0756efc11b7c241a6662833e8af7038cd3b73d647e0 \
  -c 'test "$(cat /source/18/docker/PG_VERSION)" = 18; test -s /source/18/docker/global/pg_control'

docker compose exec -T postgres pg_dump \
  --username mianba_owner --dbname mianba_prod --format=custom > "$BACKUP_FILE"
chmod 600 "$BACKUP_FILE"
sha256sum "$BACKUP_FILE" > "$BACKUP_FILE.sha256"
chmod 600 "$BACKUP_FILE.sha256"
docker compose exec -T postgres pg_restore --list < "$BACKUP_FILE" >/dev/null

docker compose exec -T postgres psql \
  --username mianba_owner --dbname mianba_prod --tuples-only --no-align \
  --command="SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' <<'SQL' \
  | sort > "$BACKUP_DIR/table_counts_before_data_bind.txt"
SELECT format(
  'SELECT %L || ''|'' || count(*) FROM %I.%I;',
  schemaname || '.' || tablename,
  schemaname,
  tablename
)
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename
\gexec
SQL
chmod 600 "$BACKUP_DIR/table_counts_before_data_bind.txt"
docker compose exec -T rabbitmq rabbitmqctl list_queues --quiet \
  name messages_ready messages_unacknowledged consumers \
  > "$BACKUP_DIR/rabbitmq_queues_before_data_bind.txt"
chmod 600 "$BACKUP_DIR/rabbitmq_queues_before_data_bind.txt"
docker compose exec -T rabbitmq rabbitmqctl list_queues --quiet \
  name messages_ready messages_unacknowledged \
  | awk 'NF >= 3 { ready += $2; unacked += $3 } END { exit !(ready == 0 && unacked == 0) }'
```

## 3. 停止旧拓扑

先停止入口和写入方。停写后再生成最终备份和精确基线，最后停止三类基础设施。不要执行 `down -v`：

```bash
set -Eeuo pipefail
docker compose stop nginx worker frontend api material-parser

export FINAL_BACKUP_FILE="$BACKUP_DIR/mianba_prod_stopped_before_data_bind_$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T postgres pg_dump \
  --username mianba_owner --dbname mianba_prod --format=custom > "$FINAL_BACKUP_FILE"
chmod 600 "$FINAL_BACKUP_FILE"
sha256sum "$FINAL_BACKUP_FILE" > "$FINAL_BACKUP_FILE.sha256"
chmod 600 "$FINAL_BACKUP_FILE.sha256"
docker compose exec -T postgres pg_restore --list < "$FINAL_BACKUP_FILE" >/dev/null
docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' <<'SQL' \
  | sort > "$BACKUP_DIR/table_counts_stopped_before_data_bind.txt"
SELECT format(
  'SELECT %L || ''|'' || count(*) FROM %I.%I;',
  schemaname || '.' || tablename,
  schemaname,
  tablename
)
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename
\gexec
SQL
chmod 600 "$BACKUP_DIR/table_counts_stopped_before_data_bind.txt"
docker compose exec -T rabbitmq rabbitmqctl list_queues --quiet \
  name messages_ready messages_unacknowledged consumers \
  > "$BACKUP_DIR/rabbitmq_queues_stopped_before_data_bind.txt"
chmod 600 "$BACKUP_DIR/rabbitmq_queues_stopped_before_data_bind.txt"
docker compose exec -T rabbitmq rabbitmqctl list_queues --quiet \
  name messages_ready messages_unacknowledged \
  | awk 'NF >= 3 { ready += $2; unacked += $3 } END { exit !(ready == 0 && unacked == 0) }'

docker compose stop rabbitmq redis postgres
docker compose ps --all
```

确认三个旧容器均已停止后才继续。

## 4. 准备新代码、镜像和目录

```bash
set -Eeuo pipefail
git fetch origin sakuracianna
test "$(git branch --show-current)" = 'sakuracianna'
git merge --ff-only "$MIGRATION_SHA"
test "$(git rev-parse HEAD)" = "$MIGRATION_SHA"

# 手动更新 .env：增加下面三个目标镜像，并暂时保留旧
# MIANBA_RUNTIME_CONFIG_DIR，直到新拓扑验证成功。
# MIANBA_POSTGRES_IMAGE=mianba-postgres:$MIGRATION_SHA
# MIANBA_REDIS_IMAGE=mianba-redis:$MIGRATION_SHA
# MIANBA_RABBITMQ_IMAGE=mianba-rabbitmq:$MIGRATION_SHA

install -d -m 750 data
install -d -m 750 data/postgres data/redis data/rabbitmq

test -z "$(find data/postgres -mindepth 1 -maxdepth 1 -print -quit)"
test -z "$(find data/redis -mindepth 1 -maxdepth 1 -print -quit)"
test -z "$(find data/rabbitmq -mindepth 1 -maxdepth 1 -print -quit)"

docker compose build postgres redis rabbitmq
docker image inspect \
  "mianba-postgres:$MIGRATION_SHA" \
  "mianba-redis:$MIGRATION_SHA" \
  "mianba-rabbitmq:$MIGRATION_SHA" >/dev/null
```

## 5. 冷复制 PostgreSQL 并初始化空缓存目录

下面只读取已经核验存在的旧 PostgreSQL 卷；目标目录在执行前必须为空，并再次确认 PG18 控制文件存在：

```bash
set -Eeuo pipefail
docker volume inspect mianba-postgres-data >/dev/null
docker run --rm --entrypoint sh \
  --mount type=volume,source=mianba-postgres-data,target=/source,readonly \
  pgvector/pgvector:0.8.5-pg18-bookworm@sha256:12a379b47ad65289572ea0756efc11b7c241a6662833e8af7038cd3b73d647e0 \
  -c 'test "$(cat /source/18/docker/PG_VERSION)" = 18; test -s /source/18/docker/global/pg_control'

docker run --rm --user 0:0 --entrypoint sh \
  --mount type=volume,source=mianba-postgres-data,target=/source,readonly \
  --mount type=bind,source="$PWD/data/postgres",target=/target \
  pgvector/pgvector:0.8.5-pg18-bookworm@sha256:12a379b47ad65289572ea0756efc11b7c241a6662833e8af7038cd3b73d647e0 \
  -c 'set -eu; cp -a /source/. /target/; chown 999:999 /target; chmod 0750 /target'

docker run --rm --user 0:0 --entrypoint sh \
  --mount type=bind,source="$PWD/data/redis",target=/target \
  redis:8.8.0-alpine@sha256:9d317178eceac8454a2284a9e6df2466b93c745529947f0cd42a0fa9609d7005 \
  -c 'chown 999:1000 /target; chmod 0750 /target'

docker run --rm --user 0:0 --entrypoint sh \
  --mount type=bind,source="$PWD/data/rabbitmq",target=/target \
  rabbitmq:4.3.2-management@sha256:c1e33461287f4c53049ce525575f0df77b2a00a88e2593776afbb6c1489bdb81 \
  -c 'chown 999:999 /target; chmod 0750 /target'

docker compose config --quiet
```

## 6. 启动与验证

```bash
set -Eeuo pipefail
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 180 postgres
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 180 redis
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 180 rabbitmq
docker compose ps postgres redis rabbitmq

docker compose run --rm --no-deps migrate
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 180 material-parser
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 240 api
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 120 frontend
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 120 worker

docker compose exec -T postgres psql \
  --username mianba_owner --dbname mianba_prod --tuples-only --no-align \
  --command="SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping
docker compose exec -T api curl --fail --silent --show-error \
  http://127.0.0.1:8000/actuator/health/readiness >/dev/null
test "$(docker compose exec -T postgres psql \
  --username mianba_owner --dbname mianba_prod --tuples-only --no-align \
  --command="SELECT consumers_ready AND rabbit_ready AND CURRENT_TIMESTAMP - updated_at < interval '30 seconds' FROM runtime_heartbeats WHERE role = 'worker';")" = 't'
test "$(docker compose exec -T postgres psql \
  --username mianba_owner --dbname mianba_prod --tuples-only --no-align \
  --command="SELECT status = 'READY' AND document_count > 0 AND chunk_count > 0 AND chunk_count = indexed_chunk_count AND failure_count = 0 FROM knowledge_index_state WHERE singleton_id = 1;")" = 't'
docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' <<'SQL' \
  | sort > "$BACKUP_DIR/table_counts_after_data_bind.txt"
SELECT format(
  'SELECT %L || ''|'' || count(*) FROM %I.%I;',
  schemaname || '.' || tablename,
  schemaname,
  tablename
)
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename
\gexec
SQL
chmod 600 "$BACKUP_DIR/table_counts_after_data_bind.txt"
diff -u \
  "$BACKUP_DIR/table_counts_stopped_before_data_bind.txt" \
  "$BACKUP_DIR/table_counts_after_data_bind.txt"
docker compose exec -T rabbitmq rabbitmqctl list_queues --quiet \
  name messages_ready messages_unacknowledged consumers

docker inspect -f '{{range .Mounts}}{{println .Type .Source .Destination}}{{end}}' \
  mianba-postgres-1 mianba-redis-1 mianba-rabbitmq-1

# 内部数据、挂载、索引和 Worker 心跳的失败关闭断言全部通过后，才跨越恢复流量边界。
docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 120 nginx
docker compose exec -T nginx nginx -t
curl --fail --silent --show-error --max-time 10 \
  https://sakuracianna.icu/api/health >/dev/null
```

启动 Nginx 是恢复外部写入的边界。执行前必须确认数据库表行数、Flyway、挂载、知识索引和 Worker 心跳与迁移前基线一致；启动后完成用户登录与核心业务人工验证，但不得再用旧卷回滚在线数据。

## 7. 清理与回滚边界

新拓扑验证成功后，从 `.env` 删除已经不再使用的 `MIANBA_RUNTIME_CONFIG_DIR`，再对 `runtime-config` 做精确路径校验并删除：

```bash
set -Eeuo pipefail
test "$(realpath runtime-config)" = "$PWD/runtime-config"
find runtime-config -depth -delete
test ! -e runtime-config
```

旧 named volumes 暂时保留，不执行删除。若在恢复对外流量前验证失败，可以停止新容器、切回旧提交并使用仍保留的 `runtime-config/` 和旧卷恢复；一旦新数据库恢复写入，旧卷立即成为过期快照，后续回滚必须使用 `data/postgres` 或逻辑备份，禁止直接切回旧卷。
