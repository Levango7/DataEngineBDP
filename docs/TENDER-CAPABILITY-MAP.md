# 招标能力对照表（云原生底座 / 湖仓 / 流 / 批 / 数据分层）

> 版本 v1.2 · 2026-11-14 · MCode（主理人本轮「迎合市场需求…核对 todo…都做完」令）
> **事实源（v1.2）**：`origin/main` @ `0dc8874308fb1c0978baaa248fafd4b6ae6279b4` —— **本轮全部坐标实测于该基线**（能力坐标取数口径见 §5；复测结论分五类：① 未漂移 ② 本表自身笔误 ③ 仓库漂移（他人 PR）④ 因新增文档必须订正的口径 ⑤ 事实已变 ⇒ 行按现状改写；逐条明细见 §3）。**再前移一次（2026-11-14，→ main `0dc88743`）**：该区间含 PR #796「master-data + data-standard 入 umbrella」+ #791「删除未接线的 invoice/payment 死代码链」+ #798「实时链路补齐第一批」+ #799「外部组件镜像 tag 上游核实表」+ #785「主问数链路真实 Provider 首刀」+ #800「台账 #126 登记」。逐条复测结果：① **未漂移** —— charts 顶层 **103/103**、各 chart 文件数、`platform/governance/real-time-pipeline` 41 / `stream-batch-scheduler` 79 / `batch-pipeline` 103 / `industry-templates` 288 / `encaps-layer` 200 / `sql-gateway` 207、`operations-api` 16、`karmada/karmada-api` 21、数据分层 **7 处 / 5 文件**、「性能损耗」在 `platform/` **零命中**；② **本表自身笔误** —— charts「顶层 107 个目录」（实测 8 个基线全为 103/103，`86a4aca8..0dc88743` chart 目录增删各 0；笔误成因＝旧计数命令把 charts 根下 4 个普通文件也算成目录，103 + 4 = 107）、`DataLayer` 行号（`:12` → `:13`，`:12` 是注释行）、「数据分层」逐处枚举漏列 `frontend/src/api/develop.ts:75`；③ **仓库漂移（他人 PR）** —— umbrella **91 → 93 文件 / 86 → 88 个 `.tgz`**（PR #796）、`platform/` 全树 **2664 → 2666**（PR #791 −7、PR #798 +9）、`platform/flink-cdc` **81 → 90 文件**（PR #798）、代码 TODO **166 处 / 19 文件 → 85 处 / 14 文件**（净 −69 处 / −5 文件；PR #790 +12 声明行、PR #791 −81 删除，来源逐笔见 §3「代码 TODO」行）；④ **因本轮新增文档而必须订正的口径** —— 「性能损耗」在 `docs/` 的命中面由 0 变为 **4 文件 / 24 处**，另加 `scripts/baremetal/README.md` **1 文件 / 1 处**，全仓合计 **9 文件 / 33 处**（`platform/` **仍零命中**）；⑤ **事实已变 ⇒ 行按现状改写** —— §2「并入 #119 ⑤」finops 行（5 个 service 类 + 2 model 共 7 文件已由 PR #791 删除，台账 #119⑤ 已 CLOSED）改为「已裁撤」；§2「并入 #119 ⑥」operations-api 行按主理人裁决口径**只写事实、标未核实、不给定性**（详见 §2.2 与 §4 第 6 条）。**基线等价性**：`d9a22452 → 0dc88743` 区间只动 2 个 docs 文件，能力坐标逐项实测零差异（详见 §4 第 9 条）。
> 配套件：`docs/STREAM-BATCH-DECISION-TABLE.md`（流批四层决策表，另一条线产出）、`docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md`（本件配套的损耗实测基线）、`docs/CLOUD-NATIVE-BARE-METAL-ACCEPTANCE.md`（裸金属 / 带外验收清单，PR #795 配套）。
> 缺口编号与 `docs/KNOWN-FAILURES.md` 第六节一一对应（**本轮只新增 #120–#122**；另两项与既有 #119 的 ⑤⑥ 重复，**不另立编号**、并入 #119，见 §2 与 §2.1）；**v1.2 复测未新增编号**，本轮变化登记见 §2.2。

## 0. 口径声明（先立规矩，避免对外承诺跑偏）

| # | 口径 | 内容 |
|---|---|---|
| 0.1 | **流与批分开** | 招标需求是「湖仓 + **流数据处理** + **批数据处理** + 数据分层」四件独立的事。本平台**不把流与批合成一件事**；引擎侧分治（Spark 批 / Flink 流），只有**定义层、调度层、存储层、查询层**统一。依据见 `docs/STREAM-BATCH-DECISION-TABLE.md` §1–§3。 |
| 0.2 | **云原生损耗先量化** | 云原生底座的性能损耗**不可避免但不是无条件接受**：按「可优化 / 结构性代价」分层归因，只对**实测主导项**付优化税。依据见本件配套的 `docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md`。 |
| 0.3 | **成熟度只认实测** | 下表「成熟度」列只填**本机可复现的实测结论**；未实测的一律写「未实测」并进盲区清单，**不写成「已支持」**。 |

## 1. 五轴能力总表

成熟度记号：**A**＝有实现且有实测证据 · **B**＝有实现，无端到端实测 · **C**＝仅设计/骨架 · **D**＝缺。

| 轴 | 能力项 | 仓内落点（实测坐标） | 成熟度 | 缺口 |
|---|---|---|---|---|
| **云原生底座** | K8s 部署面 | `design/deploy/charts/` **顶层 103 个 chart 目录，103 个含 `Chart.yaml`**（实测 `git ls-tree -r 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 design/deploy/charts/`；同一口径在 `4e20c1d2` / `5b3a44c3` / `c81dd6c5` / `beef5572` / `47d8aed9` / `86a4aca8` / `d9a22452` / `0dc88743` **八个基线全为 103/103**，且 `86a4aca8..HEAD` 的 chart 目录**增删各 0** ⇒ v1.1 §1「顶层 107 个」是该表**自身笔误**而非仓库漂移，本轮订正）；umbrella 单目录 **93 文件**（含 **88** 个 `.tgz`） | B | 无集群级端到端实测（四环境见 #9） |
| | GitOps / 弹性 / 网格 | charts 含 `argo-rollouts` / `keda` / `cluster-autoscaler` / `descheduler` / `cni-cilium` / `knative-serving` / `velero` / `cert-manager` / `external-secrets` | B | sidecar 与 Cilium 双重拦截的性能代价**未实测**（#121） |
| | 可观测 | charts 含 `prometheus`(18 文件) / `loki` / `tempo` / `grafana` / `node-problem-detector` / `metrics-server` / `opentelemetry-collector`（`observability` 模块） | B | 指标契约未覆盖「路径 × 档位」维度（#121） |
| | **性能损耗实测基线**（本轮新增行） | `docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md` §6.4「L0 底座档实测（2026-11-12，WSL2 + k3s 单节点）」：k3s v1.37.2+k3s1 / kernel `6.6.87.2-microsoft-standard-WSL2` / cgroup v2 / systemd 托管 / flannel `10.42.0.0/16` MTU 1450；node allocatable CPU **32** / 内存 **15 966 336 Ki** / ephemeral **1 027 046 117 185** / Pods **110**；镜像**冷拉 3 801 ms / 热拉 403 ms**；**Pod create→Running P50 2 297 ms / P95 5 351 ms / P99 5 383 ms**（nearest-rank，N=20 干净复测）；20 Pod 并存 CPU 773m(2%) / 内存 1581Mi(10%) | **A（仅 L0 底座档）** | **只代表 L0**；L1/L2/L3 生产档仍零实测（#124）⇒ 数字**不得**外推为生产档基线 |
| | 裸金属 / 带外验收（本轮新增行） | `scripts/baremetal/`（`ipmi_acceptance.py` 625 行 / `render_user_data.py` 178 行 / `pxe/` 三件 / `cloud-init/user-data.yaml`）+ `docs/CLOUD-NATIVE-BARE-METAL-ACCEPTANCE.md`（88 行）：六条硬判据 C1–C6、**三态判据**（PASS / FAIL / INCONCLUSIVE，拿不到结论不算通过）、密码写盘前掩码、远程写操作只打印命令交人工执行；本机实测：取证器 `--self-test` OK（**23 项断言**）+ **变异校验转红 rc=1**（scheme 白名单恒真 / 删除守卫 两路）+ **`file://` 真实调用被拒 rc=2 且证据 JSON 无本地文件内容**、渲染器 `--self-test` OK（4 项）、`serve-pxe.sh` `bash -n` / `shellcheck` 均 rc=0、四场景 1/0/1/1、端到端 RESULT: OK；**SAST 门禁 blocking finding 已处置**（URL scheme 白名单在发起请求前拦截 + 按仓内惯例加 `# nosemgrep` 抑制；抑制注释 `:117` / `urlopen :118`） | **B（资产就绪，未在真机执行）** | 本机无 BMC ⇒ 带外命令**未在真机跑过**；iPXE **未经真实 PXE / UEFI HTTP Boot 验证**；① 路径「厂商 BMC 是否暴露标准 IPMI/redfish」**未验证**（#121） |
| | 多集群联邦 | **Karmada 控制面已退役**：PR #745「退役 Karmada 控制面（chart + umbrella 休眠依赖 + 从未执行的 E2E 腿）」已并 main；现仓内只剩 `karmada-api` chart（13 文件） | D | 异地机房/多集群联邦**能力已被主动裁撤**（台账 #90 已落裁）；政企招标若要双活多活，**这是硬缺口** |
| **湖仓** | 表格式 / Catalog | `iceberg-rest`(13) / `iceberg-compaction`(11) / `hive-metastore`(13) / `metadata-collector`(13) / `catalog`(14) / `open-api-catalog`(13) | A | 口径统一层缺（#120 同族） |
| | OLAP / 查询 | `doris`(10) / `trino`(10) / `sql-gateway` 平台模块(207 文件) / `query-api` | A | — |
| | 湖仓建模规范 | `design/数据建模/湖仓存储建模规范.md`（28 317 字节，实测） | C | 设计文档，**未见对应的产品化落地件**（#120） |
| **流数据处理** | CDC 摄取 | `platform/flink-cdc`（**90 文件**）+ `design/deploy/charts/flink-cdc`(13)；裁决「**作业提交器**、不常驻」见 `docs/flink-cdc-deployment.md` §二 | A | — |
| | 流式治理闭环 | **`platform/governance/real-time-pipeline`**（**41 文件**；注：`platform/real-time-pipeline` 路径在仓内**不存在**，实测 `git ls-tree -r 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 platform/ \| grep real-time-pipeline`）；`docs/realtime-pipeline-implementation-status.md`：10 个服务类全为真实实现 | B | **SLA「流处理延迟 ≤1s」从未实测**（`docs/SLA-PERF-BASELINE-2026-09-01.md:20` 记为 skip） |
| | 流批同源存储 | `platform/stream-batch-scheduler`：Spark 批读固定 snapshot / Flink 流读最新 snapshot（README §1.2、§6.2） | A | — |
| **批数据处理** | 批流水线 | `platform/batch-pipeline`（103 文件，五阶段）+ chart `batch-pipeline`(10) | B | 无端到端批量吞吐实测 |
| | 批引擎 | chart `spark`(10) / `dolphinscheduler`(10) / `airflow`(13) / `seatunnel`(10) | B | 同上 |
| | 统一编排 | `stream-batch-scheduler` 的 `SparkBatchTaskChannel` / `FlinkStreamTaskChannel` / `BatchPipelineTaskChannel` 三通道（README §3.3） | A | — |
| **数据分层** | 平台运营数仓分层 | `design/数据建模/数据仓库分层建模.md`：ODS→DWD→DWS→ADS 四层范式，Iceberg + Parquet/ZSTD，`PARTITIONED BY (dt, tenant_id)` | C | **该文档第 3–5 行自述「适用于平台运营方…**不**涉及行业模板内租户业务数据集市」** ⇒ 平台自身分层 ≠ 租户侧分层产品 |
| | 租户侧分层能力 | 仓内产品代码里「数据分层」共 **7 处 / 5 文件**（`git grep -c "数据分层" 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 -- platform/ frontend/`），**全部落在 3 类薄壳上**：① `frontend/src/api/governance.ts:13` —— `export type DataLayer = 'ODS' \| 'DWD' \| 'DWS' \| 'ADS' \| 'DIM'`（**唯一一处含 DIM 第五层的前端类型定义**；同文件 `:12` 是其上方注释行，v1.1 写作 `:12` 系行号笔误）；② `platform/encaps-layer/.../DevelopController.java:33,115,116` —— **只按 ods/dwd/dws/ads 推导任务 DAG 依赖**（另 2 处为同一注解的 javadoc 重复）；③ `platform/industry-templates/industry_templates/models/template.py:56` —— 模板的 `layer` 字段。**v1.2 补全**：v1.1 的逐处枚举只覆盖 6 处 / 4 文件，**漏列 `frontend/src/api/develop.ts:75`**（`/** 数据分层（ods/dwd/dws/ads/other，由后端 DAG 解析推导） */`）；另一处为 UI 词条 `frontend/src/i18n/locales/modules/develop.zh-CN.json:38`「任务 DAG（按数据分层自动生成）」。**「7 处 / 5 文件」总数未漂移，漂移的是逐处枚举与行号** | D | **租户侧通用分层（建分层 → 建层内表 → 按层调度 → 分层口径与血缘打通）无产品实现 ⇒ 招标「数据分层的一套平台」最大缺口（#120）** |
| | 分层范式（模板内） | `platform/industry-templates`（288 文件）各行业 chart 的 `ddl/*.sql` 带 ODS/DWD/DWS/ADS | B | 属**行业模板内容**，非平台级分层产品；9 套模板的真实 K8s 部署验证仍缺（五维评审 P-05） |

## 2. 缺口清单（查漏补缺，与台账一一对应）

| 编号 | 缺口 | 性质 | 为什么这是缺口（实测依据） |
|---|---|---|---|
| **#121** | **云原生性能损耗：L0 底座档已实测，L1/L2/L3 生产档仍零实测** | 测度缺口（**已部分收口**） | 「性能损耗」一词在全仓的命中面（实测 `git grep -l "性能损耗" 0dc8874308fb1c0978baaa248fafd4b6ae6279b4`，**9 个文件 / 33 处**）：① `design/v2.0/` **4 文件 / 8 处**（设计层原文）；② `platform/` **零命中**；③ `docs/` **4 文件 / 24 处** —— `docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md`(12) + `docs/KNOWN-FAILURES.md`(4) + `docs/TENDER-CAPABILITY-MAP.md`(5) + `docs/messageboard/board.md`(3)；④ `scripts/baremetal/README.md` **1 文件 / 1 处**，**全部落在测度与登记文档，不是产品实现** ⇒ **损耗的实现始终不存在，但测度与登记已落地**（v1.1 写「`platform/` 与 `docs/` 零命中」，本轮因新增测度文档**必须订正**，不可留旧口径）。**已兑现**：L0 底座档（WSL2 + k3s 单节点）拿到调度冷启动与镜像拉取的真实数字（§1「性能损耗实测基线」行；登记于台账 **#124**）。**未兑现**：`docs/SLA-PERF-BASELINE-2026-09-01.md:60` 明写「cluster 档全量跑…四环境演练时执行」——**至今未跑**；L1/L2/L3 生产档（真实集群 / Flink / Spark / Iceberg / GPU / mesh）**零实测**。 |
| **#124** | **L0 底座档实测基线（#121 的「已兑现部分」，本轮新增行）** | 测度缺口（**已收口，但只到 L0**） | PR #793 合并（merge `ef97768a2fda52d78092588a420b38060c0ffd5e`）：`docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md` §6.4 给出 L0 底座档数字与复现命令（k3s v1.37.2+k3s1；镜像冷拉 3 801 ms / 热拉 403 ms；Pod create→Running P50 2 297 ms / P95 5 351 ms / P99 5 383 ms，N=20 nearest-rank）。**边界**：L0 数字**不得**外推为 L1/L2/L3 生产档基线。 |
| **#121（同项补记，不另立编号）** | **裸金属 / BMC / PXE 面：验收资产已入库，选型待主理人裁决**（本轮新增行） | 交付面缺口 | PR #795 合并后仓内新增 `scripts/baremetal/` 四件套（带外验收取证器 / cloud-init 渲染器 / PXE-over-HTTP / 模板）+ `docs/CLOUD-NATIVE-BARE-METAL-ACCEPTANCE.md` 验收清单（条款 ↔ 四条路径 ↔ 硬判据映射）。**约束判读**：带外管理类条款**只能**由 ① 云上裸金属/BMS 或 ② 有 IPMI 的实体机满足；自动化装机类条款可由 ③ PXE-over-HTTP + iPXE + cloud-init 或 ④ Ansible/cloud-init 满足 ⇒ 条款若只要求「一台 x86_64 真机 + 可重复装机」，则 ③/④ + 一台可远程装机的物理机即可闭环，**不必等 BMC**；若硬性要求标准 IPMI API，则**只有 ①/② 可行**，且 ① 须逐条确认厂商是否暴露标准 IPMI/redfish（**未验证**，不下结论）。**盲区**：本机无 BMC ⇒ 带外命令未在真机执行过，属**待执行资产**而非已通过的验收报告。 |
| **#120** | **租户侧数据分层不是产品能力** | 架构级 / 产品级 | 平台侧只有 4 样薄壳：①`design/数据建模/数据仓库分层建模.md`（ODS→DWD→DWS→ADS 范式，但**该文件 :3-5 自述「适用于平台运营方…不涉及行业模板内租户业务数据集市」**）；②`frontend/src/api/governance.ts:13` 的 `DataLayer` 类型（5 层含 DIM，**只是类型别名**）；③`DevelopController.java:115-116` 按层推导 DAG 依赖；④`platform/industry-templates` 各行业 chart 的 `ddl/*.sql`（模板内容）。**没有任何「租户建分层 → 建层内表 → 按层调度」的通用链路。**招标要「数据分层的一套平台」时，仓内现状只能答「我们有分层范式、类型定义和模板」，答不出「我们有分层产品」。 |
| **#122** | **对外「流批」口径风险** | 承诺风险 | 仓内「流批一体」= 定义层/调度层/存储层/查询层统一（`platform/stream-batch-scheduler/README.md` §1.2、§6.2），**不是引擎一体**。此前无一份文档把这条口径写成裁决，招标/售前若按字面承诺「一套引擎跑流批」会与实现不符。裁决件已由另一条线产出（`docs/STREAM-BATCH-DECISION-TABLE.md`），本行只登记其存在与待裁三项。 |
| **并入 #119 ⑤**<br>**（不另立编号）** | **finops 商业化后半段（invoice / payment 接线 or 删除）：已由 PR #791 裁撤为「删」** | 已裁撤（**v1.1 原依据已失效**） | 本表 v1.1 记「`platform/finops/dashboard` 的 `InvoiceService` / `SplitNotificationService` / `PaymentService` / `PaymentGateway` / `MockPaymentGateway` 五个类无任何 Controller 引用、合计 74 处代码 TODO」——**该依据已随 PR #791（merge `985bed39c954cf1f68c8b88b5ba6e60daa9bba08`，2026-11-12T19:11:41Z）删除而失效**：commit `73fd2cfc` 删掉 **7 个代码文件（823 行）**（`model/Invoice.java` / `model/PaymentRecord.java` + 5 个 service 类）并同步改 `platform/finops/dashboard/README.md` 与台账 #119⑤，**该 commit 共 9 files changed / 5 insertions / 837 deletions**；实测 `git ls-tree -r <ref> platform/finops/dashboard/` 中**已无**上述 7 个类路径，当前该模块 **39 文件**、**代码 TODO 命中 0 处**（仅 `README.md` 2 处）。台账 **#119⑤ 已 CLOSED**（原文：「⑤ invoice/payment 死代码链删除（CLOSED，2026-11-12 裁＝删…）」）；台账 #119⑩ 记的「74 处」是 **2026-11-10 历史快照**，不随删改写。**处置**：按「事实已变 ⇒ 行按现状改写」改为已裁撤，**不再作为待办**；不新立编号、不改口径逻辑（登记见 §2.2）。 |
| **并入 #119 ⑥**<br>**（不另立编号）** | **合同管理（operations-api）实现仍为内存骨架；且「选边已闭环」与「实现已闭环」是两件事（记载冲突项未核实）** | 功能缺口（**只登记事实，不给定性**） | **事实**：`platform/operations-api` 共 **16 文件**；代码 TODO 命中 **37 处**（`operations_api/services/__init__.py` 23 + `operations_api/repositories/__init__.py` 12 + `operations_api/app.py` 2），另有 `operations-api/README.md` 1 处 ⇒ 含 README 口径 **38 处**。**持久化缺口属实现事实、非推测**：部署单元自己的 `design/deploy/charts/operations-api/values.yaml:16-17` 明写「默认 1：合同仓储目前为内存仓库（`operations_api/repositories/__init__.py`），多副本各自持有独立内存态 ⇒ 数据分裂；持久化属产品范围，落地前保持单副本」⇒ 多副本扩容在持久化落地前无意义（同文件 `hpa.enabled: false` 与 chart README §6.3 单副本约束同口径）。**未核实项（本轮不裁定）**：① 台账 **#119⑥ 段称「platform 正版代码不并入该 chart」**（同段并称「代码不合并、路由不共享」），而同一 chart 的 `values.yaml:3-5` 称「本 Chart 为 umbrella 唯一部署单元，部署 `platform/operations-api` 正版镜像；演示态目录仅 CI 编译其 main.py 用」；② chart `README.md` §1 写「安装方式：**独立 `helm install`**（未纳入 `dataenginebdp-umbrella`，见第 8 节）」，而同文件 §8 写「本 Chart **已纳入** `charts/dataenginebdp-umbrella`…`enabled: true` 默认启用」，umbrella `values.yaml` 实测亦为 `operations-api.enabled: true` ⇒ **三处记载互不一致**；③ 台账 #119⑥ 自身已记 **CLOSED（2026-11-10 裁）** ⇒ **选边已闭环 ≠ 实现已闭环**。**处置**：按主理人本轮口径**只写事实、标未核实、不给定性**（不判定台账段与 chart 文档孰对孰错），冲突登记为 §4 未核实项；不新立编号、不改口径逻辑（登记见 §2.2）。 |

### 2.1 与既有台账行的关系（避免重复立行）

- #120 与 #117「数据流『湖仓栈宣称 vs mock 闭环』实证」**同族但不同项**：#117 记的是「已实现模块的运行态缺口」，#120 记的是「**产品能力本身不存在**」。两者并列，不覆盖。
- #121 与 #9（环境验证 1/4）、#106（values 骨架承诺）**不重叠**：#9/#106 是交付面，#121 是性能测度面。
- #122 与 #119（四轴审计收口总账）**不重叠**：#119 是已立项事项的总账，#122 是**对外口径风险**，此前无处登记。
- **finops 商业化后半段（本表原拟 #123）不另立编号**：`#119` 中列 ⑤ 已登记「invoice/payment 接线 or 删除」为待裁决项 ⇒ 同一件事两个编号＝**重复台账**。处置：**并入 #119 ⑤**，本轮在 #119 中补入本表的实测坐标与 74 项计数口径；`#117` 中「P-03 出账/支付待裁项」是同一事项的另一面，由 #119 ⑤ 统一承接。
- **operations-api 选边（本表原拟 #124）不另立编号**：`#119` 中列 ⑥ 已登记「operations-api 选边（platform 正版 vs design 部署副本）」⇒ 同上处置：**并入 #119 ⑥**，补入 35 项计数口径。`design/v2.0/评审报告/评审报告_A_PRD与实施计划.md` 的 P-04 只到「合同实体模型」阶段，这部分信息由 #119 ⑥ 承接，不再另立行。

### 2.2 撤回 / 订正登记（本轮事实变化，**不新增缺口编号**）

| 对象 | v1.1 的记载 | 本轮实测（`origin/main @ 0dc88743`） | 处置 |
|---|---|---|---|
| **finops 商业化后半段（并入 #119 ⑤）** | 「5 个 service 类无 Controller 引用、74 处代码 TODO」列为待裁决项 | PR #791（merge `985bed39c954cf1f68c8b88b5ba6e60daa9bba08`）删 7 个代码文件（823 行）；实测该模块 **39 文件、代码 TODO 0 处**（仅 README 2 处） | **撤回该待办**（事实已变 ⇒ 行按现状改写为已裁撤）；台账 #119⑤ 已 CLOSED，**不新立编号** |
| **operations-api（并入 #119 ⑥）** | 「选边未裁、实现为内存骨架、35 处代码 TODO」 | 代码 TODO 实测 **37 处**（含 README 38）；选边台账已 CLOSED，但 chart 文档三处记载互不一致 | **保留行、改写依据**；冲突列为 §4 未核实项（只写事实、不给定性） |
| **代码 TODO 总量** | 「基线 `86a4aca8` = 154 处 / 19 文件」＋「活口径 166 处 / 19 文件」 | 当前 **85 处 / 14 文件**（三骨架代码口径 73 / 含 README 76；骨架外 9 处 / 5 文件） | **全部改写为当前值**，并写明基线、净差与逐笔来源（§3「代码 TODO」行） |
| **umbrella 单目录** | 「90 文件 / 85 `.tgz`」（v1.1 自报值） | 实测 **93 文件 / 88 `.tgz`**（基线 `86a4aca8` = 91 / 86） | **订正为实测值**；+2 来自 PR #796（他人 PR，本轮只复测） |
| **charts 顶层目录** | 「107 个 chart 目录，其中 103 个含 `Chart.yaml`」 | **8 个基线全为 103 / 103** | **订正为 103 / 103**（107 是本表自身笔误，非仓库漂移） |
| **`platform/` 全树** | 未列 | **2664 → 2666**（-7 来自 PR #791，+9 来自 PR #798） | **新增实测登记**（净 +2） |
| **台账头部计数格** | 「127 项 / 127 行 / 编号至 #124」 | §六 实测 **128 行 / 编号至 #126**；且 **#125 为空洞**（§六 中不存在 #125 行） | **本轮落笔按实测订正计数格**；**不重排他人编号、不填补空洞**（编号重排属他人 lane 的既有条目） |
| **头部「事实源 / 测量基线说明」段** | v1.1 记 `origin/main @ 86a4aca8` 的测量基线链（含 umbrella 91 / 子目录 107 / 性能损耗 4 文件等历史快照） | v1.2 头部改写为「事实源（v1.2）」段，坐标全部重测于 `origin/main @ 0dc88743` | **以 v1.2 事实源段为准**；v1.1 原文可按 `git show <ref>:docs/TENDER-CAPABILITY-MAP.md` 取回（未丢失） |

## 3. 本轮「去莠存良」结果（另见台账变更日志）

| 类别 | 处理 |
|---|---|
| 台账头部**重复计数行**（**v1.1 轮实录**，计数为当时值；现值见 §2.2） | `docs/KNOWN-FAILURES.md:20` 与 `:21` 是两行同名「生产化待决项」计数句（`:20` 写 122 项、`:21` 写 119 项旧行）⇒ **删 `:21`**，并把 `:20` 计数格**压成可复现短句**（**125 项 / 125 行 / 编号至 #122 / 含 1b–1d 三子行 / 附计数命令**）；那约 4000 字的 dated 增量史与「变更日志」节重复，历史留在变更日志节。 |
| **与现实矛盾的行** | #102 末列写「①②③ 均未实施」，但 `.github/workflows/ci.yml:22-27` 实测已是 `push: branches: [main, 'release/**']` + `tags: v*`，`:13-21` 注释明写「2026-11-01（台账 #102①）：此前此处是 branches: ['*']…」⇒ **① 已由 PR #756 落地**；②③ 未实施 ⇒ **订正为「① 已落地、②③ 未实施」**。 |
| **同行内首尾自相矛盾的状态格**（7 行，**可零外部依赖证明**：末列首部写 OPEN 味、尾部已写 CLOSED 结论） | #75（尾「状态：CLOSED（2026-10-22）」）、#78（尾「收口 CLOSED」）、#79（尾「⇒ **CLOSED**」）、#90（尾「本行 CLOSED 结论不变」）、#92（尾「本行 CLOSED」）、#94（尾「本行按冻结豁免收口 CLOSED」）、#95（尾「本行以 N/A 口径收口 CLOSED」）⇒ **逐行订正末列**（只 prepend 订正段，**不改历史叙述**）。另有 4 条旧式叙述（#25/#31/#37/#40 首部「待 CI 核验」但尾已有「获验证／获 CI 验证」）属登记原貌，**不在本轮订正范围**。 |
| **#70 终判已取得**（CI 实跑取证，非推测） | 修复已合入 main：commit `dea33c2f`（2026-10-14，`.github/workflows/quality-gate.yml:68` 取 `head_sha`、`:80` `head_sha: sha`、`:85` 按 `head_sha` 过滤）。**取数判据**：`gh api .../actions/runs?head_sha=<sha>` 对 `workflow_run` 触发的工作流返回不完整（Quality Gate 实测 0 条）⇒ **判 QG 结论必须读 `commits/<sha>/check-runs`**。**作业级实证**：commit `98c70e3859df02b7ec7f190105df8bc9a08a0c5d` 的 check-runs 实测 `total_count = 59`，其中 **Quality Gate \| completed \| failure**（check id `128893306762`）；同 commit 的 CI run `42524446643` = `completed/failure`，27 个 jobs 中 **Helm Lint（job `128864902996`）与 Bash Syntax Check（job `128864903115`）均为 `failure` 且非 `cancelled`** ⇒ 判红来自内容缺陷、非取数错位或作业取消。⇒ #70 订正为 CLOSED。 |
| **留言板 stale 条目**（**v1.1 轮实录**：下文「本轮 / 本批」均指 v1.1 轮） | 基线 `origin/main @ c81dd6c5` 实测 **88 条条目 / 116 行**，其中 **37 条 `in-progress`** / **46 条 `done`** / **5 条 `info`**（较 `4e20c1d2` 的 85 条 / 113 行 / 36 in-progress 多出 2 条 `done`＝Qoder docs 线 #42/#39 销号行、1 条 `in-progress`＝zcode 12:49 活 CLAIM；较 `5b3a44c3` 的 83 条 / 35 条 in-progress 则多出 5 条＝PR #782 带入 2 条 + 销号行 2 条 + 活 CLAIM 1 条）。**stale 销账基数＝36 条**（取自 `4e20c1d2` 板面点测；c81dd6c5 的 37 条 in-progress 中，zcode 那条是**活 CLAIM、非 stale，不在销账面内**）。本轮处理结果：**销账 32 条**（所引 PR 已合入 main，gh 实测 `state=closed merged=True` + merge commit 均为 `origin/main` 祖先）**保留 3 条并加订正**（#116 manifest 5xx 处置 / 主理人 11-07 三项裁决 / 四轴审计 batch1-3）、**删除 2 条字节级完全相同的重复行**（信息量为零）、**新增本轮 CLAIM 行 1 条** ⇒ 改后 **87 条 / 115 行 / in-progress 5 / done 77 / info 5**（基线 88 条 + CLAIM 1 − 删重 2 = 87 条；in-progress：37 − 销账 32 − 删重 1 + CLAIM 1 = 5，其中 1 条为 zcode 活 CLAIM）。保留的 3 条中「四轴审计 batch1-3」所引 **#779 已 MERGED（merge commit `5210ca04`）**，但该行所列「待修/待裁」五项尚无逐条销账证据，故**保留 `in-progress` 不代销账**。**另按板规 L5「按时间倒序排列」复位 6 处时序违例**：违例在 `4e20c1d2` / `be4e5ec5` / 本分支三版实测**同为 6 处** ⇒ **非本轮引入**（本轮只复位排序，行内容逐字未改）。**基线再前移（2026-11-10 末，→ `origin/main @ beef5572`）**：板面基线实测 **89 条 / 117 行 / in-progress 37 / done 47 / info 5**（较 `c81dd6c5` 的 88 条 / 116 行多 1 条 ＝ zcode #783 销号行 `done`）；stale 销账面**不变**（基数 36、销账 32、保留 3 条加订正、删重 2 —— 新增那 1 条是 `done` 不是 stale）⇒ 本轮终态＝**89 条 / 117 行 / in-progress 6 / done 78 / info 5**（基线 89 − 删重 2 + CLAIM 2 行〔首批 16:58、本批 19:09〕＝ 89）。**基线再前移（2026-11-11 00:55Z，→ `origin/main @ 47d8aed9`）**：板面基线实测 **91 条 / 119 行 / in-progress 39 / done 47 / info 5**（与 `85695c06` **逐条一致** —— `47d8aed9` 未动 `docs/messageboard/board.md`；`git diff --numstat 85695c06 47d8aed9` 仅 2 个非 board 文件）；该基线自身实测含 **精确重复 2 行**、**严格时间戳口径时序违例 6 处**（均为 main 既有，本分支销账重做时已消解重复、倒序复位违例，**行内容逐字未改**）⇒ 本批终态＝93 条 / 121 行 / in-progress 9 / done 79 / info 5（89〔beef5572 基线处理后〕+ 收尾销号行 1 行〔20:55〕+ main 的 zcode 活 CLAIM 2 行〔21:09 / 21:46〕+ 本批 CLAIM 行 1 行〔21:55〕＝ 93），**列数异常 0、精确重复 0、严格时间戳口径时序违例 0**；另 **11 行时间戳含 `x` 占位**（main 既有，逐字保留，不参与分钟级排序校验）。 |
| **代码 TODO** | 全仓实测（口径：`platform/ frontend/ scripts/ tests/` + `\b` 词边界 + `HACK`）在 **基线 `86a4aca8` = 154 处 / 19 文件**（三骨架代码口径 142 处 / 含 README 146 处；骨架外 8 处 / 5 文件）——**该基线与本表 v1.1 自报一致**。**当前 `origin/main @ 0dc88743` = 85 处 / 14 文件**（三骨架代码口径 **73 处** / 含 README **76 处**；骨架外 **9 处 / 5 文件**）。**净差 −69 处 / −5 文件**，逐笔来源（每笔都在对应 ref 上实测，非推算）：① **PR #790**（merge `e60d3a7800d077d3c71362921a419e1ea170c3a1`）在 12 个文件加**文件级汇总声明行**（声明文本本身含 1 处 `TODO` 字样）⇒ **+12 处 / 0 文件**（`86a4aca8` 154/19 → `e60d3a78` 166/19；骨架内 finops 74→79、operations-api 35→37、karmada-api 33→36，骨架外 `query-api/internal/handler/ops.go` 8→9 处所在文件 +1）；② **PR #791**（commit `73fd2cfc`）删除 finops 死代码链 **7 个代码文件** ⇒ **-81 处 / -5 文件**（finops 5 个 service 文件整体删除带走 79 处代码 TODO，`finops/dashboard/README.md` 4→2 处；`e60d3a78` 166/19 → `73fd2cfc` 85/14）；③ 其后 **PR #793 / #795 / #798 / #799 / #785 / #800 区间 TODO 计数零漂移**（`6e6e77d8` 与 `0dc88743` 实测同值）。**当前三骨架构成**：karmada-api **36**（代码口径：`client.go` 16 + `cluster.go` 15 + `main.go` 4 + `model/cluster.go` 1）/ operations-api **37**（代码口径，含 README **38**）/ finops-dashboard **0**（代码口径，仅 README 2 处）——**三骨架不再是「142 件待办」而是 3 件已立项项**。⚠️ **口径纪律**：去掉 `\b` 会量到 **96 处 / 23 文件** 的假增长；去掉 `HACK` 在当前 main **无变化**（85 处 / 14 文件，main 已无 `HACK` 命中）⇒ **引用必须带命令原文（见 §5）**。 |
| **§1 charts 坐标笔误订正**（本轮新增） | v1.1 §1 写「`design/deploy/charts/` 顶层 **107** 个 chart 目录，其中 103 个含 `Chart.yaml`」——本轮在**它自己的基线**上用同一命令复测：`4e20c1d2` / `5b3a44c3` / `c81dd6c5` / `beef5572` / `47d8aed9` / `86a4aca8` / `d9a22452` / `0dc88743` **八个基线全部 = 103 目录 / 103 含 `Chart.yaml`**，且 `86a4aca8..HEAD` 的 chart 目录**增删各 0** ⇒ **107 是该表自身笔误，不是仓库漂移**，本轮订正为 103/103（同时 umbrella 由 90 文件 / 85 `.tgz` 更新为 **93 文件 / 88 个 `.tgz`**）。 |
| **§1「数据分层」枚举补全 + 行号订正**（本轮新增） | v1.1 §1 把 `DataLayer` 类型写在 `frontend/src/api/governance.ts:12`——实测 `:12` 是注释行 `/** 数据分层 */`，**类型定义在 `:13`**；且 v1.1 的逐处枚举只覆盖 6 处 / 4 文件，**漏列 `frontend/src/api/develop.ts:75`**。本轮实测全量 **7 处 / 5 文件**并逐处列出（见 §1 行）。**「7 处 / 5 文件」这个总数本身未漂移**，漂移的是枚举与行号。 |
| **§2「性能损耗」命中面口径订正**（本轮新增） | v1.1 写「`platform/` 与 `docs/` **零命中**」——本轮实测 `platform/` **仍零命中**，但 `docs/` 已 **4 文件 / 24 处**（测度与登记文档：性能基线 12 / 台账 4 / 本表 5 / 板面 3），另 `scripts/baremetal/README.md` **1 文件 / 1 处**，全仓合计 **9 文件 / 33 处**（本表自身落笔后该文件计数会变 ⇒ 见 §4 第 7 条）⇒ 订正为「**损耗实现不存在，但测度与登记已落地**」，并把 #121 的性质由「零实测基线」改为「L0 已实测、L1/L2/L3 仍零实测」。 |
| **仓库漂移（他人 PR，本轮只复测不改动）** | `platform/` 全树 **2664 → 2666**（**净 +2**）：① PR #791（merge `985bed39c954cf1f68c8b88b5ba6e60daa9bba08`，2026-11-12T19:11:41Z「删除未接线的 invoice/payment 死代码链」）**-7 个代码文件**（`platform/finops/dashboard` 的 `model/Invoice.java`、`model/PaymentRecord.java` + 5 个 service 类，共 823 行）；② PR #798（merge `10888a9b9c86fd538b9a2a49cafca630eaec0ab3`，2026-11-14T02:26:00Z「实时链路补齐第一批」）**+9 文件**（`platform/flink-cdc` **81 → 90**）。umbrella **91 → 93 文件 / 86 → 88 个 `.tgz`**（PR #796 merge `d1f93f46a78e79121871bcbd62af11d13e1d937c`，2026-11-12T20:20:45Z「master-data + data-standard 入 umbrella」：新增 2 个 `.tgz`、删除 0）。charts 顶层目录**增删各 0**（103 → 103），各 chart 文件数**全部未漂移**（prometheus 18 / karmada-api 13 / iceberg-rest 13 / catalog 14 / trino 10 / spark 10 等）。⇒ **本轮全部坐标已按新基线重测**，本表不保留旧值。 |

## 4. 盲区声明（本表不能替代的东西）

1. **L0 底座档已有真实数字，但 L1/L2/L3 生产档仍为零**。§1 中「成熟度 A」指**实现存在且有可复现的仓内/本机证据**；本轮新增的 L0 底座档数字（WSL2 + k3s 单节点）**只代表 L0**，**不得**外推为生产档基线。`docs/SLA-PERF-BASELINE-2026-09-01.md` 的 15 项 perf 全部是 **local 档单容器栈**（该文件 :3「测试环境：本地 Docker 集成栈」），cluster 档 0 跑。
2. **招标原文未取得**。`web_search` 因订阅余额用尽返回 **402**（不可重试），外部招标站点原文未纳入本表；本表的需求侧来自主理人口述的**五轴**（云原生底座 / 湖仓 / 流数据处理 / 批数据处理 / 数据分层）+ 仓内决策文档。**需求映射若有出入，以真实招标文件为准。**
3. **多集群联邦能力已被裁撤**（PR #745），本表记 D；但「裁撤是否与本轮招标诉求冲突」属主理人裁决项，本表只登记事实。
4. **行业模板 9 套的真实部署验证**仍缺（`design/v2.0/评审报告/评审报告_B_架构设计.md` 记 P-05「无 verify-all-templates.sh、无真实 K8s 部署记录」），本表未把模板的「有 DDL」当作「可交付」。
5. **裸金属带外验收未在真机执行过**。本机无 IPMI/BMC ⇒ `scripts/baremetal/ipmi_acceptance.py` 的带外腿在本机必然 INCONCLUSIVE（脚本把缺失写进证据，不静默放行）；iPXE 脚本**未经真实 PXE / UEFI HTTP Boot 验证**；① 云上裸金属路径「厂商 BMC 是否暴露标准 IPMI/redfish」**未验证** ⇒ 本表把该轴记 **B（资产就绪）**，**不记 A**。
6. **operations-api 的「选边」与「实现」两件事的记载互相冲突，本轮不裁定**。台账 #119⑥ 的归属段写「platform 正版代码不并入该 chart」（同段并称「代码不合并、路由不共享」），同一 chart 的 `values.yaml:3-5` 写「本 Chart 为 umbrella 唯一部署单元，部署 `platform/operations-api` 正版镜像」，chart `README.md` §1 写「独立 `helm install`（未纳入 `dataenginebdp-umbrella`）」而 §8 写「已纳入…`enabled: true`」（umbrella `values.yaml` 实测为 `operations-api.enabled: true`）⇒ **四处记载互不一致**。按主理人本轮口径，本表**只写事实、标未核实、不给定性**，不判定孰对孰错；**「选边已 CLOSED」不等于「实现已闭环」**——持久化缺口由 `values.yaml:16-17` 自述坐实（内存仓库 ⇒ 多副本数据分裂 ⇒ 保持单副本）。
7. **本表引用的「性能损耗」「代码 TODO」「台账 / 板面行数」都是**基线值**，不是活值**。本文所有数字实测于 `origin/main @ 0dc88743`；本 PR 落笔会同时改写台账（追加复核段 + 变更日志行）与板面（新增 CLAIM 行），且**本表自身**落笔后 `docs/` 内的「性能损耗」命中数亦会变化 ⇒ **引用当前值必须用 §5 命令在目标 ref 重测**，不可沿用本文数值。
8. **台账编号存在一个空洞，且头部计数格已过期（均为 main 侧既有状态，非本轮引入）**。实测 `origin/main @ 0dc88743` 的 §六 = **128 行 / 编号至 #126**，而头部计数格仍写「127 项 / 127 行 / 编号至 #124」，且 **#125 在 §六 中不存在** ⇒ 本轮落笔按实测把计数格订正为 **128 项 / 128 行 / 编号至 #126**，但**不重排他人编号、不填补空洞**（编号重排属他人 lane 的既有条目，须由该 lane 或主理人裁决）。
9. **基线前移的等价性已实测，不是推断**。`d9a22452 → 0dc88743` 区间实测只动 **2 个 docs 文件**（`docs/KNOWN-FAILURES.md` +4/−1，含 BOM 移除与 #126 行 + 变更日志行；`docs/messageboard/board.md` +2），**能力坐标逐项实测零差异**（charts 目录数 / umbrella 文件与 `.tgz` 数 / `platform/` 全树 / 各模块文件数 / 各 chart 文件数 / TODO 三骨架与双口径 / 数据分层 / 性能损耗命中面全部相同），**唯一变化是板面留言行 126 → 128** ⇒ 本次重钉基线**不改变任何能力结论**。

## 5. 证据索引（复现命令）

```bash
# 能力落点（图表与模块清单）
git -C F:\Nexus\bdp-tender-wt ls-tree -r --name-only 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 design/deploy/charts/
git -C F:\Nexus\bdp-tender-wt ls-tree -r --name-only 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 platform/
# charts 顶层目录计数（八个基线同口径均为 103/103；86a4aca8..0dc88743 增删各 0）
git -C F:\Nexus\bdp-tender-wt ls-tree -r --name-only 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 design/deploy/charts/ | cut -d/ -f4 | sort -u | wc -l
# 分层能力的产品代码落点（7 处 / 5 文件，全部是薄壳）
git -C F:\Nexus\bdp-tender-wt grep -c "数据分层" 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 -- platform/ frontend/
# 性能损耗的命中面（9 文件 / 33 处：design/v2.0/ 4 文件 8 处 + docs/ 4 文件 24 处 + scripts/ 1 文件 1 处；platform/ 零命中）
git -C F:\Nexus\bdp-tender-wt grep -l "性能损耗" 0dc8874308fb1c0978baaa248fafd4b6ae6279b4
# 代码 TODO 计数（口径：platform/ frontend/ scripts/ tests/ + \b 词边界 + HACK）
git -C F:\Nexus\bdp-tender-wt grep -c -I -E "\b(TODO|FIXME|XXX|HACK)\b" 0dc8874308fb1c0978baaa248fafd4b6ae6279b4 -- platform/ frontend/ scripts/ tests/
# ⇒ 当前 head（0dc88743）实测 85 处 / 14 文件；三骨架 73 处（代码口径）/76（含 README）；骨架之外 9 处 / 5 文件
#   finops-dashboard 0（代码口径；PR #791 已删死代码链，仅 README 2 处）
#   operations-api 37（代码口径；含 README 38）
#   karmada 36（路径 platform/karmada/karmada-api；控制面已退役，勿再按 platform/karmada/api 取数）
# ⚠️ 去掉 \b 会量到 96 处 / 23 文件 的假增长；去掉 HACK 在当前 main 无变化（85 处 / 14 文件，main 已无 HACK 命中）⇒ 引用必须带命令原文
# L0 底座档实测的复现命令见 docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md §九 证据索引
# 裸金属带外验收的复现命令见 scripts/baremetal/README.md 与 docs/CLOUD-NATIVE-BARE-METAL-ACCEPTANCE.md
# 裸金属取证器自测（23 项断言）与 scheme 守卫验证（必须 rc=2 且证据 JSON 不含本地文件内容）
python scripts/baremetal/ipmi_acceptance.py --self-test
python scripts/baremetal/ipmi_acceptance.py --redfish file:///etc/passwd
```
