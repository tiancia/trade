# Trade Workspace

这是一个包含后端、前端、自动化脚本和生成内容的工作区。顶层目录按运行职责拆分，避免业务源码、工具脚本和产物混在一起。

## 目录

```text
backend/             Spring Boot 后端，包含 OKX 交易、Polymarket、小说生成、文字游戏 API
frontend/text-game/  React + Vite 文字游戏前端
tools/polymarket/    Polymarket Python 下单桥接和调试工具
story/               小说生成输出和素材
docs/                项目结构和维护约定
```

## 常用命令

```powershell
cd A:\trade\backend
.\mvnw test
```

```powershell
cd A:\trade\frontend\text-game
npm run build
```

```powershell
cd A:\trade\tools\polymarket
python debug_polymarket_place_order.py --print-only
```

Polymarket、AI、数据库等密钥只从环境变量读取，不要写进仓库。
