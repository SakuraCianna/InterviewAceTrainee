import { type FormEvent, useEffect, useRef, useState } from "react";
import { Button, SafeArea } from "antd-mobile";
import { useLocation, useNavigate } from "react-router-dom";
import { AccountSettingsPanel } from "./AccountSettingsPanel";
import { AppIcon } from "../../components/AppIcon";
import { AvatarStage } from "../../components/AvatarStage";
import { BrandLogo } from "../../components/BrandLogo";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { Select } from "../../components/Select";
import { TaskProgress } from "./components/TaskProgress";
import { InterviewReport } from "./components/InterviewReport";
import { RecordingControls } from "./components/RecordingControls";
import { RecordingProgress } from "./components/RecordingProgress";
import { MicrophoneLevelMeter } from "./components/MicrophoneLevelMeter";
import { useEmailCodeCooldown } from "../../hooks/useEmailCodeCooldown";
import { CSRF_COOKIE_NAME, createIdempotencyKey, getApiErrorMessage, getCookie } from "../../lib/api";
import { retryAfterSeconds } from "../../lib/emailCooldown";
import {
  changePasswordWithEmailCode,
  createPersonalizedInterviewPackage,
  deleteInterviewHistoryItem,
  getActiveInterviewSession,
  getCurrentAccount,
  getInterviewSession,
  listInterviewHistory,
  logoutAccount,
  requestAccountEmailCode,
  startInterviewSession,
  startPersonalizedInterviewSession,
  streamQuestionSpeech,
  submitInterviewAnswer,
} from "./interviewApi";
import { moduleDetails, modules, states } from "./modules";
import { createSessionId, formatHistoryDate, microphoneLabel, moduleByType, statusLabel } from "./roomUtils";
import { roomClasses } from "./roomStyles";
import { useInterviewTask } from "./hooks/useInterviewTask";
import { useAudioRecorder } from "./hooks/useAudioRecorder";
import { useMicrophoneCheck } from "./hooks/useMicrophoneCheck";
import { usePersonalizationInput } from "./hooks/usePersonalizationInput";
import type {
  CurrentUserResponse,
  InterviewHistoryItem,
  InterviewStateResponse,
  InterviewType,
} from "./types";

declare global {
  interface Window {
    webkitAudioContext?: typeof AudioContext;
  }
}

const ACCOUNT_CODE_COOLDOWN_SECONDS = 90;
const ACCOUNT_CODE_STORAGE_PREFIX = "mianba_account_code_next:";

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
  const { cooldownSeconds: accountCodeCooldownSeconds, startCooldown: startAccountCodeCooldown } = useEmailCodeCooldown({
    email: currentUser?.email ?? "",
    storagePrefix: ACCOUNT_CODE_STORAGE_PREFIX,
    defaultSeconds: ACCOUNT_CODE_COOLDOWN_SECONDS,
  });
  const [historyItems, setHistoryItems] = useState<InterviewHistoryItem[]>([]);
  const [deletingHistorySessionId, setDeletingHistorySessionId] = useState("");
  const [interviewState, setInterviewState] = useState<InterviewStateResponse | null>(null);
  const [activeSession, setActiveSession] = useState<InterviewStateResponse | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);
  const {
    jobResumeFile,
    jobTitle,
    jobRequirements,
    postgraduateSchool,
    postgraduateMajor,
    postgraduateDirection,
    setJobResumeFile,
    setJobTitle,
    setJobRequirements,
    setPostgraduateSchool,
    setPostgraduateMajor,
    setPostgraduateDirection,
    isReady: isPersonalizationReady,
    createStartFormData,
    clear: clearPersonalization,
  } = usePersonalizationInput();
  const [isStartingSession, setIsStartingSession] = useState(false);
  const [isFinishingAnswer, setIsFinishingAnswer] = useState(false);
  const [isExitingSession, setIsExitingSession] = useState(false);
  const [isQuestionSpeaking, setIsQuestionSpeaking] = useState(false);
  const [pendingResume, setPendingResume] = useState(false);
  const [confirmDeleteItem, setConfirmDeleteItem] = useState<InterviewHistoryItem | null>(null);
  const [confirmExitTarget, setConfirmExitTarget] = useState<string | null>(null);
  const [socketMessage, setSocketMessage] = useState("正在检查是否有未完成训练。");
  const roomGenerationRef = useRef(0);
  const startSessionControllerRef = useRef<AbortController | null>(null);
  const startSessionRequestRef = useRef<ReturnType<typeof startInterviewSession> | ReturnType<typeof createPersonalizedInterviewPackage> | null>(null);
  const finishAnswerControllerRef = useRef<AbortController | null>(null);
  const taskRefreshControllerRef = useRef<AbortController | null>(null);
  const exitSessionControllerRef = useRef<AbortController | null>(null);
  const startingSessionIdRef = useRef<string | null>(null);
  const exitSessionLockRef = useRef(false);
  const finishingAnswerLockRef = useRef(false);
  const questionAudioCtxRef = useRef<AudioContext | null>(null);
  const questionStreamControllerRef = useRef<AbortController | null>(null);
  const isQuestionSpeakingRef = useRef(false);
  const questionAudioRef = useRef<HTMLAudioElement | null>(null);
  const questionSpeechTokenRef = useRef(0);
  const {
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
  } = useMicrophoneCheck();

  const audioRecorder = useAudioRecorder({
    selectedMicrophoneId,
    onMessage: setSocketMessage,
    onAutoFinish: () => void finishAnswer(),
  });
  const {
    isPreparing: isPreparingAnswer,
    isRecording,
    transcriptPreview,
    answerLimitMs,
    recordingStartedAt,
  } = audioRecorder;
  const state = states[stateIndex];
  const selectedModule = modules[selectedModuleIndex];
  const selectedModuleDetail = moduleDetails[selectedModule.type];
  const selectedModuleNeedsPersonalization = selectedModule.type === "job" || selectedModule.type === "postgraduate";
  const personalizationReady = isPersonalizationReady(selectedModule.type);
  const isSelectionStage = routeStage === "select";
  const shouldShowWorkspaceHeader = routeStage !== "room";
  const activeQuestion = interviewState?.current_question ?? activeSession?.current_question ?? null;
  const progressText = interviewState
    ? `${Math.min(interviewState.current_step_index + 1, interviewState.total_steps)} / ${interviewState.total_steps}`
    : "未开始";
  const expiryText = (() => {
    const expiresAt = interviewState?.expires_at;
    if (!expiresAt || interviewState?.status === "completed") return null;
    const msLeft = new Date(expiresAt).getTime() - Date.now();
    if (msLeft <= 0) return "已过期";
    const hours = Math.floor(msLeft / 3600000);
    const mins = Math.floor((msLeft % 3600000) / 60000);
    return hours > 0 ? `剩余 ${hours}h ${mins}m` : `剩余 ${mins}m`;
  })();
  const accountQuotaText = currentUser
    ? `${currentUser.credit_balance} 次${currentUser.trial_voucher_count > 0 ? ` / 体验券 ${currentUser.trial_voucher_count} 张` : ""}`
    : "未登录";
  const canReplayQuestion = Boolean(interviewState?.current_question) && !isQuestionSpeaking && !isRecording && !isPreparingAnswer && !isFinishingAnswer;

  const interviewTask = useInterviewTask({
    onSucceeded: refreshAfterInterviewTask,
    onFailed: handleInterviewTaskFailure,
  });

  const currentSessionId = interviewState?.session_id ?? activeSession?.session_id ?? sessionId;

  useEffect(() => {
    if (getCookie(CSRF_COOKIE_NAME)) {
      Promise.all([
        loadAccount(),
        loadActiveSession(),
        loadHistory(),
      ]).finally(() => setIsInitializing(false));
    } else {
      setCurrentUser(null);
      setHistoryItems([]);
      setSocketMessage("请先登录账号，再进入训练房间。");
      setIsInitializing(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    return () => {
      roomGenerationRef.current += 1;
      startSessionControllerRef.current?.abort();
      finishAnswerControllerRef.current?.abort();
      taskRefreshControllerRef.current?.abort();
      exitSessionControllerRef.current?.abort();
      questionSpeechTokenRef.current += 1;
      questionStreamControllerRef.current?.abort();
      questionStreamControllerRef.current = null;
      questionAudioCtxRef.current?.close();
      questionAudioCtxRef.current = null;
      questionAudioRef.current?.pause();
      questionAudioRef.current = null;
      window.speechSynthesis?.cancel();
      stopMicrophoneTest();
    };
  }, []);

  function goToMicrophoneCheck(resume = false) {
    if (!resume && selectedModuleNeedsPersonalization && !personalizationReady) {
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
    navigate("/interview/room");
    void startSession(pendingResume);
    setPendingResume(false);
  }

  async function loadAccount(signal?: AbortSignal) {
    let result: Awaited<ReturnType<typeof getCurrentAccount>>;
    try {
      result = await getCurrentAccount(signal);
    } catch {
      if (signal?.aborted) {
        return;
      }
      setCurrentUser(null);
      return;
    }
    if (signal?.aborted) {
      return;
    }
    const { response, data } = result;
    if (!response.ok) {
      setCurrentUser(null);
      return;
    }
    setCurrentUser(data);
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
    let result: Awaited<ReturnType<typeof requestAccountEmailCode>>;
    try {
      result = await requestAccountEmailCode(currentUser.email);
    } catch {
      setIsRequestingAccountCode(false);
      setAccountMessage("网络连接异常, 请稍后再试。");
      return;
    }
    const { response, data } = result;
    setIsRequestingAccountCode(false);
    if (!response.ok) {
      if (response.status === 429) {
        const retryAfter = retryAfterSeconds(response, ACCOUNT_CODE_COOLDOWN_SECONDS);
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
    let result: Awaited<ReturnType<typeof changePasswordWithEmailCode>>;
    try {
      result = await changePasswordWithEmailCode({ code: accountCode, new_password: accountNewPassword });
    } catch {
      setIsChangingAccountPassword(false);
      setAccountMessage("网络连接异常, 请稍后再试。");
      return;
    }
    const { response, data } = result;
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

  async function loadHistory(signal?: AbortSignal) {
    let result: Awaited<ReturnType<typeof listInterviewHistory>>;
    try {
      result = await listInterviewHistory(signal);
    } catch {
      if (signal?.aborted) {
        return;
      }
      setHistoryItems([]);
      return;
    }
    if (signal?.aborted) {
      return;
    }
    const { response, data } = result;
    if (!response.ok) {
      setHistoryItems([]);
      return;
    }
    setHistoryItems(data);
  }

  async function loadActiveSession(signal?: AbortSignal) {
    let result: Awaited<ReturnType<typeof getActiveInterviewSession>>;
    try {
      result = await getActiveInterviewSession(signal);
    } catch {
      if (signal?.aborted) {
        return;
      }
      setSocketMessage("网络连接异常, 请稍后再试。");
      return;
    }
    if (signal?.aborted) {
      return;
    }
    const { response, data } = result;
    if (response.status === 401) {
      setSocketMessage("请先登录账号，再进入训练房间。");
      return;
    }
    if (response.status === 204 || !data) {
      setActiveSession(null);
      setSocketMessage("没有未完成训练，可以开始新的模拟。");
      return;
    }
    if (!response.ok) {
      setSocketMessage("没有未完成训练，可以开始新的模拟。");
      return;
    }
    setActiveSession(data);
    setSessionId(data.session_id);
    if (data.active_task) {
      interviewTask.trackTask(data.active_task);
    }
    setSocketMessage("发现上次未完成训练，可以从中断处恢复。");
  }

  /**
   * 网络中断时只读取服务端权威状态，不重放答案正文或创建新的计费操作。
   * generation 和 AbortSignal 共同阻止已离开房间的迟到响应覆盖新状态。
   */
  async function reconcileAmbiguousSessionRequest(
    targetSessionId: string,
    generation: number,
    controller: AbortController,
    operation: "start" | "answer",
  ) {
    try {
      const { response, data } = await getInterviewSession(targetSessionId, controller.signal);
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (!response.ok) {
        setSocketMessage(
          operation === "start"
            ? "未确认服务端已创建训练，请在网络恢复后重新开始。"
            : "暂时无法确认回答是否已受理，请重新打开这场训练后再继续。",
        );
        return;
      }

      setSessionId(data.session_id);
      setInterviewState(data);
      setActiveSession(null);
      if (data.active_task) {
        interviewTask.trackTask(data.active_task);
      }
      if (data.status === "awaiting_ai" || data.active_task) {
        setStateIndex(3);
        setSocketMessage("网络已恢复，服务端已受理本次操作，正在继续同步 AI 任务。");
        return;
      }
      setStateIndex(0);
      if (data.status === "completed") {
        setSocketMessage("网络已恢复，本次训练已经完成，复盘报告已同步。");
        return;
      }
      setSocketMessage(
        operation === "start"
          ? "网络已恢复，训练创建成功，可以继续回答。"
          : "服务端未受理刚才的回答，请重新录音后提交。",
      );
      if (operation === "start") {
        void speakQuestion(data.current_question?.text, data.interview_type, data.session_id);
      }
    } catch {
      if (!controller.signal.aborted && generation === roomGenerationRef.current) {
        setSocketMessage(
          operation === "start"
            ? "面试创建结果尚未确认，网络恢复后重新打开页面会自动检查未完成训练。"
            : "回答结果尚未确认，网络恢复后重新打开页面会从服务端状态继续。",
        );
      }
    }
  }

  async function startSession(resume = false) {
    if (isExitingSession || exitSessionLockRef.current || startSessionControllerRef.current) {
      return;
    }
    if (resume) {
      if (!activeSession) {
        setSocketMessage("暂时没有可恢复的未完成训练。");
        return;
      }
      const restored = activeSession;
      setSessionId(restored.session_id);
      setInterviewState(restored);
      setActiveSession(null);
      setStateIndex(restored.status === "awaiting_ai" ? 3 : 1);
      if (restored.active_task) {
        interviewTask.trackTask(restored.active_task);
      }
      setSocketMessage(
        restored.status === "awaiting_ai"
          ? "已恢复训练，AI 正在处理上一轮回答。"
          : "已从上次中断处恢复训练。",
      );
      if (restored.status === "active") {
        void speakQuestion(restored.current_question?.text, restored.interview_type, restored.session_id);
      }
      return;
    }
    const targetSessionId = createSessionId();
    if (selectedModuleNeedsPersonalization && !personalizationReady) {
      const message = selectedModule.type === "job" ? "请先上传简历并填写目标岗位/JD。" : "请先填写目标院校和报考专业，再开始复试模拟。";
      setSocketMessage(message);
      return;
    }

    const generation = roomGenerationRef.current;
    const controller = new AbortController();
    const operationKey = createIdempotencyKey();
    startSessionControllerRef.current = controller;
    startingSessionIdRef.current = targetSessionId;
    setSessionId(targetSessionId);
    setIsStartingSession(true);
    try {
      const request = selectedModule.type === "job"
        ? createPersonalizedInterviewPackage(
          createStartFormData(targetSessionId, selectedModule.type),
          { idempotencyKey: operationKey, signal: controller.signal },
        )
        : selectedModuleNeedsPersonalization
          ? startPersonalizedInterviewSession(
            createStartFormData(targetSessionId, selectedModule.type),
            { idempotencyKey: operationKey, signal: controller.signal },
          )
          : startInterviewSession(
            { session_id: targetSessionId, interview_type: selectedModule.type },
            { idempotencyKey: operationKey, signal: controller.signal },
          );
      startSessionRequestRef.current = request;
      const { response, data } = await request;
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (!response.ok) {
        const errorMessage = getApiErrorMessage(data, "请重新登录后再试。");
        setSocketMessage(`面试创建失败：${errorMessage}`);
        return;
      }

      const sessionState = (data && typeof data === "object" && "current_session" in data && data.current_session)
        ? (data as { current_session: InterviewStateResponse }).current_session
        : (data as InterviewStateResponse);

      setSessionId(sessionState.session_id);
      clearPersonalization(selectedModule.type);
      setInterviewState(sessionState);
      setActiveSession(null);
      setStateIndex(1);
      setSocketMessage(
        sessionState.status === "completed"
          ? "这场训练已完成，可查看复盘报告。"
          : "面试已就绪，账户权益已同步。",
      );
      await Promise.all([loadHistory(controller.signal), loadAccount(controller.signal)]);
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      void speakQuestion(sessionState.current_question?.text, sessionState.interview_type, sessionState.session_id);
    } catch {
      if (!controller.signal.aborted && generation === roomGenerationRef.current) {
        await reconcileAmbiguousSessionRequest(targetSessionId, generation, controller, "start");
      }
    } finally {
      if (startSessionControllerRef.current === controller) {
        startSessionControllerRef.current = null;
        startSessionRequestRef.current = null;
        startingSessionIdRef.current = null;
        if (generation === roomGenerationRef.current) {
          setIsStartingSession(false);
        }
      }
    }
  }

  function setQuestionSpeakingState(value: boolean) {
    isQuestionSpeakingRef.current = value;
    setIsQuestionSpeaking(value);
  }

  function beginQuestionSpeech() {
    questionSpeechTokenRef.current += 1;
    const token = questionSpeechTokenRef.current;
    questionStreamControllerRef.current?.abort();
    questionStreamControllerRef.current = null;
    questionAudioCtxRef.current?.close();
    questionAudioCtxRef.current = null;
    questionAudioRef.current?.pause();
    questionAudioRef.current = null;
    window.speechSynthesis?.cancel();
    setQuestionSpeakingState(true);
    setStateIndex(1);
    return token;
  }

  function finishQuestionSpeech(token: number) {
    if (token !== questionSpeechTokenRef.current) {
      return;
    }
    questionAudioCtxRef.current = null;
    questionAudioRef.current = null;
    setQuestionSpeakingState(false);
    setStateIndex(0);
  }

  function stopQuestionSpeech() {
    questionSpeechTokenRef.current += 1;
    questionStreamControllerRef.current?.abort();
    questionStreamControllerRef.current = null;
    questionAudioCtxRef.current?.close();
    questionAudioCtxRef.current = null;
    questionAudioRef.current?.pause();
    questionAudioRef.current = null;
    window.speechSynthesis?.cancel();
    setQuestionSpeakingState(false);
    setStateIndex(0);
  }

  function speakQuestionWithBrowser(questionText: string, interviewType?: InterviewType, existingToken?: number) {
    if (!questionText || !window.speechSynthesis) {
      if (existingToken) {
        finishQuestionSpeech(existingToken);
      }
      return;
    }
    const token = existingToken ?? beginQuestionSpeech();
    const utterance = new SpeechSynthesisUtterance(questionText);
    utterance.lang = interviewType === "ielts" ? "en-US" : "zh-CN";
    utterance.rate = interviewType === "ielts" ? 0.92 : 0.98;
    utterance.onend = () => finishQuestionSpeech(token);
    utterance.onerror = () => finishQuestionSpeech(token);
    window.speechSynthesis.speak(utterance);
  }

  async function speakQuestion(questionText?: string, interviewType?: InterviewType, targetSessionId = currentSessionId) {
    if (!questionText || isQuestionSpeakingRef.current) {
      return;
    }
    const token = beginQuestionSpeech();
    const AudioContextClass = window.AudioContext ?? window.webkitAudioContext;
    if (!AudioContextClass) {
      speakQuestionWithBrowser(questionText, interviewType, token);
      return;
    }
    const controller = new AbortController();
    questionStreamControllerRef.current = controller;
    try {
      const audioCtx = new AudioContextClass();
      questionAudioCtxRef.current = audioCtx;
      let nextStartTime = 0;
      let lastSource: AudioBufferSourceNode | null = null;

      for await (const chunk of streamQuestionSpeech(targetSessionId, controller.signal)) {
        if (token !== questionSpeechTokenRef.current) {
          audioCtx.close();
          return;
        }
        const binary = atob(chunk.a);
        const bytes = new Uint8Array(binary.length);
        for (let j = 0; j < binary.length; j++) bytes[j] = binary.charCodeAt(j);
        const buffer = await audioCtx.decodeAudioData(bytes.buffer.slice(0));
        if (token !== questionSpeechTokenRef.current) {
          audioCtx.close();
          return;
        }
        const source = audioCtx.createBufferSource();
        source.buffer = buffer;
        source.connect(audioCtx.destination);
        const startAt = Math.max(audioCtx.currentTime + 0.05, nextStartTime);
        source.start(startAt);
        nextStartTime = startAt + buffer.duration;
        if (chunk.f) {
          lastSource = source;
        }
      }
      if (token !== questionSpeechTokenRef.current) return;
      if (lastSource) {
        lastSource.onended = () => finishQuestionSpeech(token);
      } else {
        finishQuestionSpeech(token);
      }
    } catch (err) {
      if (token !== questionSpeechTokenRef.current) return;
      if ((err as Error)?.name === "AbortError") return;
      questionAudioCtxRef.current?.close();
      questionAudioCtxRef.current = null;
      const hint = err instanceof Error ? err.message : "语音合成暂时不可用";
      setSocketMessage(`${hint}，已切换为浏览器本地播报。`);
      speakQuestionWithBrowser(questionText, interviewType, token);
    }
  }

  async function startAnswer() {
    if (!interviewState || interviewState.status !== "active") {
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
    if (isQuestionSpeaking) {
      setSocketMessage("请等面试官播报结束后再开始回答。");
      return;
    }
    try {
      await audioRecorder.start({
        sessionId: interviewState.session_id,
        interviewType: interviewState.interview_type,
        roundName: interviewState.current_question?.round_name,
      });
    } catch (error) {
      setSocketMessage(error instanceof Error ? error.message : "无法打开麦克风或实时识别通道，请检查权限后重试。");
      return;
    }
    setStateIndex(2);
  }

  async function finishAnswer() {
    if (!interviewState) {
      setSocketMessage("请先开始或恢复一场训练。");
      return;
    }
    if (finishingAnswerLockRef.current || finishAnswerControllerRef.current || isFinishingAnswer || interviewTask.isPending) {
      return;
    }
    if (!isRecording) {
      setSocketMessage("当前还没有开始录音，请先点击“开始回答”。");
      return;
    }
    const sessionSnapshot = interviewState;
    const turnIndex = sessionSnapshot.current_question?.turn_index ?? sessionSnapshot.current_step_index;
    const generation = roomGenerationRef.current;
    const controller = new AbortController();
    const operationKey = createIdempotencyKey();
    let phase: "transcribing" | "submitting" = "transcribing";
    finishAnswerControllerRef.current = controller;
    finishingAnswerLockRef.current = true;
    setIsFinishingAnswer(true);
    setStateIndex(3);

    try {
      const answerText = await audioRecorder.finish();
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (!answerText) {
        setStateIndex(0);
        setSocketMessage("没有识别到有效回答，请重新点击“开始回答”后再试。");
        return;
      }

      phase = "submitting";
      const { response, data } = await submitInterviewAnswer(
        sessionSnapshot.session_id,
        answerText,
        turnIndex,
        { idempotencyKey: operationKey, signal: controller.signal },
      );
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (!response.ok) {
        setStateIndex(0);
        const errorMessage = getApiErrorMessage(data, "请稍后重试。");
        setSocketMessage(`回答提交失败：${errorMessage}`);
        return;
      }
      if (response.status === 202) {
        if (!("task" in data)) {
          setStateIndex(0);
          setSocketMessage("回答已被服务接收，但任务信息不完整，请刷新训练状态后继续。");
          return;
        }
        if (data.session?.session_id) {
          setInterviewState(data.session);
        }
        interviewTask.trackTask(data.task);
        setSocketMessage("回答已进入 AI 处理队列。页面会自动同步进度，完成后再刷新下一问或复盘报告。");
        return;
      }
      if (!("session_id" in data)) {
        setStateIndex(0);
        setSocketMessage("回答处理结果格式异常，请刷新页面恢复会话。");
        return;
      }
      setInterviewState(data);
      await loadHistory(controller.signal);
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (data.status === "completed") {
        setStateIndex(0);
        setSocketMessage("训练完成，复盘报告已生成。");
        return;
      }
      const needsSupplement = data.current_step_index === sessionSnapshot.current_step_index;
      setSocketMessage(needsSupplement ? "本轮回答信息不足，面试官要求补充后才会进入下一题。" : "已进入下一问，问题正在播放。");
      void speakQuestion(data.current_question?.text, data.interview_type, data.session_id);
    } catch (error) {
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      setStateIndex(0);
      if (phase === "submitting") {
        await reconcileAmbiguousSessionRequest(sessionSnapshot.session_id, generation, controller, "answer");
      } else {
        setSocketMessage(
          error instanceof Error
            ? error.message
            : "腾讯云实时语音识别暂时不可用，本轮回答没有提交。",
        );
      }
    } finally {
      if (finishAnswerControllerRef.current === controller) {
        finishAnswerControllerRef.current = null;
      }
      if (generation === roomGenerationRef.current) {
        finishingAnswerLockRef.current = false;
        setIsFinishingAnswer(false);
      }
    }
  }

  async function refreshAfterInterviewTask(task: import("../../lib/api").AiTask) {
    const targetSessionId = task.session_id || interviewState?.session_id || sessionId;
    const generation = roomGenerationRef.current;
    taskRefreshControllerRef.current?.abort();
    const controller = new AbortController();
    taskRefreshControllerRef.current = controller;
    try {
      const { response, data } = await getInterviewSession(targetSessionId, controller.signal);
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (!response.ok) {
        setSocketMessage("AI 任务已完成，但暂时无法读取最新训练状态，请稍后刷新。");
        return;
      }
      const previousStep = interviewState?.current_step_index;
      setInterviewState(data);
      await loadHistory(controller.signal);
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (data.status === "completed") {
        setStateIndex(0);
        setSocketMessage("训练完成，复盘报告已生成并同步。");
        return;
      }
      const needsSupplement = previousStep != null && data.current_step_index === previousStep;
      setSocketMessage(needsSupplement ? "处理完成，本轮需要继续补充信息。" : "处理完成，已进入下一问，问题正在播放。");
      void speakQuestion(data.current_question?.text, data.interview_type, data.session_id);
    } catch {
      if (!controller.signal.aborted && generation === roomGenerationRef.current) {
        setSocketMessage("AI 任务已完成，但会话刷新失败。请检查网络后重新打开这场训练。");
      }
    } finally {
      if (taskRefreshControllerRef.current === controller) {
        taskRefreshControllerRef.current = null;
      }
    }
  }

  async function handleInterviewTaskFailure(task: import("../../lib/api").AiTask) {
    const targetSessionId = task.session_id || interviewState?.session_id || sessionId;
    const generation = roomGenerationRef.current;
    taskRefreshControllerRef.current?.abort();
    const controller = new AbortController();
    taskRefreshControllerRef.current = controller;
    try {
      const { response, data } = await getInterviewSession(targetSessionId, controller.signal);
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (response.ok) {
        setInterviewState(data);
        setStateIndex(data.status === "awaiting_ai" ? 3 : 0);
      } else {
        setStateIndex(0);
      }
      const message = task.error?.message || "AI 任务未能完成。";
      setSocketMessage(
        task.status === "CANCELLED"
          ? `${message} 已同步服务端会话状态。`
          : `${message} 已恢复可继续的会话状态${task.retryable ? "，也可以在任务卡片中重试" : ""}。`,
      );
    } catch {
      if (!controller.signal.aborted && generation === roomGenerationRef.current) {
        setStateIndex(0);
        setSocketMessage("AI 任务未能完成，且会话状态刷新失败。请检查网络后重新打开这场训练。");
      }
    } finally {
      if (taskRefreshControllerRef.current === controller) {
        taskRefreshControllerRef.current = null;
      }
    }
  }

  async function openHistoryItem(item: InterviewHistoryItem) {
    interviewTask.clearTask();
    const { response, data } = await getInterviewSession(item.session_id);
    if (!response.ok) {
      setSocketMessage("这条训练记录暂时无法打开，请刷新后重试。");
      return;
    }
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

  async function deleteHistoryItem(item: InterviewHistoryItem) {
    if (deletingHistorySessionId) {
      return;
    }
    setConfirmDeleteItem(item);
  }

  async function confirmDeleteHistoryItem(item: InterviewHistoryItem) {
    const generation = roomGenerationRef.current;
    setDeletingHistorySessionId(item.session_id);
    try {
      const response = await deleteInterviewHistoryItem(item.session_id);
      if (roomGenerationRef.current !== generation) {
        return;
      }
      if (!response.ok) {
        setSocketMessage("删除训练记录失败，请刷新后重试。");
        return;
      }
      setHistoryItems((items) => items.filter((historyItem) => historyItem.session_id !== item.session_id));
      if (activeSession?.session_id === item.session_id) {
        setActiveSession(null);
      }
      if (interviewState?.session_id === item.session_id) {
        setInterviewState(null);
        setSessionId(createSessionId());
      }
      setSocketMessage("训练记录已删除。");
    } catch {
      if (roomGenerationRef.current === generation) {
        setSocketMessage("删除训练记录时网络连接异常，请稍后重试。");
      }
    } finally {
      if (roomGenerationRef.current === generation) {
        setDeletingHistorySessionId("");
      }
    }
  }

  async function logout() {
    roomGenerationRef.current += 1;
    startSessionControllerRef.current?.abort();
    finishAnswerControllerRef.current?.abort();
    taskRefreshControllerRef.current?.abort();
    exitSessionControllerRef.current?.abort();
    await logoutAccount();
    stopQuestionSpeech();
    audioRecorder.reset();
    stopMicrophoneTest();
    setCurrentUser(null);
    setInterviewState(null);
    setActiveSession(null);
    setHistoryItems([]);
    setIsFinishingAnswer(false);
    finishingAnswerLockRef.current = false;
    interviewTask.clearTask();
    setSocketMessage("已退出登录，可以重新登录其他账号。");
    navigate("/", { replace: true });
  }

  async function startFresh() {
    if (exitSessionLockRef.current) {
      return;
    }
    const visibleSession = interviewState ?? activeSession;
    const pendingSessionId = startingSessionIdRef.current;
    const targetSessionId = pendingSessionId
      ?? (visibleSession && visibleSession.status !== "completed" ? visibleSession.session_id : null);
    if (targetSessionId) {
      setConfirmExitTarget(targetSessionId);
      return;
    }
    await doStartFresh(null);
  }

  async function doStartFresh(targetSessionId: string | null) {
    const isLeavingRoom = routeStage === "room";
    const pendingStartRequest = startSessionRequestRef.current;
    const generation = roomGenerationRef.current + 1;
    roomGenerationRef.current = generation;
    const controller = new AbortController();
    exitSessionControllerRef.current = controller;
    exitSessionLockRef.current = true;
    setIsExitingSession(true);
    setIsStartingSession(false);
    finishAnswerControllerRef.current?.abort();
    taskRefreshControllerRef.current?.abort();
    stopQuestionSpeech();
    audioRecorder.reset();
    setIsFinishingAnswer(false);
    finishingAnswerLockRef.current = false;
    setStateIndex(0);

    try {
      if (pendingStartRequest) {
        try {
          await pendingStartRequest;
        } catch {
          // 启动请求失败时仍继续 DELETE；404 表示服务端确实没有留下会话。
        }
      }
      if (controller.signal.aborted || generation !== roomGenerationRef.current) {
        return;
      }
      if (targetSessionId) {
        const response = await deleteInterviewHistoryItem(targetSessionId, controller.signal);
        if (controller.signal.aborted || generation !== roomGenerationRef.current) {
          return;
        }
        if (!response.ok && response.status !== 404) {
          await loadActiveSession(controller.signal);
          if (!controller.signal.aborted && generation === roomGenerationRef.current) {
            setSocketMessage("服务端未确认退出当前训练，本地会话已保留。请检查网络后重试。");
          }
          return;
        }
      }

      interviewTask.clearTask();
      setInterviewState(null);
      setActiveSession(null);
      setPendingResume(false);
      setMicrophoneStatus("idle");
      setSessionId(createSessionId());
      if (targetSessionId) {
        setHistoryItems((items) => items.filter((item) => item.session_id !== targetSessionId));
      }
      setSocketMessage(isLeavingRoom ? "已退出并终止当前训练，选择模块后可以重新开始。" : "已切换到新训练，选择模块后开始。");
      navigate("/interview");
    } catch {
      if (!controller.signal.aborted && generation === roomGenerationRef.current) {
        await loadActiveSession(controller.signal);
        if (!controller.signal.aborted && generation === roomGenerationRef.current) {
          setSocketMessage("退出训练时网络异常，本地会话已保留。请确认网络后重试。");
        }
      }
    } finally {
      if (exitSessionControllerRef.current === controller) {
        exitSessionControllerRef.current = null;
      }
      exitSessionLockRef.current = false;
      if (generation === roomGenerationRef.current) {
        setIsExitingSession(false);
      }
    }
  }

  const scenarioModuleGrid = isSelectionStage && !interviewState ? (
    <div className={roomClasses("interview-module-list launchpad-scenario-list")}>
      {modules.map((module, index) => {
        const detail = moduleDetails[module.type];
        return (
          <button
            type="button"
            key={module.title}
            aria-pressed={selectedModuleIndex === index}
            className={roomClasses(selectedModuleIndex === index && "is-active")}
            data-module={module.type}
            onClick={() => setSelectedModuleIndex(index)}
          >
            <span className={roomClasses("module-card-icon")}>
              <AppIcon icon={module.icon} size={20} />
            </span>
            <span className={roomClasses("module-card-title")}>{module.title}</span>
            <em>{module.meta}</em>
            <strong>{detail.badge}</strong>
            <small>{detail.material}</small>
          </button>
        );
      })}
    </div>
  ) : null;

  const accountSettingsPanel = currentUser && isSettingsOpen && shouldShowWorkspaceHeader ? (
    <div
      className={roomClasses("settings-overlay")}
      role="dialog"
      aria-modal="true"
      aria-label="账户设置"
      onClick={(e) => { if (e.target === e.currentTarget) setIsSettingsOpen(false); }}
    >
      <AccountSettingsPanel
      email={currentUser.email}
      code={accountCode}
      newPassword={accountNewPassword}
      message={accountMessage}
      isRequestingCode={isRequestingAccountCode}
      isChangingPassword={isChangingAccountPassword}
      codeCooldownSeconds={accountCodeCooldownSeconds}
      onCodeChange={setAccountCode}
      onPasswordChange={setAccountNewPassword}
      onRequestCode={() => void requestAccountCode()}
      onSubmit={changeAccountPassword}
    />
    </div>
  ) : null;

  if (isInitializing) {
    return <main className={roomClasses("workspace-page interview-page route-loading")}>页面加载中...</main>;
  }

  if (routeStage === "check") {
    return (
      <main className={roomClasses("workspace-page interview-page")}>
        <header className={roomClasses("workspace-header interview-workspace-header interview-workspace-header--check")}>
          <a href="/" className={roomClasses("brand-mark")}>
            <BrandLogo size={28} />
            面霸练习生
          </a>
          <div className={roomClasses("workspace-header-actions")}>
            <span className={roomClasses("session-pill")}>{currentUser ? "服务在线" : "未登录"} · 设备检测</span>
            <span className={roomClasses("credit-pill")}>
              <AppIcon icon="lucide:coins" size={16} />
              {accountQuotaText}
            </span>
            {currentUser && (
              <>
                <button type="button" className={roomClasses("logout-button", "account-button", isSettingsOpen && "is-active")} onClick={() => setIsSettingsOpen((value) => !value)}>
                  <AppIcon icon="lucide:settings-2" size={16} />
                  设置
                </button>
                <button type="button" className={roomClasses("logout-button")} onClick={() => void logout()}>
                  <AppIcon icon="lucide:log-out" size={16} />
                  退出
                </button>
              </>
            )}
          </div>
        </header>
        {accountSettingsPanel}

        <section className={roomClasses("microphone-gate")}>
          <div className={roomClasses("microphone-copy")}>
            <span className={roomClasses("eyebrow")}>Microphone Check</span>
            <h1>先确认系统能听清，再进入正式面试。</h1>
            <p>
              面试环节会采用腾讯云实时语音识别，回答时边说边转写，单轮回答最长 5 分钟。进入前先选择麦克风并说一句话，音量条有明显波动后再进入训练房间。
            </p>
            <div className={roomClasses("microphone-proof")}>
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

          <div className={roomClasses("microphone-card")}>
            <div className={roomClasses("material-card-heading")}>
              <span>
                <AppIcon icon="lucide:settings-2" size={18} />
                输入设备
              </span>
              <em>{microphoneStatus === "ready" ? "已通过" : microphoneStatus === "testing" ? "检测中" : "待检测"}</em>
            </div>

            <label className={roomClasses("microphone-select")}>
              <span>麦克风</span>
              <Select
                value={selectedMicrophoneId}
                options={
                  microphoneDevices.length === 0
                    ? [{ value: "", label: "使用系统默认麦克风" }]
                    : microphoneDevices.map((device, index) => ({
                        value: device.deviceId,
                        label: microphoneLabel(device, index),
                      }))
                }
                onChange={handleMicrophoneChange}
                ariaLabel="选择麦克风"
              />
            </label>

            <MicrophoneLevelMeter
              analyser={microphoneAnalyser}
              onVoiceDetected={() => {
                microphoneVoiceDetectedRef.current = true;
                setMicrophoneStatus("ready");
                setMicrophoneMessage("麦克风可用，声音已经能被系统采集。");
              }}
            />
            <p>{microphoneMessage}</p>

            <div className={roomClasses("microphone-actions")}>
              <Button
                type="button"
                className={roomClasses("mobile-action-button")}
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
                className={roomClasses("mobile-action-button")}
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
    <main className={roomClasses("workspace-page interview-page")}>
      {shouldShowWorkspaceHeader && (
        <header className={roomClasses("workspace-header", "interview-workspace-header", `interview-workspace-header--${routeStage}`)}>
          <a href="/" className={roomClasses("brand-mark")}>
            <BrandLogo size={28} />
            面霸练习生
          </a>
          {isSelectionStage && (
            <div className={roomClasses("workspace-flow-steps")} aria-label="训练流程">
              <span className={roomClasses("is-active")}>1 选场景</span>
              <span>2 补资料</span>
              <span>3 设备检测</span>
              <span>4 语音面试</span>
            </div>
          )}
          <div className={roomClasses("workspace-header-actions")}>
            <span className={roomClasses("session-pill")}>{currentUser ? "服务在线" : "未登录"} · {isSelectionStage ? "选择场景" : progressText}{expiryText ? ` · ${expiryText}` : ""}</span>
            <span className={roomClasses("credit-pill")}>
              <AppIcon icon="lucide:coins" size={16} />
              {accountQuotaText}
            </span>
            {currentUser && (
              <>
                <button type="button" className={roomClasses("logout-button", "account-button", isSettingsOpen && "is-active")} onClick={() => setIsSettingsOpen((value) => !value)}>
                  <AppIcon icon="lucide:settings-2" size={16} />
                  设置
                </button>
                <button type="button" className={roomClasses("logout-button")} onClick={() => void logout()}>
                  <AppIcon icon="lucide:log-out" size={16} />
                  退出
                </button>
              </>
            )}
          </div>
        </header>
      )}
      {accountSettingsPanel}

      <section className={roomClasses(isSelectionStage ? "interview-entry-layout" : "interview-layout")}>
        {isSelectionStage ? (
          <div className={roomClasses("scenario-hero-panel")}>
            <span className={roomClasses("eyebrow")}>Training Launchpad</span>
            <h1>选择训练场景，开始一次真实面试演练。</h1>
            <p>
              系统会根据场景自动切换题型结构、追问策略、评分维度与报告模板。选定后补齐必要资料，再进入设备检测。
            </p>
            {scenarioModuleGrid}

            <div className={roomClasses("scenario-highlight-card")}>
              <div className={roomClasses("scenario-highlight-head")}>
                <span>
                  <AppIcon icon={selectedModule.icon} size={22} />
                  当前选择
                </span>
                <strong>{selectedModuleDetail.badge}</strong>
              </div>
              <h2>{selectedModule.title}</h2>
              <p>{selectedModuleDetail.intro}</p>
              <div className={roomClasses("scenario-facts")}>
                <span><AppIcon icon="lucide:route" size={16} />{selectedModuleDetail.rounds}</span>
                <span><AppIcon icon="lucide:check-circle-2" size={16} />{selectedModuleDetail.material}</span>
                <span><AppIcon icon="lucide:gauge" size={16} />{selectedModuleDetail.report}</span>
              </div>
            </div>

            <div className={roomClasses("scenario-proof-grid")}>
              <span><AppIcon icon="lucide:mic-2" size={17} />进入前检测麦克风</span>
              <span><AppIcon icon="lucide:history" size={17} />中断后可恢复</span>
              <span><AppIcon icon="lucide:shield-check" size={17} />训练记录可追溯</span>
            </div>
          </div>
        ) : (
          <AvatarStage state={state} />
        )}
        <aside className={roomClasses("interview-panel", isSelectionStage && "scenario-control-panel")}>
          <span className={roomClasses("eyebrow")}>{activeQuestion ? activeQuestion.round_name : isSelectionStage ? `${selectedModule.title} Setup` : "Voice-first Interview"}</span>
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
          <div className={roomClasses("socket-status")}>
            <AppIcon icon="lucide:radio" size={18} />
            <span>{socketMessage}</span>
          </div>

          {interviewTask.task && (
            <TaskProgress
              task={interviewTask.task}
              syncError={interviewTask.error?.message}
              onRefresh={interviewTask.refresh}
              onRetry={interviewTask.task.retryable ? interviewTask.retry : undefined}
            />
          )}

          {(transcriptPreview || isRecording || isFinishingAnswer) && (
            <div className={roomClasses("transcript-card")}>
              <div>
                <AppIcon icon="lucide:captions" size={17} />
                <span>{isFinishingAnswer ? "正在整理本轮回答" : isRecording ? "实时转写预览" : "上一轮转写"}</span>
              </div>
              <p>{transcriptPreview || "已经开始监听，说话后这里会显示实时转写内容。"}</p>
            </div>
          )}

          <RecordingProgress
            active={isRecording || isFinishingAnswer}
            startedAt={recordingStartedAt}
            limitMs={answerLimitMs}
            getSilenceRemainingMs={audioRecorder.getSilenceRemainingMs}
          />

          {isSelectionStage && !interviewState && activeSession && (
            <div className={roomClasses("resume-card")}>
              <AppIcon icon="lucide:history" size={20} />
              <div>
                <strong>可恢复上次训练</strong>
                <span>{activeSession.current_question?.round_name} · {activeSession.current_step_index + 1}/{activeSession.total_steps}</span>
              </div>
              <button type="button" onClick={() => goToMicrophoneCheck(true)}>设备检测后恢复</button>
            </div>
          )}

          {isSelectionStage && !interviewState && (
            <div className={roomClasses("scenario-checklist")}>
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

          {isSelectionStage && !interviewState && selectedModuleNeedsPersonalization && (
            <section className={roomClasses("material-card")}>
              <div className={roomClasses("material-card-heading")}>
                <span>
                  <AppIcon icon={selectedModule.type === "job" ? "lucide:file-scan" : "lucide:notebook-tabs"} size={18} />
                  {selectedModule.type === "job" ? "本次面试个性化资料" : "本次复试背景"}
                </span>
                {personalizationReady ? <em>填写完成</em> : <em>开始前必填</em>}
              </div>
              {selectedModule.type === "job" ? (
                <div className={roomClasses("material-form")}>
                  <label className={roomClasses("file-picker-field")}>
                    <span>简历文件</span>
                    <input
                      type="file"
                      accept=".txt,.md,.pdf,.docx,text/plain,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                      onChange={(event) => setJobResumeFile(event.currentTarget.files?.[0] ?? null)}
                    />
                    <span className={roomClasses("file-picker-control")}>
                      <strong>选择文件</strong>
                      <em>{jobResumeFile?.name ?? "未选择文件"}</em>
                    </span>
                  </label>
                  <label>
                    <span>应聘岗位</span>
                    <input value={jobTitle} onChange={(event) => setJobTitle(event.currentTarget.value)} placeholder="例如：AI 后端工程师" />
                  </label>
                  <label className={roomClasses("material-form-wide")}>
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
                <div className={roomClasses("material-form")}>
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
              <p className={roomClasses("material-preview")}>
                隐私说明：资料仅在创建本次训练的请求内解析和检索，不写入数据库、Redis、日志或任务队列；请求完成后服务端立即释放。
              </p>
            </section>
          )}

          {interviewState?.status !== "completed" && (
            !interviewState ? (
              <div className={roomClasses("voice-controls")}>
                <Button
                  type="button"
                  className={roomClasses("mobile-action-button action-button-primary")}
                  color="primary"
                  shape="rounded"
                  loading={isStartingSession}
                  disabled={isStartingSession || isExitingSession || Boolean(currentUser && isSelectionStage && selectedModuleNeedsPersonalization && !personalizationReady)}
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
                  <AppIcon icon={isSelectionStage ? "lucide:file-scan" : "lucide:play"} size={18} />
                  {isStartingSession ? "启动中" : !currentUser ? "先登录账户" : isSelectionStage ? "进入设备检测" : "重新启动面试"}
                </Button>
                <Button
                  type="button"
                  className={roomClasses("mobile-action-button action-button-dark")}
                  fill="outline"
                  shape="rounded"
                  loading={isExitingSession}
                  disabled={isExitingSession}
                  onClick={() => void startFresh()}
                >
                  <AppIcon icon={routeStage === "room" ? "lucide:log-out" : "lucide:rotate-ccw"} size={18} />
                  {isExitingSession ? "退出中" : routeStage === "room" ? "退出训练" : "新训练"}
                </Button>
              </div>
            ) : (
              <RecordingControls
                isPreparing={isPreparingAnswer}
                isRecording={isRecording}
                isFinishing={isFinishingAnswer}
                isQuestionSpeaking={isQuestionSpeaking}
                isTaskPending={interviewTask.isPending || interviewState?.status !== "active"}
                isExiting={isExitingSession}
                canReplay={canReplayQuestion}
                onStart={() => void startAnswer()}
                onFinish={() => void finishAnswer()}
                onReplay={() => {
                  if (canReplayQuestion) {
                    void speakQuestion(interviewState.current_question?.text, interviewState.interview_type, interviewState.session_id);
                  }
                }}
                onExit={() => void startFresh()}
              />
            )
          )}

          {isSelectionStage && (
            <section className={roomClasses("history-card")}>
              <div className={roomClasses("history-card-heading")}>
                <span>
                  <AppIcon icon="lucide:history" size={18} />
                  最近训练
                </span>
                <em>{historyItems.length} 条</em>
              </div>
              {historyItems.length === 0 ? (
                <p>暂无训练记录。完成第一次模拟后，这里会显示中断恢复入口和历史复盘。</p>
              ) : (
                <div className={roomClasses("history-list")}>
                  {historyItems.map((item) => {
                    const module = moduleByType(item.interview_type);
                    return (
                      <article className={roomClasses("history-list-item")} key={item.session_id}>
                        <button type="button" className={roomClasses("history-open-button")} onClick={() => void openHistoryItem(item)}>
                          <AppIcon icon={module.icon} size={18} />
                          <span>
                            <strong>{module.title}</strong>
                            <em>{formatHistoryDate(item.created_at)} · {statusLabel(item.status)}</em>
                          </span>
                          <b>{item.report_total_score ?? `${item.current_step_index + 1}/${item.total_steps}`}</b>
                        </button>
                        <button
                          type="button"
                          className={roomClasses("history-delete-button")}
                          disabled={deletingHistorySessionId === item.session_id}
                          onClick={() => void deleteHistoryItem(item)}
                          aria-label={`删除${module.title}训练记录`}
                        >
                          <AppIcon icon="lucide:trash-2" size={16} />
                          {deletingHistorySessionId === item.session_id ? "删除中" : "删除"}
                        </button>
                      </article>
                    );
                  })}
                </div>
              )}
            </section>
          )}

          {interviewState?.report && <InterviewReport report={interviewState.report} />}
        </aside>
      </section>
      <SafeArea position="bottom" />
      <ConfirmDialog
        open={confirmDeleteItem !== null}
        title="删除训练记录"
        message="删除后将无法在最近训练中恢复这场记录，确认删除吗？"
        confirmLabel="删除"
        variant="danger"
        onConfirm={() => {
          if (confirmDeleteItem) void confirmDeleteHistoryItem(confirmDeleteItem);
          setConfirmDeleteItem(null);
        }}
        onCancel={() => setConfirmDeleteItem(null)}
      />
      <ConfirmDialog
        open={confirmExitTarget !== null}
        title="退出当前训练"
        message="退出会终止当前未完成训练，并取消仍在处理的 AI 任务。确认继续吗？"
        confirmLabel="确认退出"
        variant="danger"
        onConfirm={() => {
          const target = confirmExitTarget;
          setConfirmExitTarget(null);
          void doStartFresh(target);
        }}
        onCancel={() => setConfirmExitTarget(null)}
      />
    </main>
  );
}
