# Ubuntu 生产部署（默认 SSH 私有访问）

[项目文档总导航](../docs/README.md) · [架构与部署上手指南](../docs/PROJECT_GUIDE.md) · [后端运维](../docs/backend/OPERATIONS.md)

首次接手项目，先读上手指南理解三台机器、三条独立通信链路和配置职责；本文件负责首次安装及生产运维的详细步骤。

## deploy 目录放什么

`deploy` 存放生产部署需要的工具与配置，不放业务代码。其 README 与脚本就近维护，全局文档导航统一在仓库根目录 `docs/`。

| 路径 | 用途 |
| --- | --- |
| [scripts/build-images.sh](scripts/build-images.sh) | 在构建机按需生成应用镜像及完整版本清单，默认构建四个服务 |
| [scripts/build-upload.sh](scripts/build-upload.sh) | 一个命令按需构建、导出、上传镜像与完整清单，并核验 SHA256；可加 `--deploy` 自动发布 |
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

本方案把后端、交易驾驶舱和 X 控制台一起部署到国内 Ubuntu，不使用 Vercel。代码在构建机打包成带版本号的 Linux 镜像，服务器只负责拉取和运行；后续更新继续使用同一套发布脚本。默认通过 SSH 隧道访问两个页面，无需准备域名或 TLS 证书。

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
4. 一个国内服务器能访问的镜像仓库命名空间，例如 `registry.example.com/trade`。构建机拥有推送权限，服务器拥有拉取权限；分别执行 `docker login`。也可以使用后文的 `save/load`。

建议从 2 核 4 GB 起步，先不开监控 profile。配置内存上限合计约 2.7 GB，仍需给操作系统、Docker 和页缓存留空间；这是初始配置，不是容量测试结果。构建镜像尤其 Maven / npm 构建应放在电脑或 CI，避免占用运行服务器资源。

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

两个前端无需应用令牌，页面和管理 API 统一由网关账号密码及网络隔离限制访问；保持后端端口仅在容器网络内可达。

管理写接口统一要求 `X-Trade-CSRF: 1`，浏览器请求还校验同源或配置允许的 Origin；驾驶舱自动附加该头，命令行调用也必须带上。后端与驾驶舱应一起更新，避免旧前端的写操作收到 403；读取不受影响。

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

在电脑的 WSL / Linux 构建机或 CI 中执行，Docker Desktop 需启动 **Linux Engine**。不需要先手工生成 JAR 或 dist，Dockerfile 会完成 Maven 测试 / 打包和两个前端构建：

```bash
bash deploy/scripts/build-images.sh \
  --registry registry.example.com/trade \
  --tag 20261016-a1b2c3d --push
```

将仓库命名空间和版本号换成实际值。默认生成 `linux/amd64`，ARM 服务器用 `--platform linux/arm64`。每次发布用新版本号，不复用 tag，不使用 `latest`。不指定服务时构建 backend、cockpit、x-console、egress 四个镜像；指定服务时只构建选中的镜像。两种方式都生成包含四项镜像引用的完整清单，默认输出到 `deploy/release.env`；后者可传给服务器，不包含业务密钥。

后端构建上下文是仓库根，由 `backend/Dockerfile.dockerignore` 白名单限制；两个前端和隧道各有独立上下文。实际 `.env`、密钥、数据目录和历史构建产物不进入镜像。前端有独立 Docker Vite 配置，原本地 / Cloudflare 开发流程保持不变。

后端镜像包含现有 Polymarket 桥接所需的 Python 虚拟环境、固定版本客户端和脚本，构建时只检查依赖导入。Compose 固定 `POLYMARKET_ORDER_SCRIPT=/app/tools/polymarket/polymarket_place_order.py`；该模块及真实执行开关仍默认关闭。Story 输出固定到持久卷内的 `/app/data/story`，生成前会验证实际写入权限；这两项路径不应使用开发机的相对目录。

集市图片上传改为后端签发短期 OSS V4 PostObject policy，前端用表单直接上传，不再接收 AccessKey Secret。开启集市时需同时更新后端和集市前端，并在 OSS Bucket CORS 中允许该前端来源的 POST；policy 绑定对象 key、文件字节数、类型、ACL 和过期时间。它限制 HTTP 元数据及文件大小，不进行图片内容识别。真实 STS/OSS 权限与上传仍需部署后独立验收。

把对应版本代码和生成的 `deploy/release.env` 放到服务器。服务器上的 `.env`、`backend.env`、`secrets/` 和数据卷保持原地，再执行：

```bash
cd /opt/trade
bash deploy/scripts/release.sh validate
bash deploy/scripts/release.sh deploy
```

脚本先校验并拉取全部所需镜像，然后确保 MySQL / Redis 健康，再停止旧应用、重建选中的应用并等待健康检查。默认更新五个应用服务；基础服务保持已有容器，正常发布不更新数据库版本、不运行手工迁移、不删除数据卷。首次部署会创建它们。

发布采用单实例短暂停机。失败返回非零；如果失败发生在停止之后，部分应用可能尚未启动，应查看日志并显式重试或回滚，不会自动继续真实发布。健康检查只证明进程就绪：隧道监听成功不能证明德国 Squid、OKX、Telegram 或 X 已联通。

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

### GitHub Actions 手动构建和按服务部署

使用 [build-deploy.yml](../.github/workflows/build-deploy.yml)，仅配置 `workflow_dispatch`；push 不会触发构建或部署。将工作流提交到默认分支后，在 **Actions → Build and deploy selected services → Run workflow** 中选择分支和参数：

| 参数 | 含义 |
| --- | --- |
| `service=backend` | 只构建后端；勾选部署时只更新后端 |
| `service=cockpit` | 只构建、按需部署交易驾驶舱 |
| `service=x-console` | 只构建、按需部署 X 控制台 |
| `service=frontends` | 一起构建、按需部署两个前端 |
| `service=egress` | 只构建、按需部署业务出站隧道 |
| `service=all` | 构建四个镜像；勾选部署时执行全量发布，包含网关与 MySQL/Redis 前置检查 |
| `build` | 默认勾选，构建并推送新镜像；取消勾选可直接复用已有构建 |
| `deploy` | 默认不勾选；勾选后通过 SSH 发布并等待健康检查。仅部署时必须勾选 |
| `source_run_id` | 仅部署时可填原构建的 Actions 运行 ID；留空自动选择所选分支上匹配服务的最新可用构建 |
| `server_path` | 已准备好的服务器仓库绝对路径，默认 `/opt/trade` |
| `access` | 沿用服务器现有的 `private` 或 `public` 模式；此参数不用于切换模式 |

仓库 **Settings → Secrets and variables → Actions** 需要五项 Repository Secrets：`DEPLOY_HOST`（公网 IP / 域名）、`DEPLOY_PORT`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`（独立部署私钥完整内容）、`DEPLOY_KNOWN_HOSTS`（经可信渠道核验的 SSH 主机记录，非默认端口使用 `[主机]:端口` 格式）。部署账号需使用 Bash，能读当前成功清单、写入 `deploy/` 并使用 Docker / Compose。沿用前述密钥登录验证方式，保持主机指纹校验开启。

镜像发布到腾讯云 TCR 个人版，固定地址为 `ccr.ccs.tencentyun.com/tiancia-trade/<服务>:sha-<提交SHA>-<运行ID>-<尝试次数>`，默认构建 `linux/amd64`。在已创建的 `tiancia-trade` 命名空间下准备四个私有仓库：`backend`、`cockpit`、`x-console`、`egress`。个人版不支持多级仓库路径，地址中只有一层命名空间，不再使用 GHCR 的仓库所有者/仓库名路径。命名与登录方式见 [TCR 个人版快速入门](https://cloud.tencent.com/document/product/1141/63910)。

除上述五项 SSH Secrets 外，再配置两项 Repository Secrets：`TCR_USERNAME`（复制个人版控制台登录指令中 `--username` 的值）和 `TCR_PASSWORD`（个人版初始化或重置的镜像仓库登录密码）。工作流在 runner 上明确检查两项都存在，再通过 `docker login --password-stdin` 登录。`GITHUB_TOKEN` 仅用于读取本仓库 Actions artifact 和代码，不需要 `packages: write` 权限。

Actions 上传镜像时通过伪终端保留 Docker 的逐层字节进度，同一层最多每 10 秒打印一次上传进度，完成和错误即时输出。该进度是 Docker 报告的未压缩字节量，不能直接当作公网传输速率。整次任务沿用工作流的 90 分钟上限，不另设更短的单镜像上传上限，以免中断仍在推进的首次上传。上传失败时不生成成功镜像清单或进入部署；本地运行仍沿用普通 `docker push`。

勾选部署后，工作流仅经 SSH 上传完整镜像清单和本次清理脚本，由服务器登录 TCR、拉取选中服务镜像、重建并等待健康检查。TCR 用户名和密码通过 SSH 标准输入传入；服务器的 Docker 凭据写入独立临时目录，成功或失败退出都会清理，不覆盖服务器已有的 Docker 登录配置。`all` 仍使用 `release.sh` 的完整部署流程，拉取基础设施镜像并完成全部镜像检查后才切换容器。

服务器 Docker daemon 的代理例外需覆盖 `ccr.ccs.tencentyun.com` 及实际镜像层重定向域名，并验证这些请求经腾讯云内网访问。仅在 shell 中设置代理、或仅看到仓库入口返回 `401`，不能证明 daemon 的镜像层路径和下载速度正常；实际域名、解析与链路应在服务器核验。

调整 Docker daemon 的代理例外前，先核对 `docker info` 中现有 `No Proxy` 与 `live-restore` 状态，保留已有例外，再追加实际 registry 与镜像层重定向域名；不要照抄其他主机的 COS 域名或覆盖已有配置。未启用 `live-restore` 时，重启 Docker 会中断运行中的容器，应在维护窗口生效并核对容器状态。

配置生效后以首次私有 TCR 镜像的真实 `docker pull` 和健康检查作为验收；公开层测速不等于私有仓库鉴权、推送和完整发布已经通过。

部分构建会先从服务器下载真实的 `deploy/releases/current.env`，仅替换选中服务的镜像，下载失败即中止；即使只构建不部署，部分构建也需要这五项 SSH Secrets 和成功基线。已有部署可以逐个服务迁移到 TCR，未选中的现有镜像仍保留。没有成功基线的新服务器，先选 `all` 构建，并完成前述服务器初始化，再执行全量部署。

每次构建成功后，运行摘要列出完整镜像清单，并提供 `image-manifest-<版本>` artifact 下载 `release.env`。迁移到 TCR 的首轮必须勾选 `build`，先生成 TCR 镜像和新清单；可先不勾选 `deploy`，确认推送成功及实际镜像层下载正常后再部署。部署成功日志包含 `DEPLOYMENT_OK`。工作流按同一并发组串行运行，不取消正在执行的发布；服务端仍由 `release.sh` 加锁，并在部分部署时重新读取当前版本，保留未选服务的新版本。

仅部署已有镜像时，在同一个 **Run workflow** 中选择分支和 `service`，**取消勾选 `build`、勾选 `deploy`**，`source_run_id` 留空即可。工作流下载已有 `release.env`、上传清单，再由服务器从 TCR 拉取镜像、重建选中服务并等待健康检查，跳过 Maven/npm/Docker 构建及镜像推送。新代码尚未构建时，需要先构建一次，才能用这个入口部署。

这里的“最新”按清单 artifact 的生成时间选择，限定为所选分支、同仓库和本工作流已结束的运行，并要求所有选中服务确实在该次运行中构建、地址严格匹配 `ccr.ccs.tencentyun.com/tiancia-trade/<服务>:<版本>`；不会使用旧 GHCR 清单或从旧基线继承的选中镜像。因此，`backend` 可以复用后端单独构建或全量构建，`frontends` 要求两个前端曾一起构建，`all` 要求一次全量构建。构建并上传清单后，即使随后部署失败，也可复用该清单重试部署。摘要会列出实际版本和源运行链接；`source_run_id` 可固定某次构建，仍须满足分支和服务匹配要求。

清单 artifact 保留 30 天；自动选择只考虑尚未过期的清单，没有匹配项即报错。镜像使用明确版本号，不使用可变的 `latest` tag；若对应 TCR 镜像已被删除，拉取失败会在停止容器之前中止。每次部署使用独立的 incoming 目录，可以重复部署同一版本。

勾选 `deploy` 后，只有发布成功才会自动清理更早的本地 Trade 应用镜像：保留 `current.env`、`previous.env` 引用的全部镜像，以及任何运行中或已停止容器引用的镜像及同一镜像 ID 的别名。候选仅限 `ccr.ccs.tencentyun.com/tiancia-trade/`、`trade/`、`trade-local/` 下的 `backend`、`cockpit`、`x-console`、`egress`，不会删除基础设施、第三方镜像、容器、数据卷或远端仓库中的历史版本，也不使用强制删除或全局 prune。这里的“上一代”指整套发布的上一成功清单，不保证每个服务各有两个不同版本。

工作流会将本次 [cleanup-images.sh](scripts/cleanup-images.sh) 一同上传到独立的 incoming 版本目录，无需另行更新服务器的清理脚本。清理重新获取发布锁、读取最新清单，并在完成所有校验和 Docker 查询后才删除；缺少上一成功清单（如首次发布）、清单损坏、Docker 查询失败或发布锁被占用时均不执行删除。清理失败只产生 warning，不把已经成功的部署改判为失败；成功日志包含 `IMAGE_CLEANUP_OK`。

需要手动预览时，在服务器运行本次 incoming 目录下的脚本（替换版本目录和仓库命名空间）；默认不删除，加 `--apply` 才执行：

```bash
bash /opt/trade/deploy/incoming/<版本>/cleanup-images.sh \
  --root /opt/trade --registry ccr.ccs.tencentyun.com/tiancia-trade
```

服务器须事先具备匹配的 Compose、`release.sh`、Nginx、运行配置和密钥文件；工作流只传完整镜像清单及本次清理脚本，不同步这些部署配置文件。前后端接口存在联动变更时，应安排兼容的发布顺序或全量更新。发布会短暂停止并重建选中服务；失败返回非零，不自动回滚，沿用下文的显式回滚方式。数据库补丁、运行配置变更和访问模式切换仍按各自流程处理。

### 一个命令构建并上传

在电脑的 Git Bash / WSL 中使用 [build-upload.sh](scripts/build-upload.sh)。首次把 SSH 目标保存到 Git 忽略的本地文件，替换示例中的账号和服务器地址：

```bash
printf '%s\n' 'deploy-user@国内服务器IP' > deploy/upload-server.local
```

以后在仓库根目录按服务执行：

```bash
bash deploy/scripts/build-upload.sh backend
bash deploy/scripts/build-upload.sh cockpit
bash deploy/scripts/build-upload.sh x-console
bash deploy/scripts/build-upload.sh backend cockpit
bash deploy/scripts/build-upload.sh all
```

脚本自动生成 UTC 时间加进程号组成的版本号。部分构建会先下载目标服务器的 `deploy/releases/current.env`，随后调用已有构建脚本、只导出选中的镜像，并将 `images.tar.gz`、完整 `release.env` 和 `SHA256SUMS` 上传到 `/opt/trade/deploy/incoming/<版本号>/`，远端校验成功后才报告完成。基线下载失败、构建或传输失败时会中止，本地产物保留用于排查；不会回退到本地旧清单。

默认本地产物放在仓库同级的 `trade-deployments/<版本号>/`；仓库位于 `A:\trade` 时，即 `A:\trade-deployments\<版本号>\`。可通过 `--output-dir` 修改版本目录的父目录，通过 `--tag` 指定版本号。Git Bash 默认优先使用 Windows 自带的 OpenSSH，沿用 Windows 中已有的配置和 agent；WSL/Linux 使用 PATH 中的 SSH。主机指纹校验保持开启，首次连接先通过正常 SSH 核对主机指纹。可选 `--server 用户@主机`、`--port 端口`、`--identity 私钥路径`、`--remote-root /服务器仓库路径` 和 `--platform linux/arm64`。`--server` 优先于 `TRADE_DEPLOY_SERVER`，后者优先于本地目标文件；`--port` 未传时沿用 SSH 配置。需要自定义 SSH/SCP 程序时，可分别设置 `TRADE_SSH_COMMAND`、`TRADE_SCP_COMMAND` 为程序路径。

默认只构建、上传和校验，结束时打印服务器加载、校验和发布的命令，容器重建由你在服务器单独执行。加 `--deploy` 后，SHA256 校验成功即通过 SSH 依次加载镜像、执行 `release.sh validate` 和 `release.sh deploy --no-pull`，重新创建本次选中的服务并等待健康检查；`all --deploy` 执行全量发布。Windows CMD 可直接使用本机 Git Bash：

```cmd
"C:\git\Git\bin\bash.exe" deploy/scripts/build-upload.sh backend --deploy
"C:\git\Git\bin\bash.exe" deploy/scripts/build-upload.sh backend cockpit --deploy
"C:\git\Git\bin\bash.exe" deploy/scripts/build-upload.sh all --deploy
```

访问模式通过 `--access private|public` 指定，默认读取本地 `DEPLOY_ACCESS_MODE`，未设置时使用 `private`。公共 HTTPS 部署示例：

```cmd
"C:\git\Git\bin\bash.exe" deploy/scripts/build-upload.sh backend cockpit --deploy --access public
```

自动部署默认等待健康检查 300 秒，可通过本地环境变量 `DEPLOY_WAIT_TIMEOUT` 调整。任一步失败即返回非零，不自动重试或回滚。服务器须事先具备 `release.sh`、Compose 文件和所需配置；脚本不会自动同步代码或配置，若本次更改了 Compose、Nginx 或部署脚本，先同步对应部署文件再发布。全量离线发布仍要求服务器已有 MySQL、Redis、Nginx 镜像。仅上传后手工发布时也要使用相同访问模式。`--help` 可查看完整参数。

### 分步构建和发布

每次修改代码后，在构建机生成新版本镜像和清单，传输对应代码 / 部署文件与新清单到服务器。可以用 `--output` 保留旧清单：

```bash
# 构建机：下载服务器当前成功清单，作为未更新服务的版本基线
scp deploy-user@国内服务器IP:/opt/trade/deploy/releases/current.env deploy/base-release.env

# 只构建并推送后端
bash deploy/scripts/build-images.sh --registry registry.example.com/trade \
  --tag 20261019-b2c3d4e --base deploy/base-release.env \
  --output deploy/new-release.env --push backend

# 服务器：只更新后端；其他镜像记录保持现有版本
bash deploy/scripts/release.sh deploy backend --release deploy/new-release.env

# 回到上一次成功版本；首次成功部署还没有 previous，不能用此命令
bash deploy/scripts/release.sh rollback

# 明确恢复某份历史镜像清单
bash deploy/scripts/release.sh rollback --release deploy/releases/某份历史清单.env
```

构建命令末尾可选 `backend`（后端）、`cockpit`（交易驾驶舱）、`x-console`（X 控制台）、`egress`（代理隧道），也可组合，例如 `backend cockpit`。只构建前端时将示例末尾的 `backend` 换为对应前端服务名；发布时也指定相同服务。`--push` 只推送本次构建的镜像。不写服务名仍按原方式全量构建；全量发布同样省略发布命令的服务名。

部分构建的 `--base` 默认读取本地 `deploy/release.env`；没有完整基线时会在构建前报错。建议每次从目标服务器下载当前成功清单，并用 `--base` 显式指定，保留其他服务实际部署的镜像引用。基线只接受镜像清单字段，不要传入基础设施 `.env` 或业务 `backend.env`。基线与输出可以是同一文件，但建议用 `--output deploy/new-release.env` 保留原清单。

单独更新前端用 `cockpit` 或 `x-console`；只更换网关配置或密码可执行 `deploy gateway`，更换隧道 key / VPS 地址可执行 `deploy egress`。选中的应用始终重建，单独发布不会启动或重建其依赖，首次必须先完成全量部署。

`deploy/releases/current.env` 保存当前成功镜像，`previous.env` 保存前一成功版本，还有时间戳快照。失败保持 `current.env`；失败的回滚目标保存在 `target-*.env`，可以显式传入重试。成功回滚后再次回滚会切回刚才版本。一次只允许一个发布进程运行。

镜像回滚不会撤销数据库变化、已经发生的发帖、当前 `backend.env` 或仓库内网关配置。更新代码同时更改部署配置时，也要自行保留并恢复相应配置版本；保留历史镜像仓库 tag，不要清理掉回滚需要的镜像。

以上命令默认私有模式。访问模式不存入镜像清单；使用后文公共模式时，每次校验、发布、单服务更新和回滚均显式添加 `--access public`。

### 没有镜像仓库时

构建不加 `--push`，然后只导出本次构建的镜像。下面接续前面的后端部分构建示例：

```bash
# 构建机：只打包后端；上传 tar、新清单和本次改动的部署文件
docker save -o trade-images.tar registry.example.com/trade/backend:20261019-b2c3d4e

# 服务器
docker load -i trade-images.tar
bash deploy/scripts/release.sh deploy backend --release deploy/new-release.env --no-pull
```

单独构建其他服务时，替换镜像引用和发布服务名；组合构建就显式列出选中的所有镜像及服务。全量构建时导出四个镜像，全量发布时省略服务名。完整清单中未更新的镜像引用继续沿用基线版本，不需要为单服务更新重复打包、上传它们。

`--no-pull` 会在停止任何应用前确认所有所需镜像已在本地；首次全量还需要 mysql、redis、nginx 镜像（可先 `dc pull mysql redis gateway` 或一起 save/load）。离线回滚同样加 `--no-pull`。只有应用镜像离线不代表 npm / Maven 首次构建也能离线。

## 数据、备份和监控

生产使用独立命名卷：默认 `trade-production_mysql`、`trade-production_redis`、`trade-production_backend`。后者持久化整个 `/app/data`，保留策略、近期决策和原子替换的 `trading-state.json`；资金、订单、X 草稿和审核状态在 MySQL。不要挂载单个 JSON 文件，不要执行 `down -v`。现有 Windows 数据不会自动迁移。

第一次启动空库会执行现有 `CREATE TABLE IF NOT EXISTS` schema；旧库不会自动补列或索引。涉及 schema 的版本发布前，先按 [数据库升级说明](../backend/src/main/resources/db/README.md) 做适用性检查和备份，手工执行对应补丁；数据库恢复需单独安排，应用镜像回滚不能替代它。

简单的停机一致性备份示例（沿用上面的 `dc` 函数，卷名按自己的前缀调整）：

```bash
umask 077
mkdir -p backups
dc stop backend
dc exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --single-transaction --no-tablespaces -u "$MYSQL_USER" "$MYSQL_DATABASE"' \
  > "backups/mysql-$(date -u +%Y%m%dT%H%M%SZ).sql"
docker run --rm --mount source=trade-production_backend,target=/data,readonly \
  --mount type=bind,src="$PWD/backups",dst=/backup alpine:3.22 \
  tar -czf /backup/backend-data.tgz -C /data .
dc start backend
```

确认命令成功后再继续更新。备份可能包含账户及内容数据，应加密保存到服务器之外，并定期验证恢复。另行安全备份运行配置和密钥，不能放进代码仓库。

默认不开 Prometheus / Grafana。有足够余量时先在 `deploy/.env` 填强 `GRAFANA_ADMIN_PASSWORD`，再执行：

```bash
dc --profile monitoring up -d --no-deps prometheus grafana
```

监控只监听宿主机回环 `127.0.0.1:9090` 和 `127.0.0.1:3001`，通过 SSH 本地转发访问。复用现有交易告警规则和仪表盘；默认保留七天指标。发布脚本不更新这两个可选服务，数据库 / 缓存 / 监控的版本升级单独安排。

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
```

证书文件使用 644、私钥使用 root 所有的 600。复制真实文件，避免把指向容器挂载目录外部的符号链接放进来。公共模式的路径可用 `TLS_DIRECTORY`、`ACME_DIRECTORY` 覆盖，均相对于仓库根目录。

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

浏览器改为访问两个 `https://` 域名，仍需网关密码；HTTP 仅处理证书验证和跳转 HTTPS。私有模式的回环 Web 端口会被替换，不与公共端口同时发布。

切换私有 / 公共模式时保留相同项目名、数据卷前缀和运行配置，并执行目标模式的**全量发布**，不要只更新 `gateway` 或某个单服务，因为后端的来源配置也随模式变化。切到公共模式用 `deploy --access public`；切回私有模式用 `deploy --access private`。访问模式不存入镜像历史，镜像回滚不会恢复旧访问模式，回滚时须明确选择当前要使用的模式。

## 验证边界

每次发布前，应在隔离环境检查所选访问模式的 Compose 配置、Linux Nginx `-t`、发布脚本和受影响的应用测试；配置校验使用虚构凭据，不读取或输出生产密钥。发布完成后，重新核对当前运行镜像、容器健康、业务代理、行情和审核状态。

离线构建、配置解析和进程健康均不能代替业务验收。PAPER 决策、Telegram 审核和 X 发布状态按 [上手指南的验收步骤](../docs/PROJECT_GUIDE.md) 分别核对。公共模式还需另外验收域名、证书和续期，不能沿用私有模式的成功结论。

驾驶舱 HTML 测试覆盖 Node SSR 请求处理函数和 Cloudflare Worker 的 `fetch` 导出。离线内存渲染与接口契约测试、Linux 镜像构建，仍不能证明生产环境已加载对应镜像或外部账户权限已配置正确。
