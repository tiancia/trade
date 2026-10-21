# Polymarket Tools

这个目录只放 Polymarket Python 桥接和调试工具。

- `polymarket_place_order.py`: 后端调用的下单桥接脚本，从 stdin 读取 JSON payload，输出 Polymarket 响应 JSON。
- `debug_polymarket_place_order.py`: 本地调试 harness。默认只打印 payload；只有显式传入 `--confirm-live-order` 才会调用真实下单脚本。
- `call_polymarket_order_from_ai.py`: 用本地环境变量派生或打印 Polymarket API credentials 的辅助脚本。
- `examples/decision.json`: AI 决策样例。
- `requirements.txt`: 现有桥接使用的固定版本 Python 客户端；本地可在独立虚拟环境安装 `python -m pip install -r tools/polymarket/requirements.txt`。

密钥使用环境变量提供，例如 `POLYMARKET_PRIVATE_KEY`、`POLYMARKET_API_KEY`、`POLYMARKET_API_SECRET`、`POLYMARKET_API_PASSPHRASE`、`POLYMARKET_FUNDER_ADDRESS`。不要把真实值写进这个目录。

后端默认通过 `tools/polymarket/polymarket_place_order.py` 调用下单脚本。如果部署目录不同，设置 `POLYMARKET_ORDER_SCRIPT` 覆盖。

生产后端镜像包含 Python 虚拟环境、该依赖清单和下单脚本，容器路径是 `/app/tools/polymarket/polymarket_place_order.py`；Compose 会固定这个容器内路径。镜像构建只验证依赖可导入，不创建 API 凭据或发起订单。Polymarket 的业务和执行开关仍默认关闭；启用前仍须独立核对账户、网络、地域限制与真实执行行为。

当前固定的 `py-clob-client-v2` 已被供应商标记弃用。这里保留现有协议兼容性，统一 SDK 的迁移需单独联调，不能把依赖可导入等同于真实交易接口已验证。
