import { Button } from "antd-mobile";
import { AppIcon } from "../../../components/AppIcon";
import styles from "./RecordingControls.module.css";

type RecordingControlsProps = {
  isPreparing: boolean;
  isRecording: boolean;
  isFinishing: boolean;
  isQuestionSpeaking: boolean;
  isTaskPending: boolean;
  isExiting: boolean;
  canReplay: boolean;
  onStart: () => void;
  onFinish: () => void;
  onReplay: () => void;
  onExit: () => void;
};

export function RecordingControls(props: RecordingControlsProps) {
  const startClass = `${styles.darkButton}${props.isRecording ? ` ${styles.recording}` : ""}`;
  return (
    <div className={styles.controls} aria-label="回答控制">
      <Button
        type="button"
        className={startClass}
        shape="rounded"
        loading={props.isPreparing}
        disabled={props.isRecording || props.isPreparing || props.isFinishing || props.isQuestionSpeaking || props.isTaskPending}
        onClick={props.onStart}
      >
        <AppIcon icon={props.isRecording ? "lucide:mic" : "lucide:mic"} size={18} />
        {props.isPreparing ? "准备中" : props.isRecording ? "回答中" : "开始回答"}
      </Button>
      <Button
        type="button"
        className={styles.primaryButton}
        shape="rounded"
        loading={props.isFinishing}
        disabled={!props.isRecording || props.isPreparing || props.isFinishing || props.isTaskPending}
        onClick={props.onFinish}
      >
        <AppIcon icon="lucide:check-circle-2" size={18} />
        {props.isFinishing ? "提交中" : "回答完毕"}
      </Button>
      <Button type="button" className={styles.secondaryButton} shape="rounded" disabled={!props.canReplay} onClick={props.onReplay}>
        <AppIcon icon="lucide:volume-2" size={18} />
        {props.isQuestionSpeaking ? "正在播报" : "重播问题"}
      </Button>
      <Button
        type="button"
        className={styles.secondaryButton}
        shape="rounded"
        loading={props.isExiting}
        disabled={props.isExiting}
        onClick={props.onExit}
      >
        <AppIcon icon="lucide:log-out" size={18} />
        {props.isExiting ? "退出中" : "退出训练"}
      </Button>
    </div>
  );
}
