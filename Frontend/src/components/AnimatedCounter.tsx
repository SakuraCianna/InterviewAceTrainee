import { useLayoutEffect, useRef } from "react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

type AnimatedCounterProps = {
  value: number;
  suffix?: string;
  prefix?: string;
  decimals?: number;
  className?: string;
};

function formatValue(value: number, decimals: number) {
  return value.toLocaleString("zh-CN", {
    maximumFractionDigits: decimals,
    minimumFractionDigits: decimals,
  });
}

function prefersReducedMotion() {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function AnimatedCounter({ value, suffix = "", prefix = "", decimals = 0, className = "" }: AnimatedCounterProps) {
  const ref = useRef<HTMLElement | null>(null);

  useLayoutEffect(() => {
    if (!ref.current || prefersReducedMotion()) {
      return;
    }

    const startValue = value > 20 ? Math.max(0, value - Math.max(1, value * 0.02)) : value;
    const counter = { value: startValue };
    const ctx = gsap.context(() => {
      gsap.to(counter, {
        value,
        duration: 0.82,
        ease: "power3.out",
        scrollTrigger: {
          trigger: ref.current,
          start: "top 88%",
          once: true,
        },
        onUpdate: () => {
          if (ref.current) {
            ref.current.textContent = `${prefix}${formatValue(counter.value, decimals)}${suffix}`;
          }
        },
        onComplete: () => {
          if (ref.current) {
            ref.current.textContent = `${prefix}${formatValue(value, decimals)}${suffix}`;
          }
        },
      });
    }, ref);

    return () => ctx.revert();
  }, [decimals, prefix, suffix, value]);

  return (
    <strong className={className} ref={ref}>
      {prefix}
      {formatValue(value, decimals)}
      {suffix}
    </strong>
  );
}
