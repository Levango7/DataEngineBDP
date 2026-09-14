# 已知失败用例清单

> 最后更新: 2026-09-15 | 状态: RC（Release Candidate）| 清零期限: v2.2.0

---

## 概述

本文件清单列化项目中所有已知的预存失败用例与类型错误，用于：
1. 区分**预存失败**与**新引入回归**（审查/修复时排除清单内用例）
2. 设定清零期限与优先级
3. 透明披露项目质量状态

| 类别 | 数量 | 状态 | 清零期限 |
|------|------|------|----------|
| vue-tsc 类型错误 | 7 | 待修复 | v2.2.0 |
| vitest 失败用例 | 43 | 待修复 | v2.2.0 |
| Java 编译错误（已修复） | 0 | ✅ 已清零 | 2026-09-15 |
| **合计** | **50** | — | — |

---

## 一、vue-tsc 类型错误（7个）

### 1.1 Drawer/Modal 组件 VNodeRef 类型不匹配（4个）

| 文件 | 行:列 | 错误码 | 描述 |
|------|-------|--------|------|
| `src/components/Drawer.vue` | 10:10 | TS2322 | `HTMLElement \| null` 不匹配 `VNodeRef \| undefined` |
| `src/components/Modal.vue` | 10:10 | TS2322 | 同上 |
| `src/components/__tests__/Drawer.test.ts` | 32:7 | TS2322 | `Record<string, unknown>` 不匹配 slots 类型 |
| `src/components/__tests__/Modal.test.ts` | 46:7 | TS2322 | 同上 |

**根因**: Vue 3.4+ 收窄了 `VNodeRef` 类型定义，`template ref` 绑定 `HTMLElement | null` 不再直接兼容。
**修复方案**: 将 ref 绑定改为 `ref<string>` 或使用 `as VNodeRef` 断言。

### 1.2 AuthDashboardAnalyze 测试 Node.js 模块引用（3个）

| 文件 | 行:列 | 错误码 | 描述 |
|------|-------|--------|------|
| `src/views/__tests__/AuthDashboardAnalyze.test.ts` | 14:30 | TS2307 | Cannot find module 'node:fs' |
| `src/views/__tests__/AuthDashboardAnalyze.test.ts` | 15:25 | TS2307 | Cannot find module 'node:path' |
| `src/views/__tests__/AuthDashboardAnalyze.test.ts` | 21:26 | TS2304 | Cannot find name '__dirname' |

**根因**: 测试文件在 vitest 环境中引用了 Node.js 内置模块，但 tsconfig 未包含 `@types/node` 或 `node` 环境声明。
**修复方案**: 在 tsconfig.json 的 `compilerOptions.types` 中添加 `"node"`，或改用 `vi.mock` 替代文件系统读取。

---

## 二、vitest 失败用例（43个）

### 2.1 Drawer 组件测试（12个失败）

文件: `src/components/__tests__/Drawer.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | visible=true 时应渲染 drawer 容器 | DOM 结构与预期不匹配 |
| 2 | visible=true 时应渲染 overlay | 同上 |
| 3 | visible=false 时不应渲染 drawer | 同上 |
| 4 | 点击 overlay 应触发 close 事件 | 事件绑定方式变更 |
| 5 | 点击关闭按钮应触发 close 事件 | 同上 |
| 6 | 按下 ESC 键应触发 close 事件 | 同上 |
| 7 | 应包含 role="dialog" 属性 | 无障碍属性缺失或测试选择器不匹配 |
| 8 | 应包含 aria-modal="true" 属性 | 同上 |
| 9 | 打开时 body overflow 应为 hidden | side effect 测试环境隔离问题 |
| 10 | 关闭时 body overflow 应恢复 | 同上 |
| 11 | 关闭按钮应使用 el-button 而非原生 span | 组件替换后测试未同步 |
| 12 | header slot 应正确渲染 | slot 渲染方式变更 |

### 2.2 Toast 组件测试（10个失败）

文件: `src/components/__tests__/Toast.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 容器应包含 aria-live="polite" | 无障碍属性测试不匹配 |
| 2 | 应包含 aria-atomic 属性 | 同上 |
| 3 | store 中有 toast 时应渲染对应数量 | store mock 方式问题 |
| 4 | 不同 type 应渲染不同的 toast 修饰类 | CSS class 命名变更 |
| 5 | 默认 type 应为 info | 同上 |
| 6 | 每个 toast 应包含 role="status" 属性 | 无障碍属性测试不匹配 |
| 7 | 每个 toast 应包含手动关闭按钮 | DOM 结构变更 |
| 8 | 点击手动关闭按钮应移除对应 toast | 同上 |
| 9 | toast 消息文本应正确渲染 | 同上 |
| 10 | 应使用 TransitionGroup 包裹 | 组件实现方式变更 |

### 2.3 Develop 页面测试（5个失败）

文件: `src/views/__tests__/Develop.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 使用 el-select 而非原生 select | Element Plus 组件替换后测试未同步 |
| 2 | 使用 el-input-number 而非原生 input | 同上 |
| 3 | 使用 el-button 而非原生 button | 同上 |
| 4 | 加载中应展示 loading 文案 | 三态测试 mock 不匹配 |
| 5 | 加载失败应展示错误态与重试入口 | 同上 |

### 2.4 APIMarket 页面测试（3个失败）

文件: `src/views/__tests__/APIMarket.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 列表请求失败时应展示错误态 | mock 数据断言不匹配 |
| 2 | 点击重试成功后应渲染真实列表数据 | 同上 |
| 3 | P2-9: 先成功后失败时 KPI 概览不应展示旧数据 | 同上 |

### 2.5 FilterBar 组件测试（3个失败）

文件: `src/components/ui/__tests__/FilterBar.spec.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | renders a select per filter | 组件 props/emit 接口变更 |
| 2 | shows clear button when showClear | 同上 |
| 3 | hides search input without searchPlaceholder | 同上 |

### 2.6 JobManagement 页面测试（4个失败）

文件: `src/views/__tests__/JobManagement.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 应正确挂载组件并渲染页面标题 | 组件结构变更 |
| 2 | typeLabel 应正确映射作业类型 | 工具函数导出方式变更 |
| 3 | statusLabel 应正确映射作业状态 | 同上 |
| 4 | statusTagType 应返回正确的 tag 类型 | 同上 |

### 2.7 其他失败（6个）

| 文件 | 测试名 | 失败原因 |
|------|--------|----------|
| `AuthDashboardAnalyze.test.ts` | 主题切换按钮应使用内联 SVG 替代 emoji | emoji→SVG 替换后测试未同步 |
| `AuthDashboardAnalyze.test.ts` | 空态应使用内联 SVG 替代 emoji | 同上 |
| `EmptyState.spec.ts` | renders default message | 组件 props 接口变更 |
| `EngSpark.test.ts` | 挂载时应以当前工作空间加载作业列表 | store mock 不匹配 |
| `DataSourceManagement.test.ts` | statusTagType 应返回正确的 tag 类型 | 工具函数导出方式变更 |

---

## 三、已修复的 Java 编译错误（5个，2026-09-15 清零）

| 日期 | 模块 | 文件 | 问题 | 修复 Commit |
|------|------|------|------|-------------|
| 2026-09-15 | finops-dashboard | `BillSummary.java` | 缺少 `billingMonth` 字段 | `3f76200b` |
| 2026-09-15 | metadata-collector | `CollectionSchedulerService.java` | 缺少 `shutdown()` 方法 | `f2236b1f` |
| 2026-09-15 | real-time-pipeline | `NebulaLineageGraphClient.java` | 缺少 `validateNebulaIdentifier()` | `ab9d783e` |
| 2026-09-15 | real-time-pipeline | `FieldLineageTest.java` | 全参构造缺少 `tenantId` | `ab9d783e` |
| 2026-09-15 | infra-orchestrator | `K8sClientService.java` | 缺少 `destroy()` 方法 | `6df5c330` |

---

## 四、修复优先级

| 优先级 | 类别 | 预估工时 | 依赖 |
|--------|------|----------|------|
| P1 | vue-tsc 7个类型错误 | 2h | 无 |
| P2 | Drawer/Toast 组件测试（22个） | 4h | 需确认组件最终 API |
| P2 | Develop/APIMarket 页面测试（8个） | 3h | 需确认页面最终交互 |
| P3 | FilterBar/EmptyState/JobManagement 等（13个） | 3h | 需确认组件接口 |

**总计预估**: ~12h 可将已知失败用例清零至 0。

---

## 五、验证命令

```bash
# 前端类型检查
cd frontend && npx vue-tsc --noEmit

# 前端单元测试
cd frontend && npx vitest run

# Java 编译（已清零）
export JAVA_HOME="E:/dev-tools/jdk17.0.20_8"
mvn package -Dmaven.test.skip=true -q
```

---

## 变更日志

| 日期 | 变更 | 操作人 |
|------|------|--------|
| 2026-09-15 | 初始创建，清单化50个已知失败用例 | AI 审查流水线 |
| 2026-09-15 | Java 编译错误5个已修复清零 | AI 审查流水线 |