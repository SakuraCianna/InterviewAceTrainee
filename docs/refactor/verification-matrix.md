# 重构验收矩阵

本矩阵用于核对本地验证、GitHub 持续集成（CI）和生产部署结果。只有实际执行并留下证据的条目才能标记通过。

## A. 构建与静态检查

- Java 21 下 `mvn test`、`mvn package` 成功，依赖树中只有 Spring Boot 4.1.0 / Spring AI 2.0.0 管理线。
- 前端 `typecheck`、单元测试、生产构建成功；没有新增隐式 `any`。
- Docker Compose 在不读取生产 `.env` 的临时测试变量下可渲染。
- Nginx 配置通过语法检查。
- 仓库扫描不包含密钥、固定管理员密码、私钥或生产 Cookie。

## B. 数据与事务

- Flyway 在空的 PostgreSQL 18 + pgvector 0.8.5 上一次完成。
- API/Worker 使用独立数据库账号；Worker 无法读取用户、余额、认证、客服与退款表。
- 所有 UUID 外键、唯一键和 CHECK 生效；负余额、重复轮次、重复报告被数据库拒绝。
- 创建会话时扣次数/券、流水、会话创建同事务；失败无部分提交。
- 管理员调次数、客服工单、角色/状态和审计同事务。
- 两个并发相同 `Idempotency-Key` 只产生一个会话/AI Job/余额副作用。
- 单个用户最多在 24 小时内新建 10 份材料、30 天内新建 30 份材料，并同时保留 20 份 `ready` 材料。
- 材料保留 30 天后擦除内容并标记删除；标记删除满 7 天且无会话或任务引用时才物理删除。
- 用户删除会话时先进入 `deleting`；中途失败后，留存任务能继续完成内容擦除。
- `completed`、`cancelled` 或 `deleted` 会话结束满 90 天后擦除问答、报告和任务输入输出。

## C. 认证与恶意请求

- Bearer 与 HttpOnly Cookie均可认证；Cookie 写操作缺 CSRF、错 CSRF 均为 403。
- 登录后轮换 session；密码重置/修改撤销旧 session；禁用用户立即失效。
- 注册、登录和验证码按已实现的账号或 IP 维度限流；超限返回稳定的 HTTP 429 错误码。
- 匿名邮箱验证码、普通密码登录和管理员登录缺少、复用、过期或站点不匹配的 hCaptcha token 时拒绝；限流先于外部校验，hCaptcha 校验先于账号查询、密码计算、邮件发送和登录事务。
- hCaptcha 配置接口只公开启用状态与站点标识；服务端 Secret 不进入前端 bundle、错误响应、日志、镜像或 Git。Siteverify 超时、重定向、过大/畸形 JSON 和非成功响应均失败关闭。
- 关闭注册时，已知与未知邮箱的无效验证码路径返回相同错误；未知密码账号仍执行固定 Argon2 占位校验。
- 生产使用短密钥、非 Secure Cookie、开发验证码暴露或默认数据库密码时拒绝启动。
- JSON 超长、未知枚举、路径越权、重复参数、超大请求体、错误 MIME/魔数均得到稳定 4xx。
- PDF/DOCX 超页数、超解压体积和解析超时被拒绝；真实高压缩 PDF 只能使 256 MiB Parser 被拒绝或重启，API 容器不重启且 Parser 恢复后可继续解析正常文件。
- 错误响应不含 SQL、堆栈、Provider 原始密钥或内部连接串。

## D. AI Worker

- API 业务事务提交后返回 202；Rabbit 消息只包含 Job ID/追踪字段。
- Outbox 发布失败可恢复；publisher confirm 前不标记成功。
- 重复、乱序、过期和会话删除后的消息不会重复推进或写回。
- Provider 超时进入受控重试；不可重试业务错误持久化到任务表并 ACK；畸形消息或超过投递上限的基础设施毒消息进入 DLQ。
- Worker 数据提交后才 ACK；进程在调用前、调用中、提交后被终止的三种场景都可恢复。
- AI 输出经过严格字段、类型、重复键、尾随内容和长度校验；材料或回答中的角色切换、提示词泄露、工具调用和伪造定界符不能改变系统策略。
- 工作、复试、考公和 IELTS 的阶段与题型有差异且不会重复已有问题；IELTS 的首题、追问、阶段名、反馈和最终报告全部为英文。
- 生产 Worker 缺少 DeepSeek key 时拒绝启动，不允许带残缺配置接收任务。

## E. 前端

- 回答提交后展示 queued/running/retrying/succeeded/failed/cancelled，不提前宣称报告完成。
- 刷新后能从 active session 与 active task 恢复；断网和页面隐藏时暂停轮询，恢复联网或可见后立即同步，旧 version 不覆盖新状态。
- 创建或回答已经到达服务端但响应丢失时，客户端只读对账会话状态，不重放答案正文或产生第二次扣费；ASR 断线只有存在部分转写时才允许收尾。
- ASR 连接 15 秒未启动、启动后 20 秒静默或超过绝对墙钟上限会释放全局容量，并允许同一轮重新连接。
- 重试按钮只在允许时出现且有提交锁；双击不会产生重复任务。
- 管理首页不会在登录后一次请求全部管理接口；任务等列表按页读取，日志接口只返回后端设置的有界最近记录。
- 普通登录、匿名邮箱验证码和管理员登录均能按需加载 hCaptcha；挑战失败、切换登录方式、修改邮箱或提交失败后不会复用旧 token，已登录账号设置流程不被误拦截。
- 320、768、1024、1440 px 下无关键操作溢出；键盘可完成登录、面试与管理核心流程。
- 状态消息、录音进度、错误和完成提示具备正确 aria 语义；reduced-motion 生效。
- 路由 chunk 失败有 Error Boundary 与恢复入口。

## F. 性能与容量

- 记录 Java API/Worker RSS、GC、Hikari连接、Rabbit积压、PostgreSQL连接与慢查询基线。
- API/Worker/Parser 容器总 RSS 在配置上限内，无持续 swap；稳定容器物理内存硬上限合计 3392 MiB，内存与 Swap 合计上限为 4480 MiB，并持续观察宿主机 PSI 与 swap-in/out。
- Worker并发 1 起步；压力下 API 不因 LLM 慢调用耗尽 Tomcat线程或数据库连接。
- 首页不加载 ECharts；管理分区未打开时不请求相关接口。
- 关键 JS/CSS/image chunk 有体积预算，构建产物与重构前基线对比。

## G. 生产发布

- 先在本地完成源码检查，再提交并推送 `sakuracianna`；GitHub CI 通过后才能连接生产环境，本机不运行 Docker。
- SSH 主机指纹、用户、项目目录、当前分支、Compose project、目标卷逐项只读核验。
- 本次按明确授权清理 `mianba` 项目容器、项目镜像、网络和数据卷；服务器 `.env`、secret 与证书目录必须保留，删除前再次核对精确名称。
- hCaptcha 站点标识与服务端 Secret 只写入服务器 0700 私有目录内的 0444 ConfigTree 文件；发布后确认生产启用且浏览器 CSP、挑战和 Siteverify 闭环正常，不在终端输出实际值。
- 生产按 PostgreSQL/Redis/Rabbit -> migration -> Material Parser -> API + Frontend -> Worker -> Nginx 顺序启动。
- health/readiness、注册登录、创建面试、任务消费、报告、管理后台核心 smoke 通过。
- 观察 CPU、内存、磁盘、错误率和队列后再结束维护窗口；记录回滚镜像/提交。
- runtime release 与最终镜像不包含源码测试、test/spec 文件、依赖缓存或 Git 元数据。

## H. 2026-07-15 本地证据

- `scripts/check-all.ps1` 已通过：15 个生产 Compose 字段在根 `.env`、根模板与部署模板中名称和顺序一致，且不存在本地地址变体；Java 157 个单元测试全部成功并完成 Boot JAR 重打包；前端 19 个测试文件、49 个测试全部成功，`npm audit` 为 0 个漏洞，`typecheck` 与 Vite 生产构建成功。真实 pgvector/PG18 + Flyway 的 `RETRYING → QUEUED` 约束测试已经接入 CI `integration` profile，本机按约定不启动 PostgreSQL，因此其结果必须等待精确提交的 GitHub CI。
- Windows PowerShell 5.1 干净临时目录已完成 Maven Wrapper 首次引导：从固定 Maven Central HTTPS 地址下载 Maven 3.9.11，使用仓库固定 SHA-512 校验后解压，并由 Java 21 成功执行 `mvnw.cmd --version`；临时目录随后按规范化路径校验清理。
- GitHub Actions YAML 已完成解析；部署 Shell 通过 `bash -n`，`production-compose-contract.sh`、`production-release-contract.sh` 与 `rollback-compensation-contract.sh` 全部通过。契约覆盖服务器 0600 `compose.env` 精确字段、父进程变量污染隔离、受控 project、精确六镜像闭包、严格健康 JSON、危险命令拒绝、越界指针拒绝、首次管理员事务锁，以及回滚指针提交失败后先恢复候选 API/边缘 readiness 再补偿原指针的故障注入。
- 生产 Java 共 120 个文件、12254 行，其中注释 870 行（7.10%）、Javadoc 326 处，全部文件至少包含一处 Javadoc；`<p>`/`</p>`、`@author`、`@since`、`TODO`、`FIXME` 为 0，旧 Python 文件与生产调试打印为 0。
- 最终 JAR 中测试条目为 0，独立 `MaterialParserMain` 主类为 1；前端 `dist` 中 test/spec 名称为 0。
- 仓库敏感信息扫描未发现本次提供的 hCaptcha 凭据、私钥头、常见 Token 前缀。生产凭据仍须通过服务器受保护文件注入，不能根据本地扫描推断线上已配置。
- 本节只证明本地门禁，不证明 GitHub CI、生产部署、真实 hCaptcha、Provider、邮件、ASR/TTS、资源观察、异机备份或恢复演练已通过；这些项目必须在提交、推送与维护窗口中继续留证。
