# 实时链路补齐专项（立项件，2026-11-13 提交待裁）

> 归属：多平台多租户大数据平台 · 实时链路
> 状态：**立项件已提交，待主理人裁**（2026-11-13；本件只做只读取证与方案设计，**未改任何代码**）
> 缘起：八域能力对照（`docs/TENDER-CAPABILITY-MAP.md` 与 2026-11-08 三路盘点 FINAL-MATRIX）判定的**唯一硬缺口**——实时计算链路「CDC→入湖→物化→查询」存在三处断链，「分钟级客流/分钟级看板」类标此前不可承诺。
> 关联：台账 **#119①**（MV 管理面宿主待裁；本件汇证、不预设裁决）、**#141**（真实存储泳道：Phase 1 已落地；Phase 2 连接件矩阵与本专项同域）、`docs/STREAM-BATCH-DECISION-TABLE.md`（对外口径「定义一体、执行分治」）、`docs/realtime-pipeline-implementation-status.md`（**实时治理链**现状——与本件的**实时计算链**不是同一条链，勿混引）、`docs/flink-cdc-deployment.md`（CDC 作业提交器形态裁决）。

## 一、目标

把「CDC 采集 → 湖仓落地 → 物化刷新 → 查询」补成**有真数据、可端到端验收**的一条链，使「分钟级」能力从"可演示片段"升级为"可承诺交付"，并为招标应答提供可复现证据。

## 二、现状盘查（2026-11-13 只读取证，每条带 file:line）

### 2.1 已真实实现（可直接复用）

| 环节 | 证据 | 状态 |
|---|---|---|
| CDC 采集→Kafka | `platform/flink-cdc/src/main/java/com/levango7/dataenginebdp/flinkcdc/CdcFramework.java:225-238`（KafkaSink builder、AT_LEAST_ONCE、JSON 序列化） | 真实 |
| CDC 作业形态 | 作业提交器（非常驻）+ 两模式（即时/CronJob 定时）——2026-11-07 裁决，`docs/flink-cdc-deployment.md` | 真实 |
| 实时治理链（元数据/血缘/质量/告警） | `platform/governance/real-time-pipeline`（41 文件、含主类 `RealTimeGovernanceApplication`、`VERIFICATION.md` 治理闭环 P95≤10s 预算）；`docs/realtime-pipeline-implementation-status.md`（2026-09-02 复核：全部真实实现） | 真实 |
| SQL 物化重写 | `platform/sql-gateway/.../rewrite/QueryRewriter.java` + `MaterializedViewRepository.java` + `MaterializedViewDefinition.java` | 真实 |
| Doris 物化刷新 | `DorisMaterializedViewIntegration`（2026-11-08 批 4：真实委托 `POST /api/<db>/<mv>/_refresh`、不可达如实 false）+ `BatchPipelineOrchestrationService:160` 直连路径 | 真实 |
| Iceberg Sink 连接器骨架 | `platform/flink-cdc/.../sink/IcebergSinkConnector.java`（1096 行：Catalog/Distribution/Write/Compaction/SchemaEvolution/Upsert 六族枚举 + V2 equality-delete 映射 + Builder + 反射桥 `Class.forName("org.apache.iceberg.flink.sink.FlinkSink")`） | **骨架完备、未接线** |

### 2.2 三处断链（本专项要补的对象）

**断链 ①：CDC→Iceberg 入湖未实现。**

- `CdcFramework.attachIcebergSink()`（`:240-249`）仅为 `log.warn("Iceberg Sink 尚未实现，配置将被忽略（流不接入 Sink）")`；
- `IcebergSinkConnector` **无生产调用方**（模块级 grep 仅自引用）——1096 行的连接器是"装配完毕但没插电"；
- 反射桥现状：即使 `FlinkSink` 类可加载，`createIcebergSinkViaReflection()`（`:507-520`）**仍返回 Stub**（源码注释自述「编译期不依赖 Iceberg SDK…生产环境替换为真实 FlinkSink 调用」）；
- Stub writer（`:1051` 起）= **本地 WAL 缓冲**（JSON 行追加 `data/cdc-wal/`，5.2 修复「静默丢弃」）——保证不丢，但**不写 Iceberg**。

**断链 ②：CDC→Doris 入湖未实现。**

- `attachDorisSink()`（`:251-260`）同为 log.warn 占位；全仓无 DorisSink 实现类（grep 零命中）。

**断链 ③：物化视图管理面无运行宿主（＝台账 #119① 待裁项）。**

- `MaterializedViewController`（`.../flinkcdc/materializedview/controller/`，`@RequestMapping("/api/materialized-views")`，7 条路由：POST / GET 列表 / GET{name} / PUT{name} / DELETE{name} / POST{name}/refresh / GET{name}/status）——但 **flink-cdc 全模块无 `SpringBootApplication`**（grep 零命中）⇒ 无进程加载它；
- APISIX 已生成路由 `"flink-cdc:8080"`（`design/deploy/charts/apisix/templates/configmap-routes.yaml:21`）指向**不存在的服务端口**（既有取证 #119①，2026-11-07）⇒ 管理面不可达。

### 2.3 链路示意（文字版）

```
MySQL ──CDC(Flink,真)→ Kafka(真) ──┬─→ [断①] Iceberg（连接器未接线；写入为 WAL 占位）
                                   └─→ [断②] Doris（无实现）
Iceberg / Doris 表 ──→ 物化（sql-gateway 重写真；Doris MV 刷新真）──→ 查询（sql-gateway，真）
管理面：MaterializedViewController 7 路由 ──[断③ 无宿主]──→ APISIX 路由悬空
```

## 三、补齐方案（分阶段，逐阶段可独立裁）

### Phase A：CDC→Iceberg 入湖接线（首选，改动面最小）

- **A1（推荐）**：接线 `attachIcebergSink()` → `IcebergSinkConnector.createSink()`，并把反射桥从"返回 Stub"改为**真调用** `FlinkSink.forRowData(...).table(...).append()`（Iceberg 官方 Flink connector，运行时由 Flink 集群 lib 提供——连接器注释即按此设计）；WAL 保留为**崩溃缓冲**（现有语义不浪费）。
  - 改动面：`CdcFramework` 接线 + 反射桥真实化 + pom 增 `iceberg-flink-runtime`（provided 域）；连接器本体与既有单测基本"白捡"。
  - 依赖：Flink 集群 lib 含 iceberg-flink-runtime（文档化）；Iceberg REST Catalog 可达（real-time-pipeline 已有 client 先例）。
- **A2（备选，不建议）**：自研 REST 写入——重造写入语义、丢 compaction 能力。

验收判据：单测（接线 + 反射桥真实路径断言）；端到端 = CDC 灌一行 → Iceberg 表可查（含重启后读回）。

### Phase B：CDC→Doris 入湖

- **B1（推荐）**：Doris Stream Load（HTTP API）自研轻量 sink——Stream Load 是稳定 HTTP 面，与现网 `DorisAdapterImpl` 调用风格一致，依赖面最小；
- **B2**：引入 Flink Doris Connector（重依赖，版本矩阵待评）。

验收判据：CDC 一行 → Doris 表 SELECT 可见。

### Phase C：物化视图管理面宿主（**＝ #119①，本件不预设裁决**）

三选项与代价：

- **C1**：flink-cdc 补最小 SpringBoot 入口（仅管理面，不带 CDC 作业进程）——新进程、新部署单元（走 chart/umbrella 流程）；
- **C2**：将 Controller 移交已宿主服务（operations-api / sql-gateway——报文面/鉴权面需对齐）；
- **C3**：摘除悬空 APISIX 路由（诚实降级为文档化/配置面）。

**建议**：按"最短投产生效"择 C1 或 C2；C3 会抹掉"可演示管理面"，售前场景不建议。

### Phase D：端到端验收与对外口径

- 链路：MySQL → CDC → Iceberg →（Doris MV/物化重写）→ sql-gateway 查询；
- 判据：**数据可见延迟 P95 ≤ 60s**（与既有分钟级口径对齐；对外统一表述为"数据可见 ≤1 分钟"，并与 `STREAM-BATCH-DECISION-TABLE.md` 的 L1–L4 口径合并引用）；
- 承载：**不塞 PR 必检**——按 #141 泳道范式（nightly/dispatch 专用 workflow）；CI 成本口径见台账 #102。

## 四、验证与资源边界（如实）

- 本机（无 Docker、无集群，且设备可能随时重启）：本件为**设计先行**；实施阶段可做单测 / 反射桥静态核 / 生成物断言；**端到端须 CI 泳道（#141 先例已证 compose 最小栈可行）或真机**；
- Flink / Iceberg / Doris 三相**版本矩阵需在实施前先钉版**（Phase A/B 各一张版本表）；
- 不改变对外既有诚实口径：在 Phase A–C 全部落地并端到端实证前，"分钟级客流"类标仍按现状（CDC 采集真 + 60s 物化真）应答。

## 五、风险与反模式（先写在案）

- **反射桥脆弱性**：`Class.forName` 失败即 `IllegalStateException`——建议 A1 实施时增"启动期依赖探针"（fail-fast 前移）；
- **scope 蔓延**：本专项**不含** Flink/Kafka/Trino/IoTDB 全连接件矩阵（那是 #141 Phase 2），只补 ①②③ 三断链；
- **不碰现网部署引用**：新进程/新部署单元须走 chart/umbrella 流程（与 #119⑥ 同口径）；
- 反模式：把 Kafka 已真当成"链路已通"对外宣称（Kafka 只是中途站，入湖未落地 ⇒ 查询端无数据）。

## 六、待裁项（交主理人）

1. **是否立项**（本件转"执行中"）；
2. Phase A 路线：**A1** / A2（推荐 A1）；
3. Phase B 路线：**B1** / B2（推荐 B1）；
4. **#119① 三选项 C1 / C2 / C3**（本件汇证，裁决权归 #119①）；
5. 排期：独立排期，或并入 #141 Phase 2 泳道。

## 七、证据索引

- `platform/flink-cdc/src/main/java/com/levango7/dataenginebdp/flinkcdc/CdcFramework.java`（Kafka 真 :225-238；Iceberg/Doris 占位 :240-260）
- `platform/flink-cdc/src/main/java/com/levango7/dataenginebdp/flinkcdc/sink/IcebergSinkConnector.java`（反射桥 :507-520；Stub/WAL :1016-1075）
- `platform/flink-cdc/src/main/java/com/levango7/dataenginebdp/flinkcdc/materializedview/controller/MaterializedViewController.java`（7 路由 :58-237；宿主缺失＝全模块无 `SpringBootApplication`）
- `design/deploy/charts/apisix/templates/configmap-routes.yaml:21`（`flink-cdc:8080` 悬空路由）
- `platform/governance/real-time-pipeline/`（治理链，41 文件 + `VERIFICATION.md`）
- `docs/realtime-pipeline-implementation-status.md`、`docs/STREAM-BATCH-DECISION-TABLE.md`、`docs/flink-cdc-deployment.md`、台账 #119① / #141 / #102
