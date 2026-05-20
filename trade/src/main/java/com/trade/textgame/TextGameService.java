package com.trade.textgame;

import com.trade.client.ai.AiTextClient;
import com.trade.textgame.model.CreateTextGameSessionRequest;
import com.trade.textgame.model.SubmitTextGameChoiceRequest;
import com.trade.textgame.model.SubmitTextGameInterludeActionRequest;
import com.trade.textgame.model.TextGameActionDefinition;
import com.trade.textgame.model.TextGameCatalogResponse;
import com.trade.textgame.model.TextGameChoice;
import com.trade.textgame.model.TextGameEnding;
import com.trade.textgame.model.TextGameInterludeActionLogEntry;
import com.trade.textgame.model.TextGameInterludeView;
import com.trade.textgame.model.TextGameModeDefinition;
import com.trade.textgame.model.TextGameModeSummary;
import com.trade.textgame.model.TextGameResolutionView;
import com.trade.textgame.model.TextGameScene;
import com.trade.textgame.model.TextGameSessionResponse;
import com.trade.textgame.model.TextGameStageDefinition;
import com.trade.textgame.model.TextGameStageView;
import com.trade.textgame.model.TextGameThemeDefinition;
import com.trade.textgame.model.TextGameThemeSummary;
import com.trade.textgame.model.TextGameTurnOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class TextGameService {
    private static final Logger log = LoggerFactory.getLogger(TextGameService.class);
    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final int INTERLUDE_STEPS_REQUIRED = 4;

    private static final String PHASE_DECISION = "decision";
    private static final String PHASE_INTERLUDE = "interlude";
    private static final String PHASE_SETTLING = "settling";
    private static final String PHASE_COMPLETED = "completed";
    private static final String PHASE_ERROR = "error";

    private static final String RESOLUTION_NONE = "none";
    private static final String RESOLUTION_PENDING = "pending";
    private static final String RESOLUTION_READY = "ready";
    private static final String RESOLUTION_ERROR = "error";

    private static final List<TextGameActionDefinition> FALLBACK_INTERLUDE_ACTIONS = List.of(
            new TextGameActionDefinition(
                    "day_job",
                    "Short shift",
                    "Get cash at a physical cost.",
                    Map.of("money", 180, "health", -3, "risk", 1),
                    List.of("You take a short shift and leave with sore shoulders but a little more cash."),
                    Map.of(),
                    Map.of()
            ),
            new TextGameActionDefinition(
                    "rest",
                    "Rest",
                    "Recover health and lower risk.",
                    Map.of("health", 6, "risk", -2),
                    List.of("You slow down for a day, catch up on sleep, and steady your breathing."),
                    Map.of(),
                    Map.of()
            ),
            new TextGameActionDefinition(
                    "practice",
                    "Practice",
                    "Build skill with no immediate pay.",
                    Map.of("skill", 4, "health", -1),
                    List.of("You spend the day drilling one useful skill until the work starts to feel repeatable."),
                    Map.of(),
                    Map.of()
            )
    );
    private static final List<TextGameActionDefinition> FALLBACK_SETTLING_ACTIONS = List.of(
            new TextGameActionDefinition(
                    "review_notes",
                    "Review notes",
                    "Sort the last few days while the next scene is prepared.",
                    Map.of(),
                    List.of("You sort receipts, messages, and loose plans without changing the numbers."),
                    Map.of(),
                    Map.of()
            )
    );

    private final AiTextClient aiTextClient;
    private final TextGamePromptBuilder promptBuilder;
    private final TextGameResponseParser responseParser;
    private final TextGameDefinitionRegistry definitionRegistry;
    private final Executor resolutionExecutor;
    private final ConcurrentHashMap<UUID, TextGameSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public TextGameService(
            AiTextClient aiTextClient,
            TextGamePromptBuilder promptBuilder,
            TextGameResponseParser responseParser,
            TextGameDefinitionRegistry definitionRegistry
    ) {
        this(aiTextClient, promptBuilder, responseParser, definitionRegistry, newResolutionExecutor());
    }

    TextGameService(
            AiTextClient aiTextClient,
            TextGamePromptBuilder promptBuilder,
            TextGameResponseParser responseParser,
            TextGameDefinitionRegistry definitionRegistry,
            Executor resolutionExecutor
    ) {
        this.aiTextClient = aiTextClient;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.definitionRegistry = definitionRegistry;
        this.resolutionExecutor = resolutionExecutor;
    }

    public TextGameCatalogResponse catalog() {
        return new TextGameCatalogResponse(
                definitionRegistry.themes().stream().map(TextGameThemeSummary::from).toList(),
                definitionRegistry.modes().stream().map(TextGameModeSummary::from).toList()
        );
    }

    public TextGameSessionResponse createSession(CreateTextGameSessionRequest request) {
        cleanupExpiredSessions();
        String themeId = hasText(request == null ? null : request.themeId())
                ? request.themeId().trim()
                : definitionRegistry.defaultThemeId();
        String modeId = hasText(request == null ? null : request.modeId())
                ? request.modeId().trim()
                : definitionRegistry.defaultModeId();
        TextGameThemeDefinition theme = definitionRegistry.theme(themeId);
        TextGameModeDefinition mode = definitionRegistry.mode(modeId);
        UUID sessionId = UUID.randomUUID();
        Map<String, Integer> stats = normalizedInitialStats(theme);
        TextGameStageDefinition stage = mode.stageForTurn(0);
        String prompt = promptBuilder.buildOpeningPrompt(theme, mode, stage, stats);
        TextGameScene scene = generateOpening(prompt, sessionId);

        TextGameSession session = new TextGameSession(sessionId, theme, mode, stats, scene);
        sessions.put(sessionId, session);
        log.info(
                "Text game session created: sessionId={}, themeId={}, modeId={}, maxTurns={}",
                sessionId,
                theme.id(),
                mode.id(),
                mode.maxTurns()
        );
        return toResponse(session);
    }

    public TextGameSessionResponse getSession(String sessionId) {
        cleanupExpiredSessions();
        TextGameSession session = requireSession(sessionId);
        session.lock.lock();
        try {
            session.touch();
            return toResponse(session);
        } finally {
            session.lock.unlock();
        }
    }

    public TextGameSessionResponse submitChoice(String sessionId, SubmitTextGameChoiceRequest request) {
        cleanupExpiredSessions();
        TextGameSession session = requireSession(sessionId);
        PendingResolution resolutionToSchedule;
        TextGameSessionResponse response;
        session.lock.lock();
        try {
            session.touch();
            if (session.completed) {
                throw new TextGameConflictException("Text game session is already completed");
            }
            if (session.pendingResolution != null) {
                throw new TextGameConflictException("Text game session is waiting for the current choice resolution");
            }
            if (request == null || !hasText(request.choiceId())) {
                throw new IllegalArgumentException("choiceId is required");
            }
            if (request.turn() != null && request.turn() != session.turn) {
                throw new TextGameConflictException(
                        "Text game session has already advanced to turn " + session.turn
                );
            }
            TextGameChoice selectedChoice = requireChoice(session.scene, request.choiceId());
            int nextTurn = session.turn + 1;
            boolean finalTurn = nextTurn >= session.mode.maxTurns();
            TextGameStageDefinition nextStage = session.mode.stageForTurn(nextTurn);
            int nextDay = session.mode.dayForTurn(nextTurn);
            String prompt = promptBuilder.buildTurnPrompt(
                    session.theme,
                    session.mode,
                    session.sessionId,
                    session.turn,
                    nextTurn,
                    nextDay,
                    nextStage,
                    session.stats,
                    session.scene,
                    selectedChoice,
                    session.lastResult,
                    historyView(session.history),
                    finalTurn
            );

            PendingResolution pendingResolution = new PendingResolution(
                    selectedChoice,
                    nextTurn,
                    nextDay,
                    nextStage,
                    finalTurn,
                    prompt
            );
            session.pendingResolution = pendingResolution;
            session.interludeStep = 0;
            session.interludeLog.clear();
            resolutionToSchedule = pendingResolution;
            response = toResponse(session);
        } finally {
            session.lock.unlock();
        }
        scheduleResolution(session.sessionId, resolutionToSchedule.id);
        return response;
    }

    public TextGameSessionResponse submitInterludeAction(
            String sessionId,
            SubmitTextGameInterludeActionRequest request
    ) {
        cleanupExpiredSessions();
        TextGameSession session = requireSession(sessionId);
        session.lock.lock();
        try {
            session.touch();
            if (session.completed || session.pendingResolution == null) {
                return toResponse(session);
            }
            PendingResolution pendingResolution = session.pendingResolution;
            if (pendingResolution.status == ResolutionStatus.ERROR) {
                throw new TextGameConflictException("Text game resolution failed; retry before more actions");
            }
            if (request == null || !hasText(request.actionId())) {
                throw new IllegalArgumentException("actionId is required");
            }
            if (request.turn() != null && request.turn() != pendingResolution.nextTurn) {
                throw new TextGameConflictException(
                        "Text game is waiting for turn " + pendingResolution.nextTurn
                );
            }
            int expectedStep = session.interludeLog.size() + 1;
            if (request.step() != null && request.step() != expectedStep) {
                throw new TextGameConflictException("Text game interlude action has already advanced");
            }

            boolean settling = session.interludeStep >= INTERLUDE_STEPS_REQUIRED;
            List<TextGameActionDefinition> actions = candidateActions(session, settling);
            TextGameActionDefinition action = requireAction(actions, request.actionId());
            Map<String, Integer> statsDelta = settling ? Map.of() : copyMutableStats(action.statsDelta());
            if (!settling) {
                session.stats = applyStatsDelta(session.stats, statsDelta);
                session.interludeStep++;
            }

            int actionDay = actionDay(session, settling);
            String feedback = renderFeedback(action, expectedStep, actionDay, settling);
            session.interludeLog.add(new TextGameInterludeActionLogEntry(
                    pendingResolution.nextTurn,
                    expectedStep,
                    actionDay,
                    action.id(),
                    action.label(),
                    feedback,
                    copyStats(statsDelta),
                    copyStats(session.stats),
                    settling
            ));

            return toResponse(session);
        } finally {
            session.lock.unlock();
        }
    }

    public TextGameSessionResponse retryResolution(String sessionId) {
        cleanupExpiredSessions();
        TextGameSession session = requireSession(sessionId);
        String resolutionId;
        TextGameSessionResponse response;
        session.lock.lock();
        try {
            session.touch();
            PendingResolution pendingResolution = session.pendingResolution;
            if (pendingResolution == null) {
                throw new TextGameConflictException("Text game session has no pending resolution to retry");
            }
            if (pendingResolution.status != ResolutionStatus.ERROR) {
                throw new TextGameConflictException("Text game resolution is not in an error state");
            }
            pendingResolution.status = ResolutionStatus.PENDING;
            pendingResolution.errorMessage = null;
            resolutionId = pendingResolution.id;
            response = toResponse(session);
        } finally {
            session.lock.unlock();
        }
        scheduleResolution(session.sessionId, resolutionId);
        return response;
    }

    public TextGameSessionResponse advanceResolution(String sessionId) {
        cleanupExpiredSessions();
        TextGameSession session = requireSession(sessionId);
        session.lock.lock();
        try {
            session.touch();
            PendingResolution pendingResolution = session.pendingResolution;
            if (pendingResolution == null) {
                throw new TextGameConflictException("Text game session has no pending resolution to advance");
            }
            if (pendingResolution.status == ResolutionStatus.ERROR) {
                throw new TextGameConflictException("Text game resolution failed; retry before advancing");
            }
            if (pendingResolution.status != ResolutionStatus.READY) {
                throw new TextGameConflictException("Text game resolution is still generating");
            }
            commitResolution(session);
            return toResponse(session);
        } finally {
            session.lock.unlock();
        }
    }

    public void deleteSession(String sessionId) {
        UUID id = parseSessionId(sessionId);
        TextGameSession removed = sessions.remove(id);
        if (removed != null) {
            log.info("Text game session deleted: sessionId={}", id);
        }
    }

    private void scheduleResolution(UUID sessionId, String resolutionId) {
        resolutionExecutor.execute(() -> resolvePendingTurn(sessionId, resolutionId));
    }

    private void resolvePendingTurn(UUID sessionId, String resolutionId) {
        TextGameSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        PendingResolution pendingResolution;
        session.lock.lock();
        try {
            pendingResolution = session.pendingResolution;
            if (pendingResolution == null || !pendingResolution.id.equals(resolutionId)) {
                return;
            }
        } finally {
            session.lock.unlock();
        }

        TextGameTurnOutcome outcome = null;
        String errorMessage = null;
        try {
            outcome = generateTurn(
                    pendingResolution.prompt,
                    sessionId,
                    pendingResolution.nextTurn,
                    pendingResolution.finalTurn
            );
        } catch (TextGameAiException e) {
            errorMessage = e.getMessage();
        } catch (Exception e) {
            errorMessage = "Generate text game turn failed: " + e.getMessage();
        }

        session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        session.lock.lock();
        try {
            PendingResolution current = session.pendingResolution;
            if (current == null || !current.id.equals(resolutionId)) {
                return;
            }
            if (errorMessage == null) {
                current.outcome = outcome;
                current.status = ResolutionStatus.READY;
                current.errorMessage = null;
                current.completedAt = Instant.now();
            } else {
                current.status = ResolutionStatus.ERROR;
                current.errorMessage = errorMessage;
                current.completedAt = Instant.now();
            }
        } finally {
            session.lock.unlock();
        }
    }

    private void commitResolution(TextGameSession session) {
        PendingResolution pendingResolution = session.pendingResolution;
        TextGameTurnOutcome outcome = pendingResolution.outcome;
        Map<String, Integer> nextStats = applyStatsDelta(session.stats, outcome.statsDelta());
        session.turn = pendingResolution.nextTurn;
        session.stats = nextStats;
        session.lastResult = outcome.result();
        if (pendingResolution.finalTurn) {
            TextGameEnding ending = outcome.ending().withFinalStats(nextStats);
            session.completed = true;
            session.ending = ending;
            session.scene = new TextGameScene(ending.title(), ending.summary(), List.of());
        } else {
            session.scene = outcome.scene();
        }
        session.history.add(new TurnRecord(
                pendingResolution.nextTurn,
                pendingResolution.nextDay,
                pendingResolution.nextStage.id(),
                pendingResolution.selectedChoice.id(),
                pendingResolution.selectedChoice.label(),
                outcome.result(),
                nextStats
        ));
        session.pendingResolution = null;
        session.interludeStep = 0;
        session.interludeLog.clear();
        log.info(
                "Text game turn advanced: sessionId={}, turn={}, day={}, completed={}, stats={}",
                session.sessionId,
                session.turn,
                pendingResolution.nextDay,
                session.completed,
                session.stats
        );
    }

    private TextGameScene generateOpening(String prompt, UUID sessionId) {
        try {
            String rawResponse = aiTextClient.generateJson(prompt);
            log.info("Text game AI opening response: sessionId={}\n{}", sessionId, rawResponse);
            return responseParser.parseOpening(rawResponse);
        } catch (Exception e) {
            throw new TextGameAiException("Generate text game opening failed: " + e.getMessage(), e);
        }
    }

    private TextGameTurnOutcome generateTurn(String prompt, UUID sessionId, int turn, boolean finalTurn) {
        try {
            String rawResponse = aiTextClient.generateJson(prompt);
            log.info("Text game AI turn response: sessionId={}, turn={}\n{}", sessionId, turn, rawResponse);
            return responseParser.parseTurn(rawResponse, finalTurn);
        } catch (Exception e) {
            throw new TextGameAiException("Generate text game turn failed: " + e.getMessage(), e);
        }
    }

    private TextGameSessionResponse toResponse(TextGameSession session) {
        TextGameStageDefinition stage = session.pendingResolution == null
                ? session.mode.stageForTurn(session.turn)
                : session.mode.stageForTurn(session.pendingResolution.nextTurn);
        return new TextGameSessionResponse(
                session.sessionId,
                session.theme.id(),
                session.mode.id(),
                phaseFor(session),
                session.turn,
                session.mode.maxTurns(),
                displayDay(session),
                TextGameStageView.from(stage),
                copyStats(session.stats),
                session.lastResult,
                session.scene,
                session.ending,
                resolutionView(session.pendingResolution),
                interludeView(session),
                session.completed
        );
    }

    private TextGameResolutionView resolutionView(PendingResolution pendingResolution) {
        if (pendingResolution == null) {
            return new TextGameResolutionView(RESOLUTION_NONE, null, null, false);
        }
        return new TextGameResolutionView(
                resolutionStatus(pendingResolution.status),
                pendingResolution.nextTurn,
                pendingResolution.errorMessage,
                pendingResolution.status == ResolutionStatus.READY
        );
    }

    private TextGameInterludeView interludeView(TextGameSession session) {
        PendingResolution pendingResolution = session.pendingResolution;
        if (pendingResolution == null) {
            return null;
        }
        List<TextGameActionDefinition> actions = List.of();
        if (pendingResolution.status != ResolutionStatus.ERROR) {
            actions = candidateActions(session, session.interludeStep >= INTERLUDE_STEPS_REQUIRED);
        }
        String recentFeedback = session.interludeLog.isEmpty()
                ? null
                : session.interludeLog.getLast().feedback();
        return new TextGameInterludeView(
                pendingResolution.nextTurn,
                session.interludeStep,
                INTERLUDE_STEPS_REQUIRED,
                displayDay(session),
                session.interludeLog.size() + 1,
                actions,
                recentFeedback,
                List.copyOf(session.interludeLog)
        );
    }

    private int displayDay(TextGameSession session) {
        PendingResolution pendingResolution = session.pendingResolution;
        if (pendingResolution == null) {
            return session.mode.dayForTurn(session.turn);
        }
        int baseDay = session.mode.dayForTurn(session.turn);
        if (session.interludeStep < INTERLUDE_STEPS_REQUIRED) {
            return Math.min(pendingResolution.nextDay - 1, baseDay + session.interludeStep + 1);
        }
        return Math.max(baseDay, pendingResolution.nextDay - 1);
    }

    private int actionDay(TextGameSession session, boolean settling) {
        PendingResolution pendingResolution = session.pendingResolution;
        int baseDay = session.mode.dayForTurn(session.turn);
        if (settling) {
            return Math.max(baseDay, pendingResolution.nextDay - 1);
        }
        return Math.min(pendingResolution.nextDay - 1, baseDay + session.interludeStep);
    }

    private TextGameSession requireSession(String sessionId) {
        UUID id = parseSessionId(sessionId);
        TextGameSession session = sessions.get(id);
        if (session == null) {
            throw new TextGameNotFoundException("Text game session not found: " + sessionId);
        }
        return session;
    }

    private static TextGameChoice requireChoice(TextGameScene scene, String choiceId) {
        String normalized = choiceId.trim().toUpperCase();
        if (scene == null || scene.choices() == null) {
            throw new IllegalArgumentException("Text game session has no active choices");
        }
        for (TextGameChoice choice : scene.choices()) {
            if (normalized.equals(choice.id())) {
                return choice;
            }
        }
        throw new IllegalArgumentException("Invalid text game choiceId: " + choiceId);
    }

    private static TextGameActionDefinition requireAction(List<TextGameActionDefinition> actions, String actionId) {
        String normalized = actionId.trim();
        for (TextGameActionDefinition action : actions) {
            if (normalized.equals(action.id())) {
                return action;
            }
        }
        throw new IllegalArgumentException("Invalid text game interlude actionId: " + actionId);
    }

    private static List<TextGameActionDefinition> interludeActions(TextGameThemeDefinition theme) {
        if (theme.interludeActions() == null || theme.interludeActions().isEmpty()) {
            return FALLBACK_INTERLUDE_ACTIONS;
        }
        return theme.interludeActions();
    }

    private static List<TextGameActionDefinition> settlingActions(TextGameThemeDefinition theme) {
        if (theme.settlingActions() == null || theme.settlingActions().isEmpty()) {
            return FALLBACK_SETTLING_ACTIONS;
        }
        return theme.settlingActions();
    }

    private static List<TextGameActionDefinition> candidateActions(TextGameSession session, boolean settling) {
        List<TextGameActionDefinition> actions = settling
                ? settlingActions(session.theme)
                : interludeActions(session.theme);
        int turn = session.pendingResolution == null ? session.turn : session.pendingResolution.nextTurn;
        String stageId = session.pendingResolution == null
                ? session.mode.stageForTurn(session.turn).id()
                : session.pendingResolution.nextStage.id();
        return availableActions(
                actions,
                session.stats,
                turn,
                session.interludeLog.size(),
                stageId,
                recentActionIds(session, settling)
        );
    }

    private static List<TextGameActionDefinition> availableActions(
            List<TextGameActionDefinition> actions,
            Map<String, Integer> stats,
            int turn,
            int interludeLogSize,
            String stageId,
            List<String> recentActionIds
    ) {
        List<TextGameActionDefinition> filtered = actions.stream()
                .filter(action -> hasText(action.id()))
                .filter(action -> isAllowedByStats(action, stats))
                .toList();
        if (filtered.isEmpty()) {
            return List.of();
        }

        int desiredCount = Math.min(3, filtered.size());
        List<TextGameActionDefinition> cooled = withoutRecentActions(filtered, recentActionIds);
        if (cooled.size() < desiredCount && !recentActionIds.isEmpty()) {
            cooled = withoutRecentActions(filtered, recentActionIds.subList(0, 1));
        }
        if (cooled.size() < desiredCount) {
            cooled = filtered;
        }

        int start = Math.floorMod(turn + interludeLogSize, cooled.size());
        List<TextGameActionDefinition> rotated = new ArrayList<>(cooled.size());
        for (int i = 0; i < cooled.size(); i++) {
            rotated.add(cooled.get((start + i) % cooled.size()));
        }

        rotated.sort(Comparator.comparingInt(
                (TextGameActionDefinition action) -> actionScore(action, stats, stageId)
        ).reversed());
        return diversifyActions(rotated, desiredCount);
    }

    private static List<String> recentActionIds(TextGameSession session, boolean settling) {
        List<String> recentIds = new ArrayList<>(2);
        for (int i = session.interludeLog.size() - 1; i >= 0 && recentIds.size() < 2; i--) {
            TextGameInterludeActionLogEntry entry = session.interludeLog.get(i);
            if (entry.settling() == settling && !recentIds.contains(entry.actionId())) {
                recentIds.add(entry.actionId());
            }
        }
        return recentIds;
    }

    private static List<TextGameActionDefinition> withoutRecentActions(
            List<TextGameActionDefinition> actions,
            List<String> recentActionIds
    ) {
        if (recentActionIds.isEmpty()) {
            return actions;
        }
        return actions.stream()
                .filter(action -> !recentActionIds.contains(action.id()))
                .toList();
    }

    private static List<TextGameActionDefinition> diversifyActions(
            List<TextGameActionDefinition> actions,
            int desiredCount
    ) {
        List<TextGameActionDefinition> selected = new ArrayList<>(desiredCount);
        List<String> selectedCategories = new ArrayList<>(desiredCount);
        for (TextGameActionDefinition action : actions) {
            String category = primaryImpact(action);
            if (!selectedCategories.contains(category)) {
                selected.add(action);
                selectedCategories.add(category);
            }
            if (selected.size() == desiredCount) {
                return selected;
            }
        }
        for (TextGameActionDefinition action : actions) {
            if (!selected.contains(action)) {
                selected.add(action);
            }
            if (selected.size() == desiredCount) {
                return selected;
            }
        }
        return selected;
    }

    private static int actionScore(
            TextGameActionDefinition action,
            Map<String, Integer> stats,
            String stageId
    ) {
        Map<String, Integer> delta = action.statsDelta();
        int score = 0;
        int money = stats.getOrDefault("money", 0);
        int health = stats.getOrDefault("health", 0);
        int skill = stats.getOrDefault("skill", 0);
        int network = stats.getOrDefault("network", 0);
        int reputation = stats.getOrDefault("reputation", 0);
        int risk = stats.getOrDefault("risk", 0);

        if (money <= -2500 && deltaValue(delta, "money") > 0) {
            score += 36;
        } else if (money <= -1000 && deltaValue(delta, "money") > 0) {
            score += 24;
        }
        if (health <= 35 && deltaValue(delta, "health") > 0) {
            score += 30;
        }
        if (health <= 35 && deltaValue(delta, "health") < 0) {
            score -= 14;
        }
        if (risk >= 65 && deltaValue(delta, "risk") < 0) {
            score += 30;
        }
        if (risk >= 65 && deltaValue(delta, "risk") > 0) {
            score -= 16;
        }
        if (skill < 40 && deltaValue(delta, "skill") > 0) {
            score += 12;
        }
        if (network < 30 && deltaValue(delta, "network") > 0) {
            score += 10;
        }
        if (reputation < 30 && deltaValue(delta, "reputation") > 0) {
            score += 8;
        }

        if ("opening_crisis".equals(stageId)) {
            score += positive(delta, "money") / 20;
            score += positive(delta, "health");
            score += positiveNegated(delta, "risk") * 2;
        } else if ("growth_split".equals(stageId)) {
            score += positive(delta, "skill") * 3;
            score += positive(delta, "network") * 2;
            score += positive(delta, "reputation") * 2;
        } else if ("key_opportunity".equals(stageId)) {
            score += positive(delta, "network") * 2;
            score += positive(delta, "reputation") * 3;
            score += positive(delta, "skill") * 2;
            score += positive(delta, "money") / 30;
        } else if ("settlement_echo".equals(stageId)) {
            score += positive(delta, "reputation") * 3;
            score += positive(delta, "health") * 2;
            score += positiveNegated(delta, "risk") * 3;
        }
        return score;
    }

    private static String primaryImpact(TextGameActionDefinition action) {
        Map<String, Integer> delta = action.statsDelta();
        int cash = positive(delta, "money");
        int stability = positive(delta, "health") + positiveNegated(delta, "risk") * 2;
        int growth = positive(delta, "skill") + positive(delta, "network") + positive(delta, "reputation");
        int riskTaking = positive(delta, "risk") + positiveNegated(delta, "health");
        int max = Math.max(Math.max(cash, stability), Math.max(growth, riskTaking));
        if (max <= 0) {
            return "maintenance";
        }
        if (cash == max) {
            return "cash";
        }
        if (stability == max) {
            return "stability";
        }
        if (growth == max) {
            return "growth";
        }
        return "risk";
    }

    private static int deltaValue(Map<String, Integer> delta, String key) {
        return delta.getOrDefault(key, 0);
    }

    private static int positive(Map<String, Integer> delta, String key) {
        return Math.max(0, deltaValue(delta, key));
    }

    private static int positiveNegated(Map<String, Integer> delta, String key) {
        return Math.max(0, -deltaValue(delta, key));
    }

    private static boolean isAllowedByStats(TextGameActionDefinition action, Map<String, Integer> stats) {
        for (Map.Entry<String, Integer> entry : action.minStats().entrySet()) {
            if (stats.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> entry : action.maxStats().entrySet()) {
            if (stats.getOrDefault(entry.getKey(), 0) > entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static String renderFeedback(TextGameActionDefinition action, int step, int day, boolean settling) {
        if (!action.feedbackTemplates().isEmpty()) {
            String template = action.feedbackTemplates().get((step - 1) % action.feedbackTemplates().size());
            return template
                    .replace("{label}", action.label())
                    .replace("{step}", Integer.toString(step))
                    .replace("{day}", Integer.toString(day));
        }
        if (settling) {
            return action.label() + " finished. No stats or day count changed.";
        }
        return action.label() + " finished. The day moves forward.";
    }

    private static UUID parseSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid text game sessionId: " + sessionId);
        }
    }

    private void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccessedAt.isBefore(cutoff));
    }

    private static Map<String, Integer> normalizedInitialStats(TextGameThemeDefinition theme) {
        LinkedHashMap<String, Integer> stats = new LinkedHashMap<>();
        stats.put("money", 0);
        stats.put("health", 55);
        stats.put("skill", 20);
        stats.put("network", 15);
        stats.put("reputation", 10);
        stats.put("risk", 30);
        if (theme.initialStats() != null) {
            stats.putAll(theme.initialStats());
        }
        return copyStats(stats);
    }

    private static Map<String, Integer> applyStatsDelta(
            Map<String, Integer> currentStats,
            Map<String, Integer> statsDelta
    ) {
        LinkedHashMap<String, Integer> nextStats = new LinkedHashMap<>();
        Map<String, Integer> delta = statsDelta == null ? Map.of() : statsDelta;
        for (Map.Entry<String, Integer> entry : currentStats.entrySet()) {
            int valueDelta = delta.getOrDefault(entry.getKey(), 0);
            nextStats.put(entry.getKey(), clampStat(entry.getKey(), entry.getValue() + valueDelta));
        }
        return copyStats(nextStats);
    }

    private static int clampStat(String stat, int value) {
        if ("money".equals(stat)) {
            return Math.max(-10000, Math.min(500000, value));
        }
        return Math.max(0, Math.min(100, value));
    }

    private static List<Map<String, Object>> historyView(List<TurnRecord> history) {
        int start = Math.max(0, history.size() - 8);
        List<Map<String, Object>> view = new ArrayList<>();
        for (int i = start; i < history.size(); i++) {
            TurnRecord record = history.get(i);
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("turn", record.turn());
            item.put("day", record.day());
            item.put("stageId", record.stageId());
            item.put("choiceId", record.choiceId());
            item.put("choiceLabel", record.choiceLabel());
            item.put("result", record.result());
            item.put("stats", record.stats());
            view.add(item);
        }
        return view;
    }

    private static Map<String, Integer> copyStats(Map<String, Integer> stats) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(stats));
    }

    private static Map<String, Integer> copyMutableStats(Map<String, Integer> stats) {
        return new LinkedHashMap<>(stats == null ? Map.of() : stats);
    }

    private static String phaseFor(TextGameSession session) {
        if (session.completed) {
            return PHASE_COMPLETED;
        }
        PendingResolution pendingResolution = session.pendingResolution;
        if (pendingResolution == null) {
            return PHASE_DECISION;
        }
        if (pendingResolution.status == ResolutionStatus.ERROR) {
            return PHASE_ERROR;
        }
        if (session.interludeStep < INTERLUDE_STEPS_REQUIRED) {
            return PHASE_INTERLUDE;
        }
        return PHASE_SETTLING;
    }

    private static String resolutionStatus(ResolutionStatus status) {
        return switch (status) {
            case PENDING -> RESOLUTION_PENDING;
            case READY -> RESOLUTION_READY;
            case ERROR -> RESOLUTION_ERROR;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Executor newResolutionExecutor() {
        AtomicInteger index = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "text-game-resolution-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(threadFactory);
    }

    private enum ResolutionStatus {
        PENDING,
        READY,
        ERROR
    }

    private static class PendingResolution {
        private final String id = UUID.randomUUID().toString();
        private final Instant createdAt = Instant.now();
        private final TextGameChoice selectedChoice;
        private final int nextTurn;
        private final int nextDay;
        private final TextGameStageDefinition nextStage;
        private final boolean finalTurn;
        private final String prompt;
        private ResolutionStatus status = ResolutionStatus.PENDING;
        private TextGameTurnOutcome outcome;
        private String errorMessage;
        private Instant completedAt;

        private PendingResolution(
                TextGameChoice selectedChoice,
                int nextTurn,
                int nextDay,
                TextGameStageDefinition nextStage,
                boolean finalTurn,
                String prompt
        ) {
            this.selectedChoice = selectedChoice;
            this.nextTurn = nextTurn;
            this.nextDay = nextDay;
            this.nextStage = nextStage;
            this.finalTurn = finalTurn;
            this.prompt = prompt;
        }
    }

    private static class TextGameSession {
        private final UUID sessionId;
        private final TextGameThemeDefinition theme;
        private final TextGameModeDefinition mode;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<TurnRecord> history = new ArrayList<>();
        private final List<TextGameInterludeActionLogEntry> interludeLog = new ArrayList<>();
        private volatile Instant lastAccessedAt;
        private Map<String, Integer> stats;
        private TextGameScene scene;
        private TextGameEnding ending;
        private PendingResolution pendingResolution;
        private String lastResult;
        private int turn;
        private int interludeStep;
        private boolean completed;

        private TextGameSession(
                UUID sessionId,
                TextGameThemeDefinition theme,
                TextGameModeDefinition mode,
                Map<String, Integer> stats,
                TextGameScene scene
        ) {
            this.sessionId = sessionId;
            this.theme = theme;
            this.mode = mode;
            this.stats = stats;
            this.scene = scene;
            this.lastAccessedAt = Instant.now();
        }

        private void touch() {
            this.lastAccessedAt = Instant.now();
        }
    }

    private record TurnRecord(
            int turn,
            int day,
            String stageId,
            String choiceId,
            String choiceLabel,
            String result,
            Map<String, Integer> stats
    ) {
    }
}
