# 生产部署 Runbook

## 1. 范围与硬边界

本手册仅适用于服务器 `118.195.193.66` 上的面霸练习生生产项目。生产采用手动部署，仓库、Secret 文件、证书、Compose 配置和运行中的容器全部维护在服务器上的同一个目录：

```text
/home/ubuntu/InterviewAceTrainee/
  .git/                  项目 Git 仓库, 分支跟随 sakuracianna
  Backend/ Frontend/     应用源码
  docker/                PostgreSQL/Redis/RabbitMQ 配置镜像
  deploy/                CI 拓扑与必要运维脚本
  data/                  PostgreSQL/Redis/RabbitMQ 本机持久化数据
  docker-compose.yml     生产 Compose 定义
  .env                   非敏感生产配置(17 个字段, 与根目录 .env.example 同 schema)
  secrets/               0700 目录, 内含 0444 只读容器 Secret
  nginx/                 Nginx 配置与 TLS 证书(bundle.pem + .key)
```

必须遵守:

- 生产服务器不使用 GitHub Actions 或任何自动化流水线推送部署；`.github/workflows/ci.yml` 只在 GitHub Runner 上跑测试和构建校验，不接触服务器。
- 发布前必须确认目标 commit 已在 `ci.yml` 跑绿。
- 服务器上的 `.env`、`secrets/`、`data/`、`nginx/` 下的证书私钥属于常驻资产，任何操作都不得覆盖、移动或输出其内容。
- 禁止 `docker compose down -v`、`docker volume prune`、未核验对象的全局 prune、通配符删除或循环删除卷。
- 身份、路径或卷不明确时停止操作，先只读核验。

## 2. 发布前只读核验

```bash
cd /home/ubuntu/InterviewAceTrainee
git status
git log -1 --oneline
docker compose ps
docker ps --all --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
test -d data/postgres && test -d data/redis && test -d data/rabbitmq
df -h /
```

确认工作树干净（没有本地未提交改动）、当前 commit、容器现状和磁盘空间后才继续。

## 3. 手动发布流程

发布前确认目标 commit 已通过 `ci.yml`。以下步骤在服务器上按顺序执行，每一步都等健康检查通过再进行下一步：

```bash
cd /home/ubuntu/InterviewAceTrainee
export DEPLOY_SHA='<目标40位commit SHA>'

# 1. 更新代码到目标 commit
git fetch origin sakuracianna
git checkout "$DEPLOY_SHA"

# 2. 构建应用镜像(打上 commit SHA 标签)
docker build --tag "mianba-java:$DEPLOY_SHA" Backend
docker build --tag "mianba-frontend:$DEPLOY_SHA" Frontend

# 3. 手动更新 .env 中发生变化的镜像字段, 不要用脚本覆盖整份 .env
#    MIANBA_APP_IMAGE=mianba-java:$DEPLOY_SHA
#    MIANBA_FRONTEND_IMAGE=mianba-frontend:$DEPLOY_SHA
#    MIANBA_POSTGRES_IMAGE=mianba-postgres:$DEPLOY_SHA
#    MIANBA_REDIS_IMAGE=mianba-redis:$DEPLOY_SHA
#    MIANBA_RABBITMQ_IMAGE=mianba-rabbitmq:$DEPLOY_SHA
docker compose config --quiet

# 4. 基础设施配置有变化时, 在新标签生效后构建并核验对应镜像
docker compose build postgres redis rabbitmq
docker image inspect \
  "mianba-postgres:$DEPLOY_SHA" \
  "mianba-redis:$DEPLOY_SHA" \
  "mianba-rabbitmq:$DEPLOY_SHA" >/dev/null

# 5. 如有新的 Flyway 迁移, 先执行一次性迁移
docker compose run --rm --no-deps migrate

# 6. 逐个更新应用服务, 每步都看健康检查再继续
docker compose up -d --no-build --no-deps --pull never material-parser
docker compose up -d --no-build --no-deps --pull never api
docker compose up -d --no-build --no-deps --pull never frontend
docker compose up -d --no-build --no-deps --pull never worker
docker compose up -d --no-build --no-deps --pull never nginx
```

**关键提醒**：`docker compose stop/rm` 按服务名操作时，会作用于该服务名下**当前实际运行的容器**，与它是不是"刚创建的候选"无关。执行任何 `stop`/`rm`/`down` 前，先用 `docker compose ps <service>` 确认目标就是你想操作的那个容器，不要假设它一定是本次发布产生的新容器。

PostgreSQL、Redis 或 RabbitMQ 的镜像/数据挂载发生变化时，不使用上面的常规应用发布顺序；进入维护窗口并遵循 [named volume 到 data 目录迁移手册](named-volumes-to-data-migration.md)。

## 4. 发布后验证

```bash
cd /home/ubuntu/InterviewAceTrainee
docker compose ps
docker stats --no-stream
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping
docker compose exec -T api curl --fail --silent --show-error http://127.0.0.1:8000/actuator/health/readiness >/dev/null
docker compose exec -T material-parser curl --fail --silent --show-error http://127.0.0.1:8090/healthz >/dev/null
docker compose exec -T nginx nginx -t
```

外部验证:

```bash
curl --fail --silent --show-error --max-time 10 https://sakuracianna.icu/api/health >/dev/null
curl --fail --silent --show-error --max-time 10 https://sakuracianna.icu/api/health/readiness >/dev/null
curl --fail --silent --show-error --head --max-time 10 https://sakuracianna.icu/
```

人工验证参照 README「首次管理员」及相关业务流程，确认注册/登录、面试创建、语音识别与合成、异步任务状态机、管理端查询等核心路径正常。

## 5. 回滚

生产没有自动化的多版本 release 目录。存储已固定在项目 `data/` 后，普通应用回滚只切换 Java/前端镜像，不得 checkout 到使用 named volume 的旧 Compose：

```bash
cd /home/ubuntu/InterviewAceTrainee
export PREVIOUS_SHA='<出问题之前的commit SHA>'

# 确认旧应用镜像还在(未被 docker image prune 清理)
docker image inspect "mianba-java:$PREVIOUS_SHA" "mianba-frontend:$PREVIOUS_SHA"

# 保持当前兼容 data/ 的 Compose, 手动把两个应用镜像字段改回旧 SHA
# MIANBA_APP_IMAGE=mianba-java:$PREVIOUS_SHA
# MIANBA_FRONTEND_IMAGE=mianba-frontend:$PREVIOUS_SHA
# 精确确认 .env 和 Compose 解析结果都只引用旧 SHA 后再继续
grep -Fx "MIANBA_APP_IMAGE=mianba-java:$PREVIOUS_SHA" .env
grep -Fx "MIANBA_FRONTEND_IMAGE=mianba-frontend:$PREVIOUS_SHA" .env
docker compose config --quiet
docker compose config --images | grep -Fx "mianba-java:$PREVIOUS_SHA"
docker compose config --images | grep -Fx "mianba-frontend:$PREVIOUS_SHA"

# 逐个恢复服务, 每步都等待健康检查通过后再继续
docker compose up -d --no-build --no-deps --pull never material-parser
docker compose up -d --no-build --no-deps --pull never api
docker compose up -d --no-build --no-deps --pull never frontend
docker compose up -d --no-build --no-deps --pull never worker
docker compose up -d --no-build --no-deps --pull never nginx
```

如果新版本引入了不兼容的 Flyway 变更，不做应用层盲回滚；所有 Flyway 变更必须保持至少一个发布周期的向后兼容性，出现不兼容时保持维护状态，由负责人决定前向修复方案。基础设施镜像或存储布局回滚必须单独进入维护窗口，不能套用本节应用镜像回滚。

## 6. Secret 与证书维护

`secrets/`（0700，内含 0444 文件）和 `nginx/` 下的证书文件由运维人员直接在服务器上维护，不通过任何脚本自动生成或分发到其他目录：

- 数据库、Redis、RabbitMQ、JWT、`content_safety_hmac_secret`、`material-parser-token`：随机密钥，遗失可用 `openssl rand -hex 48` 重新生成对应文件（会导致依赖它的账号/会话失效，需评估影响后操作）。
- `deepseek_api_key`、`resend_api_key`、`mail_from`、`tencent_app_id`、`tencent_secret_id`、`tencent_secret_key`、`hcaptcha-site-key`、`hcaptcha-secret`：外部凭据，只能由项目所有者手动写入，不生成占位值。
- 证书轮换：把新的 `bundle.pem`/`.key` **原地覆盖**（用同一 inode，例如 `cat 新文件 > 旧文件路径`，不要用 `mv` 替换整个文件），再执行下面的重启（不能只 `nginx -s reload`，Docker 对单文件 bind mount 的引用在容器启动时就固定了，替换宿主机文件不会让已运行容器看到新内容）：

```bash
docker compose restart nginx
```

替换/重启后必须用外部 `openssl s_client` 或浏览器确认新证书已经在服务，不能只看宿主机文件时间戳。

## 7. 首次管理员提升

同 README「首次管理员」章节：先通过正式页面注册管理员候选账号，确认邮箱归属后创建一次性 0600 邮箱文件，执行 `deploy/promote-first-admin.sh`，验证成功后删除该文件。

## 8. 常见操作命令

```bash
cd /home/ubuntu/InterviewAceTrainee
docker compose ps
docker compose logs -f --tail 200 api
docker compose exec -T api sh
```

日常运维只使用 `ps`、`logs`、`exec`、`top`、`stats` 等观察类命令；任何状态变更（`up`、`down`、`rm`、`restart`）都按本手册第 3/5/6 节的顺序和前置检查执行，不手工拼接跳过健康检查。

## 9. 4 核 4 GB 观察门槛

发布后至少观察一个完整 AI 任务周期，重点检查:

- API/Worker/Parser RSS 持续低于各自容器限额 85%，无 OOMKilled 或非预期重启增长。
- PostgreSQL 连接低于 32，慢 SQL 与锁等待不持续增长。
- RabbitMQ 主队列不持续堆积，DLQ 无异常增长，只有预期 Worker 消费者。
- Redis 低于 160 MiB，AOF 正常，无 `noeviction` 写入失败。
- API 5xx、登录限流、Provider 超时和任务失败率处于可解释范围。
- `/proc/pressure/memory` 的 `full` 压力不持续增长，`vmstat` 的 `si/so` 不持续非零；约 1.9 GiB Swap 只吸收短峰值，不用于扩大 JVM 堆或长期超配。
- PostgreSQL 已从 `PUBLIC` 撤销默认 CONNECT 与 `public` schema 权限，仅 owner、`mianba_api` 和 `mianba_worker` 按角色获得连接/对象权限。

生产数据按以下规则执行有界批处理，排查磁盘增长或隐私删除问题时必须同时核对状态与引用关系：

- 每个用户 24 小时内最多新建 10 份材料，30 天内最多新建 30 份材料，同时最多保留 20 份 `ready` 材料
- 材料创建 30 天后擦除内容并标记删除；标记删除满 7 天且没有会话或任务引用后才物理删除
- 用户删除面试时，会话先进入 `deleting`；中断的擦除流程由每小时留存任务恢复，最终状态为 `deleted`
- `completed`、`cancelled` 或 `deleted` 会话结束满 90 天后擦除问答、报告和 AI 任务输入输出

只有 health、readiness、核心 smoke、资源和错误率都稳定，才能结束维护窗口。

## 10. 商业化开放前的备份与告警门槛

新 Java/Flyway 数据一旦产生就适用默认保护策略。正式扩大用户范围前必须完成以下门槛：

- PostgreSQL 是用户、次数账本、任务、审计和人工退款工单的事实来源；至少每日生成一次加密、异机保存的逻辑备份，并设置可验证的保留与删除周期。服务器本机卷、同盘目录或 Docker volume 快照不能单独称为备份。
- 使用与生产隔离的数据库按固定周期完成恢复演练，记录备份 SHA-256、恢复耗时、Flyway 版本和关键表行数抽查。未实际恢复过的备份不能作为上线保障。
- Redis 是缓存/限流/临时状态，RabbitMQ 是投递通道；二者不能替代 PostgreSQL outbox、任务表和余额流水。恢复流程应允许清空缓存并从数据库状态重新驱动未完成任务。
- 对磁盘剩余空间、容器 OOM/重启、API 5xx、Provider 超时、RabbitMQ 主队列与 DLQ、Worker 心跳、PostgreSQL 连接/慢查询、Redis 内存和证书有效期建立告警。证书至少在 30、14、7 天阈值告警。
- 每次发布前执行只读证书检查，不能在输出中打印私钥或 Secret：

```bash
openssl x509 -in /home/ubuntu/InterviewAceTrainee/nginx/sakuracianna.icu_bundle.pem -noout -checkend 2592000
openssl x509 -in /home/ubuntu/InterviewAceTrainee/nginx/sakuracianna.icu_bundle.pem -noout -enddate
```

仓库当前没有获批准的异机备份目的地或告警接收端，因此本手册不会伪造一个"已备份"状态。配置对象存储/备份主机、加密密钥与告警渠道属于首次商业化开放前的独立运维任务，完成后必须补充恢复演练记录。
