import { AppIcon } from "./AppIcon";

export type AvatarState = "idle" | "listening" | "thinking" | "speaking";

type AvatarStageProps = {
  state: AvatarState;
  compact?: boolean;
};

const stateCopy: Record<AvatarState, { label: string; title: string; detail: string; icon: string }> = {
  idle: {
    label: "待命",
    title: "AI 面试官已就绪",
    detail: "等待进入下一轮训练",
    icon: "lucide:sparkles"
  },
  listening: {
    label: "聆听",
    title: "正在捕捉回答",
    detail: "请保持自然语速作答",
    icon: "lucide:mic"
  },
  thinking: {
    label: "分析",
    title: "正在拆解表达结构",
    detail: "系统会生成追问与评分依据",
    icon: "lucide:brain"
  },
  speaking: {
    label: "提问",
    title: "正在播报面试问题",
    detail: "请听完问题后再开始回答",
    icon: "lucide:message-circle"
  }
};

export function AvatarStage({ state, compact = false }: AvatarStageProps) {
  const copy = stateCopy[state];

  return (
    <div className={`mentor-stage mentor-stage--${state} ${compact ? "mentor-stage--compact" : ""}`} data-state={state}>
      <div className="mentor-orbit mentor-orbit--outer" />
      <div className="mentor-orbit mentor-orbit--inner" />
      <div className="mentor-device">
        <div className="mentor-glass" aria-label={`数字面试官状态: ${copy.label}`}>
          <div className="mentor-scanline" />
          <div className="mentor-visor">
            <span />
            <span />
          </div>
          <div className="mentor-core">
            <i />
            <i />
            <i />
          </div>
          <div className="mentor-audio" aria-hidden="true">
            <span />
            <span />
            <span />
            <span />
          </div>
        </div>
        <div className="mentor-status">
          <AppIcon icon={copy.icon} size={16} />
          <span>{copy.label}</span>
        </div>
      </div>
      <div className="mentor-state-stack">
        <span className="mentor-state-label">Agent Interviewer</span>
        <strong>{copy.title}</strong>
        <p>{copy.detail}</p>
      </div>
      <div className="mentor-wave" aria-hidden="true">
        <span />
        <span />
        <span />
        <span />
        <span />
      </div>
    </div>
  );
}
