# 本地基础设施

仓库根目录的 Compose 入口加载本目录的服务定义。在 Windows 本地提供 MySQL、Redis、Prometheus 和 Grafana，生产部署使用独立的 Compose 文件。[本地入口](../../compose.yaml) · [服务定义](compose.yaml) · [生产部署](../../deploy/README.md)

## 启动与停止

在仓库根目录执行。首次使用时，从模板创建本地配置。

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
docker compose up -d mysql redis
```

需要监控时运行 `docker compose up -d prometheus grafana`。要一次启动全部四项服务，运行 `docker compose up -d`。查看状态用 `docker compose ps`，跟踪某个服务用 `docker compose logs -f mysql`。结束后运行 `docker compose down`。

## 服务与数据

Compose 将四个服务的数据绑定到 Windows 宿主机的 `A:\DockerData\trade-infrastructure\` 下。停止或重建容器后，各服务继续使用对应子目录的数据。

| 服务 | 默认本机端口 | 数据子目录 | 用途 |
| --- | --- | --- | --- |
| MySQL | 3306 | `mysql/` | 业务数据库 |
| Redis | 6379 | `redis/` | 热行情缓存 |
| Prometheus | 9090 | `prometheus/` | 抓取后端指标、计算告警规则 |
| Grafana | 3000 | `grafana/` | 查询指标、查看预置看板 |

抓取目标、规则和看板由本目录下的 `observability/` 提供。本地 Prometheus 计算告警，生产通知由部署侧 Alertmanager 投递。[指标与看板](../../docs/backend/OBSERVABILITY.md) · [生产告警与备份](../../deploy/README.md#数据备份和监控)

## 后端连接配置

MySQL 首次使用空数据目录时，按根目录 `.env` 中的 `MYSQL_DATABASE`、`MYSQL_USER` 和 `MYSQL_PASSWORD` 创建业务库与账号。已有数据目录沿用数据库中的账号。后端启动进程需要单独设置对应连接变量。[Compose 配置模板](../../.env.example) · [后端启动说明](../README.md#快速启动)

```text
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/trade_system
SPRING_DATASOURCE_USERNAME=<MYSQL_USER>
SPRING_DATASOURCE_PASSWORD=<MYSQL_PASSWORD>
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=<REDIS_PASSWORD>
```
