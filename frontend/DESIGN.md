---
version: alpha
name: shuqing-dataengine-console-design
description: "政企私有化大数据控制台的视觉契约：浅色档以 SAP 式企业应用骨架为底——白底卡片 + 单一蓝 #0057d2 + 扁平色带（#eaecee）取代阴影表达层级；正文用 Stripe 式藏青墨 #0d253d，金额/KPI/SQL 结果一律等宽数字。暗色档沿用既有 Obsidian 中性色（#101317 / #171a20），主按钮为浅底深字。可交互边界必须 ≥3:1。整体气质：克制、可信、高密度，不做营销页式渐变与玻璃。"

colors:
  bg-base: "#f5f6f7"
  bg-surface: "#ffffff"
  bg-subtle: "#fafbfc"
  bg-muted: "#eaecee"
  surface-1: "#ffffff"
  surface-2: "#f5f6f7"
  surface-3: "#eaecee"
  text-primary: "#0d253d"
  text-secondary: "#273951"
  text-tertiary: "#55647a"
  text-disabled: "#9aa3b0"
  accent: "#0057d2"
  accent-hover: "#0040b0"
  accent-active: "#00348f"
  accent-on: "#ffffff"
  accent-soft: "#ebf3fd"
  border-default: "#dbe1e6"
  border-subtle: "#e9ecf1"
  border-control: "#475e75"
  border-strong: "#475e75"
  border-focus: "#0057d2"
  success: "#0e7a4a"
  warning: "#b45309"
  danger: "#c62828"
  # v3 外壳：画布加深 + 深色导航 + 专属底栏 + 8 组主题色
  canvas: "#e9edf4"
  nav-bg: "#16202e"
  nav-ink: "#eef2f8"
  nav-muted: "#a9b6c8"
  statusbar-bg: "#dfe4ec"
  statusbar-ink: "#3c4452"
  group-infra: "#7cb1ff"
  group-engine: "#5fd3c0"
  group-governance: "#c0a6ff"
  group-devtools: "#ffc861"
  group-tenant: "#8fe3a8"
  group-intelligent: "#ff9fb2"
  group-operations: "#79dff5"
  group-platform: "#c8d3e3"

colorsDark:
  canvas: "#101317"
  nav-bg: "#06090d"
  nav-ink: "#eef2f8"
  nav-muted: "#a9b6c8"
  statusbar-bg: "#1a1e25"
  statusbar-ink: "#bac3d0"
  bg-base: "#101317"
  bg-surface: "#171a20"
  bg-subtle: "#0b0d10"
  bg-muted: "#1a1e24"
  surface-1: "#171a20"
  surface-2: "#1d2128"
  surface-3: "#23282f"
  text-primary: "#e7eaf0"
  text-secondary: "#adb5c2"
  text-tertiary: "#8a93a2"
  accent: "#7fb0ff"
  accent-hover: "#9cc4ff"
  accent-active: "#6ba3ff"
  accent-on: "#0b1220"
  accent-soft: "#16233c"
  border-default: "#2f353e"
  border-subtle: "#23272e"
  border-control: "#616b7a"
  border-strong: "#7d8797"
  border-focus: "#7fb0ff"

typography:
  family-sans: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"
  family-mono: "'JetBrains Mono', ui-monospace, SFMono-Regular, Consolas, monospace"
  family-numeric: "'JetBrains Mono', ui-monospace, 'PingFang SC', monospace"
  size-xs: { fontSize: 12px, lineHeight: 1.4 }
  size-sm: { fontSize: 13px, lineHeight: 1.5 }
  size-base: { fontSize: 14px, lineHeight: 1.5 }
  size-lg: { fontSize: 16px, lineHeight: 1.5 }
  size-xl: { fontSize: 20px, lineHeight: 1.3 }
  size-2xl: { fontSize: 24px, lineHeight: 1.25 }
  weight-regular: 400
  weight-medium: 500
  weight-semibold: 600
  weight-bold: 700

rounded:
  sm: "4px"
  md: "8px"
  lg: "12px"

spacing:
  1: "4px"
  2: "8px"
  3: "12px"
  4: "16px"
  6: "24px"
  8: "32px"

elevation:
  flat: "none（色带表达层级）"
  raised: "0 1px 2px rgb(13 37 61 / 0.06)"
  overlay: "0 8px 24px rgb(13 37 61 / 0.14)（仅弹层/下拉）"

components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.accent-on}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
    height: "32px"
    hover: "{colors.accent-hover}"
    active: "{colors.accent-active}"
    focusRing: "0 0 0 2px {colors.accent-soft}, 1px solid {colors.accent}"
  card:
    backgroundColor: "{colors.bg-surface}"
    border: "1px solid {colors.border-default}"
    shadow: "{elevation.flat}"
    rounded: "{rounded.md}"
  table-header:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text-secondary}"
    borderBottom: "1px solid {colors.border-subtle}"
  table-row:
    borderBottom: "1px solid {colors.border-subtle}"
    numericFont: "{typography.family-numeric}"
    fontVariantNumeric: "tabular-nums"
  input:
    border: "1px solid {colors.border-control}"
    rounded: "{rounded.sm}"
    focusBorder: "{colors.border-focus}"
  badge-soft:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent}"
---

# 数擎控制台设计契约（DESIGN.md）

> 事实源是 `src/styles/design-tokens.css`（组件全部按 `--ds-*` 消费）。本文件的 YAML 区是它的**镜像**，
> 二者不一致时以 CSS 为准并由 `scripts/check-design-sync.py` 判红。改任何色值都必须重跑
> `python3 scripts/check-design-contrast.py`（文本 4.5:1 / 可交互边界 3:1），不凭观感。

## 1. Overview

关键词：**克制、可信、高密度、扁平、单一强调**。

- 这是给客户机房里 operators 用的应用框架，不是给客户董事会看的营销页。层级靠**色带与发丝线**表达，不靠阴影堆叠，不靠玻璃拟态。
- 全站只有**一个品牌蓝**（浅色 `{colors.accent}`，暗色 `{colors.dark.accent}`）。历史遗留的第二强调色（紫 `#8b5cf6` 一族）已降级为「图表序列色」，不得再用于按钮/链接/焦点。
- 数字是这个产品的主角（KPI、计费、SQL 行数、延迟分位）。所有数字列走等宽数字，保证纵向对齐。

## 2. Colors

| Token（CSS：`--ds-…`） | 浅色值 | 角色 | 禁止用法 |
|---|---|---|---|
| `bg-base` | `#f5f6f7` | 应用画布（卡片之外） | 卡片底 |
| `bg-surface` | `#ffffff` | 卡片 / 内容表面 | 页面大底（会失去层次） |
| `surface-2` / `surface-3` | `#f5f6f7` / `#eaecee` | 表头带、分组色带（SAP 式扁平层级） | 用作文字底色以外的"装饰纹理" |
| `text-primary` | `#0d253d` | 标题、正文（Stripe 藏青墨） | 大面积纯黑 `#000` |
| `text-secondary` | `#273951` | 次级说明 | 表头（用 `text-secondary` 而非 tertiary） |
| `text-tertiary` | `#55647a` | 元数据、时间戳（实测 6.02:1 on 白） | 正文段落 |
| `accent` | `#0057d2` | 链接、焦点、激活、主按钮（实测 6.38:1 on 白；白字于其上同为 6.38:1） | 作大面积底色 |
| `accent-soft` | `#ebf3fd` | Badge/选中行的浅底（**独立令牌，不用 alpha 稀释**） | 代替 `accent` 做文字色 |
| `border-control` / `border-strong` | `#475e75` | 可交互边界（≥3:1，WCAG 1.4.11） | 装饰分隔 |
| `border-default` / `border-subtle` | `#dbe1e6` / `#e9ecf1` | 卡片描边 / 表格行发丝线 | 当"看得见"的控件边界（有意 <3:1） |

暗色档取值见 YAML `colorsDark`；中性色沿用既有 Obsidian 阶（`#101317/#171a20/#1d2128/#23282f`），**本轮不 churn**，只修不达标项。

## 3. Typography

- 中文优先系统栈（`PingFang SC` / `Microsoft YaHei`），代码与日志走 `JetBrains Mono` 族。
- 基线 14px / 1.5；层级 12 · 13 · 14 · 16 · 20 · 24 · 30。**不引入新的字号**，需要更大层级时用字重（500/600/700）而非新档。
- 数字：表格数值列、KPI、金额、耗时统一 `font-variant-numeric: tabular-nums` + `--ds-font-family-numeric`。
- 字重裁定：正文 400，强调 500，卡片标题 600，页面标题 600–700。禁用 300（中文细体在 GDI/DirectWrite 下发虚）。

## 4. Layout

- 8px 基准栅格；间距只用 `spacing` 档（4/8/12/16/24/32）。
- 内容区最大宽度由 `PageCard` 统一给；页面左右留白 ≥`spacing-4`。
- 节区节奏：`PageHeader → FilterBar → 主内容 → 次级区块`，区块之间 `spacing-6`；卡片内边距 `spacing-4`。
- 表密度：行高 40px（compact 档 32px）；表头 40px 带 `surface-2`。

## 5. Elevation & Depth

| 层级 | 表达 | 用于 |
|---|---|---|
| flat | 无色带 / 发丝线 | 卡片、表格、区块（默认，绝大多数场景） |
| raised | `0 1px 2px rgb(13 37 61 / 0.06)` | 仅在需要与背景分离的浮起卡片 |
| overlay | `0 8px 24px rgb(13 37 61 / 0.14)` | 弹窗、下拉、抽屉（唯一允许强阴影处） |

`--ds-shadow-glow` 属于历史"工程感"残留，只允许出现在 SQL 工作台/血缘图的运行态高亮，不得进表单与列表页。

## 6. Shapes

- 圆角 4（输入框/小徽标）· 8（按钮/卡片/控件，SAP 控件档）· 12（大容器）· 9999（仅头像/状态点）。
- 禁止 >12px 的"药丸化"卡片与大按钮；禁止同一屏混用 3 种以上圆角。

## 7. Components

- **button-primary**：`accent` 底 + `accent-on` 字 + 8px 圆角 + 高 32px；hover/active 用 `accent-hover/active`；focus 用双环（2px `accent-soft` 外环 + 1px `accent`）。
- **card / PageCard**：白底、1px `border-default`、**无阴影**、8px 圆角；标题走 `PageHeader`，不自己发明标题层级。
- **table**：表头 `surface-2` + `text-secondary`；行底线 `border-subtle`；数值列等宽数字；空态必须用 `EmptyState`，错误态必须可重试。
- **input**：边界 `border-control`（≥3:1），焦点 `border-focus`；错误态必须同时给文字说明，不允许只靠红色（色觉豁免）。
- **badge / StatusTag**：底色用独立 `-soft` 令牌（`accent-soft`/`success-soft`/`warning-soft`/`danger-soft`），文字用对应实色令牌。
- 状态覆盖要求：default / hover / focus / active / disabled / loading 六态齐了才算完成。

## 8. Do's and Don'ts

| ✅ Do | ❌ Don't（都对应真实事故） |
|---|---|
| 改色后跑 `check-design-contrast.py` 与 `check-dark-token-parity.py` | 凭观感调色——本轮实测 `--ds-border-strong` 浅色 1.88:1、暗色 1.97:1，被当"强调边界"用了很久 |
| 浅底用独立 `-soft` 令牌 | 用 `rgba(accent, .08)` 稀释做浅底（实测在浅色档对比度反而恶化） |
| 文字/链接与实底按钮**分两档值** | 一个 `accent` 同时当文字色和按钮底（同一值不可能两边都 ≥4.5:1） |
| 可交互边界走 `border-control/strong` | 拿 `border-subtle` 当控件边界——它是刻意 <3:1 的装饰线 |
| 品牌强调只用一个蓝 | 蓝 + 紫并存（`#8b5cf6` 一族已降为图表序列色） |
| 门禁脚本自证发现集非空 | 让门禁对着 0 组配对说"ALL PASS"（本轮真实踩过：外部 wcag 工具直读 `--ds-*` 时解析到 0 组仍报绿） |
| 暗色两份副本（`:root[data-theme='dark']` 与 `@media (prefers-color-scheme: dark)`）由 parity 脚本守护 | 只改一份副本——同一用户会因"哪条命中"看到不同取值 |

## 9. Responsive Behavior

- 断点沿用 `--ds-breakpoint-{xs,sm,md,lg}`（640 / 1024 / 1440 一线），窄屏优先折叠侧栏与筛选器。
- 触摸目标 ≥32px；表格在 <1024px 转为卡片式堆叠或横向滚动（二选一，不许挤压行高）。
- 尊重 `prefers-reduced-motion`（动画令牌降级为淡入淡出）与 `prefers-reduced-transparency`（玻璃档降级为实心）。

## 10. Iteration Guide（给 agent / 给同事）

1. 一次只改**一个组件或一页**。铺开方式是给页面根节点加 `data-design-contract="v2"`，新规则全部写在该作用域内，不在全局层抢改。
2. 只用 `--ds-*` 令牌；需要新语义时先在 CSS 加令牌 → 同步本文件 YAML → 跑两条门禁，再进组件。
3. 禁止新增裸十六进制色（棘轮基线：主应用 11 处、子看板 93 处，只降不升）。
4. 提交前必跑：`python3 scripts/check-design-contrast.py`（含 `--self-test` 夹具）、`python3 scripts/check-dark-token-parity.py`、`npm run type-check`、相关 `vitest`。
5. 设计决策变了要**写回本文件**并加修订注记，不允许只改代码不改契约。

### 修订注记

- 2026-10-20：初版。裁定单一蓝与扁平层级；`border-strong` 提到 ≥3:1；紫系降级为图表序列色；新增等宽数字令牌。

## 11. Known Gaps（诚实清单，未做不等于已做）

- **中文没有 SAP 那种可变字重字体**：SAP 靠 350–700 可变轴让"同字号的列表项与导航"读起来不同类。中文替代字体缺可变轴，目前只能用字号+字重双档近似。
- **5 个独立子看板**（finops / asset-exchange / multi-cluster / open-api / finetuning）**不在本契约覆盖内**：它们不引用 `design-tokens.css`（实测引用数 0）、硬编码色 93 处、无 i18n、无 e2e、主应用无入口。处置方向已定（收编进主应用），未落地。
- **玻璃拟态令牌 25 个未处置**：与"扁平色带"方向冲突，本轮保留原值，收敛与否待样板页验证后再裁。
- **`--ds-border-strong` 与 `--ds-border-control` 语义重叠**：本轮把两者都提到 ≥3:1，长期应合并为一个语义（需要一次调用点普查）。
- **没有视觉回归基线**：Playwright 只做行为断言，无截图比对；改主题色目前无法自动发现"某页塌陷"。
- **暗色档的 `-soft` 系列未逐一复核算**：门禁当前按浅色角色配对核算，暗色 Badge 组合仍有未验证面。
- 本轮**未在真机上确认 3 个样板页的渲染**（见 PR 说明的实测记录），契约先于铺开。
