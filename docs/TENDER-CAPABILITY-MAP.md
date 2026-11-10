# 招标能力对照表（云原生底座 / 湖仓 / 流 / 批 / 数据分层）

> 版本 v1.1 · 2026-11-10 · MCode（主理人本轮「迎合市场需求…核对 todo…都做完」令）
> 事实源：`origin/main` @ `4e20c1d2`（PR #782 销账后）——全部条目为**本机实测**，坐标可复现。
> 测量基线说明：本表最初的代码/图表/文档测量点取自 `c9abcf2f`（PR #777 合入后）。其后 main 前移至 `4e20c1d2`，其中 `c9abcf2f → 5b3a44c3` 一段含 **135 个文件**的代码变更（实测 `git diff --stat c9abcf2f 5b3a44c3` = 1916+/159−，含 `.github/workflows/ci.yml`、charts、frontend、platform）⇒ **本表所有坐标已在 `origin/main @ 4e20c1d2` 上逐条重新复测**，受影响项为：charts umbrella 文件 **89→90**、`.tgz` **84→85**、`platform/stream-batch-scheduler` **76→79** 文件、`platform/real-time-pipeline` 实际路径为 `platform/governance/real-time-pipeline`、`.github/workflows/ci.yml` 行号 **+38**；未受影响项（性能损耗 8 处/4 文件、数据分层 7 处/5 文件、TODO 154 处/19 文件及其行坐标）经 OLD/NEW 对比实测**完全一致**。
> 配套件：`docs/STREAM-BATCH-DECISION-TABLE.md`（流批四层决策表，另一条线产出）、`docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md`（本件配套的损耗实测基线）。
> 缺口编号与 `docs/KNOWN-FAILURES.md` 第六节一一对应（**本轮只新增 #120–#122**；另两项与既有 #119 的 ⑤⑥ 重复，**不另立编号**、并入 #119，见 §2 与 §2.1）。

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
| **云原生底座** | K8s 部署面 | `design/deploy/charts/` **顶层 107 个 chart 目录，其中 103 个含 `Chart.yaml`**（实测 `git ls-tree -r origin/main design/deploy/charts/`）；umbrella 单目录 **90 文件**（含 **85** 个 `.tgz`） | B | 无集群级端到端实测（四环境见 #9） |
| | GitOps / 弹性 / 网格 | charts 含 `argo-rollouts` / `keda` / `cluster-autoscaler` / `descheduler` / `cni-cilium` / `knative-serving` / `velero` / `cert-manager` / `external-secrets` | B | sidecar 与 Cilium 双重拦截的性能代价**未实测**（#121） |
| | 可观测 | charts 含 `prometheus`(18 文件) / `loki` / `tempo` / `grafana` / `node-problem-detector` / `metrics-server` / `opentelemetry-collector`（`observability` 模块） | B | 指标契约未覆盖「路径 × 档位」维度（#121） |
| | 多集群联邦 | **Karmada 控制面已退役**：PR #745「退役 Karmada 控制面（chart + umbrella 休眠依赖 + 从未执行的 E2E 腿）」已并 main；现仓内只剩 `karmada-api` chart（13 文件） | D | 异地机房/多集群联邦**能力已被主动裁撤**（台账 #90 已落裁）；政企招标若要双活多活，**这是硬缺口** |
| **湖仓** | 表格式 / Catalog | `iceberg-rest`(13) / `iceberg-compaction`(11) / `hive-metastore`(13) / `metadata-collector`(13) / `catalog`(14) / `open-api-catalog`(13) | A | 口径统一层缺（#120 同族） |
| | OLAP / 查询 | `doris`(10) / `trino`(10) / `sql-gateway` 平台模块(207 文件) / `query-api` | A | — |
| | 湖仓建模规范 | `design/数据建模/湖仓存储建模规范.md`（28 317 字节，实测） | C | 设计文档，**未见对应的产品化落地件**（#120） |
| **流数据处理** | CDC 摄取 | `platform/flink-cdc`（81 文件）+ `design/deploy/charts/flink-cdc`(13)；裁决「**作业提交器**、不常驻」见 `docs/flink-cdc-deployment.md` §二 | A | — |
| | 流式治理闭环 | **`platform/governance/real-time-pipeline`**（**41 文件**；注：`platform/real-time-pipeline` 路径在仓内**不存在**，实测 `git ls-tree -r origin/main platform/ \| grep real-time-pipeline`）；`docs/realtime-pipeline-implementation-status.md`：10 个服务类全为真实实现 | B | **SLA「流处理延迟 ≤1s」从未实测**（`docs/SLA-PERF-BASELINE-2026-09-01.md:20` 记为 skip） |
| | 流批同源存储 | `platform/stream-batch-scheduler`：Spark 批读固定 snapshot / Flink 流读最新 snapshot（README §1.2、§6.2） | A | — |
| **批数据处理** | 批流水线 | `platform/batch-pipeline`（103 文件，五阶段）+ chart `batch-pipeline`(10) | B | 无端到端批量吞吐实测 |
| | 批引擎 | chart `spark`(10) / `dolphinscheduler`(10) / `airflow`(13) / `seatunnel`(10) | B | 同上 |
| | 统一编排 | `stream-batch-scheduler` 的 `SparkBatchTaskChannel` / `FlinkStreamTaskChannel` / `BatchPipelineTaskChannel` 三通道（README §3.3） | A | — |
| **数据分层** | 平台运营数仓分层 | `design/数据建模/数据仓库分层建模.md`：ODS→DWD→DWS→ADS 四层范式，Iceberg + Parquet/ZSTD，`PARTITIONED BY (dt, tenant_id)` | C | **该文档第 3–5 行自述「适用于平台运营方…**不**涉及行业模板内租户业务数据集市」** ⇒ 平台自身分层 ≠ 租户侧分层产品 |
| | 租户侧分层能力 | 仓内产品代码里「数据分层」共 **7 处 / 5 文件**（`git grep -c "数据分层" origin/main -- platform/ frontend/`），**全部落在 3 类薄壳上**：① `frontend/src/api/governance.ts:12` —— `export type DataLayer = 'ODS' \| 'DWD' \| 'DWS' \| 'ADS' \| 'DIM'`（**唯一一处含 DIM 第五层的前端类型定义**）；② `platform/encaps-layer/.../DevelopController.java:33,115,116` —— **只按 ods/dwd/dws/ads 推导任务 DAG 依赖**（另 2 处为同一注解的 javadoc 重复）；③ `platform/industry-templates/industry_templates/models/template.py:56` —— 模板的 `layer` 字段。其余 1 处是 UI 词条 `frontend/src/i18n/locales/modules/develop.zh-CN.json:38`「任务 DAG（按数据分层自动生成）」 | D | **租户侧通用分层（建分层 → 建层内表 → 按层调度 → 分层口径与血缘打通）无产品实现 ⇒ 招标「数据分层的一套平台」最大缺口（#120）** |
| | 分层范式（模板内） | `platform/industry-templates`（288 文件）各行业 chart 的 `ddl/*.sql` 带 ODS/DWD/DWS/ADS | B | 属**行业模板内容**，非平台级分层产品；9 套模板的真实 K8s 部署验证仍缺（五维评审 P-05） |

## 2. 缺口清单（查漏补缺，与台账一一对应）

| 编号 | 缺口 | 性质 | 为什么这是缺口（实测依据） |
|---|---|---|---|
| **#120** | **租户侧数据分层不是产品能力** | 架构级 / 产品级 | 平台侧只有 4 样薄壳：①`design/数据建模/数据仓库分层建模.md`（ODS→DWD→DWS→ADS 范式，但**该文件 :3-5 自述「适用于平台运营方…不涉及行业模板内租户业务数据集市」**）；②`frontend/src/api/governance.ts:12` 的 `DataLayer` 类型（5 层含 DIM，**只是类型别名**）；③`DevelopController.java:115-116` 按层推导 DAG 依赖；④`platform/industry-templates` 各行业 chart 的 `ddl/*.sql`（模板内容）。**没有任何「租户建分层 → 建层内表 → 按层调度」的通用链路。**招标要「数据分层的一套平台」时，仓内现状只能答「我们有分层范式、类型定义和模板」，答不出「我们有分层产品」。 |
| **#121** | **云原生性能损耗零实测基线** | 测度缺口 | 「性能损耗」一词在全仓仅 **4 个文件**命中，**全部在 `design/v2.0/`**（`V2.0_架构设计文档.md`、`V2.0_PRD_产品需求文档.md`、`评审报告/评审报告_B_架构设计.md`、`评审报告/评审报告_D_数据联邦与行业模板.md`），`platform/` 与 `docs/` **零命中**；`docs/SLA-PERF-BASELINE-2026-09-01.md:60` 明写「cluster 档全量跑…四环境演练时执行」——**至今未跑**。损耗清单停在设计文档，没有一个数字。 |
| **#122** | **对外「流批」口径风险** | 承诺风险 | 仓内「流批一体」= 定义层/调度层/存储层/查询层统一（`platform/stream-batch-scheduler/README.md` §1.2、§6.2），**不是引擎一体**。此前无一份文档把这条口径写成裁决，招标/售前若按字面承诺「一套引擎跑流批」会与实现不符。裁决件已由另一条线产出（`docs/STREAM-BATCH-DECISION-TABLE.md`），本行只登记其存在与待裁三项。 |
| **并入 #119 ⑤**<br>**（不另立编号）** | **finops 商业化后半段未接线** | 功能缺口 | `platform/finops/dashboard` 的 `InvoiceService` / `SplitNotificationService` / `PaymentService` / `PaymentGateway` / `MockPaymentGateway` 五个类**无任何 Controller 引用**（实测：全仓引用仅落在同目录 service 类与 README）；`InvoiceService.java:22-25` 自述「未接线…不可通过任何 HTTP 端点触达」；`PaymentService.java:15-16` 自述「未接线…72 项 TODO 未实现；产品裁决（接线 or 删除）见 `docs/KNOWN-FAILURES.md` **#117** 待裁项」。5 个 service 类合计 **74 项代码 TODO**（另有 `finops/dashboard/README.md` 3 项）。**#119 ⑤ 已登记「invoice/payment 接线 or 删除」为待裁决项 ⇒ 再立新号即重复台账**，本轮只扩充 #119。 |
| **并入 #119 ⑥**<br>**（不另立编号）** | **合同管理（operations-api）为内存骨架** | 功能缺口 | `platform/operations-api` 的 `services/__init__.py`（22 项 TODO）与 `repositories/__init__.py`（11 项 TODO）自述「骨架实现…生产环境应替换为 PostgreSQL/MySQL 实现」，`app.py` 另有 2 项；代码合计 **35 项 TODO**（另有 `operations-api/README.md` 1 项）。**#119 ⑥ 已登记「operations-api 选边（platform 正版 vs design 部署副本）」为待裁决项 ⇒ 同上，并入 #119**。 |

### 2.1 与既有台账行的关系（避免重复立行）

- #120 与 #117「数据流『湖仓栈宣称 vs mock 闭环』实证」**同族但不同项**：#117 记的是「已实现模块的运行态缺口」，#120 记的是「**产品能力本身不存在**」。两者并列，不覆盖。
- #121 与 #9（环境验证 1/4）、#106（values 骨架承诺）**不重叠**：#9/#106 是交付面，#121 是性能测度面。
- #122 与 #119（四轴审计收口总账）**不重叠**：#119 是已立项事项的总账，#122 是**对外口径风险**，此前无处登记。
- **finops 商业化后半段（本表原拟 #123）不另立编号**：`#119` 中列 ⑤ 已登记「invoice/payment 接线 or 删除」为待裁决项 ⇒ 同一件事两个编号＝**重复台账**。处置：**并入 #119 ⑤**，本轮在 #119 中补入本表的实测坐标与 74 项计数口径；`#117` 中「P-03 出账/支付待裁项」是同一事项的另一面，由 #119 ⑤ 统一承接。
- **operations-api 选边（本表原拟 #124）不另立编号**：`#119` 中列 ⑥ 已登记「operations-api 选边（platform 正版 vs design 部署副本）」⇒ 同上处置：**并入 #119 ⑥**，补入 35 项计数口径。`design/v2.0/评审报告/评审报告_A_PRD与实施计划.md` 的 P-04 只到「合同实体模型」阶段，这部分信息由 #119 ⑥ 承接，不再另立行。

## 3. 本轮「去莠存良」结果（另见台账变更日志）

| 类别 | 处理 |
|---|---|
| 台账头部**重复计数行** | `docs/KNOWN-FAILURES.md:20` 与 `:21` 是两行同名「生产化待决项」计数句（`:20` 写 122 项、`:21` 写 119 项旧行）⇒ **删 `:21`**，并把 `:20` 计数格**压成可复现短句**（**125 项 / 125 行 / 编号至 #122 / 含 1b–1d 三子行 / 附计数命令**）；那约 4000 字的 dated 增量史与「变更日志」节重复，历史留在变更日志节。 |
| **与现实矛盾的行** | #102 末列写「①②③ 均未实施」，但 `.github/workflows/ci.yml:22-27` 实测已是 `push: branches: [main, 'release/**']` + `tags: v*`，`:13-21` 注释明写「2026-11-01（台账 #102①）：此前此处是 branches: ['*']…」⇒ **① 已由 PR #756 落地**；②③ 未实施 ⇒ **订正为「① 已落地、②③ 未实施」**。 |
| **同行内首尾自相矛盾的状态格**（7 行，**可零外部依赖证明**：末列首部写 OPEN 味、尾部已写 CLOSED 结论） | #75（尾「状态：CLOSED（2026-10-22）」）、#78（尾「收口 CLOSED」）、#79（尾「⇒ **CLOSED**」）、#90（尾「本行 CLOSED 结论不变」）、#92（尾「本行 CLOSED」）、#94（尾「本行按冻结豁免收口 CLOSED」）、#95（尾「本行以 N/A 口径收口 CLOSED」）⇒ **逐行订正末列**（只 prepend 订正段，**不改历史叙述**）。另有 4 条旧式叙述（#25/#31/#37/#40 首部「待 CI 核验」但尾已有「获验证／获 CI 验证」）属登记原貌，**不在本轮订正范围**。 |
| **#70 终判已取得**（CI 实跑取证，非推测） | 修复已合入 main：commit `dea33c2f`（2026-10-14，`.github/workflows/quality-gate.yml:68` 取 `head_sha`、`:80` `head_sha: sha`、`:85` 按 `head_sha` 过滤）。**取数判据**：`gh api .../actions/runs?head_sha=<sha>` 对 `workflow_run` 触发的工作流返回不完整（Quality Gate 实测 0 条）⇒ **判 QG 结论必须读 `commits/<sha>/check-runs`**。**作业级实证**：commit `98c70e3859df02b7ec7f190105df8bc9a08a0c5d` 的 check-runs 实测 `total_count = 59`，其中 **Quality Gate \| completed \| failure**（check id `128893306762`）；同 commit 的 CI run `42524446643` = `completed/failure`，27 个 jobs 中 **Helm Lint（job `128864902996`）与 Bash Syntax Check（job `128864903115`）均为 `failure` 且非 `cancelled`** ⇒ 判红来自内容缺陷、非取数错位或作业取消。⇒ #70 订正为 CLOSED。 |
| **留言板 stale 条目** | 基线 `origin/main @ 4e20c1d2` 实测 **85 条条目 / 113 行**，其中 **36 条 `in-progress`**（较 `5b3a44c3` 的 83 条 / 35 条 in-progress 多出 2 条，均为 PR #782 带入：1 条 `done` + 1 条 `CLAIM` `in-progress`）。本轮处理结果：**销账 32 条**（所引 PR 已合入 main，gh 实测 `state=closed merged=True` + merge commit 均为 `origin/main` 祖先）**保留 3 条并加订正**（#116 manifest 5xx 处置 / 主理人 11-07 三项裁决 / 四轴审计 batch1-3）、**删除 2 条字节级完全相同的重复行**（信息量为零）、**新增本轮 CLAIM 行 1 条** ⇒ 改后 **84 条 / in-progress 3 条**（销账 32 + 保留 3 − 删重 2 + CLAIM 1）。保留的 3 条中「四轴审计 batch1-3」所引 **#779 已 MERGED（merge commit `5210ca04`）**，但该行所列「待修/待裁」五项尚无逐项销账证据，故**保留 `in-progress` 不代销账**。 |
| **代码 TODO** | 全仓实测（口径：`platform/ frontend/ scripts/ tests/` + 代码扩展名）**基线钉 `origin/main @ 4e20c1d2` = 154 处 / 19 文件**，其中 **142 处（92%）**集中在 3 个**未接线或已退役的骨架模块**（finops-dashboard 5 个 service 类 **74** / operations-api 3 个文件 **35** / karmada-api 4 个文件 **33**）⇒ 逐条散列的 TODO 不是 142 件待办，是 **3 件**；本轮把这 3 件**收编为台账 #119 的待裁决项**（⑤⑥ + 新增的 karmada 条目），代码侧在 **12 个文件**加**文件级汇总声明**（说明本文件 N 处 TODO 属未接线骨架／冻结裁决、逐条清单见 `docs/KNOWN-FAILURES.md` #119、本文件不代表在研工作），**逐条 TODO 文本一条不删**（信息不丢）。⚠️ **双口径**（声明文本本身含 1 处 `TODO` 字样，故合并后计数必然上移）：**合并后活口径 = 166 处 / 19 文件 / 三骨架 152 处**，与基线 **154 处 / 19 文件 / 三骨架 142 处** 的差额**恰为 12 处声明行**，文件数不变（19）——引用时必须写明「基线钉 commit + 合并后活口径 + 精确差额」，不可留单一口径。 |

## 4. 盲区声明（本表不能替代的东西）

1. **没有任何真实集群上的性能数字**。本表所有「成熟度 A」指**实现存在且有可复现的仓内/本机证据**，不含集群级端到端实测。`docs/SLA-PERF-BASELINE-2026-09-01.md` 的 15 项 perf 全部是 **local 档单容器栈**（该文件 :3「测试环境：本地 Docker 集成栈」），cluster 档 0 跑。
2. **招标原文未取得**。本轮 `web_search` 因订阅余额用尽返回 **402**（不可重试），外部招标站点原文未纳入本表；本表的需求侧来自主理人本轮口述的**五轴**（云原生底座 / 湖仓 / 流数据处理 / 批数据处理 / 数据分层）+ 仓内决策文档。**需求映射若有出入，以真实招标文件为准。**
3. **多集群联邦能力已被裁撤**（PR #745），本表记 D；但「裁撤是否与本轮招标诉求冲突」属主理人裁决项，本表只登记事实。
4. **行业模板 9 套的真实部署验证**仍缺（`design/v2.0/评审报告/评审报告_B_架构设计.md` 记 P-05「无 verify-all-templates.sh、无真实 K8s 部署记录」），本表未把模板的「有 DDL」当作「可交付」。

## 5. 证据索引（复现命令）

```bash
# 能力落点（图表与模块清单）
git -C F:\Nexus\dataengineBDP ls-tree -r --name-only origin/main design/deploy/charts/
git -C F:\Nexus\dataengineBDP ls-tree -r --name-only origin/main platform/
# 分层能力的产品代码落点（7 处 / 5 文件，全部是薄壳）
git -C F:\Nexus\dataengineBDP grep -c "数据分层" origin/main -- platform/ frontend/
# 性能损耗的文档层命中面（4 文件全在 design/v2.0/）
git -C F:\Nexus\dataengineBDP grep -l "性能损耗" origin/main
# 代码 TODO 计数（口径：platform/ frontend/ scripts/ tests/ + 代码扩展名）
git -C F:\Nexus\dataengineBDP grep -c -I -E "\b(TODO|FIXME|XXX|HACK)\b" origin/main -- platform/ frontend/ scripts/ tests/
# ⇒ 基线钉 origin/main @ 4e20c1d2 实测 154 处 / 19 文件；其中 3 个骨架占 142 处（92%）
#   finops-dashboard 5 个 service 类 74 ＝ InvoiceService 26 + SplitNotificationService 26
#                              + PaymentService 14 + PaymentGateway 4 + MockPaymentGateway 4
#   operations-api 35 ＝ services/__init__.py 22 + repositories/__init__.py 11 + app.py 2
#   karmada-api 33    ＝ karmadaclient/client.go 15 + handler/cluster.go 14 + main.go 3 + model/cluster.go 1
# 另有 README 4 处（finops/dashboard/README.md 3 + operations-api/README.md 1）与散列 8 处
# 合并本 PR 后活口径 = 166 处 / 19 文件 / 三骨架 152 处（差额恰为 12 处文件级声明行）
# 引用时必须写「基线钉 commit + 活口径 + 精确差额」，不可留单一口径
```
