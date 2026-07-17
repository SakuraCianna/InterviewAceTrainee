package icu.sakuracianna.mianba.interview.material;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 在独立 JVM 中执行 PDFBox 和 ZIP 解析。
 *
 * 该类型不依赖 Spring，进程应配置有限内存与 {@code -XX:+ExitOnOutOfMemoryError}；
 * 即使第三方解析器失控，API JVM 仍可继续服务并把故障映射为稳定错误。
 */
public final class MaterialParserEngine {
    private final MaterialUploadPolicy policy;
    private final int maxTextChars;

    public MaterialParserEngine(MaterialUploadPolicy policy, int maxTextChars) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maxTextChars = maxTextChars > 0
                ? Math.min(maxTextChars, MaterialUploadPolicy.DEFAULT_MAX_TEXT_CHARS)
                : MaterialUploadPolicy.DEFAULT_MAX_TEXT_CHARS;
    }

    /** 解析已认证请求中的有限文件，并再次执行类型、魔数及展开资源校验。 */
    public ParsedMaterial parse(String filename, String contentType, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        String safeFilename = MaterialFilename.sanitize(filename).toLowerCase(Locale.ROOT);
        policy.validate(safeFilename, contentType, payload, ArchiveInspection.none());
        try {
            ParsedMaterial parsed;
            if (safeFilename.endsWith(".pdf")) {
                parsed = parsePdf(payload);
            } else if (safeFilename.endsWith(".docx")) {
                parsed = parseDocx(payload);
            } else if (safeFilename.endsWith(".txt") || safeFilename.endsWith(".md")) {
                parsed = new ParsedMaterial(decodeUtf8(payload), ArchiveInspection.none());
            } else if (safeFilename.endsWith(".png")
                    || safeFilename.endsWith(".jpg")
                    || safeFilename.endsWith(".jpeg")) {
                throw new UnsafeMaterialException("image_resume_ocr_not_configured");
            } else {
                throw new UnsafeMaterialException("unsupported_resume_format");
            }
            policy.validate(safeFilename, contentType, payload, parsed.inspection());
            return new ParsedMaterial(limit(parsed.text()), parsed.inspection());
        } catch (IOException exception) {
            throw new UnsafeMaterialException("resume_parse_failed");
        }
    }

    int maxUploadBytes() {
        return policy.maxUploadBytes();
    }

    private ParsedMaterial parsePdf(byte[] payload) throws IOException {
        try (PDDocument document = Loader.loadPDF(payload)) {
            int pages = document.getNumberOfPages();
            ArchiveInspection inspection = new ArchiveInspection(0, 0, pages);
            policy.validate("resume.pdf", "application/pdf", payload, inspection);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(policy.maxPdfPages());
            BoundedTextWriter writer = new BoundedTextWriter(maxTextChars);
            stripper.writeText(document, writer);
            return new ParsedMaterial(writer.toString(), inspection);
        }
    }

    private ParsedMaterial parseDocx(byte[] payload) throws IOException {
        int entries = 0;
        long expandedBytes = 0;
        ByteArrayOutputStream documentXml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload))) {
            ZipEntry entry;
            byte[] buffer = new byte[8_192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > policy.maxArchiveEntries()) {
                    throw new UnsafeMaterialException("resume_archive_entry_limit_exceeded");
                }
                boolean documentEntry = "word/document.xml".equals(entry.getName());
                ByteArrayOutputStream current = documentEntry ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > policy.maxExpandedBytes()) {
                        throw new UnsafeMaterialException("resume_expanded_size_limit_exceeded");
                    }
                    if (current != null) {
                        current.write(buffer, 0, read);
                    }
                }
                if (current != null) {
                    documentXml = current;
                }
                zip.closeEntry();
            }
        }
        if (documentXml == null) {
            throw new UnsafeMaterialException("resume_docx_document_missing");
        }
        String xml = decodeUtf8(documentXml.toByteArray());
        String text = xml
                .replaceAll("(?i)</w:p>", "\n")
                .replaceAll("(?i)<w:tab[^>]*/>", "\t")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'");
        return new ParsedMaterial(
                limit(text), new ArchiveInspection(entries, expandedBytes));
    }

    private static String decodeUtf8(byte[] payload) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString();
    }

    private String limit(String value) {
        int codePoints = value.codePointCount(0, value.length());
        return codePoints <= maxTextChars
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maxTextChars));
    }

    /** 丢弃上限以后的 PDF 文本，避免先构造不受限的大字符串再截断。 */
    private static final class BoundedTextWriter extends Writer {
        private final StringBuilder value;
        private final int maxChars;

        private BoundedTextWriter(int maxChars) {
            this.maxChars = maxChars;
            this.value = new StringBuilder(maxChars);
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            if (value.length() >= maxChars || length <= 0) {
                return;
            }
            int writable = Math.min(length, maxChars - value.length());
            value.append(characters, offset, writable);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            int length = value.length();
            if (length > 0 && Character.isHighSurrogate(value.charAt(length - 1))) {
                return value.substring(0, length - 1);
            }
            return value.toString();
        }
    }
}
