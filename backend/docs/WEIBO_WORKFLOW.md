# 微博热点评论与人工审核

## 当前范围

单目标账号、纯文字、单 RSS/Atom 新闻源、AI 草稿、正文版本、人工审核状态和后台发布。
AI 复用 `client.ai.AiTextClient` 和 `trade.ai.client`，不新增 SDK 或读取 `.env`。
新闻源提供评论资料，不证明事实已被独立核实，也不等于微博热搜排名采集。

审核契约、请求与决策属于微博域，分别位于 `weibo/application/port` 和 `weibo/domain/model`。
启用后，`weibo/infrastructure/review/TelegramHumanReviewGateway` 通过
[Telegram Bot API](TELEGRAM_API.md) 发送完整草稿及“通过并发布 / 拒绝”按钮，轮询获取认证回调。
批准触发原有发布用例；拒绝保持 `REJECTED`，不会发布。HTTP 没有批准端点。
关闭审核时使用 `UnavailableHumanReviewGateway`，草稿停在 `PENDING_REVIEW`。
装配及审核行为属于 `weibo`，协议留在 `client/telegram`，没有顶层 `telegram` 模块。

## DDD 结构

```text
automation -> weibo/interfaces/scheduler -> application/service/WeiboPostService
  -> domain/model/WeiboPost                 # 不可变聚合、正文版本与状态规则
  -> application/port/HotEventSource        # infrastructure/trend 的 RSS/Atom 实现
  -> application/port/WeiboDraftGenerator   # application/decision 的 Prompt/解析
  -> application/port/WeiboPostRepository   # infrastructure/persistence 的 MyBatis 实现
  -> application/port/HumanReviewGateway    # infrastructure/review 的 Telegram 适配器
  -> application/port/WeiboReviewDeliveryStore # 投递去重、决定、轮询 cursor/租约
  -> application/port/WeiboPostPublisher    # 复用微博发布服务和 client/weibo
```

微博域内的审核请求携带模块、业务引用、正文版本、正文、上下文和有效期。
`WeiboPostService.runReviews` 经认证适配器消费 `ReviewDecision`，再调用内部用例 `applyReview`。

## 状态与可靠性

```text
GENERATING -> PENDING_REVIEW -> APPROVED -> PUBLISHING -> PUBLISHED
     |              |             |             |
GENERATION_FAILED  REJECTED      EXPIRED      FAILED / UNKNOWN
```

- 每个账号和规范化来源 URL 只生成一次；标题、日期、URL fragment 更新不重复生成。
  不同 URL 报道同一事件暂不做语义去重，同一 URL 的进展不自动重新生成。
- AI 调用前预留并落库；重复事件、每日配额不足时不调用 AI；失败也计入生成配额。
- `revision` 是持久化乐观锁，`contentVersion` 在修改正文时增加。编辑清空批准记录，
  重新进入待审核；旧版本回调无法批准新稿。历史保存每次状态和正文版本。
- 有效期取审核 TTL 与来源时间加热点有效期中较早者，修改不延长有效期。
- 所有发布尝试（包括失败/未知）计入上限和间隔。按账号数据库行锁检查配额，CAS 领取稿件。
  领取、尝试和历史同事务提交，网络请求不占数据库事务。
- 网络错误、HTTP 408/5xx、响应缺少微博 ID、崩溃后超时的发布进入 `UNKNOWN`；
  明确的其他 4xx 拒绝进入 `FAILED`。两者都不自动重试，也不能通过编辑触发重发。
- 完成事务失败时保留 `PUBLISHING`，后续扫描转为 `UNKNOWN`。必须人工核对微博，
  本阶段不提供把未知状态强行重置成待发布的接口。不宣称外部接口严格 exactly-once。
- `GENERATING` 超时转为 `GENERATION_FAILED`，不再次自动消耗 AI。默认超时 300 秒，
  应大于实际 AI/微博请求最长耗时。停止任务不打断进行中的网络请求。
- 配额按 UTC 自然日计算。数据库连接时区应固定并在真实 MySQL 联调确认。

## 数据库升级

[`db/schema/weibo/schema.sql`](../src/main/resources/db/schema/weibo/schema.sql) 包含 OAuth、账号、草稿、审核历史、发布尝试和 Telegram 审核投递/轮询表。应用启动会创建缺失的表，已有表不会自动补字段。

需要在启动前手工建表或升级历史结构时，先检查目标库。手工脚本的依赖顺序为
[`migration_add_weibo_oauth.sql`](../src/main/resources/db/upgrade/weibo/migration_add_weibo_oauth.sql)、
[`migration_add_weibo_workflow.sql`](../src/main/resources/db/upgrade/weibo/migration_add_weibo_workflow.sql)、
[`migration_add_weibo_review_delivery.sql`](../src/main/resources/db/upgrade/weibo/migration_add_weibo_review_delivery.sql)。
只选择适用于实际结构的脚本，按照[升级说明](../src/main/resources/db/upgrade/README.md)先备份并在隔离库验证；`upgrade/` 不会自动执行。

## 配置与启动

参数见 `application.yml` 和 `.env.example`。首次试用保持真实发布关闭：

```powershell
$env:TRADE_WEIBO_WORKFLOW_ENABLED="true"
$env:TRADE_WEIBO_GENERATION_ENABLED="true"
$env:TRADE_WEIBO_TARGET_UID="目标微博账号的 UID"
$env:TRADE_WEIBO_LIVE_PUBLISHING_ENABLED="false"
$env:TRADE_WEIBO_PUBLISHING_ENABLED="false"
$env:TRADE_AUTOMATION_WEIBO_AUTO_START="false"
$env:TRADE_TELEGRAM_ENABLED="true"
$env:TRADE_WEIBO_TELEGRAM_REVIEW_ENABLED="true"
# Bot Token 由部署环境安全注入 TRADE_TELEGRAM_BOT_TOKEN，不在示例填写。
$env:TRADE_WEIBO_TELEGRAM_CHAT_ID="123456789"
$env:TRADE_WEIBO_TELEGRAM_REVIEWER_USER_IDS="123456789"
```

生成开关允许使用已配置 AI 的真实额度；离线测试不使用这些设置。管理员 API 需要部署环境中的
`TRADE_WEIBO_ADMIN_TOKEN`；OAuth 使用现有微博应用配置。不在仓库、命令输出或响应中填写真实凭据。

机器人须已加入目标聊天；私人聊天先向机器人发送 `/start`。chat ID 与 reviewer ID 必须是数字，
审核人列表可以逗号分隔多个用户 ID，不能用用户名鉴权。开启审核时缺少 Token、聊天、审核人，
或未开启 workflow/Telegram client 会使启动校验失败；默认全部关闭时不需要这些值。
网络需要代理时使用 `TRADE_TELEGRAM_PROXY_ENABLED/HOST/PORT`。Telegram 审核时正文上限配置不超过 3000，
默认仍为 280；正文完整展示，过长的来源上下文会标明截断。

`TRADE_WEIBO_FEED_URL` 配置可信 HTTPS RSS/Atom 源，不跟随重定向。RSS 要求
title/link/description/pubDate；Atom 要求 title/alternate link、summary 或 content、published 或 updated。
来源发布时间作为事件资料时间。缺少字段、非法时间或摘要的条目跳过；前 20 条、响应最大 256 KiB。
过期事件不生成，不使用 fallback 热点。无源时可以手动提交资料。

任务登记不等于运行；通过运维控制面启动/停止：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/automation/tasks/weibo
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/weibo/start
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/weibo/stop
```

generation、review 和 publishing 三个循环在同一任务内串行。真实发布关闭时 publishing 循环仍扫描过期、
崩溃恢复和审核投递。默认正文 280 个 Unicode 字符，每 UTC 日生成 5 条、发布 3 次，间隔 1800 秒；
热点有效期 24 小时、审核 TTL 6 小时。循环间隔分别 900000/5000/60000 ms，初始延迟 30000 ms。
review 短轮询只订阅 `callback_query`，批准后立即尝试发布；配额、间隔或开关不满足时保留批准状态，
由后续 publishing 循环重查。网络请求与生成会占用同任务互斥锁，5000 ms 不是审核响应的保证时限。
本地字符上限不能代替平台实际限制和账号权限。

真实自动发布必须同时满足 workflow enabled、publishing enabled、live publishing enabled、
固定账号有效 Token、对应正文版本批准、未过期、配额和间隔。Telegram 与微博权限联调前保持关闭。

旧 `POST /api/weibo/statuses` 保留 URL，但默认禁止直接发布；workflow 启用时不能绕过审核。
只有 workflow 关闭、`TRADE_WEIBO_REVIEW_REQUIRED=false` 且 live publishing 开启时，才能明确使用旧管理员
手动发布模式。该模式不属于审核工作流，不使用工作流配额。

## 管理 API

全部要求 `X-Weibo-Admin-Token`，OAuth callback 仍用 state 校验。

| 方法与路径 | 行为 |
| --- | --- |
| `POST /api/weibo/posts` | 提交事件资料生成；201 返回草稿或生成失败状态，重复/配额不足 409 |
| `GET /api/weibo/posts?limit=30` | 最近草稿，上限 100 |
| `GET /api/weibo/posts/{id}` | 状态、来源、正文和审核说明，不返回 Token |
| `GET /api/weibo/posts/{id}/history` | 最近 100 条历史，revision 倒序 |
| `PUT /api/weibo/posts/{id}` | 提供最新 expectedRevision 修改正文并重新待审 |

生成正文示例（时间在实际提交时必须仍有效）：

```json
{"title":"事件标题","sourceUrl":"https://example.test/news/1","summary":"来源事实摘要","occurredAt":"2026-10-02T01:00:00Z"}
```

修改正文示例：

```json
{"expectedRevision":1,"body":"人工修改后的评论正文"}
```

非法输入返回 400；门禁、版本冲突及非法状态返回 409。供应商原始错误正文不再通过 HTTP 返回。
automation 控制面仍由网关/内网保护，参照运维手册。

## Telegram 审核与故障恢复

- 投递按 bot ID、草稿 ID、正文版本唯一。先持久化 `SENDING` 占位，再发送，成功绑定聊天与消息 ID 后变为 `SENT`。
  待审核扫描不会重复发送同版本；送达不等于批准。
- 按钮使用随机投递引用。只接受配置审核人、配置聊天、当前 bot 及已绑定消息；正文版本和有效期由微博聚合再次校验。
  第一条认证决定先保存 callback ID、决定和微秒时间，再执行审核；重复/相反按钮不会更改已决定版本。
- cursor 和租约按 `getMe().id` 隔离，重启保留进度。消费提交或确定已失效后才推进 offset；暂时数据库失败会保留更新重试。
  回调应答失败不撤销审核。多个实例共享数据库租约，但同一个机器人不能同时给另一套应用轮询使用。
  多实例使用同步时钟；更换目标微博账号后，不再投递或批准旧账号的待审稿。
  如已有 webhook，先停用它再使用轮询；项目不自动删除其他应用的 webhook。
- 发送超时、错误或消息绑定落库失败会留下 `UNKNOWN`；占位后崩溃可能保留 `SENDING`。两者均不自动重发，按钮也不能批准未绑定消息。
  先人工核对 Telegram，再用管理 API 的最新 revision 修改正文（可以提交同样正文）生成新版本重新送审；
  旧按钮无法批准新版本。不要通过直接改库把未知发送或未知微博发布状态改成成功。
- 启用前后保留 `TRADE_WEIBO_REVIEW_REQUIRED=true`。按钮确认表示审核结果；实际发布结果从草稿状态/历史查看，当前不额外发送发布结果消息。

## 验证边界

测试使用模拟 AI/Telegram/微博及 H2 MySQL 模式执行生产迁移和 Mapper；覆盖投递去重、回调认证与恢复、租约/游标、版本、门禁、CAS、配额并发、
事务回滚、崩溃恢复、RSS/Atom 和 XXE 拒绝，不发布、不调用付费 AI、不写生产库。
H2 不等于实际 MySQL；上线前仍需隔离 MySQL 迁移、实际源/权限/凭据检查和一条审核发布联调。
