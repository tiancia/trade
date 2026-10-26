# 项目架构与部署上手指南

[返回文档总导航](README.md) · [后端模块目录](backend/MODULES.md) · [生产部署说明](../deploy/README.md)

面向第一次接手项目的开发者和运维人员。先读第 1～4 节理解系统与通信，再按第 7 节连接现有环境；修改代码、发布与排障分别见第 5、8、9 节。

代码事实以当前仓库为准，运行配置以服务器上的配置和发布记录为准。本文不保存真实主机地址、密码或令牌；示例中的 `CN_HOST`、`DE_HOST` 需要替换为交接提供的地址。

每次接手环境或完成部署后，按第 7 节重新核验容器、代理、行情和业务状态。源码、配置模板与文档不能证明服务器已运行相应版本，也不能代替 PAPER 决策、Telegram 审核和 X 发布状态的独立验收。

## 1. 先看整体：三台机器各做什么

业务后端、数据库和两个管理前端都在国内 Ubuntu 上运行。德国 VPS 提供网络出口。电脑负责开发和通过 SSH 访问管理页面，CNB 负责构建镜像并发布。

| 位置 | 运行内容 | 职责 |
| --- | --- | --- |
| 开发者电脑，当前为 Windows | 浏览器、SSH 客户端、源码 | 开发代码、手动提交 CNB 任务、访问服务器管理页面 |
| 国内 Ubuntu 主机 | Docker Engine + Compose、后端、两个前端、Nginx 网关、MySQL、Redis、egress 隧道容器 | 执行策略与内容工作流，保存业务数据，提供管理 API |
| 德国 VPS | sshd、Squid | 接收国内主机建立的 SSH 隧道，由 Squid 转发外部请求 |

```mermaid
flowchart LR
    subgraph PC[开发者电脑]
        Browser[浏览器 localhost:18080 / 18081]
        LocalSSH[SSH 本地端口转发]
        Browser --> LocalSSH
    end
    subgraph CN[国内 Ubuntu]
        CNSSH[sshd]
        Gateway[Nginx gateway]
        Frontends[cockpit / x-console]
        Backend[Spring Boot backend]
        Data[MySQL / Redis / 持久化卷]
        Egress[egress 容器 HTTP 代理入口 13128]
        CNSSH --> Gateway
        Gateway -->|页面| Frontends
        Gateway -->|/api/| Backend
        Backend --> Data
        Backend -->|显式使用代理| Egress
    end
    subgraph DE[德国 VPS]
        DESSH[sshd]
        Squid[Squid 127.0.0.1:3128]
        DESSH --> Squid
    end
    LocalSSH -->|电脑主动建立 SSH| CNSSH
    Egress -->|国内容器主动建立 SSH| DESSH
    Squid --> External[OKX / Telegram / X]
    Backend -->|应用配置直连| DeepSeek[DeepSeek API]
```

德国 VPS 不承载本项目后端和数据库；它只提供当前方案中的代理出口。电脑关闭或访问隧道断开，不会停止国内主机上的后台任务。

## 2. 三条通信链路，分别理解

### 2.1 电脑如何访问国内主机上的页面

电脑主动 SSH 登录国内主机，同时建立本地端口转发：

```text
电脑浏览器 127.0.0.1:18080
  → 电脑 SSH 客户端
  → 公网中的加密 SSH 连接
  → 国内主机 sshd
  → 国内主机 127.0.0.1:18080
  → gateway 容器 8080
  → 页面走 cockpit:3000，/api/ 走 backend:8080
```

X 控制台同理，使用电脑和国内主机上的 18081，进入 gateway 的 8081，再读取 x-console:8080。两个前端的 API 都转发给同一个 backend。

关键命令的含义：

```text
-L 127.0.0.1:18080:127.0.0.1:18080
   └─电脑上的监听地址─┘ └─从国内主机连接的目标─┘
```

两个 `127.0.0.1` 分别属于两台机器。浏览器访问 localhost 时，TCP 流量会被电脑上的 SSH 客户端接走，再从国内主机连接网关。浏览器没有直接请求国内主机公网 IP 的 18080。

浏览器读取页面后，JavaScript 再请求同源 `/api/`；生产模式由 Nginx 转发 API，不需要为浏览器填写后端容器地址。驾驶舱行情增量推送使用 **SSE**，不是浏览器与后端之间的 WebSocket。SSE 也经过同一条 SSH 隧道。

网页地址使用本机 HTTP，跨公网的这段传输由 SSH 加密；网关和后端之间使用 Docker 网络内的 HTTP。本方案默认面向获得 SSH 访问权限的管理人员，不是公开网站。

### 2.2 国内后端如何通过德国 VPS 请求外部服务

backend 按客户端配置连接 `http://egress:13128`。egress 是 Docker 服务名，13128 是其容器内监听端口。

```text
backend 中的 OKX / Telegram / X 客户端
  → HTTP 代理 egress:13128
  → egress 容器建立的 SSH 连接
  → 德国 VPS sshd
  → 德国 VPS 127.0.0.1:3128 的 Squid
  → 对应外部 HTTPS 服务
```

egress 的核心转发参数来自 [entrypoint.sh](../deploy/egress/entrypoint.sh)：

```text
ssh -NT -g -L 0.0.0.0:13128:127.0.0.1:3128 trade-proxy@DE_HOST
```

这里的本地是 **egress 容器**，远端是德国 VPS。`0.0.0.0:13128` 允许同一 Docker 网络内的 backend 连接，但 Compose 没有把它发布到宿主机或公网。

SSH 通道只转发 TCP 字节，不执行交易逻辑。Squid 接收代理协议并建立目标连接。对于当前设计中的 HTTPS 目标，客户端经 HTTP CONNECT 建立隧道，再与目标服务进行 TLS 通信；SSH 额外保护国内到德国这一段。仓库没有管理 VPS 的 Squid 配置，本文不能替代对实际 VPS 配置的核验。

响应沿已建立的连接返回，不需要德国 VPS 主动连接国内主机。德国专用用户 `trade-proxy` 的权限目标是仅允许转发到 `127.0.0.1:3128`；部署中使用公钥认证，并核验德国主机指纹。容器 SSH 退出后由 Docker 重启重连。

| 外部服务 | 当前生产模板中的出站方式 | 配置开关 |
| --- | --- | --- |
| OKX REST | egress → 德国 Squid | `TRADE_OKX_PROXY_ENABLED=true` |
| Telegram Bot API | egress → 德国 Squid | `TRADE_TELEGRAM_PROXY_ENABLED=true` |
| X API | egress → 德国 Squid | `TRADE_X_PROXY_ENABLED=true` |
| DeepSeek | 按应用配置直连 | `TRADE_AI_PROXY_ENABLED=false` |

Compose 将前三个客户端的代理地址强制设置为 `egress:13128`。这套配置不会自动代理所有外部网站；新增客户端时要检查该客户端的代理能力。backend 中填写 `127.0.0.1:13128` 会指向 backend 容器自己，而不是 egress。

Telegram 审核使用后端主动 `getUpdates` 轮询，用户在 Telegram 点击的审核操作由轮询取回；当前不依赖公网 webhook，也不需要 Telegram 直接连入国内服务器。

### 2.3 Docker 拉镜像为什么还有另一条隧道

需要通过代理拉取镜像的国内宿主机，可使用独立的 `trade-docker-egress.service` 为 Docker daemon 提供出口：

```mermaid
flowchart LR
    Daemon[国内宿主机 Docker daemon]
    HostPort[宿主机 127.0.0.1:13128]
    HostSSH[systemd 管理的 SSH 隧道]
    VPS[德国 VPS sshd → Squid:3128]
    Registry[镜像仓库 / 鉴权 / 镜像层下载]
    Daemon -->|HTTP_PROXY / HTTPS_PROXY| HostPort
    HostPort --> HostSSH --> VPS --> Registry
```

| 隧道 | 建立位置和维护者 | 谁使用 |
| --- | --- | --- |
| 电脑访问隧道 | 电脑 SSH 进程，人工启动 | 浏览器访问两个管理页面 |
| 业务出站隧道 | egress 容器，Docker 管理 | 后端的 OKX、Telegram、X 客户端 |
| Docker 拉镜像隧道 | 国内宿主机 systemd 服务 | Docker daemon 拉镜像 |

宿主机和 egress 容器可以同时使用 13128，因为它们属于不同的网络空间。宿主机 Docker 代理配置位于 `/etc/systemd/system/docker.service.d/trade-proxy.conf`；这个文件和 `trade-docker-egress.service` 本身**不在仓库中**，新机器需要另行交接或配置。服务是否启用及当前健康状态，应按第 7 节检查。

Docker daemon 的代理不会自动成为 Java 客户端代理，也不能保证 CNB 构建中的 npm/Maven 下载已使用代理。当前由 CNB 构建并推送 TCR；生产服务器拉取失败时，检查 daemon 的仓库及镜像层下载链路。

## 3. 服务、端口和网络边界

生产入口是 [compose.prod.yaml](../compose.prod.yaml)，默认七个服务。根目录的 [compose.yaml](../compose.yaml) 主要供本地基础设施使用，两者不要混用。

| 服务 | 容器内入口 | 国内宿主机入口 | 用途 |
| --- | --- | --- | --- |
| gateway | 8080 / 8081 | `127.0.0.1:18080` / `127.0.0.1:18081` | 网关登录、页面与 API 路由 |
| cockpit | 3000 | 不单独发布 | React、Next 兼容 API、vinext Node SSR 交易驾驶舱 |
| x-console | 8080 | 不单独发布 | Vite + 原生 HTML/CSS/JavaScript 的 X 只读控制台 |
| backend | 8080 | 不单独发布 | 一个 Spring Boot 进程，包含全部后端业务域 |
| mysql | 3306 | 不发布 | 业务数据库 |
| redis | 6379 | 不发布 | 可关闭的共享热行情缓存 |
| egress | 13128 | 不发布 | 业务出站 SSH 转发入口 |

国内和德国主机的 SSH 使用各自配置的端口，示例按 22 编写。德国 Squid 的目标是 `127.0.0.1:3128`，不需要向公网开放 3128。

Docker 网络分三组：`app` 连接网关、两个前端和后端；`data` 连接后端、MySQL、Redis，并设置 `internal: true`；`outbound` 连接后端与 egress。backend 同时加入三组。服务名只在相应 Docker 网络内解析，电脑浏览器不能直接访问 `backend:8080`。

Prometheus、Grafana、Alertmanager 归可选 `monitoring` profile，默认不启动；启用需先配置真实外部通知接收端。宿主机入口绑定回环：9090、3001、9093。公共域名/TLS 模式由 [compose.public.yaml](../compose.public.yaml) 提供，强制客户端证书与 Basic 口令；默认私有模式不叠加该文件。公共模式会替换网关端口，具体操作见部署说明。

## 4. 配置和认证：每份东西给谁用

### 4.1 运行配置、镜像版本与数据分开

| 文件或目录，服务器位于 `/opt/trade/` | 内容与作用 |
| --- | --- |
| `deploy/.env` | Compose 基础配置：数据库/Redis 密码、德国 SSH 地址、端口、资源限制等 |
| `deploy/backend.env` | 后端运行时业务变量：OKX、AI、Telegram、X 凭据和业务开关；raw 格式，不额外加引号 |
| `deploy/release.env` | 当前应用镜像引用；不放业务凭据 |
| `deploy/releases/` | 发布脚本保存的成功、前一版及目标镜像记录 |
| `deploy/secrets/` | egress 私钥、已核验的 known_hosts、网关密码哈希；不提交仓库 |
| Docker 命名卷 | MySQL 数据、Redis 数据、后端 `/app/data`，独立于容器生命周期 |

配置以 `.example` 模板创建，真实文件不会随代码自动生成。Compose 的 `environment` 可以覆盖 `backend.env` 中的同名项，例如容器代理地址和数据库连接。镜像构建不携带生产凭据；修改环境文件后需要**重新创建 backend**，仅 `docker compose restart` 不会重新加载 env 文件。

### 4.2 访问认证与外部服务凭据

| 认证 | 在哪里输入或使用 | 作用 |
| --- | --- | --- |
| 国内服务器 SSH 密码或登录密钥 | 电脑 SSH 客户端 | 登录、建立访问隧道、同步运行配置 |
| gateway 用户 `operator` 与网关密码 | 浏览器弹出的登录框；curl 的 `-u operator` | 进入私有 Web 网关 |

两个前端无需额外的应用令牌，X 控制台打开后自动读取内容。网关与后端分别校验个人 Basic 凭据；Authorization 仅向后端 API 转发，前端服务器不接收口令。后端管理 GET 和写接口均要求认证，未配置 bcrypt 凭据文件时返回 503；后端端口仍仅在容器网络内可达。网关包含限速与 JSON 审计，公网 HTTPS 额外强制受信客户端证书和 CRL；配置与凭据撤销见[部署说明](../deploy/README.md)。

德国转发密钥只用于国内主机/容器连接德国专用用户，不能拿它当电脑登录国内主机的密钥。OKX 只读凭据、X OAuth 凭据、Telegram Bot Token、DeepSeek Key 均属于后端运行配置，不输入前端、不打进镜像。

### 4.3 当前生产模板的业务安排

以下是 [backend.env.example](../deploy/backend.env.example) 的目标配置，不代表已经读取了服务器实时环境：

| 能力 | 目标安排 |
| --- | --- |
| OKX | 自动任务开启；`execution-mode=paper`、`live-enabled=false`；REST 可用，交易 WebSocket 关闭、热缓存开启 |
| X | AI 生成、Telegram 人工审核和审核后发布开启；真实 X 发布能力开启，但仍必须满足审核与其他门禁 |
| X 生成节奏 | 每轮完成后等待 10800000 ms，即三小时；不是每天固定整点 |
| AI | DeepSeek，模板模型名 `deepseek-flash`，应用配置直连；此处记录模板值，不代表厂商最新型号结论 |
| Polymarket / 微博 / 小说后台任务 | 生产模板默认关闭；代码仍在后端仓库中 |
| 文字游戏 / 集市前端 | 仓库中存在，默认生产 Compose 不部署这两个前端 |

## 5. 代码如何组织，业务如何执行

### 5.1 仓库与后端四层

```text
backend/                    Java 21 / Spring Boot 4 / Maven / MyBatis 后端
frontend/trading-cockpit/    交易驾驶舱
frontend/x-console/          X 内容只读控制台
frontend/text-game/          文字游戏前端
frontend/market/             集市前端
deploy/                     镜像构建、发布、代理容器、Nginx、配置模板
tools/polymarket/            Python 外部交易桥接和调试工具
story/                      小说素材与生成内容
docs/                       文档总导航、交接指南及 backend/ 后端专题
```

后端是按业务域组织的**模块化单体**，不是每个域一个独立微服务。当前业务域包括 trading、x、weibo、polymarket、story、textgame、marketplace；automation 负责跨域任务生命周期，client 负责外部供应商通信，ai 负责共享 AI 审计能力，common 放无业务归属的纯函数。

| 域内目录 | 放什么 | 典型入口 |
| --- | --- | --- |
| interfaces | HTTP 映射、鉴权、请求转换、薄调度入口 | TradingController、TradingScheduler |
| application | 用例编排、事务边界、调用顺序、出站 port | TradingStrategyEngine、XPostService |
| domain | 模型、状态、不变量、纯业务规则 | 阈值策略、风控规则、订单状态机 |
| infrastructure | MyBatis、Redis、文件、Broker、外部接入和配置装配 | MarketContextCollector、TradingStateRepository |

基本方向是 interfaces → application → domain，基础设施实现 application 定义的出站接口。跨域生命周期由 automation 编排。详细规范与完整模块说明分别见 [ARCHITECTURE](backend/ARCHITECTURE.md)、[MODULES](backend/MODULES.md)。

### 5.2 后台任务不是打开网页后才运行

`AutomationTaskRegistrar` 登记任务；应用收到 `ApplicationReadyEvent` 后按 auto-start 配置启动，或通过任务 API 启停。每个循环先等待 initialDelay，执行完成后再等待 fixedDelay。同一任务的循环共用互斥锁，忙时跳过该次执行，不积压排队；实际触发时间还会受执行耗时与其他循环影响。

当前主要节奏：交易决策约每 30 分钟一次、事件扫描约每 60 秒、订单对账循环约每 15 秒；X 生成约每三小时、审核轮询约每 5 秒、发布扫描约每 60 秒。它们是不同循环，后台任务启动也不等于每个业务门禁都已放行。

### 5.3 OKX：决策、执行与回测分开

```mermaid
flowchart TD
    Schedule[定时决策 / 定时扫描发现信号]
    Engine[TradingStrategyEngine]
    Collect[采集公共行情；PAPER 读取模拟账本，LIVE 读取私有账户]
    Strategy[当前激活策略的规则评估]
    Hold[HOLD 记录与状态]
    Router[Broker 路由及执行用例]
    Paper[PAPER 风控和数量检查后模拟成交]
    Live[LIVE 多重门禁后提交 OKX 并对账]
    Store[决策记录 / 金融状态 / 审计]
    Schedule --> Engine --> Collect --> Strategy
    Strategy -->|HOLD| Hold --> Store
    Strategy -->|交易动作| Router
    Router --> Paper --> Store
    Router --> Live --> Store
```

当前启用的是确定性阈值策略，不是每次交易决策都调用 DeepSeek。配置中的 enabled 策略属于候选，实时决策只运行当前激活的一个策略。策略评估先得到动作建议，风控、数量规则和执行门禁仍可使其变为 SKIPPED；调用到 Broker 也不等于已成交。

**PAPER 使用独立的持久化模拟现金和持仓账本。** 决策采集器读取 OKX 公共行情和产品规则，并由模拟账本提供余额及仓位，不读取真实账户余额、挂单和成交，因此不要求 OKX 私有凭据。详见 [策略参数版本与模拟资金](backend/TRADING_SIMULATION.md)。LIVE 的私有账户及订单接口需独立配置凭据和验收，PAPER 或公共行情成功不代表 LIVE 已可用。

实时引擎只执行 PAPER / LIVE。历史回测走独立 `BacktestService`、历史行情源和模拟持仓，不通过切换实时模式来启动。回测请求要有 `from`、`to`；提交成功只是入队，最终检查 SUCCEEDED / FAILED 和错误信息。

### 5.4 X：AI 生成、人工审核、发布三个阶段

```text
生成循环 → DeepSeek → 内容校验与保存 → PENDING_REVIEW
  → 后端经代理发送 Telegram 审核消息
  → 人工点击审核 → 后端轮询取回并校验身份、消息与内容版本
  → APPROVED / REJECTED
  → 审核、有效期、配额、账号与发布开关等检查
  → 调用 X API → PUBLISHED / FAILED / UNKNOWN
```

审核通过后可能立即触发一次发布检查，不必一定等到下一分钟。X 控制台目前只读，审核入口在 Telegram。AI 成功不等于已送达审核，Telegram 能收到不等于已经发帖；以具体草稿状态和有效平台帖子 ID 为准。

UNKNOWN 表示外部发布结果不确定，不能当作普通失败自动重发，应先到 X 核对。详见 [X 工作流](backend/X_WORKFLOW.md)。

## 6. 数据存哪里，Redis 和事件总线做什么

Redis 的定位是**共享热行情缓存，减少部分 OKX REST 请求，缺失或故障时回退 REST**。驾驶舱行情查询优先尝试适用的 WebSocket 缓存，再查 Redis，最后请求 REST；当前决策采集器仍直接 REST 采集，不能理解为所有 OKX 请求都会命中 Redis。

REST / WebSocket 行情生产者统一进入有界事件总线，再由隔离的 handler 写 MySQL 或 Redis。队列提供背压/丢弃策略、延迟与处理指标，避免在 WebSocket 回调线程直接做数据库写入。前端读取的 SSE 是另一条推送接口，不等于后端的交易所 WebSocket 接入。

| 数据 | 当前位置 | 容器重建后的情况 |
| --- | --- | --- |
| 订单、成交账本、仓位成本、风险、资金停止状态、X 草稿/审核/发布记录 | MySQL；默认卷 `trade-production_mysql` | 保留卷即可保留数据 |
| 热行情缓存 | Redis；默认卷 `trade-production_redis`，配置 AOF | 仍以缓存语义使用，可回退外部行情 |
| 策略选择、策略画像、有界近期决策记忆 | `/app/data/trading-state.json`；默认卷 `trade-production_backend` | 挂整个目录，保留原子替换后的文件 |
| 回测任务与结果 | 当前 `BacktestService` 进程内内存 | backend 重启会丢失，不应当成持久化回测档案 |
| 日志 | Docker json-file，Compose 限制大小与轮转 | 不是业务数据库或长期备份 |

MySQL 是金融状态权威，JSON 不是跨实例共享资金状态。发布应用镜像不自动升级旧数据库，也不会把 Windows 的开发数据迁移进 Linux 卷。不要删除命名卷来解决普通部署错误；备份与手工 schema 升级见 [生产部署说明](../deploy/README.md) 和 [数据库说明](../backend/src/main/resources/db/README.md)。

## 7. 新人连接现有环境：十分钟操作

先向维护者取得国内 SSH 地址/端口、登录方式和网关账号密码。以下电脑命令均在 **Windows PowerShell** 执行；服务器命令明确标为 Ubuntu Bash。

### 第一步：打开访问隧道

```powershell
$DomesticSsh = 'ubuntu@CN_HOST'  # 替换 CN_HOST
ssh -NT -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:18080:127.0.0.1:18080 -L 127.0.0.1:18081:127.0.0.1:18081 $DomesticSsh
```

首次连接按交接信息核对主机指纹；非默认 SSH 端口增加 `-p 实际端口`。成功后没有业务输出属于正常现象，保持该窗口运行。

打开交易驾驶舱 `http://127.0.0.1:18080` 和 X 控制台 `http://127.0.0.1:18081`，先输入网关账号密码。若电脑本地端口冲突，可将 `-L` 前半段换成未占用端口，远端目标端口不变，并使用相应浏览器地址。

### 第二步：只读验收，分清进程健康与业务结果

在电脑 PowerShell 检查网关和业务 API，curl 会询问网关密码：

```powershell
curl.exe --noproxy "*" -sS --max-time 30 -u operator 'http://127.0.0.1:18080/api/trading/runtime/status' | ConvertFrom-Json | Select-Object executionMode, liveEnabled, activeStrategyId, lastError, lastDecision
curl.exe --noproxy "*" -sS --max-time 30 -u operator 'http://127.0.0.1:18080/api/trading/market/candles?instId=BTC-USDT&bar=5m&limit=2'
curl.exe --noproxy "*" -sS --max-time 30 -u operator 'http://127.0.0.1:18080/api/automation/tasks'
```

运行状态应符合 PAPER、liveEnabled=False；最新一次决策完成后再检查 lastError 和 lastDecision。automation 的 lastRunSuccessful 表示对应调度入口的结果；story、weibo、x 会把内部失败或降级结果反馈给调度入口，交易引擎仍可能捕获错误并保存到自己的 lastError，需同时检查交易状态。

在 **国内 Ubuntu Bash** 定义日常命令并查看容器：

```bash
cd /opt/trade
dc() { docker compose --env-file deploy/.env --env-file deploy/release.env -f compose.prod.yaml "$@"; }
dc ps
dc logs --since=20m --tail=100 backend egress gateway
```

从 backend 容器验证完整业务代理链，公开时间接口不会下单：

```bash
dc exec -T backend curl --noproxy "" -fsS --max-time 20 --proxy http://egress:13128 https://www.okx.com/api/v5/public/time
```

检查宿主机的镜像下载隧道：

```bash
systemctl is-active trade-docker-egress.service
curl --noproxy "" -sS --connect-timeout 10 --max-time 30 --proxy http://127.0.0.1:13128 -o /dev/null -w 'Registry HTTP %{http_code}\n' https://registry-1.docker.io/v2/
```

Registry `/v2/` 返回 HTTP 401 是到达鉴权入口的正常挑战，不能证明 token 服务和所有镜像层下载都可用。egress healthy 只证明转发监听存在；网关 `/healthz` 只证明网关进程。分享日志前清理账户和凭据信息；Compose 配置语法检查使用 `config -q`。

## 8. 后续如何迭代部署

发布统一使用 **GitHub → CNB → TCR → 国内服务器**。电脑和生产服务器无需执行镜像构建；入口、凭据和参数见 [CNB 发布说明](../deploy/README.md#cnb-发布入口)。

1. 修改代码并完成受影响部分的检查，提交到 GitHub `main`。若改了 Compose、网关或业务配置，先同步服务器相应文件；CNB 只上传发布脚本和镜像清单。
2. 在 **Actions → Submit build or deployment to CNB → Run workflow** 选择 `main`，按变更选择 `service`：`backend`、`cockpit`、`x-console`、`egress`、`frontends` 或 `all`。
3. 选择 `mode=build_deploy` 构建并部署；若需分开执行，先用 `build`，再用 `deploy` 并在 `existing_release_manifest` 粘贴 CNB 生成的完整清单。首次没有成功基线时必须选 `all`；部分构建会经 SSH 读取服务器当前成功清单。
4. GitHub 成功只表示任务提交成功。进入结果中的 CNB 链接核对构建、推送与 `DEPLOYMENT_OK`，再按第 7 节核对容器、行情及业务状态。

GitHub 入口固定使用 `/opt/trade` 和私有访问模式。完整镜像清单包含 `RELEASE_VERSION`、`BACKEND_IMAGE`、`COCKPIT_IMAGE`、`X_CONSOLE_IMAGE`、`EGRESS_IMAGE`，即使只更新一个服务也不能省略其他项。CNB 日志会打印清单，并尝试上传清单附件；构建推送成功而部署失败时可复用清单重试，避免重复构建。

服务端发布锁与健康检查保留。发布会短暂停止并重建选中的应用；全量发布包括网关。成功后更新 `deploy/release.env`、`deploy/releases/current.env` 并保留 `previous.env`。失败不会自动回滚，需要在同一 CNB 入口选择 `mode=rollback`；它使用服务器上一成功清单并要求本地镜像齐全，首次发布没有上一版可回滚。

仅修改 `backend.env`、继续使用当前镜像时，在服务器执行：

```bash
cd /opt/trade
bash deploy/scripts/release.sh deploy backend --no-pull
```

人工回滚可执行 `bash deploy/scripts/release.sh rollback --no-pull`。镜像回滚不恢复数据库、业务 env、密钥或网关文件；数据库升级和公共访问模式切换按 [生产部署说明](../deploy/README.md) 单独处理。

## 9. 常见问题按链路定位

| 现象 | 先检查哪里 |
| --- | --- |
| 电脑 localhost 页面无法连接 | 电脑 SSH 窗口是否仍运行、本地端口是否冲突、国内 SSH 是否可达；然后检查 gateway |
| 页面可打开但 API 502 | gateway → backend；检查后端容器健康和日志，不先归因于德国 VPS |
| 读操作正常，切换策略 403 | 驾驶舱交易令牌是否正确、刷新后是否重新填写；409 则先刷新策略版本 |
| 新建回测 400 | 起止时间、策略、标的及其他请求参数；进入队列后的 FAILED 另看运行错误 |
| 行情能读，决策报 OKX GET request error | 查看最底层 Caused by 和实际请求接口；PAPER 排查公共行情、产品规则及代理，LIVE 另查私有凭据、权限和出口 IP |
| WebSocket 未就绪但行情有值 | 当前模板关闭交易所 WS，REST/Redis 仍可供行情；与浏览器 SSE 是不同层次 |
| Docker pull 超时，业务代理正常 | 宿主机 trade-docker-egress.service 与 Docker daemon 代理；它们不是 egress 容器 |
| CNB 构建或发布失败 | 进入 CNB 任务定位构建、推送、镜像拉取或健康检查阶段；已有完整清单时可仅重试部署 |
| 改了 backend.env 似乎没生效 | 同步脚本不重启服务；通过 release.sh 重建 backend，不是只 restart |
| automation 显示成功但没有决策 | 查看 trading runtime 的 lastError / lastDecision；循环未抛错不等于决策成功 |
| Telegram 收到审核，X 没发出 | 查看草稿 status / lastError、审核版本、目标账号和发布门禁；UNKNOWN 先人工核对 |
| 后端重启后回测列表消失 | 当前回测记录存内存；不是 MySQL 丢数据 |

只筛选最近交易错误及底层原因的服务器命令，复制整行执行：

```bash
dc logs --since=2h --no-color backend 2>&1 | grep -A 70 -F 'OKX strategy decision failed' | grep -E 'OKX strategy decision failed|Caused by:|^[[:space:]]*(java\.|javax\.|org\.)' | tail -n 35
```

## 10. 交接时提供什么，从哪里继续读

维护者应通过受控渠道交接：国内/德国主机 SSH 地址与端口、主机指纹、登录授权、网关账号、必要的应用令牌、当前镜像清单、备份位置，以及宿主机 systemd 隧道/Squid 的配置维护方式。共享文档只记录字段名和职责，不写真实密钥。

| 想了解的内容 | 入口 |
| --- | --- |
| 首次安装、密钥权限、网关、发布、备份、公共模式 | [生产部署说明](../deploy/README.md) |
| 后端全部能力、开发启动 | [backend README](../backend/README.md) |
| 业务模块边界 | [MODULES](backend/MODULES.md) |
| 四层职责和调用链 | [ARCHITECTURE](backend/ARCHITECTURE.md) |
| 后台任务怎么注册/启动 | [Registrar](../backend/src/main/java/com/trade/automation/application/task/AutomationTaskRegistrar.java)、[Manager](../backend/src/main/java/com/trade/automation/application/task/AutomationTaskManager.java) |
| 决策与 PAPER 账户读取 | [TradingStrategyEngine](../backend/src/main/java/com/trade/trading/application/strategy/TradingStrategyEngine.java)、[MarketContextCollector](../backend/src/main/java/com/trade/trading/infrastructure/market/MarketContextCollector.java) |
| 回测参数与结果 | [回测说明](backend/TRADING_BACKTEST.md) |
| X 审核与发布 | [X 工作流](backend/X_WORKFLOW.md)、[XPostService](../backend/src/main/java/com/trade/x/application/service/XPostService.java) |
| 电脑外的业务 SSH 隧道 | [egress entrypoint](../deploy/egress/entrypoint.sh) |
| 页面/API 到底转发给谁 | [私有网关](../deploy/nginx/private.conf.template)、[API 路由](../deploy/nginx/includes/api.conf) |
| 交易驾驶舱和 X 控制台的开发方式 | [cockpit README](../frontend/trading-cockpit/README.md)、[x-console README](../frontend/x-console/README.md) |
| 配置同步、镜像构建与发布脚本 | [sync](../deploy/scripts/sync-backend-config.ps1)、[build](../deploy/scripts/build-images.sh)、[release](../deploy/scripts/release.sh) |

后续修改端口、网关、代理方式、数据归属或后台门禁时，同时更新本文对应章节和原始配置模板；实际发布成功版本以服务器记录为准。
