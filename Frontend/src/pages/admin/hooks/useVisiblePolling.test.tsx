import { act, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useVisiblePolling } from "./useVisiblePolling";

function Harness({ poll }: { poll: (signal: AbortSignal) => Promise<void> }) {
  useVisiblePolling({ enabled: true, intervalMs: 1_000, poll });
  return null;
}

afterEach(() => {
  vi.useRealTimers();
  Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
});

describe("useVisiblePolling", () => {
  it("可见时低频轮询，隐藏暂停，卸载中止信号", async () => {
    vi.useFakeTimers();
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
    const signals: AbortSignal[] = [];
    const poll = vi.fn(async (signal: AbortSignal) => {
      signals.push(signal);
    });
    const view = render(<Harness poll={poll} />);

    await act(async () => vi.advanceTimersByTimeAsync(1_000));
    expect(poll).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    document.dispatchEvent(new Event("visibilitychange"));
    await act(async () => vi.advanceTimersByTimeAsync(5_000));
    expect(poll).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
    document.dispatchEvent(new Event("visibilitychange"));
    await act(async () => Promise.resolve());
    expect(poll).toHaveBeenCalledTimes(2);

    view.unmount();
    expect(signals.at(-1)?.aborted).toBe(true);
  });
});
