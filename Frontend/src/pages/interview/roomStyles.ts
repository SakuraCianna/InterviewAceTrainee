import roomStyles from "./InterviewRoom.module.css";

/** 同时保留共享全局类与页面级 CSS Module 类，便于渐进拆分旧样式。 */
export function roomClasses(...values: Array<string | false | null | undefined>) {
  return values
    .filter((value): value is string => Boolean(value))
    .flatMap((value) => value.split(/\s+/))
    .filter(Boolean)
    .map((className) => {
      const scopedClassName = roomStyles[className];
      return scopedClassName ? `${className} ${scopedClassName}` : className;
    })
    .join(" ");
}
