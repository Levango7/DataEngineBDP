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

### 5.1 待处理（39 条，待你贴 Security tab 清单后逐条填）

| CVE | 包 | 已装版本 | 严重度 | 分类 | 处置 | 日期 | PR/豁免 |
|---|---|---|---|---|---|---|---|

### 5.2 已修复

| CVE | 包 | 旧版 → 新版 | 处理日期 | PR 链接 |
|---|---|---|---|---|

### 5.3 已关闭（僵尸/不影响）

| CVE | 包 | 关闭理由 | 日期 |
|---|---|---|---|

---

## 六、已知局限

- 本机无法访问 GitHub Security tab（服务端配额限制），39 个告警的具体 CVE ID、包名、版本、修复版必须由你（有 GitHub 权限）贴出清单，再逐条填入 §五。
- 镜像基座 CVE（`docs/trivy-image-ignore.yaml` 已记 18 条，到期 2026-12-01）与本文的依赖 CVE 是两张表，不混用。

---

## 相关文件

- `.github/dependabot.yml` — Dependabot 配置（维护模式，仅安全更新）
- `.github/workflows/security.yml` — Trivy 依赖与镜像扫描，CRITICAL/HIGH 门禁
- `scripts/trivy-sarif-report.py` — SARIF 渲染与门禁脚本
- `docs/trivy-image-ignore.yaml` — 镜像 CVE 豁免账（模板参考）
- `SECURITY.md` — 安全政策与报告流程
