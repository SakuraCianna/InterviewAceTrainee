#!/usr/bin/env bash
set -Eeuo pipefail

# 该契约测试只检查生产交付脚本的失效保护，不启动 Docker 或访问网络。
workspace="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$workspace/.github/workflows/deploy-production.yml"
activate="$workspace/deploy/activate-release.sh"
rollback="$workspace/deploy/rollback-release.sh"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "production release contract failed: $*" >&2
  exit 1
}

grep -Fq 'test "${#runtime_images[@]}" -eq 6' "$workflow" \
  || fail "Compose image inventory is not closed to exactly six images"
grep -Fq 'unexpected = sorted(tags - expected_tags)' "$workflow" \
  || fail "image archive does not reject unexpected repository tags"
grep -Fq 'test "$http_status" = "200"' "$workflow" \
  || fail "public health verification does not require HTTP 200"
grep -Fq 'payload.get("status") == "ok"' "$workflow" \
  || fail "public liveness verification does not validate JSON"
grep -Fq 'payload.get("ready") is True' "$workflow" \
  || fail "public readiness verification does not validate JSON"
grep -Fq 'payload.get("parser_ready") is True' "$workflow" \
  || fail "public readiness verification does not require Material Parser readiness"

workflow_validator="$temporary_root/workflow-health-validator.py"
python3 - "$workflow" "$workflow_validator" <<'PY'
import re
import sys
import textwrap
from pathlib import Path

source_path, target_path = map(Path, sys.argv[1:])
source = source_path.read_text(encoding="utf-8")
match = re.search(
    r'''if python - "\$response_file" "\$probe" <<'PY'\n(?P<body>.*?)\n\s+PY\n\s+then''',
    source,
    re.DOTALL,
)
if match is None:
    raise SystemExit("workflow health validator could not be extracted")
target_path.write_text(textwrap.dedent(match.group("body")) + "\n", encoding="utf-8")
PY

write_workflow_payload() {
  local target="$1"
  local payload="$2"
  printf '%s' "$payload" > "$target"
}

run_workflow_validator() {
  local probe="$1"
  local payload="$2"
  python3 "$workflow_validator" "$payload" "$probe"
}

write_workflow_payload "$temporary_root/workflow-live-valid" '{"status":"ok"}'
write_workflow_payload "$temporary_root/workflow-ready-valid" \
  '{"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true}'
write_workflow_payload "$temporary_root/workflow-live-duplicate" \
  '{"status":"failed","status":"ok"}'
write_workflow_payload "$temporary_root/workflow-ready-duplicate" \
  '{"ready":false,"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true}'
write_workflow_payload "$temporary_root/workflow-ready-nan" \
  '{"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true,"latency":NaN}'

run_workflow_validator liveness "$temporary_root/workflow-live-valid" \
  || fail "workflow rejected valid liveness JSON"
run_workflow_validator readiness "$temporary_root/workflow-ready-valid" \
  || fail "workflow rejected valid readiness JSON"
for case_name in workflow-live-duplicate workflow-ready-duplicate workflow-ready-nan; do
  probe=readiness
  [[ "$case_name" == workflow-live-duplicate ]] && probe=liveness
  if run_workflow_validator "$probe" "$temporary_root/$case_name" >/dev/null 2>&1; then
    fail "workflow accepted non-strict JSON: $case_name"
  fi
done

for script in "$activate" "$rollback"; do
  grep -Fq 'strict_api_readiness_probe()' "$script" \
    || fail "API readiness is not a strict status-and-JSON probe: $script"
  grep -Fq 'strict_edge_readiness_probe()' "$script" \
    || fail "edge readiness is not a strict status-and-JSON probe: $script"
  grep -Fq 'validate_readiness_probe_output()' "$script" \
    || fail "readiness probe does not use a real JSON validator: $script"
  if awk '/^strict_api_readiness_probe\(\)/,/^}/; /^strict_edge_readiness_probe\(\)/,/^}/' "$script" \
    | grep -Eq 'grep[[:space:]]+-'; then
    fail "readiness probe still validates JSON with grep: $script"
  fi
done

write_probe() {
  local target="$1"
  local status="$2"
  local payload="$3"
  printf '%s\n%s' "$status" "$payload" > "$target"
}

run_validator() {
  local script="$1"
  local probe="$2"
  local prefix="$temporary_root/$(basename -- "$script").prefix"
  awk '/^root=/{exit} {print}' "$script" > "$prefix"
  MIANBA_PRODUCTION_ROOT=/tmp/mianba-contract-root \
  MIANBA_DEPLOY_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  MIANBA_FAILED_DEPLOY_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  MIANBA_STAGE_ID=contract-stage \
    bash -c 'source "$1"; validate_readiness_probe_output "$2"' _ "$prefix" "$probe"
}

valid_payload='{"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true,"timestamp":"2026-07-15T00:00:00Z"}'
write_probe "$temporary_root/valid" 200 "$valid_payload"
write_probe "$temporary_root/malformed" 200 '{'
write_probe "$temporary_root/missing" 200 \
  '{"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true}'
write_probe "$temporary_root/wrong-type" 200 \
  '{"ready":true,"database_ready":1,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true}'
write_probe "$temporary_root/duplicate" 200 \
  '{"ready":true,"ready":true,"database_ready":true,"redis_ready":true,"rabbit_ready":true,"worker_ready":true,"parser_ready":true}'
write_probe "$temporary_root/redirect" 302 "$valid_payload"

for script in "$activate" "$rollback"; do
  run_validator "$script" "$temporary_root/valid" \
    || fail "valid readiness JSON was rejected: $script"
  for invalid in malformed missing wrong-type duplicate redirect; do
    if run_validator "$script" "$temporary_root/$invalid"; then
      fail "invalid readiness probe was accepted ($invalid): $script"
    fi
  done
done

echo "Production release contract passed."
