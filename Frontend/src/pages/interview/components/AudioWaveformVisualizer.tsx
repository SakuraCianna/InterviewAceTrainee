import { useEffect, useState } from "react";

export function AudioWaveformVisualizer({ isRecording }: { isRecording: boolean }) {
  const [heights, setHeights] = useState<number[]>([40, 65, 30, 85, 50, 75, 45, 90, 60, 35]);

  useEffect(() => {
    if (!isRecording) return;
    const interval = setInterval(() => {
      setHeights(
        Array.from({ length: 10 }, () => Math.floor(Math.random() * 65) + 25)
      );
    }, 120);
    return () => clearInterval(interval);
  }, [isRecording]);

  if (!isRecording) return null;

  return (
    <div className="flex items-center justify-center gap-1.5 h-10 px-4 py-2 bg-emerald-500/10 border border-emerald-500/20 rounded-full">
      <span className="w-2 h-2 rounded-full bg-emerald-500 animate-ping mr-1" />
      <span className="text-xs text-emerald-400 font-medium mr-2">麦克风正在拾音中...</span>
      <div className="flex items-center gap-1 h-full">
        {heights.map((h, i) => (
          <div
            key={i}
            className="w-1 bg-emerald-400 rounded-full transition-all duration-150 ease-out"
            style={{ height: `${h}%` }}
          />
        ))}
      </div>
    </div>
  );
}
