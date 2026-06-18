# Backend Structure Light Cleanup Design

## Goal

Make the backend structure easier to read and maintain without changing runtime behavior. This cleanup is intentionally small: clarify package responsibilities, document allowed dependency directions, and move only low-risk shared utilities that are currently placed inside a business domain.

## Current Context

The backend is a Spring Boot Maven project under `backend/`. It already follows a mostly clear domain layout:

- `com.trade.trading`: OKX trading domain.
- `com.trade.polymarket`: Polymarket trading domain.
- `com.trade.story`: AI story generation domain.
- `com.trade.textgame`: text game backend domain.
- `com.trade.weibo`: Weibo publishing and OAuth domain.
- `com.trade.automation`: background task registration and loop management.
- `com.trade.client`: external API clients.
- `com.trade.ai`: shared AI infrastructure such as parse error auditing.

The baseline backend test suite passes with `.\mvnw test`: 129 tests, 0 failures, 1 skipped.

The main structural issue in scope for this cleanup is that a cross-domain pure helper, `TradingMath`, lives under `com.trade.trading.support` but is imported by Polymarket code. That makes Polymarket appear to depend on the OKX trading domain for a generic numeric utility.

## Non-Goals

This pass will not:

- Split large application services such as `AiStoryService`, `PolymarketMarketContextCollector`, or `MarketContextCollector`.
- Replace OKX DTOs inside `TradingDecisionContext`.
- Redesign persistence, scheduler, or controller flows.
- Change database schemas or mapper XML.
- Change API behavior, scheduler timing, prompts, trading logic, or execution safety checks.
- Move generated story files or frontend code.

Those changes are larger and should be handled in later, explicit refactors.

## Proposed Structure

Keep the existing domain-first structure, and add a small shared package for pure, dependency-light helpers:

```text
backend/src/main/java/com/trade/
  common/
    support/        Shared pure helpers used by multiple domains.
  ai/               Shared AI infrastructure.
  automation/       Background task orchestration.
  client/           External service clients and DTOs.
  polymarket/       Polymarket AI trading domain.
  story/            AI story generation domain.
  textgame/         Text game backend domain.
  trading/          OKX trading domain.
  weibo/            Weibo publishing/OAuth domain.
```

`com.trade.common.support` is for utilities that:

- have no Spring bean lifecycle;
- do not call external services;
- do not read configuration;
- do not depend on a specific business domain;
- are imported by more than one domain.

`TradingMath` should move to `com.trade.common.support.TradingMath`. All existing imports should be updated. This keeps the utility available while removing the misleading Polymarket-to-trading dependency.

## Dependency Rules

Use these rules when adding new backend code:

- Domain packages may depend on `com.trade.client`, `com.trade.ai`, and `com.trade.common` when needed.
- `com.trade.client` must stay transport-focused and should not depend on business domains.
- `com.trade.common` must stay business-neutral and must not depend on `trading`, `polymarket`, `story`, `textgame`, `weibo`, or `automation`.
- `automation` may call scheduler or lifecycle entry points from domains, but it should not contain domain business rules.
- Controllers stay in each domain's `web` package and delegate business flow to application services.
- Schedulers stay thin and delegate to application services.
- Persistence row objects, MyBatis mapper interfaces, and repository adapters stay in `persistence`.

## Implementation Plan

1. Add `com.trade.common.support` and move `TradingMath` there.
2. Update all Java imports from `com.trade.trading.support.TradingMath` to `com.trade.common.support.TradingMath`.
3. Remove the old empty `trading.support` package if no files remain.
4. Update `docs/PROJECT_STRUCTURE.md` to describe the new `common` package and dependency rules.
5. Optionally update `README.md` only if the top-level summary needs the new backend rule.
6. Run `.\mvnw test` from `backend/`.

## Testing

The required verification is:

```powershell
cd backend
.\mvnw test
```

Expected result: build success, with the same behavioral tests passing as before. Because this is a package move and import update, test failures would indicate missed imports or accidental behavior changes.

## Risks

- Package move noise can touch many files because `TradingMath` is widely used.
- Existing documentation appears to be UTF-8 content that may render incorrectly in some PowerShell output. Edits should preserve UTF-8 and avoid unrelated formatting churn.
- The working tree currently contains unrelated `story/` changes. They must remain untouched.

## Success Criteria

- Backend source has a clear `common.support` home for cross-domain pure helpers.
- Polymarket no longer imports `com.trade.trading.support.TradingMath`.
- Project structure documentation states package responsibilities and dependency rules.
- Backend tests pass.
- No unrelated `story/`, frontend, schema, or runtime output files are modified.
