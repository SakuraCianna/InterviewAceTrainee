import styles from "./HomePage.module.css";

/** 保留 GSAP 动画钩子类名，并为首页视觉样式附加局部作用域类名。 */
export function homeClasses(...values: Array<string | false | null | undefined>) {
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
