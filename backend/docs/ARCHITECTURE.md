# 后端架构说明

本页描述当前有效的代码边界；模块入口与资源归属见 [模块目录](MODULES.md)，采用该结构的原因和取舍见 [ADR-0001](adr/0001-domain-first-modular-monolith.md)。

## 1. 组织原则

后端采用“业务域优先，域内再分层”的结构。一个需求涉及的 Controller、用例、模型和持久化代码尽量放在同一个业务域下，避免在全局 `controller/`、`service/`、`mapper/` 目录之间来回查找。

顶层包分为三类：

- 业务域：`trading`、`polymarket`、`story`、`textgame`、`marketplace`、`weibo`；
- 跨域编排：`automation`；
- 共享基础能力：`client`、`ai`、`common`。

`TradeApplication` 位于 `com.trade`，Spring 组件扫描和 MyBatis Mapper 自动发现都依赖代码继续位于这个根包之下。

## 2. 顶层依赖方向

```text
automation ────────────────> domain scheduler / lifecycle entry

business domain ──────────> client        外部 HTTP / WebSocket 传输
business domain ──────────> ai            共享 AI 审计契约
business domain ──────────> common        纯工具

client / ai / common  -X-> business domain
business domain A     -X-> business domain B
```

规则：

1. 业务域之间不直接调用。需要跨域启动、停止和状态汇总时，由 `automation` 编排。
2. `client` 只处理协议、签名、序列化、DTO 和网络异常，不放交易或发布策略。
3. `common` 只能放无 Spring 生命周期、无外部 I/O、无业务归属的纯代码。
4. `ai` 放跨业务的 AI 基础契约与适配；领域专属 Prompt 和解析器仍留在各自的 `decision` 包。
5. 共享模块不得反向 import 任一业务域。

## 3. 标准业务域结构

业务域按需使用以下目录，不要求为了形式创建空包：

```text
<domain>/
├─ package-info.java     # 领域职责、主流程和导航入口
├─ web/                  # HTTP 协议、鉴权入口、参数接收、异常映射
├─ application/          # 一个完整用例的编排与事务边界
│  └─ port/              # 可选：用例需要的出站接口
├─ model/ 或 domain/     # 与数据库、HTTP 客户端无关的值和规则
├─ exception/            # 可选：application、adapter、web 共享的用例失败
├─ decision/             # AI Prompt、决策解析与校验
├─ strategy/、risk/      # 可组合的业务规则
├─ market/、execution/   # 行情采集与订单执行
├─ persistence/          # Mapper、Row、Repository/Store 实现
├─ scheduler/            # 定时触发入口，不承载业务流程
└─ config/               # ConfigurationProperties 与领域 Bean 装配
```

层次职责（新代码和后续重构的目标规则）：

| 层 | 可以做什么 | 不应该做什么 |
| --- | --- | --- |
| `web` | HTTP 映射、Header/Token 检查、请求响应转换 | 直接写 SQL、保存 Row、实现业务决策 |
| `application` | 编排一个用例、控制事务、组合领域规则与出站能力 | 处理 HTTP 细节、拼接供应商请求 |
| `application.port` | 声明用例所需的存储或外部能力 | 包含 MyBatis、HTTP 实现细节 |
| `model` / `domain` | 保存稳定业务数据和纯规则 | 依赖 Spring MVC、MyBatis 或数据库 Row |
| `exception` | 表达跨层共享的用例失败，由 Web 统一映射状态码 | 包含 Controller 或数据库实现 |
| `persistence` | Mapper、Row、数据库适配与对象转换 | 被 Controller 直接使用、包含 HTTP 逻辑 |
| `client` / provider adapter | 传输、认证、签名和供应商 DTO | 决定是否下单、风险阈值或业务流程 |
| `scheduler` | 按时触发 application service | 复制 application service 的实现 |
| `config` | 属性注册和 Bean 组合 | 承载业务计算 |

项目仍在渐进收敛边界：少量历史 `model` 还持有 provider DTO，部分 application service 也会直接调用 Mapper/Repository。修改这些代码时应逐步增加内部模型或 application port，但不要仅为改包名一次性改动交易语义。当前自动化结构测试只约束已经稳定的规则，不宣称所有目标规则都已完成。

## 4. 典型调用链

HTTP 请求：

```text
Controller
  -> Application Service
    -> domain rule / strategy
    -> application port -> persistence adapter -> MyBatis Mapper -> database
    -> client contract  -> provider client -> external API
```

尚未引入 port 的旧模块可能由 Application Service 直接调用 persistence adapter；新增跨存储或跨供应商能力时优先声明 port。

后台任务：

```text
AutomationTaskController / auto-start
  -> AutomationTaskManager
    -> domain Scheduler
      -> Application Service
```

AI 解析失败审计：

```text
Story / Polymarket Application Service
  -> ai.audit.AiResponseParseErrorSink
    <- ai.persistence.MyBatisAiResponseParseErrorRepository
```

### Trading 行情事件管道

实时和批量行情统一使用 `trading/event/TradingEvent` 信封。信封包含事件 ID、类型、来源、标的、业务发生时间、系统接收时间、关联 ID 和类型安全载荷；策略检测得到的条件则使用 `MarketSignal`，避免把“进入系统的事实”和“由策略推导的信号”混为一类。

```text
OKX WebSocket ─┐
REST decision ─┼─> TradingEventPublisher
REST fallback ─┤       -> bounded BlockingDeque
REST history ──┘          -> isolated TradingEventHandler(s)
                                  -> OkxMarketDataStore -> MyBatis -> database
```

生命周期和失败边界：

1. 事件消费者是应用级基础设施，Spring 初始化后即启动，因此 HTTP 回测和 REST 采集不依赖后台交易任务是否运行。交易任务只启停 WebSocket 生产者；应用退出时 `TradingMarketDataRuntime` 先停止生产者，再在超时范围内排空已接收事件。
2. 队列只允许固定容量。`DROP_OLDEST` 保留更新行情，`DROP_LATEST` 保留队列顺序，`BLOCK` 最多等待配置的发布超时；任何策略都不会无界扩容。
3. 每个 handler 独立捕获异常，单次数据库失败只计数和告警，不终止消费线程，也不回抛到 WebSocket 回调或策略采集流程。
4. `trade.trading.events.*` Micrometer 指标覆盖队列深度/容量、发布结果、丢弃原因、排队延迟、handler 结果和耗时；低成本运行快照由 `/api/trading/runtime/events` 提供。
5. 历史 K 线写库同样异步，但会把本次 REST 响应与已有缓存合并后直接返回，从而保留回测调用方的读后取数语义。

## 5. 分层示例：Weibo

```text
weibo/
├─ application/
│  ├─ WeiboAccountService.java
│  ├─ WeiboOAuthService.java
│  ├─ WeiboPublishingService.java
│  └─ port/
│     ├─ WeiboAccountTokenRepository.java
│     └─ WeiboOAuthStateRepository.java
├─ model/               # 账号、令牌、授权结果
├─ persistence/         # 两个 port 的 MyBatis 实现、Mapper、Row
├─ config/              # Weibo transport Bean
└─ web/                 # 管理员 API 与 HTTP 异常映射
```

这个结构明确区分了“用例需要什么”和“数据库如何实现”。应用服务依赖 port，MyBatis Adapter 实现 port，因此更换存储方式不需要修改 OAuth 或发布流程。

## 6. 共享客户端的组合位置

具体 provider 分别位于 `client/ai`、`client/gemini`、`client/okx`、`client/polymarket`、`client/weibo`。共享 AI provider 的选择统一放在 `client/config/AiClientConfiguration`；OKX Bean 只在 `trading/config/OkxClientConfiguration` 中装配。

当前 story 和 polymarket 使用 `AiTextClient`，并且不再依赖 trading 的配置类；未来新增 AI 工作流也从同一组合点获取实现。provider 包之间保持单向依赖。

## 7. 新代码应该放在哪里

| 新增内容 | 推荐位置 |
| --- | --- |
| 新 HTTP endpoint | 所属域 `web/`，业务逻辑下沉到 `application/` |
| 新用例或事务 | 所属域 `application/` |
| 新数据库表访问 | 所属域 `persistence/`，XML 放 `resources/mapper/<domain>/` |
| 新外部供应商 HTTP/WS 调用 | `client/<provider>/` 或所属域的专用 adapter 包 |
| 新 AI Prompt/响应解析 | 所属域 `decision/` |
| 新定时流程 | 薄 `scheduler/` + `AutomationTaskRegistrar` 注册 |
| 多领域复用的纯函数 | 仅在确实跨域后放 `common/support/` |
| 领域专属帮助类 | 留在该领域，避免提前放入 `common` |
| 新 API DTO | 所属域 `model/`；不得直接暴露 persistence Row |

## 8. 注释与测试约定

- 每个一级业务域必须有 `package-info.java`，说明职责、主流程和首要入口。
- 入口类、重要编排服务、port、复杂规则和安全开关使用 Javadoc；简单 getter、显然的 DTO 字段不写重复注释。
- 注释重点解释“为什么、边界、单位、失败行为”，不逐行复述代码。
- 测试包路径镜像生产包路径；移动类时同步移动测试，避免物理目录和 `package` 声明不一致。
- 结构移动后必须运行 `.\mvnw.cmd clean test`，防止 `target/` 中旧 class 掩盖遗漏。
- MyBatis Mapper 或 Row 改包时，同时检查 XML 的 `namespace`、`parameterType` 和 `resultType`。

`PackageArchitectureTest` 自动保护以下稳定边界：源码路径与 package 一致、一级模块根目录不散落未分层类、每个一级模块具有 package 文档、共享包不反向依赖业务域、业务域之间不直接 import、非 Web 层不依赖 Web、Web 不暴露 persistence 类型，以及 model/domain 不依赖 Web 或持久化类型。供应商 client 依赖采用精确技术债基线：当前仅允许测试中列出的历史 import，不能新增；旧依赖移除后必须同步缩小基线。新增规则前应先确认它代表当前共识，而不是用测试强制一次性改写历史代码。

## 9. Resources 约定

- `application.yml`：运行默认值和环境变量绑定；危险能力默认关闭。
- `db/ai_trade_mysql_schema.sql`：新数据库的完整基线。
- `db/migration/`：已有数据库的手工升级脚本，不会自动执行。
- `mapper/<domain>/`：MyBatis XML 按所属业务域分组。
- `textgame/stories/`：内置剧情资源；当前由 Seed Importer 显式选择文件，不会自动扫描整个目录。
