import { useRef, useState } from "react";
import type { AiTask } from "../../../lib/api";
import styles from "./TaskProgress.module.css";

const statusLabels: Record<AiTask["status"], string> = {
  QUEUED: "已进入处理队列",
  RUNNING: "AI 正在处理",
  RETRYING: "服务繁忙，正在自动重试",
  SUCCEEDED: "处理完成",
  FAILED: "处理失败",
  CANCELLED: "任务已取消",
};

const stageLabels: Record<string, string> = {
  WAITING_FOR_WORKER: "等待可用处理席位",
  CALLING_MODEL: "正在生成下一步内容",
  VALIDATING_OUTPUT: "正在校验生成结果",
  WRITING_RESULT: "正在保存训练结果",
  RETRY_DELAY: "本次调用未完成，等待自动重试",
  LEASE_RECOVERY: "处理节点中断，正在安全恢复任务",
  NEEDS_ATTENTION: "自动处理未完成，需要人工重试",
  TASK_EXPIRED: "任务已超过有效处理时间",
  TASK_STALE: "面试轮次已变化，旧任务已停止",
  SESSION_DELETED: "所属训练已删除",
  COMPLETED: "训练结果已保存",
};

type TaskProgressProps = {
  task: AiTask;
  onRetry?: () => void | Promise<void>;
  onRefresh?: () => void | Promise<void>;
  syncError?: string | null;
};

export function TaskProgress({ task, onRetry, onRefresh, syncError }: TaskProgressProps) {
  const [isSubmittingRetry, setIsSubmittingRetry] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const retryLockRef = useRef(false);
  const progress = Math.min(100, Math.max(0, task.progress));
  const canRetry = task.status === "FAILED" && task.retryable && Boolean(onRetry);

  async function handleRetry() {
    if (!onRetry || retryLockRef.current) {
      return;
    }
    retryLockRef.current = true;
    setIsSubmittingRetry(true);
    try {
      await onRetry();
    } finally {
      retryLockRef.current = false;
      setIsSubmittingRetry(false);
    }
  }

  async function handleRefresh() {
    if (!onRefresh || isRefreshing) {
      return;
    }
    setIsRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setIsRefreshing(false);
    }
  }

  return (
    <section className={styles.card} role="status" aria-live="polite" aria-atomic="true">
      <div className={styles.heading}>
        <div>
          <span className={styles.kicker}>AI TASK</span>
          <strong>{statusLabels[task.status]}</strong>
        </div>
        <span className={styles.percent}>{progress}%</span>
      </div>
      <progress
        className={styles.progress}
        max={100}
        value={progress}
        aria-label="AI 任务进度"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress}
      />
      <p>{task.error?.message || stageLabels[task.stage] || task.stage || "正在同步处理状态"}</p>
      {syncError && <p className={styles.syncError}>状态同步暂时失败，系统会自动重试。{syncError}</p>}
      <div className={styles.meta}>
        <span>尝试 {task.attempt}/{task.max_attempts}</span>
        <span>任务 {task.id.slice(0, 8)}</span>
      </div>
      {(canRetry || (syncError && onRefresh)) && (
        <div className={styles.actions}>
          {canRetry && (
            <button type="button" className={styles.retryButton} disabled={isSubmittingRetry} onClick={() => void handleRetry()}>
              {isSubmittingRetry ? "重试提交中" : "重试任务"}
            </button>
          )}
          {syncError && onRefresh && (
            <button type="button" className={styles.refreshButton} disabled={isRefreshing} onClick={() => void handleRefresh()}>
              {isRefreshing ? "刷新中" : "立即刷新状态"}
            </button>
          )}
        </div>
      )}
    </section>
  );
}
