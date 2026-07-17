package icu.sakuracianna.mianba.platform.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 校验可安全写入日志、Redis 键和 PostgreSQL 文本列的幂等键。
 *
 * 限制字符集可以在 Web 边界拒绝控制字符，避免底层驱动异常被错误映射为 500。
 */
@Documented
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@NotBlank
@Size(max = 128)
@Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidIdempotencyKey {

    String message() default "幂等键格式不合法";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
