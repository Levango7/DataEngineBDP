# 依赖漏洞 triage 运营文档（DataEngineBDP）

本文记录 GitHub Security tab / Dependabot 依赖漏洞告警的**处置口径、分类规则与豁免模板**。适用场景：Security tab 出现新告警（如默认分支出现 39 条告警：2 CRITICAL / 4 HIGH / 28 MEDIUM / 5 LOW），需要在不破坏 CI 门禁的前提下逐条决策。

---

## 一、现状基线（已落地）

| 项目 | 状态 | 说明 |
|---|---|---|
| Dependabot 安全更新 | ✅ 已开启 | `.github/dependabot.yml` 移除了全部 version-updates 条目，依赖面**只保留安全更新一个入口**。安全更新由仓库设置 `automated-security-fixes` 驱动，出现真实漏洞会自动开安全 PR，不受本文件影响。 |
| CI Trivy 依赖门禁 | ✅ 已落地 | `.github/workflows/security.yml`：`trivy fs --severity CRITICAL,HIGH --ignore-unfixed`，`exit-code: '1'`，仅 CRITICAL/HIGH 阻断。MEDIUM 只进 SARIF 不阻断。 |
| SARIF 渲染脚本 | ✅ 已落地 | `scripts/trivy-sarif-report.py`：把 Trivy SARIF 渲染成按严重级排序的漏洞清单，带 `--gate` / `--fail-on` 门禁模式，文件缺失/解析失败/runs 为空区分 rc=3（环境失败）与 rc=0（无漏洞）。 |
| 镜像 CVE 豁免 | ✅ 已落地 | `docs/trivy-image-ignore.yaml`：18 条镜像 CVE，`expired_at: 2026-12-01`，到期未清自动重新判红。 |

> **注意**：GitHub Security tab 的告警是按 analysis key 累积的，历史上会出现来自已失效 commit 的僵尸告警。triage 前务必先确认告警是否作用于当前 HEAD（见 §三）。

---

## 二、分类与处置规则

收到告警后，按以下顺序分类，每条只落一个类别：

### 2.1 可直接修（升级依赖）

**条件**：上游已发布修复版本，且本仓直接或间接引用了受影响版本。

**处置**：

1. 查看 Dependabot 是否已自动开安全 PR（Security → Dependabot alerts → 该 CVE 的 "Dependabot security updates" 字段）。
2. 若已开 PR：审查 diff，仅升级受影响版本，不顺带拉大版本；合并后告警应自动关闭。
3. 若未开 PR：手动升级对应 `pom.xml` / `go.mod` / `requirements*.txt` / `package.json`，提交并推，触发 CI Trivy 门禁复验。

**记录**：在本文 §五「已修复」表格追加一行（CVE、包、旧版→新版、处理日期、PR 链接）。

---

### 2.2 间接依赖，不影响本仓（无需升级）

**条件**：漏洞命中的是**传递依赖**，且本仓代码不调用受影响的类/函数，或受影响版本本仓未引用（`ignore-unfixed` 口径下 Trivy 不应报它，若报了通常是 SARIF 僵尸）。

**处置**：

1. 用 `scripts/trivy-sarif-report.py --gate --fail-on 0 <sarif>` 确认该条是否作用于当前 HEAD 的具体 `pom.xml` / `go.mod` 等清单文件。
2. 若清单文件路径不存在于当前 HEAD，标记为“僵尸告警”，不处理。
3. 若存在但本仓不调用受影响 API，可在 `docs/trivy-image-ignore.yaml` 风格下加一条**长期豁免**（不设 expired_at，附理由）。

---

### 2.3 上游无修复版（暂不可修）

**条件**：Trivy `Fixed Version` 为空，或上游尚未发布修复。

**处置**：

1. 该条不会被 `--ignore-unfixed` 门禁阻断（CI 已过滤）。
2. 加入 `docs/trivy-image-ignore.yaml` 风格的豁免表，`expired_at` 设为距今 1–3 个月（视严重度），到期未清自动重新判红，防止静默烂掉。
3. 每月复核一次，上游发布修复后按 §2.1 升级。

---

### 2.4 僵尸告警（历史 commit 遗留）

**条件**：告警作用于已失效的 commit，不作用于当前 HEAD。

**处置**：不处理，不豁免。GitHub Security tab 上可手动关闭或等 Dependabot/Trivy 自然下架。记录在本文 §五「已关闭（僵尸）」以免重复 triage。

---

## 三、triage 作业流（每条告警）

1. **打开告警**：GitHub → Security → Dependabot alerts，记录 CVE ID、包名、已装版本、严重度。
2. **确认作用域**：本地跑 `scripts/trivy-sarif-report.py --gate --fail-on 0 <最新 SARIF>`，或在 CI artifact 中下载 `trivy-fs-detail.json`，核对该条命中的清单文件是否在当前 HEAD。
3. **分类**：按 §二四类之一入桶。
4. **执行**：
   - 可直接修 → 升级依赖 → 提交 → CI 验证 → 告警关闭。
   - 间接依赖/无修复/僵尸 → 加豁免或标记不处理。
5. **记录**：在 §五相应表格追加一行，附 CVE、包、版本、处置、日期、PR/豁免链接。

---

## 四、豁免条目模板（仿 `docs/trivy-image-ignore.yaml`）

若需在仓里记录长期豁免，按以下 YAML 追加（文件位置可沿用 `docs/trivy-image-ignore.yaml` 或新建 `docs/dependency-vuln-exemptions.yaml`）：

```yaml
vulnerabilities:
  - id: CVE-XXXX-YYYY
    package: org.example:lib-name
    installed: "1.2.3"
    fixed: "1.2.4"            # 空表示上游无修复
    severity: HIGH
    reason: "传递依赖，本仓未调用受影响 API；或上游暂无修复版本"
    expired_at: 2026-12-31    # 到期未清自动重新判红；长期豁免省略本行并附理由
    source: "GitHub Security tab / Dependabot alert #NNN"
```

> **原则**：到期日统一设较短（1–3 个月）。到期若还在，要么修、要么续期并附新理由，不能把 expired_at 无限往后挪——挪日期等于把门禁重新关掉。

---

## 五、告警处置台账

### 5.1 待处理（当前 0 条）

> **2026-10-23 订正——下面这段原话是错的，原文保留以示留痕，不作数。**
>
> 原文：「Dependabot alerts 页面（`Security → Dependabot alerts`）已核对：Open 未解决 = 0，Closed 已关闭 = 0。仓库当前无未处理的依赖告警。」
>
> **实测（同一 API，2026-10-23）**：`GET /repos/{owner}/{repo}/dependabot/alerts?state=open` 返回 **30 条**未关闭告警——严重度分布 **HIGH 4 / MEDIUM 24 / LOW 2**，按「advisory × package」去重后 **23 条**。所以「Open = 0」不成立。
>
> **为什么会错**：这句话写下的时点（2026-10-10 那批）确实清过一轮，但 Dependabot 的安全告警是**按 analysis key 累积**的——仓库后来新引入的依赖（以及上游新发布的 advisory 覆盖到既有版本）会继续产生 open 告警，而本文件不会自己更新。**教训：这条数字必须现查，不能引用历史结论。**
>
> 复核命令（需有 Security 读权限）：
>
> ```
> gh api "repos/<owner>/<repo>/dependabot/alerts?state=open&per_page=100" --jq 'length'
> gh api "repos/<owner>/<repo>/dependabot/alerts?state=open&per_page=100" \
>   --jq '[.[].security_advisory.severity] | group_by(.) | map("\(.[0])=\(length)") | join(" ")'
> ```
>

**2026-10-23 分诊结果（30 条告警 / 23 个去重条目）**

| 分类 | 条目 | 处置 |
|---|---|---|
| ① 已修（本批） | `echarts`（GHSA-fgmj-fm8m-jvvx，→ 6.1.0）、`@vitest/mocker`（GHSA-qxg6-89mg-rqv8 / GHSA-xp26-3454-mjgp，→ 4.1.12）、`pytest`（GHSA-6w46-j5rx-g56g，→ 9.0.3，4 份清单）、`python-multipart`（GHSA-v9pg-7xvm-68hf，→ 0.0.31）、`github.com/quic-go/quic-go`（GHSA-vvgj-x9jq-8cj9，→ 0.59.1）、`poi-ooxml`（GHSA-gmg8-593g-7mv3，→ 5.5.1） | 见 §5.2 |
| ② 僵尸 / 已在 HEAD 修掉 | `scikit-learn`（GHSA-jw8x-6495-233v，HEAD 已是 `>=1.5.0`）、`pytest` 在 `platform/business-portal` 与 `platform/llmops` 两处（HEAD 已是 `>=9.0.3`） | 见 §5.3 |
| ③ 上游无修复版 | `org.lz4:lz4-java`（7 条，含 2 HIGH）、`mysql:mysql-connector-java`（1 条 HIGH） | `docs/dependency-vuln-exemptions.yaml` |
| ④ 不可达 / 升它会打穿别的组件 | `black`（受影响面是本仓未使用的 GitHub Action）、`io.debezium:debezium-core`（跨三个大版本，连接器绑定 1.9.x）、`brace-expansion`（5.x 一支）、`postcss-selector-parser`、`uuid` | `docs/dependency-vuln-exemptions.yaml`（每条都写了实测反证） |

> **③④ 都不是「不修」，是「本仓现在修不动」**：每一条都带 `expired_at`，到期未清会重新判红；续期必须同时说明上游进展，不许只挪日期。

| CVE | 包 | 已装版本 | 严重度 | 分类 | 处置 | 日期 | PR/豁免 |
|---|---|---|---|---|---|---|---|
| GHSA-fgmj-fm8m-jvvx 等 | 见上表 | — | — | ① | 已升 | 2026-10-23 | 本批 PR |
| GHSA-mcr4-qmvw-px4g 等 7 条 | org.lz4:lz4-java | 1.8.1 | HIGH×2 / MEDIUM×4 / LOW×1 | ③ | 限期豁免 | 2026-10-23 | dependency-vuln-exemptions.yaml |
| GHSA-m6vm-37g8-gqvh | mysql:mysql-connector-java | 8.0.33 | HIGH | ③ | 限期豁免 | 2026-10-23 | dependency-vuln-exemptions.yaml |
| GHSA-cg8m-r9f2-5wm2 | black | 26.5.1 | HIGH | ④ | 豁免（路径不可达） | 2026-10-23 | dependency-vuln-exemptions.yaml |
| GHSA-hvw5-3mgw-7rcf | io.debezium:debezium-core | 1.9.7.Final | MEDIUM | ④ | 豁免（跨大版本，带解锁条件） | 2026-10-23 | dependency-vuln-exemptions.yaml |
| GHSA-q2hr-2g5m-vwhr | brace-expansion (5.x 一支) | 5.0.9 | MEDIUM | ④ | 豁免（强推打穿 minimatch，已实测） | 2026-10-23 | dependency-vuln-exemptions.yaml |
| GHSA-rj75-hqrm-r3gf | postcss-selector-parser | 6.1.5 | MEDIUM | ④ | 豁免（强推打穿 eslint-plugin-vue，已实测） | 2026-10-23 | dependency-vuln-exemptions.yaml |
| GHSA-w5hq-g745-h8pq | uuid | 9.0.1 | MEDIUM | ④ | 豁免（仅 storybook 开发期使用） | 2026-10-23 | dependency-vuln-exemptions.yaml |
| GHSA-834v-8h66-3h79（happy-dom） | happy-dom | 20.14.6 → **21.0.4** | HIGH | ①（dev-only，修复只在大版本 21.0.4） | 已升：`frontend/package.json` + lock 两行，实测双版本 vitest FAIL 清单逐条一致 | 2026-10-24 | PR #736（并行线落地同内容；本批保留验收与对账） |

**2026-10-24 复核（#726 合入 main `b63bb529` 之后，同一 API 现查）**：`state=open` 由 30 降到 **20**。构成可对账：豁免账 13 个 id（lz4-java 7 条 + mysql + black + debezium + brace-expansion + postcss-selector-parser + uuid）+ 僵尸 3 条（`platform/business-portal` 与 `platform/llmops` 的 pytest、`platform/ml-platform` 的 scikit-learn，HEAD 早已是修复版，等 Dependabot 重扫）+ 新增 4 条 = 20。新增的是两条新公告：`GHSA-wqmh-qccw-pqwf`（mlflow，HIGH，`<=3.17.1` → fix 3.17.2，命中 3 份 requirements）与 `GHSA-834v-8h66-3h79`（happy-dom，HIGH，`>=17.0.0, <=21.0.3` → fix 只有 21.0.4）。mlflow 属同 minor 补丁，已随 #736 升；happy-dom 是 `frontend/package.json` 直接声明的 devDependency（`^20.14.6`，vitest 的 peer 是 `*`），只有大版本修复。兼容性实测后按「真升」处理，理由与数据：在干净 worktree（基于 main `b63bb529`）先跑基线 `vitest run` = 2 failed files / 12 failed / 483 passed，再 `npm install happy-dom@^21.0.4` 重跑 = 同样 12 failed / 483 passed，且两份 FAIL 清单（剥 ANSI 后逐条排序）**diff 为空**；那 12 条是本机 Node 24 下 `localStorage` 未定义的存量红（theme.test.ts 11 条 + AuthDashboardAnalyze.test.ts 1 条），与本刀无关 ⇒ 判为无新增红，进 ① 类真升，不写豁免。

**一条口径提醒（本轮现查所得）**：fs 门禁（Security 的 `Trivy Vulnerability Scan` 与 `ci.yml` 的 `Trivy FS Scan`）没有挂任何 ignorefile（`.trivyignore` 在本仓不存在，`security.yml` 的 fs job 只传 `severity` 与 `ignore-unfixed`），所以「进依赖豁免账」**不等于**「门禁不红」——门禁只认版本。此刻 main 上这两条尚未判红（trivy 侧 advisory DB 收录滞后于 Dependabot），属**潜伏红**，先记账，不等它红起来才处理。

### 5.2 已修复

| CVE | 包 | 旧版 → 新版 | 处理日期 | PR 链接 |
|---|---|---|---|---|
| GHSA-fgmj-fm8m-jvvx（ECharts XSS） | echarts | 5.6.0 → 6.1.0 | 2026-10-23 | 本批 PR（`frontend/package.json` + 两个 workspace，同一份 lockfile） |
| GHSA-qxg6-89mg-rqv8 / GHSA-xp26-3454-mjgp（Vitest mock 注入与 Windows 路径穿越） | @vitest/mocker（随 vitest 家族） | 4.1.11 → 4.1.12 | 2026-10-23 | 本批 PR |
| GHSA-6w46-j5rx-g56g（pytest） | pytest | 8.3.4 / 8.1.1 → 9.0.3 | 2026-10-23 | 本批 PR（4 份清单；pytest-asyncio 同批抬到 1.4.0，否则不可解析） |
| GHSA-v9pg-7xvm-68hf（python-multipart） | python-multipart | 0.0.30 → 0.0.31 | 2026-10-23 | 本批 PR |
| GHSA-vvgj-x9jq-8cj9（quic-go QPACK） | github.com/quic-go/quic-go | 0.59.0 → 0.59.1 | 2026-10-23 | 本批 PR（`go mod tidy` 后 `go build ./...` 通过） |
| GHSA-gmg8-593g-7mv3（POI OOXML） | org.apache.poi:poi-ooxml | 5.2.5 → 5.5.1 | 2026-10-23 | 本批 PR（等价 dependabot #636） |
| GHSA-wqmh-qccw-pqwf（mlflow） | mlflow | 3.17.0 → 3.17.2 | 2026-10-24 | PR #736（`platform/business-portal`、`platform/llmops`、`platform/ml-platform` 三份 requirements 同步；同 minor 补丁，CI 的 Python Test 覆盖这三组件） |
| GHSA-834v-8h66-3h79（happy-dom） | happy-dom | 20.14.6 → 21.0.4 | 2026-10-24 | PR #736（`frontend/package.json` + `frontend/package-lock.json`；vitest 双版本 FAIL 清单逐条一致，本地 495 例微测件与基线同集） |
| CVE-2026-108106 / CVE-2026-93451（snappy-java） | org.xerial.snappy:snappy-java | 1.1.10.5 / 1.1.10.7 → 1.1.10.9 | 2026-10-29 | PR #739（根 `pom.xml` dependencyManagement 统一抬版；`mvn dependency:tree` 实测 flink-cdc / metadata-collector / encaps-layer 三处解析到 1.1.10.9） |
| CVE-2026-102554（guava） | com.google.guava:guava | 27.0 / 30.1.1 / 32.0.0 / 33.4.8-jre → 33.7.2-jre | 2026-10-29 | PR #739（同上；本仓 9 个 pom 均为传递依赖、无直接声明。maven 基座自带的 33.6.0-jre 改不了，另记 `docs/trivy-image-ignore.yaml` 限期豁免） |
| CVE-2026-92392（curl/libcurl） | nginx:alpine 基座内 curl | 8.22.0-r0 → 8.23.0-r0 | 2026-10-29 | PR #739（`frontend/Dockerfile` 运行时层 `apk upgrade --no-cache curl`；Alpine v3.24 main 仓 APKINDEX 实测 `V:8.23.0-r0`，并记基座豁免） |
| CVE-2026-118256（门禁侧记作 CVE-2026-118257，上游 id 有碰撞） | vue-i18n + @intlify/core-base/shared | 10.0.8 → 11.4.16 | 2026-10-30 | PR #739（跨大版本；`devMl.{en-US,zh-CN}.json` 的 JSON 示例值改用 `{'}{'}…{'}}'}` 字面量转义，`t()` 渲染值与原字符串一致；双版本 vitest FAIL 清单逐条相等 + `vue-tsc`/`vite build` rc=0） |

### 5.3 已关闭（僵尸/不影响）

| CVE | 包 | 关闭理由 | 日期 |
|---|---|---|---|

---

## 六、已知局限

- 本机（本会话）无法直接访问 GitHub Security tab（服务端配额限制 429/410），但已由拥有 GitHub 权限的人现场核对：Dependabot alerts Open = 0，Closed = 0，仓库当前无未处理依赖告警。
  > **2026-10-23 订正**：这条同样不成立——原文保留留痕。实测同一 API 为 **30 条 open**（HIGH 4 / MEDIUM 24 / LOW 2），复核命令与分诊结果见 §5.1。另：本轮是通过 **REST API**（`gh api .../dependabot/alerts`）取的数，不是网页 Security tab，因此「本机无法访问 Security tab」这一限制**不影响取数**——该局限的表述本身也过时了。
- 镜像基座 CVE（`docs/trivy-image-ignore.yaml` 已记 18 条，到期 2026-12-01）与本文的依赖 CVE 是两张表，不混用。
- **2026-10-23 新增**：*依赖包*的限期豁免统一记在 `docs/dependency-vuln-exemptions.yaml`（新文件），本文只做分诊与结论；两处不要各记一份，免得对不上。
- **2026-10-23 新增**：`(owner)/(repo)` 的 Dependabot 安全更新开关（`automated_security_fixes`）本轮现查**不在 `security_and_analysis` 返回里**（即未启用），这解释了为什么 `black` / `pytest` / `echarts` 这些有修复版的告警长期没有 Dependabot 自己开安全 PR。是否重新打开属仓库设置变更，本批未动，留作独立决定。

---

## 相关文件

- `.github/dependabot.yml` — Dependabot 配置（维护模式，仅安全更新）
- `.github/workflows/security.yml` — Trivy 依赖与镜像扫描，CRITICAL/HIGH 门禁
- `scripts/trivy-sarif-report.py` — SARIF 渲染与门禁脚本
- `docs/trivy-image-ignore.yaml` — 镜像 CVE 豁免账（模板参考）
- `SECURITY.md` — 安全政策与报告流程
