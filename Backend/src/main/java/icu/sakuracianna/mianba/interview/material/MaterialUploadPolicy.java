package icu.sakuracianna.mianba.interview.material;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 在解析前统一校验资料类型、文件大小和归档解压上限。 */
public final class MaterialUploadPolicy {
    public static final int DEFAULT_MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    public static final int DEFAULT_MAX_PDF_PAGES = 50;
    public static final int DEFAULT_MAX_ARCHIVE_ENTRIES = 2_000;
    public static final long DEFAULT_MAX_EXPANDED_BYTES = 20L * 1024 * 1024;
    public static final int DEFAULT_MAX_TEXT_CHARS = 12_000;

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            ".pdf", "application/pdf",
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".txt", "text/plain",
            ".md", "text/markdown",
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg");

    private final int maxUploadBytes;
    private final int maxPdfPages;
    private final int maxArchiveEntries;
    private final long maxExpandedBytes;

    public MaterialUploadPolicy(
            int maxUploadBytes, int maxPdfPages, int maxArchiveEntries, long maxExpandedBytes) {
        this.maxUploadBytes = maxUploadBytes;
        this.maxPdfPages = maxPdfPages;
        this.maxArchiveEntries = maxArchiveEntries;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    /** 返回生产默认限额，API 客户端与独立解析进程必须共用该基线。 */
    public static MaterialUploadPolicy productionDefaults() {
        return new MaterialUploadPolicy(
                DEFAULT_MAX_UPLOAD_BYTES,
                DEFAULT_MAX_PDF_PAGES,
                DEFAULT_MAX_ARCHIVE_ENTRIES,
                DEFAULT_MAX_EXPANDED_BYTES);
    }

    /** 返回允许进入解析进程的最大原始请求体字节数。 */
    public int maxUploadBytes() {
        return maxUploadBytes;
    }

    /** 返回 PDF 允许解析的最大页数。 */
    public int maxPdfPages() {
        return maxPdfPages;
    }

    /** 返回 DOCX 允许展开的最大 ZIP 条目数。 */
    public int maxArchiveEntries() {
        return maxArchiveEntries;
    }

    /** 返回 DOCX 所有 ZIP 条目允许展开的最大总字节数。 */
    public long maxExpandedBytes() {
        return maxExpandedBytes;
    }

    /**
     * 在调用高成本解析器前验证文件声明、魔数和解压资源上限。
     * 只校验 MIME 声明会允许伪装文件进入 PDF、图片或压缩包解析器。
     *
     * @throws UnsafeMaterialException 文件类型不受支持或任一资源上限被突破时抛出
     */
    public void validate(String filename, String contentType, byte[] payload, ArchiveInspection inspection) {
        Objects.requireNonNull(payload, "payload");
        ArchiveInspection archiveInspection = Objects.requireNonNull(inspection, "inspection");
        if (payload.length > maxUploadBytes) {
            throw new UnsafeMaterialException("resume_file_too_large");
        }
        String extension = extensionOf(filename);
        String expectedType = ALLOWED_TYPES.get(extension);
        String normalizedType = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (expectedType == null || !expectedType.equals(normalizedType)) {
            throw new UnsafeMaterialException("unsupported_resume_format");
        }
        if (!magicMatches(extension, payload)) {
            throw new UnsafeMaterialException("resume_magic_mismatch");
        }
        if (archiveInspection.entries() > maxArchiveEntries) {
            throw new UnsafeMaterialException("resume_archive_entry_limit_exceeded");
        }
        if (archiveInspection.expandedBytes() > maxExpandedBytes) {
            throw new UnsafeMaterialException("resume_expanded_size_limit_exceeded");
        }
        if (archiveInspection.pages() > maxPdfPages) {
            throw new UnsafeMaterialException("resume_pdf_page_limit_exceeded");
        }
    }

    private static String extensionOf(String filename) {
        String normalized = filename == null ? "" : filename.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        int dot = normalized.lastIndexOf('.');
        if (dot <= slash) {
            return "";
        }
        return normalized.substring(dot);
    }

    private static boolean magicMatches(String extension, byte[] payload) {
        return switch (extension) {
            case ".pdf" -> startsWith(payload, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case ".docx" -> startsWith(payload, new byte[] {'P', 'K', 3, 4});
            case ".png" -> startsWith(payload, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            case ".jpg", ".jpeg" -> startsWith(payload, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case ".txt", ".md" -> !containsNul(payload);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] payload, byte[] prefix) {
        if (payload.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (payload[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNul(byte[] payload) {
        for (byte value : payload) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }
}
