package icu.sakuracianna.mianba.interview.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import icu.sakuracianna.mianba.identity.service.AbuseProtection;
import icu.sakuracianna.mianba.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

class JdbcMaterialServiceTest {

    @Test
    void keywordJsonRoundTripPreservesPunctuationAndLineBreaks() throws Exception {
        JdbcMaterialService service = newService(
                mock(JdbcTemplate.class), mock(AbuseProtection.class), mock(TransactionTemplate.class));
        List<String> keywords = List.of("Java,Spring", "引号\"与反斜线\\", "两行\n内容");

        String json = service.jsonArray(keywords);

        assertThat(service.parseJsonArray(json)).containsExactlyElementsOf(keywords);
    }

    @Test
    void capacityRejectsBeforeReadingSecondUploadIntoHeap() throws Exception {
        JdbcMaterialService service = newService(
                mock(JdbcTemplate.class), mock(AbuseProtection.class), mock(TransactionTemplate.class));
        MultipartFile firstFile = uploadFile();
        MultipartFile secondFile = uploadFile();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(firstFile.getBytes()).thenAnswer(invocation -> {
            reading.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "%PDF-invalid".getBytes(StandardCharsets.US_ASCII);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.upload(
                    UUID.randomUUID(), "upload-1", "job", firstFile,
                    "后端工程师", "Java", null, null, null));
            assertThat(reading.await(1, TimeUnit.SECONDS)).isTrue();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(
                            UUID.randomUUID(), "upload-2", "job", secondFile,
                            "后端工程师", "Java", null, null, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.detail()).isEqualTo("material_upload_capacity_full"));
            verify(secondFile, never()).getBytes();

            release.countDown();
            try {
                first.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                assertThat(expected.getCause()).isInstanceOf(ApiException.class);
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void dailyQuotaRejectsBeforeReadingUploadIntoHeap() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AbuseProtection abuseProtection = mock(AbuseProtection.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        JdbcMaterialService service = newService(jdbc, abuseProtection, transactions);
        MultipartFile file = uploadFile();
        UUID userId = UUID.randomUUID();
        doThrow(new ApiException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_exceeded",
                "请求过于频繁，请稍后重试"))
                .when(abuseProtection)
                .check("material-create-daily", userId.toString(), 10, Duration.ofDays(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(
                        userId, "upload-quota", "job", file,
                        "后端工程师", "Java", null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("rate_limit_exceeded"));

        verify(file, never()).getBytes();
        verify(transactions, never()).execute(any());
    }

    @Test
    void activeMaterialLimitRejectsBeforeInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcMaterialService service = newService(
                jdbc, mock(AbuseProtection.class), mock(TransactionTemplate.class));
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(userId))).thenReturn(20L);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.requireActiveMaterialCapacity(userId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("material_active_limit_reached"));
    }

    @Test
    void multipartTextRejectsDatabaseUnsafeControlCharacters() {
        JdbcMaterialService service = newService(
                mock(JdbcTemplate.class), mock(AbuseProtection.class), mock(TransactionTemplate.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(
                        UUID.randomUUID(), "upload-controls", "civil_service", null,
                        "岗位\u0000标题", null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail())
                                .isEqualTo("material_text_contains_control_character"));
    }

    @Test
    void uploadDelegatesInvalidPdfBytesToIsolatedParserInsteadOfParsingInApiProcess() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MaterialParserClient parserClient = mock(MaterialParserClient.class);
        JdbcMaterialService service = newService(
                jdbc, mock(AbuseProtection.class), mock(TransactionTemplate.class), parserClient);
        MultipartFile file = uploadFile();
        byte[] invalidPdf = "%PDF-invalid".getBytes(StandardCharsets.US_ASCII);
        when(file.getBytes()).thenReturn(invalidPdf);
        when(parserClient.parse("resume.pdf", "application/pdf", invalidPdf))
                .thenReturn(new ParsedMaterial("", ArchiveInspection.none()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(
                        UUID.randomUUID(), "upload-isolated", "job", file,
                        "后端工程师", "Java", null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.detail()).isEqualTo("resume_text_empty"));

        verify(parserClient).parse("resume.pdf", "application/pdf", invalidPdf);
    }

    private static JdbcMaterialService newService(
            JdbcTemplate jdbc,
            AbuseProtection abuseProtection,
            TransactionTemplate transactions) {
        MaterialParserClient parserClient = mock(MaterialParserClient.class);
        when(parserClient.parse(anyString(), anyString(), any(byte[].class)))
                .thenThrow(new ApiException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                        "resume_parse_failed",
                        "无法解析上传材料"));
        return newService(jdbc, abuseProtection, transactions, parserClient);
    }

    private static JdbcMaterialService newService(
            JdbcTemplate jdbc,
            AbuseProtection abuseProtection,
            TransactionTemplate transactions,
            MaterialParserClient parserClient) {
        return new JdbcMaterialService(jdbc, new ObjectMapper(), abuseProtection, transactions, parserClient);
    }

    private static MultipartFile uploadFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(12L);
        when(file.getOriginalFilename()).thenReturn("resume.pdf");
        when(file.getContentType()).thenReturn("application/pdf");
        return file;
    }
}
