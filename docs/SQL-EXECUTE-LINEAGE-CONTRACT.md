# /execute 响应 lineage 契约（D11 评审稿）

> 状态：已评审（2026-11-08：字段名 `tableToSource` + 选项 A 本地提取，owner 4 问全部确认）。本文事实均带 file:line，D12 据此实现。

## 结论（推荐）

`SqlExecuteResponse` 新增可空字段 `tableToSource: Map<String,String>`，语义=「SQL 涉及表全名 → 数据源标识」，由 **sql-gateway 本地提取**（AST 表名 + catalog 前缀映射），/execute 实时链路**不调用** lineage-analyzer；lineage-analyzer 既有 /analyze 等图谱 API 保持不变，供前端深度血缘直连。

## 事实（file:line）

- 现状：/execute 响应 `SqlExecuteResponse` 无血缘字段（platform/sql-gateway/src/main/java/com/levango7/dataenginebdp/sqlgateway/model/SqlExecuteResponse.java:15）。
- 既有同语义字段：/cross-source 响应 `CrossSourceResponse.tableToSource` = Map<表, 源>，注释即「表→源映射（用于前端可视化）」（platform/sql-gateway/src/main/java/com/levango7/dataenginebdp/sqlgateway/model/CrossSourceResponse.java:61）。
- lineage-analyzer 输出形状：`POST /api/v1/lineage/analyze` 回 `LineageGraph.toEChartsFormat()` = `{categories, nodes, links, meta}`（platform/governance/lineage-analyzer/src/main/java/com/levango7/dataenginebdp/governance/lineage/model/LineageGraph.java:178）。**表级边只由 INSERT / CREATE_TABLE 产生，纯 SELECT 的 links=[]**（platform/governance/lineage-analyzer/src/main/java/com/levango7/dataenginebdp/governance/lineage/analyzer/TableLineageExtractor.java:84）。
- 模块依赖方向：lineage-analyzer 反向 import sql-gateway 的 parser（TableLineageExtractor.java:3）⇒ sql-gateway 不可回 import lineage-analyzer（循环依赖）。
- sql-gateway 本地已有全部要素：`ASTNode.extractTables()`（/parse 端点在用，SqlGatewayController.java:152）与 catalog→源映射（CatalogBasedSourceResolver.java:43：hive/trino/mysql/iceberg/delta/postgresql→trino，doris→doris；无 catalog 前缀→`sql-gateway.default-source`；**未知 catalog→defaultSource + warn**（CatalogBasedSourceResolver.java:88-92 实际行为）；字面量 `"unknown"` 仅是 resolveSource 返回 null 时的防御兜底，约定同 CrossSourceExecutor.java:371）。

## 契约（拟）

- 字段：`tableToSource`，`Map<String,String>`，JSON 同名键；加在 `SqlExecuteResponse`（Lombok @Builder @Data，增量字段）。
- 取值规则：
  1. 表名含 catalog 前缀（`hive.orders`）→ 经 CatalogBasedSourceResolver 映射到源标识；catalog 未命中 → 默认源 defaultSource + warn（resolveSource 实际行为；`"unknown"` 仅是其返回 null 时的防御兜底）。
  2. 表名无前缀 → `sql-gateway.default-source`（默认 trino）。
  3. 无表 SQL（如 `SELECT 1`）→ 空 map `{}`（语义=「无表」）。
  4. 未启用（sourceResolver 未注入，setter `@Autowired(required=false)`）/ AST 解析失败 / 执行失败（status≠SUCCESS）→ `null`（JSON 缺席）。
- 降级：AST 解析异常 ⇒ `tableToSource=null` + `message` 追加注记；best-effort，绝不影响查询主链路结果。实现注记：为区分规则 3（无表→`{}`）与规则 4（解析失败→`null`），代码直接 `parseAuto(sql).extractTables()` 并捕获 `SqlParseException`；不调用 `SqlParserService.extractTables()`——它吞异常返回空列表，会把解析失败误判成「无表」。
- 缓存路径：`copyResponse` 必须透传该字段（SqlRoutingService.java:206，否则缓存命中丢字段）。
- 向后兼容：纯增量；存量消费方零改动；OpenAPI/Swagger 自动生成。
- 响应示例（成功、有表）：

```json
{
  "queryId": "…", "status": "SUCCESS",
  "columns": ["cnt"], "rows": [[42]], "durationMs": 12,
  "engine": "trino", "cached": false, "truncated": false,
  "tableToSource": {"hive.orders": "trino", "dim_user": "trino"}
}
```

## 选项

| | A 本地提取（推荐） | B HTTP 回填 lineage-analyzer（plan 字面） |
|---|---|---|
| 改动点 | DTO +1 字段；SqlRoutingService.doExecute 成功后填充（复用 parser + 映射）；copyResponse 透传 | A 全部 + 服务 URL 配置 + WebClient 调用 /analyze + 认证（ServiceTokenMinter 模式）+ 超时熔断 + 部署接线 |
| 爆炸半径 | sql-gateway 单模块，零新依赖 | 跨服务同步调用进 /execute 关键路径（+1 跳延迟、可用性耦合）；chart/env 接线 |
| 测试面 | 单测：有前缀/无前缀/unknown catalog/无表/解析失败降级/缓存命中透传 | A 全部 + IT mock 服务 + 超时/熔断用例 |
| 价值 | 恰好等于 D12 DoD「/execute 带 tableToSource」 | /analyze 对纯 SELECT 表级 links=[]（事实见上），回填不到 tableToSource 语义，源表清单仍得本地 AST |

备选项 C（DTO 同时挂 lineage 富对象 + tableToSource）：超前于 DoD，本期不做。

## 需 owner 回答

1. 字段名：`tableToSource`（与 /cross-source 对齐，前端可复用既有消费代码）还是 plan 字面 `lineage`？
2. 接线：A（本地提取）还是 B（HTTP 回填）？
3. 取值规则 1/2 的 `"unknown"` / default-source 兜底是否认可？
4. 失败/未启用 `null` 与无表 `{}` 的区分是否认可？

## 未核清单

- ColumnLineageExtractor 对 SELECT 的字段级边形状（未读；本期不消费字段级血缘）。
- 前端是否已有消费 /cross-source tableToSource 的组件（仅影响展示复用，不影响契约）。
- lineage-analyzer 部署侧服务名/端口（选 B 才需要；选 A 不涉及）。

## 边界

- 不改 /analyze、/upstream、/downstream、/impact 等 lineage-analyzer 既有 API。
- 不改 /cross-source 契约。
- 不做字段级血缘（问数可解释只需表级来源）。
