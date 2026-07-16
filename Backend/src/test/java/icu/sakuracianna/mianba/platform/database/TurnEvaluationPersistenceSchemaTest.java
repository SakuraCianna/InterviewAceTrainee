package icu.sakuracianna.mianba.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TurnEvaluationPersistenceSchemaTest {

    @Test
    void migrationAddsBoundedCodeArraysForEveryTurnEvaluationFacet() throws IOException {
        assertThat(migrationSql())
                .contains("ADD COLUMN covered_sections jsonb NOT NULL DEFAULT '[]'::jsonb")
                .contains("ADD COLUMN covered_topics jsonb NOT NULL DEFAULT '[]'::jsonb")
                .contains("ADD COLUMN risk_flags jsonb NOT NULL DEFAULT '[]'::jsonb")
                .contains("CREATE FUNCTION mianba_valid_turn_code_array(value jsonb)")
                .contains("IMMUTABLE", "STRICT", "PARALLEL SAFE")
                .contains("SET search_path = pg_catalog, pg_temp")
                .contains("jsonb_typeof(value)")
                .contains("jsonb_array_length(value) <= 16")
                .contains("octet_length(value::text) <= 4096")
                .contains("jsonb_array_elements(value)")
                .contains("jsonb_typeof(item) <> 'string'")
                .contains("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$")
                .contains("char_length(item #>> '{}') > 64")
                .contains("ck_turns_covered_sections")
                .contains("ck_turns_covered_topics")
                .contains("ck_turns_risk_flags");
    }

    @Test
    void migrationGrantsOnlyNewRuntimeResponsibilities() throws IOException {
        assertThat(migrationSql())
                .contains("REVOKE ALL ON FUNCTION mianba_valid_turn_code_array(jsonb) FROM PUBLIC")
                .contains("GRANT EXECUTE ON FUNCTION mianba_valid_turn_code_array(jsonb)")
                .contains("TO mianba_api, mianba_worker")
                .contains("GRANT INSERT ON ai_jobs TO mianba_worker")
                .contains("GRANT DELETE ON turn_dimension_scores TO mianba_api")
                .doesNotContain("ALL TABLES")
                .doesNotContain("GRANT DELETE ON ai_jobs")
                .doesNotContain("GRANT DELETE ON turn_dimension_scores TO mianba_worker");
    }

    private String migrationSql() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V4__turn_evaluation_persistence_and_worker_grants.sql")) {
            assertThat(input).as("V4 migration resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
