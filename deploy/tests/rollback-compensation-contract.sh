#!/usr/bin/env bash
set -Eeuo pipefail

# 使用假的 Docker 和 mv 注入指针提交失败，验证候选服务先恢复就绪，再补偿运维指针。
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "rollback compensation contract failed: $*" >&2
  exit 1
}

candidate_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
previous_sha='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
production_root="$temporary_root/production"
candidate_release="$production_root/releases/$candidate_sha"
previous_release="$production_root/releases/$previous_sha"
runtime_config="$production_root/shared/runtime-config"
mock_bin="$temporary_root/bin"
event_log="$temporary_root/events.log"
pointer_failure_marker="$temporary_root/pointer-failure"

mkdir -p \
  "$candidate_release/deploy" \
  "$previous_release" \
  "$production_root/shared/secrets" \
  "$production_root/shared/certs" \
  "$runtime_config/postgres-init" \
  "$mock_bin"
for release in "$candidate_release" "$previous_release"; do
  printf '%s\n' "$(basename -- "$release")" > "$release/commit.txt"
  printf 'services: {}\n' > "$release/docker-compose.yml"
done
cp -- "$workspace/deploy/validate-compose-env.sh" "$candidate_release/deploy/validate-compose-env.sh"
tr -d '\r' < "$workspace/deploy/compose.env.example" > "$production_root/shared/compose.env"
chmod 0600 "$production_root/shared/compose.env"
for runtime_file in \
  "$runtime_config/postgres-init/001-create-app-role.sh" \
  "$runtime_config/redis-entrypoint.sh" \
  "$runtime_config/rabbitmq.conf"; do
  printf 'runtime-config\n' > "$runtime_file"
  chmod 0444 "$runtime_file"
done
ln -s -- "$candidate_release" "$production_root/current"
ln -s -- "$previous_release" "$production_root/previous"

cat > "$mock_bin/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
: "${MIANBA_TEST_CANDIDATE_RELEASE:?}"
: "${MIANBA_TEST_EVENT_LOG:?}"

if [[ "${1:-}" == 'image' && "${2:-}" == 'inspect' ]]; then
  exit 0
fi
if [[ "${1:-}" == 'ps' ]]; then
  exit 0
fi
[[ "${1:-}" == 'compose' ]] || exit 0

release_path=''
for ((index = 1; index <= $#; index++)); do
  if [[ "${!index}" == '--project-directory' ]]; then
    next=$((index + 1))
    release_path="${!next}"
    break
  fi
done
arguments=" $* "
if [[ "$arguments" == *' config --services '* ]]; then
  printf '%s\n' postgres redis rabbitmq migrate material-parser api worker frontend nginx
  exit 0
fi
if [[ "$arguments" == *' ps --services --status running worker '* ]]; then
  printf 'worker\n'
  exit 0
fi
if [[ "$arguments" == *' exec -T api sh -ec '* ]]; then
  probe_script="${!#}"
  if ! sh -ec "$probe_script"; then
    exit 1
  fi
  if [[ "$release_path" == "$MIANBA_TEST_CANDIDATE_RELEASE" ]]; then
    printf 'EVENT CANDIDATE_API_READY\n' >> "$MIANBA_TEST_EVENT_LOG"
  fi
  exit 0
fi
if [[ "$arguments" == *' exec -T nginx sh -ec '* ]]; then
  probe_script="${!#}"
  if ! sh -ec "$probe_script"; then
    exit 1
  fi
  if [[ "$release_path" == "$MIANBA_TEST_CANDIDATE_RELEASE" ]]; then
    printf 'EVENT CANDIDATE_EDGE_READY\n' >> "$MIANBA_TEST_EVENT_LOG"
  fi
  exit 0
fi
exit 0
MOCK_DOCKER
chmod 0755 "$mock_bin/docker"

cat > "$mock_bin/curl" <<'MOCK_CURL'
#!/usr/bin/env sh
set -eu
output_file=''
request_url=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = '--output' ]; then
    output_file="$2"
    shift 2
  else
    case "$1" in
      http://*|https://*) request_url="$1" ;;
    esac
    shift
  fi
done
test -n "$output_file"
printf '%s\n' \
  '{"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true}' \
  > "$output_file"
if [ "$request_url" = 'https://sakuracianna.icu/api/health/readiness' ] \
  && [ -e "${MIANBA_TEST_POINTER_FAILURE_MARKER:-}" ]; then
  printf 'EVENT CANDIDATE_EDGE_READY\n' >> "$MIANBA_TEST_EVENT_LOG"
fi
printf '200'
MOCK_CURL
chmod 0755 "$mock_bin/curl"

cat > "$mock_bin/wget" <<'MOCK_WGET'
#!/usr/bin/env sh
set -eu
output_file=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = '-O' ]; then
    output_file="$2"
    shift 2
  else
    shift
  fi
done
test -n "$output_file"
printf '%s\n' '  HTTP/1.1 200 OK' >&2
printf '%s\n' \
  '{"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true}' \
  > "$output_file"
MOCK_WGET
chmod 0755 "$mock_bin/wget"

cat > "$mock_bin/mv" <<'MOCK_MV'
#!/usr/bin/env bash
set -Eeuo pipefail
: "${MIANBA_TEST_EVENT_LOG:?}"
: "${MIANBA_TEST_POINTER_FAILURE_MARKER:?}"
: "${MIANBA_TEST_PREVIOUS_LINK:?}"
: "${MIANBA_TEST_REAL_MV:?}"

arguments=("$@")
source_path="${arguments[${#arguments[@]} - 2]}"
destination="${arguments[${#arguments[@]} - 1]}"
if [[ "$destination" == "$MIANBA_TEST_PREVIOUS_LINK" \
  && "$source_path" == "$MIANBA_TEST_PREVIOUS_LINK.next."* \
  && ! -e "$MIANBA_TEST_POINTER_FAILURE_MARKER" ]]; then
  : > "$MIANBA_TEST_POINTER_FAILURE_MARKER"
  printf 'EVENT POINTER_COMMIT_FAILED\n' >> "$MIANBA_TEST_EVENT_LOG"
  exit 76
fi

"$MIANBA_TEST_REAL_MV" "$@"
if [[ -e "$MIANBA_TEST_POINTER_FAILURE_MARKER" \
  && "$destination" == "${MIANBA_TEST_PREVIOUS_LINK%/previous}/current" ]]; then
  printf 'EVENT POINTER_CURRENT_RESTORED\n' >> "$MIANBA_TEST_EVENT_LOG"
fi
MOCK_MV
chmod 0755 "$mock_bin/mv"

real_mv="$(command -v mv)"
export PATH="$mock_bin:$PATH"
export MIANBA_TEST_CANDIDATE_RELEASE="$candidate_release"
export MIANBA_TEST_EVENT_LOG="$event_log"
export MIANBA_TEST_POINTER_FAILURE_MARKER="$pointer_failure_marker"
export MIANBA_TEST_PREVIOUS_LINK="$production_root/previous"
export MIANBA_TEST_REAL_MV="$real_mv"

set +e
MIANBA_PRODUCTION_ROOT="$production_root" \
MIANBA_FAILED_DEPLOY_SHA="$candidate_sha" \
  bash "$workspace/deploy/rollback-release.sh" > "$temporary_root/stdout" 2> "$temporary_root/stderr"
status=$?
set -e

[[ "$status" -eq 76 ]] || fail "expected injected pointer failure status 76, got $status"
current_target="$(readlink -f -- "$production_root/current" 2>/dev/null || true)"
previous_target="$(readlink -f -- "$production_root/previous" 2>/dev/null || true)"
events="$(tr '\n' '|' < "$event_log" 2>/dev/null || true)"
rollback_errors="$(tail -n 80 "$temporary_root/stderr" 2>/dev/null | tr '\n' '|' || true)"
[[ "$current_target" == "$candidate_release" ]] \
  || fail "current pointer was not restored to the candidate: $current_target; events=$events; errors=$rollback_errors"
[[ "$previous_target" == "$previous_release" ]] \
  || fail "previous pointer was not restored: $previous_target"

event_line() {
  local event="$1"
  grep -n -m 1 -F -- "$event" "$event_log" | cut -d: -f1
}
failure_line="$(event_line 'EVENT POINTER_COMMIT_FAILED')"
api_line="$(event_line 'EVENT CANDIDATE_API_READY')"
edge_line="$(event_line 'EVENT CANDIDATE_EDGE_READY')"
pointer_line="$(event_line 'EVENT POINTER_CURRENT_RESTORED')"
(( failure_line < api_line && api_line < edge_line && edge_line < pointer_line )) \
  || fail "candidate readiness did not complete before pointer compensation"

echo "Rollback compensation contract passed."
