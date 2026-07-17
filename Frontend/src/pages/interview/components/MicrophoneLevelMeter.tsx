import { useEffect, useRef, useState } from "react";
import styles from "./MicrophoneLevelMeter.module.css";

type MicrophoneLevelMeterProps = {
  analyser: AnalyserNode | null;
  onVoiceDetected: () => void;
};

export function MicrophoneLevelMeter({ analyser, onVoiceDetected }: MicrophoneLevelMeterProps) {
  const [level, setLevel] = useState(0);
  const frameRef = useRef<number | null>(null);
  const detectedRef = useRef(false);
  const onVoiceDetectedRef = useRef(onVoiceDetected);
  onVoiceDetectedRef.current = onVoiceDetected;

  useEffect(() => {
    detectedRef.current = false;
    if (!analyser) {
      setLevel(0);
      return;
    }
    const samples = new Uint8Array(analyser.fftSize);
    const updateLevel = () => {
      analyser.getByteTimeDomainData(samples);
      let sum = 0;
      for (const sample of samples) {
        const normalized = (sample - 128) / 128;
        sum += normalized * normalized;
      }
      const nextLevel = Math.min(1, Math.sqrt(sum / samples.length) * 8);
      setLevel(nextLevel);
      if (nextLevel > 0.08 && !detectedRef.current) {
        detectedRef.current = true;
        onVoiceDetectedRef.current();
      }
      frameRef.current = window.requestAnimationFrame(updateLevel);
    };
    updateLevel();
    return () => {
      if (frameRef.current !== null) {
        window.cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
    };
  }, [analyser]);

  return (
    <div className={styles.meter} aria-label="麦克风音量">
      <span data-level style={{ transform: `scaleX(${Math.max(0.04, level)})` }} />
    </div>
  );
}
