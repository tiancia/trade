# Trading 回测说明

## 入口

回测由 `TradingController` 暴露，和后台自动交易任务相互独立：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/api/trading/backtests` | 校验参数并提交异步回测，成功接收返回 `202 Accepted` |
| `GET` | `/api/trading/backtests?offset=0&limit=100` | 分页查询本次进程内的回测运行摘要 |
| `GET` | `/api/trading/backtests/{runId}` | 查询运行状态、进度和汇总指标 |
| `GET` | `/api/trading/backtests/{runId}/trades?offset=0&limit=100` | 分页查询成交明细 |
| `GET` | `/api/trading/backtests/{runId}/equity?offset=0&limit=500` | 分页查询逐 K 线净值和回撤 |

请求示例：

```json
{
  "strategyId": "threshold-event-default",
  "instId": "BTC-USDT",
  "bar": "1m",
  "from": "2026-05-01T00:00:00Z",
  "to": "2026-05-02T00:00:00Z",
  "initialCash": 1000,
  "feeRate": 0.001,
  "slippageRate": 0.0005,
  "forceCloseAtEnd": true,
  "includeUnconfirmed": false,
  "maxCandles": 10000,
  "parameterOverrides": {
    "price-move-window-candles": 8
  }
}
```

`feeRate`、`slippageRate` 和收益指标都是比率，例如 `0.01` 表示 1%。费率必须位于 `[0, 1)`；`maxCandles` 允许 2 到 50000，默认 10000。

## 回放和成交口径

1. 历史 K 线按时间升序去重，默认排除 `confirm != 1` 的未完成 K 线。
2. 在第 N 根 K 线收盘后，只使用第 N 根及以前的数据评估策略，评估时间固定为该 K 线时间，不读取系统当前时间。
3. 信号在第 N+1 根 K 线开盘价成交，再叠加手续费和单向滑点，避免用产生信号的收盘价成交造成前视偏差。
4. 每根 K 线的净值按其收盘价计价。部分卖出会按卖出比例分摊剩余持仓成本，因此已实现盈亏不会重复扣除整笔建仓成本。
5. `forceCloseAtEnd=true` 时，最后一根 K 线按收盘价强制平掉剩余多头，并计入卖出手续费和滑点；关闭后，期末持仓按收盘价计入未实现盈亏。

历史行情从 OKX REST 接口按每页 300 根向过去分页拉取，每次响应仍通过 trading 行情事件总线异步写入缓存。回测直接合并本次响应和已有缓存，保留读后取数语义。

## 指标口径

| 字段 | 含义 |
| --- | --- |
| `candleCount` / `processedCandleCount` | 总 K 线数 / 已处理 K 线数，可用于显示运行进度 |
| `totalReturn` | `(期末净值 - 初始现金) / 初始现金` |
| `benchmarkReturn` | 同期标的首尾收盘价收益 |
| `maxDrawdown` | 逐 K 线净值相对历史高点的最大回撤 |
| `tradeCount` | 买卖成交执行次数 |
| `closedTradeCount` | 产生已实现盈亏的卖出成交次数 |
| `winRate` | 盈利卖出次数 / 卖出成交次数，保本卖出计入分母 |
| `profitFactor` | 总盈利 / 总亏损；没有亏损成交时为 `null`，避免把无限值错误表示为 0 |
| `totalFees` | 所有买卖手续费之和 |
| `realizedPnl` / `unrealizedPnl` | 已实现 / 期末未实现盈亏 |
| `finalCash` / `finalBaseAmount` / `finalEquity` | 期末现金、基础币持仓和总净值 |

成交明细中的 `fillPriceSource` 标明本次使用 K 线 `OPEN` 还是 `CLOSE` 成交；`timestamp` 是该 K 线的开盘时间。净值明细包含 `candleTimestamp`、`markPrice`、`cash`、`baseAmount`、`equity` 和当期 `drawdown`。

## 运行边界

- 当前 `BacktestBroker` 是现货多头模型。若全局 `instType` 为 `SWAP`、`FUTURES` 或 `OPTION`，请求会直接失败，避免在没有合约面值、保证金和资金费率模型时生成误导结果。
- 当前成交模型假设指定金额能在下一根开盘价附近全部成交，不模拟盘口深度、成交量参与率、挂单排队和 K 线内止盈止损路径。
- 两个专用工作线程执行回测，等待队列容量为 32；队列满时运行会以失败状态返回，应用关闭时最多等待 5 秒后中断剩余任务。
- 运行结果目前保存在进程内，最多保留 1000 个运行摘要。数据库中的 `okx_backtest_*` 表尚未接入运行仓储，因此应用重启后不能查询旧的 `runId`。
