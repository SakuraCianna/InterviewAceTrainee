#!/bin/sh
set -eu

# 仅用于全新数据库的首次管理员提升；邮箱来自 0600 文件，脚本不会打印其内容。
: "${MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE:?MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE is required}"
: "${MIANBA_PRODUCTION_ROOT:?MIANBA_PRODUCTION_ROOT is required}"
test -f "$MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE"
test ! -L "$MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE"
if [ "$(stat -c '%a' "$MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE")" != "600" ]; then
  echo "Bootstrap email file must use mode 0600" >&2
  exit 1
fi
test -s "$MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE"
admin_email="$(tr -d '\r\n' < "$MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE")"
test -n "$admin_email"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
compose_helper="$script_dir/production-compose.sh"
test -f "$compose_helper"
test ! -L "$compose_helper"

MIANBA_PRODUCTION_ROOT="$MIANBA_PRODUCTION_ROOT" \
  bash "$compose_helper" exec -T postgres psql \
  --set ON_ERROR_STOP=1 \
  --username mianba_owner \
  --dbname mianba_prod \
  --set bootstrap_email="$admin_email" <<'SQL'
BEGIN;

-- 将“尚无管理员”检查串行化，避免两个并发引导事务同时提升不同账号。
SELECT pg_advisory_xact_lock(hashtextextended('mianba:first-admin-bootstrap', 0));
SET LOCAL mianba.bootstrap_email = :'bootstrap_email';

DO $bootstrap$
DECLARE
    existing_admins integer;
    target_id uuid;
BEGIN
    SELECT count(*) INTO existing_admins FROM users WHERE role = 'admin';
    IF existing_admins <> 0 THEN
        RAISE EXCEPTION 'First-admin bootstrap refused because an administrator already exists';
    END IF;

    SELECT id INTO target_id
    FROM users
    WHERE email = current_setting('mianba.bootstrap_email')::citext
      AND is_active = true
      AND password_hash IS NOT NULL
    FOR UPDATE;
    IF target_id IS NULL THEN
        RAISE EXCEPTION 'Bootstrap user must exist, be active, and have a password';
    END IF;

    UPDATE users
    SET role = 'admin', auth_version = auth_version + 1,
        version = version + 1, updated_at = now()
    WHERE id = target_id;
    INSERT INTO admin_audit(
        admin_id, action, target_type, target_id, before_snapshot, after_snapshot
    ) VALUES (
        target_id, 'first_admin_bootstrap', 'user', target_id::text,
        jsonb_build_object('role', 'user'), jsonb_build_object('role', 'admin')
    );
END
$bootstrap$;

COMMIT;
SQL

echo "First administrator promotion completed; remove the bootstrap email file after verification."
