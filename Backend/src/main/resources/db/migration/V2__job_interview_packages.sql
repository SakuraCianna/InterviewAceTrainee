CREATE TABLE interview_packages (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    material_id uuid REFERENCES materials(id) ON DELETE SET NULL,
    voucher_id uuid UNIQUE REFERENCES vouchers(id) ON DELETE RESTRICT,
    start_idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXPIRED', 'CANCELLED')),
    current_stage_code varchar(32) NOT NULL DEFAULT 'TECHNICAL_FIRST'
        CHECK (current_stage_code IN ('TECHNICAL_FIRST', 'TECHNICAL_SECOND', 'HR_FINAL')),
    charged_credit integer NOT NULL CHECK (charged_credit IN (0, 3)),
    admin_unlimited_usage boolean NOT NULL DEFAULT false,
    plan_version varchar(80) NOT NULL,
    rubric_version varchar(80) NOT NULL,
    material_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(material_snapshot) = 'object')
        CHECK (octet_length(material_snapshot::text) <= 32768),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '30 days'),
    completed_at timestamptz,
    content_erased_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, start_idempotency_key),
    CHECK ((admin_unlimited_usage = false AND charged_credit = 3 AND voucher_id IS NULL)
        OR (admin_unlimited_usage = false AND charged_credit = 0 AND voucher_id IS NOT NULL)
        OR (admin_unlimited_usage = true AND charged_credit = 0 AND voucher_id IS NULL)),
    CHECK (expires_at = created_at + interval '30 days')
);
CREATE UNIQUE INDEX ux_interview_packages_one_active_user
    ON interview_packages(user_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_interview_packages_active_expires
    ON interview_packages(expires_at, id) WHERE status = 'ACTIVE';
CREATE INDEX ix_interview_packages_content_retention
    ON interview_packages(completed_at, id)
    WHERE content_erased_at IS NULL AND completed_at IS NOT NULL
      AND status IN ('COMPLETED', 'EXPIRED', 'CANCELLED');
CREATE INDEX ix_interview_packages_material
    ON interview_packages(material_id) WHERE material_id IS NOT NULL;

CREATE TABLE interview_package_stages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id uuid NOT NULL REFERENCES interview_packages(id) ON DELETE CASCADE,
    stage_code varchar(32) NOT NULL
        CHECK (stage_code IN ('TECHNICAL_FIRST', 'TECHNICAL_SECOND', 'HR_FINAL')),
    sequence_no smallint NOT NULL CHECK (sequence_no BETWEEN 1 AND 3),
    status varchar(24) NOT NULL DEFAULT 'LOCKED'
        CHECK (status IN ('LOCKED', 'UNLOCKED', 'IN_PROGRESS', 'COMPLETED', 'EXPIRED', 'CANCELLED')),
    session_id uuid UNIQUE REFERENCES sessions(id) ON DELETE SET NULL,
    plan_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(plan_snapshot) = 'object')
        CHECK (octet_length(plan_snapshot::text) <= 32768),
    context_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(context_snapshot) = 'object')
        CHECK (octet_length(context_snapshot::text) <= 65536),
    min_turns smallint NOT NULL CHECK (min_turns BETWEEN 1 AND 30),
    max_turns smallint NOT NULL CHECK (max_turns BETWEEN 1 AND 30),
    target_duration_minutes smallint NOT NULL CHECK (target_duration_minutes BETWEEN 1 AND 180),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    unlocked_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    content_erased_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (package_id, stage_code),
    UNIQUE (package_id, sequence_no),
    CHECK (min_turns <= max_turns),
    CHECK ((stage_code = 'TECHNICAL_FIRST' AND sequence_no = 1)
        OR (stage_code = 'TECHNICAL_SECOND' AND sequence_no = 2)
        OR (stage_code = 'HR_FINAL' AND sequence_no = 3))
);
CREATE INDEX ix_interview_package_stages_package_status
    ON interview_package_stages(package_id, status, sequence_no);
CREATE INDEX ix_interview_package_stages_content_retention
    ON interview_package_stages(completed_at, id)
    WHERE content_erased_at IS NULL AND completed_at IS NOT NULL
      AND status IN ('COMPLETED', 'EXPIRED', 'CANCELLED');

ALTER TABLE credit_ledger
    ADD COLUMN related_package_id uuid REFERENCES interview_packages(id) ON DELETE SET NULL;
CREATE INDEX ix_credit_ledger_related_package
    ON credit_ledger(related_package_id) WHERE related_package_id IS NOT NULL;

ALTER TABLE vouchers
    ADD COLUMN redeemed_package_id uuid REFERENCES interview_packages(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_vouchers_single_redemption_target
        CHECK (num_nonnulls(redeemed_session_id, redeemed_package_id) <= 1);
CREATE UNIQUE INDEX ux_vouchers_redeemed_package
    ON vouchers(redeemed_package_id) WHERE redeemed_package_id IS NOT NULL;

ALTER TABLE turns
    ADD COLUMN section_code varchar(48),
    ADD COLUMN question_type varchar(48),
    ADD COLUMN topic_code varchar(80),
    ADD COLUMN parent_turn_id uuid REFERENCES turns(id) ON DELETE SET NULL;
CREATE INDEX ix_turns_parent
    ON turns(parent_turn_id) WHERE parent_turn_id IS NOT NULL;
CREATE INDEX ix_turns_session_section
    ON turns(session_id, section_code, turn_index) WHERE section_code IS NOT NULL;

GRANT SELECT, INSERT, UPDATE ON
    interview_packages, interview_package_stages
TO mianba_api;

GRANT SELECT, UPDATE ON
    interview_packages, interview_package_stages
TO mianba_worker;
