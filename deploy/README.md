# Ubuntu 生产部署

本方案部署后端、交易驾驶舱和 X 控制台。代码在构建机打包成带版本号的 Linux 镜像，国内 Ubuntu 只负责拉取和运行；后续更新继续使用同一套发布脚本。

```text
浏览器 ── HTTPS + 网关密码 ── 国内 gateway
                               ├─ trade.example.com → cockpit:3000
                               ├─ x.example.com     → x-console:8080
                               └─ /api/             → backend:8080
                                                        ├─ mysql:3306 / redis:6379
                                                        ├─ DeepSeek：直接访问
                                                        └─ egress:13128
                                                             └─ 加密 SSH 隧道 → 德国 127.0.0.1:3128 Squid
                                                                                 └─ OKX / Telegram / X
```

公网只开放网关的 80 / 443 和受限制的 SSH 管理入口；数据库、Redis、后端、两个前端和代理端口都不发布到宿主机。HTTP 只用于跳转 HTTPS 和证书验证。两个前端各占一个域名的根路径。

## 先准备这些内容

1. 国内 Ubuntu 上安装 [Docker Engine + Compose 插件](https://docs.docker.com/engine/install/ubuntu/)，Compose 至少 **2.30**（使用 `env_file.format: raw`）。执行 `docker version`、`docker compose version` 确认 Linux Engine 可用。部署脚本依赖 Bash 和 Ubuntu 的 `flock`。
2. 两个域名解析到国内服务器，并准备同时覆盖两个域名的 TLS 证书。示例是 `trade.example.com` 和 `x.example.com`，需换成自己的。安全组允许 80 / 443；SSH 限定管理来源。
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
mkdir -p deploy/secrets/tls deploy/acme
chmod 700 deploy/secrets deploy/secrets/tls
chmod 600 deploy/.env deploy/backend.env
```

编辑 `deploy/.env`：

| 必填项 | 用途 |
| --- | --- |
| `COCKPIT_DOMAIN`、`X_CONSOLE_DOMAIN` | 两个实际域名，只填主机名，不带协议、路径或端口 |
| `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD` | 分别设置长随机密码，生产无缺省密码 |
| `EGRESS_SSH_HOST`、`EGRESS_SSH_PORT`、`EGRESS_SSH_USER` | 德国 VPS 的实际 SSH 地址、端口、专用用户 |

路径相对于仓库根目录。`COMPOSE_PROJECT_NAME`、`DATA_VOLUME_PREFIX` 在首次部署后保持固定，否则会切换容器项目或数据卷。`deploy/.env` 遵循 Compose env 语法，密码含 `$` 时使用单引号包住，避免插值。

编辑 `deploy/backend.env`，填好：

- `DEEPSEEK_API_KEY`；
- `TRADE_TELEGRAM_BOT_TOKEN`、`TRADE_X_TELEGRAM_CHAT_ID`、`TRADE_X_TELEGRAM_REVIEWER_USER_IDS`（多个审核人用逗号分隔）；
- `TRADE_X_TARGET_USER_ID`，以及 `X_API_KEY`、`X_API_SECRET`、`X_ACCESS_TOKEN`、`X_ACCESS_TOKEN_SECRET`；
- 两个独立的长随机管理令牌 `TRADE_TRADING_OPERATOR_TOKEN`、`TRADE_X_ADMIN_TOKEN`。

`backend.env` 使用 **raw** 格式：每行 `KEY=value`，不要给值额外加引号，不要 `source`，也不要放 shell 命令。它只在运行时注入后端，不参与镜像构建。

模板已按本次要求配置：OKX 自动策略 **PAPER**、真实下单关闭；X 自动生成、Telegram 人工审核和真实发布开启，每轮完成后等三小时；首次生成约 30 秒后开始。Polymarket、微博、小说任务关闭。DeepSeek 使用 `deepseek-flash` 直连；OKX、Telegram、X 使用隧道代理。容器地址由 Compose 强制覆盖为 `egress:13128`，不要填写 `127.0.0.1`。

PAPER 不需要 OKX 交易凭据，三个 `OKX_*` 可留空。缺少 X 目标账号或必需的审核配置会拒绝启动；AI / X 凭据、账号权限和外部连接仍需运行验收，进程健康不代表这些已验证。现有数据库中已批准、未过期且审核有效的草稿可能在启动后的扫描中发布。不要让旧电脑进程和服务器同时消费同一个 Telegram Bot；迁移前停止旧的审核 / 生成 / 发布任务。X 控制台本身是只读页面，审核在 Telegram 完成，浏览器查询另需输入 X 管理令牌。

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

## 3. 放入 TLS 证书

将覆盖两个域名的证书链和私钥放为：

```text
deploy/secrets/tls/fullchain.pem
deploy/secrets/tls/privkey.pem
```

证书文件使用 644、私钥使用 root 所有的 600。复制真实文件，避免把指向容器挂载目录外部的符号链接放进来。

若用 Certbot，首次网关尚未启动且 80 空闲时可用 [standalone 模式](https://eff-certbot.readthedocs.io/en/stable/using.html#standalone)：

```bash
sudo certbot certonly --standalone --cert-name trade.example.com \
  -d trade.example.com -d x.example.com
sudo install -m 644 /etc/letsencrypt/live/trade.example.com/fullchain.pem deploy/secrets/tls/fullchain.pem
sudo install -m 600 /etc/letsencrypt/live/trade.example.com/privkey.pem deploy/secrets/tls/privkey.pem
```

Certbot 的安装与邮箱 / 服务条款按其官方流程完成。网关上线后不要让 standalone 续期与网关争抢 80；可改用 webroot `/opt/trade/deploy/acme`（已挂载并允许验证路径）。续期成功后仍须复制新证书并 reload 网关。部署脚本不申请或自动续期证书，投产前应配置 Certbot timer 和相应 deploy hook。

## 4. 构建镜像并发布

在电脑的 WSL / Linux 构建机或 CI 中执行，Docker Desktop 需启动 **Linux Engine**。不需要先手工生成 JAR 或 dist，Dockerfile 会完成 Maven 测试 / 打包和两个前端构建：

```bash
bash deploy/scripts/build-images.sh \
  --registry registry.example.com/trade \
  --tag 20261016-a1b2c3d --push
```

将仓库命名空间和版本号换成实际值。默认生成 `linux/amd64`，ARM 服务器用 `--platform linux/arm64`。每次发布用新版本号，不复用 tag，不使用 `latest`。构建产物是 backend、cockpit、x-console、egress 四个镜像，以及只有镜像引用的 `deploy/release.env`；后者可传给服务器，不包含业务密钥。

后端构建上下文是仓库根，由 `backend/Dockerfile.dockerignore` 白名单限制；两个前端和隧道各有独立上下文。实际 `.env`、密钥、数据目录和历史构建产物不进入镜像。前端有独立 Docker Vite 配置，原本地 / Cloudflare 开发流程保持不变。

把对应版本代码和生成的 `deploy/release.env` 放到服务器。服务器上的 `.env`、`backend.env`、`secrets/` 和数据卷保持原地，再执行：

```bash
cd /opt/trade
bash deploy/scripts/release.sh validate
bash deploy/scripts/release.sh deploy
```

脚本先校验并拉取全部所需镜像，然后确保 MySQL / Redis 健康，再停止旧应用、重建选中的应用并等待健康检查。默认更新五个应用服务；基础服务保持已有容器，正常发布不更新数据库版本、不运行手工迁移、不删除数据卷。首次部署会创建它们。

发布采用单实例短暂停机。失败返回非零；如果失败发生在停止之后，部分应用可能尚未启动，应查看日志并显式重试或回滚，不会自动继续真实发布。健康检查只证明进程就绪：隧道监听成功不能证明德国 Squid、OKX、Telegram 或 X 已联通。

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
curl -fsS -u operator 'https://trade.example.com/api/trading/market/candles?instId=BTC-USDT&bar=5m&limit=2'
curl -fsS -u operator https://trade.example.com/api/automation/tasks
```

浏览器打开两个域名，输入网关密码；X 控制台再输入 `TRADE_X_ADMIN_TOKEN`。确认行情、运行状态和 X 草稿列表加载正常；确认策略运行模式为 PAPER。再完成一次真实 AI 生成 → Telegram 审核 → X 发布的人工验收，这一步会产生 AI 费用和真实 X 发帖。上线配置开关已经开启，请自行选择要批准的草稿。

`/healthz` 只报告网关，不能代表业务验收；公开网关没有转发 `/actuator/`。backend 自检在容器内部访问 `/actuator/health/readiness`。

## 后续更新和回滚

每次修改代码后，在构建机生成新版本镜像和清单，传输对应代码 / 部署文件与新清单到服务器。可以用 `--output` 保留旧清单：

```bash
# 构建机
bash deploy/scripts/build-images.sh --registry registry.example.com/trade \
  --tag 20261017-b2c3d4e --output deploy/new-release.env --push

# 服务器：只更新后端；其他镜像记录保持现有版本
bash deploy/scripts/release.sh deploy backend --release deploy/new-release.env

# 或更新全部应用
bash deploy/scripts/release.sh deploy --release deploy/new-release.env

# 回到上一次成功版本；首次成功部署还没有 previous，不能用此命令
bash deploy/scripts/release.sh rollback

# 明确恢复某份历史镜像清单
bash deploy/scripts/release.sh rollback --release deploy/releases/某份历史清单.env
```

单独更新前端用 `cockpit` 或 `x-console`；只更换网关配置、密码或证书可执行 `deploy gateway`，更换隧道 key / VPS 地址可执行 `deploy egress`。选中的应用始终重建，单独发布不会启动或重建其依赖，首次必须先完成全量部署。

`deploy/releases/current.env` 保存当前成功镜像，`previous.env` 保存前一成功版本，还有时间戳快照。失败保持 `current.env`；失败的回滚目标保存在 `target-*.env`，可以显式传入重试。成功回滚后再次回滚会切回刚才版本。一次只允许一个发布进程运行。

镜像回滚不会撤销数据库变化、已经发生的发帖、当前 `backend.env` 或仓库内网关配置。更新代码同时更改部署配置时，也要自行保留并恢复相应配置版本；保留历史镜像仓库 tag，不要清理掉回滚需要的镜像。

### 没有镜像仓库时

构建不加 `--push`，然后在构建机 `docker save -o trade-images.tar` 并显式列出清单中的四个镜像；把 tar、代码和 `release.env` 传到服务器：

```bash
docker load -i trade-images.tar
bash deploy/scripts/release.sh deploy --no-pull
```

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

## 本次验证边界

已做离线后端测试、两个前端 Docker 配置构建、X 前端测试、Nginx 配置语法检查、Compose 配置解析以及隔离的发布脚本模拟验证。当前电脑的 Docker Linux Engine 未运行，因此没有完成实际镜像构建和 Linux 容器运行验收；服务器仍需按上面的步骤检查网络、证书、内存和外部服务。未使用真实凭据调用 AI、Telegram、X 或交易接口。

驾驶舱现有 HTML 测试的第一项依赖 Cloudflare `worker.fetch`，不能直接验证 Node 构建；本次另做了隔离的 Node SSR 首页检查。vinext 在原生 Windows 上的静态资源缓存还有路径分隔符差异，模拟 Linux 路径后资源检查通过，但不能替代真正的 Linux 镜像验收。额外的本地 Nginx HTTP 冒烟服务启动被自动批准审查拒绝，返回原因只有“策略阻止”；因此只完成了 Nginx 配置语法验证。
