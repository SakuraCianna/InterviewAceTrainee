# 生产重建与发布 Runbook

## 1. 范围与硬边界

本手册仅适用于服务器 `118.195.193.66` 上的面霸练习生生产项目。当前项目所有者已明确授权在本次 Python 到 Spring Boot 4/Flyway 重构中丢弃旧 PostgreSQL、Redis 和 RabbitMQ 数据；该授权不延伸到以后的升级。

必须遵守:

- 先核验主机、部署根目录、Compose project、容器和每一个目标卷，再执行删除。
- 禁止 `docker compose down -v`、`docker volume prune`、未核验对象的全局 prune、通配符删除或循环删除卷。
- 生产服务器不保存 Git 仓库、Java/TypeScript/应用 Python 源码、`src/test`、测试文件、依赖缓存或构建工具。Ubuntu 系统自带的 `python3` 仅用于发布脚本严格解析健康 JSON，不承载业务代码。
- 服务器不运行 Maven、npm、测试、`docker build` 或 `docker compose build`。
- 旧部署的 `.env`、所有密钥文件和证书文件属于保留资产；新部署密钥只存在于 `shared/secrets`，证书只存在于 `shared/certs`，任何核验都不得输出内容。
- 身份、路径、卷或包哈希任一项不明确时停止操作。

服务器发布目录约定:

```text
<PRODUCTION_PATH>/
  .incoming/<SHA>-<RUN>-<ATTEMPT>/  本次 CI 唯一临时上传包
  releases/<SHA>/        不可变 runtime allowlist 文件
  current -> releases/<SHA>
  previous -> releases/<SHA>       上一个可恢复版本
  shared/compose.env     0600 服务器非敏感生产配置事实来源
  shared/secrets/        0700 私有目录内的 0444 容器只读 Secret
  shared/certs/          HTTPS 证书
  shared/runtime-config/ 稳定数据容器使用的只读初始化与配置文件
  shared/legacy/         仅保存退役部署的两个 0600 `.env`，不挂载到容器
```

旧仓库目录若仍保存 `.env`，清理 Docker 时不得删除、移动或覆盖该文件。迁移到新发布根目录前只检查文件类型、权限和校验和；传递实际 Secret 时使用不记录命令历史的批准通道。

`PRODUCTION_PATH` 必须是已规范化的绝对路径，至少包含两级安全字符组件，例如 `/srv/mianba`。工作流和服务器脚本都会拒绝空值、根目录、`.`/`..`、空格、符号链接目录或已存在的同 SHA release。

激活和回滚会在取得部署锁或改变容器前检查宿主机工具。Ubuntu 主机必须同时满足以下前置条件；缺少任一工具时发布失败关闭，不允许退回字符串匹配 JSON:

```bash
command -v curl >/dev/null
command -v python3 >/dev/null
python3 --version
```

## 2. CI 发布物

生产只接受 `.github/workflows/deploy-production.yml` 从 `sakuracianna` 完整 40 位 commit SHA 构建的发布物:

- `mianba-images-<SHA>.tar` 与 `.sha256`，包含 Java、前端及四个固定 digest 第三方镜像对应的 commit 专属本地标签
- `mianba-runtime-<SHA>.tar.gz` 与 `.sha256`
- `activate-release.sh` / `rollback-release.sh` / `validate-compose-env.sh` 与 `deployment-scripts.sha256`

Runtime 包只允许包含 Compose、Nginx、部署脚本和 `commit.txt`。工作流在 runner 和服务器上都拒绝名称含 `test` 或 `spec` 的文件；Java 与前端最终镜像也通过 `.dockerignore` 和多阶段构建排除测试、源码与构建缓存。同一 SHA 的 `releases/<SHA>` 一旦存在就不会被覆盖；需要重发时应使用新 commit，或在人工审核后精确处理未激活的失败 release。

`prepare` 只生成候选 artifact；`deploy` 必须经过 GitHub Environment `production` 审批。发布工作流先确认精确 SHA 已在 `sakuracianna` 的完整 CI 成功，不能用历史分支包含关系替代 Compose/业务烟测结果。GitHub Secrets 至少包括固定主机、最小权限用户、SSH 私钥、固定 known_hosts、发布根目录和可选健康 URL。

## 3. 首次重建前的只读核验

以下命令在生产服务器的 Bash 中执行，命令只读取状态。`PRODUCTION_PATH` 和 `PROJECT` 必须由操作者从批准的资产记录填写:

```bash
export PRODUCTION_PATH='/已复核的绝对发布根目录'
export PROJECT='mianba'

id
hostname
pwd
docker version
docker compose version
docker compose ls
docker ps --all --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker network ls --format 'table {{.Name}}\t{{.Driver}}\t{{.Labels}}'
docker volume ls --format 'table {{.Name}}\t{{.Driver}}\t{{.Labels}}'
docker image ls --no-trunc --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}'
docker system df
df -h / /var/lib/docker
```

必须确认主机地址为已授权服务器，发布根目录不是空值或 `/`，且当前身份是批准的部署身份。若旧项目存在，读取其 Compose project 标签:

```bash
docker volume ls \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --format 'table {{.Name}}\t{{.Labels}}'
docker ps --all \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --format 'table {{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Mounts}}'
docker network ls \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --format 'table {{.ID}}\t{{.Name}}\t{{.Labels}}'
```

卷名可能来自旧 Compose 的下划线形式，也可能是新架构固定名称，必须以标签和挂载关系为准，不能仅凭名称推断。新架构名称如下:

```bash
docker volume inspect mianba-postgres-data
docker volume inspect mianba-redis-data
docker volume inspect mianba-rabbitmq-data
```

不存在的卷会返回错误，记录为“无需删除”。存在的卷必须满足以下条件才能列入本次清理:

- Compose project/volume 标签或挂载容器能证明属于面霸练习生。
- `docker ps --all --filter volume=<精确卷名>` 只显示已核验的旧项目容器。
- 操作人与复核人共同确认项目所有者本次数据清理授权。

删除 Docker 对象前还要确认保留资产存在且不是符号链接。只输出路径、类型、权限和校验和，不读取内容:

```bash
for env_file in \
  /home/ubuntu/InterviewAceTrainee/Backend/.env \
  /home/ubuntu/InterviewAceTrainee/Frontend/.env; do
  test -f "$env_file"
  test ! -L "$env_file"
  test "$(realpath -- "$env_file")" = "$env_file"
  test "$(stat -c '%a' "$env_file")" = '600'
  stat -c '%n %a %U:%G %s' "$env_file"
  sha256sum "$env_file"
done
find "$PRODUCTION_PATH/shared/secrets" -maxdepth 1 -type f -printf '%f %m\n' | sort
find "$PRODUCTION_PATH/shared/certs" -maxdepth 1 -type f -printf '%f %m\n' | sort
```

若新发布根目录尚不存在，只核验旧 `.env`、旧密钥和证书的实际目录并记录位置；不得为了继续清理而假定这些资产可以重建。

## 4. 精确清理旧 Docker 项目

本次授权覆盖旧面霸项目的服务容器、项目网络、项目镜像和业务数据卷，但不覆盖 `.env`、密钥、证书或主机上的其他项目。先记录旧项目使用的镜像和挂载，再停止 Compose；命令不携带 `-v` 或 `--rmi`:

```bash
export OLD_PROJECT_PATH='/home/ubuntu/InterviewAceTrainee'
test -d "$OLD_PROJECT_PATH"
test -f "$OLD_PROJECT_PATH/docker-compose.yml"
cd "$OLD_PROJECT_PATH"
docker compose -p "$PROJECT" ps --all
docker compose -p "$PROJECT" config --images
docker ps --all \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --format '{{.ID}} {{.Names}} {{.Image}} {{.Mounts}}'
docker compose -p "$PROJECT" down --remove-orphans
```

停止后必须确认项目容器和项目网络已经清空；若仍有对象，先逐个 `docker inspect` 核验标签，再用精确 ID 删除:

```bash
docker ps --all \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --format 'table {{.ID}}\t{{.Names}}\t{{.Image}}'
docker network ls \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --format 'table {{.ID}}\t{{.Name}}'
```

数据卷必须按只读盘点得到的实际名称分开操作。旧版可能使用 `mianba_postgres-data`、`mianba_redis-data` 等下划线名称，新版使用连字符名称；以下占位符必须替换成已核验的单个精确卷名，不能复制占位符直接执行，也不能改成循环:

```bash
docker volume inspect <APPROVED_POSTGRES_VOLUME>
docker ps --all --filter volume=<APPROVED_POSTGRES_VOLUME> --format '{{.ID}} {{.Names}}'
docker volume rm <APPROVED_POSTGRES_VOLUME>
```

```bash
docker volume inspect <APPROVED_REDIS_VOLUME>
docker ps --all --filter volume=<APPROVED_REDIS_VOLUME> --format '{{.ID}} {{.Names}}'
docker volume rm <APPROVED_REDIS_VOLUME>
```

```bash
docker volume inspect <APPROVED_RABBITMQ_VOLUME>
docker ps --all --filter volume=<APPROVED_RABBITMQ_VOLUME> --format '{{.ID}} {{.Names}}'
docker volume rm <APPROVED_RABBITMQ_VOLUME>
```

项目镜像也必须依据停止前记录逐个处理。先确认没有容器引用，再按精确镜像 ID 删除；同一镜像若被其他项目引用则保留:

```bash
docker image inspect <APPROVED_OLD_IMAGE_ID>
docker ps --all --filter ancestor=<APPROVED_OLD_IMAGE_ID> --format '{{.ID}} {{.Names}}'
docker image rm <APPROVED_OLD_IMAGE_ID>
```

只有当完整盘点证明 Docker 主机没有任何其他工作负载，且本次维护记录明确包含构建缓存时，才能单独执行 `docker builder prune --all --force`。无论何种情况都不执行 `docker system prune` 或 `docker volume prune`。清理结束后再次运行 `docker ps --all`、`docker volume ls`、`docker network ls`、`docker image ls` 和 `docker system df`，并复核 `.env`、密钥和证书仍在原位。

本次按授权不恢复旧业务数据。后续任何清理必须恢复为“先备份并完成恢复演练”的默认策略。

## 5. 服务器 Compose 配置、Secret 与证书

`deploy/compose.env.example` 与根目录 `.env.example` 是完全同 schema 的模板，本机 `.env` 只用于静态校验且不会被生产脚本读取。将已审核的 19 字段配置安装到服务器稳定路径；不得在 Shell 中 `source` 该文件，不得通过终端输出内容：

```bash
install -m 0600 <APPROVED_COMPOSE_ENV> "$PRODUCTION_PATH/shared/compose.env"
test -f "$PRODUCTION_PATH/shared/compose.env"
test ! -L "$PRODUCTION_PATH/shared/compose.env"
test "$(stat -c '%a' "$PRODUCTION_PATH/shared/compose.env")" = '600'
```

19 个字段必须按模板顺序全部存在且非空，每项紧邻包含中文的注释，注释使用英文标点。校验器拒绝 CRLF、引号、变量展开、命令替换、管道和其他命令语法，且只输出字段名或行号错误。六个镜像和三个共享目录字段仍保留在 schema 中，激活脚本会用已校验 SHA 和 `PRODUCTION_PATH` 派生值覆盖它们；其余 10 个资源与 CORS 字段只从该服务器文件读取。

候选 runtime 就位后可执行不输出值的独立校验；自动激活和回滚也会在每次 Compose 调用前重复校验：

```bash
export DEPLOY_SHA='<已校验的40位SHA>'
bash "$PRODUCTION_PATH/releases/$DEPLOY_SHA/deploy/validate-compose-env.sh" \
  "$PRODUCTION_PATH/shared/compose.env"
```

先创建目录并限制权限，不在命令行参数中传递 secret 值:

```bash
install -d -m 0700 "$PRODUCTION_PATH/shared/secrets"
install -d -m 0700 "$PRODUCTION_PATH/shared/certs"
```

由项目所有者通过批准渠道写入以下外部凭据文件，文件末尾允许单个换行:

```text
deepseek_api_key
resend_api_key
mail_from
tencent_app_id
tencent_secret_id
tencent_secret_key
hcaptcha-site-key
hcaptcha-secret
```

`hcaptcha-site-key` 是浏览器可见的站点标识，但仍通过运行时配置接口提供，避免把生产值固化进前端镜像；`hcaptcha-secret` 只供 API 调用 Siteverify。两个文件均按 Secret 目录策略使用 0444，实际内容不得写入仓库、发布包、命令参数、终端输出或日志。生产 API 会在任一文件缺失、为空或验证地址不是官方地址时拒绝启动。

证书目录必须包含:

```text
sakuracianna.icu_bundle.pem
sakuracianna.icu.key
```

证书私钥必须是普通文件且使用 0600（发布脚本也接受预先设置的只读 0400），bundle 可使用 0644:

```bash
chmod 0644 "$PRODUCTION_PATH/shared/certs/sakuracianna.icu_bundle.pem"
chmod 0600 "$PRODUCTION_PATH/shared/certs/sakuracianna.icu.key"
```

Runtime 包中的 `deploy/prepare-server-secrets.sh` 只在文件缺失时生成数据库、Redis、RabbitMQ、JWT 和 `material-parser-token` 随机密钥，不覆盖已有 Secret，也不打印值。DeepSeek、邮件、腾讯云和两个 hCaptcha 文件必须由项目所有者预先写入，脚本不会生成占位生产凭据。普通 Compose 的文件型 Secret 保留宿主机 UID/GID/Mode，因此脚本将 0700 私有目录内的容器运行文件统一设为只读 0444，使 PostgreSQL、Redis、RabbitMQ 和 UID 10001 的 Java 进程均可读取；宿主机其他用户无法穿越父目录。`rabbitmq-definitions.json` 是从现有 RabbitMQ 密码文件派生的运行配置，不是独立 Secret；脚本按 RabbitMQ salted SHA-256 格式生成密码哈希，并可在每次执行时刷新该文件。刷新过程先在 `shared/secrets` 同目录创建 0600 临时文件，完整写入后改为 0444 再原子重命名；任何步骤失败都保留旧 definitions，原始密码不会进入命令参数或容器元数据。

首次部署时旧 `current` 可能不存在，因此禁止从旧 `current` 猜测脚本路径。自动 `deploy` 作业会先校验并解压候选 runtime 到 `releases/<SHA>`，再从该候选目录执行脚本。批准的离线部署也必须使用已校验候选目录:

```bash
export MIANBA_SECRETS_DIR="$PRODUCTION_PATH/shared/secrets"
export DEPLOY_SHA='<已校验的40位SHA>'
test "$(cat "$PRODUCTION_PATH/releases/$DEPLOY_SHA/commit.txt")" = "$DEPLOY_SHA"
sh "$PRODUCTION_PATH/releases/$DEPLOY_SHA/deploy/prepare-server-secrets.sh"
```

完成后只检查文件存在、类型和权限:

```bash
find "$PRODUCTION_PATH/shared/secrets" -maxdepth 1 -type f -printf '%f %m\n' | sort
find "$PRODUCTION_PATH/shared/certs" -maxdepth 1 -type f -printf '%f %m\n' | sort
```

## 6. 自动发布激活顺序

GitHub `deploy` 作业执行以下受控步骤:

1. 验证 `PRODUCTION_HOST` 精确等于 `118.195.193.66`，固定 SSH host key，并在 runner/服务器双端校验规范化绝对路径；服务器在任何发布变更前确认宿主 `curl` 与 `python3` 可用。
2. 以 `<SHA>-<RUN_ID>-<ATTEMPT>` 创建 0700 唯一 staging；目录已存在时拒绝上传，传输失败只删除该精确 staging。
3. 在服务器校验部署脚本和两个发布包的 SHA-256。
4. 只有 `releases/<SHA>` 不存在时才创建并解压；校验 `commit.txt`、路径越界、符号链接和 test/spec，然后使用候选校验器检查 `shared/compose.env`。
5. 检查外部 Provider、hCaptcha Secret 和证书，从候选 release 补齐缺失的基础设施 Secret、原子刷新 RabbitMQ definitions，再检查完整清单。
6. 在 `docker load` 前确认 Docker 根目录可用空间至少为完整镜像包大小加 1 GiB，再加载六个 commit 专属本地标签并逐个执行 `docker image inspect`；第三方内容已在 runner 按 digest 校验，服务器不现场拉取。候选 release 先通过 `docker compose config --quiet` 和真实 `nginx -t`，此时尚未切换 `current`。
7. 保存旧 `current` 目标，并在停止任何应用容器前确认旧 release 的六个回滚镜像仍完整。使用 `--no-build --pull never --no-recreate --wait` 启动或复用 PostgreSQL、Redis、RabbitMQ，然后运行一次性 `migrate`。
8. 先启动独立 Material Parser，再启动 API 与前端，最后启动单消费者 Worker；校验 Worker 容器正在运行，并循环请求 `/api/health/readiness`。API 对 Parser `/healthz` 的匿名探测最多等待 1 秒、最多接收 16 字节，只有精确 HTTP 200 与正文 `ok` 才就绪。
9. 启动 Nginx，再执行 `nginx -t` 和宿主本机 HTTPS readiness。内部 API 与本机 edge 响应均由宿主 `python3` 严格解析，拒绝 3xx、畸形 JSON、重复键、缺字段和错误类型，并要求 `ready`、`database_ready`、`redis_ready`、`rabbit_ready`、`worker_ready`、`parser_ready` 六项均为布尔值 `true`。任一步失败都由 trap 恢复旧 release；首次发布失败则关闭对外应用服务并移除候选 Parser。
10. 只在内部就绪成功后，才在部署锁内先更新 `previous`，再以 `mv -T` 原子切换 `current`；信号或任一指针操作失败会有界补偿并复核两个指针。
11. 指针提交后才精确删除本次 staging 并清理旧 release；保留当前、上一版及最新候选，服务器最多保留 5 个规范 SHA release。被淘汰 release 只尝试删除其六个精确 SHA 镜像标签，仍被容器引用的镜像保留并告警，不执行 Docker 全局 prune。
12. runner 从外部要求 liveness/readiness 精确返回 HTTP 200 和预期 JSON，readiness 同样必须包含六项布尔 `true`；如果失败，立即调用已校验 release 内的回滚脚本恢复 `previous`。回滚到不包含 Parser 的旧 release 时会显式停止并移除候选 Parser；如果旧版恢复或指针提交失败，脚本先重新启动候选应用并通过 API/Nginx readiness，随后才补偿原 `current/previous`，避免指针指回已被删除的候选容器。

日常运维不要手工拼接镜像和目录变量。Runtime 内的受控入口会从 `current` 解析完整 SHA，只允许 `config`、`exec`、`images`、`logs`、`ps`、`stats` 和 `top` 等观察/诊断命令；`up`、`down`、`pull`、`rm` 等状态变更必须走发布或维护脚本:

```bash
export MIANBA_PRODUCTION_ROOT="$PRODUCTION_PATH"
bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" ps
```

生产发布命令必须包含 `--no-build --pull never`。PostgreSQL 初始化脚本、Redis 入口和 RabbitMQ 配置首次安装到固定 `shared/runtime-config`；后续应用发布要求候选文件与固定副本完全一致，并以 `--no-recreate` 保持数据层生命周期，基础设施配置升级必须走独立维护流程。Flyway 仅使用 `run --rm --no-deps --no-build --pull never migrate` 以 `mianba_owner` 执行；API 与 Worker 分别使用非所有者 `mianba_api`、`mianba_worker`，数据库与 RabbitMQ 权限互相隔离。Material Parser 在独立受限容器中使用同一 JAR 的轻量主类，不挂载数据库、Redis、RabbitMQ 或 Provider Secret，也不接入公网网络；8 秒硬截止触发时进程退出并由 Compose 自动恢复。

首次重建会停止并删除旧拓扑、创建新卷和运行 Flyway，因此是明确维护窗口，不是零停机发布。后续仅应用发布才复用稳定数据层；任何 PostgreSQL/Redis/RabbitMQ 镜像或配置升级仍需要单独维护窗口和回滚评估。

## 7. 发布后验证

先查看容器与资源，不输出环境变量或 secret:

```bash
export MIANBA_PRODUCTION_ROOT="$PRODUCTION_PATH"
bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" ps
docker stats --no-stream
bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" exec -T rabbitmq rabbitmq-diagnostics -q ping
bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" exec -T api curl --fail --silent --show-error http://127.0.0.1:8000/actuator/health/readiness >/dev/null
bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" exec -T api curl --fail --silent --show-error http://127.0.0.1:8000/api/health/readiness >/dev/null
bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" exec -T material-parser curl --fail --silent --show-error http://127.0.0.1:8090/healthz >/dev/null
test "$(bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" ps --services --status running worker)" = 'worker'
bash "$PRODUCTION_PATH/current/deploy/production-compose.sh" exec -T nginx nginx -t
swapon --show
cat /proc/pressure/memory
vmstat 1 5
```

从批准的外部探测点验证:

```bash
curl --fail --silent --show-error --max-time 10 https://sakuracianna.icu/api/health >/dev/null
curl --fail --silent --show-error --max-time 10 https://sakuracianna.icu/api/health/readiness >/dev/null
curl --fail --silent --show-error --head --max-time 10 https://sakuracianna.icu/
curl --fail --silent --show-error --max-time 10 \
  https://sakuracianna.icu/api/auth/hcaptcha/config \
  | grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true'
```

使用专用测试账号人工完成:

1. 匿名邮箱验证码、普通密码登录和管理员登录分别完成 hCaptcha；确认切换方式、失败重试或修改邮箱后旧 token 不会复用，已登录账号设置中的邮箱验证码不被重复挑战。
2. 注册/登录、验证码邮件、Cookie Secure、CSRF 与退出。
3. 创建面试并重复同一 `Idempotency-Key`，确认不重复扣次数。
4. 上传正常 TXT/PDF 材料，确认 Parser 正常解析且 API/Parser 重启计数不增长。
5. 完成一次实时 ASR 与 TTS，确认断线能安全结束且不保存原始音频。
6. 提交回答，确认返回 202 与任务 URL，状态按 `QUEUED -> RUNNING -> SUCCEEDED` 推进。
7. 确认 RabbitMQ 消息不含简历、回答、音频或报告正文。
8. 管理端确认任务、认证审计、用户、客服备注和人工退款工单可查询。
9. 支付与退款仍通过微信人工处理，不执行自动资金操作。

### 首次管理员提升

全新数据库先通过正式页面注册管理员候选账号并设置密码。确认邮箱归属后，在服务器创建只含该邮箱的一次性 0600 文件；不要把邮箱写进仓库或命令历史:

```bash
export MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE="$PRODUCTION_PATH/shared/secrets/bootstrap_admin_email"
export MIANBA_PRODUCTION_ROOT="$PRODUCTION_PATH"
test -s "$MIANBA_BOOTSTRAP_ADMIN_EMAIL_FILE"
sh "$PRODUCTION_PATH/current/deploy/promote-first-admin.sh"
```

脚本先取得事务级 advisory lock，将“尚无管理员”的检查与提升串行化；它只在数据库尚无管理员、目标用户存在且启用并已有密码时成功，提升会写入 `admin_audit` 并递增 `auth_version` 使旧用户会话失效。随后用管理员入口完成密码+邮箱验证码登录并检查审计；验证成功后单独删除精确文件 `bootstrap_admin_email`，不得保留可重复使用的引导材料。

### 旧服务器源码退役

首次新拓扑通过全部验证后，服务器不得继续保留旧 Git 仓库、源码和测试。真实旧部署没有仓库根 `.env`，必须把 `Backend/.env` 与 `Frontend/.env` 分别复制到新根目录的隔离归档，并逐个核对普通文件、权限与 SHA-256 完全一致；该归档不挂载到任何容器。旧证书或独立密钥若不在这两个文件中，也必须逐项迁入 `shared/certs` 或 `shared/secrets` 并完成同样校验，不能仅因新站点暂时健康就删除。

```bash
export OLD_PROJECT_PATH='/home/ubuntu/InterviewAceTrainee'
export LEGACY_ARCHIVE_DIR="$PRODUCTION_PATH/shared/legacy"
export OLD_BACKEND_ENV="$OLD_PROJECT_PATH/Backend/.env"
export OLD_FRONTEND_ENV="$OLD_PROJECT_PATH/Frontend/.env"
export PRESERVED_BACKEND_ENV="$LEGACY_ARCHIVE_DIR/InterviewAceTrainee.Backend.env"
export PRESERVED_FRONTEND_ENV="$LEGACY_ARCHIVE_DIR/InterviewAceTrainee.Frontend.env"
test "$(realpath -m -- "$OLD_PROJECT_PATH")" = '/home/ubuntu/InterviewAceTrainee'
test -d "$OLD_PROJECT_PATH"
test ! -L "$OLD_PROJECT_PATH"
for source_env in "$OLD_BACKEND_ENV" "$OLD_FRONTEND_ENV"; do
  test -f "$source_env"
  test ! -L "$source_env"
  test "$(realpath -- "$source_env")" = "$source_env"
  test "$(stat -c '%a' "$source_env")" = '600'
done
test -d "$PRODUCTION_PATH/shared"
test ! -L "$PRODUCTION_PATH/shared"
test "$(realpath -m -- "$PRODUCTION_PATH/shared")" = "$PRODUCTION_PATH/shared"
if test -e "$LEGACY_ARCHIVE_DIR" || test -L "$LEGACY_ARCHIVE_DIR"; then
  test -d "$LEGACY_ARCHIVE_DIR"
  test ! -L "$LEGACY_ARCHIVE_DIR"
else
  install -d -m 0700 -- "$LEGACY_ARCHIVE_DIR"
fi
test "$(realpath -m -- "$LEGACY_ARCHIVE_DIR")" = "$LEGACY_ARCHIVE_DIR"
test "$(stat -c '%a' "$LEGACY_ARCHIVE_DIR")" = '700'
source_envs=("$OLD_BACKEND_ENV" "$OLD_FRONTEND_ENV")
preserved_envs=("$PRESERVED_BACKEND_ENV" "$PRESERVED_FRONTEND_ENV")
for index in "${!source_envs[@]}"; do
  source_env="${source_envs[$index]}"
  preserved_env="${preserved_envs[$index]}"
  test "$(dirname -- "$preserved_env")" = "$LEGACY_ARCHIVE_DIR"
  if test -e "$preserved_env" || test -L "$preserved_env"; then
    test -f "$preserved_env"
    test ! -L "$preserved_env"
    test "$(realpath -- "$preserved_env")" = "$preserved_env"
    test "$(stat -c '%a' "$preserved_env")" = '600'
    test "$(sha256sum "$source_env" | awk '{print $1}')" = \
      "$(sha256sum "$preserved_env" | awk '{print $1}')"
    continue
  fi
  test "$(realpath -m -- "$preserved_env")" = "$preserved_env"
  install -m 0600 -- "$source_env" "$preserved_env"
  test -f "$preserved_env"
  test ! -L "$preserved_env"
  test "$(realpath -- "$preserved_env")" = "$preserved_env"
  test "$(stat -c '%a' "$preserved_env")" = '600'
  test "$(sha256sum "$source_env" | awk '{print $1}')" = \
    "$(sha256sum "$preserved_env" | awk '{print $1}')"
done
```

确认 `current` 指向已验证的新 release、旧 Compose project 已无容器/网络/卷挂载、两个 `.env` 归档与所有证书密钥均已复核后，才能在单独维护记录中删除精确目录 `/home/ubuntu/InterviewAceTrainee`。不得用通配符、父目录递归删除或全局清理替代。删除后再次确认服务器不存在 `.git`、`src/test`、前端测试、旧 Python tests 和构建缓存；`shared/legacy/InterviewAceTrainee.Backend.env` 与 `shared/legacy/InterviewAceTrainee.Frontend.env` 必须继续保持 0600 且不进入发布包。

## 8. 回滚

自动发布会在已有旧版本时保存 `previous`；首次发布则允许不存在 `previous`。外部健康检查失败时工作流会自动调用下列逻辑。手工执行前必须确认 `current` 是待回滚 SHA；若 `previous` 存在，还必须确认它是已知健康且由同一 CI 流程构建的 release。首次发布没有 `previous` 时，同一脚本会删除候选应用服务并移除 `current`，保留数据层和候选 release 供排查：

```bash
export FAILED_DEPLOY_SHA='<当前待回滚的40位SHA>'
test "$(readlink -f "$PRODUCTION_PATH/current")" = "$PRODUCTION_PATH/releases/$FAILED_DEPLOY_SHA"
if test -e "$PRODUCTION_PATH/previous" || test -L "$PRODUCTION_PATH/previous"; then
  test -L "$PRODUCTION_PATH/previous"
  PREVIOUS_RELEASE="$(readlink -f -- "$PRODUCTION_PATH/previous")"
  test "$(dirname -- "$PREVIOUS_RELEASE")" = "$PRODUCTION_PATH/releases"
  test -f "$PREVIOUS_RELEASE/commit.txt"
else
  test ! -e "$PRODUCTION_PATH/previous"
  test ! -L "$PRODUCTION_PATH/previous"
fi
MIANBA_PRODUCTION_ROOT="$PRODUCTION_PATH" \
MIANBA_FAILED_DEPLOY_SHA="$FAILED_DEPLOY_SHA" \
  bash "$PRODUCTION_PATH/releases/$FAILED_DEPLOY_SHA/deploy/rollback-release.sh"
```

回滚脚本会先用旧 Compose/镜像恢复服务，并以精确 HTTP 200 与六项布尔 `true` 通过 API/Nginx readiness，然后才原子恢复 `current`。若这个过程失败，它会先恢复候选应用并确认 readiness，再把指针补偿回原目标；候选也无法恢复时不会强行把指针指向已知不可用版本，而是保留现场并要求人工处理。若新 Flyway schema 与旧镜像不兼容，不执行应用层盲回滚；保持维护状态并由负责人决定前向修复。所有 Flyway 变更必须至少保持一个发布周期的向后兼容性。由于本次已授权清空旧数据，旧 Python schema 不作为可恢复目标。

## 9. 4 核 4 GB 观察门槛

发布后至少观察一个完整 AI 任务周期，重点检查:

- API/Worker/Parser RSS 持续低于各自容器限额 85%，无 OOMKilled 或非预期重启增长。
- PostgreSQL 连接低于 32，慢 SQL 与锁等待不持续增长。
- RabbitMQ 主队列不持续堆积，DLQ 无异常增长，只有预期 Worker 消费者。
- Redis 低于 160 MiB，AOF 正常，无 `noeviction` 写入失败。
- API 5xx、登录限流、Provider 超时和任务失败率处于可解释范围。
- `/proc/pressure/memory` 的 `full` 压力不持续增长，`vmstat` 的 `si/so` 不持续非零；约 1.9 GiB Swap 只吸收短峰值，不用于扩大 JVM 堆或长期超配。
- `current` 目录无 `.git`、`src`、test/spec 文件或构建缓存。
- PostgreSQL 已从 `PUBLIC` 撤销默认 CONNECT 与 `public` schema 权限，仅 owner、`mianba_api` 和 `mianba_worker` 按角色获得连接/对象权限。

生产数据按以下规则执行有界批处理，排查磁盘增长或隐私删除问题时必须同时核对状态与引用关系：

- 每个用户 24 小时内最多新建 10 份材料，30 天内最多新建 30 份材料，同时最多保留 20 份 `ready` 材料
- 材料创建 30 天后擦除内容并标记删除；标记删除满 7 天且没有会话或任务引用后才物理删除
- 用户删除面试时，会话先进入 `deleting`；中断的擦除流程由每小时留存任务恢复，最终状态为 `deleted`
- `completed`、`cancelled` 或 `deleted` 会话结束满 90 天后擦除问答、报告和 AI 任务输入输出

只有 health、readiness、核心 smoke、资源和错误率都稳定，才能结束维护窗口。

## 10. 商业化开放前的备份与告警门槛

当前重建可以按本次明确授权丢弃旧内测数据，但新 Java/Flyway 数据一旦产生就恢复默认保护策略。正式扩大用户范围前必须完成以下门槛：

- PostgreSQL 是用户、次数账本、任务、审计和人工退款工单的事实来源；至少每日生成一次加密、异机保存的逻辑备份，并设置可验证的保留与删除周期。服务器本机卷、同盘目录或 Docker volume 快照不能单独称为备份。
- 使用与生产隔离的数据库按固定周期完成恢复演练，记录备份 SHA-256、恢复耗时、Flyway 版本和关键表行数抽查。未实际恢复过的备份不能作为上线保障。
- Redis 是缓存/限流/临时状态，RabbitMQ 是投递通道；二者不能替代 PostgreSQL outbox、任务表和余额流水。恢复流程应允许清空缓存并从数据库状态重新驱动未完成任务。
- 对磁盘剩余空间、容器 OOM/重启、API 5xx、Provider 超时、RabbitMQ 主队列与 DLQ、Worker 心跳、PostgreSQL 连接/慢查询、Redis 内存和证书有效期建立告警。证书至少在 30、14、7 天阈值告警。
- 每次发布前执行只读证书检查，不能在输出中打印私钥或 Secret：

```bash
openssl x509 -in "$PRODUCTION_PATH/shared/certs/sakuracianna.icu_bundle.pem" -noout -checkend 2592000
openssl x509 -in "$PRODUCTION_PATH/shared/certs/sakuracianna.icu_bundle.pem" -noout -enddate
```

仓库当前没有获批准的异机备份目的地或告警接收端，因此发布脚本不会伪造一个“已备份”状态。配置对象存储/备份主机、加密密钥与告警渠道属于首次商业化开放前的独立运维任务，完成后必须补充恢复演练记录。
