package icu.sakuracianna.mianba.aiwork.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** AI 任务状态机，使用 version 防止并发 Worker 覆盖较新的持久化状态。 */
public final class AiJob {
    private static final int MAX_ATTEMPTS = 3;

    private final UUID id;
    private final UUID ownerId;
    private final JobKind kind;
    private final String idempotencyKey;
    private final Instant expiresAt;
    private JobStatus status = JobStatus.QUEUED;
    private int attempt;
    private long version;
    private boolean retryable = true;
    private String leaseOwner;
    private Instant leaseUntil;
    private JobFailure failure;

    private AiJob(UUID id, UUID ownerId, JobKind kind, String idempotencyKey, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * 创建尚未被 Worker 领取的任务。
     *
     * @param id 任务唯一标识
     * @param ownerId 任务所有者标识
     * @param kind 任务类型
     * @param idempotencyKey 客户端幂等键
     * @param createdAt 创建时间
     * @param expiresAt 业务有效期截止时间，必须晚于创建时间
     * @return 初始状态为 {@link JobStatus#QUEUED} 的任务
     * @throws IllegalArgumentException 有效期不晚于创建时间时抛出
     */
    public static AiJob queued(
            UUID id,
            UUID ownerId,
            JobKind kind,
            String idempotencyKey,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(createdAt, "createdAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        return new AiJob(id, ownerId, kind, idempotencyKey, expiresAt);
    }

    /**
     * 使用乐观版本校验领取任务并建立 Worker 租约。
     * 同步锁保护同一聚合实例，版本号负责防止不同进程覆盖数据库中的新状态。
     *
     * @param workerId Worker 实例标识
     * @param now 当前时间
     * @param leaseDuration 租约时长
     * @param expectedVersion 调用方读取到的任务版本
     * @throws StaleJobException 任务过期、状态不允许领取或版本已变化时抛出
     */
    public synchronized void claim(String workerId, Instant now, Duration leaseDuration, long expectedVersion) {
        requireVersion(expectedVersion);
        if (!now.isBefore(expiresAt)) {
            status = JobStatus.CANCELLED;
            retryable = false;
            version++;
            throw new StaleJobException("TASK_STALE");
        }
        if (status != JobStatus.QUEUED) {
            throw new StaleJobException("job_not_queued");
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        status = JobStatus.RUNNING;
        attempt++;
        leaseOwner = Objects.requireNonNull(workerId, "workerId");
        leaseUntil = now.plus(leaseDuration);
        version++;
    }

    /**
     * 记录本次执行失败，并依据重试预算和任务有效期决定进入重试或终态。
     *
     * @param jobFailure 对外稳定的失败信息
     * @param now 当前时间
     * @param expectedVersion 调用方读取到的任务版本
     * @throws StaleJobException 任务不在运行态或版本已变化时抛出
     */
    public synchronized void fail(JobFailure jobFailure, Instant now, long expectedVersion) {
        Objects.requireNonNull(now, "now");
        requireVersion(expectedVersion);
        if (status != JobStatus.RUNNING) {
            throw new StaleJobException("job_not_running");
        }
        failure = Objects.requireNonNull(jobFailure, "jobFailure");
        leaseOwner = null;
        leaseUntil = null;
        if (jobFailure.retryable() && attempt < MAX_ATTEMPTS && now.isBefore(expiresAt)) {
            status = JobStatus.RETRYING;
            retryable = true;
        } else {
            status = now.isBefore(expiresAt) ? JobStatus.FAILED : JobStatus.CANCELLED;
            retryable = false;
        }
        version++;
    }

    /**
     * 将等待重试的任务重新放回队列态。
     *
     * @param now 当前时间
     * @param expectedVersion 调用方读取到的任务版本
     * @throws StaleJobException 任务不可重试、已过期或版本已变化时抛出
     */
    public synchronized void requeue(Instant now, long expectedVersion) {
        requireVersion(expectedVersion);
        if (status != JobStatus.RETRYING || !now.isBefore(expiresAt)) {
            throw new StaleJobException("job_not_retryable");
        }
        status = JobStatus.QUEUED;
        version++;
    }

    private void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new StaleJobException("job_version_stale");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public JobKind kind() {
        return kind;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public synchronized JobStatus status() {
        return status;
    }

    public synchronized int attempt() {
        return attempt;
    }

    public synchronized int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized boolean retryable() {
        return retryable;
    }

    public synchronized String leaseOwner() {
        return leaseOwner;
    }

    public synchronized Instant leaseUntil() {
        return leaseUntil;
    }

    public synchronized JobFailure failure() {
        return failure;
    }
}
