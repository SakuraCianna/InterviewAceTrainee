package icu.sakuracianna.mianba.interview.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class MaterialParserEngineTest {
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test
    void parsesUtf8TextWithinOutputLimit() {
        MaterialParserEngine engine = new MaterialParserEngine(MaterialUploadPolicy.productionDefaults(), 12_000);

        ParsedMaterial parsed = engine.parse(
                "resume.txt", "text/plain", "Java 后端工程师".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.text()).isEqualTo("Java 后端工程师");
        assertThat(parsed.inspection()).isEqualTo(ArchiveInspection.none());
    }

    @Test
    void truncatesTextAtUnicodeCodePointBoundary() {
        MaterialParserEngine engine = new MaterialParserEngine(MaterialUploadPolicy.productionDefaults(), 12_000);
        String text = "面".repeat(12_001);

        ParsedMaterial parsed = engine.parse("resume.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.text()).hasSize(12_000).isEqualTo("面".repeat(12_000));
    }

    @Test
    void parsesDocumentXmlAndPreservesArchiveInspection() throws Exception {
        byte[] docx = docx("<w:document><w:body><w:p><w:r><w:t>Spring</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Java</w:t></w:r></w:p></w:body></w:document>");
        MaterialParserEngine engine = new MaterialParserEngine(MaterialUploadPolicy.productionDefaults(), 12_000);

        ParsedMaterial parsed = engine.parse("resume.docx", DOCX_TYPE, docx);

        assertThat(parsed.text()).contains("Spring").contains("Java");
        assertThat(parsed.inspection().entries()).isEqualTo(1);
        assertThat(parsed.inspection().expandedBytes()).isPositive();
    }

    @Test
    void acceptsPdfAtPageLimitAndRejectsTheNextPage() throws Exception {
        byte[] onePagePdf = pdf(1);
        byte[] twoPagePdf = pdf(2);
        MaterialUploadPolicy onePagePolicy = new MaterialUploadPolicy(
                MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES, 1,
                MaterialUploadPolicy.DEFAULT_MAX_ARCHIVE_ENTRIES,
                MaterialUploadPolicy.DEFAULT_MAX_EXPANDED_BYTES);
        MaterialParserEngine engine = new MaterialParserEngine(onePagePolicy, 12_000);

        assertThat(engine.parse("resume.pdf", "application/pdf", onePagePdf).inspection().pages())
                .isEqualTo(1);
        assertThatThrownBy(() -> engine.parse("resume.pdf", "application/pdf", twoPagePdf))
                .isInstanceOf(UnsafeMaterialException.class)
                .hasMessage("resume_pdf_page_limit_exceeded");
    }

    @Test
    void rejectsDocxWhoseActualExpandedBytesExceedLimit() throws Exception {
        byte[] docx = docx("<w:document>" + "a".repeat(256) + "</w:document>");
        MaterialUploadPolicy constrained = new MaterialUploadPolicy(
                MaterialUploadPolicy.DEFAULT_MAX_UPLOAD_BYTES,
                MaterialUploadPolicy.DEFAULT_MAX_PDF_PAGES,
                MaterialUploadPolicy.DEFAULT_MAX_ARCHIVE_ENTRIES,
                64);
        MaterialParserEngine engine = new MaterialParserEngine(constrained, 12_000);

        assertThatThrownBy(() -> engine.parse("resume.docx", DOCX_TYPE, docx))
                .isInstanceOf(UnsafeMaterialException.class)
                .hasMessage("resume_expanded_size_limit_exceeded");
    }

    private static byte[] docx(String documentXml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static byte[] pdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
