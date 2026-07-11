/**
 * Text-game backend domain.
 *
 * <p>Public session APIs play published story versions, while admin APIs
 * validate, draft, replace, and publish story JSON definitions. Runtime game
 * state is persisted as session rows plus event history. Start with
 * {@link com.trade.textgame.web.TextGameController} and
 * {@link com.trade.textgame.web.TextGameAdminController}; their use cases live
 * in {@link com.trade.textgame.application.TextGameSessionService} and
 * {@link com.trade.textgame.application.TextGameAdminService}.</p>
 */
package com.trade.textgame;
