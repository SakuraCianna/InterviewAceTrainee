# Boot 4 + Spring AI 2 商业化重构架构

## 1. 已确定的技术基线

- Java 21 LTS。
- Spring Boot 4.1.0。
- Spring AI BOM 2.0.0，DeepSeek 通过官方 `spring-ai-starter-model-deepseek` 接入。
- PostgreSQL 18 + pgvector 0.8.5。
- Redis 8.8.0 Alpine。用户原始输入中的 `airplane` 标签不存在，实际采用已验证的 `redis:8.8.0-alpine`。
- RabbitMQ 4.3.2 Management。
- React 19 + Vite 7，逐页迁移 CSS Module。

版本必须在 Maven、Docker Compose 和 CI 中精确固定，不使用 `4.x`、`latest`、`8-alpine`、`pg18` 等漂移范围。

## 2. 总体形态

采用“模块化单体 + 独立 AI Worker”，不在 4 核 4 GB 单机上过早拆分微服务。

```text
Browser
  -> Nginx: TLS、静态资源、限流、请求体限制
    -> API(Java): 鉴权、事务、业务规则、WebSocket、Outbox Publisher
      -> Material Parser(Java): 内网 Bearer 鉴权、受限 PDF/DOCX/TXT 解析
      -> PostgreSQL: 业务事实、AI Job、Outbox、审计
      -> Redis: 会话、验证码、短期限流、容量租约
      -> RabbitMQ: 只传 Job ID 和追踪元数据
      -> hCaptcha Siteverify: 匿名高风险认证入口的人机验证
    -> AI Worker(Java): DeepSeek 追问/逐轮评估、整场报告、重试、租约恢复、调用日志
      -> Spring AI 2 / DeepSeek
      -> PostgreSQL

实时 ASR：Browser <-> API WebSocket <-> Tencent ASR WebSocket
```

API 与 Worker 使用同一构建产物，通过运行角色启用不同 Bean；Material Parser 使用同一 JAR 的独立主类，不启动 Spring、数据库、Redis、RabbitMQ 或公网网络。三个进程分别设置 JVM 和并发边界。RabbitMQ 不承载原始音频、简历全文或大段回答。

## 3. 模块边界

根包使用 `icu.sakuracianna.mianba`，按业务纵切：

- `identity`：用户、登录、Cookie/JWT、CSRF、会话、验证码。
- `billing`：次数、体验券、不可变流水；不实现在线支付。
- `interview`：产品目录、材料、会话状态机、轮次、报告。
- `aiwork`：Job、Outbox、RabbitMQ、Worker、DeepSeek、重试与 DLQ。
- `speech`：腾讯 ASR/TTS 与实时 WebSocket；不进入普通任务队列。
- `interview.safety`：回答输入的提示注入观察；Worker 负责提示边界与输出校验。
- `admin`：用户运营、次数、体验券、客服备注、退款纠纷、审计、Worker 监控。
- `platform`：错误协议、配置校验、数据库、Redis、观测与健康检查。

Controller 不直接操作 Repository；事务边界位于 application service。领域表使用 UUID 外键，不再用邮箱字符串作为关联键。

## 4. 数据一致性

- 全新数据库由 Flyway 管理；服务器旧数据已由项目所有者明确允许丢弃，不执行 Alembic 原地升级。
- 所有时间使用 PostgreSQL `timestamptz`，Java 使用 `Instant`。
- 余额不得为负；流水、余额更新和审计必须同事务。
- `turns(session_id, turn_index)`、`reports(session_id)` 必须唯一。
- 会话和 AI Job 使用乐观锁；迟到、重复、乱序消息通过版本与幂等键拒绝。
- API 在业务事务中同时写 `ai_jobs` 和 `outbox_events`。发布器只投递 Job ID；Worker 提交数据库后才 ACK。
- Worker 至少一次消费；`ai_jobs.idempotency_key` 与 inbox message ID 防止重复副作用。
- Provider 网络错误和临时停用可受控重试；会话状态变化和过期任务不可重试。业务终态写入任务表并由任务中心展示，提交后 ACK；DLQ 只承接畸形消息和超过投递上限的基础设施毒消息。
- API 把裁剪后的材料摘要与岗位/院校信息固化到 Job 的 `input_ref`，Worker 不读取材料表；每轮评价写入 `turns`，最终报告按全部轮次聚合。
- pgvector 扩展随数据库安装，但 embedding/RAG 流水线尚未开放；健康接口必须显示 `rag_not_implemented`，不得把零向量宣称为就绪。

## 5. 安全与防御性编程

- 生产环境缺少强 JWT 密钥、Cookie Secure、数据库/Redis/Rabbit 凭据或 AI 密钥时拒绝启动对应能力。
- 密码使用 Argon2id；密码修改/重置后撤销旧会话。
- Cookie 写请求执行双提交 CSRF；WebSocket 校验 Origin、当前会话和资源所有权。
- 登录、验证码、上传、答题、语音、管理写操作分层限流；Redis 异常时高风险入口失败关闭。
- 匿名邮箱验证码、普通密码登录和管理员登录在限流之后、账号业务之前校验 hCaptcha；token 连同服务端 Secret、客户端 IP 和本站点标识提交 Siteverify。超时、非成功状态、站点不匹配、畸形响应和重复/过期 token 均失败关闭。
- hCaptcha 站点标识只通过 `GET /api/auth/hcaptcha/config` 公开，服务端 Secret 只存在于 0700 私有目录中的 0444 ConfigTree 文件。普通 Compose 不重映射文件 Secret 的 UID，0444 让指定的非 root 容器只读，宿主机访问仍由父目录限制。前端不内嵌运行时站点值，CSP 仅放行 hCaptcha 官方所需来源。
- 所有路径 ID、分页、JSON、文本长度、枚举、文件 MIME/魔数均做边界校验。
- API 只执行 5 MiB、MIME、扩展名和魔数前置校验；PDF/DOCX/TXT 在 256 MiB、Xmx 96 MiB、单并发且无公网的独立解析容器中处理。页数、DOCX 条目和展开体积继续硬限制；单次解析超过 8 秒时由进程级看门狗强制退出，Compose 自动拉起 Parser。异常高压缩 PDF 即使触发 OOM 或卡死，也不能重启 API，API 返回可重试错误并保持可用。
- 用户简历、JD、历史问题、当前问题和答案均是不可信输入；可信系统策略与数据使用不可闭合定界符隔离，数据中的角色切换、提示词泄露、工具调用或格式修改指令一律不执行。
- 四种面试使用服务端版本化的阶段、评分维度与兜底题；IELTS 从首题到追问、阶段名、反馈和最终报告均只允许英文，文本转写不能被模型当作发音证据。
- 实时 ASR 除音频字节时长外，还限制 15 秒未启动、20 秒客户端静默和 `maxSeconds + 30 秒` 绝对墙钟；超时路径先幂等归还全局许可，再关闭连接。
- 模型只允许返回四字段 JSON；重复键、未知字段、错误类型、尾随内容和超过 16 KB 的输出均失败，阶段名、语言和重复问题还要经过服务端归一化。
- API 错误只返回稳定错误码、用户可理解消息和 request ID，不泄露 SQL、栈、Provider 密钥或内部 URL。
- 管理员初始化不含默认账号、固定密码或公开 Bootstrap API；用户先完成正式注册，再由一次性、可审计的服务器脚本提升首个管理员。
- PostgreSQL 与 RabbitMQ 均拆分 API/Worker 运行账号；Worker 无权读取用户、余额、认证、客服与退款数据。

## 6. 4 核 4 GB 运行预算

| 服务 | 内存上限 | 内存 + Swap 上限 | CPU | 初始并发策略 |
| --- | ---: | ---: | ---: | --- |
| API JVM | 768 MiB | 1024 MiB | 1.25 | Xmx 448 MiB，Hikari 6，Tomcat 80 threads |
| Worker JVM | 640 MiB | 896 MiB | 0.75 | Xmx 352 MiB，消费者 1，LLM 并发 1 |
| Material Parser JVM | 256 MiB | 384 MiB | 0.35 | Xmx 96 MiB，单并发，无数据库与公网 |
| PostgreSQL | 960 MiB | 1152 MiB | 1.10 | shared_buffers 256 MB，连接上限 40 |
| Redis | 256 MiB | 320 MiB | 0.25 | maxmemory 160 MB，noeviction |
| RabbitMQ | 384 MiB | 512 MiB | 0.40 | watermark 0.55，队列长度上限和 DLQ |
| Nginx + Frontend | 128 MiB | 192 MiB | 0.20 | 静态资源强缓存，API 分级限流 |

稳定容器的物理内存硬上限合计 3392 MiB，内存与 Swap 合计上限为 4480 MiB。当前生产机实测约 3.6 GiB 物理内存和 1.9 GiB Swap，因此物理硬上限场景仅保留约 0.3 GiB；正常 RSS 必须明显低于硬上限，并持续观察 PSI、swap-in/out 与 JVM GC。Swap 只作为短峰值保险。一次性 migrate 在 API、Parser 与 Worker 启动前退出。生产机不执行 Maven/npm 构建，也不接收源码或测试；只加载由 CI 验证的镜像与 runtime allowlist 包。

## 7. 业务边界与补充建议

支付、退款和资金业务保持现状：界面仅提供微信人工联系与纠纷记录，不接支付网关、不自动退款。`refund` 是客服工单，不是财务账本；次数补偿必须通过独立余额流水完成。

商业化优先补充以下非支付能力：

1. 训练目标与周期计划：岗位/院校/考试日期、每周目标、完成率。
2. 能力维度趋势：同场景多次报告按维度比较，给出证据链而非只有总分。
3. AI 任务中心：用户看见排队、重试和失败，管理员看见积压、耗时、Provider 错误与 DLQ。
4. 数据留存与删除申请：简历、音频、回答、日志分别设置保留期和用户删除入口。
5. 报告反馈：有用/无用、问题原因和人工纠错，为后续评测集提供经授权样本。
6. 运营标签与客服待办：标签、负责人、下次跟进时间；避免备注成为不可搜索的文本堆。

首轮重构只落数据扩展点和任务监控，不同时扩张全部业务，优先保证面试闭环可靠。

## 8. 发布顺序

1. Java API/Worker 与新 schema 在 GitHub CI 的隔离 Compose 项目完成集成测试。
2. 前端适配异步任务状态和新错误协议。
3. CI 固定同一 commit 进行测试、构建和发布。
4. 生产进入维护窗口，确认目标主机/目录/Compose 项目后才停止旧服务。
5. 按本次明确授权和复核结果，逐项删除旧项目容器、网络、镜像与业务卷，同时保留 `.env`、密钥和证书；禁止无目标的 Docker 全局清理。后续发布恢复为默认先备份策略。
6. 启动数据层、迁移、Material Parser、API、Worker、前端和 Nginx，逐层通过 health/readiness/smoke。
7. 观察队列积压、错误率、RSS、GC、数据库连接和核心面试流程后再结束维护。
