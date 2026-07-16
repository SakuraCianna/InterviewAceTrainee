import styles from "./AuthPage.module.css";

/**
 * 同时输出稳定的业务类名和 CSS Module 类名。
 * 业务类名供 GSAP 的局部选择器使用，哈希类负责隔离页面样式。
 */
export function authClasses(...values: Array<string | false | null | undefined>) {
  return values
    .filter((value): value is string => Boolean(value))
    .flatMap((value) => value.split(/\s+/))
    .filter(Boolean)
    .map((className) => {
      const scopedClassName = styles[className];
      return scopedClassName ? `${className} ${scopedClassName}` : className;
    })
    .join(" ");
}
