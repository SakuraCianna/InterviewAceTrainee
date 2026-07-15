export type InterviewType = "job" | "postgraduate" | "civil_service" | "ielts";

export type InterviewQuestion = {
  turn_index: number;
  round_name: string;
  text: string;
};

export type InterviewReport = {
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

export type InterviewStateResponse = {
  session_id: string;
  interview_type: InterviewType;
  status: string;
  current_step_index: number;
  total_steps: number;
  current_question: InterviewQuestion | null;
  active_task?: import("../../lib/api").AiTask | null;
  report: InterviewReport | null;
  detail?: string;
  message?: string;
};

export type InterviewAnswerTaskResponse = {
  request_id?: string;
  session?: InterviewStateResponse;
  task: import("../../lib/api").AiTask;
};

export type InterviewAnswerResponse = InterviewStateResponse | InterviewAnswerTaskResponse;

export type CurrentUserResponse = {
  email: string;
  role: string;
  credit_balance: number;
  trial_voucher_count: number;
};

export type InterviewHistoryItem = {
  session_id: string;
  interview_type: InterviewType;
  status: string;
  current_step_index: number;
  total_steps: number;
  report_total_score: number | null;
  created_at: string;
};

export type InterviewMaterialResponse = {
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

export type SpeechSynthesisApiResponse = {
  audio_base64?: string;
  mime_type?: string;
  detail?: string;
  message?: string;
};

export type RealtimeAsrMessage = {
  type?: string;
  text?: string;
  current_text?: string;
  detail?: string;
  message?: string;
  provider_code?: number;
  sample_rate?: number;
  chunk_ms?: number;
};

export type TranscriptWaiter = {
  promise: Promise<string>;
  resolve: (text: string) => void;
  reject: (error: Error) => void;
  settled: boolean;
};

export type AudioRecorderSession = {
  context: AudioContext;
  source: MediaStreamAudioSourceNode;
  processor: ScriptProcessorNode;
  stream: MediaStream;
  socket: WebSocket;
  pendingSamples: Float32Array;
  sampleRate: number;
};

export type MicrophoneTestSession = {
  context: AudioContext;
  source: MediaStreamAudioSourceNode;
  analyser: AnalyserNode;
  stream: MediaStream;
};

export type ApiPayload = {
  detail?: string;
  message?: string;
  dev_code?: string;
};
