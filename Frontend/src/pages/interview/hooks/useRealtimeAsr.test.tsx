import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useRealtimeAsr } from "./useRealtimeAsr";

const originalWebSocket = globalThis.WebSocket;

class FakeWebSocket {
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readonly url: string;
  readyState = 0;
  binaryType: BinaryType = "blob";
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  send = vi.fn();

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  close = vi.fn(() => {
    this.emitClose();
  });

  emitOpen() {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.(new Event("open"));
  }

  emitMessage(payload: object) {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent);
  }

  emitClose() {
    if (this.readyState === FakeWebSocket.CLOSED) {
      return;
    }
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.(new CloseEvent("close"));
  }
}

beforeEach(() => {
  FakeWebSocket.instances = [];
  Object.defineProperty(globalThis, "WebSocket", {
    configurable: true,
    writable: true,
    value: FakeWebSocket,
  });
});

afterEach(() => {
  Object.defineProperty(globalThis, "WebSocket", {
    configurable: true,
    writable: true,
    value: originalWebSocket,
  });
});

async function connect() {
  const onMessage = vi.fn();
  const view = renderHook(() => useRealtimeAsr({ onMessage }));
  act(() => view.result.current.prepareTranscript());

  let connection!: Promise<WebSocket>;
  act(() => {
    connection = view.result.current.open("session-1", "ielts");
  });
  const socket = FakeWebSocket.instances[0];
  act(() => {
    socket.emitOpen();
    socket.emitMessage({ type: "asr_ready" });
  });
  await expect(connection).resolves.toBe(socket);
  return { ...view, socket };
}

describe("useRealtimeAsr", () => {
  it("连接中断时使用已收到的部分转写安全收尾", async () => {
    const { result, socket, unmount } = await connect();
    act(() => socket.emitMessage({ type: "asr_result", text: "A partial IELTS answer" }));

    let transcript!: Promise<string>;
    act(() => {
      transcript = result.current.waitForFinalTranscript();
      socket.emitClose();
    });

    await expect(transcript).resolves.toBe("A partial IELTS answer");
    unmount();
  });

  it("连接中断且没有任何转写时拒绝提交空答案", async () => {
    const { result, socket, unmount } = await connect();

    let transcript!: Promise<string>;
    act(() => {
      transcript = result.current.waitForFinalTranscript();
      socket.emitClose();
    });

    await expect(transcript).rejects.toThrow("实时语音识别连接已断开");
    unmount();
  });
});
