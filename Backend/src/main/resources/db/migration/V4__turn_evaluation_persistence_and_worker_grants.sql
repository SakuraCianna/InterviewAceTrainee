CREATE FUNCTION mianba_valid_turn_code_array(value jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, pg_temp
AS $validation$
    SELECT CASE
        WHEN jsonb_typeof(value) <> 'array' THEN false
        ELSE jsonb_array_length(value) <= 16
            AND octet_length(value::text) <= 4096
            AND NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(value) AS entry(item)
                WHERE jsonb_typeof(item) <> 'string'
                   OR item #>> '{}' !~ '^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$'
                   OR char_length(item #>> '{}') > 64
            )
    END
$validation$;

REVOKE ALL ON FUNCTION mianba_valid_turn_code_array(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mianba_valid_turn_code_array(jsonb)
TO mianba_api, mianba_worker;

ALTER TABLE turns
    ADD COLUMN covered_sections jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN covered_topics jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN risk_flags jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_turns_covered_sections
        CHECK (mianba_valid_turn_code_array(covered_sections)),
    ADD CONSTRAINT ck_turns_covered_topics
        CHECK (mianba_valid_turn_code_array(covered_topics)),
    ADD CONSTRAINT ck_turns_risk_flags
        CHECK (mianba_valid_turn_code_array(risk_flags));

GRANT INSERT ON ai_jobs TO mianba_worker;
GRANT DELETE ON turn_dimension_scores TO mianba_api;
