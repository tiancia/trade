# ADR-0002：业务域内部统一四层，再按能力细分

- 状态：已接受
- 日期：2026-09-27
- 决策范围：`backend/`
- 补充：[ADR-0001](0001-domain-first-modular-monolith.md) 的域内目录组织；保留模块化单体和跨域依赖规则。

## 背景

Trading 根目录同时平铺 application、model、order、risk、market、event、execution、persistence、config 等包。业务能力与技术层级混用，读者难以快速区分业务用例、数据和基础设施。仅搬走 application 中的数据类没有解决入口过多的问题。

## 决策

业务域统一以 interfaces、application、domain、infrastructure 为一级目录，再按职责细分。automation 和 ai 同样遵循；纯共享传输模块 client 保留供应商分组，common 保留纯函数组织，不创建空层。

- persistence、Broker 实现、行情接入、队列实现和配置装配归 infrastructure。
- 用例按 service、strategy、order、risk、backtest、runtime 等能力组织；已有出站接口归 application/port。
- 稳定数据、状态和纯规则归 domain；回测成交和价格来源从 Broker 嵌套类型提取为独立领域模型。
- HTTP 和 scheduler 归 interfaces，保持薄入口。

此次目标是统一组织与明确职责，保留 API、配置、数据库、交易及资金安全语义。不把所有现有 application 到具体适配器的调用改成新接口，不重写策略/风控算法，也不引入框架或 Maven 子模块。

## 结果与验证

模块根目录入口减少，代码可以按“域 → 层 → 能力”导航。已有未提交整理继续保留，Java 类型全限定名随目录改变。测试镜像迁移，Mapper XML 更新类名；以架构检查、枚举配置/JSON 兼容测试和完整离线 clean test 验证。

代价是包路径较长，以及仓库外 Java 调用方需要更新引用。依然存在的 application 到基础设施实现的耦合在架构文档中明确记录，后续按具体业务需求逐步收敛。
