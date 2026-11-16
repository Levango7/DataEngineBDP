# 实时链路补齐专项（立项件，2026-11-13 提交待裁）

> 归属：多平台多租户大数据平台 · 实时链路
> 状态：**执行中（第一批 A1a/B1/C1 已实施，2026-11-13；主理人「全按推荐」授权）**
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

### Phase A：CDC→Iceberg 入湖接线（首选，改动面最小）——**A1a 已实施（2026-11-13）；终段待泳道**

- **A1（推荐）**：接线 `attachIcebergSink()` → `IcebergSinkConnector.createSink()`，并把反射桥从"返回 Stub"改为**真调用** `FlinkSink.forRowData(...).table(...).append()`（Iceberg 官方 Flink connector，运行时由 Flink 集群 lib 提供——连接器注释即按此设计）；WAL 保留为**崩溃缓冲**（现有语义不浪费）。
  - 改动面：`CdcFramework` 接线 + 反射桥真实化 + pom 增 `iceberg-flink-runtime`（provided 域）；连接器本体与既有单测基本"白捡"。
  - 依赖：Flink 集群 lib 含 iceberg-flink-runtime（文档化）；Iceberg REST Catalog 可达（real-time-pipeline 已有 client 先例）。
- **A2（备选，不建议）**：自研 REST 写入——重造写入语义、丢 compaction 能力。

验收判据：单测（接线 + 反射桥真实路径断言）；端到端 = CDC 灌一行 → Iceberg 表可查（含重启后读回）。

### Phase B：CDC→Doris 入湖——**B1 已实施（2026-11-13，含单测）**

- **B1（推荐）**：Doris Stream Load（HTTP API）自研轻量 sink——Stream Load 是稳定 HTTP 面，与现网 `DorisAdapterImpl` 调用风格一致，依赖面最小；
- **B2**：引入 Flink Doris Connector（重依赖，版本矩阵待评）。

验收判据：CDC 一行 → Doris 表 SELECT 可见。

### Phase C：物化视图管理面宿主——**C1 已实施（2026-11-13，含安全链与单测）**

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

> **裁决结果（2026-11-13）：全按推荐。** 立项✅ / A1✅ / B1✅ / C1✅（C 段取 C1）/ 独立推进。
> **第一批实施明细（同日）**：① A1a＝`CdcFramework.attachIcebergSink` 由 log.warn 占位改为真实接线
> `IcebergSinkConnector.attachTo(stream)`（属性映射 14 键 + 必填 fail-fast；写入落点＝连接器 WAL 缓冲，
> 不丢数据）；**A1 终段**（反射桥真调用 `FlinkSink/IcebergSink`、真实 Iceberg SDK 写入）需
> iceberg-flink-runtime 进集群 lib ＋ 集群端到端，留待 nightly 泳道/真机。② B1＝新增
> `DorisStreamLoadSink`（Doris Stream Load HTTP 批写、AT_LEAST_ONCE、失败抛异常交作业重试）
> ＋ `attachDorisSink` 接线，含 HttpServer 模拟单测。③ C1＝新增
> `MaterializedViewManagementApplication`（最小 Spring Boot 入口，只承载管理面；自定义安全过滤链：
> 健康端点 `/api/v1/health`（common-security `JwtAuthFilter` 白名单口径）放行、`/api/**` 走 JWT；
> `DorisHttpSqlExecutor` 提供刷新 SQL 执行通道）＋ chart `command/args` 透传支持（探针默认路径
> 同步改 `/api/v1/health`），含上下文/安全面单测。
> **未改共享模块**：common-security 因 `@ConditionalOnMissingBean(SecurityFilterChain)` 自动退让。

## 七、证据索引

- `platform/flink-cdc/src/main/java/com/levango7/dataenginebdp/flinkcdc/CdcFramework.java`（Kafka 真 :225-238；Iceberg/Doris 占位 :240-260）
- `platform/flink-cdc/src/main/java/com/levango7/dataenginebdp/flinkcdc/sink/IcebergSinkConnector.java`（反射桥 :507-520；Stub/WAL :1016-1075）
- `platform/flink-cdc/src/main/java/com/levango7/dataenginebdp/flinkcdc/materializedview/controller/MaterializedViewController.java`（7 路由 :58-237；宿主缺失＝全模块无 `SpringBootApplication`）
- `design/deploy/charts/apisix/templates/configmap-routes.yaml:21`（`flink-cdc:8080` 悬空路由）
- `platform/governance/real-time-pipeline/`（治理链，41 文件 + `VERIFICATION.md`）
- `docs/realtime-pipeline-implementation-status.md`、`docs/STREAM-BATCH-DECISION-TABLE.md`、`docs/flink-cdc-deployment.md`、台账 #119① / #141 / #102

## Phase A 端到端泳道实测（2026-11-15，本机 WSL 无 Docker 泳道）

**环境形态**（不违反「本机不启 Docker」约束）：WSL Ubuntu-24.04 直装栈——PostgreSQL 16（apt，wal_level=logical，端口 5433）＋ flink-cdc 作业进程（`CdcFrameworkMain --config`，LocalStreamEnvironment，flink-connector-postgres-cdc:3.6.0-1.20 直连 pgoutput，不经 Kafka/Debezium Connect）＋ Iceberg HadoopCatalog（`file://` warehouse）。**无需 Kafka**：source 内反序列化器直接产 `ChangeRecord`（Debezium Struct）。

**判据与口径**：Phase D「CDC 灌一行 → Iceberg 表可查，P95 ≤ 60s」；测量＝**首现口径**（每行 `clock_timestamp()` 注入时刻 → 该行在 iceberg 表中首次被读回的时刻；轮询 JVM 每 2 秒 dump 一次，采样分辨率约 2-3 秒）。

**结果**（两轮，`mv_sales_lane` 表，checkpoint 间隔 5s）：

- 第 1 轮 n=25 行（1s 间隔）：min=2.6s，p50=7.8s，**p95=11.3s**，max=13.3s。
- 第 2 轮 n=50 行（0.5s 间隔，注入速率×2）：min=4.8s，p50=11.8s，**p95=20.1s**，max=21.8s。

两轮 P95 均 ≤ 60s（余量 3-5 倍）；snapshot 全量 50 行 + 增量 125 行全部落表（账实相符）。**口径声明：本机为共享开发机，性能数据为下界；真实集群形态（standalone/REST catalog/泳道 workflow）由后续批次升级，Doris 腿按 RAM 余量分期。**

**泳道抓出并修复的两个真缺陷**（修复随本批 PR）：

1. **PostgreSQL/Oracle source 在 CdcFramework 主链不可达**：`addSourceToEnv` 硬编码 `MySqlSourceConnector.createSource`，PG/Oracle 配置实际落到 MySQL 连接器（`SourceConnectorFactory` 完备但未被调用——接线完整性审计形态）。修复＝改走工厂按类型分派。
2. **timestamptz 解析墙**：`ChangeRecordRowDataMapper` 的 TIMESTAMP 分支用 `ISO_LOCAL_DATE_TIME`，撞 pgoutput 实际产出形态 `2026-11-14T23:35:48.719472Z` 的尾随 `Z`（`unparsed text found at index 26`，writer 侧重启循环、数据全部丢失）。修复＝带时区指示符走 `OffsetDateTime`（withZone 字段转 UTC epoch millis、无 zone 字段转墙上时间），epoch millis 优先路径不变。

**泳道基建事实**（复跑配方）：作业进程须 systemd 托管（`systemd-run --unit=bdp-lane-job`，wsl.exe 会话销毁会带走 nohup/setsid 后台进程）；Debezium 自动建 `FOR ALL TABLES` publication 需 superuser 角色；classpath 需在 cp.txt 前置 kafka-clients 3.9.2（kafka-clients 4.x 移除了 Debezium 1.9 依赖的 `Sanitizer`）；Java 17 运行 JVM 需与 surefire 同款 add-opens 全集。脚本与配置在仓库外 `F:/tmp/bdp-tender/lane/`（setup/restart/poll/inject/burst/measure 六件）。

### 泳道 CI 化收官（2026-11-15~16，PR #808/#811/#812）

本机配方收编进仓（`tests/integration/realtime-lane/` 七件）并升格为 CI 泳道
（`it-realtime-lane` workflow：postgres:16 service container，触发＝nightly
22:30 UTC 与 dispatch，不进 PR 必检）。CI 首跑连红 6 轮，经「::error::
annotation 与退出码编码」通道逐轮定位并拔除六墙：

1. 冷 .m2 缺仓内私有模块 common-security ⇒ 聚合 reactor install（`-pl flink-cdc -am -DskipTests install`）后再单模块 build-classpath；
2. service container 的 PG 默认 wal_level=replica ⇒ 启动参数补 `wal_level=logical`（Caused by 直接点名「logical decoding requires wal_level >= logical」）；
3. burst 的 java classpath 缺当前目录 ⇒ `-cp "$DIR:$LANE_CP"`（Could not find main class LaneTools）；
4. service container 的 command 不接受 flow 数组 ⇒ block 字符串形式（dispatch 422）；
5. run 块默认 bash -e 吞掉失败后续诊断 ⇒ `|| RC=$?` 续行；
6. 本机到 results-receiver 的日志下载 TLS 超时 ⇒ 失败信息经 `::error::` annotation（单行）与退出码编码（23=dump 报错 / 24=数据未至 / 25=行数不足）带回。

**CI 泳道 run `43702634630` 全绿**（P95 ≤ 60s 硬断言通过）。诊断循环的教训
（Actions runner 与开发环境的六处差异）已随配方固化在 workflow 与脚本内。

### Phase B 端到端泳道实测（2026-11-16，Doris 腿收官）

**环境形态**（WSL 直装、不启 Docker）：Doris **4.1.5** 单节点（FE + BE，官方 OSS
accelerate 直下 4.32GB 全量包）＋ Postgres 16 逻辑复制（既有泳道）＋
`CdcFrameworkMain` 作业（postgres source → DorisStreamLoadSink）。

**判据与结果**：Phase B「CDC 一行 → Doris 表 SELECT 可见」——**两轮
n=25 / n=50 ⇒ p95=7.0s / 6.8s**（min 2.1s、p50≈4.7s、8 倍以上余量；口径＝
注入时刻 → mysql SELECT 首现时刻）。snapshot 全量 175 行 + 增量 75 行账实相符。

**本批抓出并修复的 sink 真缺陷（三处，端到端实测缺一不可）**：

1. **行形态包裹式**：直接序列化整个 ChangeRecord（{op, before, after}），Doris 按
   列名匹配收不到 after 内业务列（嵌套不打平）⇒ 修为打平（c/r/u 取 after、d 取
   before 并附 `__DORIS_DELETE_SIGN__=1`）；
2. **307 重定向**：Doris 4.x FE 对 /_stream_load 返回 307（转发到 BE webserver），
   JDK HttpClient 默认不跟随 ⇒ 请求空转；且**自动跟随会按 authority（host:port）
   剥离 Authorization** ⇒ BE 报 [NOT_AUTHORIZED]——最终实现为**手动重发并显式带
   认证**（followRedirects=NEVER）；
3. **100-continue 硬要求**：FE 对缺 `Expect: 100-continue` 的请求直接判 FAILED；
   Expect 是 JDK 受限头不可 `.header()` 直设（IllegalArgumentException）⇒ 用
   HttpRequest.Builder 内置 `expectContinue(true)`（线上实发 100-Continue）。

**Doris 4.x 部署与协议坑（复跑配方）**：BE 强制 swap 关闭（`swapoff -a`，WSL
运行时即可）＋ `vm.max_map_count=2000000` ＋ nofile 提升；**`__DORIS_DELETE_SIGN__`
是 hidden column，禁显式建列**（建表带了会被拒），导入键本身被接受（0 值无害已
实测）；curl 手工验证需 `-L --location-trusted`；BE mem_limit 压到 3G、FE 堆 2G，
单节点峰值约 4-5G RAM。

**删除端到端（2026-11-17 补测，PASS）**：`MERGE + DELETE ON` 主键条件路径实测闭环——
注入 10 行（首现 ≤2s 轮询）→ PG DELETE 5 行 → Doris SELECT 3 轮内归零。四轮实证链
（每轮一个真因）：①4.x 普通导入忽略 `__DORIS_DELETE_SIGN__` 并整行 upsert 成 NULL
版本；②`DELETE ON` 仅 `merge_type: MERGE` 支持（DELETE 类型直发报错）；③sink 旧拼接
`String.valueOf(null)`="null" 使条件带 `='null'` 撞类型转换（too many filtered rows）；
④pgoutput 的 DELETE before 非主键列为 null 且 src_ts 为 epoch 占位值（全列 AND 永不命中）
⇒ **删除条件与 body 只用主键键集**（yaml `sink.primaryKey` 配置驱动；未配置 fail-fast
指路，不静默丢删）。

**边界如实**：本机为共享开发机（性能数据为下界）；Doris 2.1.x 的 100-continue/307/
删除语义未测（本批以 4.1.5 实测为准）；CI 泳道暂只覆盖 Iceberg 腿（Doris 腿的 CI 化
属后续段）。
