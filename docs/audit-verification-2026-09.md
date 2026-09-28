# DataEngineBDP 独立核查报告

> **核查日期**：2026-09-19 ｜ **核查对象**：`F:\Nexus\DataEngineBDP`
> **核查基线**：HEAD = `837472c8`（分支 main，共 745 commits）
> **核查方式**：**只读**。文件计数、git 历史、配置内容、源码逐项实测
> **核查边界**：**未执行构建、未运行测试、未部署**
> **核查人**：Cline（AI 编程助手）— 独立于项目自评文档的第二方核查

---

## 一、核查方法与边界

### 1.1 核查了什么

| 维度 | 方法 |
| --- | --- |
| 组件数口径 | 实测 `pom.xml` / `go.mod` / `pyproject.toml` 计数 |
| 目录数口径 | 实测 `platform/` 一级子目录 |
| 文档声称的系统性数字 | 逐项实测比对（workflow / chart / 测试数） |
| 组件成熟度分级 | 对 `component-maturity.md` 逐行核对配置默认值与源码实现 |
| 提交真实性 | `git show --stat` 逐提交核对 |
| 发布谱系 | tag → commit 日期比对 |

### 1.2 没有核查什么（因此不下结论）

- **未执行构建**：不评价"能否编译通过"
- **未运行测试**：不评价实际通过率、覆盖率
- **未部署**：不评价"四环境零改动交付"
- **未统计全仓行数**：不评价"441K 行"这一数字
- 因此**本报告不给出综合评分**——避免制造虚假精度

---

## 二、核实为【准确】的声明

以下声明经实测**成立**，每条附可复现证据。

### 2.1 仓库规模类

| 声明（出处） | 实测证据 | 结论 |
| --- | --- | --- |
| 自研组件 46（Java 24 / Go 10 / Python 12）<br>`docs/模块数口径定义.md` | `pom.xml=24` + `go.mod=10` + `pyproject.toml=12` = **46** | ✅ **精确吻合** |
| `platform/` 一级目录 38 | 实测一级子目录 = **38** | ✅ 吻合 |
| CI workflow 15 个 | `.github/workflows/` = **15 个文件** | ✅ 吻合 |
| Helm Chart 88 个 | `design/deploy/charts/**/Chart.yaml` = **88** | ✅ 吻合 |
| 根 pom 纳管 24 个 Maven 模块 | `pom.xml` 中 `<module>` = **24** | ✅ 吻合 |

### 2.2 组件成熟度矩阵（`docs/component-maturity.md`）

对 **21 行**抽样核对，其中 **17 行准确**：

| 组件 | 矩阵声称 | 实测证据 |
| --- | --- | --- |
| sql-gateway | H2 文件 `./data/sql-gateway-db` | `application.yml:12` 完全一致 ✅ |
| encaps-layer | H2 文件 `./data/encaps-layer-db` | `application.yml:9` 一致 ✅ |
| rule-engine | H2 文件 `./data/rule-engine-db` | `application.yml:8` 一致 ✅ |
| tag-engine | H2 文件 `./data/tag-engine-db` | `application.yml:9` 一致 ✅ |
| stream-batch-scheduler | H2 `~/.shuqing/scheduler-data` | `application.yml:11` = `${user.home}/.shuqing/scheduler-data` ✅ |
| business-portal | 默认 SQLite | `settings.py:8`「默认 sqlite」✅ |
| open-api-catalog | 默认 SQLite | `settings.py:12`「默认 sqlite」✅ |
| asset-exchange | 默认 SQLite | `settings.py:8`「默认 sqlite」✅ |
| ml-platform | `sklearn` 默认；MLflow 总开关默认 false | `settings.py:59` 默认 sklearn；`:76` `mlflowEnabled=False` ✅ |
| knowledge-engine | Dockerfile fail-fast | `Dockerfile:51,54`「未配置真实 NebulaGraph 连接将启动失败」✅ |
| llm-gateway | 未配置 Provider 时兜底 Mock | `config.go:108`「开箱即用不再静默 mock；未配置任何 provider 时仍兜底 mock」✅ |
| ai-assistant | 会话 SQLite（GORM） | `session_store.go:11`「SQLite + GORM」✅ |
| catalog | glebarez 纯 Go SQLite 驱动 | `go.mod`/测试引 `github.com/glebarez/sqlite` ✅ |
| observability | Prometheus 代理 | `go.mod:8-9` `prometheus/common`、`prometheus/prometheus` ✅ |
| dqctl | cobra + viper | `go.mod:6-7` `spf13/cobra v1.8.1`、`spf13/viper v1.21.0` ✅ |
| infra-provider-cloud | 真实可部署（无 Mock 默认） | `src/main` 内 `mock`/`TODO` 命中数 = **0** ✅ |
| infra-provider-private / xinchang | 同上 | 同上，命中数 = **0** ✅ |

**结论**：矩阵的**技术栈与默认持久层描述整体可信**，是认真读码写出的文档，不是套话。

---

## 三、发现的缺口

### 🔴 缺口 1：矩阵行数口径不自洽（43 应为 41）

**声称**（`component-maturity.md:106`）：
> 合计：46（自研组件）− 2（governance 合并）− 1（finops 合并）= **43 矩阵实列**

**实测**：

| 分节 | 实测数据行 |
| --- | --- |
| 一、真实可部署（22 个） | 22 |
| 二、服务级（7 个） | 7 |
| 三、骨架 / Mock 默认（12 + 3 规划） | 15 |
| **合计** | **44 行**（含 3 行规划项，平台内实为 **41 行**） |

**两处漏扣**：

1. **`finops` 实际有 3 个构建单元，非 2 个**
   - 实测：`platform/finops/` 下有 `billing/pom.xml`、`cost-model/pom.xml`、`dashboard/pom.xml` = **3 个**
   - 但矩阵 `:100` 子模块说明只写「cost-model / dashboard」，**漏了 `billing`**
   - 交叉证据：`README.md:215` 把 `finops-billing` **列为独立组件**；根 `pom.xml:63-65` 声明了 **3 个** finops 模块
   - → 该项合并应扣 **2** 行，文档只扣 1 行
2. **`karmada` failover 内部 2 个子模块合并为 1 行，未计入扣减**
   - 实测：`karmada/failover/api/go.mod` + `karmada/failover/engine/go.mod` = 2 个构建单元，矩阵中为「karmada-failover」**1 行**
   - → 应再扣 **1** 行

**正确算式**：46 − 2（governance 3→1）− 2（finops 3→1）− 1（karmada failover 2→1）= **41 行**，与实测吻合。

**影响**：`README.md:21`、`ROADMAP.md:9`、`docs/模块数口径定义.md` 均引用「矩阵实列 43」，该数字在四份文档间传播，需一并更正为 41（或明确 44 含规划项）。

### 🔴 缺口 2：`component-maturity.md` 有 3 行内容已过时（且**全部是低估**）

| 组件 | 矩阵声称 | 实测代码 | 判定 |
| --- | --- | --- | --- |
| **vector-engine** | 「骨架（**内存 Mock 默认**）」<br>「未启用**自动回退**并告警」 | `internal/config/config.go:52` → `StoreType: "milvus"`（**默认 milvus**）<br>`main.go:60,140,166-177` → mock 需**显式**指定；milvus 模式未带 build tag 时 **fail-fast 拒绝启动** | ❌ 过时 |
| **llmops** | 「骨架（Mock 默认）」<br>「`LLMOPS_STORE_TYPE=mock` 默认」 | `config/settings.py:54` → `storeType` 默认 **`"mlflow"`**<br>`settings.py:86` → `AI_MODE` 仅在**显式设环境变量**时覆盖<br>`services/registry.py:50-51` → 仅 `isMock` 才 `_build_mock()` | ❌ 过时 |
| **operations-api** | 「**缺鉴权中间件** / CORS / Prometheus 指标」 | `api/routers/contracts.py:23` 引入 `getAuthContext, requireAdmin`；`:40` `Depends(getAuthContext)`；`:43/114/133/153/173` `requireAdmin(ctx)` | ❌ 过时（鉴权已接入） |

**方向性观察**：4 处失准（含缺口 1）**全部指向"文档比实际差"**，而非夸大。结合矩阵自述「评级日期 2026-08-26」与文档「更新日期 2026-09-15」的错位，可判断根因是：**R17–R23 轮审查与安全加固把代码改强了（fail-fast、真实默认、补鉴权），但矩阵未回刷**。

> 这一点对项目**有利**：说明不存在"把骨架吹成成品"的夸大问题，而是文档维护滞后。

### 🟠 缺口 3：Trino 460「兼容性修复」未闭合，HEAD 状态仍无法启动 Trino

**证据链**：

1. HEAD 提交 `837472c8` 标题为 `fix(docker): Trino 460兼容性修复`
2. 其 `--stat` 显示只改 3 个文件：
   `docker/trino/catalog/iceberg.properties`(2±)、`docker/trino/config.properties`(6±)、`docs/E2E-VERIFICATION-REPORT.md`(+148)
   → **完全未触及 `kafka.properties`**
3. 读 **HEAD 已提交版**内容，两处仍是 Trino 460 会拒绝的配置：
   - `kafka.properties`：`kafka.bootstrap-servers=kafka:9092`、`kafka.table-names=.*`
   - `iceberg.properties`：`hive.config.resources=...`
4. 项目自己的报告也承认未完成：
   - `docs/E2E-VERIFICATION-REPORT.md:144` —「**下一步建议 1**：更新 kafka.properties 使用 `kafka.nodes`」
   - 同文件 `:128` —「iceberg/kafka catalog **验证时临时禁用**，已恢复」
5. **实测后果**：Trino 460 容器 `exit 100`，报错正是
   `Configuration property 'hive.config.resources' was not used` 与
   `Invalid configuration property kafka.nodes ... / 'kafka.bootstrap-servers' was not used`

**影响**：`E2E-VERIFICATION-REPORT.md:114` 却写「✅ 修复 4 个 Trino 460 兼容问题」。**文档结论与代码事实不符**：按仓库文档部署 Trino 会失败。

> 现状：工作区中这两个文件**已被修改**（未提交），改动正是补齐这两处。

### 🟠 缺口 4：发布 tag 时序倒挂

| tag | 指向 commit 日期 |
| --- | --- |
| `v2.0.0-ga` | **2026-08-08** |
| `v1.0.0` | **2026-08-09** |
| `v2.0.0` | 2026-08-13 |

「GA」标签指向的提交**早于** `v1.0.0` 标签一天。结合首次提交为 `2026-08-03`，可判断 tag 为**事后回填**，不是真实发布节点。
**影响**：任何以 tag 为基准的发布追溯（发布间隔、交付节奏）都会得出错误结论。

### 🟠 缺口 5：E2E「端到端验证」是在降级状态下完成的

`docs/E2E-VERIFICATION-REPORT.md:113-129` 自述：

| 项 | 状态 |
| --- | --- |
| 核心服务 | 仅 **4/5** 运行 |
| Redis | ❌ 未启动 |
| Kafka | ⚠️ unhealthy |
| Go catalog | ❌ 未启动（依赖下载超时） |
| iceberg / kafka catalog | ❌ 验证期间**临时禁用** |
| 查询内容 | `SELECT 1` / `current_date`（常量查询，未读业务表） |

→ 在 **2 个数据 catalog 关闭、3 个组件未就绪**的情况下，报告写「跨源查询 Trino → PostgreSQL ✅」。该表述不足以支撑"端到端打通"。

### 🟡 缺口 6：`docs/项目体检报告.md` 已过时

该报告（2026-08-13）称「**46% 的菜单是占位页 / 16 个指向 `Roadmap.vue`**」。
实测 `frontend/src/router/index.ts` 中 `Roadmap` 仅 **3 处，且全为注释**：

```
L273 // 批次12新增：基础设施层 5 个页面（替换原 Roadmap 占位…）
L304 // 批次12新增：引擎层 7 个页面（替换原 Roadmap 占位…）
L347 // 批次12新增：治理/开发层 4 个页面（替换原 Roadmap 占位…）
```

5+7+4 = **16 个占位页已被真实页面替换**。→ 该报告结论在 HEAD 已不成立，但仍留在 `docs/` 并被引用。

### 🟡 缺口 7：测试计数口径互不相等

| 来源 | Java | Go | Python | 合计 |
| --- | --- | --- | --- | --- |
| `README.md` 徽章 | — | — | — | **7,636** |
| `README.md` 正文 | — | — | — | 「**6830+** 后端测试」 |
| 第三方评估报告 | 4,256 | 771 | 2,609 | 7,636 |
| **本报告实测**（`git grep`） | **4,211** | **771** | **3,440** | **8,422** |

量级可信，但**四个口径互不相等**，且本报告 Python 计数比第三方多 831（扫描范围是否含 `tests/` 未声明）。→ 建议把统计命令固化进 `KNOWN-FAILURES.md`，否则永远无法对账。

### 🟡 缺口 8：已知失败 50 个，且会阻断标准构建入口

`docs/KNOWN-FAILURES.md:19`：7 个 `vue-tsc` 类型错误 + 43 个 vitest 失败，清零期限 v2.2.0。
由于 `frontend/package.json` 的 `build` = `vue-tsc --noEmit && vite build`，**这 7 个类型错误会让标准构建入口直接失败**。

### 🟡 缺口 9：源码注释与自身实现不一致（轻微）

`platform/llmops/llmops/config/settings.py:14` 注释写「AI_MODE 全局 AI 模式: mock / real（**默认 mock**）」，
但 `:86` 的实现是 `os.getenv("AI_MODE", "")` ——**未设置时不覆盖**，即不存在"默认 mock"。
同文件 `:8` 的「LLMOPS_STORE_TYPE（默认 mlflow）」才与代码一致。
（`ml-platform` 同类注释无此问题。）

### 🟠 缺口 10：技术栈版本与文档系统性不符（Spring Boot / Go）

| 维度 | 文档声称 | 实测构建配置 | 判定 |
| --- | --- | --- | --- |
| Spring Boot | `README.md:58,310`、`component-maturity.md:16 等 ~20 处` → **3.2.x** | 根 `pom.xml:10` → `spring-boot-starter-parent` **4.1.1**；子模块均继承 | ❌ 文档滞后 |
| Go | 「Go 1.22+」 | 全部 `go.mod` → `go 1.26`（即**构建要求 1.26+**，1.22 无法编译） | ⚠️ 表述失准 |
| Java | 「Java 17」 | `pom.xml:23` → `17` | ✅ 准确 |

- **升级证据**：`git log -- pom.xml` 含提交 `d3c348e0 feat(upgrade): Spring Boot 3.2.6→4.1.1 大版本升级 + 覆盖率门禁实化 35/15→40/18`——升级真实发生，文档未回刷。
- **影响**：任何人按 README 的"Spring Boot 3.2.x"理解平台会得到错误的技术画像；按"Go 1.22+"准备环境会构建失败。

### 🟡 缺口 11：构建/测试实测结果与文档声称不符（本轮实测）

本轮实际执行（JDK 17、Go 1.26.6、Node 26.8.2、Python 3.14.3）：

| 语言 | 命令 | 实测结果 | 文档声称 | 判定 |
| --- | --- | --- | --- | --- |
| Java 编译 | `mvn -B package -DskipTests` | **BUILD SUCCESS**，25/25 模块，06:58 min | 「mvn package 全项目通过」 | ✅ 属实 |
| Java 单测 | `mvn -B -pl platform/sql-gateway test` | **BUILD FAILURE**：1219 用例，**4 Errors**（`SqlGatewayControllerTest` 因"缺少租户上下文"，见 `:428`） | 「Java 编译错误已清零」 | ⚠️ 编译属实清零，但**单测仍有 4 个 Error 未披露** |
| Go | `go build ./...`（vector-engine） | **exit 0** | — | ✅ 可构建 |
| Go 测试 | `go test ./...`（vector-engine） | **7/7 包 ok** | — | ✅ 通过 |
| Python 语法 | `compileall`（llmops/ml-platform/business-portal/open-api-catalog/asset-exchange） | **exit 0，零错误** | — | ✅ 语法通过 |
| Python 单测 | `pytest -q`（llmops） | **3 failed / 101 passed**（`test_cors.py` 三处） | — | ⚠️ 存在未记录失败 |
| 前端类型 | `vue-tsc --noEmit` | **恰好 7 个错误**，与 `KNOWN-FAILURES.md` 逐条一致 | 「7 个待修复」 | ✅ 属实（且阻断 `npm run build`） |

**新增需披露的测试失败**（文档未列出）：

1. `platform/sql-gateway`：`SqlGatewayControllerTest` 4 个 Error（standalone MockMvc 未注入租户上下文，触发 `requireTenant` 抛 `IllegalStateException`）——属于**测试环境缺口**，非生产代码 bug。
2. `platform/llmops`：`tests/test_cors.py` 3 个 Failed（CORS 中间件断言）——**文档未列入已知失败清单**。

> 环境备注：本机 PATH 默认 `java` 为 **JDK 8**，与项目要求的 JDK 17 冲突；构建须显式指定 `JAVA_HOME=E:\dev-tools\jdk17.0.20_8`。这是一个**可复现性隐患**（未在开发文档中提示）。

---

## 四、未核实事项（不下结论）

| 事项 | 未核实原因 |
| --- | --- |
| 源码总行数「441,304 行」 | 未全量统计 |
| 「22 组件真实可部署」的**运行验证** | 仅核对了配置与源码结构，未实际启动各组件 |
| 各语言测试**实际通过率 / 覆盖率** | 未运行 `mvn test` / `pytest` / `go test` / `vitest` |
| 「四环境零改动交付」 | 无真实环境，无法验证 |
| 「6830+ 测试 0 失败」类表述 | 未执行；且 `KNOWN-FAILURES.md` 已自认有 50 个已知失败 |
| arm64 / 信创适配现状 | 未验证 |
| 性能基线（P99=18.34ms 等） | 未复现 |

**因此本报告不给综合评分。**

---

## 五、建议（按性价比排序）

### P0 — 修正会误导人的文档事实

1. **更正矩阵实列数**：`docs/component-maturity.md:106` 的算式与结论由 43 → **41**；同步修正 `README.md:21`、`ROADMAP.md:9`、`docs/模块数口径定义.md` 中对 43 的引用。
2. **补 `finops/billing`**：在 `component-maturity.md:100` 的子模块说明中加入 `billing`，与 `README.md:215` 和根 `pom.xml:63-65` 对齐。
3. **回刷 3 行过时分级**：vector-engine（默认已改 milvus + fail-fast）、llmops（默认 mlflow）、operations-api（鉴权已接入）。
4. **给 `docs/项目体检报告.md` 加过时横幅**或按 HEAD 重写（其「16 个占位页」结论已失效）。

### P0 — 补完 Trino 460 配置并实证

5. `docker/trino/catalog/kafka.properties` → 改用 `kafka.nodes`；`iceberg.properties` → 删除 `hive.config.resources`。
6. **启动容器实测通过后**，再回填 `E2E-VERIFICATION-REPORT.md` 的结论行，避免"文档说修好了、实际起不来"。

### P1 — 让验证结论站得住

7. **重定义「端到端验证」通过标准**，建议至少包含：
   - 全部 catalog 启用（不允许"临时禁用"计入通过）
   - 真实业务表读写（不接受 `SELECT 1`）
   - 校验返回 `rows` 内容，而非仅看 `status`
   - 记录当时的组件启动清单
8. **固定测试计数口径**：把统计命令写入 `KNOWN-FAILURES.md`，并统一 README 徽章与正文数字。

### P1 — 仓库卫生

9. **清理工作区**：当前 17 个脏文件（UI 主题改动 + 修复 + 新文档）分类提交；`AHEAD_OF_ORIGIN=1` 的提交推到远端。
10. **发布 tag 规范化**：现有 tag 时序倒挂，建议以「含日期 + 语义化版本 + 指向 release 提交」的方式重建，或在文档中声明 tag 为回填。

### P2 — 可选

11. 修正 `platform/llmops/.../settings.py:14` 的注释（"默认 mock" 与实现不符）。
12. 已知失败 50 个（7 类型错误 + 43 vitest）建议设置更早的清零节点——类型错误会阻断 `npm run build`。

### P0（新增，来自缺口 10/11）

13. **回刷技术栈版本**：把 `README.md`、`component-maturity.md`、`development-guide.md` 中的「Spring Boot 3.2.x」→ **4.1.1**、「Go 1.22+」→ **1.26+**。
14. **把本轮实测发现的失败登记进 `KNOWN-FAILURES.md`**：
    - `sql-gateway` 的 `SqlGatewayControllerTest` 4 个 Error（测试缺租户上下文）
    - `llmops` 的 `test_cors.py` 3 个 Failed
15. **在 `development-guide.md` 注明 JDK 依赖**：构建须 JDK 17+，本机若默认是 JDK 8 需显式 `JAVA_HOME`。

---

## 六、附录：复现命令

本报告所有结论均可用以下命令复现（在仓库根执行）：

```bash
# 组件数口径
find platform -name pom.xml -not -path '*/target/*' | wc -l   # 24
find platform -name go.mod | wc -l                            # 10
find platform -name pyproject.toml | wc -l                    # 12
ls -d platform/*/ | wc -l                                     # 38

# CI / Chart
ls .github/workflows/ | wc -l                                 # 15
find design/deploy/charts -name Chart.yaml | wc -l            # 88

# 缺口 1：finops 实为 3 个构建单元
ls platform/finops/*/pom.xml                                  # billing / cost-model / dashboard

# 缺口 2：vector-engine 默认为 milvus（非 mock）
grep -n 'StoreType:' platform/vector-engine/internal/config/config.go

# 缺口 2：llmops 默认 mlflow（非 mock）
grep -n 'storeType: Literal' platform/llmops/llmops/config/settings.py

# 缺口 2：operations-api 已接入鉴权
grep -n 'getAuthContext\|requireAdmin' platform/operations-api/operations_api/api/routers/contracts.py

# 缺口 3：HEAD 版 Trino 配置仍不兼容
git show HEAD:docker/trino/catalog/kafka.properties
git show HEAD:docker/trino/catalog/iceberg.properties

# 缺口 4：tag 时序
git log -1 --format='%ad' v2.0.0-ga   # 2026-08-08
git log -1 --format='%ad' v1.0.0      # 2026-08-09

# 缺口 6：占位页已被替换（仅剩注释）
grep -n 'Roadmap' frontend/src/router/index.ts

# 缺口 7：测试计数
git grep -h -c '@Test' -- 'platform/**/*.java' | awk '{s+=$1} END {print s}'  # 4211
git grep -h -c 'func Test' -- '*_test.go' | awk '{s+=$1} END {print s}'       # 771
```

---

*本报告为独立第二方只读核查，不代表项目方立场。所有"未核实"事项均未下结论；所有结论均附可复现证据。*
