# nightly 栈外服务扩栈可行性（台账 #57① 的决策输入）

> 生成时间：2026-10-08 ｜ 取证方式：CI 作业日志时间戳 + 本机 `docker build` 实跑 + 源码逐行核对
> 对应台账行：#54（nightly 长期红腿）、#57①（栈外服务导致的 404）、#58（数据形态类，扩栈不必然覆盖）
> 本文只做决策输入，未改 `compose`、未改 `spec`、未改任何服务代码。

## 1. 这 15 条红的机制（不是回归，也不是选择器问题）

`frontend/playwright.config.ts:19-44` 的策略是：**栈外服务的前缀统一回落到 encaps-layer 的宿主机端口 18080**
（`vector`/`ai`/`ops`/`streamBatch`/`encapsData`/`encapsGateway`/`infraOrchestrator`/`lineage` 八个键的默认值都是 18080）。
encaps-layer 里没有这些前缀的实现——已在 15 个栈内服务目录逐个搜过服务端映射，命中 0；
唯一命中是 `encaps-layer/.../DevelopJobService.java:56` 作为**调用方**拼 URL——
所以这些断言拿到 **404**。

## 2. 表 A：15 条红 → 服务 → 今天有没有构建/部署单元

| # | 失败用例 | 前缀 | 归属服务 | Dockerfile | k3s manifest | Helm Chart | 可扩进 nightly？ |
|---|---|---|---|---|---|---|---|
| 1 | `eng-flink.spec.ts:36` | `/flink` | stream-batch-scheduler | **无** | 无 | 有 | 否 |
| 2 | `eng-spark.spec.ts:36` | `/spark` | stream-batch-scheduler | **无** | 无 | 有 | 否 |
| 3 | `job-management.spec.ts:48` | `/jobs` | stream-batch-scheduler | **无** | 无 | 有 | 否 |
| 4 | `datasources.spec.ts:30` | `/datasources` | encaps-data | **无** | 无 | 无 | 否 |
| 5 | `eng-doris.spec.ts:37` | `/doris` | encaps-data | **无** | 无 | 无 | 否 |
| 6 | `eng-kafka.spec.ts:40` | `/kafka` | encaps-data | **无** | 无 | 无 | 否 |
| 7 | `gateway.spec.ts:35` | `/gateway` | encaps-gateway | **无** | 无 | 无 | 否 |
| 8 | `vector.spec.ts:39` | `/vector` | vector-engine | 有（Go，8086） | **有** | 有 | **可** |
| 9 | `ai-assistant.spec.ts:55` | `/ai-assistant` | ai-assistant | 有（Go，18110） | 无 | 有 | **可** |
| 10 | `cluster-overview.spec.ts:39` | `/cluster/overview` | observability/query-api | 有（Go，8090） | 无 | 有（名为 observability） | **可** |
| 11 | `dashboard.spec.ts:50` | `/cluster/overview` | observability/query-api | 有（Go，8090） | 无 | 有（名为 observability） | **可** |
| 12 | `lineage.spec.ts:30` | `/lineage` | governance/lineage-analyzer | 有（Java，8086） | 有 | 有 | **可** |
| 13 | `data-lineage.spec.ts:39` | `/lineage` | governance/lineage-analyzer | 有（Java，8086） | 有 | 有 | **可** |
| 14 | `infra-k8s.spec.ts:39` | `/clusters` | infra-orchestrator | 有（Java，8085） | 有 | 有 | **可** |
| 15 | `infra-machine.spec.ts:35` | `/clusters/xinchang` | infra-orchestrator | 有（Java，8085） | 有 | 有 | **可** |

计数按结构复现：第 8–15 行 = **8 条可扩**（对应 5 个服务），第 1–7 行 = **7 条不可扩**（对应 3 个服务），合计 15。

服务目录（此前草稿写成 `platform/lineage-analyzer`、`observability/query-api`，均已按 `find` 结果更正）：
`platform/vector-engine`、`platform/ai-assistant`、`platform/observability/query-api`、
`platform/governance/lineage-analyzer`、`platform/infra-orchestrator`。

### 2.1 那 7 条为什么不可扩：两类卡点，不是一类

- **stream-batch-scheduler（3 条）＝"有 Chart 却没有镜像构建法"**：
  `platform/stream-batch-scheduler/pom.xml` 存在（所以 `build.yml` 的 `find platform -name pom.xml` 会编译它），
  但 `find platform/stream-batch-scheduler -name 'Dockerfile*'` 为空（所以 `build.yml:290` 的镜像构建 glob 永远不含它）；
  而 `design/deploy/charts/stream-batch-scheduler/values.yaml:9-10` 写死
  `repository: "harbor.shuqing.io/shuqing/stream-batch-scheduler"` + `tag: "2.0.0"`。
  全仓没有任何 workflow 往该仓库推送（`grep -rn stream-batch-scheduler .github/workflows/` 唯一命中是
  `ci.yml:943` 的**覆盖率排除清单**；`build.yml` 推送目标是 `ghcr.io/${REGISTRY_NS}/sq-<mod>`）。
  ⇒ 结论边界要说清：**本仓产不出这个镜像**（可证），harbor 上是否另有外部流程推过 2.0.0 **本机不可达、未验证**。
  这与台账 **#30**（Kind 冒烟拉不到镜像、"绿"是假绿）同源，是它的另一面：#30 讲的是镜像源不可达，这里讲的是镜像根本没有构建法。
- **encaps-data / encaps-gateway（4 条）＝三个都没有**：无 Dockerfile、无 k3s manifest、无同名 Chart，
  也不在 `tests/integration/docker-compose.yml`。台账 #2 原话是它们"自身没有 Dockerfile，属随宿主服务打包的模块"。
  ⇒ 这 4 条要等 **#1/#1b** 裁决"这些前缀在生产由哪个可部署单元承载"，扩栈无从下手。

顺带量化了同一个盲区的范围：`design/deploy/charts/*/values.yaml` 里引用内部镜像仓库（`harbor.shuqing.io/shuqing/*`）的
first-party Chart 共 **12 个**，其镜像名与仓内 36 个 Dockerfile 目录名**全部不相等**
（含 chunker、finops、flink-cdc、model-finetuning、observability、storage-io 等）。
`docs/deployable-backlog.yaml` 只登记反方向（"有 Dockerfile 却无 Chart"），所以这个方向今天没有门禁。
是否要补一个"Chart 镜像名 ↔ 可构建镜像名"的双向校验，属另一件事，本文件不动。

## 3. 表 B：时长实测（全部有日志出处，非估算）

### 3.1 nightly 现有栈的总账（run `37665939227` / job `112945055668`，Playwright 作业日志 UTC 时间戳）

| 阶段 | 实测 |
|---|---|
| `docker compose up -d --wait --wait-timeout 600` | 步骤首行回显 `18:21:55` → 就绪输出 `18:26:52` = **4m57s** |
| 其中 15 个镜像并行构建（`naming to docker.io/shuqing/…` 首末） | `18:22:32 → 18:25:44` = **3m12s** |
| `npm ci` + `playwright install --with-deps chromium` | `18:26:52 → 18:27:06+` ≈ **1m+** |
| 测试本体 | **15.4 分钟** |
| 该 job 全程 | ≈ **23 分钟** |
| `playwright-e2e` 的 `timeout-minutes` | **120**（`.github/workflows/nightly-e2e.yml`） |

### 3.2 单镜像构建耗时——CI 实测（run 240 / job `113050635332` "K3s Suite"，串行 `docker build -q`）

该作业的循环（`nightly-e2e.yml:191-219`）在同一个 runner 上逐个构建，每条 `构建 sq/<name>` 回声与下一条之间的间隔即该镜像耗时：

| 组件 | 类型 | 实测 |
|---|---|---|
| encaps-layer | Java（根上下文） | `22:34:51 → 22:36:18` = **86s** |
| sql-gateway | Java（根上下文） | **68s** |
| nl2sql | Python | **20s** |
| **infra-orchestrator** | Java（根上下文） | `22:37:46 → 22:38:29` = **43s** |
| knowledge-engine | Python | **23s** |
| industry-templates | Python | **11s** |
| **lineage-analyzer** | Java（根上下文） | `22:39:03 → 22:39:55` = **52s** |

7 个都打出 `sha256:` 摘要，日志里没有 `未找到 Dockerfile` 也没有 `构建失败，跳过导入` 的 warning；
随后 `deployment.apps/infra-orchestrator condition met`、`lineage-analyzer condition met`，
Pod 表两行都是 **1/1 Running**（`22:41:10`/`22:41:11`）。
两者的就绪探针都是 `httpGet /actuator/health`（`deploy/k3s/manifests/infra-orchestrator.yaml:49-52` 端口 8085、
`lineage-analyzer.yaml:34-37` 端口 8086）⇒ **今天就在 CI 里真起、且 HTTP 健康检查通过**。
同表里 `vector-engine-f854c8969-fdqd5 0/1 ErrImagePull`——manifest 存在（`deploy/k3s/manifests/vector-engine.yaml`，探针 `/health:8086`），
只是那个构建循环的 7 项清单里没有它。

### 3.3 单镜像构建耗时——本机实测（2026-10-08，Docker Desktop 4.93.0 / Engine 29.8.1，串行）

| 服务 | 首个含基础镜像拉取 | 实测 | 产出 |
|---|---|---|---|
| vector-engine | 是（`golang:1.26-alpine` + `alpine:3.19`） | **123s** | `local-timing/vector-engine:probe` 93MB |
| ai-assistant | 否（基础镜像已热） | **62s** | 62.8MB |
| query-api | 否 | **82s** | 81.7MB |

三个都成功出镜像（各有 `sha256:` 摘要）。本文前一版把这三项记成"本机 `docker build` 被 buildkit 通道打断、未验证"，
本轮重跑即全部成功，故替换为实测值；**那两次失败的原因没有查**，只能说它不复现（见 §5.4）。

### 3.4 边际成本的结论

5 个服务串行构建合计 ≈ 362s；nightly 是 `compose up` **并行**构建，当前 15 镜像并行包络 3m12s，
而新服务里最慢的是 vector-engine 123s（本机，含拉基础镜像；CI 无此开销会更低），
**低于包络内已有的 Java 镜像**。⇒ 扩栈的时长代价量级在 **+1～2 分钟**，
"扩栈会把 nightly 拖爆"在本仓当前配置下**不成立**（余量 97 分钟）。

### 3.5 一个已有覆盖，扩栈时别重复造

`ci.yml:1815-1820` 的 `Docker Build Verify` 遍历 `find platform -name Dockerfile`（`:1838`）逐个 `docker build` 仅验证不推送
⇒ **表 A 那 5 个"可扩"服务的镜像构建可行性已由现有门禁覆盖**，扩栈 PR 自身会再验一遍，不需要新增构建验证步骤。

两点要说清，避免误读：

- 它**只在 PR 上跑**（`if: github.event_name == 'pull_request'`，`:1819` 注明 push 时不跑以省资源）。
  main HEAD `911c576f` 上该 check-run 是 `skipped`——这是设计内行为，不是漏跑。
- 实测代价：PR run `37710260238`（head `cb6dd887`）该 job **success**，`01:04 → 01:28` ≈ **24 分钟**（全量 platform Dockerfile，串行）。

## 4. 表 C：扩栈每个服务要补什么（这决定"是一次 dispatch 还是要先做产品改动"）

compose 宿主端口：`18080–18096` 已被 15 个服务占用（另有 13000/14318/16686/19090/4317），
`18080–18099` 区间空闲的正好 **5 个**：**18091、18092、18097、18098、18099** ⇒ 与 5 个新服务数量吻合，不需要重排既有映射。

| 服务 | 需要补的 env（源码实测的变量名） | 依赖 |
|---|---|---|
| vector-engine | `VECTOR_ENGINE_PORT`、`MILVUS_HOST`/`MILVUS_PORT`/`MILVUS_DATABASE`（或走 `STORE_TYPE`+`MOCK_FALLBACK`）、`JWT_ISSUER`、`VECTOR_AUTH_REQUIRED`、`CORS_ORIGINS` | 栈内**没有** Milvus ⇒ 要么起 Milvus 要么显式 mock 分支 |
| ai-assistant | `AI_ASSISTANT_PORT`、`JWT_SIGNING_KEY`、`JWT_ISSUER`、`AI_ASSISTANT_DB`；默认值是 `http://localhost:…`（`internal/config/config.go:27-32`），compose 内**必须覆盖**成服务名 | `LLM_GATEWAY_URL`/`SQL_GATEWAY_URL`/`NL2SQL_URL` 指向栈内服务，但需确认容器内 `localhost` 语义 |
| query-api | `QUERY_API_PORT`、`JWT_SIGNING_KEY`、`JWT_ISSUER`、`PROMETHEUS_URL`、`ALERTMANAGER_URL`、`OPS_COMPONENTS`、`K3S_KUBECONFIG` | Prometheus **已在栈内**（`prometheus` 服务，宿主 19090）；K8s 类接口在 compose 里无 kubeconfig |
| lineage-analyzer | Java 侧标准 env | Dockerfile 用根上下文（`COPY platform/…`），依赖链见 `build.yml:204` 注释（common-security ← encaps-layer ← encaps-data ← sql-gateway ← lineage-analyzer） |
| infra-orchestrator | `CLOUD_PROVIDER_URL`、`PRIVATE_PROVIDER_URL`（k3s manifest 里给的是集群内 Service DNS） | 4 个 `infra-provider-*` 均不在栈内（k3s 作业里它们就是 `ErrImagePull`） |

一条跨语言坑，扩栈时必踩：compose 现在 Java 侧传 `JWT_SECRET`（`docker-compose.yml:31/74/101/153/182/217/377/406`），
Go 侧读 `JWT_SIGNING_KEY`（`:130`），两处字面值相同（`it-test-jwt-secret-at-least-32-bytes-long`）只是变量名不同
⇒ 新加的 Go 服务两个名字里要传的是 `JWT_SIGNING_KEY`，传成 `JWT_SECRET` 会静默不生效。

## 5. 未验证项（不拿它们当结论）

1. **扩栈后断言是否真转绿**。§4 表明 5 个服务各有一个"栈里没有"的依赖（Milvus / provider / kubeconfig 等），
   能不能给 `/vector`、`/cluster/overview` 返 200 而未 500，只能靠一次 `workflow_dispatch` 拿数。
   `infra-orchestrator`、`lineage-analyzer` 已有较强的间接证据（k3s 里 1/1 Running + HTTP 探针通过）。
2. **DOM 类断言扩栈不必然转绿**：`vector.spec.ts:30`「集合表格表头正确」、`dashboard.spec.ts:41`「资源趋势与待办审批区域存在」、
   `admin.spec.ts:28` 断言的是**渲染出来的表头/区域元素**，要页面真拿到数据才有意义（`admin.spec.ts:28` 的红在台账里已被明确写成
   "是 DOM 计数不是状态码"，见 `docs/KNOWN-FAILURES.md:269` 的续⑧；行号口径另见 `:270` 的续⑨）。
   注意这三条**不属** #58——#58 的 3 例是 `.sub` 类名漂移（`kb.spec.ts:18`、`data-lineage.spec.ts:18`、`ops-quality.spec.ts:52`）。
   本文不预判扩栈能否让它们转绿。
3. **harbor 上是否另有外部推送**：`harbor.shuqing.io` 本机不可达，§2.1 只断言"本仓无构建法"。
4. **上一版本草稿记录的"本机 `docker build` 被 buildkit 通道打断"两次失败没有复现，也没查原因**
   （同命令、同 Dockerfile 本轮三次全成功）。只留这一句事实，不给归因。

## 6. 三案代价（供裁）

| 方案 | 内容 | 代价 | 收益 |
|---|---|---|---|
| **①（推荐先做）** | 只扩 §2 表 A 里"可"的 5 个服务：compose 加 5 个 service（用 18091/18092/18097/18098/18099），`nightly-e2e.yml` 为对应 `VITE_*_TARGET` 赋值，先跑一次 dispatch | 一次 dispatch（≈25 分钟机时）+ §4 的 env 适配（其中 Milvus/provider 可能要 mock 开关） | 最多摘掉 8 条红（18 → 约 10）；这 5 个前缀获得真实回归网 |
| **②** | 只对 7 条卡在架构裁决的用例 `test.skip` + 写明理由与台账行号 | 放弃这 7 条覆盖度（诚实标注，不假绿） | main 红腿可摘，噪声下降；与 #1/#1b 裁决绑定，裁完再撤 |
| **③** | 维持现状 | 15 条长期红继续稀释信号 | 无 |

推荐 **①＋② 同批**：① 是纯 CI 配置不碰产品码；② 只针对那 7 条，且把"为什么 skip"写成可复核的引用（本文 §2.1）。
② 若采纳，`stream-batch-scheduler` 那条还应同时把"有 Chart 无 Dockerfile"记进 #2/#30 的关联说明，
否则下次仍会被读成"这个组件是可部署的"。

## 7. 复现命令

```bash
# 表 A：目录与 Dockerfile 有无
find platform/stream-batch-scheduler platform/encaps-data platform/encaps-gateway -name 'Dockerfile*'
ls platform/vector-engine/Dockerfile platform/ai-assistant/Dockerfile \
   platform/observability/query-api/Dockerfile platform/governance/lineage-analyzer/Dockerfile \
   platform/infra-orchestrator/Dockerfile

# §2.1：Chart 引用镜像名清单（12 个）
grep -rhoE 'repository: *"?harbor\.shuqing\.io/[^"]+' design/deploy/charts/*/values.yaml | sort -u

# 表 B 3.1：nightly 现有栈时长
gh api --allow-escape-sequences repos/Levango7/DataEngineBDP/actions/jobs/112945055668/logs \
  | sed 's/\x1b\[[0-9;]*[A-Za-z]//g' | grep -E "naming to docker.io|后端栈所有服务已就绪"

# 表 B 3.2：单镜像串行构建 + Ready 证据
gh api --allow-escape-sequences repos/Levango7/DataEngineBDP/actions/jobs/113050635332/logs \
  | sed 's/\x1b\[[0-9;]*[A-Za-z]//g' | grep -E "^.*构建 sq/|condition met|vector-engine-"

# 表 B 3.3：本机三镜像实建（串行，首个含基础镜像拉取）
for s in platform/vector-engine platform/ai-assistant platform/observability/query-api; do
  t0=$(date +%s); docker build -q -t "probe/$s:1" "$s" >/dev/null; echo "$s $(( $(date +%s) - t0 ))s"
done

# 表 C：空闲宿主端口
grep -noE '"[0-9]+:[0-9]+"' tests/integration/docker-compose.yml
```
