#!/usr/bin/env bash
set -Eeuo pipefail

# 该契约测试只在本机/CI 使用；生产 runtime allowlist 不包含 deploy/tests。
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "production compose contract failed: $*" >&2
  exit 1
}

write_valid_compose_env() {
  local destination="$1"
  cat > "$destination" <<'EOF'
# Java API 与 Worker 镜像, 发布脚本会覆盖为不可变 SHA 标签
MIANBA_APP_IMAGE=ignored-app-image

# 前端静态镜像, 发布脚本会覆盖为不可变 SHA 标签
MIANBA_FRONTEND_IMAGE=ignored-frontend-image

# PostgreSQL 镜像, 发布脚本会覆盖为本地 SHA 标签
MIANBA_POSTGRES_IMAGE=ignored-postgres-image

# Redis 镜像, 发布脚本会覆盖为本地 SHA 标签
MIANBA_REDIS_IMAGE=ignored-redis-image

# RabbitMQ 镜像, 发布脚本会覆盖为本地 SHA 标签
MIANBA_RABBITMQ_IMAGE=ignored-rabbitmq-image

# Nginx 镜像, 发布脚本会覆盖为本地 SHA 标签
MIANBA_NGINX_IMAGE=ignored-nginx-image

# 服务器 secret 目录, 发布脚本按生产根目录派生
MIANBA_SECRETS_DIR=/ignored/secrets

# 服务器证书目录, 发布脚本按生产根目录派生
MIANBA_CERTS_DIR=/ignored/certs

# 服务器稳定运行配置目录, 发布脚本按生产根目录派生
MIANBA_RUNTIME_CONFIG_DIR=/ignored/runtime-config

# 正式环境允许跨域来源, 此值只来自服务器配置
MIANBA_CORS_ALLOWED_ORIGINS=https://server.example.invalid

# API 数据库连接池上限, 此值只来自服务器配置
API_DB_POOL_SIZE=7

# API 最小空闲数据库连接, 此值只来自服务器配置
API_DB_MIN_IDLE=2

# Worker 数据库连接池上限, 此值只来自服务器配置
WORKER_DB_POOL_SIZE=5

# Worker 最小空闲数据库连接, 此值只来自服务器配置
WORKER_DB_MIN_IDLE=2

# 数据库连接等待毫秒数, 此值只来自服务器配置
DB_CONNECTION_TIMEOUT_MS=4321

# API 最大工作线程数, 此值只来自服务器配置
API_TOMCAT_MAX_THREADS=71

# API 等待队列长度, 此值只来自服务器配置
API_TOMCAT_ACCEPT_COUNT=37

# Worker 消费者并发数, 此值只来自服务器配置
WORKER_CONSUMER_CONCURRENCY=2

# Worker 最大消费者并发数, 此值只来自服务器配置
WORKER_CONSUMER_MAX_CONCURRENCY=3
EOF
  chmod 0600 "$destination"
}

expect_env_rejected() {
  local candidate="$1"
  local label="$2"
  if bash "$workspace/deploy/validate-compose-env.sh" "$candidate" >/dev/null 2>&1; then
    fail "$label compose env was accepted"
  fi
}

sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
production_root="$temporary_root/production"
release_dir="$production_root/releases/$sha"
mkdir -p \
  "$release_dir/deploy" \
  "$production_root/shared/secrets" \
  "$production_root/shared/certs" \
  "$production_root/shared/runtime-config" \
  "$temporary_root/bin"
printf '%s\n' "$sha" > "$release_dir/commit.txt"
printf 'services: {}\n' > "$release_dir/docker-compose.yml"
cp -- "$workspace/deploy/validate-compose-env.sh" "$release_dir/deploy/validate-compose-env.sh"
ln -s -- "$release_dir" "$production_root/current"
compose_env_file="$production_root/shared/compose.env"
write_valid_compose_env "$compose_env_file"

bash "$workspace/deploy/validate-compose-env.sh" "$compose_env_file" >/dev/null \
  || fail "valid compose env was rejected"

missing_env="$temporary_root/missing.env"
expect_env_rejected "$missing_env" "missing"

symlink_target="$temporary_root/symlink-target.env"
write_valid_compose_env "$symlink_target"
symlink_env="$temporary_root/symlink.env"
ln -s -- "$symlink_target" "$symlink_env"
expect_env_rejected "$symlink_env" "symlink"

permissive_env="$temporary_root/permissive.env"
write_valid_compose_env "$permissive_env"
chmod 0644 "$permissive_env"
expect_env_rejected "$permissive_env" "permissive mode"

missing_field_env="$temporary_root/missing-field.env"
awk 'BEGIN { RS=""; ORS="\n\n" } $0 !~ /API_DB_POOL_SIZE=/' "$compose_env_file" > "$missing_field_env"
chmod 0600 "$missing_field_env"
expect_env_rejected "$missing_field_env" "missing field"

extra_field_env="$temporary_root/extra-field.env"
cp -- "$compose_env_file" "$extra_field_env"
printf '\n# 额外字段, 生产配置禁止\nEXTRA_FIELD=1\n' >> "$extra_field_env"
chmod 0600 "$extra_field_env"
expect_env_rejected "$extra_field_env" "extra field"

reordered_env="$temporary_root/reordered.env"
awk 'BEGIN { RS=""; ORS="\n\n" } NR == 11 { held=$0; next } NR == 12 { print; print held; next } { print }' \
  "$compose_env_file" > "$reordered_env"
chmod 0600 "$reordered_env"
expect_env_rejected "$reordered_env" "reordered"

non_chinese_comment_env="$temporary_root/non-chinese-comment.env"
awk 'NR == 1 { print "# immutable application image, deployment controlled"; next } { print }' \
  "$compose_env_file" > "$non_chinese_comment_env"
chmod 0600 "$non_chinese_comment_env"
expect_env_rejected "$non_chinese_comment_env" "non-Chinese comment"

chinese_punctuation_env="$temporary_root/chinese-punctuation.env"
awk 'NR == 1 { print "# Java 镜像，部署脚本控制"; next } { print }' \
  "$compose_env_file" > "$chinese_punctuation_env"
chmod 0600 "$chinese_punctuation_env"
expect_env_rejected "$chinese_punctuation_env" "Chinese punctuation"

command_syntax_env="$temporary_root/command-syntax.env"
awk '/^MIANBA_CORS_ALLOWED_ORIGINS=/ { print "MIANBA_CORS_ALLOWED_ORIGINS=$(id)"; next } { print }' \
  "$compose_env_file" > "$command_syntax_env"
chmod 0600 "$command_syntax_env"
expect_env_rejected "$command_syntax_env" "command syntax"

crlf_env="$temporary_root/crlf.env"
cp -- "$compose_env_file" "$crlf_env"
printf '\r' >> "$crlf_env"
chmod 0600 "$crlf_env"
expect_env_rejected "$crlf_env" "carriage return"

cat > "$temporary_root/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
: "${MIANBA_TEST_DOCKER_CAPTURE:?MIANBA_TEST_DOCKER_CAPTURE is required}"
env_file=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--env-file" ]]; then
    env_file="$argument"
  fi
  previous="$argument"
done
{
  for argument in "$@"; do
    printf 'ARG:%s\n' "$argument"
  done
  printf 'APP:%s\n' "${MIANBA_APP_IMAGE:-}"
  printf 'FRONTEND:%s\n' "${MIANBA_FRONTEND_IMAGE:-}"
  printf 'POSTGRES:%s\n' "${MIANBA_POSTGRES_IMAGE:-}"
  printf 'REDIS:%s\n' "${MIANBA_REDIS_IMAGE:-}"
  printf 'RABBITMQ:%s\n' "${MIANBA_RABBITMQ_IMAGE:-}"
  printf 'NGINX:%s\n' "${MIANBA_NGINX_IMAGE:-}"
  printf 'SECRETS:%s\n' "${MIANBA_SECRETS_DIR:-}"
  printf 'CERTS:%s\n' "${MIANBA_CERTS_DIR:-}"
  printf 'RUNTIME_CONFIG:%s\n' "${MIANBA_RUNTIME_CONFIG_DIR:-}"
  for name in \
    MIANBA_CORS_ALLOWED_ORIGINS \
    API_DB_POOL_SIZE API_DB_MIN_IDLE WORKER_DB_POOL_SIZE WORKER_DB_MIN_IDLE \
    DB_CONNECTION_TIMEOUT_MS API_TOMCAT_MAX_THREADS API_TOMCAT_ACCEPT_COUNT \
    WORKER_CONSUMER_CONCURRENCY WORKER_CONSUMER_MAX_CONCURRENCY; do
    if [[ -v "$name" ]]; then
      printf 'PARENT:%s:present\n' "$name"
    else
      printf 'PARENT:%s:unset\n' "$name"
    fi
    grep -E "^${name}=" "$env_file" | sed 's/^/FILE:/'
  done
  printf '%s\n' 'STDIN:'
  cat
} > "$MIANBA_TEST_DOCKER_CAPTURE"
MOCK
chmod 0755 "$temporary_root/bin/docker"

export PATH="$temporary_root/bin:$PATH"
export MIANBA_PRODUCTION_ROOT="$production_root"
export MIANBA_TEST_DOCKER_CAPTURE="$temporary_root/docker.capture"
export MIANBA_APP_IMAGE='polluted-app-image'
export MIANBA_FRONTEND_IMAGE='polluted-frontend-image'
export MIANBA_POSTGRES_IMAGE='polluted-postgres-image'
export MIANBA_REDIS_IMAGE='polluted-redis-image'
export MIANBA_RABBITMQ_IMAGE='polluted-rabbitmq-image'
export MIANBA_NGINX_IMAGE='polluted-nginx-image'
export MIANBA_SECRETS_DIR='/polluted/secrets'
export MIANBA_CERTS_DIR='/polluted/certs'
export MIANBA_RUNTIME_CONFIG_DIR='/polluted/runtime-config'
export MIANBA_CORS_ALLOWED_ORIGINS='https://polluted.example.invalid'
export API_DB_POOL_SIZE=999
export API_DB_MIN_IDLE=999
export WORKER_DB_POOL_SIZE=999
export WORKER_DB_MIN_IDLE=999
export DB_CONNECTION_TIMEOUT_MS=999
export API_TOMCAT_MAX_THREADS=999
export API_TOMCAT_ACCEPT_COUNT=999
export WORKER_CONSUMER_CONCURRENCY=999
export WORKER_CONSUMER_MAX_CONCURRENCY=999

bash "$workspace/deploy/production-compose.sh" ps --services
for expected in \
  'ARG:compose' \
  'ARG:--project-name' \
  'ARG:mianba' \
  'ARG:--env-file' \
  "ARG:$compose_env_file" \
  "ARG:$release_dir" \
  'ARG:ps' \
  'ARG:--services' \
  "APP:mianba-java:$sha" \
  "FRONTEND:mianba-frontend:$sha" \
  "POSTGRES:mianba-postgres:$sha" \
  "REDIS:mianba-redis:$sha" \
  "RABBITMQ:mianba-rabbitmq:$sha" \
  "NGINX:mianba-nginx:$sha" \
  "SECRETS:$production_root/shared/secrets" \
  "CERTS:$production_root/shared/certs" \
  "RUNTIME_CONFIG:$production_root/shared/runtime-config"; do
  grep -Fxq -- "$expected" "$MIANBA_TEST_DOCKER_CAPTURE" \
    || fail "missing captured value: $expected"
done

for expected in \
  'FILE:MIANBA_CORS_ALLOWED_ORIGINS=https://server.example.invalid' \
  'FILE:API_DB_POOL_SIZE=7' \
  'FILE:API_DB_MIN_IDLE=2' \
  'FILE:WORKER_DB_POOL_SIZE=5' \
  'FILE:WORKER_DB_MIN_IDLE=2' \
  'FILE:DB_CONNECTION_TIMEOUT_MS=4321' \
  'FILE:API_TOMCAT_MAX_THREADS=71' \
  'FILE:API_TOMCAT_ACCEPT_COUNT=37' \
  'FILE:WORKER_CONSUMER_CONCURRENCY=2' \
  'FILE:WORKER_CONSUMER_MAX_CONCURRENCY=3'; do
  grep -Fxq -- "$expected" "$MIANBA_TEST_DOCKER_CAPTURE" \
    || fail "server compose env value was not consumed: $expected"
done
for name in \
  MIANBA_CORS_ALLOWED_ORIGINS \
  API_DB_POOL_SIZE API_DB_MIN_IDLE WORKER_DB_POOL_SIZE WORKER_DB_MIN_IDLE \
  DB_CONNECTION_TIMEOUT_MS API_TOMCAT_MAX_THREADS API_TOMCAT_ACCEPT_COUNT \
  WORKER_CONSUMER_CONCURRENCY WORKER_CONSUMER_MAX_CONCURRENCY; do
  grep -Fxq -- "PARENT:$name:unset" "$MIANBA_TEST_DOCKER_CAPTURE" \
    || fail "parent environment can override server compose env field: $name"
done

mv -- "$compose_env_file" "$temporary_root/compose.env.saved"
rm -f -- "$MIANBA_TEST_DOCKER_CAPTURE"
if bash "$workspace/deploy/production-compose.sh" ps >/dev/null 2>&1; then
  fail "production helper accepted a missing server compose env"
fi
[[ ! -e "$MIANBA_TEST_DOCKER_CAPTURE" ]] \
  || fail "Docker was invoked without a validated server compose env"
mv -- "$temporary_root/compose.env.saved" "$compose_env_file"

for name in \
  MIANBA_APP_IMAGE MIANBA_FRONTEND_IMAGE MIANBA_POSTGRES_IMAGE MIANBA_REDIS_IMAGE \
  MIANBA_RABBITMQ_IMAGE MIANBA_NGINX_IMAGE MIANBA_SECRETS_DIR MIANBA_CERTS_DIR \
  MIANBA_RUNTIME_CONFIG_DIR MIANBA_CORS_ALLOWED_ORIGINS API_DB_POOL_SIZE API_DB_MIN_IDLE \
  WORKER_DB_POOL_SIZE WORKER_DB_MIN_IDLE DB_CONNECTION_TIMEOUT_MS API_TOMCAT_MAX_THREADS \
  API_TOMCAT_ACCEPT_COUNT WORKER_CONSUMER_CONCURRENCY WORKER_CONSUMER_MAX_CONCURRENCY; do
  grep -Fq -- "\${$name:?" "$workspace/docker-compose.yml" \
    || fail "Compose field is not fail-closed: $name"
done

rm -f -- "$MIANBA_TEST_DOCKER_CAPTURE"
if bash "$workspace/deploy/production-compose.sh" up -d >/dev/null 2>&1; then
  fail "state-changing Compose command was accepted"
fi
[[ ! -e "$MIANBA_TEST_DOCKER_CAPTURE" ]] \
  || fail "Docker was invoked for a rejected state-changing command"

outside_release="$temporary_root/outside/$sha"
mkdir -p "$outside_release"
printf '%s\n' "$sha" > "$outside_release/commit.txt"
printf 'services: {}\n' > "$outside_release/docker-compose.yml"
rm -- "$production_root/current"
ln -s -- "$outside_release" "$production_root/current"
if bash "$workspace/deploy/production-compose.sh" ps >/dev/null 2>&1; then
  fail "current pointer outside releases was accepted"
fi
rm -- "$production_root/current"
ln -s -- "$release_dir" "$production_root/current"

bootstrap_file="$production_root/shared/secrets/bootstrap_admin_email"
printf '%s\n' 'bootstrap@example.invalid' > "$bootstrap_file"
chmod 0600 "$bootstrap_file"
promotion_output="$temporary_root/promotion.stdout"
MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE="$bootstrap_file" \
  sh "$workspace/deploy/promote-first-admin.sh" > "$promotion_output"
grep -Fxq 'ARG:exec' "$MIANBA_TEST_DOCKER_CAPTURE" \
  || fail "administrator promotion did not use the controlled Compose helper"
grep -Fxq 'ARG:postgres' "$MIANBA_TEST_DOCKER_CAPTURE" \
  || fail "administrator promotion did not target PostgreSQL"
grep -Fq 'first_admin_bootstrap' "$MIANBA_TEST_DOCKER_CAPTURE" \
  || fail "administrator promotion SQL was not sent through standard input"
grep -Fq 'pg_advisory_xact_lock' "$MIANBA_TEST_DOCKER_CAPTURE" \
  || fail "administrator promotion did not serialize first-admin bootstrap"
if grep -Fq 'bootstrap@example.invalid' "$promotion_output"; then
  fail "administrator email was printed to standard output"
fi

echo "Production Compose contract passed."
