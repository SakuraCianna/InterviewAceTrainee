#!/usr/bin/env bash
set -Eeuo pipefail

# 该脚本只在生产服务器上激活 CI 生成的不可变发布物，不接收源码或测试文件。
: "${MIANBA_PRODUCTION_ROOT:?MIANBA_PRODUCTION_ROOT is required}"
: "${MIANBA_DEPLOY_SHA:?MIANBA_DEPLOY_SHA is required}"
: "${MIANBA_STAGE_ID:?MIANBA_STAGE_ID is required}"

fail() {
  echo "Deployment refused: $*" >&2
  return 1
}

readonly -a COMPOSE_CONTRACT_FIELDS=(
  MIANBA_APP_IMAGE MIANBA_FRONTEND_IMAGE MIANBA_SECRETS_DIR MIANBA_CERTS_DIR
  MIANBA_RUNTIME_CONFIG_DIR MIANBA_CORS_ALLOWED_ORIGINS API_DB_POOL_SIZE API_DB_MIN_IDLE
  WORKER_DB_POOL_SIZE WORKER_DB_MIN_IDLE DB_CONNECTION_TIMEOUT_MS API_TOMCAT_MAX_THREADS
  API_TOMCAT_ACCEPT_COUNT WORKER_CONSUMER_CONCURRENCY WORKER_CONSUMER_MAX_CONCURRENCY
)

validate_root() {
  local candidate="$1"
  [[ "$candidate" =~ ^(/[A-Za-z0-9][A-Za-z0-9._-]*){2,}$ ]] \
    || fail "production root must be an absolute path with at least two safe components"
  [[ "$(realpath -m -- "$candidate")" == "$candidate" ]] \
    || fail "production root must already be normalized"
  [[ -d "$candidate" && ! -L "$candidate" ]] \
    || fail "production root must be a real directory"
}

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
    || fail "compose env validator is missing from the immutable release"
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
      echo "Required release-local image is missing: $image" >&2
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

remove_exact_stage() {
  local candidate="$1"
  [[ "$candidate" == "$incoming_dir/$stage_id" ]] || return 1
  [[ "$(realpath -m -- "$candidate")" == "$candidate" ]] || return 1
  [[ -d "$candidate" && ! -L "$candidate" ]] || return 1
  rm -rf -- "$candidate"
}

remove_exact_release() {
  local candidate="$1"
  local release_sha
  validate_release_dir "$candidate" || return 1
  release_sha="$(basename -- "$candidate")"
  # release 目录和其精确镜像标签删除前，必须先验证该 release 的 Compose 配置。
  validate_release_compose "$candidate" "$release_sha" || return 1
  rm -rf -- "$candidate"
  if ! docker image rm \
    "mianba-java:$release_sha" \
    "mianba-frontend:$release_sha" \
    "mianba-postgres:$release_sha" \
    "mianba-redis:$release_sha" \
    "mianba-rabbitmq:$release_sha" \
    "mianba-nginx:$release_sha" >/dev/null 2>&1; then
    echo "Release directory was removed, but one or more exact SHA image tags are still in use: $release_sha" >&2
  fi
}

require_docker_disk_headroom() {
  local image_archive="$1"
  local docker_root available_kib archive_kib required_kib
  docker_root="$(docker info --format '{{.DockerRootDir}}')"
  [[ "$docker_root" == /* && -d "$docker_root" ]] || fail "Docker root directory is unavailable"
  available_kib="$(df -Pk -- "$docker_root" | awk 'NR == 2 { print $4 }')"
  [[ "$available_kib" =~ ^[0-9]+$ ]] || fail "Docker free space could not be determined"
  archive_kib=$((($(stat -c '%s' "$image_archive") + 1023) / 1024))
  required_kib=$((archive_kib + 1024 * 1024))
  (( available_kib >= required_kib )) \
    || fail "Docker storage needs the image archive size plus 1 GiB of free space"
}

prune_old_releases() {
  local protected_current="$1"
  local protected_previous="$2"
  local keep_limit=5
  local keep_count=0
  local name path prune_status=0
  declare -A keep=()
  local -a release_names=()

  keep["$protected_current"]=1
  keep_count=1
  if [[ -n "$protected_previous" && -z "${keep[$protected_previous]+x}" ]]; then
    keep["$protected_previous"]=1
    keep_count=$((keep_count + 1))
  fi

  mapfile -t release_names < <(
    find "$releases_dir" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' \
      | sort -rn | awk '{print $2}'
  )
  for name in "${release_names[@]}"; do
    [[ "$name" =~ ^[0-9a-f]{40}$ ]] || continue
    path="$releases_dir/$name"
    validate_release_dir "$path" || continue
    if [[ -n "${keep[$path]+x}" ]]; then
      continue
    fi
    if (( keep_count < keep_limit )); then
      keep["$path"]=1
      keep_count=$((keep_count + 1))
      continue
    fi
    remove_exact_release "$path" || prune_status=1
  done
  return "$prune_status"
}

prepare_runtime_config_dir() {
  if [[ -e "$runtime_config_dir" || -L "$runtime_config_dir" ]]; then
    [[ -d "$runtime_config_dir" && ! -L "$runtime_config_dir" ]] \
      || fail "runtime-config must be a real directory"
  else
    mkdir -m 0750 -- "$runtime_config_dir"
  fi
  if [[ -e "$runtime_config_dir/postgres-init" || -L "$runtime_config_dir/postgres-init" ]]; then
    [[ -d "$runtime_config_dir/postgres-init" && ! -L "$runtime_config_dir/postgres-init" ]] \
      || fail "runtime-config/postgres-init must be a real directory"
  else
    mkdir -m 0750 -- "$runtime_config_dir/postgres-init"
  fi
  chmod 0750 "$runtime_config_dir" "$runtime_config_dir/postgres-init"
}

install_runtime_config_file() {
  local source="$1"
  local destination="$2"
  local temporary="$destination.install.$$"
  [[ -f "$source" && ! -L "$source" ]] || fail "runtime config source is invalid: $source"

  if [[ -e "$destination" || -L "$destination" ]]; then
    [[ -f "$destination" && ! -L "$destination" ]] \
      || fail "runtime config target is not a regular file: $destination"
    cmp -s -- "$source" "$destination" \
      || fail "runtime config differs from the installed copy; use a separate infrastructure maintenance procedure: $destination"
    [[ "$(stat -c '%a' "$destination")" == "444" ]] \
      || fail "installed runtime config must use mode 0444: $destination"
    return
  fi

  [[ ! -e "$temporary" && ! -L "$temporary" ]] \
    || fail "runtime config installation temporary already exists: $temporary"
  runtime_install_temps+=("$temporary")
  install -m 0444 -- "$source" "$temporary"
  ln -- "$temporary" "$destination"
  rm -- "$temporary"
}

install_runtime_config() {
  prepare_runtime_config_dir
  install_runtime_config_file \
    "$release_dir/deploy/postgres-init/001-create-app-role.sh" \
    "$runtime_config_dir/postgres-init/001-create-app-role.sh"
  install_runtime_config_file \
    "$release_dir/deploy/redis-entrypoint.sh" \
    "$runtime_config_dir/redis-entrypoint.sh"
  install_runtime_config_file \
    "$release_dir/deploy/rabbitmq.conf" \
    "$runtime_config_dir/rabbitmq.conf"
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

cleanup_runtime_install_temps() {
  local temporary status=0
  for temporary in "${runtime_install_temps[@]}"; do
    if [[ -e "$temporary" || -L "$temporary" ]]; then
      [[ -f "$temporary" && ! -L "$temporary" ]] || {
        status=1
        continue
      }
      rm -- "$temporary" || status=1
    fi
  done
  return "$status"
}

root="$MIANBA_PRODUCTION_ROOT"
deploy_sha="$MIANBA_DEPLOY_SHA"
stage_id="$MIANBA_STAGE_ID"
[[ "$deploy_sha" =~ ^[0-9a-f]{40}$ ]] || fail "deploy SHA must contain 40 lowercase hexadecimal characters"
[[ "$stage_id" =~ ^${deploy_sha}-[0-9]+-[0-9]+$ ]] || fail "stage id does not belong to this workflow run"
validate_root "$root"
require_readiness_probe_tools

incoming_dir="$root/.incoming"
releases_dir="$root/releases"
stage="$incoming_dir/$stage_id"
release_dir="$releases_dir/$deploy_sha"
secrets_dir="$root/shared/secrets"
certs_dir="$root/shared/certs"
runtime_config_dir="$root/shared/runtime-config"
compose_env_file="$root/shared/compose.env"
compose_env_validator="$release_dir/deploy/validate-compose-env.sh"
current_link="$root/current"
previous_link="$root/previous"
current_next="$root/current.next.$$"
previous_next="$root/previous.next.$$"
current_restore="$root/current.restore.$$"
previous_restore="$root/previous.restore.$$"
lock_file="$root/.deployment.lock"

for directory in "$incoming_dir" "$releases_dir" "$root/shared" "$secrets_dir" "$certs_dir"; do
  [[ -d "$directory" && ! -L "$directory" ]] || fail "required directory is missing or is a symlink: $directory"
done
[[ ! -L "$lock_file" ]] || fail "deployment lock must not be a symbolic link"
exec 9>>"$lock_file"
flock -w 5 9 || fail "another deployment or rollback holds the production lock"
chmod 0750 "$incoming_dir" "$releases_dir"
chmod 0700 "$root/shared" "$secrets_dir" "$certs_dir"
[[ -d "$stage" && ! -L "$stage" ]] || fail "release staging directory is missing or is a symlink"
[[ "$(realpath -m -- "$stage")" == "$stage" ]] || fail "release staging path is not normalized"
[[ ! -e "$release_dir" && ! -L "$release_dir" ]] || fail "release SHA already exists and is immutable"

for artifact in \
  "mianba-images-$deploy_sha.tar" \
  "mianba-images-$deploy_sha.tar.sha256" \
  "mianba-runtime-$deploy_sha.tar.gz" \
  "mianba-runtime-$deploy_sha.tar.gz.sha256"; do
  [[ -f "$stage/$artifact" && ! -L "$stage/$artifact" ]] || fail "missing regular artifact: $artifact"
done
(cd "$stage" && sha256sum --check "mianba-images-$deploy_sha.tar.sha256")
(cd "$stage" && sha256sum --check "mianba-runtime-$deploy_sha.tar.gz.sha256")
if tar -tzf "$stage/mianba-runtime-$deploy_sha.tar.gz" \
  | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  fail "runtime archive contains an unsafe path"
fi

release_created=0
candidate_started=0
pointer_transaction_active=0
deployment_committed=0
original_current_present=0
original_current_target=""
original_previous_present=0
original_previous_target=""
old_release=""
old_sha=""
runtime_install_temps=()

handle_failure() {
  local status="$1"
  local reason="$2"
  local pointer_status=0 cleanup_status=0 restore_status=0 delete_status=0
  trap - ERR INT TERM HUP
  set +e
  echo "Candidate release failed ($reason); attempting bounded rollback." >&2

  cleanup_pointer_temps || pointer_status=1
  cleanup_runtime_install_temps || pointer_status=1
  # committed 是最终状态；提交后的信号只能中断非关键清理，不能把指针补偿回旧版本。
  if (( pointer_transaction_active == 1 && deployment_committed == 0 )); then
    restore_original_pointers_bounded || pointer_status=1
  fi

  if (( deployment_committed == 0 && candidate_started == 1 )); then
    remove_candidate_application_containers "$release_dir" "$deploy_sha" || cleanup_status=1
    if [[ -n "$old_release" ]]; then
      restore_release "$old_release" "$old_sha" || restore_status=1
    fi
  fi

  if (( deployment_committed == 0 && release_created == 1 \
    && pointer_status == 0 && cleanup_status == 0 && restore_status == 0 )); then
    remove_exact_release "$release_dir" || delete_status=1
  fi
  remove_exact_stage "$stage" || true

  if (( pointer_status != 0 || cleanup_status != 0 || restore_status != 0 || delete_status != 0 )); then
    echo "Automatic rollback was incomplete; pointers or candidate files were preserved for inspection." >&2
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

mkdir -m 0750 -- "$release_dir"
release_created=1
tar --no-same-owner --no-same-permissions \
  -xzf "$stage/mianba-runtime-$deploy_sha.tar.gz" -C "$release_dir"
validate_release_identity "$release_dir" "$deploy_sha"
if find "$release_dir" -type l -print -quit | grep -q .; then
  fail "runtime release contains an unexpected symbolic link"
fi
if find "$release_dir" -type f \( -iname '*test*' -o -iname '*spec*' \) -print -quit | grep -q .; then
  fail "runtime release contains a forbidden test/spec file"
fi
[[ -f "$compose_env_validator" && ! -L "$compose_env_validator" ]] \
  || fail "runtime release is missing the compose env validator"
bash "$compose_env_validator" "$compose_env_file"

if [[ -e "$current_link" || -L "$current_link" ]]; then
  [[ -L "$current_link" ]] || fail "current must be a symbolic link"
  old_release="$(readlink -f -- "$current_link")"
  old_sha="$(basename -- "$old_release")"
  validate_release_identity "$old_release" "$old_sha"
  original_current_present=1
  original_current_target="$old_release"
elif [[ -e "$previous_link" || -L "$previous_link" ]]; then
  fail "previous exists without a current release"
fi
if [[ -e "$previous_link" || -L "$previous_link" ]]; then
  [[ -L "$previous_link" ]] || fail "previous must be a symbolic link"
  original_previous_target="$(readlink -f -- "$previous_link")"
  validate_release_identity "$original_previous_target" "$(basename -- "$original_previous_target")"
  original_previous_present=1
fi

for required in \
  deepseek_api_key resend_api_key mail_from \
  tencent_app_id tencent_secret_id tencent_secret_key \
  hcaptcha-site-key hcaptcha-secret; do
  [[ -f "$secrets_dir/$required" && ! -L "$secrets_dir/$required" && -s "$secrets_dir/$required" ]] \
    || fail "missing regular provider secret file: $required"
done
for certificate in sakuracianna.icu_bundle.pem sakuracianna.icu.key; do
  [[ -f "$certs_dir/$certificate" && ! -L "$certs_dir/$certificate" && -s "$certs_dir/$certificate" ]] \
    || fail "missing regular TLS file: $certificate"
done
case "$(stat -c '%a' "$certs_dir/sakuracianna.icu.key")" in
  400|600) ;;
  *) fail "TLS private key must use mode 0400 or 0600" ;;
esac
MIANBA_SECRETS_DIR="$secrets_dir" bash "$release_dir/deploy/prepare-server-secrets.sh"
for required in \
  postgres_owner_password postgres_api_password postgres_worker_password \
  redis_app_password rabbitmq_api_password rabbitmq_worker_password \
  rabbitmq-definitions.json jwt_secret material-parser-token deepseek_api_key resend_api_key mail_from \
  tencent_app_id tencent_secret_id tencent_secret_key hcaptcha-site-key hcaptcha-secret; do
  [[ -f "$secrets_dir/$required" && ! -L "$secrets_dir/$required" && -s "$secrets_dir/$required" ]] \
    || fail "missing regular server secret file: $required"
  [[ "$(stat -c '%a' "$secrets_dir/$required")" == "444" ]] \
    || fail "runtime secret must use mode 0444 inside a mode 0700 directory: $required"
done

install_runtime_config
validate_release_compose "$release_dir" "$deploy_sha"
if [[ -n "$old_release" ]]; then
  validate_release_compose "$old_release" "$old_sha"
  verify_release_images_present "$old_sha"
fi
require_docker_disk_headroom "$stage/mianba-images-$deploy_sha.tar"
docker load --input "$stage/mianba-images-$deploy_sha.tar"
verify_release_images_present "$deploy_sha"
compose_for "$release_dir" "$deploy_sha" run --rm --no-deps --pull never nginx nginx -t

candidate_started=1
compose_for "$release_dir" "$deploy_sha" up -d --no-build --no-recreate --pull never \
  --wait --wait-timeout 240 \
  postgres redis rabbitmq
compose_for "$release_dir" "$deploy_sha" run --rm --no-deps --pull never migrate
start_material_parser_if_present "$release_dir" "$deploy_sha"
compose_for "$release_dir" "$deploy_sha" up -d --no-build --no-deps --pull never \
  --wait --wait-timeout 240 \
  api frontend
compose_for "$release_dir" "$deploy_sha" up -d --no-build --no-deps --pull never \
  --wait --wait-timeout 120 \
  worker
[[ "$(compose_for "$release_dir" "$deploy_sha" ps --services --status running worker)" == "worker" ]] \
  || fail "worker container is not running"
wait_for_api_readiness "$release_dir" "$deploy_sha" \
  || fail "API or Worker-backed public readiness did not become healthy"
compose_for "$release_dir" "$deploy_sha" up -d --no-build --no-deps --pull never \
  --wait --wait-timeout 120 \
  nginx
compose_for "$release_dir" "$deploy_sha" exec -T nginx nginx -t
wait_for_edge_readiness "$release_dir" "$deploy_sha" \
  || fail "Nginx readiness did not become healthy"
compose_for "$release_dir" "$deploy_sha" ps

# 两个运维指针构成一个补偿式事务；只有两个目标均校验成功才提交。
pointer_transaction_active=1
if [[ -n "$old_release" ]]; then
  replace_pointer "$previous_link" "$old_release" "$previous_next"
  pointer_matches "$previous_link" "$old_release" || fail "previous pointer update could not be verified"
fi
replace_pointer "$current_link" "$release_dir" "$current_next"
pointer_matches "$current_link" "$release_dir" || fail "current pointer update could not be verified"
if [[ -n "$old_release" ]]; then
  pointer_matches "$previous_link" "$old_release" || fail "previous pointer changed during commit"
else
  [[ ! -e "$previous_link" && ! -L "$previous_link" ]] || fail "first deployment unexpectedly has a previous pointer"
fi
deployment_committed=1
pointer_transaction_active=0
trap - ERR INT TERM HUP

# 发布已提交后再做非关键清理；失败只告警，避免把健康的新版本误判为需回滚。
cleanup_pointer_temps || echo "Warning: pointer temporary cleanup needs manual inspection." >&2
cleanup_runtime_install_temps || echo "Warning: runtime-config temporary cleanup needs manual inspection." >&2
remove_exact_stage "$stage" || echo "Warning: staged artifacts could not be removed." >&2
prune_old_releases "$release_dir" "$old_release" \
  || echo "Warning: one or more obsolete releases could not be safely pruned." >&2

echo "Release $deploy_sha activated after readiness verification."
