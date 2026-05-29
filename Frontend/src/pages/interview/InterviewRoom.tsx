import { type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Button, SafeArea } from "antd-mobile";
import { useLocation, useNavigate } from "react-router-dom";
import { AppIcon } from "../../components/AppIcon";
import { AvatarStage, AvatarState } from "../../components/AvatarStage";
import { BrandLogo } from "../../components/BrandLogo";
import { CSRF_COOKIE_NAME, csrfHeaders, getApiErrorMessage, getCookie } from "../../lib/api";

type InterviewType = "job" | "postgraduate" | "civil_service" | "ielts";

type InterviewQuestion = {
  turn_index: number;
  round_name: string;
  text: string;
};

type InterviewReport = {
  session_id: string;
  interview_type: InterviewType;
  total_score: number;
  readiness_level: string;
  score_explanation: string;
  summary: string;
  dimensions: { name: string; score: number; comment: string; level?: string | null; evidence?: string[]; action?: string | null }[];
  strengths: string[];
  improvements: string[];
  next_plan: string[];
  priority_actions: string[];
  evidence: string[];
  risk_flags: string[];
  recommended_drills: string[];
  turns: { round_name: string; question: string; answer: string; score?: number | null; feedback?: string | null; evidence?: string[] }[];
};

type InterviewStateResponse = {
  session_id: string;
  interview_type: InterviewType;
  status: string;
  current_step_index: number;
  total_steps: number;
  current_question: InterviewQuestion | null;
  report: InterviewReport | null;
  balance_after?: number;
  voucher_applied?: boolean;
  voucher_id?: string | null;
  detail?: string;
  message?: string;
};

type CurrentUserResponse = {
  email: string;
  role: string;
  credit_balance: number;
  trial_voucher_count: number;
};

type InterviewHistoryItem = {
  session_id: string;
  interview_type: InterviewType;
  status: string;
  current_step_index: number;
  total_steps: number;
  report_total_score: number | null;
  created_at: string;
};

type InterviewMaterialResponse = {
  id: string;
  interview_type: InterviewType;
  job_title?: string | null;
  target_school?: string | null;
  major?: string | null;
  research_direction?: string | null;
  resume_text_preview?: string | null;
  extracted_text_chars: number;
  profile_summary: string;
  keywords: string[];
};

type SpeechSynthesisApiResponse = {
  audio_base64?: string;
  mime_type?: string;
  detail?: string;
  message?: string;
};

type RealtimeAsrMessage = {
  type?: string;
  text?: string;
  current_text?: string;
  detail?: string;
  message?: string;
  provider_code?: number;
  sample_rate?: number;
  chunk_ms?: number;
};

type TranscriptWaiter = {
  promise: Promise<string>;
  resolve: (text: string) => void;
  reject: (error: Error) => void;
  settled: boolean;
};

type AudioRecorderSession = {
  context: AudioContext;
  source: MediaStreamAudioSourceNode;
  processor: ScriptProcessorNode;
  stream: MediaStream;
  socket: WebSocket;
  pendingSamples: Float32Array;
  sampleRate: number;
};

type MicrophoneTestSession = {
  context: AudioContext;
  source: MediaStreamAudioSourceNode;
  analyser: AnalyserNode;
  stream: MediaStream;
};

declare global {
  interface Window {
    webkitAudioContext?: typeof AudioContext;
  }
}

const states: AvatarState[] = ["idle", "speaking", "listening", "thinking"];
const modules: { icon: string; title: string; meta: string; type: InterviewType; lang: string }[] = [
  { icon: "lucide:briefcase-business", title: "工作面试", meta: "专业一面 + 专业二面 + HR 面", type: "job", lang: "zh-CN" },
  { icon: "lucide:graduation-cap", title: "研究生复试", meta: "自我介绍、专业基础、科研潜力", type: "postgraduate", lang: "zh-CN" },
  { icon: "lucide:landmark", title: "考公面试", meta: "综合分析、组织协调、应急应变", type: "civil_service", lang: "zh-CN" },
  { icon: "lucide:languages", title: "雅思口语", meta: "Part 1 / 2 / 3 全流程", type: "ielts", lang: "en-US" },
];

const moduleDetails: Record<
  InterviewType,
  {
    badge: string;
    intro: string;
    rounds: string;
    material: string;
    report: string;
    checklist: string[];
  }
> = {
  job: {
    badge: "3 轮完整体验",
    intro: "面向校招、社招和转岗求职，系统会结合简历、目标岗位和 JD 追问项目细节、岗位匹配、沟通动机与 HR 压力题。",
    rounds: "专业一面、专业二面、HR 面",
    material: "开始前需要上传简历，并填写应聘岗位和岗位要求。",
    report: "输出岗位匹配度、项目深挖表现、风险点、示范回答和下一轮专项训练建议。",
    checklist: ["简历 OCR 预分析", "JD 与能力关键词匹配", "二面追问链路", "HR 动机与稳定性"],
  },
  postgraduate: {
    badge: "1 次单场模拟",
    intro: "面向研究生复试，围绕院校、目标专业、研究方向、自我介绍、专业基础和导师沟通组织问题。",
    rounds: "复试综合问答",
    material: "开始前需要填写目标院校、报考专业，研究方向可选填。",
    report: "输出专业基础、科研潜力、表达结构、导师沟通准备度和补强路线。",
    checklist: ["院校专业背景", "自我介绍结构", "专业基础追问", "科研兴趣表达"],
  },
  civil_service: {
    badge: "1 次结构化模拟",
    intro: "面向考公结构化面试，按综合分析、组织协调、人际沟通、应急应变等常见题型训练稳定表达。",
    rounds: "结构化题型组",
    material: "无需上传材料，选择后直接进入设备检测。",
    report: "输出审题准确性、公共视角、表达层次、落地措施和临场稳定性。",
    checklist: ["审题拆解", "观点与层次", "公共治理视角", "应急处置逻辑"],
  },
  ielts: {
    badge: "2 次口语全流程",
    intro: "面向 IELTS Speaking，按 Part 1、Part 2、Part 3 节奏进行英文口语问答和延展追问。",
    rounds: "Part 1 / Part 2 / Part 3",
    material: "无需上传材料，选择后直接进入设备检测。",
    report: "输出流利度、词汇、语法、发音、观点展开和可复用表达建议。",
    checklist: ["日常问答", "话题卡展开", "抽象讨论", "表达替换建议"],
  },
};
const PCM_SAMPLE_RATE = 16000;
const PCM_CHUNK_MS = 200;
const PCM_CHUNK_SAMPLES = Math.floor(PCM_SAMPLE_RATE * (PCM_CHUNK_MS / 1000));
const FINAL_TRANSCRIPT_TIMEOUT_MS = 10_000;
const ANSWER_LIMIT_MS = 300_000;
const SILENCE_AUTO_FINISH_MS = 3_000;
const SILENCE_CALIBRATION_MS = 900;
const MIN_VOICE_RMS = 0.018;
const VOICE_MARGIN_RMS = 0.012;
const VOICE_NOISE_RATIO = 2.3;
const RECORDING_PROGRESS_INTERVAL_MS = 200;
const ACCOUNT_CODE_COOLDOWN_SECONDS = 90;
const ACCOUNT_CODE_STORAGE_PREFIX = "mianba_account_code_next:";

type ApiPayload = {
  detail?: string;
  message?: string;
  dev_code?: string;
};

function normalizeAccountEmail(email: string) {
  return email.trim().toLowerCase();
}

function accountCodeCooldownKey(email: string) {
  return `${ACCOUNT_CODE_STORAGE_PREFIX}${normalizeAccountEmail(email)}`;
}

function secondsUntil(timestamp: number) {
  return Math.max(0, Math.ceil((timestamp - Date.now()) / 1000));
}

function retryAfterSeconds(response: Response) {
  const rawValue = response.headers.get("Retry-After");
  const parsedValue = rawValue ? Number.parseInt(rawValue, 10) : ACCOUNT_CODE_COOLDOWN_SECONDS;
  return Number.isFinite(parsedValue) && parsedValue > 0 ? parsedValue : ACCOUNT_CODE_COOLDOWN_SECONDS;
}

async function parseApiPayload(response: Response): Promise<ApiPayload> {
  try {
    return (await response.json()) as ApiPayload;
  } catch {
    return {};
  }
}

function createSessionId() {
  const random = Math.random().toString(36).slice(2, 8);
  return `session-${Date.now()}-${random}`;
}

function moduleByType(type: InterviewType) {
  return modules.find((module) => module.type === type) ?? modules[0];
}

function resampleAudio(samples: Float32Array, inputSampleRate: number, outputSampleRate: number) {
  if (inputSampleRate === outputSampleRate) {
    return samples;
  }
  const ratio = inputSampleRate / outputSampleRate;
  const outputLength = Math.round(samples.length / ratio);
  const output = new Float32Array(outputLength);
  for (let index = 0; index < outputLength; index += 1) {
    const sourceIndex = index * ratio;
    const before = Math.floor(sourceIndex);
    const after = Math.min(before + 1, samples.length - 1);
    const weight = sourceIndex - before;
    output[index] = samples[before] * (1 - weight) + samples[after] * weight;
  }
  return output;
}

function appendSamples(first: Float32Array, second: Float32Array) {
  if (first.length === 0) {
    return second;
  }
  const merged = new Float32Array(first.length + second.length);
  merged.set(first, 0);
  merged.set(second, first.length);
  return merged;
}

function encodePcm16(samples: Float32Array) {
  const buffer = new ArrayBuffer(samples.length * 2);
  const view = new DataView(buffer);
  samples.forEach((sample, index) => {
    const clamped = Math.max(-1, Math.min(1, sample));
    view.setInt16(index * 2, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
  });
  return buffer;
}

function getAnswerLimitMs(_interviewType?: InterviewType, _roundName?: string) {
  return ANSWER_LIMIT_MS;
}

function formatAnswerLimit(interviewType: InterviewType, roundName?: string) {
  const limitSeconds = Math.round(getAnswerLimitMs(interviewType, roundName) / 1000);
  if (limitSeconds >= 300) {
    return "最多 5 分钟";
  }
  if (limitSeconds >= 60) {
    return `最多 ${Math.round(limitSeconds / 60)} 分钟`;
  }
  return `最多 ${limitSeconds} 秒`;
}

function formatDuration(ms: number) {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

function calculateRms(samples: Float32Array) {
  if (samples.length === 0) {
    return 0;
  }
  let sum = 0;
  samples.forEach((sample) => {
    sum += sample * sample;
  });
  return Math.sqrt(sum / samples.length);
}

function isEffectiveVoice(rms: number, noiseFloor: number) {
  const threshold = Math.max(MIN_VOICE_RMS, noiseFloor + VOICE_MARGIN_RMS, noiseFloor * VOICE_NOISE_RATIO);
  return rms > threshold;
}

function microphoneLabel(device: MediaDeviceInfo, index: number) {
  return device.label || `麦克风 ${index + 1}`;
}

function statusLabel(status: string) {
  return status === "completed" ? "已完成" : "进行中";
}

function formatHistoryDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "刚刚";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function InterviewRoom() {
  const location = useLocation();
  const navigate = useNavigate();
  const routeStage = location.pathname.endsWith("/check") ? "check" : location.pathname.endsWith("/room") ? "room" : "select";
  const [stateIndex, setStateIndex] = useState(1);
  const [selectedModuleIndex, setSelectedModuleIndex] = useState(0);
  const [sessionId, setSessionId] = useState(createSessionId);
  const [currentUser, setCurrentUser] = useState<CurrentUserResponse | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [accountCode, setAccountCode] = useState("");
  const [accountNewPassword, setAccountNewPassword] = useState("");
  const [accountMessage, setAccountMessage] = useState("使用邮箱验证码修改账户密码。");
  const [isRequestingAccountCode, setIsRequestingAccountCode] = useState(false);
  const [isChangingAccountPassword, setIsChangingAccountPassword] = useState(false);
  const [accountCodeCooldownSeconds, setAccountCodeCooldownSeconds] = useState(0);
  const [historyItems, setHistoryItems] = useState<InterviewHistoryItem[]>([]);
  const [interviewState, setInterviewState] = useState<InterviewStateResponse | null>(null);
  const [activeSession, setActiveSession] = useState<InterviewStateResponse | null>(null);
  const [materialsByType, setMaterialsByType] = useState<Partial<Record<InterviewType, InterviewMaterialResponse>>>({});
  const [jobResumeFile, setJobResumeFile] = useState<File | null>(null);
  const [jobTitle, setJobTitle] = useState("");
  const [jobRequirements, setJobRequirements] = useState("");
  const [postgraduateSchool, setPostgraduateSchool] = useState("");
  const [postgraduateMajor, setPostgraduateMajor] = useState("");
  const [postgraduateDirection, setPostgraduateDirection] = useState("");
  const [isPreparingMaterial, setIsPreparingMaterial] = useState(false);
  const [isStartingSession, setIsStartingSession] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [isPreparingAnswer, setIsPreparingAnswer] = useState(false);
  const [isFinishingAnswer, setIsFinishingAnswer] = useState(false);
  const [recordingElapsedMs, setRecordingElapsedMs] = useState(0);
  const [answerLimitMs, setAnswerLimitMs] = useState(ANSWER_LIMIT_MS);
  const [silenceRemainingMs, setSilenceRemainingMs] = useState(SILENCE_AUTO_FINISH_MS);
  const [microphoneReady, setMicrophoneReady] = useState(false);
  const [microphoneDevices, setMicrophoneDevices] = useState<MediaDeviceInfo[]>([]);
  const [selectedMicrophoneId, setSelectedMicrophoneId] = useState("");
  const [microphoneLevel, setMicrophoneLevel] = useState(0);
  const [microphoneStatus, setMicrophoneStatus] = useState<"idle" | "testing" | "ready" | "failed">("idle");
  const [microphoneMessage, setMicrophoneMessage] = useState("正式进入面试前，请先选择并检测麦克风。");
  const [pendingResume, setPendingResume] = useState(false);
  const [transcriptPreview, setTranscriptPreview] = useState("");
  const transcriptPreviewRef = useRef("");
  const [socketState, setSocketState] = useState("连接中");
  const [socketMessage, setSocketMessage] = useState("正在检查是否有未完成训练。");
  const socketRef = useRef<WebSocket | null>(null);
  const asrSocketRef = useRef<WebSocket | null>(null);
  const recorderRef = useRef<AudioRecorderSession | null>(null);
  const recordingTimeoutRef = useRef<number | null>(null);
  const recordingProgressIntervalRef = useRef<number | null>(null);
  const recordingStartedAtRef = useRef(0);
  const lastVoiceAtRef = useRef(0);
  const hasDetectedAnswerVoiceRef = useRef(false);
  const noiseFloorRef = useRef(0.006);
  const voiceFrameCountRef = useRef(0);
  const autoFinishingRef = useRef(false);
  const finalTranscriptWaiterRef = useRef<TranscriptWaiter | null>(null);
  const microphoneTestRef = useRef<MicrophoneTestSession | null>(null);
  const microphoneAnimationRef = useRef<number | null>(null);
  const state = states[stateIndex];
  const selectedModule = modules[selectedModuleIndex];
  const selectedModuleDetail = moduleDetails[selectedModule.type];
  const currentMaterial = materialsByType[selectedModule.type] ?? null;
  const selectedModuleNeedsMaterial = selectedModule.type === "job" || selectedModule.type === "postgraduate";
  const isSelectionStage = routeStage === "select";
  const activeQuestion = interviewState?.current_question ?? activeSession?.current_question ?? null;
  const progressText = interviewState
    ? `${Math.min(interviewState.current_step_index + 1, interviewState.total_steps)} / ${interviewState.total_steps}`
    : "未开始";
  const recordingProgressPercent = Math.min(1, recordingElapsedMs / Math.max(answerLimitMs, 1));
  const accountQuotaText = currentUser
    ? `${currentUser.credit_balance} 次${currentUser.trial_voucher_count > 0 ? ` / 体验券 ${currentUser.trial_voucher_count} 张` : ""}`
    : "未登录";

  const socketSessionId = useMemo(() => interviewState?.session_id ?? activeSession?.session_id ?? sessionId, [activeSession, interviewState, sessionId]);

  useEffect(() => {
    if (getCookie(CSRF_COOKIE_NAME)) {
      void loadAccount();
      void loadActiveSession();
      void loadHistory();
    } else {
      setCurrentUser(null);
      setHistoryItems([]);
      setSocketMessage("请先登录账号，再进入训练房间。");
    }
    void loadMicrophoneDevices();
  }, []);

  useEffect(() => {
    if (!currentUser?.email) {
      setAccountCodeCooldownSeconds(0);
      return;
    }
    const nextAllowedAt = Number.parseInt(window.localStorage.getItem(accountCodeCooldownKey(currentUser.email)) ?? "0", 10);
    setAccountCodeCooldownSeconds(secondsUntil(nextAllowedAt));
  }, [currentUser?.email]);

  useEffect(() => {
    if (accountCodeCooldownSeconds <= 0 || !currentUser?.email) {
      return;
    }
    const timer = window.setInterval(() => {
      const nextAllowedAt = Number.parseInt(window.localStorage.getItem(accountCodeCooldownKey(currentUser.email)) ?? "0", 10);
      setAccountCodeCooldownSeconds(secondsUntil(nextAllowedAt));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [accountCodeCooldownSeconds, currentUser?.email]);

  useEffect(() => {
    return () => {
      window.speechSynthesis?.cancel();
      stopRecorder();
      stopMicrophoneTest();
      asrSocketRef.current?.close();
      socketRef.current?.close();
    };
  }, []);

  useEffect(() => {
    if (!navigator.mediaDevices?.addEventListener) {
      return;
    }
    const handleDeviceChange = () => {
      void loadMicrophoneDevices();
    };
    navigator.mediaDevices.addEventListener("devicechange", handleDeviceChange);
    return () => {
      navigator.mediaDevices.removeEventListener("devicechange", handleDeviceChange);
    };
  }, []);

  useEffect(() => {
    if (!getCookie(CSRF_COOKIE_NAME)) {
      socketRef.current?.close();
      setSocketState("已断开");
      return;
    }
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const websocket = new WebSocket(`${protocol}://${window.location.host}/api/ws/interviews/${socketSessionId}`);
    socketRef.current = websocket;

    websocket.onopen = () => {
      setSocketState("已连接");
      setSocketMessage((message) => (message.includes("未完成") ? message : "实时面试通道已建立。"));
    };
    websocket.onmessage = (event) => {
      let payload: { type?: string; received_type?: string; state?: string; message?: string };
      try {
        payload = JSON.parse(event.data) as { type?: string; received_type?: string; state?: string; message?: string };
      } catch {
        setSocketMessage("实时通道返回了无法识别的消息，已忽略本次事件。");
        return;
      }
      if (payload.type === "session_kicked") {
        setSocketState("已下线");
        setSocketMessage("账号已在其他设备登录，当前训练页已下线，请重新登录。");
        setCurrentUser(null);
        setInterviewState(null);
        setActiveSession(null);
        window.setTimeout(() => {
          window.location.assign("/login");
        }, 1200);
        return;
      }
      if (payload.type === "event_ack") {
        setSocketMessage(`系统已记录：${payload.received_type}`);
        return;
      }
      if (payload.state === "connected") {
        setSocketMessage("训练通道已就绪。");
      }
    };
    websocket.onerror = () => {
      setSocketState("连接异常");
      setSocketMessage("实时通道异常，仍可通过普通接口继续训练。");
    };
    websocket.onclose = () => {
      setSocketState("已断开");
    };

    return () => {
      websocket.close();
    };
  }, [socketSessionId]);

  async function loadMicrophoneDevices() {
    if (!navigator.mediaDevices?.enumerateDevices) {
      setMicrophoneStatus("failed");
      setMicrophoneMessage("当前浏览器不支持麦克风设备枚举，请使用新版 Chrome 或 Edge。");
      return;
    }
    const devices = (await navigator.mediaDevices.enumerateDevices()).filter((device) => device.kind === "audioinput");
    setMicrophoneDevices(devices);
    setSelectedMicrophoneId((currentDeviceId) => currentDeviceId || devices[0]?.deviceId || "");
  }

  function handleMicrophoneChange(deviceId: string) {
    stopMicrophoneTest();
    setSelectedMicrophoneId(deviceId);
    setMicrophoneReady(false);
    setMicrophoneLevel(0);
    setMicrophoneStatus("idle");
    setMicrophoneMessage("已切换输入设备，请重新检测麦克风。");
  }

  function microphoneConstraints(): MediaStreamConstraints {
    const audio: MediaTrackConstraints = {
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    };
    if (selectedMicrophoneId) {
      audio.deviceId = { exact: selectedMicrophoneId };
    }
    return { audio };
  }

  function stopMicrophoneTest() {
    if (microphoneAnimationRef.current !== null) {
      window.cancelAnimationFrame(microphoneAnimationRef.current);
      microphoneAnimationRef.current = null;
    }
    const session = microphoneTestRef.current;
    microphoneTestRef.current = null;
    if (!session) {
      return;
    }
    session.source.disconnect();
    session.stream.getTracks().forEach((track) => track.stop());
    void session.context.close();
  }

  async function runMicrophoneCheck() {
    const AudioContextCtor = window.AudioContext ?? window.webkitAudioContext;
    if (!navigator.mediaDevices?.getUserMedia || !AudioContextCtor) {
      setMicrophoneStatus("failed");
      setMicrophoneMessage("当前浏览器不支持录音，请使用新版 Chrome 或 Edge。");
      return;
    }
    stopMicrophoneTest();
    setMicrophoneStatus("testing");
    setMicrophoneReady(false);
    setMicrophoneMessage("正在打开麦克风，请试着说一句话。");
    try {
      const stream = await navigator.mediaDevices.getUserMedia(microphoneConstraints());
      await loadMicrophoneDevices();
      const context = new AudioContextCtor();
      const source = context.createMediaStreamSource(stream);
      const analyser = context.createAnalyser();
      analyser.fftSize = 1024;
      source.connect(analyser);
      microphoneTestRef.current = { context, source, analyser, stream };
      const samples = new Uint8Array(analyser.fftSize);
      let hasDetectedVoice = false;
      const updateLevel = () => {
        analyser.getByteTimeDomainData(samples);
        let sum = 0;
        samples.forEach((sample) => {
          const normalized = (sample - 128) / 128;
          sum += normalized * normalized;
        });
        const rms = Math.sqrt(sum / samples.length);
        const level = Math.min(1, rms * 8);
        setMicrophoneLevel(level);
        if (level > 0.08 && !hasDetectedVoice) {
          hasDetectedVoice = true;
          setMicrophoneStatus("ready");
          setMicrophoneMessage("麦克风可用，声音已经能被系统采集。");
        }
        microphoneAnimationRef.current = window.requestAnimationFrame(updateLevel);
      };
      updateLevel();
      window.setTimeout(() => {
        if (!hasDetectedVoice && microphoneTestRef.current) {
          setMicrophoneMessage("麦克风已打开，但声音偏小。请靠近麦克风或换一个输入设备。");
        }
      }, 1800);
    } catch {
      setMicrophoneStatus("failed");
      setMicrophoneMessage("无法打开麦克风，请在浏览器地址栏允许麦克风权限后重新检测。");
      setMicrophoneLevel(0);
    }
  }

  function goToMicrophoneCheck(resume = false) {
    if (!resume && selectedModuleNeedsMaterial && !currentMaterial) {
      const message =
        selectedModule.type === "job"
          ? "请先上传简历并填写目标岗位和岗位要求, 再进入设备检测"
          : "请先填写目标院校和报考专业, 再进入设备检测";
      setSocketMessage(message);
      return;
    }
    if (resume && !activeSession) {
      setSocketMessage("暂时没有可恢复的未完成训练");
      return;
    }
    stopMicrophoneTest();
    setPendingResume(resume);
    setMicrophoneReady(false);
    setMicrophoneLevel(0);
    setMicrophoneStatus("idle");
    setMicrophoneMessage("正式进入面试前, 请先选择并检测麦克风");
    navigate("/interview/check");
  }

  function enterInterviewRoom() {
    if (microphoneStatus !== "ready") {
      setMicrophoneMessage("请先完成一次麦克风检测，确认系统能听到声音。");
      return;
    }
    stopMicrophoneTest();
    setMicrophoneReady(true);
    navigate("/interview/room");
    void startSession(pendingResume);
    setPendingResume(false);
  }

  async function loadAccount() {
    let response: Response;
    try {
      response = await fetch("/api/auth/me", { credentials: "include" });
    } catch {
      setCurrentUser(null);
      return;
    }
    if (!response.ok) {
      setCurrentUser(null);
      return;
    }
    setCurrentUser((await response.json()) as CurrentUserResponse);
  }

  function startAccountCodeCooldown(seconds = ACCOUNT_CODE_COOLDOWN_SECONDS) {
    if (!currentUser?.email) {
      return;
    }
    const safeSeconds = Math.max(1, seconds);
    window.localStorage.setItem(accountCodeCooldownKey(currentUser.email), String(Date.now() + safeSeconds * 1000));
    setAccountCodeCooldownSeconds(safeSeconds);
  }

  async function requestAccountCode() {
    if (!currentUser?.email) {
      setAccountMessage("请先登录账户。");
      return;
    }
    if (accountCodeCooldownSeconds > 0) {
      const message = `${accountCodeCooldownSeconds} 秒后可以重新获取验证码。`;
      setAccountMessage(message);
      return;
    }
    setIsRequestingAccountCode(true);
    setAccountMessage("正在发送验证码...");
    let response: Response;
    try {
      response = await fetch("/api/auth/email-code/request", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: currentUser.email }),
      });
    } catch {
      setIsRequestingAccountCode(false);
      setAccountMessage("网络连接异常, 请稍后再试。");
      return;
    }
    const data = await parseApiPayload(response);
    setIsRequestingAccountCode(false);
    if (!response.ok) {
      if (response.status === 429) {
        const retryAfter = retryAfterSeconds(response);
        startAccountCodeCooldown(retryAfter);
        const message = `获取太频繁, 请 ${retryAfter} 秒后再试。`;
        setAccountMessage(message);
        return;
      }
      const errorMessage = getApiErrorMessage(data, "请稍后再试。");
      setAccountMessage(`验证码发送失败: ${errorMessage}`);
      return;
    }
    if (data.dev_code) {
      setAccountCode(data.dev_code);
    }
    startAccountCodeCooldown();
    setAccountMessage("验证码已发送, 5 分钟内有效。");
  }

  async function changeAccountPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!currentUser?.email) {
      setAccountMessage("请先登录账户。");
      return;
    }
    if (accountCode.trim().length !== 6 || accountNewPassword.trim().length < 8) {
      setAccountMessage("请填写 6 位验证码和至少 8 位新密码。");
      return;
    }
    setIsChangingAccountPassword(true);
    setAccountMessage("正在修改密码...");
    let response: Response;
    try {
      response = await fetch("/api/auth/password/change", {
        method: "POST",
        credentials: "include",
        headers: csrfHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify({ code: accountCode, new_password: accountNewPassword }),
      });
    } catch {
      setIsChangingAccountPassword(false);
      setAccountMessage("网络连接异常, 请稍后再试。");
      return;
    }
    const data = await parseApiPayload(response);
    setIsChangingAccountPassword(false);
    if (!response.ok) {
      const errorMessage = getApiErrorMessage(data, "请检查验证码或账户状态。");
      setAccountMessage(`密码修改失败: ${errorMessage}`);
      return;
    }
    setAccountCode("");
    setAccountNewPassword("");
    setAccountMessage("密码已修改, 下次登录请使用新密码。");
  }

  async function loadHistory() {
    let response: Response;
    try {
      response = await fetch("/api/interviews/history", { credentials: "include" });
    } catch {
      setHistoryItems([]);
      return;
    }
    if (!response.ok) {
      setHistoryItems([]);
      return;
    }
    setHistoryItems((await response.json()) as InterviewHistoryItem[]);
  }

  async function loadActiveSession() {
    let response: Response;
    try {
      response = await fetch("/api/interviews/active", { credentials: "include" });
    } catch {
      setSocketMessage("网络连接异常, 请稍后再试。");
      return;
    }
    if (response.status === 401) {
      setSocketMessage("请先登录账号，再进入训练房间。");
      return;
    }
    if (!response.ok) {
      setSocketMessage("没有未完成训练，可以开始新的模拟。");
      return;
    }
    const data = (await response.json()) as InterviewStateResponse;
    setActiveSession(data);
    setSessionId(data.session_id);
    setSocketMessage("发现上次未完成训练，可以从中断处恢复。");
  }

  async function startSession(resume = false) {
    const targetSessionId = resume && activeSession ? activeSession.session_id : createSessionId();
    const material = resume && activeSession ? materialsByType[activeSession.interview_type] : currentMaterial;
    if (!resume && selectedModuleNeedsMaterial && !material) {
      const message = selectedModule.type === "job" ? "请先上传简历并填写目标岗位/JD。" : "请先填写目标院校和报考专业，再开始复试模拟。";
      setSocketMessage(message);
      return;
    }
    setIsStartingSession(true);
    const response = await fetch("/api/interviews", {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        session_id: targetSessionId,
        interview_type: resume && activeSession ? activeSession.interview_type : selectedModule.type,
        material_id: material?.id,
      }),
    });
    const data = (await response.json()) as InterviewStateResponse;
    setIsStartingSession(false);

    if (!response.ok) {
      const errorMessage = getApiErrorMessage(data, "请重新登录后再试。");
      setSocketMessage(`面试创建失败：${errorMessage}`);
      return;
    }

    if (typeof data.balance_after === "number") {
      setCurrentUser((user) =>
        user
          ? {
              ...user,
              credit_balance: data.balance_after ?? user.credit_balance,
              trial_voucher_count: data.voucher_applied ? Math.max(0, user.trial_voucher_count - 1) : user.trial_voucher_count,
            }
          : user,
      );
    }
    setSessionId(data.session_id);
    setInterviewState(data);
    setActiveSession(null);
    setStateIndex(1);
    setSocketMessage(
      data.status === "completed"
        ? "这场训练已完成，可查看复盘报告。"
        : data.voucher_applied
          ? `面试已就绪，已使用体验券，剩余次数 ${data.balance_after ?? "已更新"}。`
          : `面试已就绪，剩余次数 ${data.balance_after ?? "已更新"}。`,
    );
    await loadHistory();
    void speakQuestion(data.current_question?.text, data.interview_type, data.session_id);
  }

  async function prepareMaterial() {
    setIsPreparingMaterial(true);
    const formData = new FormData();
    formData.append("interview_type", selectedModule.type);
    if (selectedModule.type === "job") {
      if (!jobResumeFile || !jobTitle.trim() || !jobRequirements.trim()) {
        setSocketMessage("工作面试需要简历文件、目标岗位和岗位要求。");
        setIsPreparingMaterial(false);
        return;
      }
      formData.append("resume_file", jobResumeFile);
      formData.append("job_title", jobTitle.trim());
      formData.append("job_requirements", jobRequirements.trim());
    }
    if (selectedModule.type === "postgraduate") {
      if (!postgraduateSchool.trim() || !postgraduateMajor.trim()) {
        setSocketMessage("研究生复试需要先填写目标院校和报考专业。");
        setIsPreparingMaterial(false);
        return;
      }
      formData.append("target_school", postgraduateSchool.trim());
      formData.append("major", postgraduateMajor.trim());
      if (postgraduateDirection.trim()) {
        formData.append("research_direction", postgraduateDirection.trim());
      }
    }

    let response: Response;
    let data: InterviewMaterialResponse & { detail?: string; message?: string };
    try {
      response = await fetch("/api/interview-materials", {
        method: "POST",
        credentials: "include",
        headers: csrfHeaders(),
        body: formData,
      });
      data = (await response.json()) as InterviewMaterialResponse & { detail?: string; message?: string };
    } catch {
      setIsPreparingMaterial(false);
      setSocketMessage("资料分析请求失败，请检查网络后重试。");
      return;
    }
    setIsPreparingMaterial(false);

    if (!response.ok) {
      const errorMessage = getApiErrorMessage(data, "请检查文件和填写内容。");
      setSocketMessage(`资料分析失败：${errorMessage}`);
      return;
    }

    setMaterialsByType((previous) => ({ ...previous, [data.interview_type]: data }));
    setSocketMessage(data.interview_type === "job" ? "简历和岗位要求已分析，可以开始工作面试。" : "复试院校和专业信息已保存，可以开始模拟。");
  }

  function speakQuestionWithBrowser(questionText: string, interviewType?: InterviewType) {
    if (!questionText || !window.speechSynthesis) {
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(questionText);
    utterance.lang = interviewType === "ielts" ? "en-US" : "zh-CN";
    utterance.rate = interviewType === "ielts" ? 0.92 : 0.98;
    setStateIndex(1);
    utterance.onend = () => setStateIndex(0);
    window.speechSynthesis.speak(utterance);
  }

  async function speakQuestion(questionText?: string, interviewType?: InterviewType, targetSessionId = socketSessionId) {
    if (!questionText) {
      return;
    }
    setStateIndex(1);
    try {
      const response = await fetch("/api/speech/tts", {
        method: "POST",
        credentials: "include",
        headers: csrfHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify({ text: questionText, interview_type: interviewType, session_id: targetSessionId }),
      });
      const data = (await response.json()) as SpeechSynthesisApiResponse;
      if (!response.ok || !data.audio_base64 || !data.mime_type) {
        throw new Error(getApiErrorMessage(data, "语音合成失败。"));
      }
      window.speechSynthesis?.cancel();
      const audio = new Audio(`data:${data.mime_type};base64,${data.audio_base64}`);
      audio.onended = () => setStateIndex(0);
      audio.onerror = () => {
        setStateIndex(0);
        speakQuestionWithBrowser(questionText, interviewType);
      };
      await audio.play();
    } catch {
      setSocketMessage("腾讯云语音合成暂时不可用，已切换为浏览器本地播报。");
      speakQuestionWithBrowser(questionText, interviewType);
    }
  }

  function sendSocketEvent(type: string) {
    if (socketRef.current?.readyState !== WebSocket.OPEN) {
      return;
    }
    socketRef.current.send(JSON.stringify({ type, session_id: socketSessionId, avatar_state: state }));
  }

  function stopRecordingProgressTimer() {
    if (recordingProgressIntervalRef.current !== null) {
      window.clearInterval(recordingProgressIntervalRef.current);
      recordingProgressIntervalRef.current = null;
    }
  }

  function startRecordingProgressTimer(limitMs: number) {
    stopRecordingProgressTimer();
    const update = () => {
      const now = Date.now();
      const elapsedMs = Math.min(limitMs, Math.max(0, now - recordingStartedAtRef.current));
      const idleBase = hasDetectedAnswerVoiceRef.current ? lastVoiceAtRef.current : recordingStartedAtRef.current;
      const remainingSilenceMs = Math.max(0, SILENCE_AUTO_FINISH_MS - Math.max(0, now - idleBase));
      setRecordingElapsedMs(elapsedMs);
      setSilenceRemainingMs(remainingSilenceMs);
    };
    update();
    recordingProgressIntervalRef.current = window.setInterval(update, RECORDING_PROGRESS_INTERVAL_MS);
  }

  function resetRecordingMeters(limitMs = ANSWER_LIMIT_MS) {
    setRecordingElapsedMs(0);
    setAnswerLimitMs(limitMs);
    setSilenceRemainingMs(SILENCE_AUTO_FINISH_MS);
    recordingStartedAtRef.current = Date.now();
    lastVoiceAtRef.current = recordingStartedAtRef.current;
    hasDetectedAnswerVoiceRef.current = false;
    noiseFloorRef.current = 0.006;
    voiceFrameCountRef.current = 0;
    autoFinishingRef.current = false;
  }

  function handleAnswerAudioLevel(samples: Float32Array) {
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
          hasDetectedAnswerVoiceRef.current = true;
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
      hasDetectedAnswerVoiceRef.current = true;
      lastVoiceAtRef.current = now;
      return;
    }

    const idleBase = hasDetectedAnswerVoiceRef.current ? lastVoiceAtRef.current : startedAt;
    if (now - idleBase >= SILENCE_AUTO_FINISH_MS) {
      autoFinishingRef.current = true;
      setSocketMessage("连续 3 秒未检测到有效人声，系统已自动结束本轮回答。");
      void finishAnswer();
    }
  }

  function flushRecorderAudio(recorder: AudioRecorderSession) {
    if (recorder.pendingSamples.length === 0 || recorder.socket.readyState !== WebSocket.OPEN) {
      recorder.pendingSamples = new Float32Array();
      return;
    }
    recorder.socket.send(encodePcm16(recorder.pendingSamples));
    recorder.pendingSamples = new Float32Array();
  }

  function stopRecorder(options: { sendEnd?: boolean } = {}) {
    const recorder = recorderRef.current;
    recorderRef.current = null;
    stopRecordingProgressTimer();
    if (recordingTimeoutRef.current !== null) {
      window.clearTimeout(recordingTimeoutRef.current);
      recordingTimeoutRef.current = null;
    }
    if (!recorder) {
      return false;
    }
    flushRecorderAudio(recorder);
    if (options.sendEnd && recorder.socket.readyState === WebSocket.OPEN) {
      recorder.socket.send(JSON.stringify({ type: "end" }));
    }
    recorder.processor.disconnect();
    recorder.source.disconnect();
    recorder.stream.getTracks().forEach((track) => track.stop());
    void recorder.context.close();
    return true;
  }

  function createTranscriptWaiter() {
    let resolveWaiter: (text: string) => void = () => undefined;
    let rejectWaiter: (error: Error) => void = () => undefined;
    const waiter: TranscriptWaiter = {
      promise: new Promise<string>((resolve, reject) => {
        resolveWaiter = resolve;
        rejectWaiter = reject;
      }),
      resolve: resolveWaiter,
      reject: rejectWaiter,
      settled: false,
    };
    finalTranscriptWaiterRef.current = waiter;
    return waiter;
  }

  function resolveTranscriptWaiter(text: string) {
    const waiter = finalTranscriptWaiterRef.current;
    if (!waiter || waiter.settled) {
      return;
    }
    waiter.settled = true;
    waiter.resolve(text);
  }

  function rejectTranscriptWaiter(error: Error) {
    const waiter = finalTranscriptWaiterRef.current;
    if (!waiter || waiter.settled) {
      return;
    }
    waiter.settled = true;
    waiter.reject(error);
  }

  function setTranscript(text: string) {
    transcriptPreviewRef.current = text;
    setTranscriptPreview(text);
  }

  async function waitForFinalTranscript() {
    const waiter = finalTranscriptWaiterRef.current;
    if (!waiter) {
      return transcriptPreviewRef.current.trim();
    }
    const fallbackPromise = new Promise<string>((resolve) => {
      window.setTimeout(() => resolve(transcriptPreviewRef.current.trim()), FINAL_TRANSCRIPT_TIMEOUT_MS);
    });
    const text = await Promise.race([waiter.promise, fallbackPromise]);
    finalTranscriptWaiterRef.current = null;
    return text.trim();
  }

  function openRealtimeAsrSocket(targetSessionId: string, interviewType: InterviewType) {
    return new Promise<WebSocket>((resolve, reject) => {
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const websocket = new WebSocket(`${protocol}://${window.location.host}/api/ws/speech/asr/${encodeURIComponent(targetSessionId)}`);
      let ready = false;
      const timeoutId = window.setTimeout(() => {
        if (!ready) {
          websocket.close();
          reject(new Error("实时语音识别连接超时，请稍后重试。"));
        }
      }, 10_000);
      asrSocketRef.current = websocket;
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
          setSocketMessage("实时语音识别已连接，开始回答后会边说边转写。");
          resolve(websocket);
          return;
        }
        if (payload.type === "asr_result") {
          setTranscript(payload.text || "");
          return;
        }
        if (payload.type === "asr_completed") {
          setTranscript(payload.text || "");
          resolveTranscriptWaiter(payload.text || "");
          return;
        }
        if (payload.type === "asr_error") {
          const message = getApiErrorMessage(payload, payload.message || "实时语音识别失败，请稍后重试。");
          setSocketMessage(message);
          if (!ready) {
            window.clearTimeout(timeoutId);
            reject(new Error(message));
          } else {
            rejectTranscriptWaiter(new Error(message));
          }
        }
      };
      websocket.onerror = () => {
        const error = new Error("实时语音识别连接异常，请检查腾讯云配置或网络状态。");
        if (!ready) {
          window.clearTimeout(timeoutId);
          reject(error);
        } else {
          rejectTranscriptWaiter(error);
        }
      };
      websocket.onclose = () => {
        window.clearTimeout(timeoutId);
        if (!ready) {
          reject(new Error("实时语音识别连接未能建立。"));
          return;
        }
        if (finalTranscriptWaiterRef.current && !finalTranscriptWaiterRef.current.settled) {
          const fallbackTranscript = transcriptPreviewRef.current.trim();
          if (fallbackTranscript) {
            resolveTranscriptWaiter(fallbackTranscript);
            return;
          }
          rejectTranscriptWaiter(new Error("实时语音识别连接已断开。"));
        }
      };
    });
  }

  async function startAnswer() {
    if (!interviewState || interviewState.status === "completed") {
      setSocketMessage("请先开始或恢复一场训练。");
      return;
    }
    if (isRecording) {
      setSocketMessage("当前正在回答，请说完后点击“回答完毕”。");
      return;
    }
    if (isPreparingAnswer) {
      setSocketMessage("正在准备麦克风和实时识别通道，请稍等。");
      return;
    }
    if (isFinishingAnswer) {
      return;
    }

    const AudioContextCtor = window.AudioContext ?? window.webkitAudioContext;
    if (!navigator.mediaDevices?.getUserMedia || !AudioContextCtor) {
      setSocketMessage("当前浏览器不支持录音，请使用新版 Chrome 或 Edge。");
      return;
    }

    let stream: MediaStream | null = null;
    let context: AudioContext | null = null;
    const currentAnswerLimitMs = getAnswerLimitMs(interviewState.interview_type, interviewState.current_question?.round_name);
    setIsPreparingAnswer(true);
    try {
      stopRecorder();
      setTranscript("");
      resetRecordingMeters(currentAnswerLimitMs);
      createTranscriptWaiter();
      setSocketMessage("正在打开麦克风并建立实时语音识别通道。");
      stream = await navigator.mediaDevices.getUserMedia(microphoneConstraints());
      context = new AudioContextCtor();
      const asrSocket = await openRealtimeAsrSocket(interviewState.session_id, interviewState.interview_type);
      const source = context.createMediaStreamSource(stream);
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
      processor.onaudioprocess = (event) => {
        const inputSamples = new Float32Array(event.inputBuffer.getChannelData(0));
        handleAnswerAudioLevel(inputSamples);
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
      recordingTimeoutRef.current = window.setTimeout(() => {
        if (recorderRef.current) {
          setSocketMessage("已到建议回答时长，本轮回答将自动收尾并提交转写。");
          void finishAnswer();
        }
      }, currentAnswerLimitMs);
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop());
      void context?.close();
      rejectTranscriptWaiter(error instanceof Error ? error : new Error("实时语音识别启动失败。"));
      finalTranscriptWaiterRef.current = null;
      setIsPreparingAnswer(false);
      setIsRecording(false);
      setSocketMessage(error instanceof Error ? error.message : "无法打开麦克风或实时识别通道，请检查权限后重试。");
      return;
    }

    setIsPreparingAnswer(false);
    setIsRecording(true);
    startRecordingProgressTimer(currentAnswerLimitMs);
    setStateIndex(2);
    setSocketMessage(`正在实时转写回答，${formatAnswerLimit(interviewState.interview_type, interviewState.current_question?.round_name)}。说完后点击“回答完毕”。`);
    sendSocketEvent("answer_started");
  }

  async function finishAnswer() {
    if (!interviewState) {
      setSocketMessage("请先开始或恢复一场训练。");
      return;
    }
    if (isFinishingAnswer) {
      return;
    }
    if (!recorderRef.current) {
      setSocketMessage("当前还没有开始录音，请先点击“开始回答”。");
      return;
    }
    setIsFinishingAnswer(true);
    stopRecorder({ sendEnd: true });
    setIsRecording(false);
    setStateIndex(3);
    sendSocketEvent("answer_finished");

    let answerText = "";
    try {
      setSocketMessage("正在收尾实时转写结果。");
      answerText = await waitForFinalTranscript();
    } catch (error) {
      setStateIndex(0);
      setIsFinishingAnswer(false);
      setSocketMessage(error instanceof Error ? error.message : "腾讯云实时语音识别暂时不可用，本轮回答没有提交。");
      return;
    }
    if (!answerText) {
      setStateIndex(0);
      setIsFinishingAnswer(false);
      setSocketMessage("没有识别到有效回答，请重新点击“开始回答”后再试。");
      return;
    }
    const response = await fetch(`/api/interviews/${encodeURIComponent(interviewState.session_id)}/answers`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ answer_text: answerText }),
    });
    const data = (await response.json()) as InterviewStateResponse;
    if (!response.ok) {
      setIsFinishingAnswer(false);
      const errorMessage = getApiErrorMessage(data, "请稍后重试。");
      setSocketMessage(`回答提交失败：${errorMessage}`);
      return;
    }
    setInterviewState(data);
    setIsFinishingAnswer(false);
    await loadHistory();
    if (data.status === "completed") {
      setStateIndex(0);
      setSocketMessage("训练完成，复盘报告已生成。");
      return;
    }
    setSocketMessage("已进入下一问，问题正在播放。");
    void speakQuestion(data.current_question?.text, data.interview_type, data.session_id);
  }

  async function openHistoryItem(item: InterviewHistoryItem) {
    const response = await fetch(`/api/interviews/${encodeURIComponent(item.session_id)}`, { credentials: "include" });
    if (!response.ok) {
      setSocketMessage("这条训练记录暂时无法打开，请刷新后重试。");
      return;
    }
    const data = (await response.json()) as InterviewStateResponse;
    const moduleIndex = modules.findIndex((module) => module.type === data.interview_type);
    if (moduleIndex >= 0) {
      setSelectedModuleIndex(moduleIndex);
    }
    setSessionId(data.session_id);
    setInterviewState(data);
    setActiveSession(null);
    setSocketMessage(data.status === "completed" ? "已打开历史复盘报告。" : "已恢复这场未完成训练。");
    navigate("/interview/room");
  }

  async function logout() {
    await fetch("/api/auth/logout", { method: "POST", credentials: "include", headers: csrfHeaders() });
    window.speechSynthesis?.cancel();
    stopRecorder();
    stopMicrophoneTest();
    asrSocketRef.current?.close();
    socketRef.current?.close();
    setCurrentUser(null);
    setInterviewState(null);
    setActiveSession(null);
    setHistoryItems([]);
    setIsPreparingAnswer(false);
    setIsRecording(false);
    setIsFinishingAnswer(false);
    resetRecordingMeters();
    setSocketState("已退出");
    setSocketMessage("已退出登录，可以重新登录其他账号。");
    navigate("/", { replace: true });
  }

  function startFresh() {
    stopRecorder();
    asrSocketRef.current?.close();
    setTranscript("");
    setIsRecording(false);
    setIsPreparingAnswer(false);
    setIsFinishingAnswer(false);
    resetRecordingMeters();
    setInterviewState(null);
    setActiveSession(null);
    setPendingResume(false);
    setMicrophoneReady(false);
    setMicrophoneStatus("idle");
    setMicrophoneLevel(0);
    setSessionId(createSessionId());
    setSocketMessage("已切换到新训练，选择模块后开始。");
    navigate("/interview");
  }

  const scenarioModuleGrid = isSelectionStage && !interviewState ? (
    <div className="interview-module-list launchpad-scenario-list">
      {modules.map((module, index) => {
        const detail = moduleDetails[module.type];
        return (
          <button
            type="button"
            key={module.title}
            aria-pressed={selectedModuleIndex === index}
            className={selectedModuleIndex === index ? "is-active" : ""}
            data-module={module.type}
            onClick={() => setSelectedModuleIndex(index)}
          >
            <span className="module-card-icon">
              <AppIcon icon={module.icon} size={20} />
            </span>
            <span className="module-card-title">{module.title}</span>
            <em>{module.meta}</em>
            <strong>{detail.badge}</strong>
            <small>{detail.material}</small>
          </button>
        );
      })}
    </div>
  ) : null;

  const accountSettingsPanel = currentUser && isSettingsOpen ? (
    <section className="account-settings-panel" aria-label="账户设置">
      <div className="account-settings-copy">
        <span className="eyebrow">Account Settings</span>
        <h2>账户与密码</h2>
        <p>{currentUser.email}</p>
      </div>
      <form className="account-password-form" onSubmit={changeAccountPassword}>
        <label>
          <span>邮箱验证码</span>
          <div className="code-row">
            <div className="input-shell">
              <AppIcon icon="lucide:key-round" size={17} />
              <input
                value={accountCode}
                onChange={(event) => setAccountCode(event.currentTarget.value)}
                placeholder="6 位验证码"
                minLength={6}
                maxLength={6}
                required
              />
            </div>
            <Button
              type="button"
              className="code-button"
              fill="outline"
              shape="rounded"
              loading={isRequestingAccountCode}
              disabled={isRequestingAccountCode || accountCodeCooldownSeconds > 0}
              onClick={() => void requestAccountCode()}
            >
              {isRequestingAccountCode ? "发送中" : accountCodeCooldownSeconds > 0 ? `${accountCodeCooldownSeconds}s` : "获取"}
            </Button>
          </div>
        </label>
        <label>
          <span>新密码</span>
          <div className="input-shell">
            <AppIcon icon="lucide:key-round" size={17} />
            <input
              type="password"
              value={accountNewPassword}
              onChange={(event) => setAccountNewPassword(event.currentTarget.value)}
              placeholder="至少 8 位"
              minLength={8}
              required
            />
          </div>
        </label>
        <Button className="auth-submit account-password-submit" color="primary" loading={isChangingAccountPassword} shape="rounded" type="submit">
          {isChangingAccountPassword ? "修改中" : "修改密码"}
        </Button>
        <p className="account-settings-message" role="status" aria-live="polite">{accountMessage}</p>
      </form>
    </section>
  ) : null;

  if (routeStage === "check") {
    return (
      <main className="workspace-page interview-page">
        <header className="workspace-header">
          <a href="/" className="brand-mark">
            <BrandLogo size={28} />
            面霸练习生
          </a>
          <div className="workspace-header-actions">
            <span className="session-pill">{socketState} · 设备检测</span>
            <span className="credit-pill">
              <AppIcon icon="lucide:coins" size={16} />
              {accountQuotaText}
            </span>
            {currentUser && (
              <>
                <button type="button" className={`logout-button account-button${isSettingsOpen ? " is-active" : ""}`} onClick={() => setIsSettingsOpen((value) => !value)}>
                  <AppIcon icon="lucide:settings-2" size={16} />
                  设置
                </button>
                <button type="button" className="logout-button" onClick={() => void logout()}>
                  <AppIcon icon="lucide:log-out" size={16} />
                  退出
                </button>
              </>
            )}
          </div>
        </header>
        {accountSettingsPanel}

        <section className="microphone-gate">
          <div className="microphone-copy">
            <span className="eyebrow">Microphone Check</span>
            <h1>先确认系统能听清，再进入正式面试。</h1>
            <p>
              面试环节会采用腾讯云实时语音识别，回答时边说边转写，单轮回答最长 5 分钟。进入前先选择麦克风并说一句话，音量条有明显波动后再进入训练房间。
            </p>
            <div className="microphone-proof">
              <span>
                <AppIcon icon="lucide:mic-2" size={17} />
                实时采集
              </span>
              <span>
                <AppIcon icon="lucide:audio-lines" size={17} />
                边说边识别
              </span>
              <span>
                <AppIcon icon="lucide:shield-check" size={17} />
                本轮提交前可重试
              </span>
            </div>
          </div>

          <div className="microphone-card">
            <div className="material-card-heading">
              <span>
                <AppIcon icon="lucide:settings-2" size={18} />
                输入设备
              </span>
              <em>{microphoneStatus === "ready" ? "已通过" : microphoneStatus === "testing" ? "检测中" : "待检测"}</em>
            </div>

            <label className="microphone-select">
              <span>麦克风</span>
              <select value={selectedMicrophoneId} onChange={(event) => handleMicrophoneChange(event.currentTarget.value)}>
                {microphoneDevices.length === 0 ? (
                  <option value="">使用系统默认麦克风</option>
                ) : (
                  microphoneDevices.map((device, index) => (
                    <option key={device.deviceId || `device-${index}`} value={device.deviceId}>
                      {microphoneLabel(device, index)}
                    </option>
                  ))
                )}
              </select>
            </label>

            <div className="microphone-meter" aria-label="麦克风音量">
              <span style={{ transform: `scaleX(${Math.max(0.04, microphoneLevel)})` }} />
            </div>
            <p>{microphoneMessage}</p>

            <div className="microphone-actions">
              <Button
                type="button"
                className="mobile-action-button"
                color="primary"
                shape="rounded"
                loading={microphoneStatus === "testing"}
                onClick={() => void runMicrophoneCheck()}
                disabled={microphoneStatus === "testing"}
              >
                <AppIcon icon="lucide:waveform" size={18} />
                {microphoneStatus === "testing" ? "检测中" : "检测麦克风"}
              </Button>
              <Button
                type="button"
                className="mobile-action-button"
                color="success"
                shape="rounded"
                onClick={enterInterviewRoom}
                disabled={microphoneStatus !== "ready"}
              >
                <AppIcon icon="lucide:arrow-right" size={18} />
                进入面试房间
              </Button>
            </div>
          </div>
        </section>
        <SafeArea position="bottom" />
      </main>
    );
  }

  return (
    <main className="workspace-page interview-page">
      <header className="workspace-header">
        <a href="/" className="brand-mark">
          <BrandLogo size={28} />
          面霸练习生
        </a>
        {isSelectionStage && (
          <div className="workspace-flow-steps" aria-label="训练流程">
            <span className="is-active">1 选场景</span>
            <span>2 补资料</span>
            <span>3 设备检测</span>
            <span>4 语音面试</span>
          </div>
        )}
        <div className="workspace-header-actions">
          <span className="session-pill">{socketState} · {isSelectionStage ? "选择场景" : progressText}</span>
          <span className="credit-pill">
            <AppIcon icon="lucide:coins" size={16} />
            {accountQuotaText}
          </span>
          {currentUser && (
            <>
              <button type="button" className={`logout-button account-button${isSettingsOpen ? " is-active" : ""}`} onClick={() => setIsSettingsOpen((value) => !value)}>
                <AppIcon icon="lucide:settings-2" size={16} />
                设置
              </button>
              <button type="button" className="logout-button" onClick={() => void logout()}>
                <AppIcon icon="lucide:log-out" size={16} />
                退出
              </button>
            </>
          )}
        </div>
      </header>
      {accountSettingsPanel}

      <section className={isSelectionStage ? "interview-entry-layout" : "interview-layout"}>
        {isSelectionStage ? (
          <div className="scenario-hero-panel">
            <span className="eyebrow">Training Launchpad</span>
            <h1>选择训练场景，开始一次真实面试演练。</h1>
            <p>
              系统会根据场景自动切换题型结构、追问策略、评分维度与报告模板。选定后补齐必要资料，再进入设备检测。
            </p>
            {scenarioModuleGrid}

            <div className="scenario-highlight-card">
              <div className="scenario-highlight-head">
                <span>
                  <AppIcon icon={selectedModule.icon} size={22} />
                  当前选择
                </span>
                <strong>{selectedModuleDetail.badge}</strong>
              </div>
              <h2>{selectedModule.title}</h2>
              <p>{selectedModuleDetail.intro}</p>
              <div className="scenario-facts">
                <span><AppIcon icon="lucide:route" size={16} />{selectedModuleDetail.rounds}</span>
                <span><AppIcon icon="lucide:check-circle-2" size={16} />{selectedModuleDetail.material}</span>
                <span><AppIcon icon="lucide:gauge" size={16} />{selectedModuleDetail.report}</span>
              </div>
            </div>

            <div className="scenario-proof-grid">
              <span><AppIcon icon="lucide:mic-2" size={17} />进入前检测麦克风</span>
              <span><AppIcon icon="lucide:history" size={17} />中断后可恢复</span>
              <span><AppIcon icon="lucide:shield-check" size={17} />训练记录可追溯</span>
            </div>
          </div>
        ) : (
          <AvatarStage state={state} />
        )}
        <aside className={isSelectionStage ? "interview-panel scenario-control-panel" : "interview-panel"}>
          <span className="eyebrow">{activeQuestion ? activeQuestion.round_name : isSelectionStage ? `${selectedModule.title} Setup` : "Voice-first Interview"}</span>
          <h1>
            {interviewState?.status === "completed"
              ? "本次训练已完成。"
              : isSelectionStage
                ? "本次训练配置"
                : "面试房间已就绪。"}
          </h1>
          <p>
            {isSelectionStage
              ? "工作面试和研究生复试会先收集必要资料；考公面试与雅思口语可以直接进入设备检测。"
              : "系统会播放问题, 只需要开口回答并点击“回答完毕”。刷新或离开页面后, 再回来会自动提示恢复未完成训练。"}
          </p>
          <div className="socket-status">
            <AppIcon icon="lucide:radio" size={18} />
            <span>{socketMessage}</span>
          </div>

          {(transcriptPreview || isRecording || isFinishingAnswer) && (
            <div className="transcript-card">
              <div>
                <AppIcon icon="lucide:captions" size={17} />
                <span>{isFinishingAnswer ? "正在整理本轮回答" : isRecording ? "实时转写预览" : "上一轮转写"}</span>
              </div>
              <p>{transcriptPreview || "已经开始监听，说话后这里会显示实时转写内容。"}</p>
            </div>
          )}

          {(isRecording || isFinishingAnswer) && (
            <div className="recording-progress-card">
              <div className="recording-progress-heading">
                <span>
                  <AppIcon icon="lucide:activity" size={17} />
                  回答计时
                </span>
                <strong>
                  {formatDuration(recordingElapsedMs)} / {formatDuration(answerLimitMs)}
                </strong>
              </div>
              <div className="recording-progress-track" aria-label="本轮回答进度">
                <span style={{ transform: `scaleX(${recordingProgressPercent})` }} />
              </div>
              <div className="recording-progress-meta">
                <span>最长 5 分钟, 可提前点击回答完毕</span>
                <span>静音自动收尾 {formatDuration(silenceRemainingMs)}</span>
              </div>
            </div>
          )}

          {isSelectionStage && !interviewState && activeSession && (
            <div className="resume-card">
              <AppIcon icon="lucide:history" size={20} />
              <div>
                <strong>可恢复上次训练</strong>
                <span>{activeSession.current_question?.round_name} · {activeSession.current_step_index + 1}/{activeSession.total_steps}</span>
              </div>
              <button type="button" onClick={() => goToMicrophoneCheck(true)}>设备检测后恢复</button>
            </div>
          )}

          {isSelectionStage && !interviewState && (
            <div className="scenario-checklist">
              <span>
                <AppIcon icon="lucide:list-checks" size={17} />
                本模块会重点覆盖
              </span>
              <div>
                {selectedModuleDetail.checklist.map((item) => (
                  <em key={item}>{item}</em>
                ))}
              </div>
            </div>
          )}

          {isSelectionStage && !interviewState && selectedModuleNeedsMaterial && (
            <section className="material-card">
              <div className="material-card-heading">
                <span>
                  <AppIcon icon={selectedModule.type === "job" ? "lucide:file-scan" : "lucide:notebook-tabs"} size={18} />
                  {selectedModule.type === "job" ? "面试资料预分析" : "复试背景设置"}
                </span>
                {currentMaterial ? <em>已就绪</em> : <em>开始前必填</em>}
              </div>
              {selectedModule.type === "job" ? (
                <div className="material-form">
                  <label className="file-picker-field">
                    <span>简历文件</span>
                    <input
                      type="file"
                      accept=".txt,.md,.pdf,.docx,text/plain,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                      onChange={(event) => setJobResumeFile(event.currentTarget.files?.[0] ?? null)}
                    />
                    <span className="file-picker-control">
                      <strong>选择文件</strong>
                      <em>{jobResumeFile?.name ?? "未选择文件"}</em>
                    </span>
                  </label>
                  <label>
                    <span>应聘岗位</span>
                    <input value={jobTitle} onChange={(event) => setJobTitle(event.currentTarget.value)} placeholder="例如：AI 后端工程师" />
                  </label>
                  <label className="material-form-wide">
                    <span>岗位要求 / JD</span>
                    <textarea
                      value={jobRequirements}
                      onChange={(event) => setJobRequirements(event.currentTarget.value)}
                      placeholder="粘贴招聘 JD、岗位职责、加分项和技术要求"
                      rows={4}
                    />
                  </label>
                </div>
              ) : (
                <div className="material-form">
                  <label>
                    <span>目标院校</span>
                    <input
                      value={postgraduateSchool}
                      onChange={(event) => setPostgraduateSchool(event.currentTarget.value)}
                      placeholder="例如：中国人民大学"
                    />
                  </label>
                  <label>
                    <span>报考专业</span>
                    <input
                      value={postgraduateMajor}
                      onChange={(event) => setPostgraduateMajor(event.currentTarget.value)}
                      placeholder="例如：计算机科学与技术"
                    />
                  </label>
                  <label>
                    <span>研究方向（选填）</span>
                    <input
                      value={postgraduateDirection}
                      onChange={(event) => setPostgraduateDirection(event.currentTarget.value)}
                      placeholder="例如：自然语言处理与智能教育"
                    />
                  </label>
                </div>
              )}
              <div className="material-actions">
                <Button
                  type="button"
                  className="mobile-action-button"
                  color="primary"
                  shape="rounded"
                  loading={isPreparingMaterial}
                  disabled={isPreparingMaterial}
                  onClick={() => void prepareMaterial()}
                >
                  <AppIcon icon="lucide:sparkles" size={17} />
                  {isPreparingMaterial ? "分析中" : currentMaterial ? "重新分析" : "分析资料"}
                </Button>
                {currentMaterial && (
                  <span>
                    {currentMaterial.extracted_text_chars > 0 ? `${currentMaterial.extracted_text_chars} 字文本` : "背景已保存"}
                    {currentMaterial.keywords.length > 0 ? ` · ${currentMaterial.keywords.slice(0, 3).join(" / ")}` : ""}
                  </span>
                )}
              </div>
              {currentMaterial && <p className="material-preview">{currentMaterial.profile_summary}</p>}
            </section>
          )}

          {interviewState?.status !== "completed" && (
            <div className="voice-controls">
              {!interviewState ? (
                <Button
                  type="button"
                  className="mobile-action-button"
                  color="primary"
                  shape="rounded"
                  loading={isStartingSession}
                  disabled={isStartingSession || Boolean(currentUser && isSelectionStage && selectedModuleNeedsMaterial && !currentMaterial)}
                  onClick={() => {
                    if (!currentUser) {
                      navigate("/login");
                      return;
                    }
                    if (isSelectionStage) {
                      goToMicrophoneCheck(false);
                      return;
                    }
                    void startSession(pendingResume);
                  }}
                >
                  <AppIcon icon={isSelectionStage ? "lucide:scan-line" : "lucide:play"} size={18} />
                  {isStartingSession ? "启动中" : !currentUser ? "先登录账户" : isSelectionStage ? "进入设备检测" : "重新启动面试"}
                </Button>
              ) : (
                <>
                  <Button
                    type="button"
                    className="mobile-action-button"
                    color="primary"
                    shape="rounded"
                    loading={isPreparingAnswer}
                    disabled={isRecording || isPreparingAnswer || isFinishingAnswer}
                    onClick={() => void startAnswer()}
                  >
                    <AppIcon icon="lucide:mic" size={18} />
                    {isPreparingAnswer ? "准备中" : isRecording ? "回答中" : "开始回答"}
                  </Button>
                  <Button
                    type="button"
                    className="mobile-action-button"
                    color="primary"
                    shape="rounded"
                    loading={isFinishingAnswer}
                    disabled={!isRecording || isPreparingAnswer || isFinishingAnswer}
                    onClick={() => void finishAnswer()}
                  >
                    <AppIcon icon="lucide:square" size={18} />
                    {isFinishingAnswer ? "提交中" : "回答完毕"}
                  </Button>
                  <Button
                    type="button"
                    className="mobile-action-button"
                    fill="outline"
                    shape="rounded"
                    disabled={isRecording || isPreparingAnswer || isFinishingAnswer}
                    onClick={() => void speakQuestion(interviewState.current_question?.text, interviewState.interview_type, interviewState.session_id)}
                  >
                    <AppIcon icon="lucide:volume-2" size={18} />
                    重播问题
                  </Button>
                </>
              )}
              <Button type="button" className="mobile-action-button" fill="outline" shape="rounded" onClick={startFresh}>
                <AppIcon icon="lucide:rotate-ccw" size={18} />
                新训练
              </Button>
            </div>
          )}

          <section className="history-card">
            <div className="history-card-heading">
              <span>
                <AppIcon icon="lucide:history" size={18} />
                最近训练
              </span>
              <em>{historyItems.length} 条</em>
            </div>
            {historyItems.length === 0 ? (
              <p>暂无训练记录。完成第一次模拟后，这里会显示中断恢复入口和历史复盘。</p>
            ) : (
              <div className="history-list">
                {historyItems.slice(0, 5).map((item) => {
                  const module = moduleByType(item.interview_type);
                  return (
                    <button type="button" key={item.session_id} onClick={() => void openHistoryItem(item)}>
                      <AppIcon icon={module.icon} size={18} />
                      <span>
                        <strong>{module.title}</strong>
                        <em>{formatHistoryDate(item.created_at)} · {statusLabel(item.status)}</em>
                      </span>
                      <b>{item.report_total_score ?? `${item.current_step_index + 1}/${item.total_steps}`}</b>
                    </button>
                  );
                })}
              </div>
            )}
          </section>

          {interviewState?.report && (
            <section className="room-report">
              <div className="room-report-score">
                <strong>{interviewState.report.total_score}</strong>
                <span>{interviewState.report.readiness_level || "综合表现"}</span>
              </div>
              <p>{interviewState.report.summary}</p>
              {interviewState.report.score_explanation && (
                <div className="room-report-summary-card">
                  <AppIcon icon="lucide:gauge" size={18} />
                  <span>{interviewState.report.score_explanation}</span>
                </div>
              )}
              <div className="room-report-proof-grid">
                <article>
                  <h2>
                    <AppIcon icon="lucide:list-checks" size={17} />
                    评分依据
                  </h2>
                  {(interviewState.report.evidence || []).map((item) => (
                    <span key={item}>{item}</span>
                  ))}
                </article>
                <article>
                  <h2>
                    <AppIcon icon="lucide:triangle-alert" size={17} />
                    风险提醒
                  </h2>
                  {(interviewState.report.risk_flags || []).map((item) => (
                    <span key={item}>{item}</span>
                  ))}
                </article>
              </div>
              <div className="room-report-insights">
                <article>
                  <h2>
                    <AppIcon icon="lucide:check-circle-2" size={17} />
                    本轮亮点
                  </h2>
                  {interviewState.report.strengths.map((item) => (
                    <span key={item}>{item}</span>
                  ))}
                </article>
                <article>
                  <h2>
                    <AppIcon icon="lucide:activity" size={17} />
                    需要强化
                  </h2>
                  {interviewState.report.improvements.map((item) => (
                    <span key={item}>{item}</span>
                  ))}
                </article>
              </div>
              <div className="room-report-grid">
                {interviewState.report.dimensions.map((dimension) => (
                  <div key={dimension.name}>
                    <span>{dimension.name}</span>
                    <strong>{dimension.score}</strong>
                    {dimension.level && <small>{dimension.level}</small>}
                    <em>{dimension.comment}</em>
                    {dimension.evidence?.map((item) => (
                      <small key={item}>{item}</small>
                    ))}
                    {dimension.action && <em>{dimension.action}</em>}
                  </div>
                ))}
              </div>
              <div className="room-report-plan">
                <h2>优先动作</h2>
                {(interviewState.report.priority_actions || []).map((item) => (
                  <span key={item}>{item}</span>
                ))}
              </div>
              <div className="room-report-plan">
                <h2>下一轮建议</h2>
                {interviewState.report.next_plan.map((item) => (
                  <span key={item}>{item}</span>
                ))}
              </div>
              <div className="room-report-drills">
                {(interviewState.report.recommended_drills || []).map((item) => (
                  <span key={item}>{item}</span>
                ))}
              </div>
              <div className="room-report-turns">
                <h2>
                  <AppIcon icon="lucide:file-text" size={17} />
                  问答记录
                </h2>
                {interviewState.report.turns.map((turn, index) => (
                  <details key={`${turn.round_name}-${index}`}>
                    <summary>
                      <span>{turn.round_name}</span>
                      {typeof turn.score === "number" && <b>{turn.score}</b>}
                    </summary>
                    <p>{turn.question}</p>
                    <em>{turn.answer}</em>
                    {turn.feedback && <p>{turn.feedback}</p>}
                    {turn.evidence && turn.evidence.length > 0 && (
                      <div className="room-report-turn-tags">
                        {turn.evidence.map((item) => (
                          <span key={item}>{item}</span>
                        ))}
                      </div>
                    )}
                  </details>
                ))}
              </div>
            </section>
          )}
        </aside>
      </section>
      <SafeArea position="bottom" />
    </main>
  );
}
