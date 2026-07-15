#!/usr/bin/env bash
set -Eeuo pipefail

# 为当前不可变 release 注入完整镜像与共享目录变量，避免人工拼接 Compose 环境导致版本漂移。
: "${MIANBA_PRODUCTION_ROOT:?MIANBA_PRODUCTION_ROOT is required}"
(( $# > 0 )) || { echo "Usage: production-compose.sh <compose-command> [arguments...]" >&2; exit 2; }

fail() {
  echo "Production Compose command refused: $*" >&2
  exit 1
}

readonly -a COMPOSE_CONTRACT_FIELDS=(
  MIANBA_APP_IMAGE MIANBA_FRONTEND_IMAGE MIANBA_SECRETS_DIR MIANBA_CERTS_DIR
  MIANBA_RUNTIME_CONFIG_DIR MIANBA_CORS_ALLOWED_ORIGINS API_DB_POOL_SIZE API_DB_MIN_IDLE
  WORKER_DB_POOL_SIZE WORKER_DB_MIN_IDLE DB_CONNECTION_TIMEOUT_MS API_TOMCAT_MAX_THREADS
  API_TOMCAT_ACCEPT_COUNT WORKER_CONSUMER_CONCURRENCY WORKER_CONSUMER_MAX_CONCURRENCY
)

root="$MIANBA_PRODUCTION_ROOT"
[[ "$root" =~ ^(/[A-Za-z0-9][A-Za-z0-9._-]*){2,}$ ]] \
  || fail "production root is not a safe absolute path"
[[ "$(realpath -m -- "$root")" == "$root" && -d "$root" && ! -L "$root" ]] \
  || fail "production root is not normalized or is a symbolic link"

current_link="$root/current"
releases_dir="$root/releases"
secrets_dir="$root/shared/secrets"
certs_dir="$root/shared/certs"
runtime_config_dir="$root/shared/runtime-config"
compose_env_file="$root/shared/compose.env"
[[ -L "$current_link" ]] || fail "current release pointer is missing"
release_dir="$(readlink -f -- "$current_link")"
release_sha="$(basename -- "$release_dir")"
[[ "$release_sha" =~ ^[0-9a-f]{40}$ ]] || fail "current release SHA is invalid"
[[ "$(dirname -- "$release_dir")" == "$releases_dir" ]] \
  || fail "current release is outside the immutable release directory"
[[ -d "$release_dir" && ! -L "$release_dir" ]] || fail "current release directory is invalid"
[[ -f "$release_dir/commit.txt" && ! -L "$release_dir/commit.txt" ]] \
  || fail "current release commit marker is missing"
[[ "$(tr -d '\r\n' < "$release_dir/commit.txt")" == "$release_sha" ]] \
  || fail "current release commit marker does not match its directory"
compose_env_validator="$release_dir/deploy/validate-compose-env.sh"
[[ -f "$compose_env_validator" && ! -L "$compose_env_validator" ]] \
  || fail "current release is missing the compose env validator"
bash "$compose_env_validator" "$compose_env_file"

for directory in "$secrets_dir" "$certs_dir" "$runtime_config_dir"; do
  [[ -d "$directory" && ! -L "$directory" ]] || fail "required shared directory is invalid: $directory"
done

# 只开放观察和受控容器内诊断；发布、删除、拉取与重建必须继续走部署脚本。
case "$1" in
  config|exec|images|logs|ps|stats|top) ;;
  *) fail "unsupported command '$1'; use the release scripts for state-changing operations" ;;
esac

sanitized_environment=(env)
for field in "${COMPOSE_CONTRACT_FIELDS[@]}"; do
  sanitized_environment+=(-u "$field")
done
exec "${sanitized_environment[@]}" \
  MIANBA_APP_IMAGE="mianba-java:$release_sha" \
  MIANBA_FRONTEND_IMAGE="mianba-frontend:$release_sha" \
  MIANBA_SECRETS_DIR="$secrets_dir" \
  MIANBA_CERTS_DIR="$certs_dir" \
  MIANBA_RUNTIME_CONFIG_DIR="$runtime_config_dir" \
  docker compose \
  --env-file "$compose_env_file" \
  --project-name mianba \
  --project-directory "$release_dir" \
  --file "$release_dir/docker-compose.yml" \
  "$@"
