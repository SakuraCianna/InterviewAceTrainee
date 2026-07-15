package icu.sakuracianna.mianba.interview.material;

import java.nio.charset.StandardCharsets;

/** 规范化不可信上传文件名，禁止路径、控制字符和超长 HTTP 头。 */
final class MaterialFilename {
    private static final int MAX_CODE_POINTS = 255;
    private static final int MAX_UTF8_BYTES = 1_024;

    private MaterialFilename() {
    }

    /** 只保留路径末段，并在 Unicode 码点边界上限制长度。 */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        StringBuilder safe = new StringBuilder(Math.min(basename.length(), MAX_CODE_POINTS));
        basename.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(safe::appendCodePoint);
        String result = safe.toString().strip();
        int count = result.codePointCount(0, result.length());
        if (count > MAX_CODE_POINTS) {
            result = result.substring(result.offsetByCodePoints(0, count - MAX_CODE_POINTS));
        }
        while (result.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES && !result.isEmpty()) {
            result = result.substring(result.offsetByCodePoints(0, 1));
        }
        return result;
    }
}
