#!/bin/sh
set -eu

# 该脚本仅在全新 PGDATA 初始化时运行；API 与 Worker 账号互相隔离且均无管理权限。
api_password="$(cat /run/secrets/postgres_api_password)"
worker_password="$(cat /run/secrets/postgres_worker_password)"
for value in "$api_password" "$worker_password"; do
  if [ "${#value}" -lt 32 ]; then
    echo "PostgreSQL runtime passwords must contain at least 32 characters" >&2
    exit 1
  fi
done

psql --set ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set api_password="$api_password" \
  --set worker_password="$worker_password" <<'SQL'
SELECT format(
    'CREATE ROLE mianba_api LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
    :'api_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mianba_api')
\gexec

SELECT format(
    'ALTER ROLE mianba_api WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
    :'api_password'
)
\gexec

SELECT format(
    'CREATE ROLE mianba_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
    :'worker_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mianba_worker')
\gexec

SELECT format(
    'ALTER ROLE mianba_worker WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
    :'worker_password'
)
\gexec

REVOKE ALL PRIVILEGES ON DATABASE mianba_prod FROM PUBLIC;
GRANT CONNECT ON DATABASE mianba_prod TO mianba_api, mianba_worker;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO mianba_api, mianba_worker;
SQL
