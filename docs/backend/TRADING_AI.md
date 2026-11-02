# Trading AI 策略顾问

AI 以交易日为单位制定日内短线策略，目标是在现有风控内争取扣除买卖手续费、价差和滑点后的净收益，行情允许时每天完成 **5–8 笔完整交易**。每笔指从开仓到卖出至空仓，买入、部分卖出和重放不额外计数，跨日持仓计入平仓日。次数是可调整的规划目标，不是强制下单配额，也不保证每日盈利或达到次数。

AI 读取公开行情，可以保持策略、切换已有策略或直接修改当前策略参数。PAPER 和 LIVE 均可按 `AUTO_APPLY` 开关自动应用；AI **不能切换 PAPER/LIVE 执行模式**，也不能开启实盘授权。每次生效变更同时保存数据库审计并通过独立 Telegram Bot 通知。需要新增策略模式或修改代码时，仅发送具体建议，不自动创建或部署策略。

## 运行流程

独立自动任务 `trading-ai` → MySQL 预留评估窗口 → 已确认、连续的 15m K 线与策略配置快照 → 共享 `AiTextClient` → 严格 JSON 校验 → 应用门禁 → 在同一数据库事务中保存策略变更、前后快照与待通知状态 → 提交后发送 TG 变更通知或新模式建议。

- 默认每 30 分钟复评，按交易日统计目标，首次启动等待 60 秒；日界线沿用 `trade.trading.risk.daily-zone`（默认 `Asia/Shanghai`，UTC+8）。例如当天 14:00 首次评估，下次窗口为 14:30；23:50 评估后的下次窗口为次日 00:00。任务每分钟检查数据库窗口，未到期不采集行情、不调用 AI；调度、网络及停机可能延迟实际评估。数据库频率限制跨进程重启和实例生效，失败也消耗本轮窗口，避免付费请求密集重试。
- `REVIEW_INTERVAL_MS` 可在 15 分钟至 24 小时之间配置，仍使用同一日目标；设为 86400000 可恢复每天评估。每轮附带最近最多 3 次已有建议及结果，要求说明改变当天方案的新证据；不能仅因到点或交易次数不足反复调参。建议过期时间不会越过当前交易日，已应用的策略不会在午夜自动撤销，持仓仍由原有退出和风控逻辑管理。
- 输入包含当前模拟会话的当天及前一天统计：买卖动作数、完整交易数、已实现净收益、当日手续费。查询使用半开日区间与账户版本，不截取最近一页成交。净收益已含分摊的买入和卖出费用，不重复扣减手续费字段，也不等于含浮盈亏的当日权益收益。新建/替换会话会明确标注部分日或没有历史，不能当作完整一天的表现；不合并不同模拟会话。LIVE 缺少真实成交统计时明确标记未知。
- 同时提供带 UTC 偏移的交易日当地时间、当日剩余分钟，以及当前 PAPER 会话今天最多 12 条近期执行检查（策略版本、动作、观望原因、执行状态和拦截原因）。这些记录来自有界本地记忆，只用于解释未成交，不替代完整账本统计；空样本不证明全天没有决策。AI 需要区分未出现信号、成本/风控拦截、行情异常和缺少执行记录，不能通过放宽参数绕过运行故障。
- 低于次数目标时分析行情机会和信号限制，证据不足则等待；达到或超过目标时避免无效换手，保护性退出始终不受次数目标限制。现有策略不能表达所需日内行为时只提出逻辑建议，不能虚构回测或为了凑单放宽成本、仓位与风控限制。此功能不强制日末平仓，也不在执行器中强制最低/最高笔数。
- 输入默认最近 96 根已收盘 15m K 线、已有策略参数及 PAPER 汇总绩效，不上传密钥、真实账户余额或订单。至少 32 根，最多 199 根；不得超过 `fifteen-minute-candle-limit - 1`。最新收盘最多允许落后一个周期加 60 秒，断档、重复、未收盘和非法 OHLCV 不可用于自动调整。
- 输出仅允许 `HOLD`、`SWITCH`、`TUNE`。模型信心是模型自评，不代表经校准的成功概率，也不证明策略盈利。
- TUNE 直接修改当前策略支持的信号参数、止损止盈、浮亏触发阈值和策略最低净收益阈值，不再限制每轮 6 项或 ±20%。日内 `mode` 仅接受已有的 `TREND_PULLBACK`、`VOLUME_BREAKOUT`、`RANGE_REVERSION`，可通过 TUNE 修改；未实现的新模式只能写入 `logicSuggestion`。完整参数仍须通过数值范围、整数、EMA/RSI/ATR/止盈止损关系和 K 线容量校验。
- 金额、仓位规模、手续费、滑点假设、确认收盘规则、全局风控、执行模式和实盘授权不可由 AI 修改。策略内部阈值变化不绕过全局净优势、风险收益、日亏损及资金停止等执行门禁。
- 自动应用要求当前为 PAPER 或 LIVE 的 SPOT，模型信心默认至少 0.80。同库受管理 LIVE 持仓、未决订单和当前 PAPER 持仓都会拦截变更；PAPER 还要求同一已初始化空仓会话及版本。应用前复核执行模式/标的、活动策略修订、参数版本、有效期和领导租约。持仓期间保留建议，等待下一次独立复评，不排队强行应用旧建议。
- 策略切换沿用活动策略选择；参数变更保留不可变版本及评估编号。评估表 `application_json` 保存时间、标的、执行模式、前后策略 ID、选择修订和完整参数版本；原因、信心与行情依据在同一评估的 proposal/snapshot 中。生效状态 `APPLIED`、审计与变更使用同一事务，审计失败会回滚变更。每轮只执行一种动作。
- 自动调整使已采集的旧选择修订/参数版本无法执行；PAPER 同时推进模拟会话版本。LIVE 复用订单预留与策略维护的数据库互斥机制。
- AI HTTP 请求在独立任务上执行，不持有交易决策锁。停止 AI 任务会令在途评估失效；失效建议不再应用。已经提交的变更仍完成通知，不能通过暂停抹去审计或通知责任。

## 后续填写环境变量

本地 Java 进程参考 [backend/.env.example](../../backend/.env.example)；Spring Boot 不自动读取普通 `.env`，需由 IDE 或进程环境注入。生产使用 [deploy/backend.env.example](../../deploy/backend.env.example) 对应的后端 env_file。根 `.env` 仅用于基础设施。

下表开关默认值指配置类和 env 示例；当前 `application.yml` 运行模板已开启顾问和当前 PAPER/LIVE 模式内自动应用；自动启动跟随顾问主开关，通知按独立聊天配置接入，启动前核对环境覆盖。已有 `REVIEW_INTERVAL_MS=3600000` 或 `86400000` 环境变量会保留原节奏，需改为 `1800000` 并重启后端才使用 30 分钟复评。已预留的数据库冷却窗口不会提前清除，页面显示真实的下一窗口，下一轮再按新间隔预留；不要通过删除 guard 行触发付费重试。手续费、最低净优势、风险收益门槛和日亏损限制作为只读约束进入 AI 输入，实际价差和滑点并非该输入的实测数据。

| 变量 | 默认值 / 用途 |
| --- | --- |
| `TRADE_TRADING_AI_ENABLED` | `false`，允许付费 AI 评估 |
| `TRADE_AUTOMATION_TRADING_AI_AUTO_START` | 可省略，默认跟随顾问主开关；仅需手动启动时设 `false` |
| `TRADE_TRADING_AI_AUTO_APPLY` | `false`，改为 `true` 后允许当前 PAPER 或 LIVE 模式中符合条件的策略自动调整；不允许切换执行模式 |
| `TRADE_TRADING_AI_REVIEW_INTERVAL_MS` | `1800000`，默认每 30 分钟复评；范围 900000–86400000 |
| `TRADE_TRADING_AI_TARGET_MIN_CLOSED_TRADES_PER_DAY` | `5`，完整交易数目标下限，至少 1 |
| `TRADE_TRADING_AI_TARGET_MAX_CLOSED_TRADES_PER_DAY` | `8`，目标上限，不小于下限且不超过 100；不是强制成交限额 |
| `TRADE_TRADING_AI_MAX_REVIEW_AGE_SECONDS` | `300`，从预留到应用的有效期，范围 30–600 |
| `TRADE_TRADING_AI_CANDLE_COUNT` | `96`，范围 32–199 |
| `TRADE_TRADING_AI_MINIMUM_CONFIDENCE` | `0.80`，范围 0.5–1 |
| `TRADE_TRADING_AI_TELEGRAM_ENABLED` | 可省略，配置独立 chat ID 即接入通知；`false` 可单独暂停 |
| `TRADE_TRADING_AI_TELEGRAM_BOT_TOKEN` | 留空，填写新 Bot 的 token，不能使用 X/微博共享 Bot |
| `TRADE_TRADING_AI_TELEGRAM_CHAT_ID` | 留空，该 Bot 的独立私聊或另建群的数字 chat ID |
| `TRADE_TRADING_AI_TELEGRAM_COOLDOWN_MS` | `86400000`，仅新模式/逻辑建议的最小间隔，至少 3600000；已生效变更不受此限制 |
| `TRADE_TRADING_AI_TELEGRAM_BASE_URL` | `https://api.telegram.org` |
| `TRADE_TRADING_AI_TELEGRAM_CONNECT_TIMEOUT_SECONDS` / `REQUEST_TIMEOUT_SECONDS` | `10` / `30`，后者完整名称为 `TRADE_TRADING_AI_TELEGRAM_REQUEST_TIMEOUT_SECONDS` |
| `TRADE_TRADING_AI_TELEGRAM_PROXY_ENABLED` / `PROXY_HOST` / `PROXY_PORT` | 默认 `false` / `127.0.0.1` / `7897`，都使用 `TRADE_TRADING_AI_TELEGRAM_` 前缀 |

AI 复用现有 `TRADE_AI_PROVIDER`、`TRADE_AI_MODEL`、`TRADE_AI_BASE_URL`、`TRADE_AI_API_KEY_ENV_NAME` 和所指定的密钥变量，例如 `DEEPSEEK_API_KEY`。不新增默认的真实调用，也不需要把密钥填入代码。原有交易自动任务的开关与顾问独立；运行顾问不会自行开启交易、LIVE 或 X。

`AUTO_APPLY=false` 时仅生成建议；`true` 时允许当前 PAPER 或 LIVE 模式内自动调整。修改环境变量后重启后端。驾驶舱中的启动/暂停只控制当前进程任务，不改写环境变量或执行模式。

## Telegram 隔离与投递

必须创建不同于 X/微博共享 Bot 的新 Bot，再主动与新 Bot 发起私聊或加入专用群。不同 Bot 的私聊可能具有相同的用户 chat ID，这是两个独立会话；同一个 X 审核群则会被拒绝。配置不回退到 `trade.telegram` 的 token/chat，顾问不调用 `getUpdates`，不会消费 X 的审核回调。

每次已生效 SWITCH/TUNE 均发送变更通知，包含评估编号、标的、执行模式、动作、时间、前后策略/参数版本、调参前后值与原因；不受建议冷却影响，也不占用建议窗口。非空 `logicSuggestion` 发送新模式/逻辑建议，若同轮发生变更则合并在变更通知中，并标明建议尚未实施。纯建议按标的与执行模式持久化冷却，默认一天最多一条；冷却期间仍保存数据库。普通 HOLD 不通知。过长消息截断展示，完整内容保存在评估记录。

变更提交时即保存 `PENDING`；进程在提交后、发送前中断，下一次顾问循环会先处理未尝试投递的变更。发送前原子预留 `SENDING`，同一记录不会被并发重复发送；成功为 `SENT`，超时/失败为 `UNKNOWN`，不自动重发，避免外部结果不明时重复消息。中断后 `SENDING` 需人工核对。禁用或配置失败记为 `DISABLED` / `CONFIG_ERROR`，不补发。TG 失败不会回滚已经提交的策略变更。

## 数据库、观测与回退

`okx_ai_strategy_guard` 和 `okx_ai_strategy_review` 不存储凭据。Flyway V4 在评估表追加可空 `application_json`，保留历史记录；新库与升级均按版本链自动执行，镜像数据库版本为 4。部署前按既有流程备份；不修改已发布迁移或重复执行历史补丁。接入前置条件见 [数据库说明](../../backend/src/main/resources/db/README.md)。

驾驶舱显示每日目标、交易日与时区、当前模拟会话的今日/昨日进度和已实现净收益，以及当前生效策略及参数版本、数据库预留的下次评估窗口、最近执行检查原因、AI 状态和最近 5 次评估。AI 面板随全局每 30 秒轮询、全局同步按钮、面板同步按钮和窗口重新聚焦更新；失败保留旧数据并显示错误。评估时间统一使用交易日时区，避免浏览器时区与 UTC 混淆。读取状态不触发 AI 调用；`HELD` 表示该轮保持策略，并非漏同步了切换。生效记录可展开查看变更前后参数；本次升级需执行 Flyway V4。管理认证及 CSRF 规则与现有 API 一致：

- `GET /api/trading/ai/status`：开关与应用资格、`daily` 目标/统计/近期执行检查，以及 `assessment` 同步时间/实际下次窗口/生效策略版本，不返回密钥。
- `GET /api/trading/ai/reviews?limit=20`：最近评估，limit 范围 1–100，含输入、建议、已应用前后快照 `applicationJson` 和 TG 投递状态；旧记录的 applicationJson 为 null。
- `GET /api/automation/tasks/trading-ai`：任务及最近运行结果。
- `POST /api/automation/tasks/trading-ai/start` 或 `/stop`：启动/停止。

`CONFLICT`、`STALE`、`LOW_CONFIDENCE` 表示未应用；`FAILED`/`REJECTED` 在任务状态中报失败，原有交易继续。提交结果不确定时核对同一评估、策略版本与活动选择，不自动重放。生效变更及审计由同一数据库事务维护；只有 `PENDING` 通知可在后续循环补充首次投递。下轮重新采集现状、生成独立建议。审计表长期保留，容量清理按部署备份策略单独管理。

停止顾问并设 `AUTO_APPLY=false` 可立即停止后续自动调整，但不会撤销已经生效的版本。需要回退时，在已有参数编辑界面以最新版本号保存历史参数，并切回所需策略；保留审计表用于追踪。AI 评估未经真实提供商和 Telegram 联调前，只能视为本地离线验证通过，不能据此启用 LIVE 或声称策略有效。
