import { useCallback, useEffect, useRef, useState } from "react";
import {
  ANSWER_LIMIT_MS,
  MIN_VOICE_RMS,
  MAX_ASR_BUFFERED_BYTES,
  PCM_CHUNK_SAMPLES,
  PCM_SAMPLE_RATE,
  SILENCE_AUTO_FINISH_MS,
  SILENCE_CALIBRATION_MS,
  appendSamples,
  calculateRms,
  encodePcm16,
  formatAnswerLimit,
  getAnswerLimitMs,
  isEffectiveVoice,
  resampleAudio,
} from "../audio";
import type { AudioRecorderSession, InterviewType } from "../types";
import { useRealtimeAsr } from "./useRealtimeAsr";

declare global {
  interface Window {
    webkitAudioContext?: typeof AudioContext;
  }
}

type UseAudioRecorderOptions = {
  selectedMicrophoneId: string;
  onMessage: (message: string) => void;
  onAutoFinish: () => void;
};

type StartRecordingOptions = {
  sessionId: string;
  interviewType: InterviewType;
  roundName?: string;
};

export function useAudioRecorder(options: UseAudioRecorderOptions) {
  const [isPreparing, setIsPreparing] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [answerLimitMs, setAnswerLimitMs] = useState(ANSWER_LIMIT_MS);
  const [recordingStartedAt, setRecordingStartedAt] = useState(0);
  const recordingStartedAtRef = useRef(0);
  const recorderRef = useRef<AudioRecorderSession | null>(null);
  const timeoutRef = useRef<number | null>(null);
  const lastVoiceAtRef = useRef(0);
  const hasDetectedVoiceRef = useRef(false);
  const noiseFloorRef = useRef(0.006);
  const voiceFrameCountRef = useRef(0);
  const autoFinishingRef = useRef(false);
  const startGenerationRef = useRef(0);
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const {
    transcriptPreview,
    clearTranscript,
    prepareTranscript,
    open: openRealtimeAsr,
    waitForFinalTranscript,
    close: closeRealtimeAsr,
  } = useRealtimeAsr({ onMessage: options.onMessage });

  const microphoneConstraints = useCallback((): MediaStreamConstraints => {
    const audio: MediaTrackConstraints = {
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    };
    if (optionsRef.current.selectedMicrophoneId) {
      audio.deviceId = { exact: optionsRef.current.selectedMicrophoneId };
    }
    return { audio };
  }, []);

  const flushRecorderAudio = useCallback((recorder: AudioRecorderSession) => {
    if (recorder.pendingSamples.length === 0 || recorder.socket.readyState !== WebSocket.OPEN) {
      recorder.pendingSamples = new Float32Array();
      return;
    }
    recorder.socket.send(encodePcm16(recorder.pendingSamples));
    recorder.pendingSamples = new Float32Array();
  }, []);

  const stop = useCallback((stopOptions: { sendEnd?: boolean; closeAsr?: boolean } = {}) => {
    // 代次递增会让仍在等待麦克风授权或 ASR 建连的旧 start() 主动释放局部资源。
    startGenerationRef.current += 1;
    const recorder = recorderRef.current;
    recorderRef.current = null;
    if (timeoutRef.current !== null) {
      window.clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    if (recorder) {
      flushRecorderAudio(recorder);
      if (stopOptions.sendEnd && recorder.socket.readyState === WebSocket.OPEN) {
        recorder.socket.send(JSON.stringify({ type: "end" }));
      }
      recorder.processor.onaudioprocess = null;
      recorder.processor.disconnect();
      recorder.source.disconnect();
      recorder.stream.getTracks().forEach((track) => track.stop());
      void recorder.context.close();
    }
    if (stopOptions.closeAsr) {
      closeRealtimeAsr();
    }
    setIsPreparing(false);
    setIsRecording(false);
    return Boolean(recorder);
  }, [closeRealtimeAsr, flushRecorderAudio]);

  const resetMeters = useCallback((limitMs: number) => {
    const startedAt = Date.now();
    setAnswerLimitMs(limitMs);
    setRecordingStartedAt(startedAt);
    recordingStartedAtRef.current = startedAt;
    lastVoiceAtRef.current = startedAt;
    hasDetectedVoiceRef.current = false;
    noiseFloorRef.current = 0.006;
    voiceFrameCountRef.current = 0;
    autoFinishingRef.current = false;
  }, []);

  const handleAudioLevel = useCallback((samples: Float32Array) => {
    const startedAt = recordingStartedAtRef.current;
    if (!startedAt || autoFinishingRef.current) {
      return;
    }
    const now = Date.now();
    const rms = calculateRms(samples);
    if (now - startedAt < SILENCE_CALIBRATION_MS) {
      const cappedRms = Math.min(rms, 0.02);
      noiseFloorRef.current = Math.min(0.016, Math.max(0.004, noiseFloorRef.current * 0.88 + cappedRms * 0.12));
      if (rms > MIN_VOICE_RMS * 1.4) {
        voiceFrameCountRef.current = Math.min(4, voiceFrameCountRef.current + 1);
        if (voiceFrameCountRef.current >= 2) {
          hasDetectedVoiceRef.current = true;
          lastVoiceAtRef.current = now;
        }
      }
      return;
    }
    const voiceDetected = isEffectiveVoice(rms, noiseFloorRef.current);
    if (voiceDetected) {
      voiceFrameCountRef.current = Math.min(4, voiceFrameCountRef.current + 1);
    } else {
      voiceFrameCountRef.current = Math.max(0, voiceFrameCountRef.current - 1);
      noiseFloorRef.current = Math.min(0.05, noiseFloorRef.current * 0.96 + rms * 0.04);
    }
    if (voiceFrameCountRef.current >= 2) {
      hasDetectedVoiceRef.current = true;
      lastVoiceAtRef.current = now;
      return;
    }
    const idleBase = hasDetectedVoiceRef.current ? lastVoiceAtRef.current : startedAt;
    if (now - idleBase >= SILENCE_AUTO_FINISH_MS) {
      autoFinishingRef.current = true;
      optionsRef.current.onMessage("连续 3 秒未检测到有效人声，系统已自动结束本轮回答。");
      optionsRef.current.onAutoFinish();
    }
  }, []);

  const start = useCallback(async ({ sessionId, interviewType, roundName }: StartRecordingOptions) => {
    const AudioContextCtor = window.AudioContext ?? window.webkitAudioContext;
    if (!navigator.mediaDevices?.getUserMedia || !AudioContextCtor) {
      throw new Error("当前浏览器不支持录音，请使用新版 Chrome 或 Edge。");
    }
    let stream: MediaStream | null = null;
    let context: AudioContext | null = null;
    let asrSocket: WebSocket | null = null;
    let generation = 0;
    const currentLimitMs = getAnswerLimitMs(interviewType, roundName);
    setIsPreparing(true);
    try {
      stop({ closeAsr: true });
      generation = startGenerationRef.current + 1;
      startGenerationRef.current = generation;
      const requireCurrentGeneration = () => {
        if (generation !== startGenerationRef.current) {
          throw new Error("录音准备已取消。");
        }
      };
      setIsPreparing(true);
      prepareTranscript();
      resetMeters(currentLimitMs);
      optionsRef.current.onMessage("正在打开麦克风并建立实时语音识别通道。");
      stream = await navigator.mediaDevices.getUserMedia(microphoneConstraints());
      requireCurrentGeneration();
      context = new AudioContextCtor();
      asrSocket = await openRealtimeAsr(sessionId, interviewType);
      requireCurrentGeneration();
      const source = context.createMediaStreamSource(stream);
      // ScriptProcessor remains for protocol compatibility; migrate to AudioWorklet after cross-browser PCM validation.
      const processor = context.createScriptProcessor(4096, 1, 1);
      const recorder: AudioRecorderSession = {
        context,
        source,
        processor,
        stream,
        socket: asrSocket,
        pendingSamples: new Float32Array(),
        sampleRate: context.sampleRate,
      };
      let transportFailureQueued = false;
      const stopForTransportFailure = () => {
        if (transportFailureQueued || recorderRef.current?.socket !== asrSocket) {
          return;
        }
        transportFailureQueued = true;
        window.queueMicrotask(() => {
          if (recorderRef.current?.socket !== asrSocket) {
            return;
          }
          stop({ closeAsr: true });
          optionsRef.current.onMessage("实时语音识别连接已中断，本轮录音已停止，请重新回答。");
        });
      };
      asrSocket.addEventListener("close", stopForTransportFailure);
      processor.onaudioprocess = (event) => {
        if (recorder.socket.readyState !== WebSocket.OPEN) {
          event.outputBuffer.getChannelData(0).fill(0);
          return;
        }
        if (recorder.socket.bufferedAmount > MAX_ASR_BUFFERED_BYTES) {
          stopForTransportFailure();
          event.outputBuffer.getChannelData(0).fill(0);
          return;
        }
        const inputSamples = new Float32Array(event.inputBuffer.getChannelData(0));
        handleAudioLevel(inputSamples);
        const pcmSamples = resampleAudio(inputSamples, recorder.sampleRate, PCM_SAMPLE_RATE);
        recorder.pendingSamples = appendSamples(recorder.pendingSamples, pcmSamples);
        while (recorder.pendingSamples.length >= PCM_CHUNK_SAMPLES && recorder.socket.readyState === WebSocket.OPEN) {
          const chunk = recorder.pendingSamples.slice(0, PCM_CHUNK_SAMPLES);
          recorder.pendingSamples = recorder.pendingSamples.slice(PCM_CHUNK_SAMPLES);
          recorder.socket.send(encodePcm16(chunk));
        }
        event.outputBuffer.getChannelData(0).fill(0);
      };
      source.connect(processor);
      processor.connect(context.destination);
      recorderRef.current = recorder;
      timeoutRef.current = window.setTimeout(() => {
        if (recorderRef.current) {
          optionsRef.current.onMessage("已到建议回答时长，本轮回答将自动收尾并提交转写。");
          optionsRef.current.onAutoFinish();
        }
      }, currentLimitMs);
      setIsPreparing(false);
      setIsRecording(true);
      optionsRef.current.onMessage(`正在实时转写回答，${formatAnswerLimit(interviewType, roundName)}。说完后点击“回答完毕”。`);
      return currentLimitMs;
    } catch (error) {
      if (asrSocket && asrSocket.readyState < WebSocket.CLOSING) {
        asrSocket.close();
      }
      stream?.getTracks().forEach((track) => track.stop());
      void context?.close();
      if (generation === startGenerationRef.current) {
        stop({ closeAsr: true });
      }
      throw error;
    }
  }, [handleAudioLevel, microphoneConstraints, openRealtimeAsr, prepareTranscript, resetMeters, stop]);

  const finish = useCallback(async () => {
    if (!recorderRef.current) {
      throw new Error("当前还没有开始录音，请先点击“开始回答”。");
    }
    stop({ sendEnd: true });
    optionsRef.current.onMessage("正在收尾实时转写结果。");
    return waitForFinalTranscript();
  }, [stop, waitForFinalTranscript]);

  const reset = useCallback(() => {
    stop({ closeAsr: true });
    clearTranscript();
  }, [clearTranscript, stop]);

  const getSilenceRemainingMs = useCallback(() => {
    const now = Date.now();
    const idleBase = hasDetectedVoiceRef.current ? lastVoiceAtRef.current : recordingStartedAtRef.current;
    return Math.max(0, SILENCE_AUTO_FINISH_MS - Math.max(0, now - idleBase));
  }, []);

  useEffect(() => {
    return () => {
      stop({ closeAsr: true });
    };
  }, [stop]);

  return {
    isPreparing,
    isRecording,
    transcriptPreview,
    answerLimitMs,
    recordingStartedAt,
    start,
    finish,
    stop,
    reset,
    getSilenceRemainingMs,
  };
}
