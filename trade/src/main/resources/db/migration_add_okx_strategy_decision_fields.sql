ALTER TABLE `okx_decision_runs`
    ADD COLUMN `requested_order_size` decimal(38,18) AFTER `sell_base_amount`;

ALTER TABLE `okx_decision_runs`
    ADD COLUMN `strategy_bias` varchar(32) AFTER `confidence`;

ALTER TABLE `okx_decision_runs`
    ADD COLUMN `strategy_thesis` text AFTER `strategy_bias`;

ALTER TABLE `okx_decision_runs`
    ADD COLUMN `strategy_invalidation` text AFTER `strategy_thesis`;

ALTER TABLE `okx_decision_runs`
    ADD COLUMN `strategy_horizon` varchar(128) AFTER `strategy_invalidation`;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `parsed_order_size` decimal(38,18) AFTER `parsed_sell_base_amount`;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `parsed_strategy_bias` varchar(32) AFTER `parsed_confidence`;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `parsed_strategy_thesis` text AFTER `parsed_strategy_bias`;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `parsed_strategy_invalidation` text AFTER `parsed_strategy_thesis`;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `parsed_strategy_horizon` varchar(128) AFTER `parsed_strategy_invalidation`;
