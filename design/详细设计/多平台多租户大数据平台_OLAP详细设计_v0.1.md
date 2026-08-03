# L2.5 OLAP（Doris 2.1）· 详细设计（v0.1）

> 归属：多平台多租户大数据平台 · L2 大数据引擎层
> 适用套餐：标准版、旗舰版（基础版不含，对齐 §11.5.1 套餐矩阵）
> 对标：Apache Doris 2.1（国产开源 MPP）/ StarRocks（可选）/ ClickHouse（仅性能基准对照，非选型）
> 关联：L2.1 统一存储（External Catalog 直查 Iceberg）；L2.6 湖仓集一体（集层=实时在线）；L2.7 统一 SQL 网关（路由 OLAP）；L2.3 Flink（CDC→物化同步）；L0.11 封装层（资源/隔离）

## 1. 定位与价值

OLAP 是湖仓集一体的**集层在线服务引擎**：以 Apache Doris 承载实时 OLAP、报表、在线 API 查询，毫秒级响应；通过 External Catalog 直查 Iceberg 避免数据冗余拷贝，物化视图加速高频查询。

客户价值：
- "实时在线"——Flink CDC 实时入 Doris 物化视图，BI 报表与在线服务毫秒出结果。
- "数据不搬运"——Doris External Catalog 直查 Iceberg 湖仓表，零拷贝访问历史数据。
- "一 SQL 通吃"——经 L2.7 统一 SQL 网关，客户无感路由到 Doris，无需切换连接。

平台价值：
- Doris Operator on K8s 标准化部署，FE+BE 分离、弹性扩缩，与 SKE 发行版深度适配。
- 国产开源、ARM 构建成熟、信创生态完善，四环境同构交付，不锁云托管服务。

## 2. 总体架构

```text
┌──────── 统一 SQL 网关（L2.7，路由 OLAP 查询） ────────┐
│        Trino(交互/跨源)        Doris(在线/物化/报表)    │
└──────────────────────┬──────────────────────────┘
                       ▼
┌──────── Doris on K8s（Doris Operator） ────────┐
│  FE（元数据/解析/调度，3 节点高可用）              │
│     ▼ Catalog 路由                                │
│  BE（执行/存储，StatefulSet，NVMe 本地缓存）       │
│     ├── Internal Catalog  → Doris 本地表（集层）   │
│     └── External Catalog  → Iceberg on 对象存储    │
└──────────────────────┬──────────────────────────┘
        ┌──────────────┴──────────────┐
        ▼                             ▼
  Flink CDC（L2.3）              L2.1 统一存储
  实时入 Doris 物化视图           直查 Iceberg 湖仓表
```

## 3. External Catalog 直查 Iceberg

Doris 通过 External Catalog 直接读取 L2.1 统一存储中的 Iceberg 表，避免将湖仓数据二次拷贝进 Doris 内部存储，实现"湖仓直查 + 集层物化"的混合执行。

```sql
-- SQL示例：创建 Iceberg External Catalog
CREATE CATALOG iceberg_catalog PROPERTIES (
  'type'='iceberg',
  'iceberg.catalog.type'='rest',
  'iceberg.catalog.uri'='http://iceberg-rest:8181',
  'warehouse'='s3://shuqing-warehouse/',
  's3.endpoint'='${STORAGE_ENDPOINT}',
  's3.access_key'='${AK}','s3.secret_key'='${SK}'
);
-- 跨源联邦查询：Doris 物化表 JOIN Iceberg 湖仓表
SELECT a.uid, b.label FROM doris_db.online_user a
  JOIN iceberg_catalog.lake_db.user_tag b ON a.uid=b.uid;
```

> 关键约束：External Catalog 只读直查 Iceberg；高频/聚合查询走物化视图落地 Doris 内部表，平衡新鲜度与性能。

## 4. 物化视图与实时同步

- **实时入仓**：Flink CDC 捕获 MySQL/Kafka 变更，实时写入 Doris 内部表（集层在线表），支撑毫秒级在线服务。
- **物化视图**：对高频聚合查询构建 Doris 物化视图（MV），查询自动改写命中 MV，加速报表与 Dashboard。
- **同步链路**：Flink CDC → Doris Stream Load → 物化视图增量刷新；失败重试与 Exactly-Once 由 Flink checkpoint 保障。

```text
MySQL/Kafka ──CDC──▶ Flink(L2.3) ──Stream Load──▶ Doris 在线表
                                                      │
                                                      ▼
                                               物化视图(MV) ──▶ BI/在线API
```

## 5. 接口契约（REST API，经统一 SQL 网关路由）

```
POST /api/olap/v1/catalogs              { name, type:iceberg, props } → 建 External Catalog
POST /api/olap/v1/databases             { catalog, db }
POST /api/olap/v1/tables                { db, table, schema, engine:doris }
POST /api/olap/v1/materialized-views    { baseTable, mvSql } → 建物化视图
POST /api/olap/v1/load/stream           { table, data } → Stream Load 实时写入
GET  /api/olap/v1/query                 { sql } → 经 L2.7 网关路由到 Doris 执行
```

> 客户不直连 Doris FE，所有查询经 L2.7 统一 SQL 网关统一鉴权、路由、限流；OLAP 路由规则按 SQL 特征（聚合/limit/在线表）判定。

## 6. 关键流程

1. **查询路由**：客户端 SQL → L2.7 网关鉴权 → 判定为 OLAP → 转发 Doris FE。
2. **FE 解析**：FE 解析 SQL → 元数据定位 → 命中物化视图则改写 → 生成执行计划分片。
3. **BE 执行**：BE 并行执行 Shuffle/聚合 → 命中 External Catalog 则下推读 Iceberg → NVMe 缓存加速。
4. **结果返回**：BE 汇聚结果 → FE 回传 → 网关返回客户端。
5. **物化构建**：Flink CDC → Stream Load 写 Doris 表 → MV 增量刷新 → 在线 API 毫秒查询。

## 7. 资源与调优

- **BE 资源**：StatefulSet 每 Pod 独占 NVMe 本地盘（缓存 Iceberg 数据块与 Doris 数据）；CPU/Memory 按 workspace 配额隔离。
- **SKE 加速**：BE 优先使用 NVMe + IO_uring 高 IO；ARM 信创环境使用国产编译优化的 Doris 镜像。
- **Colocate Table**：同分布键的星型表 Colocate Join，避免 Shuffle，加速多表关联报表。
- **物化视图策略**：按查询热度自动建/淘汰 MV；冷查询回退直查 Iceberg，避免 MV 膨胀。

## 8. 多环境适配

| 环境 | Doris 镜像 | 存储/缓存 | 说明 |
| --- | --- | --- | --- |
| 信创 | 国产 ARM 镜像 | 信创对象存储 + NVMe | 信创生态优先，IO_uring 加速 |
| 本地数据中心 | x86/ARM 标准镜像 | Ceph RGW + 本地 NVMe | 裸金属/虚机自建 K8s |
| 公有云 VM | x86 标准镜像 | 客户云对象存储 + 本地 NVMe | **只用裸对象存储，不启用云托管 OLAP** |
| 私有云 | x86/ARM 镜像 | 厂商对象存储 + NVMe | 客户私有云 VM 自建 |

> 关键约束：四环境 Doris 部署形态、SQL、Catalog 完全一致；差异仅在镜像架构与 StorageDriver 后端，保持可迁移、不锁云。

## 9. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| External Catalog 直查性能不稳 | 复杂查询慢 | 高频查询物化落地 Doris；冷查直查 Iceberg |
| 物化视图膨胀 | 存储与刷新成本高 | 按热度自动建/淘汰 MV；过期 MV 归档 |
| BE 单点资源争抢 | 多租户互相影响 | 按 workspace 隔离 BE 资源池 + Workload Group 限流 |
| 实时同步延迟/重复 | 数据不一致 | Flink checkpoint 保 Exactly-Once；Stream Load 失败重试 |
| 信创 ARM 兼容性 | 镜像/算子缺失 | 使用国产验证的 Doris ARM 镜像；缺失能力降级告警 |

## 10. 与 UI 的对应

控制台 v0.3「数据集成」页（Kafka/MySQL→Doris 实时同步任务）、工作台"在线查询 12 ms · 物化视图 38 个"、数据开发（建 Doris 表 / 物化视图 / External Catalog）均基于此引擎。本文件是其 OLAP 侧契约依据。