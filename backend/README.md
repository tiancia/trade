# Trade Backend

Spring Boot 4 + Java 21 backend for automated trading, Polymarket decisioning, AI story generation, text-game APIs, and Weibo publishing.

## Quick Start

```powershell
cd A:\trade\backend
.\mvnw test
.\mvnw spring-boot:run
```

Use `.env.example` as a variable checklist for your shell, IDE, or deployment environment. Spring Boot reads environment variables; it does not load `.env` files automatically.

By default, trading, Polymarket execution, and story generation are disabled. Enable them explicitly after database, API keys, and risk settings are ready.

## Main Structure

```text
src/main/java/com/trade/
  TradeApplication.java       Spring Boot entry point
  trading/                    OKX strategy trading domain
  polymarket/                 Polymarket AI decision and order execution domain
  story/                      AI short-story generation domain
  textgame/                   Text-game session and admin APIs
  weibo/                      Weibo OAuth and publishing domain
  client/                     Transport clients for external services
  ai/                         Shared AI persistence and parse-error auditing
  automation/                 Start/stop/status orchestration for background loops
  common/                     Business-neutral shared helpers

src/main/resources/
  application.yml             Runtime configuration and safe defaults
  db/ai_trade_mysql_schema.sql Main MySQL bootstrap schema
  db/migration/               Manual database migration scripts
  mapper/<domain>/            MyBatis XML grouped by owning domain
  textgame/stories/           Seed story JSON files

data/
  trading-state.example.json  Example local trading state file
```

## Layer Rules

- `web`: HTTP controllers and request checks only; keep business decisions out.
- `scheduler`: timed triggers only; delegate real work to application services.
- `application`: orchestrates a use case across clients, domain logic, persistence, and execution.
- `decision`, `strategy`, `risk`, `market`, `execution`, `domain`: domain rules and calculations.
- `persistence`: MyBatis mappers, row objects, repositories, and file-backed state.
- `model`: API or domain data structures with minimal behavior.
- `config`: `@ConfigurationProperties` and Spring bean wiring.
- `client`: external HTTP/WebSocket details only; no trading or publishing policy.

## Configuration

Use `.env.example` as a checklist. The checked-in configuration intentionally keeps risky loops disabled:

- `TRADE_TRADING_ENABLED=false`
- `TRADE_TRADING_WEBSOCKET_ENABLED=false`
- `TRADE_POLYMARKET_ENABLED=false`
- `TRADE_POLYMARKET_EXECUTION_ENABLED=false`
- `TRADE_STORY_ENABLED=false`

Runtime state belongs in `data/trading-state.json`, which is ignored by Git. Commit only `data/trading-state.example.json`.

## Tests

```powershell
.\mvnw test
```

Most domain code has focused unit tests under the same package path in `src/test/java`. Add tests beside the domain you change.
