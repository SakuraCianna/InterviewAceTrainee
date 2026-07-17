import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MicrophoneLevelMeter } from "./MicrophoneLevelMeter";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("MicrophoneLevelMeter", () => {
  it("在子组件内更新音量，并且只通知一次有效人声", async () => {
    vi.stubGlobal("requestAnimationFrame", vi.fn(() => 1));
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    const analyser = {
      fftSize: 8,
      getByteTimeDomainData(samples: Uint8Array) {
        samples.set([255, 0, 255, 0, 255, 0, 255, 0]);
      },
    } as unknown as AnalyserNode;
    const onVoiceDetected = vi.fn();
    const view = render(<MicrophoneLevelMeter analyser={analyser} onVoiceDetected={onVoiceDetected} />);

    await waitFor(() => expect(onVoiceDetected).toHaveBeenCalledTimes(1));
    expect(view.container.querySelector("[data-level]" )).toHaveStyle({ transform: "scaleX(1)" });

    view.rerender(<MicrophoneLevelMeter analyser={analyser} onVoiceDetected={onVoiceDetected} />);
    expect(onVoiceDetected).toHaveBeenCalledTimes(1);
  });
});
