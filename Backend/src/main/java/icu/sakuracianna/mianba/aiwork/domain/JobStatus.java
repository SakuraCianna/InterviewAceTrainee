package icu.sakuracianna.mianba.aiwork.domain;

/** 可持久化 AI 任务的生命周期状态。 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    RETRYING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
