#!/usr/bin/env bash
set -Eeuo pipefail

# 此脚本会写入一次性验证码并直接检查数据库，只允许在隔离的 CI 拓扑中运行。
if [[ "${CI:-}" != "true" ]]; then
  echo "ci-business-smoke.sh may only run when CI=true" >&2
  exit 1
fi

for command in curl docker node; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Required command is unavailable: $command" >&2
    exit 1
  }
done

readonly HOST_NAME="sakuracianna.icu"
readonly BASE_URL="https://${HOST_NAME}"
readonly EMAIL="ci-smoke-${GITHUB_RUN_ID:-0}-${GITHUB_RUN_ATTEMPT:-0}@example.invalid"
readonly CODE="654321"
readonly PASSWORD="CiSmoke-Only-2026!"
TEMP_DIR="$(mktemp -d)"
readonly TEMP_DIR
readonly COOKIE_JAR="${TEMP_DIR}/cookies.txt"
readonly COMPOSE_FILE="docker-compose.yml"
readonly CI_COMPOSE_FILE="deploy/docker-compose.ci.yml"

cleanup() {
  rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT
touch "$COOKIE_JAR"
chmod 600 "$COOKIE_JAR"

compose() {
  docker compose -f "$COMPOSE_FILE" -f "$CI_COMPOSE_FILE" "$@"
}

request() {
  local output_file="$1"
  shift
  curl --silent --show-error --fail-with-body --insecure \
    --resolve "${HOST_NAME}:443:127.0.0.1" \
    --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" \
    --output "$output_file" "$@"
}

json_value() {
  local input_file="$1"
  local property_path="$2"
  node - "$input_file" "$property_path" <<'NODE'
const fs = require('node:fs');
const [file, path] = process.argv.slice(2);
let value = JSON.parse(fs.readFileSync(file, 'utf8'));
for (const part of path.split('.')) {
  value = value?.[part];
}
if (value === undefined || value === null) {
  process.exit(2);
}
process.stdout.write(String(value));
NODE
}

new_uuid() {
  node -e "process.stdout.write(require('node:crypto').randomUUID())"
}

# 同时检查三个运行条件，避免覆盖文件被误用到生产 Worker。
compose exec -T worker sh -ec '
  test "$MIANBA_RUNTIME_ROLE" = "worker"
  test "$MIANBA_RUNTIME_PRODUCTION" = "false"
  test "$MIANBA_AI_RUNTIME_STUB_ENABLED" = "true"
'

# 验证码仅注入当前 CI 用户的受限 Redis key，密码在容器内读取且不会进入日志。
compose exec -T -e CI_SMOKE_EMAIL="$EMAIL" -e CI_SMOKE_CODE="$CODE" redis sh -ec '
  REDISCLI_AUTH="$(cat /run/secrets/redis_app_password)" \
    redis-cli --user mianba_app SETEX \
    "mianba:auth:email-code:${CI_SMOKE_EMAIL}" 300 "$CI_SMOKE_CODE" >/dev/null
'

printf '{"email":"%s","password":"%s","code":"%s"}\n' \
  "$EMAIL" "$PASSWORD" "$CODE" > "${TEMP_DIR}/register-request.json"
request "${TEMP_DIR}/register-response.json" \
  --request POST --header 'Content-Type: application/json' \
  --data-binary "@${TEMP_DIR}/register-request.json" \
  "${BASE_URL}/api/auth/password/register"

node - "${TEMP_DIR}/register-response.json" "$EMAIL" <<'NODE'
const fs = require('node:fs');
const [file, expectedEmail] = process.argv.slice(2);
const response = JSON.parse(fs.readFileSync(file, 'utf8'));
if (response.email !== expectedEmail || response.role !== 'user' || response.trial_voucher_count < 1) {
  throw new Error('Registration response did not include the expected user and trial voucher');
}
NODE

CSRF_TOKEN="$(awk '$6 == "csrf_token" { value = $7 } END { print value }' "$COOKIE_JAR")"
readonly CSRF_TOKEN
if [[ -z "$CSRF_TOKEN" ]]; then
  echo "Registration did not create a CSRF cookie" >&2
  exit 1
fi

# 构造小体积、高解压压力的合法 PDF，验证不可信解析只能影响受限 parser 容器。
node - "${TEMP_DIR}/resource-pressure.pdf" <<'NODE'
const fs = require('node:fs');
const zlib = require('node:zlib');
const [output] = process.argv.slice(2);
const content = Buffer.concat([
  Buffer.from('BT /F1 12 Tf 72 720 Td ('),
  Buffer.alloc(80 * 1024 * 1024, 0x41),
  Buffer.from(') Tj ET'),
]);
const compressed = zlib.deflateSync(content, { level: 9 });
const chunks = [];
const offsets = [0];
let length = 0;
const append = (value) => {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value, 'binary');
  chunks.push(bytes);
  length += bytes.length;
};
const object = (id, body) => {
  offsets[id] = length;
  append(`${id} 0 obj\n`);
  append(body);
  append('\nendobj\n');
};
append('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n');
object(1, '<< /Type /Catalog /Pages 2 0 R >>');
object(2, '<< /Type /Pages /Kids [3 0 R] /Count 1 >>');
object(3, '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>');
object(4, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
offsets[5] = length;
append(`5 0 obj\n<< /Length ${compressed.length} /Filter /FlateDecode >>\nstream\n`);
append(compressed);
append('\nendstream\nendobj\n');
const xrefOffset = length;
append('xref\n0 6\n0000000000 65535 f \n');
for (let id = 1; id <= 5; id += 1) {
  append(`${String(offsets[id]).padStart(10, '0')} 00000 n \n`);
}
append(`trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`);
const pdf = Buffer.concat(chunks);
if (pdf.length > 5 * 1024 * 1024) {
  throw new Error('Compressed PDF exceeded the public upload limit');
}
fs.writeFileSync(output, pdf, { mode: 0o600 });
NODE

API_CONTAINER_ID="$(compose ps -q api)"
readonly API_CONTAINER_ID
API_RESTARTS_BEFORE="$(docker inspect --format '{{.RestartCount}}' "$API_CONTAINER_ID")"
readonly API_RESTARTS_BEFORE
PARSER_CONTAINER_ID_BEFORE="$(compose ps -q material-parser)"
readonly PARSER_CONTAINER_ID_BEFORE
PARSER_RESTARTS_BEFORE="$(docker inspect --format '{{.RestartCount}}' "$PARSER_CONTAINER_ID_BEFORE")"
readonly PARSER_RESTARTS_BEFORE
BOMB_STATUS="$(curl --silent --show-error --insecure --max-time 30 \
  --resolve "${HOST_NAME}:443:127.0.0.1" \
  --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" \
  --output "${TEMP_DIR}/resource-pressure-response.json" --write-out '%{http_code}' \
  --request POST \
  --header "X-CSRF-Token: ${CSRF_TOKEN}" \
  --header "Idempotency-Key: ci-parser-pressure-$(new_uuid)" \
  --form 'interview_type=job' \
  --form 'job_title=Backend Engineer' \
  --form 'job_requirements=Build reliable distributed services' \
  --form "resume_file=@${TEMP_DIR}/resource-pressure.pdf;type=application/pdf" \
  "${BASE_URL}/api/interview-materials")"
case "$BOMB_STATUS" in
  201)
    json_value "${TEMP_DIR}/resource-pressure-response.json" id >/dev/null
    ;;
  408|503|504) ;;
  *)
    echo "Unexpected resource-pressure upload status: $BOMB_STATUS" >&2
    exit 1
    ;;
esac

compose up -d --no-build --wait --wait-timeout 120 material-parser >/dev/null
PARSER_CONTAINER_ID_AFTER="$(compose ps -q material-parser)"
readonly PARSER_CONTAINER_ID_AFTER
PARSER_RESTARTS_AFTER="$(docker inspect --format '{{.RestartCount}}' "$PARSER_CONTAINER_ID_AFTER")"
readonly PARSER_RESTARTS_AFTER
if [[ "$BOMB_STATUS" != "201" \
  && "$PARSER_CONTAINER_ID_AFTER" == "$PARSER_CONTAINER_ID_BEFORE" \
  && "$PARSER_RESTARTS_AFTER" -le "$PARSER_RESTARTS_BEFORE" ]]; then
  echo "Parser failure response did not correspond to a parser restart or replacement" >&2
  exit 1
fi
test "$(compose ps -q api)" = "$API_CONTAINER_ID"
test "$(docker inspect --format '{{.RestartCount}}' "$API_CONTAINER_ID")" = "$API_RESTARTS_BEFORE"
compose exec -T api curl --fail --silent --show-error --max-time 5 \
  http://127.0.0.1:8000/api/health/readiness >/dev/null

printf 'Java 21, Spring Boot, PostgreSQL, Redis, RabbitMQ and defensive programming.\n' \
  > "${TEMP_DIR}/normal-resume.txt"
request "${TEMP_DIR}/normal-material.json" \
  --request POST \
  --header "X-CSRF-Token: ${CSRF_TOKEN}" \
  --header "Idempotency-Key: ci-parser-recovery-$(new_uuid)" \
  --form 'interview_type=job' \
  --form 'job_title=Backend Engineer' \
  --form 'job_requirements=Build reliable distributed services' \
  --form "resume_file=@${TEMP_DIR}/normal-resume.txt;type=text/plain" \
  "${BASE_URL}/api/interview-materials"
json_value "${TEMP_DIR}/normal-material.json" id >/dev/null

MATERIAL_KEY="ci-material-$(new_uuid)"
readonly MATERIAL_KEY
request "${TEMP_DIR}/material.json" \
  --request POST \
  --header "X-CSRF-Token: ${CSRF_TOKEN}" \
  --header "Idempotency-Key: ${MATERIAL_KEY}" \
  --form 'interview_type=civil_service' \
  "${BASE_URL}/api/interview-materials"
MATERIAL_ID="$(json_value "${TEMP_DIR}/material.json" id)"
readonly MATERIAL_ID

SESSION_ID="$(new_uuid)"
readonly SESSION_ID
START_KEY="ci-session-$(new_uuid)"
readonly START_KEY
printf '{"session_id":"%s","interview_type":"civil_service","material_id":"%s"}\n' \
  "$SESSION_ID" "$MATERIAL_ID" > "${TEMP_DIR}/start-request.json"
request "${TEMP_DIR}/session.json" \
  --request POST \
  --header 'Content-Type: application/json' \
  --header "X-CSRF-Token: ${CSRF_TOKEN}" \
  --header "Idempotency-Key: ${START_KEY}" \
  --data-binary "@${TEMP_DIR}/start-request.json" \
  "${BASE_URL}/api/interviews"

node - "${TEMP_DIR}/session.json" "$SESSION_ID" <<'NODE'
const fs = require('node:fs');
const [file, sessionId] = process.argv.slice(2);
const session = JSON.parse(fs.readFileSync(file, 'utf8'));
if (session.session_id !== sessionId || session.status !== 'active'
    || session.current_step_index !== 0 || session.total_steps !== 5
    || session.current_question?.turn_index !== 0 || session.report !== null) {
  throw new Error('Civil service session did not start in the expected five-turn state');
}
NODE

# 首轮暂停 Worker，确保刷新页面时可以从会话快照恢复 active_task，再从任务接口继续轮询。
compose stop --timeout 30 worker >/dev/null

for turn_index in 0 1 2 3 4; do
  # 遵守线上 AI 提交限流，不让烟测依赖 Nginx 的突发额度。
  sleep 5
  answer_key="ci-answer-${turn_index}-$(new_uuid)"
  printf '{"turn_index":%s,"answer_text":"CI 第 %s 轮回答：先明确目标和约束，再给出执行步骤、风险控制和复盘指标。"}\n' \
    "$turn_index" "$((turn_index + 1))" > "${TEMP_DIR}/answer-request.json"
  request "${TEMP_DIR}/answer-response.json" \
    --request POST \
    --header 'Content-Type: application/json' \
    --header "X-CSRF-Token: ${CSRF_TOKEN}" \
    --header "Idempotency-Key: ${answer_key}" \
    --data-binary "@${TEMP_DIR}/answer-request.json" \
    "${BASE_URL}/api/interviews/${SESSION_ID}/answers"
  task_id="$(json_value "${TEMP_DIR}/answer-response.json" task.id)"

  request "${TEMP_DIR}/task.json" "${BASE_URL}/api/tasks/${task_id}"
  if [[ "$turn_index" == "0" ]]; then
    request "${TEMP_DIR}/recovered-session.json" "${BASE_URL}/api/interviews/${SESSION_ID}"
    node - "${TEMP_DIR}/recovered-session.json" "${TEMP_DIR}/task.json" "$SESSION_ID" "$task_id" <<'NODE'
const fs = require('node:fs');
const [sessionFile, taskFile, sessionId, taskId] = process.argv.slice(2);
const session = JSON.parse(fs.readFileSync(sessionFile, 'utf8'));
const task = JSON.parse(fs.readFileSync(taskFile, 'utf8'));
if (session.session_id !== sessionId || session.status !== 'awaiting_ai'
    || session.active_task?.id !== taskId || session.active_task?.status !== 'QUEUED') {
  throw new Error('Session refresh did not recover the queued active task');
}
if (task.id !== taskId || task.session_id !== sessionId || task.status !== 'QUEUED') {
  throw new Error('Task refresh did not recover the queued worker task');
}
NODE
    compose start worker >/dev/null
  fi

  task_status=""
  for attempt in $(seq 1 60); do
    request "${TEMP_DIR}/task.json" "${BASE_URL}/api/tasks/${task_id}"
    task_status="$(json_value "${TEMP_DIR}/task.json" status)"
    case "$task_status" in
      SUCCEEDED)
        break
        ;;
      FAILED|CANCELLED)
        echo "Worker task ${task_id} reached terminal status ${task_status}" >&2
        exit 1
        ;;
    esac
    if [[ "$attempt" == "60" ]]; then
      echo "Worker task ${task_id} did not complete within 120 seconds" >&2
      exit 1
    fi
    sleep 2
  done

  request "${TEMP_DIR}/session.json" "${BASE_URL}/api/interviews/${SESSION_ID}"
  node - "${TEMP_DIR}/session.json" "$SESSION_ID" "$turn_index" <<'NODE'
const fs = require('node:fs');
const [file, sessionId, rawTurn] = process.argv.slice(2);
const turn = Number(rawTurn);
const session = JSON.parse(fs.readFileSync(file, 'utf8'));
if (session.session_id !== sessionId || session.total_steps !== 5) {
  throw new Error('Session identity or turn count changed unexpectedly');
}
if (turn < 4) {
  const nextTurn = turn + 1;
  if (session.status !== 'active' || session.current_step_index !== nextTurn
      || session.current_question?.turn_index !== nextTurn || session.report !== null) {
    throw new Error(`Session did not advance to active turn ${nextTurn}`);
  }
} else if (session.status !== 'completed' || session.current_step_index !== 4
    || session.report === null || typeof session.report !== 'object') {
  throw new Error('Final worker result did not create the completed report');
}
NODE
done

request "${TEMP_DIR}/delete-response.txt" \
  --request DELETE --header "X-CSRF-Token: ${CSRF_TOKEN}" \
  "${BASE_URL}/api/interviews/${SESSION_ID}"

deleted_status="$(curl --silent --show-error --insecure \
  --resolve "${HOST_NAME}:443:127.0.0.1" \
  --cookie "$COOKIE_JAR" --output "${TEMP_DIR}/deleted-session.json" \
  --write-out '%{http_code}' "${BASE_URL}/api/interviews/${SESSION_ID}")"
if [[ "$deleted_status" != "404" ]]; then
  echo "Deleted interview remained visible through the API: HTTP ${deleted_status}" >&2
  exit 1
fi

deleted_task_status="$(curl --silent --show-error --insecure \
  --resolve "${HOST_NAME}:443:127.0.0.1" \
  --cookie "$COOKIE_JAR" --output "${TEMP_DIR}/deleted-task.json" \
  --write-out '%{http_code}' "${BASE_URL}/api/tasks/${task_id}")"
if [[ "$deleted_task_status" != "404" ]]; then
  echo "Deleted interview task remained visible through the user API: HTTP ${deleted_task_status}" >&2
  exit 1
fi

# 用数据库所有者只读断言删除结果；不输出凭据，也不修改业务数据。
erasure_result="$(compose exec -T -e CI_SESSION_ID="$SESSION_ID" postgres sh -ec '
  psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --tuples-only --no-align --set "session_id=$CI_SESSION_ID"
' <<'SQL'
SELECT CASE WHEN
    EXISTS (
        SELECT 1 FROM sessions
        WHERE id = :'session_id'::uuid
          AND status = 'deleted'
          AND content_erased_at IS NOT NULL
    )
    AND (SELECT count(*) FROM turns WHERE session_id = :'session_id'::uuid) = 5
    AND NOT EXISTS (
        SELECT 1 FROM turns
        WHERE session_id = :'session_id'::uuid
          AND (
              round_name <> '内容已删除'
              OR question_text <> '该轮内容已按隐私策略删除。'
              OR answer_text IS NOT NULL
              OR answer_idempotency_key IS NOT NULL
              OR evaluation_score IS NOT NULL
              OR evaluation_feedback IS NOT NULL
              OR evaluated_at IS NOT NULL
              OR status <> 'cancelled'
          )
    )
    AND NOT EXISTS (SELECT 1 FROM reports WHERE session_id = :'session_id'::uuid)
    AND (SELECT count(*) FROM ai_jobs WHERE session_id = :'session_id'::uuid) = 5
    AND NOT EXISTS (
        SELECT 1 FROM ai_jobs
        WHERE session_id = :'session_id'::uuid
          AND (
              request_hash <> encode(digest('mianba:erased-ai-job:v1', 'sha256'), 'hex')
              OR input_ref <> '{}'::jsonb
              OR result_ref IS NOT NULL
              OR error_message IS NOT NULL
          )
    )
    AND NOT EXISTS (
        SELECT 1 FROM content_safety
        WHERE session_id = :'session_id'::uuid
          AND (matched_terms <> '[]'::jsonb OR content_excerpt IS NOT NULL)
    )
THEN 'ok' ELSE 'failed' END;
SQL
)"

if [[ "$(tr -d '[:space:]' <<<"$erasure_result")" != "ok" ]]; then
  echo "Database content erasure assertions failed" >&2
  exit 1
fi

echo "CI business smoke completed: registration, five worker turns, report, recovery and erasure"
