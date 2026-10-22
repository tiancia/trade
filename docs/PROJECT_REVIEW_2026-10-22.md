# 全项目检查与修复记录（2026-10-22）

本次检查覆盖 `A:\trade` 的后端、四个前端、部署配置与脚本、Polymarket 工具。发现并修复了下列可重现的安全或稳定性问题。当前按业务域划分的模块化单体结构可以继续使用，没有为目录整齐拆模块或重写架构。

检查结论仅适用于当前源码和本地验证，不代表线上已部署，也不保证不存在其他漏洞。任务开始时 Git 工作区干净；本次未提交或推送代码，未执行真实下单、发布、付费 AI 请求或生产数据库写入。

## 已修复问题

### P1：需要随本次修复发布

| ID | 问题与影响 | 修复与主要位置 |
| --- | --- | --- |
| 01 | 交易余额 `availBal=0` 被回退为现金余额，BUY 计算又把零余额上限当作不限额，可能在余额被冻结或不可用时仍生成买单。 | 字段存在时采用可用余额，零、负数和无法解析的余额不能产生 BUY。[TradingMarketInputs.java:22](../backend/src/main/java/com/trade/trading/application/market/TradingMarketInputs.java)、[OrderSizingRules.java:27](../backend/src/main/java/com/trade/trading/domain/order/OrderSizingRules.java)。 |
| 02 | BUY 没有复用 SELL 的未解决订单检查。此前订单超时、结果未知时，下一根 K 线的新幂等键可能继续加仓。 | 所有新 LIVE 提交在调用交易所前检查其他未解决订单；读取失败停止提交。同幂等键的查询恢复仍走原路径。[LiveOrderExecutionService.java:272](../backend/src/main/java/com/trade/trading/application/execution/LiveOrderExecutionService.java)。 |
| 03 | WebSocket 只在连接时请求一次消息，订阅 ACK 或首个片段会消耗需求；K 线又使用了 `/public` 地址，导致行情无法持续接收。 | 每个文本片段后 `request(1)`，K 线订阅改走独立 `/business`。增加 `TRADE_OKX_WS_BUSINESS_URL`，同步配置、模板和运维说明。[OkxWebSocketClient.java:157](../backend/src/main/java/com/trade/client/okx/OkxWebSocketClient.java)、[OkxApi.java](../backend/src/main/java/com/trade/client/okx/OkxApi.java)。 |
| 04 | 图片地址仅做字符串前缀检查，`users/1/../2/a.jpg` 和编码点段可绕过用户目录约束；未配置自定义图片域名时，还会跳过前缀校验。 | 拒绝解码后的点段、反斜杠、用户信息、查询及 fragment；自动生成的 OSS bucket 地址同样约束卖家前缀。[MarketplaceItemRules.java:36](../backend/src/main/java/com/trade/marketplace/domain/rule/MarketplaceItemRules.java)、[MarketplaceItemService.java:141](../backend/src/main/java/com/trade/marketplace/application/service/MarketplaceItemService.java)。 |
| 05 | 注册规则允许 128 字符，与 BCrypt 的 72 UTF-8 字节边界不一致；长输入会在编码时失败，登录验证可能截断超长输入。 | 注册和登录统一校验 72 字节；前端同步输入上限、中文提示与字节校验。[MarketplaceAccountRules.java:16](../backend/src/main/java/com/trade/marketplace/domain/rule/MarketplaceAccountRules.java)、[MarketplaceAuthService.java:85](../backend/src/main/java/com/trade/marketplace/application/service/MarketplaceAuthService.java)、[MarketplaceApp.tsx](../frontend/market/src/MarketplaceApp.tsx)。 |
| 06 | 集市启动时的旧账号校验响应没有绑定当前会话，退出后可能恢复旧会话，旧 401 也可能清掉新登录账号的存储。 | 成功和失败响应落地前都检查当前 token，失效时同步清理引用。[MarketplaceApp.tsx:133](../frontend/market/src/MarketplaceApp.tsx)。 |
| 07 | 驾驶舱切换 K 线周期或收到 SSE 后，慢的旧 REST 响应仍能覆盖当前图表。 | 请求取消、请求代次和 SSE 更新使旧结果失效，取消后的失败也不再覆盖新状态。[page.tsx:490](../frontend/trading-cockpit/app/page.tsx)。 |
| 08 | Python 下单桥接先等待进程退出，再读取 stdout/stderr；输出超过管道缓冲区时会卡住。异常或中断也可能留下继续运行的子进程。 | 同时排空两条输出管道，退出路径统一销毁仍存活的进程、关闭流，保留线程中断语义。[PolymarketPythonOrderRunner.java:108](../backend/src/main/java/com/trade/polymarket/infrastructure/broker/PolymarketPythonOrderRunner.java)。杀死本地进程不能撤销已到达交易所的订单。 |

余额、会话和路径问题均补充现有测试。WebSocket 原先没有传输层测试，因此新增 [OkxWebSocketClientTest.java](../backend/src/test/java/com/trade/client/okx/OkxWebSocketClientTest.java)，使用离线传输模拟验证 ACK、分片、pong、畸形消息后的持续接收。Python 桥接在现有测试文件中启动本地 Java 测试子进程，验证大输出、超时和中断，不依赖 Python SDK 或网络。

WebSocket 修改依据：[OKX K 线频道](https://www.okx.com/docs-v5#order-book-trading-market-data-ws-candlesticks-channel)、[JDK 21 Listener 消息需求契约](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.Listener.html)。

### 09：前端依赖安全修补

已检查四个项目的 npm 依赖公告，更新有兼容修补版本的依赖；没有使用 `npm audit fix --force` 或忽略 peer 冲突。

| 项目 | 更新 | 最终完整 npm audit（包含开发依赖） |
| --- | --- | --- |
| market / text-game | Vite 7.3.7、Vitest 4.1.12，修补兼容范围内的 PostCSS、nanoid、Browserslist 等转依赖。market 的单 worker 配置同步迁移为 `maxWorkers: 1`。 | 两项目均为 0。 |
| x-console | Vite 7.3.7。 | 更新命令审计为 0。 |
| trading-cockpit | Vite 8.3.5、Cloudflare plugin 1.64.0、Wrangler 4.151.0；对 vinext 固定的 image-size 使用 2.0.4 窄范围覆盖。 | 从 17 项降至 8 项 high；均由同一个未修补的 braces 公告沿依赖链传播。 |

剩余 [braces 深层嵌套模式拒绝服务公告](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)暂无上游修补版本。当前使用点位于 ESLint 与构建插件的 glob 处理，输入来自项目配置或源码；没有发现 HTTP 输入到该解析器的路径。保留这项已知风险，没有通过降级框架或隐藏审计结果消除数字。驾驶舱容器复制了完整 `node_modules`，因此 `--omit=dev` 的零告警不能用来宣称运行镜像没有漏洞。Cloudflare plugin 的官方依赖链包含 Miniflare 5 beta；本次验证了本地构建和 Node 运行，未执行 Cloudflare 发布。

验证使用 Node 22.14；Vite 7 要求 Node 20.19+ 或 22.12+。本机 npm 10 安装新 Vitest 时遇到 Arborist `edgesOut` 错误，集市和文字游戏通过临时 `npx npm@11.6.2` 生成锁文件；没有修改全局 npm，最终 `npm ci` 和 `npm ls --all` 均成功。

官方公告明确 [Vitest mocker 的 4.1.12 回补版本](https://github.com/vitest-dev/vitest/security/advisories/GHSA-qxg6-89mg-rqv8)修复相关问题。[Tinypool 公告](https://github.com/tinylibs/tinypool/security/advisories/GHSA-85c8-ppgw-ccpr)描述的是依赖已有原型污染及特定调用方式的利用链；扫描严重等级不等于本项目已证明存在公网远程代码执行。

### 10：Windows 生产静态资源路径

驾驶舱真实 HTTP 验收发现 vinext 0.0.50 在 Windows 使用反斜杠缓存静态资源路径，导致主页返回 200、页面的 JS/CSS 却全部 404。采用同系列上游补丁 vinext 0.0.55 修复，并在已有 [rendered-html.test.mjs](../frontend/trading-cockpit/tests/rendered-html.test.mjs) 中新增生产 HTTP 回归，逐个验证页面引用资源。没有修改 `node_modules` 或新增自定义服务器。

同时依据当前 `MarketContextCollector` 的 PAPER 分支，更正部署说明、全局上手指南及运维说明中“PAPER 没有独立模拟资金、仍需私有 OKX 凭据”的过时文案；代码原本已使用持久化模拟账本，本次未改变其资金来源。

## 验证

| 检查 | 结果 |
| --- | --- |
| `backend/mvnw.cmd -o clean test` | 580 项，0 失败、0 错误，1 项外部集成测试跳过。 |
| `backend/mvnw.cmd -o -q "-Dtest=PackageArchitectureTest" test` | 单独复核通过；18 项分层检查通过。 |
| Python 桥接现有 Java 测试类 | 9 项通过，含新增的管道压力、超时、中断回归。 |
| `node --test deploy/tests/cleanup-images.test.cjs` | 23 项通过；使用模拟 Docker/SSH，未删除真实镜像。 |
| 部署 Bash / PowerShell 语法 | 5 个 Bash 文件和 1 个 PowerShell 文件通过解析。 |
| 私有 / 公共 Compose `config -q` | 使用合成密码和已跟踪 example 文件通过；未读取真实运行配置。 |
| market | 最终锁文件干净安装成功，17 项测试与生产构建通过，完整依赖审计为 0。 |
| text-game | 最终锁文件干净安装成功，3 项测试与生产构建通过，完整依赖审计为 0。 |
| x-console | 17 项测试与生产构建通过，更新命令审计为 0；独立复核查询遭遇 registry 超时。 |
| trading-cockpit | 最终依赖下 Sites 构建、25 项测试及 lint 通过；Node 专用构建通过，其后再次运行 4 项 HTTP/渲染测试通过。 |
| 驾驶舱真实生产 HTTP | 主页及其全部 JS/CSS 均为 200，`/.vite/manifest.json` 为 404；临时服务已退出。 |
| `git diff --check` | 通过。 |

四个前端共 62 项测试通过；Node 专用构建后的 4 项 HTTP/渲染复核与其中的测试重叠，不重复计数。

## 结构判断和剩余边界

- 业务域和四层依赖已有自动化检查保护；部分 application service 直接使用 Mapper 的现状已在模块文档中说明，本次没有为消除形式上的不一致进行重构。
- WebSocket 仍无自动重连和重订阅；断线后依赖缓存过期检查与 REST 回退，恢复 WebSocket 通道需要重启对应任务。这次没有扩展成新的连接恢复框架。
- Docker Linux Engine 不可用，不能运行完整镜像构建或容器验收。Compose 静态校验和本地 Node 构建不等于镜像、线上状态已验证。
- 真实 OKX、Polymarket、Telegram/X、OSS/STS、生产 MySQL 和线上部署未验证；默认危险开关保持原值。新 WebSocket 配置需要随代码部署，线上覆盖地址仍需核对。
- 依赖公告检查主要覆盖 npm；没有将其表述为 JVM、Python SDK、操作系统与容器基础镜像的完整 CVE 扫描。Polymarket SDK 迁移及其真实协议兼容性仍需独立联调。
