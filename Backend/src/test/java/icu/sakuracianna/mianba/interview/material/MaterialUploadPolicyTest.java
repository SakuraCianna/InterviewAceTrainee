package icu.sakuracianna.mianba.interview.material;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MaterialUploadPolicyTest {

    private final MaterialUploadPolicy policy = new MaterialUploadPolicy(
            5 * 1024 * 1024, 50, 2_000, 20 * 1024 * 1024);

    @Test
    void acceptsPdfOnlyWhenExtensionMimeAndMagicAgree() {
        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);

        assertThatCode(() -> policy.validate("resume.pdf", "application/pdf", pdf, ArchiveInspection.none()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate("resume.pdf", "application/pdf",
                "not-a-pdf".getBytes(StandardCharsets.UTF_8), ArchiveInspection.none()))
                .isInstanceOf(UnsafeMaterialException.class)
                .hasMessage("resume_magic_mismatch");
    }

    @Test
    void rejectsCompressedOrExpandedBombsAndOversizedFiles() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> policy.validate("resume.pdf", "application/pdf", tooLarge,
                ArchiveInspection.none())).isInstanceOf(UnsafeMaterialException.class)
                .hasMessage("resume_file_too_large");
        assertThatThrownBy(() -> policy.validate("resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Arrays.copyOf(new byte[] {'P', 'K', 3, 4}, 32),
                new ArchiveInspection(2_001, 4 * 1024 * 1024)))
                .isInstanceOf(UnsafeMaterialException.class)
                .hasMessage("resume_archive_entry_limit_exceeded");
    }
}
