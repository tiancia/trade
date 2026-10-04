# X：AI 草稿 → Telegram 审核 → 发布

本模块面向一个 X 账号的原创文学与情绪共鸣短帖。`client/x` 负责 OAuth 1.0a 签名和 API；`x` 域负责内容配置、候选筛选、草稿、审核、配额与发布记录；`automation` 登记任务 `x` 的 generation、review、publishing 三个循环。复用现有 `trade.ai.client` 和 Telegram Bot。

## 内容配置

在 `application.yml` 的 `trade.x.workflow.content` 或部署环境变量中设置：

```ini
TRADE_X_CONTENT_DIRECTION=面向成年读者的原创文学短章，写日常细节、孤独、关系、欲言又止与自我和解；可有克制的成人暧昧，不靠猎奇消费苦难
TRADE_X_CONTENT_LANGUAGE=简体中文
TRADE_X_CONTENT_TONE=有画面、有情绪张力、语言准确克制，亲近而不讨好，结尾留有余味
TRADE_X_CONTENT_INSTRUCTIONS=轮换生活切片、微型叙事、独白与短诗；每条聚焦一个场景和一种情绪；不堆砌辞藻，不写鸡汤或求赞求关注，不默认加标签、表情和链接；虚构不冒充真实经历，成人暧昧限于自愿且非露骨的表达
TRADE_X_CONTENT_MIN_CHARS=40
TRADE_X_CONTENT_MAX_CHARS=120
```

方向在启用生成时必填；语言、语气和额外规则进入 Prompt。长度是正文 NFC 规范化并去除首尾空白后的 Unicode 码点数，含标点、空格、换行，`1 <= min-chars <= max-chars <= 280`。

还会用官方 `twitter-text` Java 库检查普通帖子的 **280 加权字符上限**。中文通常权重为 2、复合 Emoji 按规则计数、识别到的 URL 按 23 计数，因此不能把配置的 280 字理解为可发布 280 个汉字。两种检查都通过才送审；不截断，不自动重试付费 AI 请求。[X 字数规则](https://docs.x.com/fundamentals/counting-characters)

方向、语言、语气、规则和字数范围随每条草稿存为快照。修改部署配置需重启应用，影响以后生成的草稿；已有草稿修订和发布继续按其保存的规则校验。方向引导由 Prompt 和人工审核保证，长度及格式由代码硬校验。当前没有实时新闻采集或联网事实核验。

这是当前代码、YAML 和 `.env.example` 的默认内容方向。部署环境中已有的 `TRADE_X_CONTENT_*` 值会覆盖默认值：若仍配置“开发经验”或“表现苦难”，需在部署环境更新后重启；仅修改仓库不会覆盖这些环境变量。所有生成、审核、发布开关继续默认关闭。

## 创作与筛选流程

```text
账号与每日额度预约
  -> 最近 30 条记录中筛出同账号最多 8 条非空正文
  -> 一次 AI 请求：构思三个不同切入角度 -> 自评修改 -> 按推荐顺序输出候选
  -> 本地逐条检查格式、长度、加权字数和近期重复
  -> 第一条合格正文及创作说明持久化
  -> Telegram 人工审核 -> 现有发布门禁
```

- `application/decision/XDraftPromptBuilder` 集中维护创作要求：具体场景与动作、首句张力、结尾余味；轮换微型叙事、生活切片、独白与短诗，不强制每篇都写悲伤、爱情或暧昧。
- 提示词要求三个候选。解析兼容 1–3 个候选以及旧版 `{body, reviewNote}` 响应；所有正文走同一校验。候选排序是模型的编辑建议，不是系统测得的文学评分。
- 每次只调用一次 `AiTextClient.generateJson`。第一条超长、格式错误或重复时，检查这次响应中的下一条；全部不合格则标记 `GENERATION_FAILED`，保留已占用生成额度，不送审、不发布、不自动重试。三个候选会增加单次输入/输出 token 用量，不能据此认为费用与原来相同。
- 只保存最终入选正文与其 `reviewNote`；未入选候选不入库、不发送给 Telegram、不发布。没有新增表、列、配置键或接口请求体。
- 本地重复判断忽略 Unicode 兼容形态、大小写、空白与标点；只针对本次读取的有限历史窗口，不是语义查重，也不是多实例并发生成的数据库唯一约束。模型同时收到近期正文以避开重复意象和句式；历史文本按不可信数据处理，不允许覆盖创作指令。
- 历史窗口包含同账号已保存的非空正文，不限于已发布状态，目的是避免重复生成审核中的稿件或被拒绝的旧稿。账号过滤发生在传给 AI 之前。

暧昧方向定位为**自愿成年人之间的非露骨表达**：可以写目光、犹豫、关系距离与留白，不描写性行为或性器官，不性化未成年人或年龄不明者，不美化强迫或骚扰。这些内容边界由提示词引导和人工审核落实，当前没有新增自动语义审查模型，不能把字符校验通过视为内容已审核。

文学虚构允许，但不能包装成真实新闻、作者亲历或真实人物引用；第一人称虚构要求自然标明“虚构独白”等标记，并计入字数。`reviewNote` 要说明体裁、核心情绪、吸引力来源、虚构/事实边界及暧昧程度，属于 AI 自述，审核人仍需判断。发布文本始终是审核看到的完整正文。

人工修改正文后，送审消息会明确标注原始 AI 说明不代表当前版本；旧批准继续失效，必须审核新正文。不会为更新审核说明额外调用 AI。

如果想调整风格，只需要修改既有内容配置。例如：

| 方向 | 建议写入 direction / tone / instructions 的重点 |
| --- | --- |
| 文学生活切片 | 普通人的生活动作、物件与细微变化；允许温柔、幽默与希望；结尾不说教 |
| 情绪共鸣 | 成年人的孤独、自尊与告别；避免把困境写成羞辱或制造男女对立 |
| 含蓄暧昧 | 明确为自愿成年人关系；通过日常场景与未说出口的话表达吸引；非露骨，不每条重复同一意象 |

这些是创作取向，不是流量承诺；实际文学质量和受众反馈需要审核真实模型输出后再调整。

## 准备账号与数据库

1. 应用启动会执行 [`db/schema/x/schema.sql`](../src/main/resources/db/schema/x/schema.sql)，自动创建缺失的六张 X 表。若在应用启动前手工建表，可选择 [`migration_add_x_workflow.sql`](../src/main/resources/db/upgrade/x/migration_add_x_workflow.sql)。已有表不会由 `CREATE TABLE IF NOT EXISTS` 补字段；需要升级时先对比实际结构，再按[升级说明](../src/main/resources/db/upgrade/README.md)选择适用补丁。
2. 在 X Developer 平台准备可访问相应端点的 App，设置 **Read and Write** 权限并获取该账号的 API Key、API Secret、Access Token、Access Token Secret。权限修改后按平台要求重新取得用户 token。这里只实现单账号凭据，不提供网页登录授权流程。[OAuth 1.0a 用户凭据](https://docs.x.com/fundamentals/authentication/oauth-1-0a/obtaining-user-access-tokens)
3. 将四个值安全注入 `X_API_KEY`、`X_API_SECRET`、`X_ACCESS_TOKEN`、`X_ACCESS_TOKEN_SECRET`。设置 `TRADE_X_TARGET_USER_ID` 为 X 账号的数字 ID，保持字符串，不是 `@username`。客户端的 `XApi.getMe()` 对应 `GET /2/users/me`；每次发帖前会查询并比对 token 的账号 ID，错账号不会 POST。[客户端使用](X_API.md)
4. 保留现有 `TRADE_TELEGRAM_BOT_TOKEN`，为 X 设置 `TRADE_X_TELEGRAM_CHAT_ID` 和 `TRADE_X_TELEGRAM_REVIEWER_USER_IDS`；后者是允许审核的数字 Telegram 用户 ID，多个用逗号分隔。继续使用现有 AI 提供商和其环境变量，不需要新增 AI 客户端凭据。

## 复用 Telegram Bot，暂停微博审核

若微博任务已运行，先停止：

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/weibo/stop
```

在**所有使用该 Bot 的实例**关闭微博审核，并保持微博任务自动启动关闭。暂时不使用微博时，也关闭其生成和发布开关：

```ini
TRADE_AUTOMATION_WEIBO_AUTO_START=false
TRADE_WEIBO_TELEGRAM_REVIEW_ENABLED=false
TRADE_WEIBO_WORKFLOW_ENABLED=false
TRADE_WEIBO_GENERATION_ENABLED=false
TRADE_WEIBO_PUBLISHING_ENABLED=false
TRADE_WEIBO_LIVE_PUBLISHING_ENABLED=false
```

同一应用同时启用微博和 X 的 Telegram review 会启动失败，防止两个 poller 消费彼此的更新。X 只处理 `x:a:` / `x:r:` 按钮，旧微博按钮不能审批 X 草稿。Bot 不可同时由其他程序轮询或使用 webhook；切换后待处理的微博按钮不会在 X 流程中恢复审批。

## 启用生成和审核

所有代码默认开关均为 false，凭据均为空。完成以上准备后在部署环境设置：

```ini
TRADE_X_WORKFLOW_ENABLED=true
TRADE_X_GENERATION_ENABLED=true
TRADE_TELEGRAM_ENABLED=true
TRADE_X_TELEGRAM_REVIEW_ENABLED=true
TRADE_X_TARGET_USER_ID=替换为X账号数字ID
TRADE_X_TELEGRAM_CHAT_ID=替换为Telegram数字ChatID
TRADE_X_TELEGRAM_REVIEWER_USER_IDS=替换为Telegram数字用户ID

# 初次联调先审核，确认状态与内容；以下两个发布开关保持关闭。
TRADE_X_PUBLISHING_ENABLED=false
TRADE_X_LIVE_PUBLISHING_ENABLED=false
TRADE_AUTOMATION_X_AUTO_START=false
```

Spring Boot 不自动加载 `.env`。设置到 Shell、IDE 或部署环境，重启后显式启动任务：

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/x/start
Invoke-RestMethod http://127.0.0.1:8080/api/automation/tasks/x
# 停止后续循环
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/x/stop
```

automation API 沿用现有运维访问控制，须置于受保护的部署控制面。手工启动不会打开业务开关；注册 Bean 不发起外部请求。

确认联调后，设置 `TRADE_X_CLIENT_ENABLED=true`、`TRADE_X_PUBLISHING_ENABLED=true`、`TRADE_X_LIVE_PUBLISHING_ENABLED=true` 并重启，再启动 `x` 任务。也可按需设置 `TRADE_AUTOMATION_X_AUTO_START=true`。

Bot 消息展示目标账号、草稿 ID、版本、过期时间和完整待发布正文。审核上下文优先展示 AI 创作说明，再提示检查开头、细节、余味、近期重复、虚构边界与成人暧昧边界，最后展示冻结配置；过长说明可能被截断，正文完整保留。只有指定聊天、审核人、Bot 与绑定消息匹配的按钮才有效。点击“通过并发布”后立即尝试发布；开关未开、配额不足或发布间隔未到时保持 APPROVED，后续 publishing 循环继续检查。拒绝不发布；修改正文使旧批准失效，必须审核新版本。

## 频率与额度

| 环境变量 | 默认 | 含义 |
| --- | --- | --- |
| `TRADE_X_GENERATION_FIXED_DELAY_MS` | 900000 | 每次生成循环间隔；按 UTC 时间段与账号保留唯一生成键 |
| `TRADE_X_TELEGRAM_POLLING_FIXED_DELAY_MS` | 5000 | 按钮审核轮询间隔，短轮询 |
| `TRADE_X_PUBLISHING_FIXED_DELAY_MS` | 60000 | 扫描待审核、已批准及中断状态的间隔 |
| `TRADE_X_INITIAL_DELAY_MS` | 30000 | 任务开始后的首次循环延迟 |
| `TRADE_X_DAILY_GENERATION_LIMIT` | 5 | UTC 日生成预约上限，失败生成也占用 |
| `TRADE_X_DAILY_PUBLISH_LIMIT` | 3 | UTC 日发布尝试上限，失败或不确定尝试也占用 |
| `TRADE_X_PUBLISH_INTERVAL_SECONDS` | 1800 | 同账号两次发布尝试的最小间隔 |
| `TRADE_X_REVIEW_TTL_HOURS` | 6 | 草稿审核及发布有效期 |
| `TRADE_X_CLAIM_TIMEOUT_SECONDS` | 300 | 中断生成/发布的恢复超时，至少 60 秒 |

多实例使用同一数据库时，以账号锁、CAS、唯一生成键和发布尝试记录约束重复执行。网络调用不在数据库事务中。

## 管理草稿与失败状态

设置 `TRADE_X_ADMIN_TOKEN` 后才注册管理 API，请求头为 `X-X-Admin-Token`：

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/x/posts` | 按当前配置生成一次，不需要请求体；同样计入生成额度 |
| `GET /api/x/posts?limit=30` | 查看最近草稿与状态 |
| `GET /api/x/posts/{id}` | 查看一条草稿及冻结规则 |
| `GET /api/x/posts/{id}/history` | 查看版本、审核和发布审计 |
| `PUT /api/x/posts/{id}` | `{ "expectedRevision": 1, "body": "新正文" }`，修改后重新送审 |

没有绕过审核的 HTTP 发布或批准接口。例：

```powershell
$xHeaders = @{ "X-X-Admin-Token" = $env:TRADE_X_ADMIN_TOKEN }
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/x/posts -Headers $xHeaders
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/x/posts -Headers $xHeaders
```

发布前完成审核和持久化 claim，再调用 `POST /2/tweets`，仅发送审核过的正文。收到明确 4xx 拒绝（除 408）记为 FAILED；超时、5xx、解析失败或中断发送记为 UNKNOWN。两种状态都不自动重发；必须先在 X 核对实际结果。审核投递的不确定发送也不自动重复，同版本无绑定记录的按钮不能审批。

离线测试验证本地状态、协议签名、配置、数据库契约和模拟完整流程；实际 X API 权限、余额/额度、网络代理、Telegram 权限、AI 质量和 MySQL 部署仍需环境联调。当前不包含图片、线程和 Premium 长帖。
