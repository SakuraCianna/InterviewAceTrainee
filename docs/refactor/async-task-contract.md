# AI 异步任务契约 v1

## 1. 创建任务

需要 AI 的写操作返回 HTTP 202，响应设置 `Location: /api/tasks/{taskId}` 和短 `Retry-After`。客户端必须发送 `Idempotency-Key`；同一用户、资源和请求体在有效期内重复提交返回同一个任务。

```json
{
  "request_id": "req_...",
  "session": {},
  "task": {
    "id": "4dfd5e1b-2c0a-4b6e-8de6-22a8880eced5",
    "kind": "GENERATE_FOLLOW_UP",
    "status": "QUEUED",
    "stage": "WAITING_FOR_WORKER",
    "progress": 0,
    "attempt": 0,
    "max_attempts": 3,
    "retryable": true,
    "version": 0,
    "result_ref": null,
    "error": null,
    "created_at": "2026-07-14T12:00:00Z",
    "updated_at": "2026-07-14T12:00:00Z"
  }
}
```

当前正式开放的异步 kind 是 `GENERATE_FOLLOW_UP`；Worker 在非末轮生成下一问，在末轮原子生成最终报告。Schema 为后续版本预留 `ANALYZE_MATERIAL`、`GENERATE_REPORT`、`SYNTHESIZE_SPEECH`，但它们尚未作为可创建任务的公开能力。实时 ASR 不是任务 kind。

## 2. 状态机

```text
QUEUED -> RUNNING -> SUCCEEDED
  |          |
  |          -> RETRYING -> QUEUED
  |          -> FAILED
  -> CANCELLED
```

- `QUEUED`：事务已提交，等待 Outbox 发布或 Worker。
- `RUNNING`：Worker 已取得租约。
- `RETRYING`：本次失败但可重试，包含下次执行时间。
- `SUCCEEDED`：结果已原子写回业务表。
- `FAILED`：不可重试或达到最大次数。
- `CANCELLED`：会话删除、用户取消或任务已过期。

状态只能沿合法边推进。事件带单调递增 `version`，客户端忽略旧版本。

## 3. 查询、重试与刷新恢复

- `GET /api/tasks/{taskId}`：仅任务所有者或管理员可见。
- `POST /api/tasks/{taskId}/retry`：仅 `FAILED` 且 `retryable=true`；需要 CSRF 和新的 `Idempotency-Key`。
- `GET /api/admin/tasks`：管理员按状态、kind 和 session 查询，必须分页。
- `GET /api/interviews/active`：返回未完成会话及可选 `active_task`，页面刷新后直接接续轮询，不重复 POST 创建会话。
- 不提供任务状态推送 WebSocket；浏览器在页面可见时按退避间隔轮询，断网恢复后仍以 GET 为事实来源。

## 4. 错误协议

所有 HTTP 错误使用：

```json
{
  "detail": "rate_limit_exceeded",
  "message": "请求过于频繁，请稍后重试",
  "request_id": "req_...",
  "errors": []
}
```

任务错误使用稳定 `code`，不把 Provider 原始响应、提示词、密钥或栈返回前端：

- `AI_PROVIDER_UNAVAILABLE`
- `AI_PROVIDER_DISABLED`
- `AI_OUTPUT_INVALID`
- `TASK_STALE`
- `ATTEMPT_BUDGET_EXHAUSTED`
- `WORKER_LEASE_EXHAUSTED`

## 5. RabbitMQ 内部消息

消息体不包含简历、答案或报告全文：

```json
{
  "schema_version": 1,
  "message_id": "...",
  "job_id": "...",
  "correlation_id": "...",
  "trace_id": "...",
  "created_at": "2026-07-14T12:00:00Z"
}
```

交换机、队列与 DLQ：

- exchange：`mianba.ai.v1`
- queue：`mianba.ai.jobs.v1`
- dead-letter queue：`mianba.ai.dlq.v1`

消息持久化、publisher confirm、手动 ACK。延迟重试写入新的 PostgreSQL Outbox 事件，通过 `available_at` 再发布，不使用 retry queue。Worker 先按 Job ID/版本取得数据库租约，再从 `turns` 与 Job 的受控材料上下文快照读取输入；提交逐轮评价、整场聚合报告和任务终态后 ACK。重复消息不得重复扣次数、推进轮次或生成多份报告。业务终态不依赖一次不可靠的 reject 进入 DLQ；任务表是权威告警，DLQ 仅处理畸形消息与超过 delivery-limit 的毒消息。
