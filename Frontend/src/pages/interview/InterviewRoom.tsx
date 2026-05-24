import { useEffect, useMemo, useRef, useState } from "react";
import { AppIcon } from "../../components/AppIcon";
import { AvatarStage, AvatarState } from "../../components/AvatarStage";
import { csrfHeaders } from "../../lib/api";

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
  summary: string;
  dimensions: { name: string; score: number; comment: string }[];
  strengths: string[];
  improvements: string[];
  next_plan: string[];
  turns: { round_name: string; question: string; answer: string }[];
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
  major?: string | null;
  research_direction?: string | null;
  resume_text_preview?: string | null;
  extracted_text_chars: number;
  profile_summary: string;
  keywords: string[];
};

type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start: () => void;
  stop: () => void;
  onresult: ((event: { results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
};

declare global {
  interface Window {
    SpeechRecognition?: new () => SpeechRecognitionLike;
    webkitSpeechRecognition?: new () => SpeechRecognitionLike;
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
  const [postgraduateMajor, setPostgraduateMajor] = useState("");
  const [postgraduateDirection, setPostgraduateDirection] = useState("");
  const [isPreparingMaterial, setIsPreparingMaterial] = useState(false);
  const [isStartingSession, setIsStartingSession] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [socketState, setSocketState] = useState("连接中");
  const [socketMessage, setSocketMessage] = useState("正在检查是否有未完成训练。");
  const socketRef = useRef<WebSocket | null>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const answerDraftRef = useRef("");
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
      const payload = JSON.parse(event.data) as { type?: string; received_type?: string; state?: string };
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
      setSocketMessage(selectedModule.type === "job" ? "请先上传简历并填写目标岗位/JD。" : "请先填写报考专业，再开始复试模拟。");
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
      setSocketMessage(data.detail === "insufficient_credits" ? "次数不足，请添加官方微信 Teptysuki666 开通面试次数。" : "面试创建失败，请重新登录后再试。");
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
    speakQuestion(data.current_question?.text, data.interview_type);
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
      if (!postgraduateMajor.trim()) {
        setSocketMessage("研究生复试需要先填写报考专业。");
        setIsPreparingMaterial(false);
        return;
      }
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
    const data = (await response.json()) as InterviewMaterialResponse & { detail?: string };
    setIsPreparingMaterial(false);

    if (!response.ok) {
      const messageMap: Record<string, string> = {
        job_material_required_fields: "工作面试需要简历、目标岗位和岗位要求。",
        postgraduate_major_required: "研究生复试需要报考专业。",
        resume_file_too_large: "简历文件过大，请控制在 5MB 以内。",
        image_resume_ocr_not_configured: "当前暂支持 txt、pdf、docx 简历解析，图片简历识别稍后开放。",
        image_resume_ocr_dependency_missing: "图片 OCR 依赖尚未安装，请先使用 txt、pdf、docx 简历。",
        unsupported_resume_format: "当前支持 txt、pdf、docx 简历，请换一个文件格式。",
        resume_text_empty: "简历没有提取到文字，请换成文字版 PDF、docx 或 txt。",
      };
      setSocketMessage(messageMap[String(data.detail)] ?? "资料分析失败，请检查文件和填写内容。");
      return;
    }

    setMaterialsByType((previous) => ({ ...previous, [data.interview_type]: data }));
    setSocketMessage(data.interview_type === "job" ? "简历和岗位要求已分析，可以开始工作面试。" : "复试专业信息已保存，可以开始模拟。");
  }

  function speakQuestion(questionText?: string, interviewType?: InterviewType) {
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

  function sendSocketEvent(type: string) {
    if (socketRef.current?.readyState !== WebSocket.OPEN) {
      return;
    }
    socketRef.current.send(JSON.stringify({ type, session_id: socketSessionId, avatar_state: state }));
  }

  function startAnswer() {
    if (!interviewState || interviewState.status === "completed") {
      setSocketMessage("请先开始或恢复一场训练。");
      return;
    }

    const SpeechRecognitionCtor = window.SpeechRecognition ?? window.webkitSpeechRecognition;
    if (!SpeechRecognitionCtor) {
      setSocketMessage("当前浏览器不支持语音识别，请使用新版 Chrome 或 Edge。");
      return;
    }

    answerDraftRef.current = "";
    const recognition = new SpeechRecognitionCtor() as SpeechRecognitionLike;
    recognition.lang = modules.find((module) => module.type === interviewState.interview_type)?.lang ?? "zh-CN";
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.onresult = (event) => {
      let text = "";
      for (let index = 0; index < event.results.length; index += 1) {
        text += event.results[index][0].transcript;
      }
      answerDraftRef.current = text.trim();
    };
    recognition.onend = () => setIsRecording(false);
    recognition.onerror = () => {
      setIsRecording(false);
      setSocketMessage("语音识别中断，可以重新点击开始回答。");
    };
    recognitionRef.current = recognition;
    recognition.start();
    setIsRecording(true);
    setStateIndex(2);
    setSocketMessage("正在听你的回答。说完后点击“回答完毕”。");
    sendSocketEvent("answer_started");
  }

  async function finishAnswer() {
    if (!interviewState) {
      setSocketMessage("请先开始或恢复一场训练。");
      return;
    }
    recognitionRef.current?.stop();
    setIsRecording(false);
    setStateIndex(3);
    sendSocketEvent("answer_finished");

    const answerText = answerDraftRef.current || "语音识别没有返回文本，但用户已完成本轮口头回答。";
    const response = await fetch(`/api/interviews/${encodeURIComponent(interviewState.session_id)}/answers`, {
      method: "POST",
      credentials: "include",
      headers: csrfHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ answer_text: answerText }),
    });
    const data = (await response.json()) as InterviewStateResponse;
    if (!response.ok) {
      setSocketMessage(data.detail ?? "回答提交失败，请稍后重试。");
      return;
    }
    setInterviewState(data);
    answerDraftRef.current = "";
    await loadHistory();
    if (data.status === "completed") {
      setStateIndex(0);
      setSocketMessage("训练完成，复盘报告已生成。");
      return;
    }
    setSocketMessage("已进入下一问，问题正在播放。");
    speakQuestion(data.current_question?.text, data.interview_type);
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
                  <button type="button" disabled={isRecording} onClick={startAnswer}>
                    <AppIcon icon="lucide:mic" size={18} />
                    开始回答
                  </button>
                  <button type="button" onClick={() => void finishAnswer()}>
                    <AppIcon icon="lucide:square" size={18} />
                    回答完毕
                  </button>
                  <button type="button" onClick={() => speakQuestion(interviewState.current_question?.text, interviewState.interview_type)}>
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
                <span>综合表现</span>
              </div>
              <p>{interviewState.report.summary}</p>
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
                    <em>{dimension.comment}</em>
                  </div>
                ))}
              </div>
              <div className="room-report-plan">
                <h2>下一轮建议</h2>
                {interviewState.report.next_plan.map((item) => (
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
                    <summary>{turn.round_name}</summary>
                    <p>{turn.question}</p>
                    <em>{turn.answer}</em>
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
