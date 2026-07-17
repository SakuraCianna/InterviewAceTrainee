package icu.sakuracianna.mianba.interview.material;

/** 对压缩文档解析成本的统计结果。 */
public record ArchiveInspection(int entries, long expandedBytes, int pages) {
    /** 创建不包含页数统计的归档检查结果。 */
    public ArchiveInspection(int entries, long expandedBytes) {
        this(entries, expandedBytes, 0);
    }

    /** 返回不需要归档检查的零值结果。 */
    public static ArchiveInspection none() {
        return new ArchiveInspection(0, 0, 0);
    }
}
