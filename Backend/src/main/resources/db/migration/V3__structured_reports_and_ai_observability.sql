ALTER TABLE reports
    ADD COLUMN package_id uuid REFERENCES interview_packages(id) ON DELETE CASCADE,
    ADD COLUMN report_scope varchar(16),
    ADD COLUMN generation_status varchar(32),
    ADD COLUMN current_revision integer,
    ADD COLUMN summary_source varchar(24),
    ADD COLUMN prompt_version varchar(80),
    ADD COLUMN rubric_version varchar(80),
    ADD COLUMN enhanced_at timestamptz,
    ADD COLUMN enhancement_attempts smallint,
    ADD COLUMN enhancement_error_code varchar(80);

UPDATE reports
SET report_scope = 'SESSION',
    generation_status = 'BASE_READY',
    current_revision = 1,
    summary_source = 'TEMPLATE',
    prompt_version = 'legacy-unknown',
    rubric_version = 'legacy-unknown',
    enhancement_attempts = 0;

ALTER TABLE reports
    ALTER COLUMN session_id DROP NOT NULL,
    ALTER COLUMN report_scope SET DEFAULT 'SESSION',
    ALTER COLUMN report_scope SET NOT NULL,
    ALTER COLUMN generation_status SET DEFAULT 'BASE_READY',
    ALTER COLUMN generation_status SET NOT NULL,
    ALTER COLUMN current_revision SET DEFAULT 1,
    ALTER COLUMN current_revision SET NOT NULL,
    ALTER COLUMN summary_source SET DEFAULT 'TEMPLATE',
    ALTER COLUMN summary_source SET NOT NULL,
    ALTER COLUMN prompt_version SET DEFAULT 'legacy-unknown',
    ALTER COLUMN prompt_version SET NOT NULL,
    ALTER COLUMN rubric_version SET DEFAULT 'legacy-unknown',
    ALTER COLUMN rubric_version SET NOT NULL,
    ALTER COLUMN enhancement_attempts SET DEFAULT 0,
    ALTER COLUMN enhancement_attempts SET NOT NULL,
    ADD CONSTRAINT ck_reports_single_subject
        CHECK (num_nonnulls(session_id, package_id) = 1),
    ADD CONSTRAINT ck_reports_scope
        CHECK (report_scope IN ('SESSION', 'PACKAGE')),
    ADD CONSTRAINT ck_reports_scope_subject
        CHECK ((report_scope = 'SESSION' AND session_id IS NOT NULL AND package_id IS NULL)
            OR (report_scope = 'PACKAGE' AND package_id IS NOT NULL AND session_id IS NULL)),
    ADD CONSTRAINT ck_reports_generation_status
        CHECK (generation_status IN (
            'BASE_READY', 'ENHANCING', 'ENHANCED', 'ENHANCEMENT_FAILED')),
    ADD CONSTRAINT ck_reports_current_revision
        CHECK (current_revision > 0),
    ADD CONSTRAINT ck_reports_summary_source
        CHECK (summary_source IN ('TEMPLATE', 'AI')),
    ADD CONSTRAINT ck_reports_prompt_version
        CHECK (char_length(btrim(prompt_version)) BETWEEN 1 AND 80),
    ADD CONSTRAINT ck_reports_rubric_version
        CHECK (char_length(btrim(rubric_version)) BETWEEN 1 AND 80),
    ADD CONSTRAINT ck_reports_enhanced_at
        CHECK (enhanced_at IS NULL OR enhanced_at >= created_at),
    ADD CONSTRAINT ck_reports_enhancement_attempts
        CHECK (enhancement_attempts BETWEEN 0 AND 4),
    ADD CONSTRAINT ck_reports_enhancement_error_code
        CHECK (enhancement_error_code IS NULL
            OR char_length(btrim(enhancement_error_code)) BETWEEN 1 AND 80);

-- V1 使用 table-level UNIQUE，约束名可能由 PostgreSQL 自动生成，不能依赖固定名称。
DO $drop_reports_session_unique$
DECLARE
    unique_constraint record;
BEGIN
    FOR unique_constraint IN
        SELECT constraint_row.conname
        FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'reports'::regclass
          AND constraint_row.contype = 'u'
          AND array_length(constraint_row.conkey, 1) = 1
          AND EXISTS (
              SELECT 1
              FROM unnest(constraint_row.conkey) AS constrained_column(attribute_number)
              JOIN pg_attribute attribute_row
                ON attribute_row.attrelid = constraint_row.conrelid
               AND attribute_row.attnum = constrained_column.attribute_number
              WHERE attribute_row.attname = 'session_id'
          )
    LOOP
        EXECUTE format(
                'ALTER TABLE reports DROP CONSTRAINT %I',
                unique_constraint.conname);
    END LOOP;
END
$drop_reports_session_unique$;

CREATE UNIQUE INDEX ux_reports_session
    ON reports(session_id) WHERE session_id IS NOT NULL;
CREATE UNIQUE INDEX ux_reports_package
    ON reports(package_id) WHERE package_id IS NOT NULL;

CREATE TABLE report_revisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    source varchar(24) NOT NULL
        CHECK (source IN ('TEMPLATE', 'AI_ENHANCEMENT')),
    report_json jsonb NOT NULL CHECK (jsonb_typeof(report_json) = 'object'),
    generated_job_id uuid REFERENCES ai_jobs(id) ON DELETE SET NULL,
    provider_name varchar(80)
        CHECK (provider_name IS NULL OR char_length(btrim(provider_name)) BETWEEN 1 AND 80),
    model_name varchar(120)
        CHECK (model_name IS NULL OR char_length(btrim(model_name)) BETWEEN 1 AND 120),
    prompt_version varchar(80)
        CHECK (prompt_version IS NULL OR char_length(btrim(prompt_version)) BETWEEN 1 AND 80),
    rubric_version varchar(80)
        CHECK (rubric_version IS NULL OR char_length(btrim(rubric_version)) BETWEEN 1 AND 80),
    output_schema_version varchar(40)
        CHECK (output_schema_version IS NULL
            OR char_length(btrim(output_schema_version)) BETWEEN 1 AND 40),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (report_id, revision_no)
);
CREATE INDEX ix_report_revisions_generated_job
    ON report_revisions(generated_job_id) WHERE generated_job_id IS NOT NULL;

INSERT INTO report_revisions(
    report_id, revision_no, source, report_json, prompt_version, rubric_version,
    output_schema_version, created_at)
SELECT id, 1, 'TEMPLATE', report_json, prompt_version, rubric_version,
       schema_version::text, created_at
FROM reports;

CREATE TABLE turn_dimension_scores (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    turn_id uuid NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
    dimension_code varchar(48) NOT NULL
        CHECK (dimension_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    score smallint NOT NULL CHECK (score BETWEEN 0 AND 100),
    evidence text NOT NULL CHECK (char_length(evidence) BETWEEN 1 AND 800),
    comment text CHECK (comment IS NULL OR char_length(comment) <= 1200),
    rubric_version varchar(80) NOT NULL
        CHECK (char_length(btrim(rubric_version)) BETWEEN 1 AND 80),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (turn_id, dimension_code)
);

ALTER TABLE ai_jobs
    ADD COLUMN package_id uuid REFERENCES interview_packages(id) ON DELETE CASCADE,
    ADD COLUMN report_id uuid REFERENCES reports(id) ON DELETE SET NULL;
CREATE INDEX ix_ai_jobs_package
    ON ai_jobs(package_id, created_at DESC) WHERE package_id IS NOT NULL;
CREATE INDEX ix_ai_jobs_report
    ON ai_jobs(report_id, created_at DESC) WHERE report_id IS NOT NULL;

ALTER TABLE ai_call_logs
    ADD COLUMN operation varchar(48),
    ADD COLUMN prompt_version varchar(80),
    ADD COLUMN output_schema_version varchar(40),
    ADD COLUMN validation_status varchar(24),
    ADD COLUMN finish_reason varchar(80),
    ADD COLUMN attempt_no smallint;

UPDATE ai_call_logs
SET operation = 'LEGACY',
    validation_status = 'NOT_RECORDED',
    attempt_no = 1;

ALTER TABLE ai_call_logs
    ALTER COLUMN operation SET DEFAULT 'LEGACY',
    ALTER COLUMN operation SET NOT NULL,
    ALTER COLUMN validation_status SET DEFAULT 'NOT_RECORDED',
    ALTER COLUMN validation_status SET NOT NULL,
    ALTER COLUMN attempt_no SET DEFAULT 1,
    ALTER COLUMN attempt_no SET NOT NULL,
    ADD CONSTRAINT ck_ai_call_logs_operation
        CHECK (operation IN (
            'LEGACY',
            'MATERIAL_ANALYSIS',
            'TURN_EVALUATION',
            'REPORT_ENHANCEMENT',
            'PACKAGE_REPORT',
            'SPEECH_RECOGNITION',
            'SPEECH_SYNTHESIS')),
    ADD CONSTRAINT ck_ai_call_logs_prompt_version
        CHECK (prompt_version IS NULL OR char_length(btrim(prompt_version)) BETWEEN 1 AND 80),
    ADD CONSTRAINT ck_ai_call_logs_output_schema_version
        CHECK (output_schema_version IS NULL
            OR char_length(btrim(output_schema_version)) BETWEEN 1 AND 40),
    ADD CONSTRAINT ck_ai_call_logs_validation_status
        CHECK (validation_status IN (
            'NOT_RECORDED', 'NOT_APPLICABLE', 'VALID', 'INVALID', 'FAILED')),
    ADD CONSTRAINT ck_ai_call_logs_finish_reason
        CHECK (finish_reason IS NULL OR char_length(btrim(finish_reason)) BETWEEN 1 AND 80),
    ADD CONSTRAINT ck_ai_call_logs_attempt_no
        CHECK (attempt_no BETWEEN 1 AND 4);

GRANT SELECT ON report_revisions, turn_dimension_scores TO mianba_api;
GRANT SELECT, INSERT ON report_revisions TO mianba_worker;
GRANT SELECT, INSERT, UPDATE ON turn_dimension_scores TO mianba_worker;
