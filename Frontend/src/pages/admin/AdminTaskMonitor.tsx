import type { AiTask, TaskStatus } from "../../lib/api";
import styles from "./AdminTaskMonitor.module.css";

const STATUS_LABELS: Record<TaskStatus, string> = {
  QUEUED: "排队中",
  RUNNING: "处理中",
  RETRYING: "等待重试",
  SUCCEEDED: "已完成",
  FAILED: "需处理",
  CANCELLED: "已取消",
};

type AdminTaskMonitorProps = {
  tasks: AiTask[];
  loading: boolean;
  isRetrying?: (task: AiTask) => boolean;
  onRetry: (task: AiTask) => void;
};

/** 管理端 AI 队列视图；状态版本用于识别迟到事件，不展示模型输入或敏感正文。 */
export function AdminTaskMonitor({ tasks, loading, isRetrying, onRetry }: AdminTaskMonitorProps) {
  if (loading) {
    return <p className={styles.empty}>正在读取 Worker 队列...</p>;
  }
  if (tasks.length === 0) {
    return <p className={styles.empty}>当前没有 AI 任务。</p>;
  }
  return (
    <div className={styles.list}>
      {tasks.map((task) => (
        <article className={styles.item} data-status={task.status} key={task.id}>
          <div className={styles.identity}>
            <strong>{task.kind}</strong>
            <span>{task.session_id ?? "无关联会话"}</span>
            <code>{task.id}</code>
          </div>
          <div className={styles.progress}>
            <span style={{ width: `${Math.min(100, Math.max(0, task.progress))}%` }} />
          </div>
          <div className={styles.meta}>
            <b>{STATUS_LABELS[task.status]}</b>
            <span>{task.stage}</span>
            <span>尝试 {task.attempt}/{task.max_attempts} · v{task.version}</span>
          </div>
          {task.error && <p className={styles.error}>{task.error.code}：{task.error.message ?? "任务处理失败"}</p>}
          {task.status === "FAILED" && task.retryable && (
            <button className={styles.retry} type="button" disabled={isRetrying?.(task)} onClick={() => onRetry(task)}>
              {isRetrying?.(task) ? "重新入队中" : "重新入队"}
            </button>
          )}
        </article>
      ))}
    </div>
  );
}
