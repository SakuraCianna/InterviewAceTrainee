package icu.sakuracianna.mianba.interview.material;

import java.util.Objects;

/** 独立材料解析进程返回的有限纯文本及资源检查统计。 */
public record ParsedMaterial(String text, ArchiveInspection inspection) {
    public ParsedMaterial {
        text = Objects.requireNonNull(text, "text");
        inspection = Objects.requireNonNull(inspection, "inspection");
    }
}
