package icu.sakuracianna.mianba.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LocalPrivacyRagSchemaTest {
    private static final String MIGRATION =
            "/db/migration/V5__local_privacy_rag_and_content_safety.sql";

    @Test
    void migrationRemovesEveryPersistentPrivateMaterialPath() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("DROP TABLE materials")
                .contains("ALTER TABLE sessions DROP COLUMN material_id")
                .contains("ALTER TABLE ai_jobs DROP COLUMN material_id")
                .contains("ALTER TABLE interview_packages DROP COLUMN material_id")
                .contains("ALTER TABLE interview_packages DROP COLUMN material_snapshot")
                .contains("ck_ai_jobs_input_ref_without_private_material")
                .doesNotContain("CREATE TABLE knowledge_documents")
                .doesNotContain("vector(");
    }

    @Test
    void migrationCreatesMetadataOnlyRedisIndexStateAndSafeAudit() throws IOException {
        assertThat(migrationSql())
                .contains("CREATE TABLE knowledge_index_state")
                .contains("ALTER TABLE content_safety RENAME COLUMN matched_terms TO rule_ids")
                .contains("ALTER TABLE content_safety DROP COLUMN content_excerpt")
                .contains("content_digest char(64)")
                .contains("GRANT INSERT ON content_safety TO mianba_worker")
                .contains("COMMENT ON TABLE knowledge_index_state IS '公共岗位与考研知识库在 Redis 中的向量索引状态'");
    }

    private String migrationSql() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).as("V5 migration resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
