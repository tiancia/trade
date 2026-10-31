package com.trade.automation.infrastructure.config;

import com.trade.automation.application.task.ModuleControl;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import com.trade.story.infrastructure.config.AiStoryProperties;
import com.trade.trading.infrastructure.config.TradingAiProperties;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.x.infrastructure.config.XPublishingProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class ModuleControlConfiguration {
    @Bean
    public ModuleControl tradingControl(TradingProperties properties) {
        return new ModuleControl("trading", "OKX 策略交易", "策略决策、行情订阅、事件扫描和订单对账。",
                properties::isEnabled, properties::setEnabled, () -> {},
                () -> List.of("执行模式：" + properties.getExecutionMode(),
                        properties.isLiveExecutionAllowed() ? "实盘授权已配置；仍受资金状态、风控和执行租约限制。" : "实盘执行未授权。",
                        "关闭会停止自动对账和策略止损检查，不会平仓或撤销已有订单。"));
    }

    @Bean
    public ModuleControl tradingAiControl(TradingAiProperties properties, TradingProperties trading) {
        return new ModuleControl("trading-ai", "Trading AI 策略顾问", "定期评估行情，生成策略建议和复盘记录。",
                properties::isEnabled, properties::setEnabled, () -> {
                    properties.validate();
                    if (properties.getCandleCount() >= trading.getFifteenMinuteCandleLimit()) {
                        throw new IllegalStateException("AI 行情数量必须小于 15 分钟行情缓存上限。");
                    }
                }, () -> List.of(trading.isEnabled() ? "交易模块已启用。" : "交易模块关闭，AI 将跳过评估；请先开启 OKX 策略交易。",
                        properties.isAutoApply() ? "已配置 PAPER 自动应用；仍受持仓、置信度和版本校验限制。" : "自动应用未授权，仅提供建议。"));
    }

    @Bean
    public ModuleControl polymarketControl(AiPolymarketProperties properties) {
        return new ModuleControl("polymarket", "Polymarket AI", "收集候选市场，执行 AI 分析并记录决策。",
                properties::isEnabled, properties::setEnabled, () -> {},
                () -> List.of(properties.getExecution().isEnabled() ? "真实执行已配置；仍受市场、地域和未决订单门禁限制。" : "真实执行未授权，仅分析。"));
    }

    @Bean
    public ModuleControl storyControl(AiStoryProperties properties) {
        return new ModuleControl("story", "AI 故事生成", "按计划生成故事，并保存到配置的输出目录。",
                properties::isEnabled, properties::setEnabled, () -> {},
                () -> List.of("开启后按既有计划调用 AI，可能产生供应商费用。"));
    }

    @Bean
    public ModuleControl xControl(XWorkflowProperties properties, XTelegramReviewProperties review,
            TelegramClientProperties telegram, XPublishingProperties publishing,
            WeiboWorkflowProperties weibo, WeiboTelegramReviewProperties weiboReview) {
        return new ModuleControl("x", "X 内容工作流", "草稿生成、Telegram 审核和已审核内容发布。",
                properties::isEnabled, properties::setEnabled, () -> {
                    properties.policy(true);
                    review.validate(telegram, true);
                    if (review.isEnabled() && weibo.isEnabled() && weiboReview.isEnabled()) {
                        throw new IllegalStateException("X 与微博共用 Telegram Bot，请先关闭微博工作流。");
                    }
                }, () -> List.of("内容生成：" + state(properties.isGenerationEnabled()) + "；发布扫描：" + state(properties.isPublishingEnabled()),
                        "Telegram 审核：" + state(review.isEnabled()),
                        publishing.isLivePublishingEnabled() ? "真实发布已授权；仅发布通过审核的内容。" : "真实发布未授权。"));
    }

    @Bean
    public ModuleControl weiboControl(WeiboWorkflowProperties properties, WeiboTelegramReviewProperties review,
            TelegramClientProperties telegram, WeiboClientProperties publishing,
            XWorkflowProperties x, XTelegramReviewProperties xReview) {
        return new ModuleControl("weibo", "微博内容工作流", "热点采集、草稿生成、Telegram 审核和已审核内容发布。",
                properties::isEnabled, properties::setEnabled, () -> {
                    var policy = properties.policy(true);
                    if (policy.generationEnabled()) policy.requireGeneration();
                    if (policy.publishingEnabled()) policy.requirePublishing();
                    review.validate(telegram, true, properties.getMaxBodyChars());
                    if (review.isEnabled() && x.isEnabled() && xReview.isEnabled()) {
                        throw new IllegalStateException("微博与 X 共用 Telegram Bot，请先关闭 X 工作流。");
                    }
                }, () -> List.of("内容生成：" + state(properties.isGenerationEnabled()) + "；发布扫描：" + state(properties.isPublishingEnabled()),
                        "Telegram 审核：" + state(review.isEnabled()),
                        publishing.isLivePublishingEnabled() ? "真实发布已授权；仅发布通过审核的内容。" : "真实发布未授权。"));
    }

    private static String state(boolean enabled) { return enabled ? "已配置开启" : "配置关闭"; }
}
