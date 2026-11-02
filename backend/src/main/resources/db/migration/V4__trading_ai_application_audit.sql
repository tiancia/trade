-- Applies after V3. Adds immutable before/after evidence and pending notification metadata
-- to existing AI review rows; existing reviews are retained with NULL application_json.
-- Verify: okx_ai_strategy_review.application_json exists; Flyway current version is 4.
-- No destructive data changes. Roll back application code without dropping this audit column.
ALTER TABLE okx_ai_strategy_review ADD COLUMN application_json LONGTEXT NULL;
