package icu.sakuracianna.mianba.interview.material;

/** 上传资料超过格式、压缩包或解析资源安全边界。 */
public final class UnsafeMaterialException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnsafeMaterialException(String message) {
        super(message);
    }
}
