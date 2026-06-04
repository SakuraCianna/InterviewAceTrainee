import { AppIcon } from "./AppIcon";

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

const stateDetails: Record<AvatarState, string> = {
  idle: "Context ready",
  listening: "Capturing answer",
  thinking: "Scoring signal",
  speaking: "Question live"
};

export function AvatarStage({ state, compact = false }: AvatarStageProps) {
  return (
    <div className={`mentor-stage mentor-stage--${state} ${compact ? "mentor-stage--compact" : ""}`}>
      <div className="mentor-orbit mentor-orbit--outer" />
      <div className="mentor-orbit mentor-orbit--inner" />
      <div className="mentor-device">
        <div className="mentor-glass" aria-label={`数字面试官正在${stateLabels[state]}`}>
          <div className="mentor-scanline" />
          <div className="mentor-lens">
            <span className="mentor-lens-ring mentor-lens-ring--outer" />
            <span className="mentor-lens-ring mentor-lens-ring--inner" />
            <AppIcon icon="lucide:brain" size={42} />
          </div>
          <div className="mentor-spectrum" aria-hidden="true">
            {Array.from({ length: 18 }).map((_, index) => (
              <span key={index} style={{ animationDelay: `${index * 42}ms` }} />
            ))}
          </div>
        </div>
        <div className="mentor-status">
          {state === "listening" && <AppIcon icon="lucide:mic" size={16} />}
          {state === "thinking" && <AppIcon icon="lucide:brain" size={16} />}
          {state === "speaking" && <AppIcon icon="lucide:sparkles" size={16} />}
          {state === "idle" && <AppIcon icon="lucide:sparkles" size={16} />}
          <span>{stateLabels[state]}</span>
        </div>
        <div className="mentor-console" aria-hidden="true">
          <span>Context</span>
          <span>Follow-up</span>
          <span>Rubric</span>
        </div>
        <strong className="mentor-readout">{stateDetails[state]}</strong>
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
