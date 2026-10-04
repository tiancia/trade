# Telegram Bot API 接入

## 模块与范围

`client/telegram` 使用 JDK `HttpClient` 和现有 Jackson 2，承载 Telegram 的 HTTP 协议、响应 DTO 和错误处理。
`client/telegram/config/TelegramClientConfiguration` 绑定属性并装配共享 `TelegramApi`；微博和 X 分别在自身业务域实现审核行为，没有顶层 `telegram` 模块。复用同一 Bot 时两套审核不能同时启用，见 [X 工作流](X_WORKFLOW.md)。
属性默认关闭，创建客户端不调用 API，不启动轮询。

已提供以下四类方法，协议依据 [Telegram 官方 Bot API](https://core.telegram.org/bots/api)：

| Java 方法 | 用途 | 返回值 |
| --- | --- | --- |
| `getMe()` | 查询机器人身份 | `TelegramUser` |
| `sendMessage(chatId, text)` | 发送纯文本 | `TelegramMessage` |
| `sendMessage(chatId, text, replyMarkup)` | 发送带内联回调按钮的文本 | `TelegramMessage` |
| `getUpdates(offset, limit, timeoutSeconds, allowedUpdates)` | 获取消息和按钮回调更新 | `List<TelegramUpdate>` |
| `answerCallbackQuery(callbackQueryId, text, showAlert)` | 应答按钮回调 | `boolean` |

微博审核投递、按钮认证和持久化处理见[微博工作流](WEIBO_WORKFLOW.md)。关闭审核时仍使用 `UnavailableHumanReviewGateway`。

## 配置

`TelegramClientProperties` 的前缀为 `trade.telegram`，由 `TelegramClientConfiguration` 装配。
`application.yml` 与 `.env.example` 提供 `TRADE_TELEGRAM_ENABLED/BOT_TOKEN/BASE_URL`、连接/请求超时及代理配置。
其他调用方也可以通过 setter 手动创建客户端：

| Java 属性 | 默认值 |
| --- | --- |
| `enabled` | `false` |
| `botToken` | 空 |
| `baseUrl` | `https://api.telegram.org` |
| `connectTimeoutSeconds` | `10` |
| `requestTimeoutSeconds` | `30` |
| `proxy.enabled` | `false` |
| `proxy.host` | `127.0.0.1` |
| `proxy.port` | `7897` |

以下示例由调用方显式读取 `TRADE_TELEGRAM_BOT_TOKEN`，创建客户端并调用 API；创建实例不会发送消息或启动后台轮询：

```java
TelegramClientProperties properties = new TelegramClientProperties();
properties.setEnabled(true);
properties.setBotToken(System.getenv("TRADE_TELEGRAM_BOT_TOKEN"));
TelegramApi telegramApi = new TelegramApi(new TelegramHttpClient(properties));

TelegramUser bot = telegramApi.getMe();
TelegramMessage sent = telegramApi.sendMessage("-1001234567890", "测试消息");
TelegramInlineKeyboardMarkup buttons = new TelegramInlineKeyboardMarkup(List.of(List.of(
        new TelegramInlineKeyboardButton("通过", "approve:42:v1"),
        new TelegramInlineKeyboardButton("驳回", "reject:42:v1"))));
TelegramMessage withButtons = telegramApi.sendMessage("-1001234567890", "待处理消息", buttons);
List<TelegramUpdate> updates = telegramApi.getUpdates(
        null, 100, 0, List.of("message", "callback_query"));
boolean answered = telegramApi.answerCallbackQuery("callback-id", null, false);
```

示例 chat ID 和 callback ID 需替换为实际值。`chatId` 支持负数 ID 或可访问频道的 `@username`；消息正文保持原样，按 Unicode 码点限制为 1–4096 个字符。

`TelegramInlineKeyboardButton` 的按钮文字不能为空，`callbackData` 长度为 1–64 个 UTF-8 字节；`TelegramInlineKeyboardMarkup` 的每行至少包含一个按钮。二参数 `sendMessage` 保持原行为，三参数传入 `null` 时也不发送 `reply_markup`。

## 更新与失败处理

`getUpdates` 的 `limit` 为 1–100，`timeoutSeconds` 为非负秒数；HTTP 请求超时取配置值与 `timeoutSeconds + 10` 中较大者，其他三个方法使用配置的请求超时。客户端只执行一次请求，offset 的持久化和更新处理由调用用例负责。处理成功后使用最大 `updateId + 1` 获取后续更新；发送更大的 offset 会确认更早更新，因此不要在业务处理成功前推进。已设置 webhook 时不能同时使用 `getUpdates`。详见 [官方 getUpdates 说明](https://core.telegram.org/bots/api#getupdates)。

当前 DTO 只映射 `message` 与 `callback_query` 的基础字段，建议像上例一样显式订阅这两类更新。其他更新类型只保留 `updateId`，不会保留其内容；`allowedUpdates=null` 或空列表可能接收到这些尚未映射的类型，处理时不能仅凭 update ID 就视为业务已完成。

HTTP 非成功状态、Telegram `ok=false`、缺失或无法解析的结果均作为失败。`TelegramApiException` 提供 `statusCode()`、`errorCode()`、`description()`、`retryAfterSeconds()` 和 `migrateToChatId()`，调用方可据此处理限流或群组迁移。客户端不自动重试；发送超时不能证明消息未送达，直接重试可能重复发送。

Bot Token 位于 Telegram 请求 URL 路径中；客户端异常不暴露原始请求 URL、响应正文或底层异常链，描述中的 Token 会替换为 `***`。业务日志也不要输出 Token 或完整请求对象。回调应答仅确认已收到回调，不构成人工批准，详见 [官方 answerCallbackQuery 说明](https://core.telegram.org/bots/api#answercallbackquery)。

## 验证边界

离线测试使用 fake sender 验证请求、DTO、超时、异常和属性校验，不使用真实 Bot Token，不发送 Telegram 消息。
实际调用前需在运行环境确认 Bot Token、网络/代理和目标聊天权限。协议客户端不拥有业务表，微博审核的迁移见[微博工作流](WEIBO_WORKFLOW.md)。
