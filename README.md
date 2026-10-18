# Trade Workspace

这个仓库按运行职责拆成后端、前端、工具脚本、生成内容和文档，避免业务代码、调试脚本、运行产物混在同一层。

文档统一从 [项目文档总导航](docs/README.md) 进入。第一次接手项目，先读 [项目架构与部署上手指南](docs/PROJECT_GUIDE.md)：包含电脑、国内主机、德国 VPS 的通信图，端口和令牌说明，业务调用链、接入步骤、迭代发布与排障入口。

## 目录

```text
backend/             Spring Boot 后端：交易决策、调度、外部客户端、持久化、HTTP API
compose.yaml         MySQL、Redis、Prometheus、Grafana 的统一 Docker 入口
compose.prod.yaml    Ubuntu 生产部署：后端、交易驾驶舱、X 控制台、代理隧道和基础服务
compose.public.yaml  可选的双域名 HTTPS 公共访问覆盖配置
deploy/              生产配置模板、私有 / 公共网关、构建 / 发布 / 回滚脚本
docs/                文档总导航、全局交接指南及 backend/ 后端专题文档
frontend/trading-cockpit/  交易驾驶舱：Node SSR
frontend/x-console/  X 内容只读控制台：静态站点
frontend/text-game/  React + Vite 文字游戏前端：浏览器交互和界面状态
frontend/market/     React + Vite 二手集市前端：商品发布、筛选、会话和图片上传
tools/polymarket/    Polymarket Python 工具：CLOB 下单桥接、凭证调试、样例 payload
story/               AI 小说生成输出和素材，属于运行产物
```

后端 Java 包也按“业务域 + 层职责”拆分。具体规则见 [backend/README.md](backend/README.md)。

Ubuntu 首次部署和后续迭代按 [生产部署说明](deploy/README.md) 操作。默认将后端和两个前端一起部署到国内服务器，通过电脑 SSH 隧道访问 `http://127.0.0.1:18080`（交易驾驶舱）和 `http://127.0.0.1:18081`（X 控制台），无需域名或 TLS 证书。原有 `compose.yaml` 继续用于本地基础服务；生产使用独立的 `compose.prod.yaml` 和 Linux 命名卷。后续需要公共 HTTPS 访问时再叠加 `compose.public.yaml`。

## 常用命令

从仓库根目录执行：

```powershell
Copy-Item .env.example .env  # 首次执行；本地开发密码默认均为 123456
docker compose up -d
```

```powershell
cd backend
.\mvnw test
```

```powershell
cd frontend\text-game
npm run build
```

```powershell
cd frontend\market
npm run build
```

```powershell
cd tools\polymarket
python debug_polymarket_place_order.py --print-only
```

## 配置约定

- Polymarket、OKX、AI、数据库等密钥只从环境变量或启动工具加载的本地 `.env` 读取，不写进仓库。
- 从 `backend/` 启动时，Polymarket 下单脚本默认使用 `../tools/polymarket/polymarket_place_order.py`，需要改路径时设置 `POLYMARKET_ORDER_SCRIPT`。
- `story/` 是生成内容目录，不放服务端源码；后端生成位置由 `trade.story.output-dir` 控制。
