# 微博热点评论与人工审核

## 当前范围

单目标账号、纯文字、单 RSS/Atom 新闻源、AI 草稿、正文版本、人工审核状态和后台发布。
AI 复用 `client.ai.AiTextClient` 和 `trade.ai.client`，不新增 SDK 或读取 `.env`。
新闻源提供评论资料，不证明事实已被独立核实，也不等于微博热搜排名采集。

`telegram` 是独立共享审核能力，和 `ai` 一样不得反向依赖业务域。当前
`UnavailableHumanReviewGateway` 不联网、不发送消息、不自动批准。因此真实流程停在
`PENDING_REVIEW`；审核至发布链路使用离线模拟验证。HTTP 没有批准端点。

## DDD 结构

```text
automation -> weibo/interfaces/scheduler -> application/service/WeiboPostService
  -> domain/model/WeiboPost                 # 不可变聚合、正文版本与状态规则
  -> application/port/HotEventSource        # infrastructure/trend 的 RSS/Atom 实现
  -> application/port/WeiboDraftGenerator   # application/decision 的 Prompt/解析
  -> application/port/WeiboPostRepository   # infrastructure/persistence 的 MyBatis 实现
  -> telegram/application/port/HumanReviewGateway
  -> application/port/WeiboPostPublisher    # 复用微博发布服务和 client/weibo
```

共享审核请求只携带模块、业务引用、正文版本、正文、上下文和有效期。未来其他模块可复用，
不依赖微博模型。`WeiboPostService.applyReview` 是内部应用用例，未来只能由审核人已认证的适配器调用。

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

新库基线包含 `weibo_account_gate`、`weibo_post`、`weibo_post_history`、`weibo_publish_attempt`。
原 OAuth 表不变。存量库先有 `migration_add_weibo_oauth.sql`，再手工执行
[`migration_add_weibo_workflow.sql`](../src/main/resources/db/migration/migration_add_weibo_workflow.sql)。
按照[迁移说明](../src/main/resources/db/migration/README.md)先备份并在隔离库验证；迁移目录不会自动执行。

## 配置与启动

参数见 `application.yml` 和 `.env.example`。首次试用保持真实发布关闭：

```powershell
$env:TRADE_WEIBO_WORKFLOW_ENABLED="true"
$env:TRADE_WEIBO_GENERATION_ENABLED="true"
$env:TRADE_WEIBO_TARGET_UID="目标微博账号的 UID"
$env:TRADE_WEIBO_LIVE_PUBLISHING_ENABLED="false"
$env:TRADE_WEIBO_PUBLISHING_ENABLED="false"
$env:TRADE_AUTOMATION_WEIBO_AUTO_START="false"
```

生成开关允许使用已配置 AI 的真实额度；离线测试不使用这些设置。管理员 API 需要部署环境中的
`TRADE_WEIBO_ADMIN_TOKEN`；OAuth 使用现有微博应用配置。不在仓库、命令输出或响应中填写真实凭据。

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

generation 和 publishing 两个循环在同一任务内串行。真实发布关闭时 publishing 循环仍扫描过期、
崩溃恢复和审核投递。默认正文 280 个 Unicode 字符，每 UTC 日生成 5 条、发布 3 次，间隔 1800 秒；
热点有效期 24 小时、审核 TTL 6 小时。循环间隔分别 900000/60000 ms，初始延迟 30000 ms。
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

## Telegram TODO

1. 共享模块实现 Bot API 传输、凭据配置和 reviewer/chat allowlist。
2. module/reference/version 为持久化投递身份；待审核循环重复 submit 时不得重复发消息。
3. 通过/驳回按钮的回调认证后生成 ReviewDecision，处理重复、过期和冲突，确认 answerCallbackQuery。
4. 组合/编排边界注册各模块决策处理器，路由至微博 applyReview；Telegram 不 import 微博。
5. 保存消息与 update/callback 身份。消息送达不等于批准；正文改动生成新版本，发布后反馈结果。

## 验证边界

测试使用模拟 AI/微博及 H2 MySQL 模式执行生产迁移和 Mapper；覆盖版本、门禁、CAS、配额并发、
事务回滚、崩溃恢复、RSS/Atom 和 XXE 拒绝，不发布、不调用付费 AI、不写生产库。
H2 不等于实际 MySQL；上线前仍需隔离 MySQL 迁移、实际源/权限/凭据检查和一条审核发布联调。
