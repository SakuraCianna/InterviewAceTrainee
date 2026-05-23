import { Brain, Mic, Sparkles } from "lucide-react";

export type AvatarState = "idle" | "listening" | "thinking" | "speaking";

type AvatarStageProps = {
  state: AvatarState;
  compact?: boolean;
};

const stateLabels: Record<AvatarState, string> = {
  idle: "待命",
  listening: "聆听",
  thinking: "思考",
  speaking: "提问"
};

export function AvatarStage({ state, compact = false }: AvatarStageProps) {
  return (
    <div className={`mentor-stage mentor-stage--${state} ${compact ? "mentor-stage--compact" : ""}`}>
      <div className="mentor-orbit mentor-orbit--outer" />
      <div className="mentor-orbit mentor-orbit--inner" />
      <div className="mentor-device">
        <div className="mentor-glass" aria-label={`AI 面试官正在${stateLabels[state]}`}>
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
          <div className="mentor-audio">
            <span />
            <span />
            <span />
            <span />
          </div>
        </div>
        <div className="mentor-status">
          {state === "listening" && <Mic size={16} />}
          {state === "thinking" && <Brain size={16} />}
          {state === "speaking" && <Sparkles size={16} />}
          {state === "idle" && <Sparkles size={16} />}
          <span>{stateLabels[state]}</span>
        </div>
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
