ALTER TABLE `okx_decision_runs`
    ADD COLUMN `win_probability` decimal(38,18) AFTER `sell_base_amount`;

ALTER TABLE `okx_decision_runs`
    ADD COLUMN `confidence` decimal(38,18) AFTER `win_probability`;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `parsed_win_probability` decimal(38,18) AFTER `parsed_sell_base_amount`;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `parsed_confidence` decimal(38,18) AFTER `parsed_win_probability`;
