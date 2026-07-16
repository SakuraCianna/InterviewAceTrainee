import { useEffect, useRef, useState } from "react";
import { RECORDING_PROGRESS_INTERVAL_MS, formatDuration } from "../audio";
import styles from "./RecordingProgress.module.css";

type RecordingProgressProps = {
  active: boolean;
  startedAt: number;
  limitMs: number;
  getSilenceRemainingMs: () => number;
};

export function RecordingProgress({ active, startedAt, limitMs, getSilenceRemainingMs }: RecordingProgressProps) {
  const [elapsedMs, setElapsedMs] = useState(0);
  const [silenceRemainingMs, setSilenceRemainingMs] = useState(getSilenceRemainingMs);
  const silenceGetterRef = useRef(getSilenceRemainingMs);
  silenceGetterRef.current = getSilenceRemainingMs;

  useEffect(() => {
    if (!active) {
      setElapsedMs(0);
      return;
    }
    const update = () => {
      setElapsedMs(Math.min(limitMs, Math.max(0, Date.now() - startedAt)));
      setSilenceRemainingMs(silenceGetterRef.current());
    };
    update();
    const timerId = window.setInterval(update, RECORDING_PROGRESS_INTERVAL_MS);
    return () => window.clearInterval(timerId);
  }, [active, limitMs, startedAt]);

  if (!active) {
    return null;
  }
  const progress = Math.min(100, Math.round((elapsedMs / Math.max(limitMs, 1)) * 100));
  return (
    <section className={styles.card} aria-label="本轮回答计时">
      <div className={styles.heading}>
        <span>回答计时</span>
        <strong>{formatDuration(elapsedMs)} / {formatDuration(limitMs)}</strong>
      </div>
      <progress max={100} value={progress} aria-label="本轮回答进度" aria-valuenow={progress} />
      <div className={styles.meta}>
        <span>可提前结束回答</span>
        <span>静音自动收尾 {formatDuration(silenceRemainingMs)}</span>
      </div>
    </section>
  );
}
