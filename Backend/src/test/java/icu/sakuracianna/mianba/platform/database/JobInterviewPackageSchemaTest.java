package icu.sakuracianna.mianba.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JobInterviewPackageSchemaTest {

    @Test
    void migrationDefinesPackageStagesBillingAndTurnMetadata() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V2__job_interview_packages.sql")) {
            assertThat(input).as("V2 migration resource").isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("CREATE TABLE interview_packages")
                    .contains("CREATE TABLE interview_package_stages")
                    .contains("charged_credit IN (0, 3)")
                    .contains("TECHNICAL_FIRST", "TECHNICAL_SECOND", "HR_FINAL")
                    .contains("sequence_no", "target_duration_minutes", "IN_PROGRESS")
                    .contains("completed_at", "content_erased_at")
                    .contains("related_package_id", "redeemed_package_id")
                    .contains("section_code", "question_type", "parent_turn_id")
                    .contains("CREATE UNIQUE INDEX ux_interview_packages_one_active_user")
                    .contains("WHERE status = 'ACTIVE'")
                    .contains("ck_vouchers_single_redemption_target")
                    .contains("num_nonnulls(redeemed_session_id, redeemed_package_id) <= 1")
                    .contains("GRANT SELECT, INSERT, UPDATE ON")
                    .contains("TO mianba_api", "TO mianba_worker")
                    .doesNotContain("ALL TABLES");
        }
    }
}
