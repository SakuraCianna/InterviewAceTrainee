CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL UNIQUE,
    password_hash text,
    role varchar(16) NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
    credit_balance integer NOT NULL DEFAULT 0 CHECK (credit_balance >= 0),
    is_active boolean NOT NULL DEFAULT true,
    auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE materials (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    upload_idempotency_key varchar(128) NOT NULL,
    source_sha256 char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    interview_type varchar(32) NOT NULL CHECK (interview_type IN ('job', 'postgraduate', 'civil_service', 'ielts')),
    status varchar(24) NOT NULL DEFAULT 'ready' CHECK (status IN ('uploaded', 'analyzing', 'ready', 'failed', 'deleted')),
    resume_filename varchar(255),
    resume_content_type varchar(120),
    resume_size_bytes integer CHECK (resume_size_bytes BETWEEN 0 AND 5242880),
    resume_text text CHECK (resume_text IS NULL OR char_length(resume_text) <= 12000),
    job_title varchar(160),
    job_requirements text CHECK (job_requirements IS NULL OR char_length(job_requirements) <= 8000),
    target_school varchar(160),
    major varchar(160),
    research_direction varchar(240),
    profile_summary text NOT NULL DEFAULT '',
    keywords jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(keywords) = 'array'),
    embedding vector(1536),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    retention_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_materials_user_created ON materials(user_id, created_at DESC);
CREATE INDEX ix_materials_retention ON materials(retention_until)
    WHERE status <> 'deleted' AND retention_until IS NOT NULL;
CREATE INDEX ix_materials_deleted_purge ON materials(updated_at, id)
    WHERE status = 'deleted';
CREATE UNIQUE INDEX ux_materials_user_upload_idempotency
    ON materials(user_id, upload_idempotency_key);

CREATE TABLE sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    start_idempotency_key varchar(128) NOT NULL,
    material_id uuid REFERENCES materials(id) ON DELETE SET NULL,
    interview_type varchar(32) NOT NULL CHECK (interview_type IN ('job', 'postgraduate', 'civil_service', 'ielts')),
    status varchar(24) NOT NULL DEFAULT 'active' CHECK (status IN ('created', 'active', 'awaiting_ai', 'completed', 'cancelled', 'deleting', 'deleted')),
    current_turn_index integer NOT NULL DEFAULT 0 CHECK (current_turn_index >= 0),
    total_turns integer NOT NULL CHECK (total_turns BETWEEN 1 AND 30),
    charged_credit integer NOT NULL DEFAULT 0 CHECK (charged_credit >= 0),
    voucher_id uuid,
    admin_unlimited_usage boolean NOT NULL DEFAULT false,
    failure_code varchar(80),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    content_erased_at timestamptz,
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_sessions_user_status_created ON sessions(user_id, status, created_at DESC);
CREATE INDEX ix_sessions_material ON sessions(material_id) WHERE material_id IS NOT NULL;
CREATE INDEX ix_sessions_content_retention ON sessions(ended_at, id)
    WHERE content_erased_at IS NULL AND ended_at IS NOT NULL
      AND status IN ('completed', 'cancelled', 'deleting', 'deleted');
CREATE INDEX ix_sessions_deleting_pending ON sessions(updated_at, id)
    WHERE status = 'deleting' AND content_erased_at IS NULL;
CREATE UNIQUE INDEX ux_sessions_user_start_idempotency ON sessions(user_id, start_idempotency_key);
CREATE UNIQUE INDEX ux_sessions_one_open_per_user ON sessions(user_id)
    WHERE status IN ('created', 'active', 'awaiting_ai');
CREATE INDEX ix_sessions_open_expires ON sessions(expires_at)
    WHERE status IN ('created', 'active', 'awaiting_ai');

CREATE TABLE turns (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    turn_index integer NOT NULL CHECK (turn_index >= 0),
    round_name varchar(80) NOT NULL,
    question_text text NOT NULL CHECK (char_length(question_text) BETWEEN 1 AND 4000),
    answer_text text CHECK (answer_text IS NULL OR char_length(answer_text) BETWEEN 1 AND 8000),
    answer_idempotency_key varchar(128),
    evaluation_score smallint CHECK (evaluation_score BETWEEN 0 AND 100),
    evaluation_feedback text CHECK (evaluation_feedback IS NULL OR char_length(evaluation_feedback) <= 1200),
    evaluated_at timestamptz,
    status varchar(24) NOT NULL DEFAULT 'waiting_answer' CHECK (status IN ('waiting_answer', 'processing', 'answered', 'cancelled')),
    created_at timestamptz NOT NULL DEFAULT now(),
    answered_at timestamptz,
    UNIQUE (session_id, turn_index),
    UNIQUE (session_id, answer_idempotency_key)
);

CREATE TABLE reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL UNIQUE REFERENCES sessions(id) ON DELETE CASCADE,
    total_score integer NOT NULL CHECK (total_score BETWEEN 0 AND 100),
    report_json jsonb NOT NULL CHECK (jsonb_typeof(report_json) = 'object'),
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_turns_open_created ON turns(created_at, id, session_id)
    WHERE status IN ('waiting_answer', 'processing');

CREATE TABLE credit_ledger (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    change_amount integer NOT NULL CHECK (change_amount <> 0),
    balance_after integer NOT NULL CHECK (balance_after >= 0),
    reason varchar(80) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    related_session_id uuid REFERENCES sessions(id) ON DELETE SET NULL,
    operator_admin_id uuid REFERENCES users(id) ON DELETE SET NULL,
    note text CHECK (note IS NULL OR char_length(note) <= 1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX ix_credit_ledger_user_created ON credit_ledger(user_id, created_at DESC);

CREATE TABLE vouchers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issue_idempotency_key varchar(128) NOT NULL,
    voucher_type varchar(80) NOT NULL DEFAULT 'admin_grant',
    scope_interview_type varchar(32) CHECK (scope_interview_type IS NULL OR scope_interview_type IN ('job', 'postgraduate', 'civil_service', 'ielts')),
    remaining_uses integer NOT NULL DEFAULT 1 CHECK (remaining_uses BETWEEN 0 AND 20),
    status varchar(24) NOT NULL DEFAULT 'available' CHECK (status IN ('available', 'redeemed', 'expired', 'cancelled')),
    issue_reason varchar(120) NOT NULL,
    issued_by_admin_id uuid REFERENCES users(id) ON DELETE SET NULL,
    note text CHECK (note IS NULL OR char_length(note) <= 1000),
    redeemed_session_id uuid REFERENCES sessions(id) ON DELETE SET NULL,
    redeemed_at timestamptz,
    expires_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'redeemed') = (redeemed_at IS NOT NULL))
);
ALTER TABLE sessions ADD CONSTRAINT fk_sessions_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id) ON DELETE SET NULL;
CREATE INDEX ix_vouchers_user_status ON vouchers(user_id, status, expires_at);
CREATE UNIQUE INDEX ux_vouchers_user_issue_idempotency
    ON vouchers(user_id, issue_idempotency_key);

CREATE TABLE providers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_type varchar(16) NOT NULL CHECK (provider_type IN ('llm', 'asr', 'tts')),
    provider_name varchar(80) NOT NULL,
    display_name varchar(120) NOT NULL,
    model_name varchar(120) NOT NULL,
    purpose varchar(80) NOT NULL DEFAULT 'general',
    enabled boolean NOT NULL DEFAULT true,
    priority integer NOT NULL DEFAULT 100 CHECK (priority BETWEEN 1 AND 10000),
    region varchar(16) NOT NULL DEFAULT 'cn',
    api_key_hint varchar(32),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_type, provider_name, model_name, purpose)
);

CREATE TABLE system_configs (
    config_key varchar(120) PRIMARY KEY,
    value_json jsonb NOT NULL,
    description text NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_by_admin_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ai_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id uuid REFERENCES sessions(id) ON DELETE CASCADE,
    material_id uuid REFERENCES materials(id) ON DELETE CASCADE,
    kind varchar(40) NOT NULL CHECK (kind IN ('ANALYZE_MATERIAL', 'GENERATE_FOLLOW_UP', 'GENERATE_REPORT', 'SYNTHESIZE_SPEECH')),
    status varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'RETRYING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    stage varchar(48) NOT NULL DEFAULT 'WAITING_FOR_WORKER',
    progress integer NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
    max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 3),
    retryable boolean NOT NULL DEFAULT true,
    manual_retry_count smallint NOT NULL DEFAULT 0 CHECK (manual_retry_count BETWEEN 0 AND 1),
    manual_retry_key varchar(128),
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    input_ref jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(input_ref) = 'object'),
    result_ref jsonb CHECK (result_ref IS NULL OR jsonb_typeof(result_ref) = 'object'),
    error_code varchar(80),
    error_message varchar(500),
    lease_owner varchar(120),
    lease_until timestamptz,
    next_attempt_at timestamptz,
    expires_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, idempotency_key),
    CHECK ((manual_retry_count = 0) = (manual_retry_key IS NULL)),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (error_code IS NULL OR status IN ('RETRYING', 'FAILED', 'CANCELLED'))
);
CREATE INDEX ix_ai_jobs_dispatch ON ai_jobs(status, next_attempt_at, created_at);
CREATE INDEX ix_ai_jobs_owner_created ON ai_jobs(owner_id, created_at DESC);
CREATE INDEX ix_ai_jobs_session ON ai_jobs(session_id, created_at DESC);
CREATE INDEX ix_ai_jobs_material ON ai_jobs(material_id) WHERE material_id IS NOT NULL;
CREATE INDEX ix_ai_jobs_expired_lease ON ai_jobs(lease_until) WHERE status = 'RUNNING';
CREATE INDEX ix_ai_jobs_active_updated ON ai_jobs(updated_at, id, session_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRYING') AND session_id IS NOT NULL;

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    event_type varchar(120) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    correlation_id varchar(96) NOT NULL,
    trace_id varchar(96),
    publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    claim_owner varchar(120),
    claim_until timestamptz,
    last_error varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);
CREATE INDEX ix_outbox_unpublished ON outbox_events(available_at, created_at) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published ON outbox_events(published_at) WHERE published_at IS NOT NULL;

CREATE TABLE runtime_heartbeats (
    role varchar(32) PRIMARY KEY CHECK (role IN ('worker')),
    instance_id varchar(120) NOT NULL,
    consumers_ready boolean NOT NULL DEFAULT false,
    rabbit_ready boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE processed_messages (
    consumer_name varchar(120) NOT NULL,
    message_id uuid NOT NULL,
    job_id uuid NOT NULL REFERENCES ai_jobs(id) ON DELETE CASCADE,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, message_id)
);
CREATE INDEX ix_processed_messages_processed_at ON processed_messages(processed_at);

CREATE TABLE ai_call_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id uuid REFERENCES ai_jobs(id) ON DELETE SET NULL,
    session_id uuid REFERENCES sessions(id) ON DELETE SET NULL,
    provider_id uuid REFERENCES providers(id) ON DELETE SET NULL,
    provider_type varchar(16) NOT NULL,
    provider_name varchar(80) NOT NULL,
    model_name varchar(120) NOT NULL,
    purpose varchar(80) NOT NULL,
    success boolean NOT NULL,
    latency_ms integer CHECK (latency_ms IS NULL OR latency_ms >= 0),
    provider_request_id varchar(160),
    input_tokens integer CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens integer CHECK (output_tokens IS NULL OR output_tokens >= 0),
    audio_duration_ms integer CHECK (audio_duration_ms IS NULL OR audio_duration_ms >= 0),
    characters integer CHECK (characters IS NULL OR characters >= 0),
    estimated_cost_cents integer CHECK (estimated_cost_cents IS NULL OR estimated_cost_cents >= 0),
    error_code varchar(80),
    usage_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_call_logs_created ON ai_call_logs(created_at DESC);

CREATE TABLE content_safety (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    session_id uuid REFERENCES sessions(id) ON DELETE SET NULL,
    source varchar(32) NOT NULL,
    action varchar(40) NOT NULL,
    risk_level varchar(20) NOT NULL,
    categories jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(categories) = 'array'),
    matched_terms jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(matched_terms) = 'array'),
    content_excerpt text CHECK (content_excerpt IS NULL OR char_length(content_excerpt) <= 500),
    message_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_content_safety_created ON content_safety(created_at DESC);
CREATE INDEX ix_content_safety_session ON content_safety(session_id)
    WHERE session_id IS NOT NULL;

CREATE TABLE auth_login (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    email citext NOT NULL,
    auth_method varchar(40) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('user', 'admin')),
    success boolean NOT NULL,
    failure_reason varchar(120),
    ip_address inet,
    user_agent text,
    request_id varchar(96),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_auth_login_email_created ON auth_login(email, created_at DESC);
CREATE INDEX ix_auth_login_created ON auth_login(created_at DESC);

CREATE TABLE customer_notes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key varchar(128) NOT NULL,
    category varchar(80) NOT NULL,
    content text NOT NULL CHECK (char_length(content) BETWEEN 2 AND 2000),
    related_session_id uuid REFERENCES sessions(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_customer_notes_admin_idempotency
    ON customer_notes(admin_id, idempotency_key);
CREATE INDEX ix_customer_notes_user_created ON customer_notes(user_id, created_at DESC);

CREATE TABLE refund (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status varchar(32) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'processing', 'investigating', 'resolved', 'rejected', 'cancelled')),
    reason varchar(120) NOT NULL,
    description text NOT NULL CHECK (char_length(description) BETWEEN 2 AND 3000),
    amount_cents integer CHECK (amount_cents IS NULL OR amount_cents >= 0),
    currency varchar(16) NOT NULL DEFAULT 'CNY',
    credit_adjustment integer,
    related_session_id uuid REFERENCES sessions(id) ON DELETE SET NULL,
    resolution text CHECK (resolution IS NULL OR char_length(resolution) <= 3000),
    created_by_admin_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key varchar(128) NOT NULL,
    updated_by_admin_id uuid REFERENCES users(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_refund_admin_idempotency
    ON refund(created_by_admin_id, idempotency_key);
CREATE INDEX ix_refund_created ON refund(created_at DESC);
CREATE INDEX ix_refund_user_created ON refund(user_id, created_at DESC);

CREATE TABLE admin_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action varchar(80) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id varchar(120) NOT NULL,
    before_snapshot jsonb,
    after_snapshot jsonb,
    ip_address inet,
    user_agent text,
    request_id varchar(96),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_audit_created ON admin_audit(created_at DESC);

CREATE TABLE admin_operations (
    id uuid PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    operation_type varchar(80) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    result_json jsonb CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE (admin_id, operation_type, idempotency_key)
);

INSERT INTO system_configs(config_key, value_json, description) VALUES
    ('registration_open', 'true'::jsonb, '是否开放新用户注册'),
    ('password_login_enabled', 'true'::jsonb, '是否允许密码登录'),
    ('email_code_login_enabled', 'true'::jsonb, '是否允许邮箱验证码登录'),
    ('new_user_default_credits', '0'::jsonb, '新用户默认次数'),
    ('new_user_trial_vouchers', '1'::jsonb, '新用户体验券数量');

INSERT INTO providers(provider_type, provider_name, display_name, model_name, purpose, enabled, priority, region, api_key_hint)
VALUES ('llm', 'deepseek', 'DeepSeek', 'deepseek-chat', 'interview', true, 100, 'cn', 'server-secret');

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO mianba_api, mianba_worker;

GRANT SELECT, INSERT, UPDATE ON
    users, materials, sessions, turns, vouchers, providers, system_configs,
    ai_jobs, outbox_events, refund, admin_operations
TO mianba_api;
GRANT SELECT, INSERT ON
    credit_ledger, auth_login, customer_notes, admin_audit
TO mianba_api;
GRANT SELECT, INSERT, UPDATE ON content_safety TO mianba_api;
GRANT SELECT ON reports, ai_call_logs, runtime_heartbeats TO mianba_api;
GRANT DELETE ON reports, materials TO mianba_api;
GRANT DELETE ON auth_login, ai_call_logs, content_safety TO mianba_api;
GRANT SELECT, DELETE ON processed_messages TO mianba_api;
GRANT DELETE ON outbox_events TO mianba_api;

GRANT SELECT, UPDATE ON ai_jobs, sessions TO mianba_worker;
GRANT SELECT, INSERT, UPDATE ON turns, reports TO mianba_worker;
GRANT SELECT ON providers, processed_messages TO mianba_worker;
GRANT INSERT ON processed_messages, outbox_events, ai_call_logs TO mianba_worker;
GRANT SELECT, INSERT, UPDATE ON runtime_heartbeats TO mianba_worker;

-- 新迁移必须显式为对应运行角色授权，禁止用 ALL TABLES 扩大 Worker 权限。
