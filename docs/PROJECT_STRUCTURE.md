# Project Structure

## Top Level

- `backend/`: Spring Boot 应用，负责所有服务端 API、调度任务、外部客户端、持久化和交易执行。
- `frontend/text-game/`: 文字游戏 Web 客户端，只负责浏览器交互和前端状态展示。
- `tools/polymarket/`: Python 侧 Polymarket CLOB 下单桥接、凭证调试和示例输入。
- `story/`: AI 小说生成结果和素材。这里是业务产物，不放后端代码。
- `docs/`: 结构说明、维护约定和后续设计文档。

## Backend Packages

后端按“业务域 + 层职责”组织：

- `com.trade.client.*`: 外部服务客户端，封装 OKX、Polymarket、Gemini、OpenAI-compatible API。
- `com.trade.ai.*`: 跨业务域复用的 AI 基础设施，例如 AI 响应解析错误审计。
- `com.trade.automation.application`: 自动化任务注册、启动、停止和调度编排。
- `com.trade.automation.config`: 自动化模块配置属性和 scheduler bean。
- `com.trade.automation.model`: 自动化任务、循环和状态 DTO。
- `com.trade.automation.web`: 自动化任务 HTTP API。
- `com.trade.trading.*`: OKX AI 交易域，包含行情、决策、风控、执行、持久化和调度。
- `com.trade.polymarket.*`: Polymarket AI 交易域，包含市场采样、AI 决策、下单执行和审计。
- `com.trade.story.*`: AI 小说生成域，包含趋势采集、Prompt、解析、文件持久化和调度。
- `com.trade.textgame.application`: 文字游戏会话和回合业务流程。
- `com.trade.textgame.decision`: 文字游戏 AI Prompt 和响应解析。
- `com.trade.textgame.definition`: 文字游戏主题和模式定义加载。
- `com.trade.textgame.model`: 文字游戏请求、响应和定义模型。
- `com.trade.textgame.web`: 文字游戏 HTTP API 和异常映射。

## Placement Rules

- 新的 HTTP Controller 放到对应业务域的 `web` 包。
- 调度编排、用例服务、状态机流程放到 `application` 包。
- 外部 API 细节放到 `client` 包，不直接泄漏到业务流程。
- 纯数据结构放到 `model` 包；数据库行对象和 mapper 放到 `persistence` 包。
- AI prompt 构造和 AI 响应解析放到 `decision` 包，避免和执行、风控混在一起。
- 真实密钥只通过环境变量注入；示例文件只能保留非敏感占位值。
