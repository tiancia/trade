# Polymarket Tools

这个目录只放 Polymarket Python 桥接和调试工具。

- `polymarket_place_order.py`: 后端调用的下单桥接脚本，从 stdin 读取 JSON payload，输出 Polymarket 响应 JSON。
- `debug_polymarket_place_order.py`: 本地调试 harness。默认只打印 payload；只有显式传入 `--confirm-live-order` 才会调用真实下单脚本。
- `call_polymarket_order_from_ai.py`: 用本地环境变量派生或打印 Polymarket API credentials 的辅助脚本。
- `examples/decision.json`: AI 决策样例。

密钥使用环境变量提供，例如 `POLYMARKET_PRIVATE_KEY`、`POLYMARKET_API_KEY`、`POLYMARKET_API_SECRET`、`POLYMARKET_API_PASSPHRASE`、`POLYMARKET_FUNDER_ADDRESS`。不要把真实值写进这个目录。
