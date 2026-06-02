import type { CSSProperties } from "react";
import { AnimatedCounter } from "./AnimatedCounter";

type MetricDialProps = {
  value: number;
  label: string;
  caption?: string;
  max?: number;
};

type MetricDialStyle = CSSProperties & {
  "--metric-percent": string;
};

export function MetricDial({ value, label, caption, max = 100 }: MetricDialProps) {
  const percent = Math.max(0, Math.min(100, (value / max) * 100));
  const metricStyle: MetricDialStyle = { "--metric-percent": `${percent}%` };

  return (
    <div className="metric-dial" style={metricStyle}>
      <div className="metric-dial-ring">
        <AnimatedCounter value={value} decimals={max === 10 ? 1 : 0} />
      </div>
      <div className="metric-dial-copy">
        <span>{label}</span>
        {caption && <p>{caption}</p>}
      </div>
    </div>
  );
}
