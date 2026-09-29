/**
 * Text-game backend domain.
 *
 * <p>Public session APIs play published story versions, while admin APIs
 * validate, draft, replace, and publish story JSON definitions. Runtime game
 * state is represented by GameSession and saved through TextGameSessionStore;
 * MyBatisTextGameSessionStore owns row/JSON mapping and event history. Start with
 * {@link com.trade.textgame.interfaces.web.TextGameController} and
 * {@link com.trade.textgame.interfaces.web.TextGameAdminController}; their use cases live
 * in {@link com.trade.textgame.application.service.TextGameSessionService} and
 * {@link com.trade.textgame.application.service.TextGameAdminService}. Shared use-case
 * failures live in {@code domain.exception}, pure rules in {@code domain.rule},
 * with rule Bean wiring in TextGameConfiguration, and database/configuration adapters in {@code infrastructure}.</p>
 */
package com.trade.textgame;
