import styles from "./LegalPage.module.css";

/** 为四类政策页面复用同一组局部样式。 */
export function legalClasses(...values: Array<string | false | null | undefined>) {
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
