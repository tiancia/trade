package com.trade.x.infrastructure.review;

import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.dto.TelegramCallbackQuery;
import com.trade.client.telegram.dto.TelegramInlineKeyboardButton;
import com.trade.client.telegram.dto.TelegramInlineKeyboardMarkup;
import com.trade.client.telegram.dto.TelegramMessage;
import com.trade.client.telegram.dto.TelegramUpdate;
import com.trade.client.telegram.dto.TelegramUser;
import com.trade.x.application.port.XHumanReviewGateway;
import com.trade.x.application.port.XReviewDeliveryStore;
import com.trade.x.domain.model.XReviewDecision;
import com.trade.x.domain.model.XReviewRequest;
import com.trade.x.domain.model.XReviewDelivery;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provider adapter. Transactions commit before any Bot API call; messages never approve themselves. */
public class TelegramXReviewGateway implements XHumanReviewGateway {
    private static final Logger log = LoggerFactory.getLogger(TelegramXReviewGateway.class);
    private static final Pattern ACTION = Pattern.compile("x:([ar]):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private final TelegramApi api;
    private final TelegramClientProperties client;
    private final XTelegramReviewProperties settings;
    private final XWorkflowProperties workflow;
    private final XReviewDeliveryStore deliveries;
    private final Clock clock;
    private volatile Long botId;

    public TelegramXReviewGateway(TelegramApi api, TelegramClientProperties client,
            XTelegramReviewProperties settings, XWorkflowProperties workflow,
            XReviewDeliveryStore deliveries) {
        this(api, client, settings, workflow, deliveries, Clock.systemUTC());
    }

    public TelegramXReviewGateway(TelegramApi api, TelegramClientProperties client,
            XTelegramReviewProperties settings, XWorkflowProperties workflow,
            XReviewDeliveryStore deliveries, Clock clock) {
        this.api = api;
        this.client = client;
        this.settings = settings;
        this.workflow = workflow;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @Override
    public boolean submit(XReviewRequest request) {
        if (!ready() || !workflow.getTargetUserId().equals(request.targetUserId()) || !request.expiresAt().isAfter(now())) return false;
        // Validate before taking the durable send reservation. Never truncate the approved body.
        String text = reviewText(request);
        long identity = identity();
        var existing = deliveries.findForPost(identity, request.reference(), request.version());
        if (existing.isPresent()) return "SENT".equals(existing.get().status());
        String id = UUID.randomUUID().toString();
        long chatId = settings.requiredChatId();
        XReviewDelivery delivery = new XReviewDelivery(id, identity, request.reference(), request.version(),
                chatId, null, "SENDING", request.expiresAt(), null, null, null, null, "PENDING");
        if (!deliveries.reserve(delivery)) return false;
        try {
            TelegramInlineKeyboardMarkup buttons = new TelegramInlineKeyboardMarkup(List.of(List.of(
                    new TelegramInlineKeyboardButton("通过并发布", "x:a:" + id),
                    new TelegramInlineKeyboardButton("拒绝", "x:r:" + id))));
            TelegramMessage message = api.sendMessage(Long.toString(chatId), text, buttons);
            if (message.chat() == null || !Objects.equals(message.chat().id(), chatId)
                    || message.messageId() == null || message.messageId() <= 0
                    || !deliveries.markSent(identity, id, message.messageId())) {
                throw new IllegalStateException("Telegram review message could not be bound");
            }
            return true;
        } catch (RuntimeException failure) {
            // A timeout or a completion DB failure may have sent the message: never resend this version.
            try { deliveries.markUnknown(identity, id); }
            catch (RuntimeException storageFailure) { log.warn("Telegram review send completion unavailable: deliveryId={}", id); }
            log.warn("Telegram review delivery uncertain: deliveryId={}", id);
            return false;
        }
    }

    @Override
    public void poll(Function<XReviewDecision, Boolean> consume) {
        if (!ready()) return;
        long identity = identity();
        // Each invocation has its own lease owner, including concurrent invocations in one JVM.
        String owner = UUID.randomUUID().toString();
        Instant started = now();
        OptionalLong cursor = deliveries.acquirePolling(identity, owner, started, leaseUntil(started));
        if (cursor.isEmpty()) return;
        try {
            long offset = cursor.getAsLong();
            List<TelegramUpdate> updates = api.getUpdates(offset, 100, 0, List.of("callback_query"));
            for (TelegramUpdate update : updates.stream().sorted(Comparator.comparing(TelegramUpdate::updateId)).toList()) {
                if (update.updateId() < offset) continue;
                if (update.updateId() == Long.MAX_VALUE) throw new IllegalStateException("Invalid Telegram update id");
                Instant current = now();
                if (!deliveries.renewPolling(identity, owner, current, leaseUntil(current))) return;
                String response = process(identity, update.callbackQuery(), consume);
                current = now();
                offset = update.updateId() + 1;
                if (!deliveries.advancePolling(identity, owner, offset, current, leaseUntil(current))) return;
                // Callback UX is best effort after committing the decision and cursor.
                answer(update.callbackQuery(), response);
            }
        } finally {
            deliveries.releasePolling(identity, owner);
        }
    }

    private String process(long identity, TelegramCallbackQuery callback, Function<XReviewDecision, Boolean> consume) {
        if (callback == null) return null;
        if (callback.id() == null || callback.id().isBlank() || callback.id().length() > 128
                || callback.from() == null || callback.from().isBot()
                || callback.from().id() == null || !settings.getReviewerUserIds().contains(callback.from().id())
                || callback.message() == null || callback.message().chat() == null
                || !Objects.equals(callback.message().chat().id(), settings.requiredChatId())) {
            return "此操作不属于已授权的审核人或聊天";
        }
        Matcher action = ACTION.matcher(callback.data() == null ? "" : callback.data());
        if (!action.matches()) return "无法识别此审核按钮";
        String id = action.group(2);
        XReviewDelivery delivery = deliveries.find(identity, id).orElse(null);
        if (delivery == null || delivery.botId() != identity || !"SENT".equals(delivery.status())
                || delivery.chatId() != settings.requiredChatId()
                || delivery.messageId() == null || delivery.messageId() <= 0
                || !Objects.equals(delivery.messageId(), callback.message().messageId())) {
            return "消息未绑定到审核记录，请查看最新草稿";
        }
        if (delivery.callbackId() == null) {
            if (!delivery.expiresAt().isAfter(now())) return "草稿已过期，不能审核";
            deliveries.recordDecision(identity, id, callback.id(), "a".equals(action.group(1)),
                    "telegram:" + callback.from().id(), now());
            delivery = deliveries.find(identity, id).orElseThrow();
        }
        if ("APPLIED".equals(delivery.decisionStatus())) return "该版本已审核，请查看X草稿状态";
        if ("INVALID".equals(delivery.decisionStatus())) return "审核已失效，请查看最新草稿";
        if (!callback.id().equals(delivery.callbackId())) return "已有审核正在处理，请稍后查看状态";
        XReviewDecision decision = new XReviewDecision(delivery.postId(), delivery.version(),
                delivery.approved(), delivery.reviewer(), "Telegram button review", delivery.decidedAt());
        boolean applied = consume.apply(decision);
        deliveries.finishDecision(identity, id, applied);
        return applied ? (decision.approved() ? "已通过，将按发布开关和配额发布" : "已拒绝，不会发布")
                : "正文已修改、过期或已处理，请查看最新草稿";
    }

    private void answer(TelegramCallbackQuery callback, String text) {
        if (callback == null || callback.id() == null || callback.id().isBlank() || text == null) return;
        try { api.answerCallbackQuery(callback.id(), text, false); }
        catch (RuntimeException ignored) { log.debug("Telegram review callback answer unavailable"); }
    }

    private boolean ready() { return settings.isEnabled() && workflow.isEnabled() && client.isEnabled(); }

    private synchronized long identity() {
        if (botId == null) {
            TelegramUser bot = api.getMe();
            if (!bot.isBot() || bot.id() == null || bot.id() <= 0) {
                throw new IllegalStateException("Telegram review requires a bot identity");
            }
            botId = bot.id();
        }
        return botId;
    }

    private Instant now() { return Instant.now(clock).truncatedTo(ChronoUnit.MICROS); }
    private Instant leaseUntil(Instant time) { return time.plusSeconds(Math.max(60L, (long) client.getRequestTimeoutSeconds() + 10)); }

    private String reviewText(XReviewRequest request) {
        String header = "X草稿待审核\n目标账号：" + request.targetUserId() + "\n草稿：" + request.reference()
                + "\n正文版本：" + request.version() + "\n过期时间：" + request.expiresAt() + "\n\n【待发布正文】\n";
        String footer = "\n【正文结束】\n\n通过后只会发布以上正文。\n【来源与审核说明】\n";
        String essential = header + request.content() + footer;
        int budget = 4096 - essential.codePointCount(0, essential.length());
        if (budget < 40) throw new IllegalArgumentException("Full X body does not fit a Telegram review message");
        String context = request.context() == null ? "" : request.context();
        String suffix = "\n（来源说明过长，已截断；正文完整保留）";
        int contextLimit = Math.min(budget, 1000);
        if (context.codePointCount(0, context.length()) > contextLimit) {
            int retained = contextLimit - suffix.codePointCount(0, suffix.length());
            context = context.substring(0, context.offsetByCodePoints(0, retained)) + suffix;
        }
        return essential + context;
    }
}

