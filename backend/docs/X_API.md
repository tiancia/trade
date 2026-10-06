# X API 接入

`client/x` 提供单账号 OAuth 1.0a user-context 协议封装，复用 JDK `HttpClient` 与现有 Jackson，不新增 SDK。调用方负责生成、审核、账号核对与发布门禁，协议客户端不会自动发帖或启动任务。

| 方法 | HTTP | 返回值 |
| --- | --- | --- |
| `getMe()` | `GET /2/users/me` | `XUser(id, name, username)` |
| `publishText(text)` | `POST /2/tweets`，JSON `{text}` | `XPost(id, text)` |

这两个端点支持 OAuth 1.0a 用户上下文认证，详见 [X 官方认证映射](https://docs.x.com/fundamentals/authentication/guides/v2-authentication-mapping)、[账号查询](https://docs.x.com/x-api/users/get-my-user) 和 [创建帖子](https://docs.x.com/x-api/posts/create-post)。响应必须包含正整数形式的字符串 `data.id`，长度为 1–19 位；未知字段忽略，错误或缺失结果不视为成功。

## 凭据与属性

本版本使用开发者平台配置的 API Key、API Secret、用户 Access Token 和 Access Token Secret，不提供 OAuth 网页授权流程。应用和用户令牌需要拥有发帖权限；真实权限与当前平台访问资格应在开发者平台确认。客户端四项凭据默认空，全部从 `toString` 排除。

`XClientProperties` 的绑定前缀为 `trade.x.client`：

下表是属性类默认值；仓库 `application.yml` 当前把 client.enabled 的回退值设为 true，实际开关还受部署环境覆盖。业务 workflow publishing 回退值为 false，不能仅凭客户端启用推断会发帖。

| 属性 | 默认值 |
| --- | --- |
| `enabled` | `false` |
| `api-key`、`api-secret`、`access-token`、`access-token-secret` | 空 |
| `base-url` | `https://api.x.com` |
| `connect-timeout-seconds` | `10` |
| `request-timeout-seconds` | `30` |
| `proxy.enabled` | `false` |
| `proxy.host` | `127.0.0.1` |
| `proxy.port` | `7897` |

以下只展示显式调用方式；示例不会由应用启动自动执行：

```java
XClientProperties properties = new XClientProperties();
properties.setEnabled(true);
properties.setApiKey(System.getenv("X_API_KEY"));
properties.setApiSecret(System.getenv("X_API_SECRET"));
properties.setAccessToken(System.getenv("X_ACCESS_TOKEN"));
properties.setAccessTokenSecret(System.getenv("X_ACCESS_TOKEN_SECRET"));
XApi api = new XApi(new XHttpClient(properties));
XUser user = api.getMe();
// 调用方确认账号、正文版本和人工审核结果后才允许执行：
XPost published = api.publishText("已审核的正文");
```

`publishText` 拒绝空白文本，正文保留原样；生成方向、字数限制和平台加权长度由业务用例控制。客户端不开启媒体、回复、线程或自动截断。

## 签名与失败处理

签名采用 OAuth 1.0a HMAC-SHA1，按 [X 官方签名说明](https://docs.x.com/fundamentals/authentication/oauth-1-0a/creating-a-signature) 与 [RFC 5849](https://www.rfc-editor.org/rfc/rfc5849) 百分编码、排序并签名。每次请求使用新的 nonce 与当前秒时间戳；URL query 参数参与规范化，基础 URI 去除 query/fragment；JSON 正文不作为表单参数参与签名。

客户端禁用重定向，不自动重试。HTTP 失败抛 `XApiException`，仅提供 `statusCode()`；解析、I/O 与中断异常使用固定文案，不保留原始响应、Authorization 或底层异常链。中断恢复线程标记。发送超时可能已经成功发帖，业务流程应保留不确定状态并人工核对，不能直接重发。

业务发布器将 `GET /2/users/me` 的 HTTP 失败保留为 `FAILED` 与 `preflight HTTP <状态>`，可确认创建帖子请求尚未发送；`POST /2/tweets` 的明确 4xx（除 408）为 `FAILED`，其余不确定结果为 `UNKNOWN`。日志和记录只保留阶段、状态和固定文案，单凭状态码不能证明余额不足。

按 2026-10-06 核实的 [官方计费文档](https://docs.x.com/x-api/getting-started/pricing)，X API 使用预付 credits 按使用量扣费；余额耗尽或达到 spending limit 都可能阻断 API。账号查询也可能产生读取费用。价格和可用余额以开发者控制台为准；购买 AI 服务额度不代表 X API 有余额。

离线测试使用 fake sender，覆盖公开签名向量、GET/POST、JSON 正文、nonce、属性门禁、异常脱敏和代理，不读真实凭据、不调用 X。实际账号权限、网络、额度和发布仍需独立联调。
