package icu.sakuracianna.mianba.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StructuredReportSchemaTest {

    @Test
    void migrationDefinesScopedReportsAndImmutableRevisions() throws IOException {
        assertThat(migrationSql())
                .contains("ADD COLUMN package_id uuid")
                .contains("report_scope")
                .contains("generation_status")
                .contains("current_revision")
                .contains("summary_source")
                .contains("CHECK (num_nonnulls(session_id, package_id) = 1)")
                .contains("CREATE UNIQUE INDEX ux_reports_session")
                .contains("WHERE session_id IS NOT NULL")
                .contains("CREATE UNIQUE INDEX ux_reports_package")
                .contains("WHERE package_id IS NOT NULL")
                .contains("CREATE TABLE report_revisions")
                .contains("UNIQUE (report_id, revision_no)")
                .contains("INSERT INTO report_revisions");
    }

    @Test
    void migrationDefinesDimensionScoresAndReportJobReferences() throws IOException {
        assertThat(migrationSql())
                .contains("CREATE TABLE turn_dimension_scores")
                .contains("turn_id uuid NOT NULL REFERENCES turns(id) ON DELETE CASCADE")
                .contains("score smallint NOT NULL CHECK (score BETWEEN 0 AND 100)")
                .contains("UNIQUE (turn_id, dimension_code)")
                .contains("ALTER TABLE ai_jobs")
                .contains("ADD COLUMN package_id uuid")
                .contains("ADD COLUMN report_id uuid")
                .contains("CREATE INDEX ix_ai_jobs_package")
                .contains("CREATE INDEX ix_ai_jobs_report");
    }

    @Test
    void migrationAddsBoundedAiCallValidationMetadataWithoutContentBodies() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("ALTER TABLE ai_call_logs")
                .contains("operation")
                .contains("prompt_version")
                .contains("output_schema_version")
                .contains("validation_status")
                .contains("finish_reason")
                .contains("attempt_no")
                .doesNotContain("prompt_body", "output_body", "prompt_text", "output_text");
    }

    @Test
    void migrationGrantsNewTablesByRuntimeResponsibility() throws IOException {
        assertThat(migrationSql())
                .contains("GRANT SELECT ON report_revisions, turn_dimension_scores TO mianba_api")
                .contains("GRANT SELECT, INSERT ON report_revisions TO mianba_worker")
                .contains("GRANT SELECT, INSERT, UPDATE ON turn_dimension_scores TO mianba_worker");
    }

    private String migrationSql() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V3__structured_reports_and_ai_observability.sql")) {
            assertThat(input).as("V3 migration resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
