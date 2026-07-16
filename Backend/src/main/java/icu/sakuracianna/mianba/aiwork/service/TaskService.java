package icu.sakuracianna.mianba.aiwork.service;

import java.util.Optional;
import java.util.UUID;

/** 按任务所有者查询、重试和管理异步任务的应用服务。 */
public interface TaskService {
    /**
     * 查询任务详情；普通用户只能查看自己的任务，管理员可查看任意任务。
     *
     * @param requesterId 请求用户标识
     * @param admin 是否具有管理员权限
     * @param taskId 任务标识
     * @return 任务当前快照
     */
    TaskView get(UUID requesterId, boolean admin, UUID taskId);

    /**
     * 根据所有者和幂等键查找已创建任务，用于重放相同业务请求。
     *
     * @param ownerId 任务所有者标识
     * @param idempotencyKey 原始请求幂等键
     * @return 已存在的任务；不存在时返回空
     */
    Optional<TaskView> findByOwnerAndIdempotency(UUID ownerId, String idempotencyKey);

    /**
     * 返回指定用户会话最新的处理中或可人工重试任务，用于页面刷新后的状态恢复。
     *
     * @param ownerId 任务所有者标识
     * @param sessionId 面试会话标识
     * @return 当前任务；会话没有未完成任务时返回空
     */
    Optional<TaskView> findCurrentForOwnerSession(UUID ownerId, UUID sessionId);

    /**
     * 在人工重试预算内重新投递失败任务。
     *
     * @param requesterId 请求用户标识
     * @param admin 是否具有管理员权限
     * @param taskId 任务标识
     * @param idempotencyKey 本次重试幂等键
     * @param requestId 请求链路标识
     * @return 重试后的任务快照
     */
    TaskView retry(UUID requesterId, boolean admin, UUID taskId, String idempotencyKey, String requestId);

    /**
     * 按管理端筛选条件分页查询任务。
     *
     * @return 包含总数和翻页状态的任务页
     */
    TaskPage list(String status, String kind, UUID sessionId, int limit, int offset);

    /** 管理端任务分页结果。 */
    record TaskPage(java.util.List<TaskView> items, long total, int limit, int offset, boolean hasMore) {
        @com.fasterxml.jackson.annotation.JsonProperty("has_more")
        @Override
        public boolean hasMore() {
            return hasMore;
        }
    }
}
