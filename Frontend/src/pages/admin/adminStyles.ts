import adminStyles from "./AdminShell.module.css";
import authStyles from "../AuthPage.module.css";

/** 同时保留共享全局类与后台 CSS Module 类，支持后台组件渐进拆分。 */
export function adminClasses(...values: Array<string | false | null | undefined>) {
  return values
    .filter((value): value is string => Boolean(value))
    .flatMap((value) => value.split(/\s+/))
    .filter(Boolean)
    .map((className) => {
      const scopedClassNames = [adminStyles[className], authStyles[className]].filter(Boolean);
      return [className, ...scopedClassNames].join(" ");
    })
    .join(" ");
}
