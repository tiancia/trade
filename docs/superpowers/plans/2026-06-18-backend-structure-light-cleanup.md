# Backend Structure Light Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clarify backend package responsibilities by moving the shared `TradingMath` helper into a neutral `common.support` package and documenting the dependency boundary.

**Architecture:** Keep the existing domain-first Spring Boot package layout. Add only `com.trade.common.support` for pure, cross-domain helpers, update imports mechanically, and append dependency boundary rules to the existing project structure document.

**Tech Stack:** Java 21, Spring Boot, Maven Wrapper, JUnit 6, MyBatis.

---

## File Map

- Create: `backend/src/test/java/com/trade/common/support/TradingMathTest.java`
  - Direct unit coverage for the helper behavior before and after the package move.
- Move: `backend/src/main/java/com/trade/trading/support/TradingMath.java` to `backend/src/main/java/com/trade/common/support/TradingMath.java`
  - Same helper implementation, new neutral package.
- Modify: Java files under `backend/src/main/java/com/trade/**`
  - Mechanical import replacement from `com.trade.trading.support.TradingMath` to `com.trade.common.support.TradingMath`.
- Modify: `docs/PROJECT_STRUCTURE.md`
  - Append a backend dependency boundary section.

---

### Task 1: Move `TradingMath` To `common.support`

**Files:**
- Create: `backend/src/test/java/com/trade/common/support/TradingMathTest.java`
- Move: `backend/src/main/java/com/trade/trading/support/TradingMath.java` -> `backend/src/main/java/com/trade/common/support/TradingMath.java`
- Modify: Java files that import `com.trade.trading.support.TradingMath`

- [ ] **Step 1: Write the failing test for the future package**

Create `backend/src/test/java/com/trade/common/support/TradingMathTest.java`:

```java
package com.trade.common.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingMathTest {

    @Test
    void decimalReturnsZeroForBlankOrInvalidInput() {
        assertEquals(BigDecimal.ZERO, TradingMath.decimal(null));
        assertEquals(BigDecimal.ZERO, TradingMath.decimal(" "));
        assertEquals(BigDecimal.ZERO, TradingMath.decimal("not-a-number"));
    }

    @Test
    void decimalParsesTrimmedText() {
        assertEquals(new BigDecimal("12.340"), TradingMath.decimal(" 12.340 "));
    }

    @Test
    void percentChangeHandlesMissingBaseAndCalculatesRatio() {
        assertEquals(BigDecimal.ZERO, TradingMath.percentChange(new BigDecimal("10"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.2500000000"),
                TradingMath.percentChange(new BigDecimal("125"), new BigDecimal("100")));
    }

    @Test
    void clampHonorsPositiveUpperBoundOnly() {
        assertEquals(BigDecimal.ZERO, TradingMath.clamp(null, new BigDecimal("10")));
        assertEquals(new BigDecimal("5"), TradingMath.clamp(new BigDecimal("5"), new BigDecimal("10")));
        assertEquals(new BigDecimal("10"), TradingMath.clamp(new BigDecimal("15"), new BigDecimal("10")));
        assertEquals(new BigDecimal("15"), TradingMath.clamp(new BigDecimal("15"), BigDecimal.ZERO));
    }

    @Test
    void roundDownToStepKeepsValidTradingStep() {
        assertEquals(BigDecimal.ZERO, TradingMath.roundDownToStep(null, new BigDecimal("0.01")));
        assertEquals(new BigDecimal("1.23"),
                TradingMath.roundDownToStep(new BigDecimal("1.239"), new BigDecimal("0.01")));
        assertEquals(new BigDecimal("1.239"),
                TradingMath.roundDownToStep(new BigDecimal("1.239"), BigDecimal.ZERO));
    }

    @Test
    void plainReturnsNonScientificTextWithoutTrailingZeros() {
        assertEquals("0", TradingMath.plain(null));
        assertEquals("0", TradingMath.plain(new BigDecimal("0.000")));
        assertEquals("12.34", TradingMath.plain(new BigDecimal("12.3400")));
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails before the move**

Run from `backend/`:

```powershell
.\mvnw -Dtest=TradingMathTest test
```

Expected: compile failure with `cannot find symbol` for `TradingMath`, because `com.trade.common.support.TradingMath` does not exist yet.

- [ ] **Step 3: Move the helper and update its package**

Run from repository root:

```powershell
New-Item -ItemType Directory -Force backend\src\main\java\com\trade\common\support
git mv backend\src\main\java\com\trade\trading\support\TradingMath.java backend\src\main\java\com\trade\common\support\TradingMath.java
```

Change the package line in `backend/src/main/java/com/trade/common/support/TradingMath.java` to:

```java
package com.trade.common.support;
```

- [ ] **Step 4: Replace imports mechanically**

Run from repository root:

```powershell
$files = rg -l 'com\.trade\.trading\.support\.TradingMath' backend\src\main\java backend\src\test\java
foreach ($file in $files) {
    $text = Get-Content -Raw -LiteralPath $file
    $text = $text.Replace('com.trade.trading.support.TradingMath', 'com.trade.common.support.TradingMath')
    Set-Content -LiteralPath $file -Value $text -NoNewline
}
```

- [ ] **Step 5: Run the focused helper test**

Run from `backend/`:

```powershell
.\mvnw -Dtest=TradingMathTest test
```

Expected: build success and `TradingMathTest` passes.

- [ ] **Step 6: Commit the package move**

Run from repository root:

```powershell
git add backend\src\main\java backend\src\test\java\com\trade\common\support\TradingMathTest.java
git commit -m "refactor: move trading math to common support"
```

Expected: commit includes only Java source/test changes for the `TradingMath` move and import replacement.

---

### Task 2: Document Backend Dependency Boundaries

**Files:**
- Modify: `docs/PROJECT_STRUCTURE.md`

- [ ] **Step 1: Append the common package and dependency rule section**

Append this section to the end of `docs/PROJECT_STRUCTURE.md`:

```markdown

## Backend Dependency Boundaries

- `com.trade.common.*`: shared, business-neutral helpers. It must not depend on any business domain package.
- `com.trade.client.*`: external service clients and DTOs. It must stay transport-focused and must not contain business workflows.
- Domain packages (`trading`, `polymarket`, `story`, `textgame`, `weibo`) may depend on `common`, `client`, and `ai` when needed.
- `automation` may call scheduler or lifecycle entry points from domains, but it should not contain domain business rules.
- Controllers stay in each domain's `web` package and delegate business flow to application services.
- Schedulers stay thin and delegate to application services.
- Pure helpers used by more than one domain belong in `com.trade.common.support`, not in a domain-specific `support` package.
```

- [ ] **Step 2: Commit the documentation update**

Run from repository root:

```powershell
git add docs\PROJECT_STRUCTURE.md
git commit -m "docs: clarify backend dependency boundaries"
```

Expected: commit includes only `docs/PROJECT_STRUCTURE.md`.

---

### Task 3: Verify Imports, Tests, And Working Tree Scope

**Files:**
- Read-only audit of backend source and git status.

- [ ] **Step 1: Confirm no old `TradingMath` imports remain**

Run from repository root:

```powershell
rg 'com\.trade\.trading\.support\.TradingMath' backend\src\main\java backend\src\test\java
```

Expected: no output and exit code 1.

- [ ] **Step 2: Confirm the new common import is used**

Run from repository root:

```powershell
rg 'com\.trade\.common\.support\.TradingMath' backend\src\main\java backend\src\test\java
```

Expected: output includes both `trading` and `polymarket` Java files.

- [ ] **Step 3: Run the full backend test suite**

Run from `backend/`:

```powershell
.\mvnw test
```

Expected: build success, 129 or more tests run, 0 failures, 0 errors. One skipped OKX integration test may remain skipped.

- [ ] **Step 4: Check worktree scope**

Run from repository root:

```powershell
git status --short
```

Expected: only pre-existing `story/` changes remain uncommitted. There should be no uncommitted backend or docs changes after the commits above.

---

## Plan Self-Review

- Spec coverage: package move, import update, docs update, test verification, and unrelated `story/` preservation are covered.
- Scope control: the plan does not split large services, isolate OKX DTOs, change schedulers, change persistence schemas, or alter runtime logic.
- Type consistency: all package references use `com.trade.common.support.TradingMath`; all commands target the existing Maven backend.
