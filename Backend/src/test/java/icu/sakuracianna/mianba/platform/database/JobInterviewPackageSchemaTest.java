package icu.sakuracianna.mianba.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JobInterviewPackageSchemaTest {

    @Test
    void migrationDefinesPackageStagesBillingAndTurnMetadata() throws IOException {
        assertThat(migrationSql())
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

    @Test
    void migrationPairsEveryStageCodeWithItsCanonicalSequence() throws IOException {
        assertThat(migrationSql())
                .contains("stage_code = 'TECHNICAL_FIRST' AND sequence_no = 1")
                .contains("stage_code = 'TECHNICAL_SECOND' AND sequence_no = 2")
                .contains("stage_code = 'HR_FINAL' AND sequence_no = 3");
    }

    @Test
    void migrationDefinesExactlyThreePackageBillingModes() throws IOException {
        String normalizedSql = migrationSql().replaceAll("\\s+", " ").trim();
        String expectedBillingCheck = """
                CHECK ((admin_unlimited_usage = false AND charged_credit = 3 AND voucher_id IS NULL)
                    OR (admin_unlimited_usage = false AND charged_credit = 0 AND voucher_id IS NOT NULL)
                    OR (admin_unlimited_usage = true AND charged_credit = 0 AND voucher_id IS NULL))
                """.replaceAll("\\s+", " ").trim();

        assertThat(normalizedSql)
                .contains("admin_unlimited_usage boolean NOT NULL DEFAULT false");
        assertThat(packageBillingCheck(normalizedSql))
                .isEqualTo(expectedBillingCheck);
    }

    @Test
    void migrationEnforcesAnExactThirtyDayExpiration() throws IOException {
        assertThat(migrationSql())
                .contains("CHECK (expires_at = created_at + interval '30 days')");
    }

    private String packageBillingCheck(String normalizedSql) {
        String marker = "CHECK ((admin_unlimited_usage";
        int start = normalizedSql.indexOf(marker);
        assertThat(start).as("package billing CHECK start").isGreaterThanOrEqualTo(0);

        int depth = 0;
        for (int index = normalizedSql.indexOf('(', start); index < normalizedSql.length(); index++) {
            char character = normalizedSql.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return normalizedSql.substring(start, index + 1);
            }
        }
        throw new AssertionError("Package billing CHECK is not closed");
    }

    private String migrationSql() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V2__job_interview_packages.sql")) {
            assertThat(input).as("V2 migration resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
