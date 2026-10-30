# X：AI 草稿 → Telegram 审核 → 发布

本模块默认面向一个 X 账号的中文成年读者，创作聊天记录样式的原创虚构短对话：每条2～6句，一句一行，用成对双引号包住；情绪来自几句来回、回应里的变化与潜台词，不附旁白、角色名、解释或结尾总结。可写暧昧、想念、试探、靠近与退让，不写成话术教材或散文金句。`client/x` 负责 OAuth 1.0a 签名和 API；`x` 域负责内容配置、候选筛选、草稿、审核、配额与发布记录；`automation` 登记任务 `x` 的 generation、review、publishing 三个循环。复用现有 `trade.ai.client` 和 Telegram Bot，不新增 AI 客户端。

## 内容配置

`/api/automation/tasks` 的 X 循环会报告生成失败、Telegram 投递失败、发布 `FAILED/UNKNOWN`、发布中断恢复和已启用发布时的凭据不可用。单条失败保持其持久化状态，不重发未知发布；整轮 `lastRunSuccessful=false`，后续循环继续调度。显式禁用功能、无候选和额度/幂等预留未获准属于正常跳过。Micrometer 提供 `trade.automation.runs`、`trade.automation.duration` 和 `trade.automation.last.success`，以 `task=x` 和循环名区分；错误消息、账号和草稿 ID 不进入指标标签。

在 `application.yml` 的 `trade.x.workflow.content` 或部署环境变量中设置：

```ini
TRADE_X_CONTENT_DIRECTION=面向中文成年读者，创作聊天记录样式的原创虚构短对话，写成年人的暧昧、想念、试探、靠近与退让；让情绪从几句来回和潜台词里自然出现
TRADE_X_CONTENT_LANGUAGE=简体中文
TRADE_X_CONTENT_TONE=自然简短的中文聊天口吻，有回应、有停顿、有潜台词，可温柔、嘴硬、幽默或失落；像两个人在说话，不像作者在写金句
TRADE_X_CONTENT_INSTRUCTIONS=每条正文仅2至6句对话，一句一行，每行用成对双引号包住；不加空行、角色名、标题、旁白、解释或总结；三个候选更换关系处境、情绪与潜台词，不轮换成散文；话要接得上，不句句金句，不提供沟通话术；reviewNote用简体中文说明原创虚构、潜台词、情绪变化与核验项，绝不混入正文；不冒充真实聊天记录、截图、亲历或投稿；仅自愿成年人且非露骨，不承诺挽回、涨粉或收益
TRADE_X_CONTENT_FORMAT=CHAT_DIALOGUE
TRADE_X_CONTENT_MIN_CHARS=8
TRADE_X_CONTENT_MAX_CHARS=120
```

方向在启用生成时必填；语言、语气和额外规则进入 Prompt。长度是正文 NFC 规范化并去除首尾空白后的 Unicode 码点数，含双引号、标点、空格和换行，`1 <= min-chars <= max-chars <= 280`。默认下限降低为8，允许两三句短话，不为了凑字数添加旁白。

`format=CHAT_DIALOGUE` 硬校验2～6行，每行用完整的 ASCII 双引号或中文弯双引号包住，引号内不能全为空白，不接受空行、引号外行首行尾空白或行内制表符，也不接受一行多组同种双引号。整体首尾空白仍按现有规则去除；不会自动补引号、拆句或改写正文。提示词引导同一条对话统一引号风格，代码允许每行各自完整匹配。`format=FREE_TEXT` 保留原自由正文校验。是否真的一句一行、是否含角色名、旁白或伪装的说明，仍由提示词和人工判断，不能从引号校验推断语义正确。

默认正文样式（虚构示意）：

```text
“你到家了吗？”
“到了。”
“那怎么还显示正在输入？”
“有句话，写了又删。”
```

还会用官方 `twitter-text` Java 库检查普通帖子的 **280 加权字符上限**。中文通常权重为 2、复合 Emoji 按规则计数、识别到的 URL 按 23 计数，因此不能把配置的 280 字理解为可发布 280 个汉字。两种检查都通过才送审；不截断，不自动重试付费 AI 请求。[X 字数规则](https://docs.x.com/fundamentals/counting-characters)

方向、语言、语气、格式、规则和字数范围随每条草稿存为快照。修改部署配置需重启应用，影响以后生成的草稿；已有草稿修订和发布继续按其保存的规则校验。旧 JSON 没有 `format` 时按 `FREE_TEXT` 读取，旧散文、英文正文及其字数不会被强制改成聊天对话。新字段使用现有 `content_policy_json` 保存，不新增表或列，不需要数据库迁移。当前没有实时新闻采集、聊天记录采集或联网事实核验。

这是当前代码、YAML 和 `.env.example` 的默认内容方向。部署环境中已有的 `TRADE_X_CONTENT_*` 值会覆盖默认值：切换聊天样式时需同步更新方向、语气、额外规则、`FORMAT=CHAT_DIALOGUE` 和字数范围后重启；若从英文切换，还需同步语言。旧散文或前缀标识要求会与聊天格式冲突，不能只改语言或格式。仅修改仓库不会覆盖环境变量；已有草稿不会被自动改写。

X 只需工作流主开关及必要凭据：调度、生成和发布扫描默认跟随工作流，客户端按 OAuth 凭据接入，Telegram 按 Bot Token 和审核聊天接入。YAML 的真实发布授权默认 false，env 示例为 true；Java 工作流默认关闭。细分开关仅作可选覆盖，旧配置迁移见[功能启用](OPERATIONS.md#功能启用)。生成间隔统一为完成上一轮后等待 3 小时，每个 UTC 日最多预约 8 次生成和 8 次发布尝试。部署环境应显式配置，不能仅凭属性类默认值判断实际运行状态。缺少目标账号，或已配置审核聊天却缺少 Token / 审核人时，启动校验会拒绝；未配置审核聊天时只保留待审核草稿；缺少 X 凭据时发布等待。共享 Telegram 客户端使用独立配置；X 审核关闭时不会通过它轮询或送审。实际 `.env`、凭据和生产数据不进入仓库，配置开启不能代替外部联调。

## 创作与筛选流程

```text
账号与每日额度预约
  -> 最近 30 条记录中筛出同账号最多 8 条非空正文
  -> 一次 AI 请求：构思三个不同情绪与潜台词的对话 -> 自评修改 -> 按推荐顺序输出候选
  -> 本地逐条检查格式、长度、加权字数和近期重复
  -> 第一条合格正文及创作说明持久化
  -> Telegram 人工审核 -> 现有发布门禁
```

- `application/decision/XDraftPromptBuilder` 按冻结格式选择要求：默认 `CHAT_DIALOGUE` 的三个候选都是2～6句短对话，通过关系处境、情绪和潜台词区分，不再轮换散文或观点。话要自然接得上，不句句金句，不附标题、角色名、旁白、解释、虚构前缀或总结；`FREE_TEXT` 按自由正文规则创作，保留英文配置兼容。
- Prompt 通过近期正文避开重复开头、对白、冲突与结尾，避免总写同一种暧昧或失落。这是创作建议，不是持久化题材统计或语义查重；当前没有周排期或每帖发布时间。
- 正文是一个完整单帖，原样持久化、送审与发布；没有 Markdown 标题/加粗、线程编号或伪装原生投票。格式校验在生成候选、保存、修订和发布前复用，格式不合格不会靠截断或补写修复。对话自然度、情绪与内容边界由 Prompt 引导、人工审核，字符与引号校验不会判断语义。
- 提示词要求三个候选。解析兼容 1–3 个候选以及旧版 `{body, reviewNote}` 响应；所有正文走同一校验。候选排序是模型的编辑建议，不是系统测得的文学评分。
- 每次只调用一次 `AiTextClient.generateJson`。第一条超长、格式错误或重复时，检查这次响应中的下一条；全部不合格则标记 `GENERATION_FAILED`，保留已占用生成额度，不送审、不发布、不自动重试。三个候选会增加单次输入/输出 token 用量，不能据此认为费用与原来相同。
- 只保存最终入选正文与其 `reviewNote`；未入选候选不入库、不发送给 Telegram、不发布。新增 `TRADE_X_CONTENT_FORMAT` 配置键和 JSON 快照中的格式字段，没有新增数据库表、列或接口请求体。
- 本地重复判断忽略 Unicode 兼容形态、大小写、空白与标点；只针对本次读取的有限历史窗口，不是语义查重，也不是多实例并发生成的数据库唯一约束。模型同时收到近期正文以避开重复意象和句式；历史文本按不可信数据处理，不允许覆盖创作指令。
- 历史窗口包含同账号已保存的非空正文，不限于已发布状态，目的是避免重复生成审核中的稿件或被拒绝的旧稿。账号过滤发生在传给 AI 之前。

性主题定位为**自愿成年人之间的非露骨观察、反思与沟通**：可以写被需要与被理解、欲望差异、期待与边界、约会里的犹豫，不以性唤起为目的，不描写性行为或性器官，不性化未成年人或年龄不明者，不美化强迫或骚扰，不制造性别对立。这些内容边界由提示词引导和人工审核落实，当前没有新增自动语义审查模型，不能把字符校验通过视为内容已审核。X 对成人内容的判断包含表达目的，不是只看有无裸露；具体标记、展示与限制以 [X 成人内容政策](https://help.x.com/en/rules-and-policies/adult-content) 为准。

默认对话是原创虚构，不能包装成真实聊天截图、作者亲历、读者投稿、新闻或真实人物引用。为了保持纯对话样式，正文不加“虚构对话：”前缀；虚构性质在 `reviewNote` 中向审核人说明，该说明不会发布，不能把它当成已向读者披露。运营时可在账号简介说明“原创虚构对话”，本模块不修改账号简介。`FREE_TEXT` 仍按其提示词标明虚构叙事。不冒充咨询师、治疗师或性健康专家，不提供个体诊断、治疗或性健康建议，不承诺挽回、操纵他人、涨粉或收益。`reviewNote` 用简体中文说明虚构、关系处境、潜台词、情绪变化及成年人边界，属于 AI 自述，审核人仍需判断；发布文本始终是审核看到的完整正文。

人工修改正文后，送审消息会明确标注原始 AI 说明不代表当前版本；旧批准继续失效，必须审核新正文。不会为更新审核说明额外调用 AI。

可尝试的对话情绪，不是固定栏目：

| 情绪 | 对话中的变化 |
| --- | --- |
| 想念与嘴硬 | 问对方有没有睡，真正想说的话却删了又写 |
| 靠近与试探 | 一句普通邀约，另一方多问了一句，让关系显出变化 |
| 失落与幽默 | 一方等着认真回答，另一方轻轻岔开；不靠羞辱制造情绪 |

这些是创作取向，不是流量承诺；实际内容质量和受众反馈需要审核真实模型输出后再调整。

可先人工记录 20–30 条稿件的情绪、对白自然度、曝光、持续讨论、关注和读者反馈，再调整创作取向；系统目前不采集互动指标，也不保证流量或收入。图片、原生投票与 thread 需要结构化内容、完整预览审核及部分成功处理，当前未实现。也要持续遵守 [X 自动化规则](https://help.x.com/en/rules-and-policies/x-automation)，避免重复或高度相似内容。

### 运营工具与创作奖励

按 2026-10-12 核实的 [X 原创内容奖励规则](https://help.x.com/en/using-x/original-content-rewards)，使用自动化方式创建或发布的内容被列为不符合奖励资格；人工审核没有被列为该规则的豁免。因此本模块是 AI 草稿、人工审阅与受控发布工具，不能视为领取奖励的合规方案。“非露骨”也不等于必然能获得收益，过度性暗示等内容仍可能受到[创作者变现标准](https://help.x.com/en/rules-and-policies/content-monetization-standards)限制。是否有受众、商业需求或平台奖励资格，都要另行验证。

## 准备账号与数据库

1. 应用启动通过 Flyway 完成版本迁移，六张 X 表已纳入 V1。完整结构参考为 [`db/schema/x/schema.sql`](../../backend/src/main/resources/db/schema/x/schema.sql)，正常发布无需手工建表；只有首次接入前的历史结构不兼容时，才按[数据库说明](../../backend/src/main/resources/db/README.md)核实适用的历史前置补丁。
2. 在 X Developer 平台准备可访问相应端点的 App，设置 **Read and Write** 权限并获取该账号的 API Key、API Secret、Access Token、Access Token Secret。权限修改后按平台要求重新取得用户 token。这里只实现单账号凭据，不提供网页登录授权流程。[OAuth 1.0a 用户凭据](https://docs.x.com/fundamentals/authentication/oauth-1-0a/obtaining-user-access-tokens)
3. 将四个值安全注入 `X_API_KEY`、`X_API_SECRET`、`X_ACCESS_TOKEN`、`X_ACCESS_TOKEN_SECRET`。设置 `TRADE_X_TARGET_USER_ID` 为 X 账号的数字 ID，保持字符串，不是 `@username`。客户端的 `XApi.getMe()` 对应 `GET /2/users/me`；每次发帖前会查询并比对 token 的账号 ID，错账号不会 POST。[客户端使用](X_API.md)
4. 保留现有 `TRADE_TELEGRAM_BOT_TOKEN`，为 X 设置 `TRADE_X_TELEGRAM_CHAT_ID` 和 `TRADE_X_TELEGRAM_REVIEWER_USER_IDS`；后者是允许审核的数字 Telegram 用户 ID，多个用逗号分隔。继续使用现有 AI 提供商和其环境变量，不需要新增 AI 客户端凭据。

## 复用 Telegram Bot，暂停微博审核

若微博任务已运行，先停止：

```powershell
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} http://127.0.0.1:8080/api/automation/tasks/weibo/stop
```

暂时不使用微博时，在**所有使用该 Bot 的实例**关闭微博模块即可；旧的 auto-start 显式覆盖按[功能启用](OPERATIONS.md#功能启用)移除：

```ini
TRADE_WEIBO_WORKFLOW_ENABLED=false
TRADE_WEIBO_LIVE_PUBLISHING_ENABLED=false
```

同一应用同时启用微博和 X 的 Telegram review 会启动失败，防止两个 poller 消费彼此的更新。X 只处理 `x:a:` / `x:r:` 按钮，旧微博按钮不能审批 X 草稿。Bot 不可同时由其他程序轮询或使用 webhook；切换后待处理的微博按钮不会在 X 流程中恢复审批。

## 当前运行配置

以下示例运行自动生成、Telegram 人工审核及审核后真实发布流程；只需工作流和真实发布两个开关。YAML 的真实发布授权默认 false，以下示例显式开启。数字账号、聊天、审核人标识及凭据需通过部署环境提供：

```ini
TRADE_X_WORKFLOW_ENABLED=true
TRADE_X_LIVE_PUBLISHING_ENABLED=true
TRADE_X_TARGET_USER_ID=替换为X账号数字ID
TRADE_X_TELEGRAM_CHAT_ID=替换为Telegram数字ChatID
TRADE_X_TELEGRAM_REVIEWER_USER_IDS=替换为Telegram数字用户ID

TRADE_X_GENERATION_FIXED_DELAY_MS=10800000
TRADE_X_DAILY_GENERATION_LIMIT=8
TRADE_X_DAILY_PUBLISH_LIMIT=8

TRADE_X_PROXY_ENABLED=true
TRADE_X_PROXY_HOST=127.0.0.1
TRADE_X_PROXY_PORT=13128
TRADE_TELEGRAM_PROXY_ENABLED=true
TRADE_TELEGRAM_PROXY_HOST=127.0.0.1
TRADE_TELEGRAM_PROXY_PORT=13128
```

国内主机通过本机 `127.0.0.1:13128` 的 HTTP 代理入口访问 X 和 Telegram；该端口须由通往德国 Squid 的 SSH 隧道提供。OKX 也可共用这个入口。若 Java 部署在容器中，容器内的 `127.0.0.1` 指向容器自身，须根据实际网络方式设置代理主机地址。应用本身不会创建或维持隧道。

Spring Boot 不自动加载 `.env`。将部署值注入 Shell、IDE、systemd `EnvironmentFile` 或容器环境后重启；已有进程环境变量会覆盖 YAML 回退值，修改示例或 YAML 不会改变运行中的进程。任务随应用就绪自动启动，三个循环首次执行前等待 30 秒。已有同账号、已批准且未过期的旧草稿也可能在启动后的发布扫描中发布；待审核草稿仍须点击有效审核按钮。

### 可选：分阶段联调覆盖值

如需先验收生成和审核而暂不发帖，只需关闭真实发布：

```ini
TRADE_X_LIVE_PUBLISHING_ENABLED=false
```

工作流开启后默认自动运行。仅需手动启动时，额外设置 `TRADE_AUTOMATION_X_AUTO_START=false`；任务 API 可随时查询或启停：

```powershell
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} http://127.0.0.1:8080/api/automation/tasks/x/start
Invoke-RestMethod http://127.0.0.1:8080/api/automation/tasks/x
# 停止后续循环
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} http://127.0.0.1:8080/api/automation/tasks/x/stop
```

automation API 沿用现有运维访问控制，须置于受保护的部署控制面。手工启动不会打开业务开关；注册 Bean 不发起外部请求。

X 内容工作台也可读取并启停当前后端实例的 X 自动工作流，覆盖生成、审核和发布三个循环。停止不取消已经开始的工作，也不禁用独立的手动生成 API；恢复后仍在有效期内且符合门禁的已批准草稿可能继续发布。该状态只在当前进程有效，重启后默认跟随工作流主开关；`TRADE_AUTOMATION_X_AUTO_START` 可显式覆盖；多实例需要分别控制。操作结果未知时应刷新状态确认。

完成分阶段验收后，设置 `TRADE_X_LIVE_PUBLISHING_ENABLED=true`，并移除不再需要的手动启动覆盖，重启生效。以上操作说明不代表已完成真实 AI、Telegram 或 X API 联调。

Bot 消息展示目标账号、草稿 ID、版本、过期时间和完整待发布正文。审核上下文优先展示中文 AI 创作说明，并显示冻结格式：`CHAT_DIALOGUE` 检查2～6句成对引号、自然来回、潜台词与情绪，识别旁白、角色名、解释和总结，确认没有冒充真实聊天；`FREE_TEXT` 只按旧稿冻结的风格和规则判断，不强制聊天格式。共同核对冻结语言与方向、成年人边界，以及是否冒充专家或承诺操纵、挽回、涨粉与收益。审核说明不会发布，过长说明可能被截断，正文完整保留。只有指定聊天、审核人、Bot 与绑定消息匹配的按钮才有效。点击“通过并发布”后立即尝试发布；开关未开、本地配额不足或发布间隔未到时保持 APPROVED，后续 publishing 循环继续检查。拒绝不发布；修改正文使旧批准失效，必须审核新版本。按钮反馈表示审核受理，发布结果需查看草稿状态。

## 频率与额度

| 环境变量 | 默认 | 含义 |
| --- | --- | --- |
| `TRADE_X_GENERATION_FIXED_DELAY_MS` | 10800000 | 每次生成结束后等待 3 小时；按 UTC 时间段与账号保留唯一生成键，不是按钟点或每周排期 |
| `TRADE_X_TELEGRAM_POLLING_FIXED_DELAY_MS` | 5000 | 按钮审核轮询间隔，短轮询 |
| `TRADE_X_PUBLISHING_FIXED_DELAY_MS` | 60000 | 扫描待审核、已批准及中断状态的间隔 |
| `TRADE_X_INITIAL_DELAY_MS` | 30000 | 任务开始后的首次循环延迟 |
| `TRADE_X_DAILY_GENERATION_LIMIT` | 8 | UTC 日生成预约上限，失败生成也占用 |
| `TRADE_X_DAILY_PUBLISH_LIMIT` | 8 | UTC 日发布尝试上限，失败或不确定尝试也占用 |
| `TRADE_X_PUBLISH_INTERVAL_SECONDS` | 1800 | 同账号两次发布尝试的最小间隔 |
| `TRADE_X_REVIEW_TTL_HOURS` | 24 | 自预约创建起的草稿审核及发布有效期，改稿/批准不续期 |
| `TRADE_X_CLAIM_TIMEOUT_SECONDS` | 300 | 中断生成/发布的恢复超时，至少 60 秒 |

多实例使用同一数据库时，以账号锁、CAS、唯一生成键和发布尝试记录约束重复执行。网络调用不在数据库事务中。

## 管理草稿与失败状态

管理 API 随应用注册，无需单独的应用令牌。生产访问由网关账号密码和容器网络隔离保护，后端端口不直接对公网开放：

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/x/posts` | 按当前配置生成一次，不需要请求体；同样计入生成额度 |
| `GET /api/x/posts?limit=30` | 查看最近草稿与状态 |
| `GET /api/x/posts/{id}` | 查看一条草稿及冻结规则 |
| `GET /api/x/posts/{id}/history` | 查看版本、审核和发布审计 |
| `PUT /api/x/posts/{id}` | `{ "expectedRevision": 1, "body": "新正文" }`，修改后重新送审 |

没有绕过审核的 HTTP 发布或批准接口。例：

```powershell
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} -Uri http://127.0.0.1:8080/api/x/posts
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/x/posts
```

发布前完成审核和持久化 claim，再调用 `POST /2/tweets`，仅发送审核过的正文。收到明确 4xx 拒绝（除 408）记为 FAILED；超时、5xx、解析失败或中断发送记为 UNKNOWN。两种状态都不自动重发；必须先在 X 核对实际结果。审核投递的不确定发送也不自动重复，同版本无绑定记录的按钮不能审批。

### 审核通过却没有发布

先通过管理 API 查询 `status`、`lastError`、`postId` 和历史，不要仅根据 Telegram 的通过提示或 automation 循环成功判断结果：

| 结果 | 含义和排查 |
| --- | --- |
| `APPROVED` + `Waiting to publish: ...` | 发布扫描遇到 workflow publishing、client、live、凭据或账号门禁；也可能等待本地每天 8 次尝试或最小 30 分钟间隔。此时没有 POST；同一原因不反复写历史 |
| `APPROVED` 且没有等待记录 | 可能尚未运行 publishing 扫描；检查 x 任务是否运行、当前版本是否已部署和扫描是否异常。仅查询 API 不会触发扫描 |
| `FAILED` + `preflight HTTP ...` | `GET /2/users/me` 失败，创建帖子请求尚未发送；HTTP 状态保留，查询失败也会占本地尝试额度 |
| `FAILED` + `X request failed: HTTP ...` | POST 收到明确拒绝；检查开发者控制台余额、spending limit、OAuth 权限及平台错误，不自动重发 |
| `UNKNOWN` | 发布结果不确定；必须先在 X 核对是否已经成功，不能直接再次发送 |
| `EXPIRED` | 超过创建起的有效期，审核或等待不续期 |
| `PUBLISHED` + `postId` | API 返回有效帖子 ID；仍可在 X 核对实际展示 |

关闭 workflow 总开关时扫描直接停止，不会新增等待记录。已批准且同一原因等待的草稿仍保留批准与 TTL；门禁满足后后续扫描可继续发布。`FAILED` / `UNKNOWN` 不属于自动重试队列。日志只显示草稿 ID、状态和受控原因，不打印正文、密钥或供应商响应。

按 2026-10-06 核实的 [X API 官方计费说明](https://docs.x.com/x-api/getting-started/pricing)，余额不足或达到 spending limit 会阻断请求；本模块还会先调用账号查询端点。因此额度确实可能导致失败，但源码门禁关闭时根本没有 X 请求，充值不会改变该门禁。HTTP 402 等状态是线索，不能单凭状态码确定计费原因，也不能把 AI 服务额度当作 X API 余额。

离线测试验证本地状态、协议签名、配置、数据库契约和模拟完整流程；实际 X API 权限、余额/额度、网络代理、Telegram 权限、AI 质量和 MySQL 部署仍需环境联调。当前不包含图片、线程和 Premium 长帖。
