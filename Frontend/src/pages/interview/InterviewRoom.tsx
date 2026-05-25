import { useEffect, useMemo, useRef, useState } from "react";
import { AppIcon } from "../../components/AppIcon";
import { AvatarStage, AvatarState } from "../../components/AvatarStage";
import { csrfHeaders, getApiErrorMessage } from "../../lib/api";

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
  detail?: string;
  message?: string;
};

type CurrentUserResponse = {
  email: string;
  role: string;
  credit_balance: number;
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

type SpeechRecognitionApiResponse = {
  text?: string;
  detail?: string;
  message?: string;
};

type SpeechSynthesisApiResponse = {
  audio_base64?: string;
  mime_type?: string;
  detail?: string;
  message?: string;
};

type AudioRecorderSession = {
  context: AudioContext;
  source: MediaStreamAudioSourceNode;
  processor: ScriptProcessorNode;
  stream: MediaStream;
  chunks: Float32Array[];
  sampleRate: number;
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

function createSessionId() {
  const random = Math.random().toString(36).slice(2, 8);
  return `session-${Date.now()}-${random}`;
}

function moduleByType(type: InterviewType) {
  return modules.find((module) => module.type === type) ?? modules[0];
}

function mergeAudioChunks(chunks: Float32Array[]) {
  const totalLength = chunks.reduce((total, chunk) => total + chunk.length, 0);
  const samples = new Float32Array(totalLength);
  let offset = 0;
  chunks.forEach((chunk) => {
    samples.set(chunk, offset);
    offset += chunk.length;
  });
  return samples;
}

function writeAscii(view: DataView, offset: number, value: string) {
  for (let index = 0; index < value.length; index += 1) {
    view.setUint8(offset + index, value.charCodeAt(index));
  }
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

function encodeWav(samples: Float32Array, sampleRate: number) {
  const outputSampleRate = 16000;
  const outputSamples = resampleAudio(samples, sampleRate, outputSampleRate);
  const bytesPerSample = 2;
  const buffer = new ArrayBuffer(44 + outputSamples.length * bytesPerSample);
  const view = new DataView(buffer);
  writeAscii(view, 0, "RIFF");
  view.setUint32(4, 36 + outputSamples.length * bytesPerSample, true);
  writeAscii(view, 8, "WAVE");
  writeAscii(view, 12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, outputSampleRate, true);
  view.setUint32(28, outputSampleRate * bytesPerSample, true);
  view.setUint16(32, bytesPerSample, true);
  view.setUint16(34, 8 * bytesPerSample, true);
  writeAscii(view, 36, "data");
  view.setUint32(40, outputSamples.length * bytesPerSample, true);
  let offset = 44;
  outputSamples.forEach((sample) => {
    const clamped = Math.max(-1, Math.min(1, sample));
    view.setInt16(offset, clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff, true);
    offset += bytesPerSample;
  });
  return new Blob([view], { type: "audio/wav" });
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
  const [stateIndex, setStateIndex] = useState(1);
  const [selectedModuleIndex, setSelectedModuleIndex] = useState(0);
  const [sessionId, setSessionId] = useState(createSessionId);
  const [currentUser, setCurrentUser] = useState<CurrentUserResponse | null>(null);
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
  const [socketState, setSocketState] = useState("连接中");
  const [socketMessage, setSocketMessage] = useState("正在检查是否有未完成训练。");
  const socketRef = useRef<WebSocket | null>(null);
  const recorderRef = useRef<AudioRecorderSession | null>(null);
  const state = states[stateIndex];
  const selectedModule = modules[selectedModuleIndex];
  const currentMaterial = materialsByType[selectedModule.type] ?? null;
  const selectedModuleNeedsMaterial = selectedModule.type === "job" || selectedModule.type === "postgraduate";
  const activeQuestion = interviewState?.current_question ?? activeSession?.current_question ?? null;
  const progressText = interviewState
    ? `${Math.min(interviewState.current_step_index + 1, interviewState.total_steps)} / ${interviewState.total_steps}`
    : "未开始";

  const socketSessionId = useMemo(() => interviewState?.session_id ?? activeSession?.session_id ?? sessionId, [activeSession, interviewState, sessionId]);

  useEffect(() => {
    void loadAccount();
    void loadActiveSession();
    void loadHistory();
  }, []);

  useEffect(() => {
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const websocket = new WebSocket(`${protocol}://${window.location.host}/api/ws/interviews/${socketSessionId}`);
    socketRef.current = websocket;

    websocket.onopen = () => {
      setSocketState("已连接");
      setSocketMessage((message) => (message.includes("未完成") ? message : "实时面试通道已建立。"));
    };
    websocket.onmessage = (event) => {
      const payload = JSON.parse(event.data) as { type?: string; received_type?: string; state?: string; message?: string };
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

  async function loadAccount() {
    const response = await fetch("/api/auth/me", { credentials: "include" });
    if (!response.ok) {
      setCurrentUser(null);
      return;
    }
    setCurrentUser((await response.json()) as CurrentUserResponse);
  }

  async function loadHistory() {
    const response = await fetch("/api/interviews/history", { credentials: "include" });
    if (!response.ok) {
      setHistoryItems([]);
      return;
    }
    setHistoryItems((await response.json()) as InterviewHistoryItem[]);
  }

  async function loadActiveSession() {
    const response = await fetch("/api/interviews/active", { credentials: "include" });
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
      setSocketMessage(selectedModule.type === "job" ? "请先上传简历并填写目标岗位/JD。" : "请先填写目标院校和报考专业，再开始复试模拟。");
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
      setSocketMessage(`面试创建失败：${getApiErrorMessage(data, "请重新登录后再试。")}`);
      return;
    }

    if (typeof data.balance_after === "number") {
      setCurrentUser((user) => (user ? { ...user, credit_balance: data.balance_after ?? user.credit_balance } : user));
    }
    setSessionId(data.session_id);
    setInterviewState(data);
    setActiveSession(null);
    setStateIndex(1);
    setSocketMessage(data.status === "completed" ? "这场训练已完成，可查看复盘报告。" : `面试已就绪，剩余次数 ${data.balance_after ?? "已更新"}。`);
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

    const response = await fetch("/api/interview-materials", {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders(),
      body: formData,
    });
    const data = (await response.json()) as InterviewMaterialResponse & { detail?: string; message?: string };
    setIsPreparingMaterial(false);

    if (!response.ok) {
      setSocketMessage(`资料分析失败：${getApiErrorMessage(data, "请检查文件和填写内容。")}`);
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

  function stopRecorder() {
    const recorder = recorderRef.current;
    recorderRef.current = null;
    if (!recorder) {
      return null;
    }
    recorder.processor.disconnect();
    recorder.source.disconnect();
    recorder.stream.getTracks().forEach((track) => track.stop());
    void recorder.context.close();
    return encodeWav(mergeAudioChunks(recorder.chunks), recorder.sampleRate);
  }

  async function transcribeAnswerAudio(audioBlob: Blob, targetSessionId: string, interviewType: InterviewType) {
    const formData = new FormData();
    formData.append("audio_file", audioBlob, `${targetSessionId}.wav`);
    formData.append("session_id", targetSessionId);
    formData.append("interview_type", interviewType);
    const response = await fetch("/api/speech/asr", {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders(),
      body: formData,
    });
    const data = (await response.json()) as SpeechRecognitionApiResponse;
    if (!response.ok || !data.text) {
      throw new Error(getApiErrorMessage(data, "语音识别失败。"));
    }
    return data.text;
  }

  async function startAnswer() {
    if (!interviewState || interviewState.status === "completed") {
      setSocketMessage("请先开始或恢复一场训练。");
      return;
    }

    const AudioContextCtor = window.AudioContext ?? window.webkitAudioContext;
    if (!navigator.mediaDevices?.getUserMedia || !AudioContextCtor) {
      setSocketMessage("当前浏览器不支持录音，请使用新版 Chrome 或 Edge。");
      return;
    }

    try {
      stopRecorder();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const context = new AudioContextCtor();
      const source = context.createMediaStreamSource(stream);
      const processor = context.createScriptProcessor(4096, 1, 1);
      const chunks: Float32Array[] = [];
      processor.onaudioprocess = (event) => {
        chunks.push(new Float32Array(event.inputBuffer.getChannelData(0)));
        event.outputBuffer.getChannelData(0).fill(0);
      };
      source.connect(processor);
      processor.connect(context.destination);
      recorderRef.current = { context, source, processor, stream, chunks, sampleRate: context.sampleRate };
    } catch {
      setIsRecording(false);
      setSocketMessage("无法打开麦克风，请检查浏览器权限后重新点击开始回答。");
      return;
    }

    setIsRecording(true);
    setStateIndex(2);
    setSocketMessage("正在录制你的回答。说完后点击“回答完毕”，系统会用腾讯云语音识别转写。");
    sendSocketEvent("answer_started");
  }

  async function finishAnswer() {
    if (!interviewState) {
      setSocketMessage("请先开始或恢复一场训练。");
      return;
    }
    const audioBlob = stopRecorder();
    setIsRecording(false);
    setStateIndex(3);
    sendSocketEvent("answer_finished");

    let answerText = "语音识别没有返回文本，但用户已完成本轮口头回答。";
    if (audioBlob && audioBlob.size > 44) {
      try {
        setSocketMessage("正在用腾讯云语音识别转写回答。");
        answerText = await transcribeAnswerAudio(audioBlob, interviewState.session_id, interviewState.interview_type);
      } catch {
        setStateIndex(0);
        setSocketMessage("腾讯云语音识别暂时不可用，本轮回答没有提交。请稍后重新点击开始回答。");
        return;
      }
    }
    const response = await fetch(`/api/interviews/${encodeURIComponent(interviewState.session_id)}/answers`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ answer_text: answerText }),
    });
    const data = (await response.json()) as InterviewStateResponse;
    if (!response.ok) {
      setSocketMessage(`回答提交失败：${getApiErrorMessage(data, "请稍后重试。")}`);
      return;
    }
    setInterviewState(data);
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
  }

  async function logout() {
    await fetch("/api/auth/logout", { method: "POST", credentials: "include", headers: csrfHeaders() });
    window.speechSynthesis?.cancel();
    stopRecorder();
    socketRef.current?.close();
    setCurrentUser(null);
    setInterviewState(null);
    setActiveSession(null);
    setHistoryItems([]);
    setSocketState("已退出");
    setSocketMessage("已退出登录，可以重新登录其他账号。");
  }

  function startFresh() {
    setInterviewState(null);
    setActiveSession(null);
    setSessionId(createSessionId());
    setSocketMessage("已切换到新训练，选择模块后开始。");
  }

  return (
    <main className="workspace-page interview-page">
      <header className="workspace-header">
        <a href="/" className="brand-mark">
          <AppIcon icon="solar:soundwave-circle-bold-duotone" size={24} />
          面霸练习生
        </a>
        <div className="workspace-header-actions">
          <span className="session-pill">{socketState} · {progressText}</span>
          <span className="credit-pill">
            <AppIcon icon="lucide:coins" size={16} />
            {currentUser ? `${currentUser.credit_balance} 次` : "未登录"}
          </span>
          {currentUser && (
            <button type="button" className="logout-button" onClick={() => void logout()}>
              <AppIcon icon="lucide:log-out" size={16} />
              退出
            </button>
          )}
        </div>
      </header>

      <section className="interview-layout">
        <AvatarStage state={state} />
        <aside className="interview-panel">
          <span className="eyebrow">{activeQuestion ? activeQuestion.round_name : "Voice-first Interview"}</span>
          <h1>{interviewState?.status === "completed" ? "本次训练已完成。" : "选择场景，用语音完成整场模拟。"}</h1>
          <p>系统会播放问题，你只需要开口回答并点击“回答完毕”。刷新或离开页面后，再回来会自动提示恢复未完成训练。</p>
          <div className="socket-status">
            <AppIcon icon="lucide:radio" size={18} />
            <span>{socketMessage}</span>
          </div>

          {!interviewState && activeSession && (
            <div className="resume-card">
              <AppIcon icon="lucide:history" size={20} />
              <div>
                <strong>可恢复上次训练</strong>
                <span>{activeSession.current_question?.round_name} · {activeSession.current_step_index + 1}/{activeSession.total_steps}</span>
              </div>
              <button type="button" onClick={() => void startSession(true)}>恢复</button>
            </div>
          )}

          {!interviewState && (
            <div className="interview-module-list">
              {modules.map((module, index) => (
                <button
                  type="button"
                  key={module.title}
                  className={selectedModuleIndex === index ? "is-active" : ""}
                  onClick={() => setSelectedModuleIndex(index)}
                >
                  <AppIcon icon={module.icon} size={18} />
                  <span>{module.title}</span>
                  <em>{module.meta}</em>
                </button>
              ))}
            </div>
          )}

          {!interviewState && selectedModuleNeedsMaterial && (
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
                  <label>
                    <span>简历文件</span>
                    <input
                      type="file"
                      accept=".txt,.md,.pdf,.docx,text/plain,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                      onChange={(event) => setJobResumeFile(event.currentTarget.files?.[0] ?? null)}
                    />
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
                <button type="button" disabled={isPreparingMaterial} onClick={() => void prepareMaterial()}>
                  <AppIcon icon="lucide:sparkles" size={17} />
                  {isPreparingMaterial ? "分析中" : currentMaterial ? "重新分析" : "分析资料"}
                </button>
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
                <button type="button" disabled={isStartingSession || (selectedModuleNeedsMaterial && !currentMaterial)} onClick={() => void startSession(false)}>
                  <AppIcon icon="lucide:play" size={18} />
                  {isStartingSession ? "启动中" : "开始训练"}
                </button>
              ) : (
                <>
                  <button type="button" disabled={isRecording} onClick={() => void startAnswer()}>
                    <AppIcon icon="lucide:mic" size={18} />
                    开始回答
                  </button>
                  <button type="button" onClick={() => void finishAnswer()}>
                    <AppIcon icon="lucide:square" size={18} />
                    回答完毕
                  </button>
                  <button type="button" onClick={() => void speakQuestion(interviewState.current_question?.text, interviewState.interview_type, interviewState.session_id)}>
                    <AppIcon icon="lucide:volume-2" size={18} />
                    重播问题
                  </button>
                </>
              )}
              <button type="button" onClick={startFresh}>
                <AppIcon icon="lucide:rotate-ccw" size={18} />
                新训练
              </button>
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
    </main>
  );
}
