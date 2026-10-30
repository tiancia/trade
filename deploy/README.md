# Ubuntu 生产部署（默认 SSH 私有访问）

[项目文档总导航](../docs/README.md) · [架构与部署上手指南](../docs/PROJECT_GUIDE.md) · [后端运维](../docs/backend/OPERATIONS.md)

首次接手项目，先读上手指南理解三台机器、三条独立通信链路和配置职责；本文件负责首次安装及生产运维的详细步骤。

## deploy 目录放什么

`deploy` 存放生产部署需要的工具与配置，不放业务代码。其 README 与脚本就近维护，全局文档导航统一在仓库根目录 `docs/`。

| 路径 | 用途 |
| --- | --- |
| [ci/cnb.yml](ci/cnb.yml)、[scripts/cnb-ci.sh](scripts/cnb-ci.sh) | CNB 控制流水线与适配入口 |
| [scripts/managed-ci.sh](scripts/managed-ci.sh)、[scripts/build-images.sh](scripts/build-images.sh) | CNB 构建、推送镜像并通过 SSH 发布 |
| [scripts/release.sh](scripts/release.sh) | 在服务器校验、发布选定服务和显式回滚 |
| [scripts/cleanup-images.sh](scripts/cleanup-images.sh) | 保留当前、上一成功清单和容器引用的镜像，预览或清理更早的本地 Trade 镜像 |
| [scripts/sync-backend-config.ps1](scripts/sync-backend-config.ps1) | Windows 向服务器安全同步选定业务配置；本身不重启服务 |
| `egress/` | 业务出站 SSH 隧道的 Dockerfile 和入口脚本 |
| `nginx/` | 私有 / 公共网关、API 路由和 SSE 转发配置 |
| `prometheus/` | 生产容器网络使用的指标抓取配置 |
| `.env.example`、`backend.env.example`、`release.env.example` | 基础设施、后端业务、镜像版本的配置模板 |
| `.env`、`backend.env`、`secrets/`、`releases/` | 在实际环境创建的运行配置、密钥及发布记录，Git 不保存真实值 |

本地开发中间件使用根目录 `compose.yaml`；生产使用 `compose.prod.yaml`。不要因为目录里也有配置模板，就把真实凭据或运行数据放入共享文档。

## 部署方案

本方案把后端、交易驾驶舱和 X 控制台一起部署到国内 Ubuntu，不使用 Vercel。代码由 CNB 打包为带版本号的 Linux 镜像并推送 TCR，服务器负责拉取和运行；后续更新继续使用同一套发布脚本。默认通过 SSH 隧道访问两个页面，无需准备域名或 TLS 证书。

```text
电脑浏览器 ── localhost:18080 / localhost:18081
                       └─ SSH 加密隧道 → 国内服务器 127.0.0.1:18080 / :18081
                                               └─ gateway + 网关密码
                                                    ├─ 18080 → cockpit:3000
                                                    ├─ 18081 → x-console:8080
                                                    └─ /api/ → backend:8080
                                                                 ├─ mysql:3306 / redis:6379
                                                                 ├─ DeepSeek：直接访问
                                                                 └─ egress:13128
                                                                      └─ SSH → 德国 127.0.0.1:3128 Squid
                                                                                └─ OKX / Telegram / X
```

默认只开放受限制的 SSH 管理入口。两个 Web 入口绑定宿主机 `127.0.0.1`；安全组和防火墙不开放 80、443、18080、18081、数据库、后端或代理端口。电脑访问的 HTTP 地址在本机回环，跨公网传输由 SSH 加密。页面和 API 统一使用网关账号密码限制访问。

网关删除调用方的 `Forwarded`、`X-Forwarded-Prefix` 和 `X-Forwarded-Ssl`，根据实际连接重建转发主机、协议、端口和来源地址，避免后端的转发头处理器信任伪造信息。显式外部端口取自实际请求的 `Host`，因此 SSH 私有访问的 `18080` / `18081` 以及自定义 HTTPS 端口仍可用于生成正确 URL。更新此规则时同步两个 Nginx 模板和 `includes/proxy-common.conf`，再按现行发布流程重新创建 `gateway`；仅发布后端镜像不会更新服务器配置文件。

电脑到国内服务器的访问隧道，与国内服务器到德国 VPS 的 `egress` 隧道独立。关闭电脑上的 SSH 连接只会中断页面访问，服务器上的 PAPER 策略、X 生成、Telegram 审核和发布任务继续运行。此方案是私人运维访问安排；公网网站的备案与接入要求另按托管方式和服务商规定确认，见后文可选公共模式。

## 先准备这些内容

1. 国内 Ubuntu 上安装 [Docker Engine + Compose 插件](https://docs.docker.com/engine/install/ubuntu/)，Compose 至少 **2.30**（使用 `env_file.format: raw`）。执行 `docker version`、`docker compose version` 确认 Linux Engine 可用。部署脚本依赖 Bash 和 Ubuntu 的 `flock`。
2. 国内服务器的 SSH 地址、端口和登录账号；电脑可以通过 SSH 登录。安全组和主机防火墙仅开放所需 SSH 入口，并尽量限定管理来源。服务器账号需能使用 Docker；可使用专用部署账号。
3. 德国 VPS 上已有可用 Squid；准备一个只用于转发到其 `127.0.0.1:3128` 的 SSH 账号及密钥。Squid 不需要开放公网 3128。
4. 准备 TCR 命名空间 `ccr.ccs.tencentyun.com/tiancia-trade` 下的 `backend`、`cockpit`、`x-console`、`egress` 四个仓库，以及 CNB 控制仓库和部署凭据，配置见后文 [CNB 发布入口](#cnb-发布入口)。

建议从 2 核 4 GB 起步，先不开监控 profile。配置内存上限合计约 2.7 GB，仍需给操作系统、Docker 和页缓存留空间；这是初始配置，不是容量测试结果。Maven / npm 和镜像构建由 CNB 执行，避免占用运行服务器资源。

下面除构建步骤外，都在国内 Ubuntu 的仓库根目录执行，示例目录 `/opt/trade`。不要在服务器复用开发环境根 `.env` 或 Windows 的 `A:/DockerData/...`。原 `compose.yaml` 保持本地用途，生产入口是 `compose.prod.yaml`。

## 1. 创建服务器配置

将当前代码放到 `/opt/trade`，然后：

```bash
cd /opt/trade
umask 077
cp deploy/.env.example deploy/.env
cp deploy/backend.env.example deploy/backend.env
mkdir -p deploy/secrets
chmod 700 deploy/secrets
chmod 600 deploy/.env deploy/backend.env
```

编辑 `deploy/.env`：

| 必填项 | 用途 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD` | 分别设置长随机密码，生产无缺省密码 |
| `EGRESS_SSH_HOST`、`EGRESS_SSH_PORT`、`EGRESS_SSH_USER` | 德国 VPS 的实际 SSH 地址、端口、专用用户 |

`COCKPIT_LOCAL_PORT=18080`、`X_CONSOLE_LOCAL_PORT=18081` 是服务器回环监听端口，一般保持默认。无需填写域名、准备证书或设置公网 Web 入口；如改端口，同步修改后文 SSH 转发的服务器目标端口。电脑端也可换未占用端口，并使用对应浏览器地址。

路径相对于仓库根目录。`COMPOSE_PROJECT_NAME`、`DATA_VOLUME_PREFIX` 在首次部署后保持固定，否则会切换容器项目或数据卷。`deploy/.env` 遵循 Compose env 语法，密码含 `$` 时使用单引号包住，避免插值。

编辑 `deploy/backend.env`，填好：

- `DEEPSEEK_API_KEY`；
- `TRADE_TELEGRAM_BOT_TOKEN`、`TRADE_X_TELEGRAM_CHAT_ID`、`TRADE_X_TELEGRAM_REVIEWER_USER_IDS`（多个审核人用逗号分隔）；
- `TRADE_X_TARGET_USER_ID`，以及 `X_API_KEY`、`X_API_SECRET`、`X_ACCESS_TOKEN`、`X_ACCESS_TOKEN_SECRET`；
- `OKX_ACCESS_KEY`、`OKX_SECRET_KEY`、`OKX_ACCESS_PASSPHRASE`：仅 LIVE 的私有账户和订单接口需要；PAPER 使用独立模拟资金账本及公共行情，可留空；

页面通过 Basic Auth 登录；管理 API 由后端独立校验同一份 bcrypt `gateway.htpasswd`，包括 GET。后端缺少认证配置时返回 503，非法或缺少凭据返回 401。Compose 强制开启认证并只读挂载哈希文件；保持后端端口仅在容器网络内可达。凭据只向后端 API 转发，前端服务器不接收口令。

管理写接口还要求 `X-Trade-CSRF: 1`，浏览器请求校验同源或配置允许的 Origin；驾驶舱自动附加该头。命令行需同时携带 Basic 凭据及 CSRF 头，例如 `curl --user operator -H 'X-Trade-CSRF: 1' ...`（交互输入口令）。原有微博和文字游戏业务令牌仍须携带。后端与网关须一起更新，旧网关会剥除 Authorization。原生开发运行需要配置 `TRADE_MANAGEMENT_AUTH_CREDENTIALS_FILE`；仅隔离开发环境可显式设置 `TRADE_MANAGEMENT_AUTH_ENABLED=false`。

每位操作者使用独立账号，新增使用 `htpasswd -B deploy/secrets/gateway.htpasswd alice`，已有文件勿加 `-c`。使用 bcrypt cost 4–14，生产建议 cost 10–12。更新或撤销后重建 backend 与 gateway，使只读挂载和后端快照同时更新；后端只缓存成功凭据的 SHA256 30 秒，不保存明文，重建后立即清空。后端限制每分钟 30 次未缓存的口令验证，网关每来源 IP 限制平均 10 请求/秒、突发 100；收到 429 后按 Retry-After 等待。网关 JSON 审计保留来源 IP、账号、客户端证书序列号、方法、路径、结果与 request_id；应用审计记录写操作及拒绝的操作者、路径和状态。均不记录 Authorization、请求体或查询参数。容器日志仍在本机，外部收集与保留期限需另行配置。

`backend.env` 使用 **raw** 格式：每行 `KEY=value`，不要给值额外加引号，不要 `source`，也不要放 shell 命令。它只在运行时注入后端，不参与镜像构建。

如果 Windows 开发机已经通过 `setx` 或 `application.yml` 配好了这些业务凭据，可在 **Windows PowerShell** 一次同步：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File A:\trade\deploy\scripts\sync-backend-config.ps1 -Server ubuntu@国内服务器公网IP
```

仓库在其他位置时修改脚本路径；自定义 SSH 端口追加 `-Port 端口`，指定登录私钥追加 `-IdentityFile "私钥路径"`。电脑需已通过 SSH 核验并保存国内服务器主机指纹，服务器需有 Python 3 且登录用户可写 `/opt/trade/deploy`。`-CheckOnly` 只检查本地必填项。

脚本优先读取 Windows 用户、系统、当前进程环境变量，再读取本项目 YAML 对应字段的标量或 `${变量:默认值}`；这样无需重开终端也能读取 `setx` 新保存的值。默认仅同步上面九项 AI / Telegram / X 凭据和目标 ID。补齐 OKX 配置时单独执行下面的命令，仅更新三个 `OKX_*` 字段；也兼容后端已有的 `ak`、`sk`、`ap` 环境变量别名：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File A:\trade\deploy\scripts\sync-backend-config.ps1 -Server ubuntu@国内服务器公网IP -OnlyOkx
```

缺项时只显示字段名并终止上传。内容经 SSH 标准输入传输，不回显、不放入命令参数或本地中间文件。服务器保留基础设施 `.env` 和其他业务配置，不再生成管理令牌。更新前保留权限 600 的 `backend.env.before-sync-*` 备份，再原子替换 `backend.env`。同步不会启动或重启服务。运行中的容器需要通过发布脚本重新创建 `backend` 才能加载新环境；单纯 `docker compose restart` 不会加载 env 文件更新。

模板默认配置为：OKX 自动策略 **PAPER**、真实下单关闭；X 自动生成、Telegram 人工审核和真实发布开启，每轮完成后等三小时；首次生成约 30 秒后开始。Polymarket、微博、小说任务关闭。DeepSeek 使用 `deepseek-flash` 直连；OKX、Telegram、X 使用隧道代理。容器地址由 Compose 强制覆盖为 `egress:13128`，不要填写 `127.0.0.1`。

当前 PAPER 使用独立、持久化的模拟现金和持仓账本，决策只采集公共行情与产品规则，不读取真实账户余额、挂单和成交；三个 `OKX_*` 可以留空。LIVE 私有接口需要独立配置和验收凭据；只读检查仅开启 `Read`，关闭 `Trade` / `Withdraw`，绑定 IP 时使用实际出口德国 VPS 的公网 IP。权限说明参见 [OKX 官方文档](https://www.okx.com/docs-v5/en/#overview-api-key)。公共行情、PAPER 成功不代表 LIVE 私有接口可用；同时检查运行状态 `lastError` 和最新决策。模拟账本口径见 [策略参数版本与模拟资金](../docs/backend/TRADING_SIMULATION.md)。

缺少 X 目标账号或必需的审核配置会拒绝启动；AI / X 凭据、账号权限和外部连接仍需运行验收，进程健康不代表这些已验证。现有数据库中已批准、未过期且审核有效的草稿可能在启动后的扫描中发布。不要让旧电脑进程和服务器同时消费同一个 Telegram Bot；迁移前停止旧的审核 / 生成 / 发布任务。X 控制台本身是只读页面，审核在 Telegram 完成；浏览器访问使用前述网关账号密码。

## 2. 配置德国隧道和网关密码

在国内服务器生成专用密钥，无口令便于容器无人值守连接；权限和德国端的转发限制配合使用：

```bash
ssh-keygen -t ed25519 -f deploy/secrets/egress_key -N '' -C trade-egress
```

将 `egress_key.pub` 安装到德国 VPS 专用用户的 `~/.ssh/authorized_keys`。可以在德国 VPS 创建非 sudo 用户 `trade-proxy`；该用户需要 SSH 公钥登录权限，sshd 需允许本地 TCP 转发。公钥行建议使用以下限制，后半段替换为实际公钥：

```text
restrict,port-forwarding,permitopen="127.0.0.1:3128",command="/usr/sbin/nologin" ssh-ed25519 AAAA... trade-egress
```

`.ssh` 属于该用户且权限 700，`authorized_keys` 权限 600。以上 key 允许 `ssh -N` 的指定转发，禁止交互会话。配置语义见 [OpenSSH 官方手册](https://man.openbsd.org/sshd.8#AUTHORIZED_KEYS_FILE_FORMAT)。已有 sshd 全局禁止转发时还需调整专用用户的有效配置，不要关闭主机身份验证。

回到国内服务器，把 `VPS_HOST` 和 `22` 替换为实际主机和端口：

```bash
ssh-keyscan -p 22 VPS_HOST > deploy/secrets/egress_known_hosts
ssh-keygen -lf deploy/secrets/egress_known_hosts
```

将指纹与德国 VPS 控制台上 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` 等对应主机公钥指纹比对，确认一致后再使用。`ssh-keyscan` 本身不验证身份。更换 SSH 端口时，`known_hosts` 中应为该端口的记录。

```bash
sudo chown 10001:10001 deploy/secrets/egress_key
sudo chmod 600 deploy/secrets/egress_key
chmod 644 deploy/secrets/egress_known_hosts
sudo apt-get update
sudo apt-get install -y apache2-utils
htpasswd -cB deploy/secrets/gateway.htpasswd operator
chmod 644 deploy/secrets/gateway.htpasswd
```

`htpasswd` 交互输入网关密码，避免密码出现在命令参数中。哈希文件需允许 Nginx worker 读取，所以文件使用 644、宿主机父目录保持 700；SSH 私钥则仅 UID 10001 可读。容器启动时复制私钥到 tmpfs 并检查权限。每次 SSH 退出由 Docker 重启重连；不会向宿主机发布 13128。

## 3. 构建镜像并发布

完成服务器配置后，将代码提交到 GitHub `main`，在 **Actions → Submit build or deployment to CNB → Run workflow** 选择 `service=all`、`mode=build_deploy`。首次尚无成功镜像基线，必须全量构建。也可先用 `mode=build`，确认 CNB 镜像推送成功后，再用 `mode=deploy` 并粘贴完整清单发布，详见 [CNB 发布入口](#cnb-发布入口)。

CNB 构建 `linux/amd64` 镜像；不需要在电脑或生产服务器手工生成 JAR、dist。GitHub 任务成功只表示提交成功，构建与生产发布结果以 CNB 任务日志为准。

后端构建上下文是仓库根，由 `backend/Dockerfile.dockerignore` 白名单限制；两个前端和隧道各有独立上下文。实际 `.env`、密钥、数据目录和历史构建产物不进入镜像。前端有独立 Docker Vite 配置，原本地 / Cloudflare 开发流程保持不变。

后端镜像包含现有 Polymarket 桥接所需的 Python 虚拟环境、固定版本客户端和脚本，构建时只检查依赖导入。Compose 固定 `POLYMARKET_ORDER_SCRIPT=/app/tools/polymarket/polymarket_place_order.py`；该模块及真实执行开关仍默认关闭。Story 输出固定到持久卷内的 `/app/data/story`，生成前会验证实际写入权限；这两项路径不应使用开发机的相对目录。

集市图片上传改为后端签发短期 OSS V4 PostObject policy，前端用表单直接上传，不再接收 AccessKey Secret。开启集市时需同时更新后端和集市前端，并在 OSS Bucket CORS 中允许该前端来源的 POST；policy 绑定对象 key、文件字节数、类型、ACL 和过期时间。它限制 HTTP 元数据及文件大小，不进行图片内容识别。真实 STS/OSS 权限与上传仍需部署后独立验收。

CNB 将完整镜像清单及发布脚本上传服务器，然后校验、拉取镜像并等待容器健康。服务器上的 `deploy/.env`、`backend.env`、`secrets/` 和数据卷须提前准备；Compose、网关等配置变更仍需单独同步到服务器。

全量发布先确保 MySQL / Redis 健康，再停止并重建选中的应用，包括网关。包含 backend 的发布会检查镜像与数据库版本，在停止旧后端后自动备份 MySQL，由新后端启动时执行 Flyway 迁移，健康及版本检查通过才记录成功；仅前端更新跳过数据库操作。不会删除数据卷或自动升级 MySQL 服务版本。

发布采用单实例短暂停机。失败返回非零并停止已进入更新阶段的后端，须核对日志和数据库实际状态后处理。迁移可能部分提交，不自动修复版本记录或恢复备份；数据库版本高于目标镜像时拒绝回滚。健康检查只证明进程就绪，外部代理、行情和内容发布还需业务验收。

## 4. 建立电脑访问隧道

在 Windows PowerShell 打开单独窗口执行，把 `deploy-user@国内服务器IP` 换成实际 SSH 账号和地址：

```powershell
ssh -NT -o ExitOnForwardFailure=yes `
  -L 127.0.0.1:18080:127.0.0.1:18080 `
  -L 127.0.0.1:18081:127.0.0.1:18081 `
  deploy-user@国内服务器IP
```

SSH 不是 22 端口时，在登录地址前添加 `-p 实际端口`；有专用登录密钥时添加 `-i 密钥路径`。这里的登录密钥属于电脑访问国内服务器，不能使用德国代理的 `egress_key`。首次连接核对国内服务器主机指纹。命令成功后通常没有输出，保留该窗口；电脑休眠或网络中断后重新运行即可。

浏览器访问：

- 交易驾驶舱：`http://127.0.0.1:18080`；
- X 控制台：`http://127.0.0.1:18081`。

两个页面都先输入 `operator` 和网关密码，之后无需填写应用令牌，X 控制台会自动读取内容。电脑本地端口已占用时，例如把第一条改为 `-L 127.0.0.1:28080:127.0.0.1:18080`，浏览器对应使用 `http://127.0.0.1:28080`。不要把 `-L` 的监听地址改成 `0.0.0.0`。

## 5. 部署验收

在服务器 Bash 定义一个便于日常排障的命令；不使用 `source`：

```bash
dc() {
  docker compose --env-file deploy/.env --env-file deploy/release.env -f compose.prod.yaml "$@"
}
dc ps
dc logs --tail=100 backend egress gateway
```

不要把完整日志或 `docker compose config` 输出公开，它们可能包含运行信息。配置校验使用 `config -q`。日志分享前清理凭据和账户数据。

分别验证：

```bash
# 仅从后端容器验证完整代理链；公开时间接口不会下单。
dc exec -T backend curl --noproxy '' -fsS --max-time 20 \
  --proxy http://egress:13128 https://www.okx.com/api/v5/public/time

# -u 只给用户名，由 curl 交互询问网关密码。
curl -fsS -u operator 'http://127.0.0.1:18080/api/trading/market/candles?instId=BTC-USDT&bar=5m&limit=2'
curl -fsS -u operator http://127.0.0.1:18080/api/automation/tasks
```

以上两个网关请求可直接在服务器上验证；Windows 电脑建立隧道后也可用 `curl.exe` 请求同一地址。浏览器确认行情、运行状态和 X 草稿列表加载正常；确认策略运行模式为 PAPER。再完成一次真实 AI 生成 → Telegram 审核 → X 发布的人工验收，这一步会产生 AI 费用和真实 X 发帖。上线配置开关已经开启，请自行选择要批准的草稿。

`/healthz` 只报告网关，不能代表业务验收；网关没有转发 `/actuator/`。backend 自检在容器内部访问 `/actuator/health/readiness`。从其他机器直接请求国内服务器的 18080 / 18081 应无法连接；如果能连接，检查是否仍使用旧 Compose 配置或存在其他进程监听。

## 后续更新和回滚

### CNB 发布入口

发布链路为 **GitHub → CNB → TCR → 生产服务器**。[GitHub 工作流](../.github/workflows/cnb-build-deploy.yml)只接受本仓库 `main` 的手动请求，向私有控制仓库 `tiancia/trade-ci` 提交任务；[CNB 流水线](ci/cnb.yml)验证并检出 GitHub `main` 历史内的指定提交，再调用 [cnb-ci.sh](scripts/cnb-ci.sh)、[managed-ci.sh](scripts/managed-ci.sh) 和 [build-images.sh](scripts/build-images.sh)。

GitHub Repository Secret 仅需 `CNB_TOKEN`，用于向控制仓库提交任务。将 `deploy/ci/cnb.yml` 安装为控制仓库的 `.cnb.yml`；流水线从私有 secrets 仓库导入环境配置，注入 `TCR_USERNAME`、`TCR_PASSWORD` 及部署所需的 `DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_PORT`、`DEPLOY_SSH_PRIVATE_KEY`、`DEPLOY_KNOWN_HOSTS`。SSH 主机记录须预先核验，非默认端口使用 `[主机]:端口` 格式。部署账号需使用 Bash，能读成功清单、写 `/opt/trade/deploy` 并使用 Docker / Compose。

TCR 和 SSH 凭据只在 CNB 注入，不放进 GitHub 请求、构建上下文或镜像。GitHub 入口固定部署到 `/opt/trade`，使用 `private` 访问模式；公共模式按后文手工运维流程处理。

在 **Actions → Submit build or deployment to CNB → Run workflow** 选择 `main` 和参数：

| 参数 | 可选值与用途 |
| --- | --- |
| `service` | `backend`、`cockpit`、`x-console`、`egress`：单服务；`frontends`：两个前端；`all`：四个镜像及全量发布 |
| `mode=build` | 默认模式，只构建并推送镜像 |
| `mode=build_deploy` | 构建、推送并部署选定服务 |
| `mode=deploy` | 复用已发布镜像，必须在 `existing_release_manifest` 粘贴完整 `release.env` 文本 |
| `mode=rollback` | 按服务器 `deploy/releases/previous.env` 回滚选定服务，不填写历史清单 |

单服务或 `frontends` 构建会通过 SSH 下载服务器的 `deploy/releases/current.env`，只替换选定镜像，因此即使仅构建也需要部署 SSH 凭据和成功基线。没有基线时先选 `all`。只改后端选 `backend`，只改驾驶舱选 `cockpit`；`all` 部署包含网关与基础设施前置检查。

镜像固定推送到 `ccr.ccs.tencentyun.com/tiancia-trade/<服务>`，版本含实际 GitHub SHA、运行号和随机后缀，不使用 `latest`。所有选定镜像推送成功后才生成完整清单，包含 `RELEASE_VERSION` 及四项 `BACKEND_IMAGE`、`COCKPIT_IMAGE`、`X_CONSOLE_IMAGE`、`EGRESS_IMAGE`。CNB 日志在 `IMAGE_MANIFEST_BEGIN` / `IMAGE_MANIFEST_END` 间打印清单，并尝试保留 `release-*.env` 附件 30 天；后续部署失败仍可复制清单重试 `mode=deploy`。该模式校验选定服务确实属于清单版本，不自动搜索历史任务。

GitHub 成功只表示 CNB 接受了请求。打开提交结果中的 CNB 任务链接核对构建和推送；生产发布成功日志包含 `DEPLOYMENT_OK`，回滚成功包含 `ROLLBACK_OK`，并应核对服务器成功版本记录和业务状态。

### 发布行为与回滚

CNB 上传本次发布脚本和清单到服务器独立的 incoming 目录，通过 SSH 标准输入传递 TCR 凭据，服务器使用临时 Docker 登录配置拉取镜像。成功后更新 `deploy/release.env`、`deploy/releases/current.env`，保留 `previous.env` 和历史记录。服务端锁串行化发布，健康检查失败不会自动回滚。

`mode=rollback` 先检查上一成功版本的本地镜像齐全，再切换选定服务；首次发布没有 `previous.env`，不能回滚。人工运维也可在服务器执行：

```bash
cd /opt/trade
bash deploy/scripts/release.sh rollback --no-pull
```

仅更新业务配置、继续使用原镜像时，可用 `bash deploy/scripts/release.sh deploy backend --no-pull` 重建后端。单纯 `docker compose restart` 不加载新的 env 文件。镜像回滚不恢复数据库、业务配置、密钥或网关文件；数据库补丁和访问模式切换单独处理。

发布成功后清理更早的本地应用镜像，保留当前、上一成功清单及容器引用的镜像。清理失败仅告警，不改变已成功发布的结果。CNB 不会自动同步 Compose、Nginx 或业务配置文件；这些文件变更需先同步服务器，再安排相应服务更新。

### Docker Hub 基础镜像限流（429）：纯云端镜像源切换

CNB 使用的公共 Docker Hub 加速地址 `mirror.ccs.tencentyun.com` 可能返回
HTTP 429。CNB 托管平台的默认 Builder 和 Docker daemon 配置未必可修改，
因此构建脚本通过 Dockerfile `ARG` 直接引用备用镜像地址。

**CNB 云端构建默认启用 `BASE_IMAGE_SOURCE=daocloud`**：对应镜像为
`m.daocloud.io/docker.io/library/{maven,eclipse-temurin,node,nginx,alpine}` 的固定版本。
无需通过 Windows 下载/上传镜像，也不必创建嵌套 Buildx Builder。
本地调用 `build-images.sh` 的默认来源仍然是 Docker Hub。

在 CNB 导入的受控环境变量中，可以显式覆盖以下值：
- `BASE_IMAGE_SOURCE=daocloud`（CNB 默认），DaoCloud 公共镜像代理；
- `BASE_IMAGE_SOURCE=dockerhub`，原有 Docker Hub 引用（可能仍经 CNB 默认加速站）；
- `BASE_IMAGE_SOURCE=tcr`，可选，仅在 TCR 的五个 `base-*` 镜像仓库
  已存在对应版本时使用，**不要求手工 Windows 上传作为正常部署步骤**。

DaoCloud 公共服务同样可能限流和出现缓存问题；参见
[DaoCloud 镜像站官方项目](https://github.com/DaoCloud/public-image-mirror)。
如果备用源不可用，可切换到原始镜像源测试；频繁发生时应在 CNB 中
引入自动云端同步/私有镜像缓存机制，而无需本地机器介入。

日志应包含 `Build base image source: daocloud`，以及对应
`m.daocloud.io/docker.io/library/...` 镜像元数据下载记录。
Dockerfile 最顶部的 `# syntax=docker/dockerfile:1` 是独立的
BuildKit 前端镜像依赖，可能仍经平台默认镜像站拉取。业务镜像推送目标、
发布版本、生产服务器镜像拉取与回滚逻辑都维持原样。

### 构建缓存与耗时定位

BuildKit 按服务保存本地 OCI 缓存，CNB 节点数据卷提供复用；节点变化或缓存淘汰后可能重新填充。构建脚本逐服务记录构建、推送和 `elapsed_seconds`；发布脚本通过 `DEPLOY_STAGE` 区分配置校验、镜像拉取、容器停止、重建与健康等待。依赖下载和推送发生在 CNB，`image_pull` 才是生产服务器下载。

服务器 Docker daemon 的代理例外需覆盖 TCR 及实际镜像层重定向域名，保留已有例外并通过真实私有镜像拉取验收。仅仓库入口返回 `401` 不能证明完整下载正常；调整 daemon 配置可能需要在维护窗口重启 Docker 并核对容器状态。

## 数据、备份和监控

生产使用独立命名卷：默认 `trade-production_mysql`、`trade-production_redis`、`trade-production_backend`。后者持久化整个 `/app/data`，保留策略、近期决策和原子替换的 `trading-state.json`；资金、订单、X 草稿和审核状态在 MySQL。不要挂载单个 JSON 文件，不要执行 `down -v`。现有 Windows 数据不会自动迁移。

数据库结构由镜像内的 Flyway 版本迁移管理。空库自动初始化；无迁移历史的旧库经已知结构核验后接入，再执行增量升级，本次 Trading 变更包含在 V2 中。今后改库追加 `db/migration/V<N>__description.sql`、同步模块完整结构并提升后端 Dockerfile 的 schema 标签，不能修改已发布的迁移。详见 [数据库升级说明](../backend/src/main/resources/db/README.md)。过旧或未知结构会阻止启动，需先核实历史前置条件。

后端发布前会在 `deploy/releases/database-backups/` 留存权限受限的 `.sql.gz` 和 SHA256 文件，输出 `DATABASE_BACKUP_OK`。备份、迁移、健康或版本检查任何一步失败都不会记录发布成功。备份覆盖当前整库且不自动删除，定期按保留策略归档；本地发布快照不替代下述异机备份。所有业务表须为 InnoDB；共用数据库的其他主机或外部写入者须先停止，单机发布锁与数据库迁移锁不会代替它们停写。

### 加密异机数据库备份

[backup-mysql.sh](scripts/backup-mysql.sh) 对全部 InnoDB 业务表执行在线事务快照，直接经过 gzip 和 age 公钥加密，再通过严格校验主机指纹的 SCP 上传异机。只有异机 SHA256 核验及独立备份监控的成功心跳均完成，才输出 `BACKUP_OFFSITE_OK`；任何阶段失败都返回非零。它不停止交易应用，也不写明文 dump，不会删除旧备份。脚本与发布共用 `.release.lock`，备份期间也不要另行执行 DDL。

先在生产服务器安装 `age`、OpenSSH client、curl 和 util-linux；在独立恢复机器生成 age 密钥，将**公钥**放到生产的 `/etc/trade-backup/recipients.txt`，私钥留在恢复机器或离线保险库。准备独立备份服务器账号、受限 SSH 私钥和经人工核对的 `known_hosts`；备份服务器必须与业务主机处于不同故障域，不能把同机另一目录当异机。生产仅持有加密公钥，不需要解密私钥。

另在独立外部监控创建每日备份心跳，要求超过 26 小时无成功心跳即通知值守人；将其专用 HTTPS URL 放入 root 可读的 `/etc/trade-backup/heartbeat.curl`，格式为 `url = "https://实际监控端点"`。该服务必须在本机、Docker、网络或备份任务全部失效时仍能发出告警。上述目录设 700，私钥与心跳配置设 600，勿提交仓库。

```bash
bash deploy/scripts/backup-mysql.sh \
  --recipients /etc/trade-backup/recipients.txt \
  --destination backup@真实异机地址:/srv/trade-backups \
  --identity /etc/trade-backup/ssh_key --known-hosts /etc/trade-backup/known_hosts \
  --heartbeat-config /etc/trade-backup/heartbeat.curl --output /var/backups/trade
```

首次手工运行成功后，复制 [systemd service](systemd/trade-mysql-backup.service) 和 [timer](systemd/trade-mysql-backup.timer) 到 `/etc/systemd/system/`，把 service 的 `backup-host`、路径和用户改成实际值，再执行 `systemctl daemon-reload`、`systemctl enable --now trade-mysql-backup.timer`。默认每天 UTC 03:15 加 0–15 分钟随机延迟；用 `systemctl list-timers trade-mysql-backup.timer` 和 `journalctl -u trade-mysql-backup.service` 核对。当前代码不会自动安装或启用定时器。

这是每日逻辑快照，初始恢复点目标约 24 小时，不提供 binlog 时间点恢复，也不包含 MySQL 用户授权、存储过程、事件、`backend_data`、运行配置和密钥。`backend_data` 的策略与本地状态应在维护窗口停止 backend 后另做加密异机卷备份；将对应时间的运行配置、发布镜像清单和密钥分别安全备份。数据库与应用卷并非在线原子快照，不能混用任意时点的状态文件。备份服务本地文件会累积，须在恢复验证后按容量安排保留策略；异机保留至少一份生产账号无法修改的版本或不可变副本。

### 隔离恢复演练

在独立恢复机器取回异机 `.sql.gz.age` 和相邻 `.sha256` 文件，预先加载与生产匹配的 MySQL 8 镜像，准备 age 私钥，运行：

```bash
bash deploy/scripts/restore-drill.sh \
  --backup /srv/restore/mysql-20261024T031500Z-123.sql.gz.age \
  --identity /secure/offline-age-key --image mysql:8.0
```

脚本先校验 SHA256，再解密流式导入**新建**、无网络、无发布端口、无业务数据卷的临时 MySQL；检查存在业务表并执行 `mysqlcheck`，仅成功后输出 `RESTORE_DRILL_OK`。退出时仅删除本次新建容器及其匿名卷；不会连接生产 MySQL、启动 backend 或恢复真实交易。它验证 SQL 可导入与表检查，不替代逐域数据核对、业务恢复测试和资金对账。

至少每月以及每次 schema 变更后执行一次，并在仓库外记录所用异机备份、恢复耗时和结果；用外部日程/监控监督演练是否逾期。灾难恢复时先恢复隔离环境、核对数据库/策略版本及真实交易所订单与仓位，保持实盘停机，完成资金对账及人工确认后才恢复业务。仅备份命令成功不等于完成恢复演练；本地 mock 测试也不代表真实数据库恢复已通过。

### 告警投递与独立存活监控

默认不开监控。启用前填强 `GRAFANA_ADMIN_PASSWORD`，并在 `deploy/secrets/alertmanager_webhook_url` 写入一个外部 **HTTPS、支持 Alertmanager JSON 的**值守通知端点，设适当读取权限；可通过 `ALERTMANAGER_WEBHOOK_URL_FILE` 指定其他秘密文件。缺文件、空值或示例地址会使 Alertmanager 拒绝启动；不要直接使用不兼容的 Telegram/Slack API 地址。需要这些渠道时在外部使用兼容适配器，或按 [Alertmanager 官方接收器配置](https://prometheus.io/docs/alerting/latest/configuration/) 修改接收器。

```bash
dc --profile monitoring up -d --no-deps prometheus grafana alertmanager
```

生产 Prometheus 的 `alerting` 已指向 Alertmanager；交易告警按规则发送并包含恢复通知。Alertmanager 自身通知失败也有规则，但同一失效通道无法保证送达自己的故障，所以还需要独立外部存活监控。三个服务仅绑定回环 9090/3001/9093，通过 SSH 转发访问；默认指标保留七天。发布脚本不更新这三个可选服务，配置/版本升级单独安排。

启用后先用 `dc exec -T prometheus promtool check config /etc/prometheus/prometheus.yml` 和 `dc exec -T alertmanager amtool check-config /etc/alertmanager/alertmanager.yml` 检查，再向回环 Alertmanager API 投入临时测试告警：

```bash
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  --data '[{"labels":{"alertname":"TradeNotificationTest","severity":"critical"},"annotations":{"summary":"Verify delivery with the designated operator"}}]' \
  http://127.0.0.1:9093/api/v2/alerts
```

须由真实收件人确认到达，再通过实际 Prometheus 规则触发验证完整链路；API 返回 2xx、告警页面出现、或进程健康均不能作为送达证据。测试结束后在 Alertmanager 检查其恢复。配置独立外部监测生产主机、HTTPS网关、备份心跳及通知服务；同机 Prometheus/Alertmanager 在整机或网络失效时无法自报。

现有 Compose 仍是**单机部署**，容器自动重启与异机备份不能提供高可用。消除该单点需要独立故障域的数据库复制/托管数据库、受控故障切换、外部监控和备用应用主机；在确认持久化数据、领导者租约及交易对账前不可直接启动第二个 LIVE 实例。仓库配置与脚本不代表上述基础设施已建立或已上线。

## 可选：双域名 HTTPS 公共模式

以后需要公网 Web 访问时，才启用 `compose.public.yaml`，它在基础配置上替换网关端口、域名和证书挂载。部署地区、备案和接入要求先按服务商确认，见 [阿里云ICP备案要求](https://help.aliyun.com/zh/icp-filing/basic-icp-service/product-overview/icp-filing-requirements-for-a-regular-website)。仅把访问地址改成公网 IP 不能作为免备案方案。

准备两个域名解析到国内服务器，例如 `trade.example.com`、`x.example.com`；在 `deploy/.env` 增加 `COCKPIT_DOMAIN`、`X_CONSOLE_DOMAIN`，只填主机名，不带协议、路径或端口。安全组允许 80 / 443，其他业务端口继续不公开。准备同时覆盖两个域名的证书链与私钥：

```bash
mkdir -p deploy/secrets/tls deploy/acme
chmod 700 deploy/secrets/tls
```

```text
deploy/secrets/tls/fullchain.pem
deploy/secrets/tls/privkey.pem
deploy/secrets/tls/client-ca.pem
deploy/secrets/tls/client-crl.pem
```

证书文件使用 644、私钥使用 root 所有的 600。复制真实文件，避免把指向容器挂载目录外部的符号链接放进来。公共模式的路径可用 `TLS_DIRECTORY`、`ACME_DIRECTORY` 覆盖，均相对于仓库根目录。

公网 HTTPS 强制 mTLS 客户端证书及 Basic 口令：每位操作者取得专用客户端证书/私钥（clientAuth 用途），浏览器导入其 PKCS#12；服务端只放受信 CA 的证书 `client-ca.pem` 和仍在有效期内的撤销列表 `client-crl.pem`，CA 私钥留在离线签发端。签发、撤销与 CRL 更新可按 [OpenSSL CA 文档](https://docs.openssl.org/3.5/man1/openssl-ca/)执行；撤销客户端后发布新 CRL 并重建 gateway，同时撤销相应 Basic 账号。使用短期客户端证书并在 CRL 到期前更新。缺少客户端证书、非受信证书或已撤销证书都无法进入管理站点；缺少 CA/CRL 文件时 Nginx 拒绝启动。证书私钥不要与口令一起共享或放到服务器。该方案不提供 OIDC/硬件密钥登录或集中身份管理。

若用 Certbot，首次网关尚未启动且 80 空闲时可用 [standalone 模式](https://eff-certbot.readthedocs.io/en/stable/using.html#standalone)：

```bash
sudo certbot certonly --standalone --cert-name trade.example.com \
  -d trade.example.com -d x.example.com
sudo install -m 644 /etc/letsencrypt/live/trade.example.com/fullchain.pem deploy/secrets/tls/fullchain.pem
sudo install -m 600 /etc/letsencrypt/live/trade.example.com/privkey.pem deploy/secrets/tls/privkey.pem
```

Certbot 的安装与邮箱 / 服务条款按其官方流程完成。网关上线后不要让 standalone 续期与网关争抢 80；可改用 webroot `/opt/trade/deploy/acme`（已挂载并允许验证路径）。续期成功后仍须复制新证书并 reload 网关。部署脚本不申请或自动续期证书，投产前应配置 Certbot timer 和相应 deploy hook。

公共模式的所有发布操作都加 `--access public`，例如：

```bash
bash deploy/scripts/release.sh validate --access public
bash deploy/scripts/release.sh deploy --access public
bash deploy/scripts/release.sh deploy gateway --access public
bash deploy/scripts/release.sh rollback --access public
```

公共模式排障的 `dc` 函数也必须同时传两个 Compose 文件：

```bash
dc() {
  docker compose --env-file deploy/.env --env-file deploy/release.env \
    -f compose.prod.yaml -f compose.public.yaml "$@"
}
```

浏览器改为访问两个 `https://` 域名，须选择客户端证书并输入个人 Basic 口令；HTTP 仅处理证书验证和跳转 HTTPS。CLI 另外提供 `--cert`、`--key` 和 `--user`。私有模式的回环 Web 端口会被替换，不与公共端口同时发布。网关容器健康探针使用容器内回环 8082，不提供外部免证书访问入口。

切换私有 / 公共模式时保留相同项目名、数据卷前缀和运行配置，并执行目标模式的**全量发布**，不要只更新 `gateway` 或某个单服务，因为后端的来源配置也随模式变化。切到公共模式用 `deploy --access public`；切回私有模式用 `deploy --access private`。访问模式不存入镜像历史，镜像回滚不会恢复旧访问模式，回滚时须明确选择当前要使用的模式。

## 验证边界

每次发布前，应在隔离环境检查所选访问模式的 Compose 配置、Linux Nginx `-t`、发布脚本和受影响的应用测试；配置校验使用虚构凭据，不读取或输出生产密钥。发布完成后，重新核对当前运行镜像、容器健康、业务代理、行情和审核状态。

离线构建、配置解析和进程健康均不能代替业务验收。PAPER 决策、Telegram 审核和 X 发布状态按 [上手指南的验收步骤](../docs/PROJECT_GUIDE.md) 分别核对。公共模式还需另外验收域名、证书和续期，不能沿用私有模式的成功结论。

驾驶舱 HTML 测试覆盖 Node SSR 请求处理函数和 Cloudflare Worker 的 `fetch` 导出。离线内存渲染与接口契约测试、Linux 镜像构建，仍不能证明生产环境已加载对应镜像或外部账户权限已配置正确。
