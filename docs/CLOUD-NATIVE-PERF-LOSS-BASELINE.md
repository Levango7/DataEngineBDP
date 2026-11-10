# 云原生性能损耗基线与场景适配边界

> 最后更新: 2026-11-10 | 口径: 定义一体、执行分治、损耗先量化、按场景选档
> 配套件: `docs/TENDER-CAPABILITY-MAP.md`（五轴能力总表）、`docs/STREAM-BATCH-DECISION-TABLE.md`（四层决策表，定义/执行/量化/选档）
> 坐标基线: `origin/main` @ `be4e5ec5`（PR #782 销账后 + #42/#39 销号后）。`.github/workflows/ci.yml` 在 `c9abcf2f → 5b3a44c3` 区间由 3064 行增至 3102 行（**行号 +38**）⇒ 本文所有 `ci.yml` 行坐标已在 `be4e5ec5` 上重新复测；`quality-gate.yml`、`run_benchmark.py`、SLA 基线文档、perf 测试坐标经 OLD/NEW 对比**未变**。

## 〇、口径声明（先读这一节，否则下面的数字会被误读）

1. **损耗先量化**：本文只把**有实测坐标或可复现入口**的损耗写成条目。凡是只有设计稿阈值、没有本机或 CI 实测的，一律标注「设计阈值 / 未实测」，**不写成性能结论**。
2. **执行分治**：流用 Flink、批用 Spark，**引擎不合并**。本文的损耗按「流侧执行路径」与「批侧执行路径」**分开计**，不求一个统一的「平台性能损耗率」。
3. **成熟度只认实测**：设计文档里的百分比是**风险阈值**（评审时用来要求「必须压测」），不是「已经损耗了这么多」。
4. **场景选档**：同一套云原生底座在不同场景下的合理损耗**不一样**。本文给出档位边界；跨档位承诺一个统一数字，是本文明确反对的做法。
5. 本文是**基线与复现入口**，不是性能承诺书。真实集群档（cluster）的数字必须在有集群的环境里跑出来，本机跑不出（见 §八 盲区声明）。

---

## 一、结论摘要（一页读完）

| 结论 | 证据 | 成熟度 |
|------|------|--------|
| 「性能损耗」这个词全仓只有 **8 处命中、分布在 4 个文件**，**全部在 `design/v2.0/`**（设计层）；`platform/` 与 `docs/` 下**零命中** ⇒ 损耗分析**没有任何产品落点** | `git grep -n -E "性能损耗" origin/main`（复现命令见 §九） | 实测 |
| 仓内现存的全部量化损耗数字都来自**设计稿的风险阈值**，共 4 类：sidecar 资源开销、mesh 吞吐损耗、国密损耗、Istio×Cilium 双重拦截 | `design/v2.0/V2.0_架构设计文档.md:1223`、`:1296`、`:1300`；`design/v2.0/V2.0_PRD_产品需求文档.md:622`、`:625`；`design/v2.0/V2.0_实施计划.md:515`、`:518`；`design/v2.0/评审报告/评审报告_B_架构设计.md:102` | 设计阈值（未实测） |
| 唯一落地的性能实测是 **local 档**：SLA **6/6 通过 + 4 项合理 skip**、perf **15/15 通过**；**cluster 档至今 0 跑** | `docs/SLA-PERF-BASELINE-2026-09-01.md:3`（环境＝本地 Docker 集成栈）、`:6`、`:20`、`:21`、`:58-61`（§四 开放项） | 实测（local 档） |
| 流批执行路径**本来就是分开的**，仓内不存在「流批一体=引擎一体」的实现 ⇒ 本轴**不需要推翻代码**，只需要把对外口径钉死 | `platform/stream-batch-scheduler/README.md:53-58`（三条独立 TaskChannel + 工厂）、`:23-25`（Spark 3.5.0 批 / Flink 1.18.0 流 / Iceberg 1.5.0）、`:12`、`:264-267`（快照隔离数据流图） | 实测 |
| CI 的 `Performance Test` job **PR 上根本不触发**（`if:` = schedule \|\| workflow_dispatch \|\| push 到 main），且即便触发也是 **`continue-on-error: true`（失败不阻断）**，其基准脚本对不可达服务**退化为「基于源码分析的理论性能估计」** ⇒ **CI 绿灯不能直接当实测性能证据** | `.github/workflows/ci.yml:2727`（job 键 `performance-test`）、`:2728`（name）、`:2735-2738`（`if:` 条件——PR 不触发）、`:2740`（`continue-on-error: true`）、`:2759`（跑 `run_benchmark.py --fail-on-sla-violation`）；`tests/performance/run_benchmark.py:3-6`（脚本自述「对不可达服务,基于源码分析给出理论性能估计」）、`:41-43`（默认探测的是集群内网 IP `10.43.246.140`） | 实测 |

**给招标口径的一句话**：可以承诺的是「**按场景选档 + 每个档位有复现入口**」，**不能**承诺「统一性能损耗率」。原因不是实现差，而是**当前没有任何一份经过真实集群验证的损耗数字**（§八）。

---

## 二、损耗点分解（流侧 / 批侧分开计）

下表是**损耗点清单**，不是损耗率清单。每一行的「现有对策」列写的是**仓内已经有的东西**；「已有实测数字」列如实写「无」。

| # | 损耗点 | 归属 | 流侧（Flink）表现 | 批侧（Spark）表现 | 仓内现有对策 | 已有实测数字 |
|---|--------|------|------------------|------------------|----------------|-------------|
| L1 | 调度冷启动 / 作业提交 | 两侧 | CDC 提交器**非常驻**，每次投递都要起 Pod + 拉镜像 + `flink run` ⇒ 冷启动是**常态路径** | 批作业同理，一次性 Pod 起停 | `docs/flink-cdc-deployment.md:4`（裁决：作业提交器）、`:14`（§二 裁决）、`:19`（常驻 Pod 空转计费 + 双重资源管理）、`:24`（结论）、`:40`（CronJob/Job 适配低峰窗口） | 无 |
| L2 | 镜像拉取与缓存 | 两侧 | 同上；提交器镜像 = JRE + flink-clients + job jar | 同上 | `docs/flink-cdc-deployment.md:44`（提交器镜像构成）；CI 侧 `scripts/pick-base-registry.sh` 选源 + `scripts/build-retry.sh` 兜底换源（CI 实跑证据见 `docs/KNOWN-FAILURES.md` #88 行） | CI 侧有（镜像源选择与换源兜底），**Pod 冷启动耗时无** |
| L3 | 网络 shuffle / 跨节点传输 | 两侧 | Flink 的网络 shuffle（跨 TaskManager） | Spark 的 shuffle（跨 Executor） | `design/deploy/charts/` 下有 spark/flink 相关 chart；**无 shuffle 量化配置记录** | 无 |
| L4 | 远程存储 / 列存扫描 | 两侧 | 流侧写 Iceberg（V2 upsert ⇒ 每次产生新 snapshot） | 批侧读**固定 snapshot**、走列存扫描 | `platform/stream-batch-scheduler/README.md:12`（snapshot 隔离）、`:23-25`、`:250`（T015 场景） | 无 |
| L5 | 序列化 / 反序列化 | 两侧 | Flink 自身算子链序列化 | Spark 阶段间 shuffle 序列化 | 无专门设计记录 | 无 |
| L6 | state / checkpoint | **流侧独有** | **Checkpoint 间隔 60s 与「实时入仓 ≤10s」目标自相矛盾**：实际延迟 = CDC(1-2s) + 处理(2-3s) + 等待 Checkpoint(0-60s) + 写 Iceberg(2-3s) + 可见(1s) = **6-69s** | 不适用 | `design/v2.0/评审报告/评审报告_D_数据联邦与行业模板.md:54`（问题）、`:56`（延迟算式）、`:57`（建议：interval 改 10s/5s，或 SLA 改为 P99 ≤ 30s） | **无**（该矛盾是评审发现，至今未实测复核） |
| L7 | 编排轮询与状态收敛 | 两侧 | 提交器与 Flink 集群之间的状态同步 | DolphinScheduler / Airflow 轮询 DAG 状态 | `platform/stream-batch-scheduler/README.md:1.2` 四项能力；chart `dolphinscheduler` / `airflow` / `seatunnel` 在册 | 无 |
| L8 | 容器隔离与 sidecar 资源开销 | 两侧 | 每 Pod **+100m CPU / 128Mi 内存**（Istio sidecar） | 同 | `design/v2.0/V2.0_架构设计文档.md:1223`（ADR-001 后果栏①：每 Pod +100m CPU/128Mi 内存，通过选择性注入缓解） | **无**（设计阈值，非实测） |
| L9 | 网络拦截层叠加 | 两侧 | Istio sidecar（iptables 重定向 outbound）**叠加** V1.0 Cilium socketLB（Pod outbound 拦截）⇒ 双重拦截 | 同 | `design/v2.0/V2.0_架构设计文档.md:1223`（后果栏②：双重拦截可能导致流量异常或性能损耗；协同策略 `hostNamespaceOnly=true`）、`:1300`（AR-009）、`design/v2.0/评审报告/评审报告_B_架构设计.md:102`（评审意见：ADR-001 未评估 Istio 与 Cilium 兼容性） | **无** |
| L10 | 加解密 / 国密模块 | 两侧 | 热点路径上的国密加解密开销 | 同 | `design/v2.0/V2.0_PRD_产品需求文档.md:625`（R-TECH-007 国密损耗 ≥30%）、`design/v2.0/V2.0_架构设计文档.md:1296`（AR-005：硬件加速 + 算法分离 + 压测 + 关键路径国际算法兜底）、`design/v2.0/V2.0_实施计划.md:518` | **无**（设计阈值） |
| L11 | 质量规则窗口聚合延迟 | **流侧独有** | 窗口聚合类规则延迟 = **窗口长度 + 处理延迟**，1 小时窗口直接远超「质量校验延迟 < 10s」目标；行级断言才能秒级 | 不适用 | `design/v2.0/评审报告/评审报告_D_数据联邦与行业模板.md:86`（问题）、`:87`（建议：规则延迟分级） | **无** |

**读法**：L1/L2 是**两侧共有且可优化**的部署形状损耗；L3–L5 是**引擎内部**损耗，靠参数与数据布局调；L6/L11 是**流侧固有语义**损耗（等 checkpoint、等窗口），属于**结构性代价，要认不要藏**；L8/L9/L10 是**云原生/合规引入**的损耗，是本轴招标场景下最需要被追问的部分，而它们**一个都没有实测数字**。

---

## 三、流侧（流数据处理）损耗要点

1. **提交器形态 = 冷启动常态**。CDC 的部署形态已被裁决为「作业提交器（非常驻服务）」（`docs/flink-cdc-deployment.md:4`、`:14`、`:24`）。裁决理由里直接点了云原生成本账：常驻 Pod 空转计费，且与 Flink 集群形成「双重资源管理」（`:19`）。**代价就是**：每次投递都要付一次冷启动。真实作业长驻 Flink 集群，checkpoint / exactly-once 由集群承担（`:20`）。
2. **Checkpoint 是流侧最大的语义损耗**。L6 那条 6-69s 的算式来自评审报告，**至今没有被实测复核过**——这正是「实时入仓 ≤10s」在流批分开架构下最容易被误承诺的地方。
3. **流侧窗口聚合的延迟不可压到 10s 以下**，除非放弃窗口语义（L11）。
4. **流批一致性靠 snapshot 隔离，不靠引擎合并**：批读固定 snapshot、流读最新 snapshot（`platform/stream-batch-scheduler/README.md:12`、`:264-267`）。这条是流批分开的**正确性红利**，与性能损耗无关，但决定了「流批一体」的正确落点是定义层。

---

## 四、批侧（批数据处理）损耗要点

1. **批侧的优势是吞吐与列存扫描**，不是延迟。批读固定 snapshot 意味着批任务**不受流写入影响**（`platform/stream-batch-scheduler/README.md:12`、`:238`）。
2. **批侧损耗主要在 L1/L2/L3**：起停、镜像、shuffle。批侧有成熟的开源参数手册可查，但**本仓没有任何一份 Spark 参数基线**（无 shuffle 大小、无并发数、无分区数记录）。
3. **编排轮询（L7）**：批侧有 DolphinScheduler 3.2.0 / Airflow 两套 chart 在册，轮询间隔与状态收敛延迟**无记录**。

---

## 五、场景选档：同一套底座，合理损耗不同

档位定义如下。**每档给出「该档必须实测什么」，这才是本文可承诺的部分**。

| 档 | 场景 | 允许的损耗形态 | 必须实测的项 | 当前状态 |
|----|------|----------------|--------------|----------|
| **L0 本地/开发** | 单机 docker compose、编码自测 | 全部放开；用 local 档宽松阈值防数量级退化 | `tests/integration/perf/` 的 local 档 15 项 | ✅ 已有基线（`docs/SLA-PERF-BASELINE-2026-09-01.md` §二） |
| **L1 集成/CI** | PR 门禁、集成泳道 | 只保「门禁可信」，不保性能达标 | 门禁类断言（如 Quality Gate 判据脚本自测） | ✅ 已有（`scripts/quality-gate-verdict.py --self-test` 14 例） |
| **L2 生产** | 多副本 + 资源保障 | 允许 sidecar 开销、允许 checkpoint 等待；**不允许**用 local 阈值承诺 | SLA 10 项中本地能测的 6 项 + perf 15 项按 **cluster 阈值**跑 | ❌ **cluster 档 0 跑**（`docs/SLA-PERF-BASELINE-2026-09-01.md:58-61` 明列为开放项） |
| **L3 规模/信创** | 多集群、国密合规、GPU | 额外叠加 L8/L9/L10 三项云原生/合规损耗 | 上面全部 + mesh 双重拦截实测 + 国密 P99 + 窗口规则延迟分级 | ❌ **无任何实测**；且多集群联邦方向已随 Karmada 控制面退役而裁撤 |

**边界声明**：L3 里的「多集群」在当前树里**已被裁撤**（Karmada 控制面 chart 已退役，仓内只剩 `karmada-api` 一张 chart）⇒ 若招标要求多集群，本仓**当前无实现**，不应在应答里承诺。

---

## 六、实测基线现状与复现入口

### 6.1 已有基线（唯一一份）

`docs/SLA-PERF-BASELINE-2026-09-01.md`：

- 环境：**本地 Docker 集成栈（`tests/integration/docker-compose.yml`）**（`:3`）——⚠️ **该行自述「10 服务」与实测不符**：本机在 `origin/main @ be4e5ec5` 上解析该 compose 的顶层 `services:` 段（缩进 2 的服务键）实测 **31 个服务**（`encaps-layer`…`jaeger`）。本文件**不静默改写 SLA 文档原貌**（原文行是他人已入库的实测记录），此处显式登记该差异 ⇒ **「10 服务」是过时自述**，引用环境规模时以 **31** 为准。
- 结论：SLA **6/6 通过**（4 项合理 skip）、perf **15/15 通过（local 档）**（`:6`）
- 4 项 skip 的原因：AI 推理（无真实模型）、微调（无 GPU）、**流处理延迟（无 Flink 集群）**、治理吞吐（无治理服务）（`:20`、`:21`）
- 开放项：**cluster 档全量跑**（多副本集群 + 资源保障）待四环境演练时执行（`:58-61`）

### 6.2 复现入口表（每项「跑什么 → 在哪取数 → 现在什么状态」）

| 目标 | 复现命令 | 取数位置 | 当前状态 |
|------|---------|---------|---------|
| local 档 SLA 10 项 | `PERF_TIER=local pytest tests/integration/perf/` | `tests/integration/perf/test_sla_verification.py`（`PERF_TIER` 语义见 `tests/integration/perf/conftest.py:166`） | ✅ 有基线（2026-09-01） |
| local 档 perf 15 项 | 同上（`test_performance_benchmark.py`） | 同目录；local 档阈值见 `test_performance_benchmark.py:328` | ✅ 有基线 |
| CI 性能压测 | CI job `Performance Test (性能压测)`（`.github/workflows/ci.yml:2727` job 键 / `:2728` name） | artifact `performance-benchmark-report`（`:2761-2768`）/ `performance-benchmark-json`（`:2771-2781`）；基线文档 `sla-perf-baseline`（`:2784-2790`） | ⚠️ **绿灯不等于实测**：①该 job 的 `if:` 只认 schedule / workflow_dispatch / push 到 main（`:2735-2738`）⇒ **PR 上根本不触发**，PR 绿与性能无关；②即使触发也带 `continue-on-error: true`（`:2740`）不阻断；③脚本对不可达服务退化为源码理论估计（`tests/performance/run_benchmark.py:3-6`） |
| cluster 档 | `PERF_TIER=cluster pytest tests/integration/perf/`（需多副本集群 + 资源保障） | 同上 | ❌ **0 跑**（`docs/SLA-PERF-BASELINE-2026-09-01.md:58-61`） |
| 流处理延迟 ≤1s | 需要 Flink 集群 | `tests/integration/perf/test_sla_verification.py` | ❌ skip（无集群） |
| 国密 / mesh 损耗 | **无入口** | — | ❌ 无脚本、无基线 |

### 6.3 一条必须写进应答的诚实结论

三重限制叠在一起，**任何一条都足以让「CI 绿」失去性能含义**：

1. **PR 上根本不跑**：`ci.yml:2735-2738` 的 `if:` 只认 `schedule` / `workflow_dispatch` / `push` 到 `refs/heads/main` ⇒ **PR 门禁与性能无关**，PR 全绿不构成任何性能证据。
2. **跑了也不阻断**：`ci.yml:2740` 的 `continue-on-error: true` ⇒ 即使 push 到 main 触发并失败，也不影响合并结论。
3. **数字可能是编的**：`tests/performance/run_benchmark.py` 默认探测的是**集群内网 IP**（`:41-43`，如 `10.43.246.140`），不可达时按**源码分析给出理论性能估计**（`:3-6`）⇒ 在没有真实集群的 runner 上，那条绿灯**很可能只是理论估计**。

⇒ 它能防「服务挂掉」，**不能证明「性能达标」**。

---

## 七、优化手段清单（按损耗点对号）

只列**仓内已有**或**有明确落点**的手段；每条标注代价与适用档。没有的写「无」。

| 损耗点 | 手段 | 代价 | 适用档 | 仓内落点 |
|--------|------|------|--------|---------|
| L1 | 提交器按需运行（Job/CronJob），不用常驻 Pod | 每次投递付冷启动 | L0/L1 | `docs/flink-cdc-deployment.md:19`、`:40`（低峰窗口/批量追赶） |
| L1/L2 | 镜像源选源 + 失败换源兜底 | 多一次探测 | 全部 | `scripts/pick-base-registry.sh`、`scripts/build-retry.sh`；CI 实跑证据见台账 #88 行 |
| L2 | 预热镜像 / 节点级缓存 | 占用节点磁盘 | L2/L3 | **无** |
| L4 | Iceberg snapshot 隔离：批读固定 snapshot、流读最新 | 批读延迟到下一次 snapshot | L2 | `platform/stream-batch-scheduler/README.md:12`、`:238`、`:264-267` |
| L6 | Checkpoint 间隔调小（10s/5s），或把 SLA 改为 P99 ≤ 30s（含 interval） | 小间隔 ⇒ state 存储与写放大上升 | L2 | 设计侧建议已写：`design/v2.0/评审报告/评审报告_D_数据联邦与行业模板.md:57`；**仓内未实施** |
| L6 | 增量 checkpoint | 实现复杂度上升 | L3 | **无** |
| L7 | 编排轮询间隔与并发度调优 | 状态收敛变慢 | L2 | **无参数记录** |
| L8 | Service Mesh **按 Namespace 选择性注入**；备选 **Cilium Service Mesh 模式（无 sidecar）** | 放弃部分 mesh 能力 | L2/L3 | 设计侧：`design/v2.0/V2.0_架构设计文档.md:1223`（后果栏① + 备选方案）、`design/v2.0/V2.0_PRD_产品需求文档.md:622`（R-TECH-004 对策）、`design/v2.0/V2.0_实施计划.md:515`；**仓内未实施** |
| L9 | Cilium socketLB 配 `hostNamespaceOnly=true`，只拦 host namespace 流量，Pod 间交给 sidecar | Pod 间流量路径改变 | L2/L3 | 设计侧：`design/v2.0/V2.0_架构设计文档.md:1223`（协同策略）、`:1300`（AR-009）；**仓内未实施** |
| L10 | 国密硬件加速 + 算法分离 + **关键路径用国际算法兜底** | 牺牲部分合规纯度 | L3 | 设计侧：`design/v2.0/V2.0_架构设计文档.md:1296`（AR-005）、`design/v2.0/V2.0_PRD_产品需求文档.md:625`、`:518`；**仓内未实施** |
| L11 | 流式质量规则**延迟分级**（行级断言 vs 窗口聚合） | 需产品侧接受分级 SLA | L2/L3 | 设计侧建议：`design/v2.0/评审报告/评审报告_D_数据联邦与行业模板.md:87`；**仓内未实施** |
| 全部 | 按场景选档，而不是全域一套配置 | 需要运维侧接受分档 | 全部 | 本文件 §五 |

**「优化税」纪律**（与 qoder 的立场一致）：**先量化损耗构成再优化，不为不存在的瓶颈付优化税**。当前 L3/L8/L9/L10 连损耗量级都不知道，因此**不建议在招标应答里承诺「已优化云原生性能损耗」**——应承诺「**有可执行的档位与复现入口，量化在 L2 演练中完成**」。

---

## 八、盲区声明（必须与本文一并引用）

1. **本机无集群**：无 Flink 集群、无 Spark 集群、无 GPU、无真实 LLM 服务 ⇒ L2/L3 档的任何数字**本机不可得**，本文不提供任何估算值。
2. **cluster 档 0 跑**：`docs/SLA-PERF-BASELINE-2026-09-01.md:58-61` 把它列为开放项，至今未执行。
3. **CI 性能绿灯的语义限制**：见 §6.3——理论估计不等于实测。
4. **设计阈值 ≠ 实测损耗**：本文 §二 引用的 10%、30%、+100m/128Mi 全部来自设计评审的**风险阈值**，用途是「要求必须压测」，不是「已经损耗这么多」。
5. **招标需求原文未取得**：本轮未能访问招标网站原文（联网搜索能力因账户余额用尽不可用，详见交付说明）⇒ 本文的需求侧依据是**仓内决策文档 + 主理人口述的五轴**，不是招标原文。
6. **多集群联邦已裁撤**：Karmada 控制面已退役 ⇒ L3 档的「多集群」当前**无实现**。
7. **9 个行业模板未验证**：`platform/industry-templates/` 的模板未做端到端验证（见 `docs/TENDER-CAPABILITY-MAP.md` §4 盲区声明）。
8. **SLA 基线文档的环境规模自述已过时**：`docs/SLA-PERF-BASELINE-2026-09-01.md:3` 自述「本地 Docker 集成栈，**10 服务**」，实测 compose 顶层 `services:` 段为 **31 个服务**（`origin/main @ be4e5ec5`）⇒ 该文档记录的是**早期 10 服务栈**上的基线，而当前栈规模已扩至 31 ⇒ **SLA 6/6 + perf 15/15 的结论仍以该文档记录的当时栈为准，不自动等同于当前 31 服务栈的现状**；本文件不改写他人已入库的实测记录，只显式登记此差异。

---

## 九、证据索引（复现命令）

```bash
# 1. 确认「性能损耗」只存在于设计层（期望：8 处、4 文件，全部在 design/v2.0/；platform/ 与 docs/ 零命中）
git grep -n -E "性能损耗" origin/main
git grep -n -E "性能损耗" origin/main -- platform/ docs/

# 2. 流批执行路径分开（三条独立 TaskChannel + 技术栈版本）
git show origin/main:platform/stream-batch-scheduler/README.md | sed -n '12p;23,25p;53,58p;264,267p'

# 3. CDC 提交器裁决（非常驻）
git show origin/main:docs/flink-cdc-deployment.md | sed -n '4p;14p;19p;24p;40p'

# 4. Checkpoint 间隔与延迟目标的矛盾
git show origin/main:"design/v2.0/评审报告/评审报告_D_数据联邦与行业模板.md" | sed -n '54p;56p;57p;86p;87p'

# 5. sidecar 与 Cilium 双重拦截
git show origin/main:"design/v2.0/V2.0_架构设计文档.md" | sed -n '1223p;1296p;1300p'

# 6. 性能基线（local 档）与开放项
git show origin/main:docs/SLA-PERF-BASELINE-2026-09-01.md

# 7. CI 性能 job 的真实语义（PR 不触发 + continue-on-error + 理论估计兜底）
#    行坐标基线：origin/main @ be4e5ec5（ci.yml 3102 行；旧基线 3064 行 ⇒ 旧坐标 +38）
git show origin/main:.github/workflows/ci.yml | sed -n '2727,2740p;2759p'
git show origin/main:tests/performance/run_benchmark.py | sed -n '1,12p;38,43p'
```

> 注：仓内中文路径用 `git show origin/main:<路径>` 在部分 shell 下会因转义失败；稳妥做法是用 `git ls-tree -r -z --full-tree origin/main` 解出 blob OID 后 `git cat-file -p <oid>` 读取。
