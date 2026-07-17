import { useEffect, useRef, useState } from "react";
import type { MicrophoneTestSession } from "../types";

export type MicrophoneStatus = "idle" | "testing" | "ready" | "failed";

export type UseMicrophoneCheckResult = {
  microphoneDevices: MediaDeviceInfo[];
  selectedMicrophoneId: string;
  microphoneAnalyser: AnalyserNode | null;
  microphoneStatus: MicrophoneStatus;
  microphoneMessage: string;
  setMicrophoneStatus: React.Dispatch<React.SetStateAction<MicrophoneStatus>>;
  setMicrophoneMessage: React.Dispatch<React.SetStateAction<string>>;
  handleMicrophoneChange: (deviceId: string) => void;
  runMicrophoneCheck: () => Promise<void>;
  stopMicrophoneTest: () => void;
  microphoneVoiceDetectedRef: React.MutableRefObject<boolean>;
};

export function useMicrophoneCheck(): UseMicrophoneCheckResult {
  const [microphoneDevices, setMicrophoneDevices] = useState<MediaDeviceInfo[]>([]);
  const [selectedMicrophoneId, setSelectedMicrophoneId] = useState("");
  const [microphoneAnalyser, setMicrophoneAnalyser] = useState<AnalyserNode | null>(null);
  const [microphoneStatus, setMicrophoneStatus] = useState<MicrophoneStatus>("idle");
  const [microphoneMessage, setMicrophoneMessage] = useState("正式进入面试前，请先选择并检测麦克风。");
  const microphoneTestRef = useRef<MicrophoneTestSession | null>(null);
  const microphoneTestGenerationRef = useRef(0);
  const microphoneVoiceDetectedRef = useRef(false);

  useEffect(() => {
    void loadMicrophoneDevices();
    if (!navigator.mediaDevices?.addEventListener) {
      return;
    }
    const handleDeviceChange = () => { void loadMicrophoneDevices(); };
    navigator.mediaDevices.addEventListener("devicechange", handleDeviceChange);
    return () => {
      navigator.mediaDevices.removeEventListener("devicechange", handleDeviceChange);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function loadMicrophoneDevices() {
    if (!navigator.mediaDevices?.enumerateDevices) {
      setMicrophoneStatus("failed");
      setMicrophoneMessage("当前浏览器不支持麦克风设备枚举，请使用新版 Chrome 或 Edge。");
      return;
    }
    const devices = (await navigator.mediaDevices.enumerateDevices()).filter(
      (device) => device.kind === "audioinput",
    );
    setMicrophoneDevices(devices);
    setSelectedMicrophoneId((current) => current || devices[0]?.deviceId || "");
  }

  function handleMicrophoneChange(deviceId: string) {
    stopMicrophoneTest();
    setSelectedMicrophoneId(deviceId);
    setMicrophoneStatus("idle");
    setMicrophoneMessage("已切换输入设备，请重新检测麦克风。");
  }

  function stopMicrophoneTest() {
    microphoneTestGenerationRef.current += 1;
    setMicrophoneAnalyser(null);
    microphoneVoiceDetectedRef.current = false;
    const session = microphoneTestRef.current;
    microphoneTestRef.current = null;
    if (!session) return;
    session.source.disconnect();
    session.stream.getTracks().forEach((track) => track.stop());
    void session.context.close();
  }

  async function runMicrophoneCheck() {
    const AudioContextCtor =
      window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!navigator.mediaDevices?.getUserMedia || !AudioContextCtor) {
      setMicrophoneStatus("failed");
      setMicrophoneMessage("当前浏览器不支持录音，请使用新版 Chrome 或 Edge。");
      return;
    }
    stopMicrophoneTest();
    const generation = microphoneTestGenerationRef.current + 1;
    microphoneTestGenerationRef.current = generation;
    setMicrophoneStatus("testing");
    setMicrophoneMessage("正在打开麦克风，请试着说一句话。");
    microphoneVoiceDetectedRef.current = false;

    const audio: MediaTrackConstraints = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
    if (selectedMicrophoneId) audio.deviceId = { exact: selectedMicrophoneId };

    let stream: MediaStream | null = null;
    let context: AudioContext | null = null;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio });
      if (generation !== microphoneTestGenerationRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      await loadMicrophoneDevices();
      if (generation !== microphoneTestGenerationRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      context = new AudioContextCtor();
      const source = context.createMediaStreamSource(stream);
      const analyser = context.createAnalyser();
      analyser.fftSize = 1024;
      source.connect(analyser);
      microphoneTestRef.current = { context, source, analyser, stream };
      setMicrophoneAnalyser(analyser);
      window.setTimeout(() => {
        if (!microphoneVoiceDetectedRef.current && microphoneTestRef.current) {
          setMicrophoneMessage("麦克风已打开，但声音偏小。请靠近麦克风或换一个输入设备。");
        }
      }, 1800);
    } catch {
      stream?.getTracks().forEach((t) => t.stop());
      void context?.close();
      if (generation !== microphoneTestGenerationRef.current) return;
      setMicrophoneStatus("failed");
      setMicrophoneMessage("无法打开麦克风，请在浏览器地址栏允许麦克风权限后重新检测。");
      setMicrophoneAnalyser(null);
    }
  }

  return {
    microphoneDevices,
    selectedMicrophoneId,
    microphoneAnalyser,
    microphoneStatus,
    microphoneMessage,
    setMicrophoneStatus,
    setMicrophoneMessage,
    handleMicrophoneChange,
    runMicrophoneCheck,
    stopMicrophoneTest,
    microphoneVoiceDetectedRef,
  };
}
