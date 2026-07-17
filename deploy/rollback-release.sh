#!/usr/bin/env bash
set -Eeuo pipefail

# 外部健康探测失败时，只允许从当前候选 SHA 恢复到激活脚本保存的 previous。
: "${MIANBA_PRODUCTION_ROOT:?MIANBA_PRODUCTION_ROOT is required}"
: "${MIANBA_FAILED_DEPLOY_SHA:?MIANBA_FAILED_DEPLOY_SHA is required}"

fail() {
  echo "Rollback refused: $*" >&2
  return 1
}

readonly -a COMPOSE_CONTRACT_FIELDS=(
  MIANBA_APP_IMAGE MIANBA_FRONTEND_IMAGE MIANBA_SECRETS_DIR MIANBA_CERTS_DIR
  MIANBA_RUNTIME_CONFIG_DIR MIANBA_CORS_ALLOWED_ORIGINS API_DB_POOL_SIZE API_DB_MIN_IDLE
  WORKER_DB_POOL_SIZE WORKER_DB_MIN_IDLE DB_CONNECTION_TIMEOUT_MS API_TOMCAT_MAX_THREADS
  API_TOMCAT_ACCEPT_COUNT WORKER_CONSUMER_CONCURRENCY WORKER_CONSUMER_MAX_CONCURRENCY
)

validate_release_dir() {
  local candidate="$1"
  local release_name
  release_name="$(basename -- "$candidate")"
  [[ "$release_name" =~ ^[0-9a-f]{40}$ ]] || return 1
  [[ "$(dirname -- "$candidate")" == "$releases_dir" ]] || return 1
  [[ "$(realpath -m -- "$candidate")" == "$candidate" ]] || return 1
  [[ -d "$candidate" && ! -L "$candidate" ]] || return 1
}

validate_release_identity() {
  local release_path="$1"
  local release_sha="$2"
  local marker
  validate_release_dir "$release_path" \
    || fail "release path is not a validated immutable release: $release_path"
  [[ "$(basename -- "$release_path")" == "$release_sha" ]] \
    || fail "release path and requested SHA disagree"
  [[ -f "$release_path/commit.txt" && ! -L "$release_path/commit.txt" ]] \
    || fail "release commit marker is missing"
  marker="$(tr -d '\r\n' < "$release_path/commit.txt")"
  [[ "$marker" == "$release_sha" ]] || fail "release commit marker does not match its directory"
}

compose_for() {
  local release_path="$1"
  local release_sha="$2"
  local field
  local -a sanitized_environment=(env)
  shift 2
  [[ -f "$compose_env_validator" && ! -L "$compose_env_validator" ]] \
    || fail "compose env validator is missing from the failed candidate release"
  bash "$compose_env_validator" "$compose_env_file"
  for field in "${COMPOSE_CONTRACT_FIELDS[@]}"; do
    sanitized_environment+=(-u "$field")
  done
  "${sanitized_environment[@]}" \
    MIANBA_APP_IMAGE="mianba-java:$release_sha" \
    MIANBA_FRONTEND_IMAGE="mianba-frontend:$release_sha" \
    MIANBA_SECRETS_DIR="$secrets_dir" \
    MIANBA_CERTS_DIR="$certs_dir" \
    MIANBA_RUNTIME_CONFIG_DIR="$runtime_config_dir" \
    docker compose \
      --env-file "$compose_env_file" \
      --project-name mianba \
      --project-directory "$release_path" \
      --file "$release_path/docker-compose.yml" \
      "$@"
}

validate_release_compose() {
  local release_path="$1"
  local release_sha="$2"
  validate_release_identity "$release_path" "$release_sha" \
    && compose_for "$release_path" "$release_sha" config --quiet
}

verify_release_images_present() {
  local release_sha="$1"
  local image
  local -a images=(
    "mianba-java:$release_sha"
    "mianba-frontend:$release_sha"
    "mianba-postgres:$release_sha"
    "mianba-redis:$release_sha"
    "mianba-rabbitmq:$release_sha"
    "mianba-nginx:$release_sha"
  )
  for image in "${images[@]}"; do
    docker image inspect "$image" >/dev/null 2>&1 || {
      echo "Required rollback image is missing: $image" >&2
      return 1
    }
  done
}

# 返回值为三态：0=存在 parser，1=明确不存在，2=Compose 配置无法解析。
release_has_material_parser() {
  local release_path="$1"
  local release_sha="$2"
  local services
  if ! services="$(compose_for "$release_path" "$release_sha" config --services)"; then
    echo "Compose config could not be parsed while detecting material-parser: $release_path" >&2
    return 2
  fi
  if grep -Fxq 'material-parser' <<< "$services"; then
    return 0
  fi
  return 1
}

start_material_parser_if_present() {
  local release_path="$1"
  local release_sha="$2"
  local parser_state
  if release_has_material_parser "$release_path" "$release_sha"; then
    compose_for "$release_path" "$release_sha" up -d --no-build --no-deps --pull never \
      --wait --wait-timeout 120 \
      material-parser
    return 0
  fi
  parser_state=$?
  (( parser_state == 1 )) || return "$parser_state"
}

wait_for_api_readiness() {
  local release_path="$1"
  local release_sha="$2"
  local attempt
  for attempt in $(seq 1 30); do
    if strict_api_readiness_probe "$release_path" "$release_sha"; then
      return 0
    fi
    sleep 4
  done
  return 1
}

wait_for_edge_readiness() {
  local release_path="$1"
  local release_sha="$2"
  local attempt
  for attempt in $(seq 1 20); do
    if strict_edge_readiness_probe "$release_path" "$release_sha"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

require_readiness_probe_tools() {
  command -v python3 >/dev/null 2>&1 \
    || fail "python3 is required for strict readiness JSON validation"
  command -v curl >/dev/null 2>&1 \
    || fail "curl is required for strict edge readiness verification"
}

validate_readiness_probe_output() {
  local probe_file="$1"
  [[ -f "$probe_file" && ! -L "$probe_file" ]] || return 1
  python3 - "$probe_file" <<'PY'
import json
import sys
from pathlib import Path

MAXIMUM_RESPONSE_BYTES = 65_536
REQUIRED_FLAGS = (
    "ready",
    "database_ready",
    "redis_ready",
    "rabbit_ready",
    "worker_ready",
    "parser_ready",
)


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def reject_non_standard_constant(_value):
    raise ValueError("non-standard JSON constant")


def invalid():
    raise SystemExit(1)


try:
    with Path(sys.argv[1]).open("rb") as stream:
        raw = stream.read(MAXIMUM_RESPONSE_BYTES + 5)
        if stream.read(1):
            invalid()
    status, separator, payload = raw.partition(b"\n")
    if separator != b"\n" or status != b"200" or not payload or len(payload) > MAXIMUM_RESPONSE_BYTES:
        invalid()
    document = json.loads(
        payload.decode("utf-8"),
        object_pairs_hook=reject_duplicate_keys,
        parse_constant=reject_non_standard_constant,
    )
except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
    invalid()

if type(document) is not dict:
    invalid()
if any(document.get(flag) is not True for flag in REQUIRED_FLAGS):
    invalid()
PY
}

strict_api_readiness_probe() {
  local release_path="$1"
  local release_sha="$2"
  local probe_file status=1
  probe_file="$(mktemp)" || return 1
  if compose_for "$release_path" "$release_sha" exec -T api sh -ec '
    response_file="$(mktemp)"
    cleanup() { rm -f -- "$response_file"; }
    trap cleanup EXIT HUP INT TERM
    http_status="$(curl --silent --show-error --connect-timeout 2 --max-time 5 --max-filesize 65536 \
      --output "$response_file" --write-out "%{http_code}" \
      http://127.0.0.1:8000/api/health/readiness)"
    printf "%s\n" "$http_status"
    cat -- "$response_file"
  ' > "$probe_file" && validate_readiness_probe_output "$probe_file"; then
    status=0
  fi
  rm -f -- "$probe_file"
  return "$status"
}

strict_edge_readiness_probe() {
  local release_path="$1"
  local release_sha="$2"
  local probe_file response_file http_status status=1
  probe_file="$(mktemp)" || return 1
  response_file="$(mktemp)" || {
    rm -f -- "$probe_file"
    return 1
  }
  if http_status="$(curl --silent --show-error --noproxy '*' --connect-timeout 2 --max-time 5 \
      --max-filesize 65536 --insecure --resolve 'sakuracianna.icu:443:127.0.0.1' \
      --output "$response_file" --write-out '%{http_code}' \
      https://sakuracianna.icu/api/health/readiness)" \
    && { printf '%s\n' "$http_status"; cat -- "$response_file"; } > "$probe_file" \
    && validate_readiness_probe_output "$probe_file"; then
    status=0
  fi
  rm -f -- "$probe_file" "$response_file"
  return "$status"
}

verify_application_containers_absent() {
  local service ids
  for service in "$@"; do
    if ! ids="$(docker ps --all --quiet \
      --filter 'label=com.docker.compose.project=mianba' \
      --filter "label=com.docker.compose.service=$service")"; then
      echo "Docker could not verify removal of Compose service: $service" >&2
      return 1
    fi
    if [[ -n "$ids" ]]; then
      echo "Compose service still has a container after cleanup: $service ($ids)" >&2
      return 1
    fi
  done
}

remove_candidate_application_containers() {
  local release_path="$1"
  local release_sha="$2"
  local parser_state stop_status=0 remove_status=0 verify_status=0
  local -a services=(nginx worker api frontend migrate)

  # 删除动作前必须确认目标 release 的 Compose 配置可完整解析。
  validate_release_compose "$release_path" "$release_sha" || return 1
  if release_has_material_parser "$release_path" "$release_sha"; then
    services+=(material-parser)
  else
    parser_state=$?
    (( parser_state == 1 )) || return "$parser_state"
  fi

  compose_for "$release_path" "$release_sha" stop --timeout 10 "${services[@]}" \
    || stop_status=$?
  compose_for "$release_path" "$release_sha" rm -f "${services[@]}" \
    || remove_status=$?
  verify_application_containers_absent "${services[@]}" || verify_status=$?
  if (( stop_status != 0 || remove_status != 0 || verify_status != 0 )); then
    echo "Candidate cleanup failed (stop=$stop_status, rm=$remove_status, verify=$verify_status)." >&2
    return 1
  fi
}

restore_release() {
  local release_path="$1"
  local release_sha="$2"
  validate_release_compose "$release_path" "$release_sha" \
    && verify_release_images_present "$release_sha" \
    && compose_for "$release_path" "$release_sha" up -d --no-build --no-recreate --pull never \
      --wait --wait-timeout 240 \
      postgres redis rabbitmq \
    && start_material_parser_if_present "$release_path" "$release_sha" \
    && compose_for "$release_path" "$release_sha" up -d --no-build --no-deps --pull never \
      --wait --wait-timeout 240 \
      api frontend \
    && compose_for "$release_path" "$release_sha" up -d --no-build --no-deps --pull never \
      --wait --wait-timeout 120 \
      worker \
    && [[ "$(compose_for "$release_path" "$release_sha" ps --services --status running worker)" == "worker" ]] \
    && compose_for "$release_path" "$release_sha" up -d --no-build --no-deps --pull never \
      --wait --wait-timeout 120 \
      nginx \
    && wait_for_api_readiness "$release_path" "$release_sha" \
    && wait_for_edge_readiness "$release_path" "$release_sha"
}

replace_pointer() {
  local pointer="$1"
  local target="$2"
  local temporary="$3"
  [[ "$(dirname -- "$pointer")" == "$root" && "$(dirname -- "$temporary")" == "$root" ]] || return 1
  [[ ! -e "$temporary" && ! -L "$temporary" ]] || return 1
  ln -s -- "$target" "$temporary" && mv -Tf -- "$temporary" "$pointer"
}

remove_pointer() {
  local pointer="$1"
  [[ "$(dirname -- "$pointer")" == "$root" ]] || return 1
  if [[ -e "$pointer" || -L "$pointer" ]]; then
    [[ -L "$pointer" ]] || return 1
    rm -- "$pointer"
  fi
}

pointer_matches() {
  local pointer="$1"
  local target="$2"
  [[ -L "$pointer" && "$(readlink -f -- "$pointer")" == "$target" ]]
}

cleanup_pointer_temps() {
  local temporary status=0
  for temporary in "$current_next" "$previous_next" "$current_restore" "$previous_restore"; do
    if [[ -e "$temporary" || -L "$temporary" ]]; then
      if [[ -L "$temporary" ]]; then
        rm -- "$temporary" || status=1
      else
        echo "Refusing to remove non-symlink pointer temporary: $temporary" >&2
        status=1
      fi
    fi
  done
  return "$status"
}

restore_original_pointers_once() {
  local status=0
  cleanup_pointer_temps || status=1
  if (( original_current_present == 1 )); then
    replace_pointer "$current_link" "$original_current_target" "$current_restore" || status=1
  else
    remove_pointer "$current_link" || status=1
  fi
  if (( original_previous_present == 1 )); then
    replace_pointer "$previous_link" "$original_previous_target" "$previous_restore" || status=1
  else
    remove_pointer "$previous_link" || status=1
  fi
  cleanup_pointer_temps || status=1
  if (( original_current_present == 1 )); then
    pointer_matches "$current_link" "$original_current_target" || status=1
  else
    [[ ! -e "$current_link" && ! -L "$current_link" ]] || status=1
  fi
  if (( original_previous_present == 1 )); then
    pointer_matches "$previous_link" "$original_previous_target" || status=1
  else
    [[ ! -e "$previous_link" && ! -L "$previous_link" ]] || status=1
  fi
  return "$status"
}

restore_original_pointers_bounded() {
  local attempt
  for attempt in 1 2; do
    if restore_original_pointers_once; then
      return 0
    fi
  done
  return 1
}

root="$MIANBA_PRODUCTION_ROOT"
failed_sha="$MIANBA_FAILED_DEPLOY_SHA"
[[ "$root" =~ ^(/[A-Za-z0-9][A-Za-z0-9._-]*){2,}$ ]] \
  || fail "production root is not a safe absolute path"
[[ "$(realpath -m -- "$root")" == "$root" && -d "$root" && ! -L "$root" ]] \
  || fail "production root is not normalized or is a symlink"
[[ "$failed_sha" =~ ^[0-9a-f]{40}$ ]] || fail "failed deploy SHA is invalid"
require_readiness_probe_tools

releases_dir="$root/releases"
candidate_release="$releases_dir/$failed_sha"
current_link="$root/current"
previous_link="$root/previous"
secrets_dir="$root/shared/secrets"
certs_dir="$root/shared/certs"
runtime_config_dir="$root/shared/runtime-config"
compose_env_file="$root/shared/compose.env"
compose_env_validator="$candidate_release/deploy/validate-compose-env.sh"
current_next="$root/current.next.$$"
previous_next="$root/previous.next.$$"
current_restore="$root/current.restore.$$"
previous_restore="$root/previous.restore.$$"
lock_file="$root/.deployment.lock"

for directory in "$releases_dir" "$root/shared" "$secrets_dir" "$certs_dir" "$runtime_config_dir"; do
  [[ -d "$directory" && ! -L "$directory" ]] || fail "required directory is missing or is a symlink: $directory"
done
for runtime_file in \
  "$runtime_config_dir/postgres-init/001-create-app-role.sh" \
  "$runtime_config_dir/redis-entrypoint.sh" \
  "$runtime_config_dir/rabbitmq.conf"; do
  [[ -f "$runtime_file" && ! -L "$runtime_file" ]] || fail "installed runtime config is missing: $runtime_file"
  [[ "$(stat -c '%a' "$runtime_file")" == "444" ]] \
    || fail "installed runtime config must use mode 0444: $runtime_file"
done
[[ ! -L "$lock_file" ]] || fail "deployment lock must not be a symbolic link"
exec 9>>"$lock_file"
flock -w 5 9 || fail "another deployment or rollback holds the production lock"

[[ -L "$current_link" ]] || fail "current release pointer is missing"
[[ "$(readlink -f -- "$current_link")" == "$candidate_release" ]] \
  || fail "current release is no longer the failed candidate"
validate_release_identity "$candidate_release" "$failed_sha"
[[ -f "$compose_env_validator" && ! -L "$compose_env_validator" ]] \
  || fail "failed candidate release is missing the compose env validator"
bash "$compose_env_validator" "$compose_env_file"
validate_release_compose "$candidate_release" "$failed_sha"

original_current_present=1
original_current_target="$candidate_release"
original_previous_present=0
original_previous_target=""
pointer_transaction_active=0
rollback_committed=0
candidate_transition_started=0

if [[ -e "$previous_link" || -L "$previous_link" ]]; then
  [[ -L "$previous_link" ]] || fail "previous must be a symbolic link"
  previous_release="$(readlink -f -- "$previous_link")"
  previous_sha="$(basename -- "$previous_release")"
  [[ "$previous_release" != "$candidate_release" ]] || fail "previous points back to the failed candidate"
  validate_release_identity "$previous_release" "$previous_sha"
  validate_release_compose "$previous_release" "$previous_sha"
  verify_release_images_present "$previous_sha"
  original_previous_present=1
  original_previous_target="$previous_release"
else
  previous_release=""
  previous_sha=""
fi

handle_failure() {
  local status="$1"
  local reason="$2"
  local pointer_status=0 candidate_restore_status=0
  trap - ERR INT TERM HUP
  set +e
  cleanup_pointer_temps || pointer_status=1
  if (( rollback_committed == 1 )); then
    echo "Rollback had already committed before $reason; the restored release remains active." >&2
    exit "$status"
  fi
  # 候选清理开始后，必须先恢复候选服务并验证 readiness，再恢复其原始指针。
  if (( candidate_transition_started == 1 )); then
    echo "Rollback failed ($reason); restoring the candidate before pointer compensation." >&2
    if restore_release "$candidate_release" "$failed_sha"; then
      restore_original_pointers_bounded || pointer_status=1
    else
      candidate_restore_status=1
      pointer_status=1
    fi
  fi
  if (( candidate_restore_status != 0 )); then
    echo "Candidate recovery also failed; pointers were not forced back to an unavailable release." >&2
  elif (( pointer_status != 0 )); then
    echo "Candidate services recovered, but pointer compensation needs manual repair." >&2
  else
    echo "Candidate services and original release pointers were restored after rollback failure." >&2
  fi
  exit "$status"
}

on_error() {
  local status=$?
  handle_failure "$status" "command error"
}

trap on_error ERR
trap 'handle_failure 130 "SIGINT"' INT
trap 'handle_failure 143 "SIGTERM"' TERM
trap 'handle_failure 129 "SIGHUP"' HUP

# 先删除并按 Compose label 复核候选应用容器，再恢复旧版本；数据层始终保留。
candidate_transition_started=1
remove_candidate_application_containers "$candidate_release" "$failed_sha"

if [[ -z "$previous_release" ]]; then
  # 首次发布无可恢复版本时，清理所有应用层服务并移除 current，保留数据层和候选 release 供排查。
  pointer_transaction_active=1
  remove_pointer "$current_link"
  [[ ! -e "$current_link" && ! -L "$current_link" ]] || fail "first-deployment current pointer was not removed"
  [[ ! -e "$previous_link" && ! -L "$previous_link" ]] || fail "first deployment unexpectedly has a previous pointer"
  rollback_committed=1
  pointer_transaction_active=0
  trap - ERR INT TERM HUP
  cleanup_pointer_temps || echo "Warning: pointer temporary cleanup needs manual inspection." >&2
  echo "First deployment failed external verification; all application services were removed."
  exit 0
fi

restore_release "$previous_release" "$previous_sha"
compose_for "$previous_release" "$previous_sha" ps

# current/previous 作为一个补偿式事务提交；任一更新失败会最多补偿两次。
pointer_transaction_active=1
replace_pointer "$current_link" "$previous_release" "$current_next"
pointer_matches "$current_link" "$previous_release" || fail "current rollback pointer could not be verified"
replace_pointer "$previous_link" "$candidate_release" "$previous_next"
pointer_matches "$previous_link" "$candidate_release" || fail "previous rollback pointer could not be verified"
pointer_matches "$current_link" "$previous_release" || fail "current pointer changed during rollback commit"
rollback_committed=1
pointer_transaction_active=0
trap - ERR INT TERM HUP
cleanup_pointer_temps || echo "Warning: pointer temporary cleanup needs manual inspection." >&2

echo "External verification rollback restored release $previous_sha."
