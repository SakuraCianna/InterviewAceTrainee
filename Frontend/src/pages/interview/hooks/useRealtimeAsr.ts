import { useCallback, useEffect, useRef, useState } from "react";
import { getApiErrorMessage } from "../../../lib/api";
import { FINAL_TRANSCRIPT_TIMEOUT_MS, PCM_SAMPLE_RATE } from "../audio";
import type { InterviewType, RealtimeAsrMessage, TranscriptWaiter } from "../types";

type UseRealtimeAsrOptions = {
  onMessage: (message: string) => void;
};

export function useRealtimeAsr({ onMessage }: UseRealtimeAsrOptions) {
  const [transcriptPreview, setTranscriptPreview] = useState("");
  const socketRef = useRef<WebSocket | null>(null);
  const transcriptRef = useRef("");
  const waiterRef = useRef<TranscriptWaiter | null>(null);
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;

  const setTranscript = useCallback((text: string) => {
    transcriptRef.current = text;
    setTranscriptPreview(text);
  }, []);

  const resolveWaiter = useCallback((text: string) => {
    const waiter = waiterRef.current;
    if (!waiter || waiter.settled) {
      return;
    }
    waiter.settled = true;
    waiter.resolve(text);
  }, []);

  const rejectWaiter = useCallback((error: Error) => {
    const waiter = waiterRef.current;
    if (!waiter || waiter.settled) {
      return;
    }
    waiter.settled = true;
    waiter.reject(error);
  }, []);

  const close = useCallback(() => {
    const socket = socketRef.current;
    socketRef.current = null;
    if (socket && socket.readyState < WebSocket.CLOSING) {
      socket.close();
    }
  }, []);

  const prepareTranscript = useCallback(() => {
    close();
    setTranscript("");
    let resolvePromise: (text: string) => void = () => undefined;
    let rejectPromise: (error: Error) => void = () => undefined;
    waiterRef.current = {
      promise: new Promise<string>((resolve, reject) => {
        resolvePromise = resolve;
        rejectPromise = reject;
      }),
      resolve: resolvePromise,
      reject: rejectPromise,
      settled: false,
    };
  }, [close, setTranscript]);

  const open = useCallback((targetSessionId: string, interviewType: InterviewType) => {
    return new Promise<WebSocket>((resolve, reject) => {
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const websocket = new WebSocket(`${protocol}://${window.location.host}/api/ws/speech/asr/${encodeURIComponent(targetSessionId)}`);
      let ready = false;
      let settled = false;
      const timeoutId = window.setTimeout(() => {
        if (!ready) {
          websocket.close();
          if (!settled) {
            settled = true;
            reject(new Error("实时语音识别连接超时，请稍后重试。"));
          }
        }
      }, 10_000);
      socketRef.current = websocket;
      websocket.binaryType = "arraybuffer";
      websocket.onopen = () => {
        websocket.send(JSON.stringify({ type: "start", interview_type: interviewType, sample_rate: PCM_SAMPLE_RATE }));
      };
      websocket.onmessage = (event) => {
        let payload: RealtimeAsrMessage;
        try {
          payload = JSON.parse(String(event.data)) as RealtimeAsrMessage;
        } catch {
          return;
        }
        if (payload.type === "asr_ready") {
          ready = true;
          window.clearTimeout(timeoutId);
          onMessageRef.current("实时语音识别已连接，开始回答后会边说边转写。");
          if (!settled) {
            settled = true;
            resolve(websocket);
          }
          return;
        }
        if (payload.type === "asr_result") {
          setTranscript(payload.text || "");
          return;
        }
        if (payload.type === "asr_completed") {
          setTranscript(payload.text || "");
          resolveWaiter(payload.text || "");
          return;
        }
        if (payload.type === "asr_error") {
          const message = getApiErrorMessage(payload, payload.message || "实时语音识别失败，请稍后重试。");
          onMessageRef.current(message);
          if (!ready && !settled) {
            settled = true;
            window.clearTimeout(timeoutId);
            reject(new Error(message));
          } else {
            rejectWaiter(new Error(message));
            websocket.close();
          }
        }
      };
      websocket.onerror = () => {
        const error = new Error("实时语音识别连接异常，请检查腾讯云配置或网络状态。");
        if (!ready && !settled) {
          settled = true;
          window.clearTimeout(timeoutId);
          reject(error);
        } else {
          rejectWaiter(error);
          websocket.close();
        }
      };
      websocket.onclose = () => {
        window.clearTimeout(timeoutId);
        if (socketRef.current === websocket) {
          socketRef.current = null;
        }
        if (!ready && !settled) {
          settled = true;
          reject(new Error("实时语音识别连接未能建立。"));
          return;
        }
        if (waiterRef.current && !waiterRef.current.settled) {
          const fallbackTranscript = transcriptRef.current.trim();
          if (fallbackTranscript) {
            resolveWaiter(fallbackTranscript);
          } else {
            rejectWaiter(new Error("实时语音识别连接已断开。"));
          }
        }
      };
    });
  }, [rejectWaiter, resolveWaiter, setTranscript]);

  const waitForFinalTranscript = useCallback(async () => {
    const waiter = waiterRef.current;
    if (!waiter) {
      return transcriptRef.current.trim();
    }
    let timeoutId: number | null = null;
    const fallbackPromise = new Promise<string>((resolve) => {
      timeoutId = window.setTimeout(() => resolve(transcriptRef.current.trim()), FINAL_TRANSCRIPT_TIMEOUT_MS);
    });
    try {
      return (await Promise.race([waiter.promise, fallbackPromise])).trim();
    } finally {
      if (timeoutId !== null) {
        window.clearTimeout(timeoutId);
      }
      waiterRef.current = null;
      close();
    }
  }, [close]);

  useEffect(() => {
    return () => {
      rejectWaiter(new Error("页面已离开，实时语音识别已取消。"));
      waiterRef.current = null;
      close();
    };
  }, [close, rejectWaiter]);

  return {
    transcriptPreview,
    clearTranscript: () => setTranscript(""),
    prepareTranscript,
    open,
    waitForFinalTranscript,
    close,
  };
}
