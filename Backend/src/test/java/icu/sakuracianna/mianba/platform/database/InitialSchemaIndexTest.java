package icu.sakuracianna.mianba.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InitialSchemaIndexTest {

    @Test
    void retentionAndPrivacyQueriesHavePredicateMatchedPartialIndexes() throws IOException {
        String schema;
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
            assertThat(input).as("V1 migration resource").isNotNull();
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(schema)
                .contains("CREATE INDEX ix_sessions_deleting_pending ON sessions(updated_at, id)")
                .contains("WHERE status = 'deleting' AND content_erased_at IS NULL")
                .contains("CREATE INDEX ix_materials_deleted_purge ON materials(updated_at, id)")
                .contains("WHERE status = 'deleted'")
                .contains("CREATE INDEX ix_content_safety_session ON content_safety(session_id)")
                .contains("WHERE session_id IS NOT NULL")
                .contains("CREATE INDEX ix_ai_jobs_active_updated ON ai_jobs(updated_at, id, session_id)")
                .contains("WHERE status IN ('QUEUED', 'RUNNING', 'RETRYING') AND session_id IS NOT NULL")
                .contains("CREATE INDEX ix_turns_open_created ON turns(created_at, id, session_id)")
                .contains("WHERE status IN ('waiting_answer', 'processing')");
    }
}
