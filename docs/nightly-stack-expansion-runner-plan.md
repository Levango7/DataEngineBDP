# nightly 扩栈① 的 CI 容量方案（决策输入 + 实施计划）

> 产出：2026-10-09，pi ｜ 状态：**待实施**（本文件不改 compose / 不改 workflow，只给方案）
> 关联：台账 #54 / #57① / #58；`docs/nightly-stack-feasibility.md`（#375，扩栈可行性）；
> PR #383（② 落地）、#385（① 两次 dispatch 否决）、#391（vector-engine HEALTHCHECK）、#392（契约修复 + “404 条件式”）
> 一句话：扩栈① 的**契约与路径问题已全部修复**（#392），剩下的唯一障碍是**标准 runner 的内存容量**；
> 本文件给出三种可落地的解法与推荐组合。

---

## 0. 结论速览

- ① 失败**不是服务缺陷**：同一套 5 个服务在本机 Docker 29.8.1 全部 healthy（#392 复述），
  在 CI 却是栈启动即 `it-encaps-layer is unhealthy`。
- 机制已定位：**compose 全栈无任何容器内存上限**，而每个 Java 服务都用
  `JAVA_OPTS="-XX:MaxRAMPercentage=75.0"` ⇒ 每个 JVM 都按**宿主机**内存（标准 runner ≈ 7 GB）算堆，
  8 个 JVM + Python/Go/3 个观测组件 + Chromium 一起把 runner 打爆。
- 因此**不必"放弃 ①"**：只要 (A) 把扩栈规格单独跑在**精简栈**里，和/或 (B) 给容器加内存上限，
  ① 就能稳定落地。推荐 **A + C**（B 作为纵深防御）。
- 若账号可用**更大 runner**（≥16 GB），则 D 是最省事的选项。

---

## 1. 现状与边界

| 项 | 状态 |
|---|---|
| ② 裁决（对栈外断言 `test.skip`） | **已落地**（#383），nightly 18 → 2 红 |
| ① 裁决（服务进 nightly compose） | **实测否决**（#385 两次 dispatch 栈启动失败）；结论记入 #392 + 台账 #57 |
| 排查副产品（真缺陷） | **已落地**（#392）：ai-assistant `/sessions` 裸数组、query-api `QUERY_API_K8S_MOCK`、vite `/lineage` strip、vector 裸数组断言 |
| 断言策略 | **已升级**（#392）：11 处栈外 API 断言由“无条件 skip”改为“**仅 404 才跳过**” ⇒ 服务一旦可用即自动断言，半坏（500/契约不符）不被吞 |
| 仍保留 skip | `infra-machine` 的 `/clusters/{env}`（不可达/禁用 provider 非 200，待产品定容错语义） |

> 边界：`docs/nightly-stack-feasibility.md`（#375）论证了「**镜像可构建**」（时长 +1~2 分钟），
> 但**没有算 runner 的内存容量** —— 这正是 ① 在 CI 翻车的原因。本文件补上这块。

---

## 2. 失败证据（全部实测，可复查）

| run | 阶段 | 结果 |
|---|---|---|
| `37889365483`（① 首跑，head `d868b46b`） | 起全栈成功，跑全量 28 spec | **13 failed / 9 skipped / 302 passed**（契约真因，后被 #392 修） |
| `37919622584`（修正版，head `1b21b8aa`） | `docker compose --profile nightly-extra up --wait` | **`dependency failed to start: container it-encaps-layer is unhealthy`** |
| `37923491561`（同上，重跑） | 同上 | **同一失败** |
| `37919469408`（PR 触发，同 head，起全栈跑稳定子集） | 栈起来了，测试期 | **71 failed / 46 passed**（登录后 h1 取不到、page 被关闭 = 资源性大范围超时） |
| `37883187189`（main，② 之后，**基准**） | 全量 | **2 failed / 305 passed**（`dashboard:41 .bar`、`vector:30` 表格） |

⇒ 对照干净：**基准 2 红**在“**只起 18 个基础服务**”时稳定；一旦**多起 5 个服务**（共 23），
栈启动/测试期就随机崩 ⇒ 障碍在**容量**，不在代码。

---

## 3. 根因机制：内存 over-commit（本文件的增量分析）

### 3.1 事实

- `tests/integration/docker-compose.yml`：**没有任何** `mem_limit` / `cpus` / `deploy.resources`
  （全仓 grep `mem_limit|cpus:|deploy:|resources:` 命中 0）。
- Java 镜像统一 `ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"`
  （13 个 Dockerfile 命中；栈内 8 个 Java 服务全中）。
- `runs-on: ubuntu-latest`；本仓库为私有 ⇒ 标准 runner = **2 vCPU / ~7 GB RAM**。

### 3.2 后果

无 cgroup 内存上限时，Java 17 的 `MaxRAMPercentage` 按**宿主机**内存计 ⇒ 每个 JVM 的
`MaxHeap ≈ 0.75 × 7 GB ≈ 5.25 GB`。8 个 JVM **各自**都以为自己能用 5 GB，合计远超 7 GB；
再叠加 7 个 Python、5 个 Go、prometheus/grafana/jaeger、Vite + Chromium（~0.5–0.8 GB），
内存被打爆 → 内核回收/杀进程 → `encaps-layer` 健康检查连续失败 → `--wait` 失败。

> 基础栈（18 个，6 个 Java）**正好卡在边缘**（所以 nightly 平时能跑）；
> 再加 2 个 Java（infra-orchestrator、lineage-analyzer）+ 3 个 Go 就**越线**。

### 3.3 粗算（无上限，稳态 RSS 量级）

| 组 | 数量 | 单项量级 | 小计 |
|---|---|---|---|
| Java（8） | encaps-layer、encaps-tenant、sql-gateway、rule-engine、cost-model、finops-dashboard + infra-orchestrator、lineage-analyzer | 300–600 MB | ~3.6 GB |
| Python（7） | evaluation、finetuning-loop、model-registry、asset-exchange、business-portal、open-api-catalog、industry-templates | 150–250 MB | ~1.4 GB |
| Go（5） | catalog + vector-engine、ai-assistant、query-api | 50–100 MB | ~0.4 GB |
| 观测（3） | prometheus、grafana、jaeger | prometheus ~300 MB | ~0.55 GB |
| 前端测试 | Vite dev + Chromium | — | ~0.7 GB |
| **合计** | 23 容器 | | **≈ 6.6 GB vs 7 GB 上限** |

（稳态已贴上限；再加上 JVM 未及时归还的堆、页面缓存、构建残留 ⇒ 抖动即崩。）

---

## 4. 候选方案

| 方案 | 做法 | 代价 | 收益 | 适用 |
|---|---|---|---|---|
| **A. 扩栈规格独立精简 job** | 新增一个 job，只起 **encaps-layer + prometheus + 5 个扩栈服务**（用 `docker compose --profile nightly-extra up <svc...>` 按名启动），只跑这 8 个 spec；基础 nightly job 保持 18 服务不变 | 多一个 job（~15 分钟机时）；需把 8 个 spec 归类 | 单 runner 同时只跑 ~7 容器，彻底避容量问题；基础 nightly 零波及 | **推荐主线** |
| **B. 容器内存封顶 + JVM 感知** | 给 Java 服务加 `mem_limit`（如 512m）⇒ Java 17 cgroup 感知后 `MaxHeap≈70%×512MB`；Python/Go 加 `mem_limit`（256m/128m）；必要时显式 `-Xmx` | 需逐服务调参；限太紧会 OOM | 全栈内存可控，A/C 的纵深防御 | **推荐配套** |
| **C. 串行启动 + 健康检查加固** | 5 个扩栈服务加 `depends_on: encaps-layer: condition: service_healthy`（错峰启动）；基础栈 healthcheck 放宽 `start_period`/`retries` | 启动总时长 +20–40s | 消除“encaps-layer 被挤到 unhealthy”的失败模式 | **推荐配套** |
| **D. 更大 runner** | `runs-on: ubuntu-latest-4-cores`（16 GB）或 self-hosted | 需账号支持 larger runner / 维护自托管；有费用 | 一行改动，最省事 | 若可用，**最优先** |
| **E. 维持 ② + “404 条件式”** | 就是 #392 现状 | 8 个 API 断言在无服务时跳过 | 0 成本；nightly 稳在 2 红 | 兜底（现状） |

---

## 5. 推荐路线

**优先 D；否则 A + C（B 作为纵深防御）。** 理由：

- A 把“容量问题”从根上变成“不需要”——扩栈规格只需要 `encaps-layer`（登录）+ 目标服务，
  prometheus 仅 query-api 的 `/cluster/overview` 需要；其余 16 个基础服务与扩栈无关。
- C 让启动确定化，避免再次出现“基础栈被别人拖到 unhealthy”。
- B 让基础栈本身更稳（即使维持 E 也值得做）。
- D 若可用则一步到位；但很多私有仓库默认不给 larger runner，故不能作为唯一方案。

**不建议**：把 23 个服务继续挤在同一个 7 GB runner 上跑（即 #385 的失败路径）。

---

## 6. 实施步骤（A + C + B）

### 6.1 A：独立精简 job

在 `.github/workflows/nightly-e2e.yml` 新增 job（示意，非最终代码）：

```yaml
  playwright-e2e-expanded:
    name: Playwright E2E（扩栈规格 #57①）
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4
      # ... setup-node / npm ci / playwright install 同现有 job ...
      - name: 只起扩栈所需的最简栈
        working-directory: tests/integration
        run: |
          # 按名启动 ⇒ 只拉起这些服务及其 depends_on（encaps-layer 供登录）；
          # 5 个扩栈服务在 profile nightly-extra 下。
          docker compose --profile nightly-extra up -d --wait --wait-timeout 600 \
            encaps-layer prometheus vector-engine ai-assistant query-api \
            infra-orchestrator lineage-analyzer
      - name: 只跑扩栈相关的 8 个 spec
        working-directory: frontend
        run: |
          npx playwright test \
            tests/e2e/vector.spec.ts tests/e2e/ai-assistant.spec.ts \
            tests/e2e/cluster-overview.spec.ts tests/e2e/dashboard.spec.ts \
            tests/e2e/data-lineage.spec.ts tests/e2e/lineage.spec.ts \
            tests/e2e/infra-k8s.spec.ts tests/e2e/infra-machine.spec.ts
```

要点与坑：

- **`prometheus` 的 `depends_on`**：现有 compose 里 prometheus 依赖 encaps-layer/sql-gateway/
  catalog/rule-engine，会把这些一并拉起。若想更精简，可在**扩栈 override**里去掉 prometheus 的
  `depends_on`（query-api 的 `/cluster/*` 走 k8s mock，不依赖 prometheus）。
- `encaps-layer` 必须在（8 个 spec 的 `beforeEach` 要登录）。
- 8 个 spec 里的 `infra-machine` 仍会 skip（provider 语义待定），无妨。

### 6.2 C：串行 + 健康检查加固（`tests/integration/docker-compose.yml`）

- 5 个扩栈服务各加：
  `depends_on: { encaps-layer: { condition: service_healthy } }`
- `encaps-layer` 的 healthcheck：`start_period: 15s → 45s`，`retries: 10 → 20`（可选）。

### 6.3 B：内存封顶（`tests/integration/docker-compose.yml`，Java 为主）

```yaml
  encaps-layer:
    mem_limit: 768m        # Java 17 cgroup 感知：MaxHeap ≈ 0.7×768MB
  # 其余 Java：mem_limit: 512m
  # Python：mem_limit: 256m；Go：mem_limit: 128m
```

- 加 `mem_limit` 后**不要**再手动写 `-Xmx`（让 `MaxRAMPercentage` 按 cgroup 算），除非需要更细控制。
- 先只给栈内服务加，观察 nightly 是否更稳；`--wait` 会更早暴露“谁内存不够”。

### 6.4 解 skip

- #392 已把 11 处断言改为“**仅 404 才 skip**” ⇒ 扩栈服务一上线，这些断言**自动生效**，
  无需再手动解 skip。
- 仅 `infra-machine` 需另行处理（产品容错语义，见 §8）。

---

## 7. 验收标准与 dispatch 计划

1. `pr`（或 dispatch 到分支）：**新 job 绿** + **基础 nightly job 不受影响**。
2. 全量 dispatch 的失败数应从基准 **2 → 0**（`dashboard:41 .bar`、`vector:30` 表格随 query-api/
   vector-engine 就位而转绿——首次 dispatch 已实测 `vector:30` 可通过）。
3. 新增红 = 0；跳过数不高于 #392 现状（仅 `infra-machine`）。
4. 每步都取数：先 A 单独跑（确认精简栈能起、8 spec 结果），再叠加 B/C，最后合并。

---

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| `mem_limit` 设太紧 → JVM OOM | 先取保守值（Java 512–768m），用 `--wait` + job 日志观察；必要时回调 |
| 精简 job 与基础 job 共享 `VITE_*` 语义漂移 | 两 job 都用 `playwright.config.ts` 的同一 target 表；扩栈端口固定复用 |
| `prometheus` 依赖链把栈拉大 | 用 override 去掉其 `depends_on`，或改由按名只起 query-api（cluster mock 不依赖它） |
| `infra-machine` `/clusters/{env}` | 仍非 200：**产品决策**——单环境列表是否与聚合口径一样“对不可达/禁用 provider 返回空 200”，或加 provider mock。在此之前保留 skip |
| 回滚 | 全部改动都是**新增 job / 新增 compose 键**，删除即可回到 ②+E 现状 |

---

## 9. 附录

### 9.1 全栈 23 个服务分组

- **Java(8)**：encaps-layer、encaps-tenant、sql-gateway、rule-engine、cost-model、finops-dashboard、infra-orchestrator、lineage-analyzer
- **Python(7)**：evaluation、finetuning-loop、model-registry、asset-exchange、business-portal、open-api-catalog、industry-templates
- **Go(5)**：catalog、vector-engine、ai-assistant、query-api
- **观测(3)**：prometheus、grafana、jaeger
- **扩栈 profile `nightly-extra`(5)**：vector-engine、ai-assistant、query-api、infra-orchestrator、lineage-analyzer

### 9.2 复现命令

```bash
# 探查是否有内存上限（应为空）
grep -nE "mem_limit|cpus:|deploy:|resources:" tests/integration/docker-compose.yml
# 各 Java 镜像的堆策略（宿主机比例）
grep -rn "MaxRAMPercentage" platform --include=Dockerfile
# 本机起全栈（开发机 Docker 内存充足时）
cd tests/integration && docker compose --profile nightly-extra up -d --wait
```

### 9.3 与既有文档的关系

- `docs/nightly-stack-feasibility.md`（#375）：回答“**能不能扩、要花多久**”。
- 本文件：回答“**扩进 CI 后怎么跑得稳**”（runner 容量视角）。
- 台账 #57① 行：记录 ① 的否决结论与副产品（#392 已回填）。
