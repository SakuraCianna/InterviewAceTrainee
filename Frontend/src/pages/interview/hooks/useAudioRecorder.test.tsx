import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAudioRecorder } from "./useAudioRecorder";

const realtimeAsr = vi.hoisted(() => ({
  clearTranscript: vi.fn(),
  prepareTranscript: vi.fn(),
  open: vi.fn(),
  waitForFinalTranscript: vi.fn(),
  close: vi.fn(),
}));

vi.mock("./useRealtimeAsr", () => ({
  useRealtimeAsr: () => ({ transcriptPreview: "", ...realtimeAsr }),
}));

function Harness() {
  const recorder = useAudioRecorder({
    selectedMicrophoneId: "",
    onMessage: () => undefined,
    onAutoFinish: () => undefined,
  });
  return (
    <div>
      <span data-testid="preparing">{String(recorder.isPreparing)}</span>
      <span data-testid="recording">{String(recorder.isRecording)}</span>
      <button
        type="button"
        onClick={() => void recorder.start({ sessionId: "session-1", interviewType: "job" }).catch(() => undefined)}
      >
        开始
      </button>
      <button type="button" onClick={recorder.reset}>取消</button>
    </div>
  );
}

describe("useAudioRecorder", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(window, "AudioContext", {
      configurable: true,
      value: class FakeAudioContext {},
    });
  });

  it("取消准备后释放迟到的麦克风流且不再建立 ASR", async () => {
    let resolveStream: (stream: MediaStream) => void = () => undefined;
    const stopTrack = vi.fn();
    const stream = { getTracks: () => [{ stop: stopTrack }] } as unknown as MediaStream;
    const getUserMedia = vi.fn(() => new Promise<MediaStream>((resolve) => {
      resolveStream = resolve;
    }));
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: { getUserMedia },
    });
    render(<Harness />);

    await userEvent.click(screen.getByRole("button", { name: "开始" }));
    await waitFor(() => expect(screen.getByTestId("preparing")).toHaveTextContent("true"));
    await userEvent.click(screen.getByRole("button", { name: "取消" }));
    await act(async () => resolveStream(stream));

    await waitFor(() => expect(stopTrack).toHaveBeenCalledTimes(1));
    expect(realtimeAsr.open).not.toHaveBeenCalled();
    expect(screen.getByTestId("preparing")).toHaveTextContent("false");
    expect(screen.getByTestId("recording")).toHaveTextContent("false");
  });
});
