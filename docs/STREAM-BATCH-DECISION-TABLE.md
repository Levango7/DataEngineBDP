# BDP 数据面流批一体决策表（四层）

> 版本 v1.0 · 2026-11-09 · Qoder（docs/nightly 线）
> 裁决链：主理人 2026-11-08 裁决「流批一体＝作业定义层」⇒ 本表把该裁决落成 BDP 数据面的四层决策表。
> 一句话：**定义一体、执行分治、损耗先量化、按场景选档**；流批一体是好架构当且仅当它落在定义层，执行层按档分治。

## 1. 四层总览

| 层 | 裁决 | BDP 落点（已存在 / 本表延伸） | 判据 |
|---|---|---|---|
| L1 定义一体 | 作业定义、口径、血缘、正确性契约只定义一次，不绑引擎 | 资产目录 `stream_batch=true` 标注、OpenLineage 统一血缘、rule-engine 规则单一事实源、`/execute tableToSource` 契约（#774 已合 main）、metric-contract.yaml | 改引擎不改口径 |
| L2 执行分治 | 三条执行路径按档分工，调度层装配 | 流=Flink CDC（L2.12）；批=Spark + batch-pipeline 五阶段；查=sql-gateway / federated-query + BiViewRouter + Doris MV；DolphinScheduler DAG 统一编排（T035） | 没有引擎在两个极端同时最优 |
| L3 损耗先量化 | 云原生损耗分层归因，先测量后优化 | SLA 基线 local/cluster 分档、每作业声明 latencyTier + overheadBudget、metrics.json → Prometheus | 优化只打实测主导项 |
| L4 场景选档 | 查询时选档已有（BiViewRouter `latencyRequirementMs`）；本表把选档前置到作业定义时 | §5 场景决策表 | 档位标在定义里，部署按档选径 |

## 2. L1 定义一体：统一什么

作业定义四要素（单份声明，引擎不属于其中）：

1. source / sink（哪张 Iceberg 表、哪个 Kafka topic）
2. transform + 口径（rule-engine 规则模板 / SQL 口径）
3. 正确性契约（exactly-once / snapshot 隔离 / 高水位增量）
4. 档位标签（latencyTier + throughputTier + overheadBudget）

BDP 已有事实（不必新建）：

- 批流同读同一张 Iceberg 表：Spark 批读固定 snapshot、Flink 流读最新 snapshot，snapshot 隔离保证同源同结果（T035 README §1.2/§6.2；REQ-RT-002 验收①）。
- 流任务 checkpoint 与批任务 snapshot 不冲突（REQ-RT-002 验收③）。
- 血缘两路归并：离线 SQL 解析 v1 + CDC 实时解析 v2 → 同一血缘图（架构文档 §3.4 端点对照）。
- 质量规则单一事实源在 rule-engine，batch-pipeline 的 8 类规则是其批量执行方言（M4 归一）。

## 3. L2 执行分治：三条路径

| 路径 | 组件 | 优化目标 | 不擅长 |
|---|---|---|---|
| 流路径 | flink-cdc / Flink（T035 `FLINK_STREAM` 通道） | 低延迟连续处理、CDC 增量捕获、mini-batch 合并同主键变更（100ms/1000 行）降 Iceberg commit 频次 | 大范围回填、全量重算 |
| 批路径 | Spark（T035 `SPARK_BATCH` 通道）+ batch-pipeline（`BATCH_PIPELINE` 通道，五阶段，高水位 / snapshot-diff 增量） | 吞吐、列存扫描、全量与回填 | 秒级延迟 |
| 查路径 | sql-gateway / federated-query + BiViewRouter + Doris 物化视图 | 交互延迟（SLA：SQL P95 ≤3s / P99 ≤5s） | 持续计算 |

装配：DolphinScheduler DAG 三类 TaskChannel（T035 §3.3），同一 DAG 编排流批混合（REQ-RT-002 验收②）。
选型规则：连续增量 → 流路径；有界大数据量 → 批路径；交互问答 → 查路径；BI 视图由 BiViewRouter 按 `latencyRequirementMs` 在批快照视图与流最新视图间选（T035 §4.3）。

## 4. L3 损耗量化：云原生开销分解

铁律：每作业在定义层声明 latencyTier + overheadBudget；上线先跑损耗分解，优化只打实测主导项；不为不存在的瓶颈付优化税。

| 损耗项 | 成因 | 测量点 | 达标线 / 基线 | 优化杠杆 | 性质 |
|---|---|---|---|---|---|
| 调度冷启动 | K8s pod 创建 + 镜像拉取，批作业突发敏感 | DAG 节点排队→起跑时长 | 按档阈值 | 常驻 executor、镜像预拉、snapshot-diff 增量免全量重跑 | 可优化 |
| 网络 shuffle | 节点 overlay + 跨节点交换 | 各 stage shuffle 字节 / 时长 | 相对本地基线无数量级退化 | 列存 + 压缩、局部性调度 | 可优化 |
| 远程存储读写 | 对象存储（MinIO）vs 本地盘 | 读放大 / 延迟直方图 | snapshot-diff 增量 IO ∝ 新增行数 | parquet 列裁剪、本地缓存层 | 可优化 |
| 序列化 | JSON 往返 | 编解码 CPU 占比 | — | Arrow / Parquet 内部格式 | 可优化 |
| state / checkpoint | Flink state backend I/O + 对齐 | checkpoint 时长 / 失败率 | 与批 snapshot 不冲突（REQ-RT-002 ③） | 增量 checkpoint、state TTL、mini-batch 降 commit 频次 | 可优化 |
| 编排轮询 | `BATCH_PIPELINE` 通道 REST 轮询（默认间隔 2s / 总超时 3600s，T035 §3.4） | 状态回收延迟 | 按档容忍 | 轮询间隔按档配 | 可优化 |
| 容器隔离 | cgroup / 命名空间 | — | 弹性与隔离的交换 | 无 | 结构性代价，认领不优化 |

基线锚：`docs/SLA-PERF-BASELINE-2026-09-01.md`——SLA local 档 6/6 通过；「流处理延迟 ≤1s」是 cluster 档项，本地栈无 Flink 集群合理 skip，是当前最大测度缺口。

## 5. L4 场景选档：决策表

档位：**L0** 秒级实时（端到端 P95 ≤5s，L2.12 入仓锚）· **L1** 分钟级近实时 · **L2** 小时级 / 日内 · **L3** T+1 离线。

| 场景 | 档 | 正确性契约 | 路径 | 损耗预算重点 | 杠杆 | 验收锚 |
|---|---|---|---|---|---|---|
| CDC 实时入仓（MySQL/Oracle/Postgres → Iceberg） | L0 | exactly-once + V2 upsert | 流 | commit 频次、端到端 P95 | Kafka 削峰 + mini-batch | 架构 §3.2 链路说明（P95 ≤5s / 治理闭环 ≤10s） |
| 实时大屏 / 监控 | L0/L1 | 流最新视图 | 流 + Doris MV | MV 刷新延迟 | 物化视图自动刷新 | REQ-RT-002 ④ |
| nl2sql 问数（产品主链路） | 按 `latencyRequirementMs` | 批快照 or 流最新 | 查（sql-gateway）；查流批一体表时视图选择走 BiViewRouter | 查询 P95/P99 | 路由选视图 + MV 命中 | SLA #2/#3（P95 ≤3s / P99 ≤5s） |
| 离线报表 / 即席分析 | L2/L3 | 批固定 snapshot（一致性优先） | 批 / 查 | 全量扫描 IO | 列存裁剪、snapshot 复用 | REQ-RT-002 ①（批流同源同结果） |
| 数据质量·批量规则 | L2 | 批次台账 + 坏行隔离 | 批（batch-pipeline，v1 同步端点） | validate 阶段时长 | 高水位增量 | batch-pipeline README |
| 数据质量·流式规则 | L0/L1 | 流式订阅 | 流（v2 `/quality/stream`） | 规则求值延迟 | 规则类型按配置自动路由 | 架构 §L3.3 端点对照 |
| 血缘解析 | 离线 + 实时 | 两路归并同一血缘图 | 批（v1 SQL 解析）+ 流（v2 CDC 解析） | 解析吞吐 | — | 架构 §L3.4、#774 tableToSource |
| 回填 / 重算 | L3 | upsert 幂等 | 批 | 全量 IO | snapshot-diff 增量 | batch-pipeline 增量模式 |
| 运维巡检 / 报告（C 线 SOP 复用 metric-contract） | L3 | 批次 | 批 | 报告生成时长 | 定时调度 | 00-执行计划 C1a |

## 6. 接入点（本表不动代码，只登记决策）

- 查询时选档已存在：BiViewRouter `queryMode=AUTO` + `latencyRequirementMs`（T035 §4.3）——L4 在查询侧的落点。
- 定义时选档是本表延伸：作业定义带 latencyTier / overheadBudget，调度装配按档选通道；数据摄入登记补 tier 字段时直接复用 §5 档位枚举。
- 指标侧：metric-contract.yaml 增列「路径 × 档位」观测项之前，先按 §4 测量点跑一轮基线；SLA #9（流处理延迟 ≤1s）的 cluster 档实测是当前缺口。

## 7. 反模式（不做什么）

- 不强制单引擎跑全部（流引擎跑批 = 吞吐税；批引擎跑流 = 延迟税）。
- 不为不存在的瓶颈付优化税（先 §4 分解，后优化）。
- 不把弹性 / 隔离的结构性代价当 bug 修（认领，不优化）。
- 不在定义层写死引擎（L1 无引擎字段；换引擎不改口径）。

## 8. 待主理人裁

1. 数据摄入登记是否本批补 latencyTier 字段（纯字段，但碰 REST 契约）。
2. SLA #9 流处理延迟 ≤1s 是否排进 cluster 档演练（需 Flink 集群资源）。
3. 损耗分解进 nightly 守门还是仅入 SOP（C 线 D15 运维年费 SOP 复用 metric-contract）。

## 附：证据索引

- `platform/stream-batch-scheduler/README.md` §1.2/§3.3/§3.4/§4.3/§6.2（T035 通道、轮询配置、视图路由、数据流图）
- `design/v2.0/V2.0_架构设计文档.md` §3.2.3（L2.13）、实时入仓链路说明（mini-batch、P95 ≤5s）、L2.12/L3.3/L3.4 对照、ADR-003
- `design/v2.0/V2.0_PRD_产品需求文档.md` REQ-RT-002 验收标准五条
- `platform/batch-pipeline/README.md`（五阶段、engine/storage 后端、增量模式、M4 归一）
- `docs/SLA-PERF-BASELINE-2026-09-01.md`（SLA 6/6 local 档、cluster 档目标、perf 分档）
- `platform/observability/metric-contract.yaml`（指标契约单一事实源）
- `docs/SQL-EXECUTE-LINEAGE-CONTRACT.md`（/execute 血缘契约，#774 已合 main）
