#!/bin/sh
set -eu

# 脚本不会覆盖已有 secret，也不会把值输出到终端；隔离 CI 使用同一权限模型。
# rabbitmq-definitions.json 是由现有 secret 派生的运行配置，每次执行都可以安全刷新。
: "${MIANBA_SECRETS_DIR:?MIANBA_SECRETS_DIR is required}"
umask 077
mkdir -p "$MIANBA_SECRETS_DIR"
[ -d "$MIANBA_SECRETS_DIR" ] && [ ! -L "$MIANBA_SECRETS_DIR" ] || {
  echo "Secret directory must be a real directory" >&2
  exit 1
}
chmod 700 "$MIANBA_SECRETS_DIR"

create_random_secret() {
  name="$1"
  path="$MIANBA_SECRETS_DIR/$name"
  if [ -e "$path" ] || [ -L "$path" ]; then
    if [ ! -f "$path" ] || [ -L "$path" ]; then
      echo "Existing secret must be a regular file: $path" >&2
      exit 1
    fi
  else
    openssl rand -hex 48 > "$path"
  fi
  # Compose 文件型 Secret 不重映射 UID；0700 父目录保护宿主机，0444 允许各非 root 容器只读。
  chmod 444 "$path"
}

create_random_secret postgres_owner_password
create_random_secret postgres_api_password
create_random_secret postgres_worker_password
create_random_secret redis_app_password
create_random_secret rabbitmq_api_password
create_random_secret rabbitmq_worker_password
create_random_secret jwt_secret
create_random_secret content_safety_hmac_secret
create_random_secret material-parser-token

# DeepSeek、邮件、腾讯云与 hCaptcha 凭据必须由项目所有者提供；这里不会生成无效占位值。
for required in \
  deepseek_api_key resend_api_key mail_from \
  tencent_app_id tencent_secret_id tencent_secret_key \
  hcaptcha-site-key hcaptcha-secret; do
  path="$MIANBA_SECRETS_DIR/$required"
  if [ ! -f "$path" ] || [ -L "$path" ] || [ ! -s "$path" ]; then
    echo "Missing required provider secret file: $path" >&2
    exit 1
  fi
  chmod 444 "$path"
done

# RabbitMQ SHA-256 密码格式为 Base64(salt || SHA256(salt || password))。
# 使用受限临时文件计算，避免原始密码出现在进程参数、环境变量或 Docker 元数据中。
hash_work="$(mktemp -d)"
definitions_path="$MIANBA_SECRETS_DIR/rabbitmq-definitions.json"
definitions_temp=""
if [ -e "$definitions_path" ] || [ -L "$definitions_path" ]; then
  if [ ! -f "$definitions_path" ] || [ -L "$definitions_path" ]; then
    echo "RabbitMQ definitions must be a regular file" >&2
    exit 1
  fi
fi
cleanup_hash_work() {
  if [ -n "$definitions_temp" ]; then
    rm -f -- "$definitions_temp"
  fi
  rm -f -- "$hash_work/password" "$hash_work/salt" "$hash_work/digest"
  rmdir -- "$hash_work" 2>/dev/null || true
}
trap cleanup_hash_work EXIT HUP INT TERM
chmod 700 "$hash_work"
hash_rabbit_password() {
  secret_name="$1"
  tr -d '\r\n' < "$MIANBA_SECRETS_DIR/$secret_name" > "$hash_work/password"
  if [ "$(wc -c < "$hash_work/password")" -lt 32 ]; then
    echo "$secret_name must contain at least 32 characters" >&2
    exit 1
  fi
  openssl rand 4 > "$hash_work/salt"
  { cat "$hash_work/salt"; cat "$hash_work/password"; } \
    | openssl dgst -sha256 -binary > "$hash_work/digest"
  { cat "$hash_work/salt"; cat "$hash_work/digest"; } | openssl base64 -A
}

rabbit_api_hash="$(hash_rabbit_password rabbitmq_api_password)"
rabbit_worker_hash="$(hash_rabbit_password rabbitmq_worker_password)"
test -n "$rabbit_api_hash"
test -n "$rabbit_worker_hash"

# 临时文件必须与目标文件位于同一目录，生成成功后再用原子重命名替换旧配置。
# 任何写入、权限设置或重命名失败都会触发 trap，只删除临时文件并保留旧配置。
definitions_temp="$(mktemp "$MIANBA_SECRETS_DIR/.rabbitmq-definitions.json.tmp.XXXXXX")"
chmod 600 "$definitions_temp"
cat > "$definitions_temp" <<EOF
{
  "users": [
    {
      "name": "mianba_api",
      "password_hash": "$rabbit_api_hash",
      "hashing_algorithm": "rabbit_password_hashing_sha256",
      "tags": ""
    },
    {
      "name": "mianba_worker",
      "password_hash": "$rabbit_worker_hash",
      "hashing_algorithm": "rabbit_password_hashing_sha256",
      "tags": ""
    }
  ],
  "vhosts": [{"name": "/mianba"}],
  "permissions": [
    {
      "user": "mianba_api",
      "vhost": "/mianba",
      "configure": "^mianba\\\\.",
      "write": "^mianba\\\\.",
      "read": "^mianba\\\\."
    },
    {
      "user": "mianba_worker",
      "vhost": "/mianba",
      "configure": "^mianba\\\\.ai\\\\.jobs\\\\.v1$",
      "write": "^$",
      "read": "^mianba\\\\.ai\\\\.jobs\\\\.v1$"
    }
  ]
}
EOF
chmod 444 "$definitions_temp"
mv -f -- "$definitions_temp" "$definitions_path"
definitions_temp=""

cleanup_hash_work
trap - EXIT HUP INT TERM

echo "Secret files and RabbitMQ definitions are ready; values were not printed."
