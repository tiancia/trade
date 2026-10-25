# Polymarket Tools

这个目录只放 Polymarket Python 桥接和调试工具。

- `polymarket_place_order.py`: 后端调用的下单桥接脚本，从 stdin 读取 JSON payload，输出 Polymarket 响应 JSON。
- `debug_polymarket_place_order.py`: 本地调试 harness。默认只打印 payload；只有显式传入 `--confirm-live-order` 才会调用真实下单脚本。
- `call_polymarket_order_from_ai.py`: 凭据辅助脚本，默认不联网；只有显式传入 `--derive-api-credentials` 才派生凭据，且默认隐藏凭据值。确需在私密终端查看时再加 `--show-secrets`，不要把输出贴到日志或聊天中。
- `examples/decision.json`: AI 决策样例。
- `requirements.txt`: 现有桥接使用的固定版本 Python 客户端；本地可在独立虚拟环境安装 `python -m pip install -r tools/polymarket/requirements.txt`。

密钥使用环境变量提供，例如 `POLYMARKET_PRIVATE_KEY`、`POLYMARKET_API_KEY`、`POLYMARKET_API_SECRET`、`POLYMARKET_API_PASSPHRASE`、`POLYMARKET_FUNDER_ADDRESS`。不要把真实值写进这个目录。

后端默认通过 `tools/polymarket/polymarket_place_order.py` 调用下单脚本。如果部署目录不同，设置 `POLYMARKET_ORDER_SCRIPT` 覆盖。

生产后端镜像包含 Python 虚拟环境、该依赖清单和下单脚本，容器路径是 `/app/tools/polymarket/polymarket_place_order.py`；Compose 会固定这个容器内路径。镜像构建只验证依赖可导入，不创建 API 凭据或发起订单。Polymarket 的业务和执行开关仍默认关闭；启用前仍须独立核对账户、网络、地域限制与真实执行行为。

当前固定的 `py-clob-client-v2` 已被供应商标记弃用。这里保留现有协议兼容性，统一 SDK 的迁移需单独联调，不能把依赖可导入等同于真实交易接口已验证。

下单桥接必须把配置的签名类型和订单类型完整传给 SDK；不支持时直接失败，不会移除签名类型重试，也不会让 FAK/FOK 静默退回 SDK 默认订单类型。API key、secret、passphrase 必须三项一起配置；缺项时停止，不会忽略已有凭据另行派生。

Java 后端向脚本发送订单请求中冻结的订单类型；stdin 写入、进程执行和输出排空共用 `timeout-ms` 时限。stdout、stderr 各限制为 1 MiB，超限终止子进程；失败日志和异常只记录退出码与输出长度，不附带可能含凭据的原始 SDK 输出。超时或异常不证明交易所未接受订单，必须人工核实实际结果，不应直接重复下单。

脚本退出码为 0 只表示进程正常结束。后端还要求响应中 `success=true`、无 `errorMsg` 错误、非空订单 ID 和已知的接受状态，才记录 `PLACED`；这表示订单被接受，不表示成交。拒绝或不完整响应会令自动任务报失败。LIVE 决策必须先成功创建数据库审计记录，创建失败时不调用 AI 或下单。

后端真实 BUY 执行前必须以独立数据库事务取得 `polymarket_execution_guard` 预留。执行异常、确认缺失、进程崩溃或最终审计写入失败时，预留不会自动释放，其他实例及重启后的任务也无法继续真实下单。核实真实账户后按[运维恢复步骤](../../docs/backend/OPERATIONS.md#polymarket-未决执行恢复)处理；不能靠重启、清表或重复下单试探结果。此保护由 Java 后端负责，手动直接运行 Python 脚本不经过该数据库门禁。

离线安全回归测试：`python -m unittest discover -s tools/polymarket/tests`。测试使用本地假客户端，不连接 Polymarket、不读取真实凭据、不提交订单。
