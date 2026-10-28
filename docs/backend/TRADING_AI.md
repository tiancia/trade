# Trading AI 策略顾问

AI 以交易日为单位制定日内短线策略，目标是在现有风控内争取扣除买卖手续费、价差和滑点后的净收益，行情允许时每天完成 **5–8 笔完整交易**。每笔指从开仓到卖出至空仓，买入、部分卖出和重放不额外计数，跨日持仓计入平仓日。次数是可调整的规划目标，不是强制下单配额，也不保证每日盈利或达到次数。

AI 读取公开行情，提出保持策略、切换已有策略或调整当前策略信号参数的建议。PAPER 模式可按开关自动应用；LIVE 仅记录建议。需要新增或修改策略代码时，AI 给出具体逻辑建议，通过独立 Telegram Bot 通知，代码由人工检查后修改。

## 运行流程

独立自动任务 `trading-ai` → MySQL 预留评估窗口 → 已确认、连续的 15m K 线与策略配置快照 → 共享 `AiTextClient` → 严格 JSON 校验 → 应用门禁 → 策略参数版本或活动策略更新 → 必要时发送逻辑建议。

- 默认每个交易日评估一次，首次启动等待 60 秒；日界线沿用 `trade.trading.risk.daily-zone`（默认 `Asia/Shanghai`，UTC+8）。例如当天 14:00 首次评估，下次窗口在次日 00:00 开放，而非次日 14:00。任务每分钟检查数据库窗口，未到期不采集行情、不调用 AI；调度、网络及停机可能延迟实际评估。数据库频率限制跨进程重启和实例生效，失败也消耗本轮窗口，避免付费请求密集重试。
- 可缩短 `REVIEW_INTERVAL_MS`（例如 3600000）开启日内复评，仍使用同一日目标。每轮附带最近最多 3 次已有建议及结果，要求说明改变当天方案的新证据；不能仅因到点或交易次数不足反复调参。建议过期时间不会越过当前交易日，已应用的策略不会在午夜自动撤销，持仓仍由原有退出和风控逻辑管理。
- 输入包含当前模拟会话的当天及前一天统计：买卖动作数、完整交易数、已实现净收益、当日手续费。查询使用半开日区间与账户版本，不截取最近一页成交。净收益已含分摊的买入和卖出费用，不重复扣减手续费字段，也不等于含浮盈亏的当日权益收益。新建/替换会话会明确标注部分日或没有历史，不能当作完整一天的表现；不合并不同模拟会话。LIVE 缺少真实成交统计时明确标记未知。
- 低于次数目标时分析行情机会和信号限制，证据不足则等待；达到或超过目标时避免无效换手，保护性退出始终不受次数目标限制。现有策略不能表达所需日内行为时只提出逻辑建议，不能虚构回测或为了凑单放宽成本、仓位与风控限制。此功能不强制日末平仓，也不在执行器中强制最低/最高笔数。
- 输入默认最近 96 根已收盘 15m K 线、已有策略参数及 PAPER 汇总绩效，不上传密钥、真实账户余额或订单。至少 32 根，最多 199 根；不得超过 `fifteen-minute-candle-limit - 1`。最新收盘最多允许落后一个周期加 60 秒，断档、重复、未收盘和非法 OHLCV 不可用于自动调整。
- 输出仅允许 `HOLD`、`SWITCH`、`TUNE`。模型信心是模型自评，不代表经校准的成功概率，也不证明策略盈利。
- TUNE 每轮最多改 6 个信号参数，每项相对当前值不超过 ±20%，并经过已有完整参数校验。金额、仓位规模、止损止盈、手续费、滑点、最低净收益和确认收盘规则不可由 AI 修改。模式变化通过切换预先配置的策略实现。
- 自动应用要求 PAPER、SPOT、已初始化且空仓的同一模拟会话，模型信心默认至少 0.80。应用前检查活动策略修订、目标参数版本、模拟会话版本、评估有效期及领导租约，并与决策、模拟成交和会话替换互斥。有持仓时仅保留建议，不改变管理持仓的策略。
- 策略切换沿用现有活动策略选择；参数变更保留不可变版本及评估编号。每轮只执行一种变更。后续确定性策略、风控、订单幂等与资金停止逻辑仍生效。
- 自动调整成功时同时推进模拟会话版本，拒绝调整前已经采集、尚未成交的旧策略决策。
- AI HTTP 请求在独立任务上执行，不持有交易决策锁。停止 AI 任务会令在途评估失效；已发出的 HTTP 请求可能仍完成，但失效结果不再应用或通知。

## 后续填写环境变量

本地 Java 进程参考 [backend/.env.example](../../backend/.env.example)；Spring Boot 不自动读取普通 `.env`，需由 IDE 或进程环境注入。生产使用 [deploy/backend.env.example](../../deploy/backend.env.example) 对应的后端 env_file。根 `.env` 仅用于基础设施。

下表开关默认值指配置类和 env 示例；当前 `application.yml` 运行模板已开启顾问、自动启动、模拟自动应用及通知，启动前核对环境覆盖。已有 `REVIEW_INTERVAL_MS=3600000` 环境变量会继续保留每小时复评，需改为 `86400000` 才使用每日默认节奏。手续费、最低净优势、风险收益门槛和日亏损限制作为只读约束进入 AI 输入，实际价差和滑点并非该输入的实测数据。

| 变量 | 默认值 / 用途 |
| --- | --- |
| `TRADE_TRADING_AI_ENABLED` | `false`，允许付费 AI 评估 |
| `TRADE_AUTOMATION_TRADING_AI_AUTO_START` | `false`，应用启动后自动启动顾问任务；也可在驾驶舱点启动 |
| `TRADE_TRADING_AI_AUTO_APPLY` | `false`，改为 `true` 后允许符合条件的 PAPER 自动调整；LIVE 始终仅建议 |
| `TRADE_TRADING_AI_REVIEW_INTERVAL_MS` | `86400000`，默认每日评估；范围 900000–86400000，缩短可日内复评 |
| `TRADE_TRADING_AI_TARGET_MIN_CLOSED_TRADES_PER_DAY` | `5`，完整交易数目标下限，至少 1 |
| `TRADE_TRADING_AI_TARGET_MAX_CLOSED_TRADES_PER_DAY` | `8`，目标上限，不小于下限且不超过 100；不是强制成交限额 |
| `TRADE_TRADING_AI_MAX_REVIEW_AGE_SECONDS` | `300`，从预留到应用的有效期，范围 30–600 |
| `TRADE_TRADING_AI_CANDLE_COUNT` | `96`，范围 32–199 |
| `TRADE_TRADING_AI_MINIMUM_CONFIDENCE` | `0.80`，范围 0.5–1 |
| `TRADE_TRADING_AI_TELEGRAM_ENABLED` | `false`，独立通知开关 |
| `TRADE_TRADING_AI_TELEGRAM_BOT_TOKEN` | 留空，填写新 Bot 的 token，不能使用 X/微博共享 Bot |
| `TRADE_TRADING_AI_TELEGRAM_CHAT_ID` | 留空，该 Bot 的独立私聊或另建群的数字 chat ID |
| `TRADE_TRADING_AI_TELEGRAM_COOLDOWN_MS` | `86400000`，通知最小间隔，至少 3600000 |
| `TRADE_TRADING_AI_TELEGRAM_BASE_URL` | `https://api.telegram.org` |
| `TRADE_TRADING_AI_TELEGRAM_CONNECT_TIMEOUT_SECONDS` / `REQUEST_TIMEOUT_SECONDS` | `10` / `30`，后者完整名称为 `TRADE_TRADING_AI_TELEGRAM_REQUEST_TIMEOUT_SECONDS` |
| `TRADE_TRADING_AI_TELEGRAM_PROXY_ENABLED` / `PROXY_HOST` / `PROXY_PORT` | 默认 `false` / `127.0.0.1` / `7897`，都使用 `TRADE_TRADING_AI_TELEGRAM_` 前缀 |

AI 复用现有 `TRADE_AI_PROVIDER`、`TRADE_AI_MODEL`、`TRADE_AI_BASE_URL`、`TRADE_AI_API_KEY_ENV_NAME` 和所指定的密钥变量，例如 `DEEPSEEK_API_KEY`。不新增默认的真实调用，也不需要把密钥填入代码。原有交易自动任务的开关与顾问独立；运行顾问不会自行开启交易、LIVE 或 X。

建议先设 `ENABLED=true` 并保持 `AUTO_APPLY=false`，核对评估与通知后再开启 PAPER 自动调整。修改环境变量后重启后端。驾驶舱中的启动/暂停只控制当前进程任务，不改写环境变量。

## Telegram 隔离与投递

必须创建不同于 X/微博共享 Bot 的新 Bot，再主动与新 Bot 发起私聊或加入专用群。不同 Bot 的私聊可能具有相同的用户 chat ID，这是两个独立会话；同一个 X 审核群则会被拒绝。配置不回退到 `trade.telegram` 的 token/chat，顾问不调用 `getUpdates`，不会消费 X 的审核回调。

仅非空的逻辑建议触发通知，普通保持、切换和调参不刷屏。按标的与执行模式做持久化冷却，默认一天最多一条；冷却期间建议仍保存在评估记录。禁用或配置失败的通知记录为 `DISABLED` / `CONFIG_ERROR`，不补发。发送前持久化 `SENDING`；成功为 `SENT`，超时/失败为 `UNKNOWN`，不自动重发，避免网络结果不明时重复消息。中断后仍是 `SENDING` 的记录需人工核对。

## 数据库、观测与回退

新增 `okx_ai_strategy_guard` 和 `okx_ai_strategy_review`，不存储凭据。新库由 schema 初始化；旧库执行 [手工建表补丁](../../backend/src/main/resources/db/upgrade/trading/2026-10-28-ai-strategy-advisor.sql)，先备份并在隔离测试库验证。启动已创建相同结构时无需重复操作。

驾驶舱显示每日目标、交易日与时区、当前模拟会话的今日/昨日进度和已实现净收益，以及 AI 状态、最近 5 次评估、原因、参数和逻辑建议，并可单独启停顾问。已有 schema 不需要为日度统计新增迁移。管理认证及 CSRF 规则与现有 API 一致：

- `GET /api/trading/ai/status`：开关与应用资格，以及 `daily` 目标/统计，不返回密钥。
- `GET /api/trading/ai/reviews?limit=20`：最近评估，limit 范围 1–100，含输入和建议 JSON。
- `GET /api/automation/tasks/trading-ai`：任务及最近运行结果。
- `POST /api/automation/tasks/trading-ai/start` 或 `/stop`：启动/停止。

`CONFLICT`、`STALE`、`LOW_CONFIDENCE` 表示未应用；`FAILED`/`REJECTED` 在任务状态中报失败，原有交易继续。`APPLY_UNKNOWN` 或中断后停留 `APPLYING` 表示需要核对参数版本及活动策略：数据库参数和文件中的活动选择不构成分布式事务，因此不会自动重放不确定的变更。下轮会重新采集现状、生成独立建议，不重试旧操作。审计表长期保留，容量清理按部署备份策略单独管理。

停止顾问并设 `AUTO_APPLY=false` 可立即停止后续自动调整，但不会撤销已经生效的版本。需要回退时，在已有参数编辑界面以最新版本号保存历史参数，并切回所需策略；保留审计表用于追踪。AI 评估未经真实提供商和 Telegram 联调前，只能视为本地离线验证通过，不能据此启用 LIVE 或声称策略有效。
