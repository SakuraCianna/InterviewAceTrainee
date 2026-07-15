#!/usr/bin/env bash
set -Eeuo pipefail

# 生产 Compose 配置只允许受控字段与安全字符, 不使用 source 也不输出任何值。
fail() {
  echo "Compose environment validation failed: $*" >&2
  exit 1
}

(( $# == 1 )) || fail "exactly one compose env path is required"
compose_env_file="$1"

[[ -f "$compose_env_file" && ! -L "$compose_env_file" ]] \
  || fail "compose env must be a regular non-symlink file"
[[ "$(stat -c '%a' -- "$compose_env_file")" == "600" ]] \
  || fail "compose env must use mode 0600"
command -v python3 >/dev/null 2>&1 || fail "python3 is required for compose env validation"

python3 - "$compose_env_file" <<'PY'
import re
import sys
from pathlib import Path

EXPECTED_FIELDS = (
    "MIANBA_APP_IMAGE",
    "MIANBA_FRONTEND_IMAGE",
    "MIANBA_SECRETS_DIR",
    "MIANBA_CERTS_DIR",
    "MIANBA_RUNTIME_CONFIG_DIR",
    "MIANBA_CORS_ALLOWED_ORIGINS",
    "API_DB_POOL_SIZE",
    "API_DB_MIN_IDLE",
    "WORKER_DB_POOL_SIZE",
    "WORKER_DB_MIN_IDLE",
    "DB_CONNECTION_TIMEOUT_MS",
    "API_TOMCAT_MAX_THREADS",
    "API_TOMCAT_ACCEPT_COUNT",
    "WORKER_CONSUMER_CONCURRENCY",
    "WORKER_CONSUMER_MAX_CONCURRENCY",
)
CHINESE_TEXT = re.compile(r"[\u4e00-\u9fff]")
CHINESE_PUNCTUATION = re.compile(r"[，。；：！？、]")
FIELD_LINE = re.compile(r"([A-Z][A-Z0-9_]*)=(.*)")
SAFE_VALUE = re.compile(r"[-A-Za-z0-9._:/,@%+]+")


def refuse(message: str) -> None:
    raise SystemExit(f"Compose environment validation failed: {message}")


try:
    raw = Path(sys.argv[1]).read_bytes()
except OSError as error:
    refuse(f"compose env could not be read: {error.__class__.__name__}")
if b"\r" in raw:
    refuse("compose env must use LF line endings")
try:
    lines = raw.decode("utf-8").split("\n")
except UnicodeDecodeError:
    refuse("compose env must be valid UTF-8")

field_index = 0
comment_line = None
for line_number, line in enumerate(lines, start=1):
    if not line:
        if comment_line is not None:
            refuse(f"comment on line {comment_line} is not adjacent to a field")
        continue
    if line.startswith("#"):
        if comment_line is not None:
            refuse("field comments must contain exactly one adjacent line")
        if not line.startswith("# ") or len(line) == 2:
            refuse(f"comment on line {line_number} must start with '# '")
        comment = line[2:]
        if CHINESE_TEXT.search(comment) is None:
            refuse(f"comment on line {line_number} must contain Chinese text")
        if CHINESE_PUNCTUATION.search(comment) is not None:
            refuse(f"comment on line {line_number} must use English punctuation")
        comment_line = line_number
        continue

    if comment_line != line_number - 1:
        refuse(f"field on line {line_number} requires an adjacent Chinese comment")
    if field_index >= len(EXPECTED_FIELDS):
        refuse(f"compose env contains an extra field on line {line_number}")
    match = FIELD_LINE.fullmatch(line)
    if match is None:
        refuse(f"field on line {line_number} must use KEY=VALUE")
    field_name, field_value = match.groups()
    expected_name = EXPECTED_FIELDS[field_index]
    if field_name != expected_name:
        refuse(f"field {field_index + 1} must be {expected_name}")
    if not field_value:
        refuse(f"field {field_name} must not be empty")
    if SAFE_VALUE.fullmatch(field_value) is None:
        refuse(f"field {field_name} contains forbidden syntax")
    field_index += 1
    comment_line = None

if comment_line is not None:
    refuse("final comment is not followed by a field")
if field_index != len(EXPECTED_FIELDS):
    refuse(f"compose env must contain exactly {len(EXPECTED_FIELDS)} ordered fields")
PY
