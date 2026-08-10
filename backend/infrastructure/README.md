# 本地基础设施

仓库根目录 `A:\trade\compose.yaml` 是本地中间件的统一命令入口，它加载 `backend/infrastructure/compose.yaml` 中的服务定义。目前包含：

- MySQL：业务主存储；
- Redis：可降级的热行情缓存；
- Prometheus：指标采集与告警规则计算；
- Grafana：指标查询与仪表盘展示。

后续增加 Kafka、MinIO、Loki 等中间件时，在 `services` 中增加服务；只有存在独立配置时，才在本目录下增加对应子目录。监控专属配置统一保留在 `observability/` 下。

## 目录结构

```text
trade/
├── compose.yaml                     # 默认 Compose 命令入口
├── .env.example                     # Docker 基础设施配置模板
└── backend/
    ├── .env.example                 # 后端应用配置模板
    └── infrastructure/
        ├── compose.yaml             # 中间件服务定义
        └── observability/
            ├── prometheus/          # 抓取配置和告警规则
            └── grafana/             # 数据源、看板 provisioning
```

## 首次启动

固定从仓库根目录 `A:\trade` 执行：

```powershell
Copy-Item .env.example .env
# 本地开发的 MySQL、Redis 和 Grafana 密码默认均为 123456
docker compose up -d
```

只启动业务依赖：

```powershell
docker compose up -d mysql redis
```

只启动监控服务：

```powershell
docker compose up -d prometheus grafana
```

查看状态和日志：

```powershell
docker compose ps
docker compose logs -f mysql redis
```

停止容器但保留数据：

```powershell
docker compose down
```

命名卷保存 MySQL、Redis、Prometheus 和 Grafana 数据。`down --volumes` 会删除这些数据，只应在明确需要重建本地数据时使用。

## 从旧目录迁移

旧配置使用 Compose 项目名 `trade-observability`。如果旧容器仍在运行，应先用根目录 `.env` 停止旧项目，再启动新项目，避免 `3000`、`9090` 等端口冲突：

```powershell
docker compose -p trade-observability down
docker compose up -d
```

项目名变化不会删除旧命名卷。需要复用旧数据时，在首次启动新项目之前，把根目录 `.env` 中相应卷名改为旧名称，例如：

```text
MYSQL_VOLUME_NAME=trade-observability_mysql-data
REDIS_VOLUME_NAME=trade-observability_redis-data
PROMETHEUS_VOLUME_NAME=trade-observability_prometheus-data
PROMETHEUS_VOLUME_EXTERNAL=true
GRAFANA_VOLUME_NAME=trade-observability_grafana-data
GRAFANA_VOLUME_EXTERNAL=true
```

MySQL 的 `MYSQL_USER`、`MYSQL_PASSWORD` 等初始化变量只对空数据卷生效；复用旧 MySQL 卷时继续使用旧库中已有的账号密码，或在数据库内显式迁移账号。

## 后端连接配置

Compose 会创建 `trade_system` 数据库和权限受限的 `trade` 用户。把仓库根目录 `.env` 的容器账号密码同步到 `backend/.env` 的 Spring 连接变量：

```text
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/trade_system
SPRING_DATASOURCE_USERNAME=<MYSQL_USER>
SPRING_DATASOURCE_PASSWORD=<MYSQL_PASSWORD>
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=<REDIS_PASSWORD>
```

不要把根目录 `.env` 或生产密码提交到仓库。模板中的 `123456` 只适合端口绑定到 `127.0.0.1` 的本地开发环境；如需远程访问或生产部署，必须换成强密码，并通过受控网络和防火墙显式开放。

## 可选：从任意目录执行

不建议全局设置 `COMPOSE_FILE=A:\trade\compose.yaml`，因为它会改变所有普通 `docker compose` 命令的默认目标。确实需要从任意目录管理本项目时，可在 PowerShell `$PROFILE` 中定义一个独立命令：

```powershell
function trade-compose {
    docker compose --project-directory A:\trade -f A:\trade\compose.yaml @args
}
```

重新打开终端后，可以在任意目录安全地执行：

```powershell
trade-compose up -d
trade-compose ps
trade-compose down
```
