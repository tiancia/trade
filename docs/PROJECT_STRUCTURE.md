# Project Structure

这份文档固定当前重构后的边界：顶层目录按运行单元拆，后端包按业务域和层职责拆。

## Top Level

- `backend/`: Spring Boot 应用。负责 HTTP API、自动化调度、AI 决策、交易执行、外部 API 客户端、数据库持久化。
- `frontend/text-game/`: 文字游戏 Web 客户端。只负责浏览器界面、用户交互和前端状态，不承载交易或生成逻辑。
- `tools/polymarket/`: Python 侧 Polymarket CLOB 工具。后端通过脚本桥接真实下单，本地调试也放在这里。
- `story/`: AI 小说生成结果和素材。这里是运行产物，不放 Java、React 或 Python 工具代码。
- `docs/`: 项目结构、维护规则、后续设计说明。

## Backend Domains

- `com.trade.trading.*`: OKX AI 交易域。包含行情采集、事件检测、AI Prompt、决策解析、风控、订单执行、交易状态持久化和调度。
- `com.trade.polymarket.*`: Polymarket AI 交易域。包含市场发现、盘口采样、AI Prompt、决策解析、下单执行、地理限制检查和审计。
- `com.trade.story.*`: AI 小说生成域。包含热点采集、Prompt、响应解析、文件保存和定时生成。
- `com.trade.textgame.*`: 文字游戏后端域。包含会话流程、AI 回合生成、主题/模式定义加载、HTTP API。
- `com.trade.automation.*`: 自动化编排域。统一注册、启动、停止和查看后台循环任务。
- `com.trade.client.*`: 外部服务客户端。封装 OKX、Polymarket、Gemini、OpenAI-compatible API，不放业务流程。
- `com.trade.ai.*`: 跨业务域复用的 AI 基础设施，例如 AI 响应解析错误审计。

## Backend Layers

- `application`: 用例服务、状态机流程、任务注册和编排。
- `config`: `@ConfigurationProperties`、Bean 装配、模块配置。
- `decision`: Prompt 构造、AI 响应解析、决策格式校验。
- `execution`: 下单、执行器、外部命令调用等有副作用的动作。
- `market` 或 `trend`: 行情、盘口、榜单、趋势等上下文采集。
- `model`: 请求、响应、领域快照、枚举和值对象。
- `persistence`: 数据库行对象、Mapper、审计/状态存储。
- `risk`: 风控规则和风控评估。
- `scheduler`: 定时任务入口。
- `support`: 小范围纯工具函数。
- `web`: Controller、HTTP DTO 入口和异常映射。

## Placement Rules

- 新 HTTP 接口放到对应业务域的 `web` 包。
- 调度入口只负责触发，复杂业务流程放到 `application`。
- 外部 API 细节留在 `client`，业务域通过明确方法调用，不直接拼 HTTP 请求。
- Prompt 构造和 AI 响应解析放到 `decision`，不要混进执行器或 Controller。
- 真实下单、外部命令、文件写入等副作用放到 `execution` 或 `persistence`。
- 数据库表结构和 MyBatis XML 仍放在 `backend/src/main/resources/db` 与 `backend/src/main/resources/mapper`。
- 真实密钥只通过环境变量或本地 `.env` 注入；示例文件只能保留非敏感占位值。

## Runtime Paths

- 后端默认从 `backend/` 启动；相对路径会先按后端工作目录解析，再尝试仓库根目录。
- Polymarket 下单脚本默认路径是 `tools/polymarket/polymarket_place_order.py`，可用 `POLYMARKET_ORDER_SCRIPT` 覆盖。
- OKX 本地交易状态默认写到 `backend/data/trading-state.json`。
- 小说输出默认写到 `story/`，可通过 `trade.story.output-dir` 覆盖。
