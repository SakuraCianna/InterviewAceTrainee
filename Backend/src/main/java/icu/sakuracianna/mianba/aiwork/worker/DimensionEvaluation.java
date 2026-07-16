package icu.sakuracianna.mianba.aiwork.worker;

/** 单一可信评价维度；字符串已由模型适配器完成边界清理和协议校验。 */
public record DimensionEvaluation(
        String code,
        int score,
        String evidence,
        String comment) {
}
