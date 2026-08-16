# 前端页面布局规范

> 本规范统一约束 DataEngineBDP 前端页面的结构骨架、数据加载三态处理、`useApi` composable 使用方式、错误处理策略、Element Plus 组件用法、路由组织与类型安全要求。所有新增页面必须遵循本规范，已有页面在重构时向本规范对齐。
>
> 适用范围：`frontend/src/views/**` 下全部页面组件，以及 `frontend/src/router/index.ts` 路由配置。

## 1. 总体原则

### 1.1 设计目标

- **结构一致**：所有页面遵循相同的骨架（标题 → 副标题 → KPI 区 → 主内容区），降低用户认知成本。
- **三态完备**：每个数据驱动区域必须处理 loading / error / data / empty 四态，禁止只写 data 态。
- **薄页面**：页面只做"取数 + 展示 + 触发动作"，业务逻辑下沉到 composable 或 API 模块。
- **类型安全**：所有响应数据、组件 props、表单数据有完整 TypeScript 类型定义。
- **统一组件库**：全部使用 Element Plus，禁止混用其他 UI 库。

### 1.2 技术栈约束

| 维度 | 选型 | 备注 |
| --- | --- | --- |
| 框架 | Vue 3 + `<script setup lang="ts">` | 禁止 Options API |
| UI 库 | Element Plus | 全项目唯一 UI 库 |
| 图标 | `@element-plus/icons-vue` | 统一图标来源 |
| 路由 | vue-router 4 + hash 模式 | `createWebHashHistory` |
| 状态 | Pinia | 跨页面共享状态 |
| HTTP | `@/composables/useApi` + `@/api/*` | 不直接用 axios |
| 测试 | Vitest | 页面逻辑通过 composable 单测覆盖 |

## 2. 页面结构规范

### 2.1 标准骨架

所有列表/管理类页面遵循以下骨架：

```vue
<template>
  <div class="page-root">
    <!-- 1. 标题区 -->
    <h1>页面标题</h1>
    <div class="sub">副标题或功能描述</div>

    <!-- 2. KPI 卡片区（可选，仅 Dashboard/概览页使用） -->
    <div class="grid g4">
      <div class="card">...</div>
    </div>

    <!-- 3. 主内容区（表格/表单/图表） -->
    <el-card shadow="never" class="page-card">
      <!-- 3.1 工具栏：操作按钮 + 搜索 + 筛选 -->
      <div class="toolbar">...</div>

      <!-- 3.2 数据展示：表格 + 三态 -->
      <el-table v-loading="loading" :data="list">...</el-table>

      <!-- 3.3 分页 -->
      <div class="pagination-wrap">
        <el-pagination ... />
      </div>
    </el-card>

    <!-- 4. 弹窗（创建/编辑/详情） -->
    <el-dialog v-model="dialogVisible" ...>...</el-dialog>
  </div>
</template>
```

### 2.2 标题区规范

- 一级页面标题使用 `<h1>`，仅一个。
- 副标题使用 `<div class="sub">`，一句话描述页面用途，颜色 `var(--muted)` 或 `#717a80`。
- 禁止在标题区堆叠操作按钮，操作按钮放在主内容区的工具栏。

```vue
<h1>租户管理</h1>
<div class="sub">
  管理平台多租户的创建、配额、状态与基本信息，底层自动映射为 K8s Namespace 与资源配额。
</div>
```

### 2.3 KPI 卡片区

- 仅 Dashboard、集群概览等汇总页使用 KPI 卡片区。
- 使用 `class="grid g4"`（4 列）或 `class="grid g2"`（2 列）布局。
- 每个卡片使用 `class="card"`，内部结构：`<h3>` 标题 + `<div class="kpi">` 主数值 + `<div class="meta">` 辅助说明。
- KPI 区必须独立处理三态，不与主表格共用 loading。

```vue
<div class="grid g4">
  <template v-if="loading">
    <div class="card" v-for="i in 4" :key="i">
      <h3>加载中…</h3>
      <div class="kpi">--</div>
    </div>
  </template>
  <template v-else-if="error">
    <div class="card" style="grid-column: span 4">
      <h3>加载失败</h3>
      <a href="javascript:void(0)" @click="reload">重试</a>
    </div>
  </template>
  <template v-else-if="data">
    <div class="card">...</div>
  </template>
</div>
```

### 2.4 主内容区

- 使用 `<el-card shadow="never" class="page-card">` 包裹，统一边框圆角。
- 卡片内部自上而下：工具栏 → 数据展示 → 分页。
- 多个独立区块使用 `<div class="grid g2">` 或 `style="margin-top: 14px"` 分隔。

### 2.5 工具栏规范

工具栏位于主内容区顶部，使用 flex 布局：

```vue
<div class="toolbar">
  <!-- 左侧：主操作按钮 -->
  <el-button type="primary" @click="openCreateDialog">+ 新建</el-button>

  <!-- 中间：搜索与筛选 -->
  <el-input v-model="keyword" placeholder="按名称搜索" clearable style="width: 240px" />
  <el-select v-model="filterStatus" placeholder="状态筛选" clearable style="width: 140px" />

  <!-- 弹簧：把右侧按钮推到最右 -->
  <div class="spacer"></div>

  <!-- 右侧：辅助操作 -->
  <el-button :icon="Refresh" circle @click="loadList" />
</div>
```

工具栏样式约定：

```css
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
```

### 2.6 页面根元素

- 列表/管理页：根元素使用 `<div class="page-root">` 或语义化类名（如 `<div class="tenant-page">`）。
- Dashboard 等纯展示页：可直接使用 `<div>` 作为根。
- 根元素不强制 padding，由全局布局容器统一控制页面边距，保证所有页面左右宽度一致。

## 3. 三态处理规范

### 3.1 四态定义

每个数据驱动区域必须处理以下四种状态：

| 状态 | 触发条件 | 展示要求 |
| --- | --- | --- |
| loading | 请求进行中 | 加载占位（骨架屏或"加载中…"文字） |
| error | 请求失败 | 错误信息 + 重试按钮 |
| data | 请求成功且有数据 | 实际数据展示 |
| empty | 请求成功但无数据 | "暂无数据"占位 |

### 3.2 三态渲染模式

#### 3.2.1 模板内联三态（推荐用于 KPI 卡片、独立区块）

使用 `v-if` / `v-else-if` / `v-else` 链式判断：

```vue
<template v-if="loading">
  <div class="meta">加载中…</div>
</template>
<template v-else-if="error">
  <div class="meta" style="color: var(--muted)">
    {{ error.message }}，<a href="javascript:void(0)" @click="reload">重试</a>
  </div>
</template>
<template v-else-if="data">
  <!-- 实际数据展示 -->
</template>
```

#### 3.2.2 el-table 内置三态（推荐用于列表页）

利用 `v-loading` 指令与 `empty-text` 属性：

```vue
<el-table
  v-loading="loading"
  :data="list"
  :empty-text="error ? '加载失败，请重试' : '暂无数据'"
>
  ...
</el-table>
```

#### 3.2.3 空状态独立处理

当需要富文本空状态（图标 + 引导操作）时，使用 `el-empty`：

```vue
<el-empty v-if="!loading && !error && list.length === 0" description="暂无租户">
  <el-button type="primary" @click="openCreateDialog">新建租户</el-button>
</el-empty>
```

### 3.3 三态与 useApi 配合

通过 `useApi` 返回的 `loading` / `error` / `data` 直接绑定到模板：

```vue
<script setup lang="ts">
const { data, loading, error, execute } = useApi<ClusterOverview>(
  () => clusterApi.getClusterOverview()
)
onMounted(() => void execute())
</script>

<template>
  <div v-if="loading">加载中…</div>
  <div v-else-if="error">
    {{ error.message }} <button @click="execute">重试</button>
  </div>
  <div v-else-if="data">{{ data.clusterName }}</div>
</template>
```

### 3.4 禁止项

- 禁止只写 data 态，忽略 loading / error。
- 禁止用 `v-if="data"` 同时表示"加载完成"，应显式区分 loading。
- 禁止在 loading 时仍渲染旧数据（除非明确需要 keep-alive 体验）。
- 禁止 error 态只显示"出错了"，必须提供可读错误信息与重试入口。

## 4. useApi 使用规范

### 4.1 基本用法

`useApi` 是项目统一的 API 调用包装，自动维护 loading / error / data 三态：

```ts
const { data, loading, error, execute, hasLoaded, reset } = useApi<T>(
  () => apiCall()
)
```

### 4.2 返回值说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `data` | `Ref<T \| null>` | 响应数据，初始为 null |
| `loading` | `Ref<boolean>` | 是否正在请求 |
| `error` | `Ref<ApiError \| Error \| null>` | 错误对象，初始为 null |
| `hasLoaded` | `Ref<boolean>` | 是否已成功请求过至少一次 |
| `execute` | `(...args) => Promise<T \| null>` | 触发请求，返回结果或 null |
| `reset` | `() => void` | 重置为初始状态 |

### 4.3 配置项

```ts
const { data, execute } = useApi(
  (id: string) => tenantApi.getTenant(id),
  {
    immediate: false,        // 是否立即执行（默认 false）
    initialData: null,       // 初始数据（默认 null）
    onSuccess: (data) => { /* 成功回调 */ },
    onError: (err) => { /* 失败回调 */ }
  }
)
```

### 4.4 标准调用模式

#### 4.4.1 挂载时加载

```ts
const { data, loading, error, execute } = useApi<ClusterOverview>(
  () => clusterApi.getClusterOverview()
)

onMounted(() => {
  void execute()
})
```

#### 4.4.2 立即加载

```ts
const { data, loading, error } = useApi<ClusterOverview>(
  () => clusterApi.getClusterOverview(),
  { immediate: true }
)
```

#### 4.4.3 带参数触发

```ts
const { data, execute } = useApi<Tenant>(
  (id: string) => tenantApi.getTenant(id)
)

async function viewDetail(id: string) {
  await execute(id)
}
```

#### 4.4.4 多个并发请求

多个独立请求分别使用独立的 `useApi`：

```ts
const overviewApi = useApi(() => clusterApi.getClusterOverview())
const nodesApi = useApi(() => clusterApi.listNodes())

onMounted(() => {
  void overviewApi.execute()
  void nodesApi.execute()
})
```

### 4.5 useApi 与手动 try/catch 的选择

| 场景 | 推荐方式 |
| --- | --- |
| 页面初始加载、详情查询 | `useApi` |
| 表单提交、删除确认等一次性动作 | 手动 `async function + try/catch` |
| 需要拿返回值做后续处理 | 手动 `try/catch`（`execute` 返回 null 时不便区分） |
| 列表查询 + 分页 + 搜索 | 手动 `try/catch`（需精细控制 loading 与 error 时机） |

列表页手动 try/catch 模板：

```ts
const loading = ref(false)
const error = ref(false)
const list = ref<Tenant[]>([])

async function loadList() {
  loading.value = true
  error.value = false
  try {
    const result = await tenantApi.listTenants({ page: currentPage.value, pageSize: pageSize.value })
    list.value = result.list
    total.value = result.total
  } catch (e) {
    error.value = true
    ElMessage.error('列表加载失败')
  } finally {
    loading.value = false
  }
}
```

### 4.6 禁止项

- 禁止在 `useApi` 的 factory 中写副作用（修改 store、跳转路由）。
- 禁止在 `onSuccess` / `onError` 中抛错，会破坏 Promise 链。
- 禁止绕过 `useApi` 直接调用 `axios`。

## 5. 错误处理规范

### 5.1 错误分层

| 层级 | 处理方 | 职责 |
| --- | --- | --- |
| 网络错误 | `client.ts` 拦截器 | 提示"网络异常"，抛 `ApiError` |
| HTTP 401 | `client.ts` 拦截器 | 跳登录页 |
| HTTP 403/404/500 | `client.ts` 拦截器 | 统一提示，抛 `ApiError` |
| 业务错误（code ≠ 0） | `client.ts` 拦截器 | 提示 `message`，抛 `ApiError` |
| 表单校验错误 | 页面层 `el-form` rules | 字段下方红字提示 |
| 业务流程错误 | 页面层 catch | 特殊业务码分支处理 |

### 5.2 页面层错误处理策略

#### 5.2.1 默认：依赖拦截器

大多数场景下，页面层不需要 catch 错误，拦截器已统一提示：

```ts
async function handleSubmit() {
  await tenantApi.createTenant(params)  // 失败时拦截器已提示
  ElMessage.success('创建成功')          // 仅成功时执行
  dialogVisible.value = false
  await loadList()
}
```

#### 5.2.2 特殊：业务码分支

需要根据业务码做不同处理时（如标红某个字段）：

```ts
async function handleSubmit() {
  try {
    await tenantApi.createTenant(params)
    ElMessage.success('创建成功')
  } catch (e) {
    if (e instanceof ApiError && e.code === 1001) {
      // 租户编码已存在，标红 code 字段
      formRef.value?.validateField('code')
      return
    }
    // 其他错误已由拦截器提示，无需重复
  }
}
```

#### 5.2.3 列表加载失败

列表加载失败时，设置 `error` 标志，表格 `empty-text` 显示"加载失败，请重试"：

```ts
async function loadList() {
  loading.value = true
  error.value = false
  try {
    const result = await tenantApi.listTenants(...)
    list.value = result.list
  } catch {
    error.value = true
    // 不重复 ElMessage，拦截器已提示
  } finally {
    loading.value = false
  }
}
```

### 5.3 用户确认操作

删除、停用等危险操作必须先弹 `ElMessageBox.confirm` 确认：

```ts
async function handleDelete(row: Tenant) {
  try {
    await ElMessageBox.confirm(
      `确定删除租户「${row.name}」吗？该操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await tenantApi.deleteTenant(row.id)
    ElMessage.success('删除成功')
    await loadList()
  } catch {
    // 用户取消或删除失败，不提示
  }
}
```

### 5.4 表单校验

使用 `el-form` 的 `rules` 属性 + `formRef.validate`：

```ts
const formRules: FormRules = {
  name: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入租户编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9-]*$/, message: '小写字母开头，仅含小写字母、数字、连字符', trigger: 'blur' }
  ]
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    // 提交逻辑
  })
}
```

### 5.5 禁止项

- 禁止在页面层重复提示拦截器已提示的错误。
- 禁止用 `console.error` 替代用户可见的错误提示。
- 禁止 catch 后静默吞掉错误且不设置 error 状态（用户无法感知失败）。
- 禁止在 catch 中 `throw e` 后又不在外层处理（会导致未捕获 Promise 拒绝）。

## 6. Element Plus 组件规范

### 6.1 组件使用约定

| 场景 | 组件 | 关键属性 |
| --- | --- | --- |
| 数据表格 | `el-table` + `el-table-column` | `v-loading`、`stripe`、`border`、`empty-text` |
| 分页 | `el-pagination` | `background`、`layout="total, sizes, prev, pager, next, jumper"` |
| 表单 | `el-form` + `el-form-item` | `:rules`、`label-width`、`label-position` |
| 输入框 | `el-input` | `clearable`、`placeholder` |
| 下拉选择 | `el-select` + `el-option` | `clearable` |
| 弹窗 | `el-dialog` | `:close-on-click-modal="false"` |
| 消息提示 | `ElMessage` | `success` / `error` / `warning` |
| 确认框 | `ElMessageBox.confirm` | `type: 'warning'` |
| 标签 | `el-tag` | `:type`、`effect="light"` |
| 进度条 | `el-progress` | `:percentage`、`:color`、`:stroke-width` |
| 空状态 | `el-empty` | `description` |
| 图标 | `@element-plus/icons-vue` | 命名导入 |

### 6.2 表格规范

```vue
<el-table
  v-loading="loading"
  :data="list"
  stripe
  border
  style="width: 100%"
  :empty-text="error ? '加载失败，请重试' : '暂无数据'"
>
  <el-table-column prop="id" label="ID" width="120" />
  <el-table-column prop="name" label="名称" min-width="160" />
  <el-table-column label="状态" width="100">
    <template #default="{ row }">
      <el-tag :type="statusTagType(row.status)" effect="light">
        {{ statusLabel(row.status) }}
      </el-tag>
    </template>
  </el-table-column>
  <el-table-column label="操作" width="160" fixed="right">
    <template #default="{ row }">
      <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
      <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
    </template>
  </el-table-column>
</el-table>
```

约定：

- 列宽：固定列用 `width`，弹性列用 `min-width`，操作列用 `fixed="right"`。
- 状态/枚举列：用 `el-tag` + `effect="light"`，颜色通过辅助函数映射。
- 操作列：用 `el-button link` 文字按钮，主操作 `type="primary"`，危险操作 `type="danger"`。
- 自定义单元格内容用 `#default="{ row }"` 插槽，禁止用 `:formatter`（类型不友好）。

### 6.3 分页规范

```vue
<div class="pagination-wrap">
  <el-pagination
    v-model:current-page="currentPage"
    v-model:page-size="pageSize"
    :page-sizes="[10, 20, 50, 100]"
    :total="total"
    layout="total, sizes, prev, pager, next, jumper"
    background
    @size-change="loadList"
    @current-change="loadList"
  />
</div>
```

约定：

- 默认 `pageSize` 为 20，可选 `[10, 20, 50, 100]`。
- `layout` 固定为 `"total, sizes, prev, pager, next, jumper"`。
- 必须使用 `background` 属性。
- `size-change` 与 `current-change` 都触发 `loadList`。
- 分页容器样式：`display: flex; justify-content: flex-end; margin-top: 16px;`。

### 6.4 表单规范

```vue
<el-form
  ref="formRef"
  :model="formData"
  :rules="formRules"
  label-width="100px"
  label-position="right"
>
  <el-form-item label="名称" prop="name">
    <el-input v-model="formData.name" placeholder="请输入名称" />
  </el-form-item>
</el-form>
```

约定：

- `label-position` 统一 `right`，`label-width` 根据最长标签调整（通常 80-120px）。
- 必填字段在 `rules` 中 `required: true`，trigger 根据组件选 `blur` 或 `change`。
- 编辑时不可变字段用 `:disabled="isEdit"` 禁用。
- 弹窗内表单关闭时 `@closed="resetForm"` 重置。

### 6.5 弹窗规范

```vue
<el-dialog
  v-model="dialogVisible"
  :title="isEdit ? '编辑' : '新建'"
  width="520px"
  :close-on-click-modal="false"
  @closed="resetForm"
>
  <!-- el-form 内容 -->
  <template #footer>
    <el-button @click="dialogVisible = false">取消</el-button>
    <el-button type="primary" :loading="submitting" @click="handleSubmit">
      {{ isEdit ? '保存' : '创建' }}
    </el-button>
  </template>
</el-dialog>
```

约定：

- `:close-on-click-modal="false"`，防止误点遮罩关闭丢数据。
- 提交按钮使用 `:loading="submitting"` 防重复提交。
- 取消按钮在左，确认按钮在右。
- 弹窗宽度通常 480-640px，超大表单用 720px。

### 6.6 图标规范

- 图标统一从 `@element-plus/icons-vue` 命名导入。
- 按钮内图标用 `:icon` 属性：`<el-button :icon="Refresh" circle />`。
- 禁止混用其他图标库（如 Font Awesome、iconify）。
- 图标风格保持统一、简约，不使用花哨图标。

```ts
import { Refresh, Search, Plus, Delete, Edit } from '@element-plus/icons-vue'
```

### 6.7 消息提示规范

- 成功提示：`ElMessage.success('操作成功')`。
- 失败提示：通常由拦截器统一调用，页面层仅在特殊场景手动调用。
- 警告提示：`ElMessage.warning(...)`，用于非阻断性提醒。
- 确认对话框：`ElMessageBox.confirm(...)`，用于删除、停用等危险操作。

提示消息应简洁可读，不暴露技术细节（如堆栈、SQL 错误）。

## 7. 路由规范

### 7.1 路由定义

路由统一在 `frontend/src/router/index.ts` 中定义，使用 `RouteRecordRaw[]` 数组形式。

### 7.2 路径命名

- 路径使用 **kebab-case**，全小写：`/tenant-management`、`/data-source`、`/sql-workbench`。
- 单词路径可使用单数形式：`/dashboard`、`/login`、`/search`。
- 多词路径使用连字符：`/workspace-management`、`/quota-management`。
- 子路径用 `/` 分层：`/orchestrator/dag`。

### 7.3 路由名称

- 路由 `name` 使用 **PascalCase**：`TenantManagement`、`ClusterOverview`、`JobManagement`。
- 名称与组件名一致，便于 `router.push({ name: 'TenantManagement' })` 调用。

### 7.4 路由 meta

```ts
{
  path: '/tenants',
  name: 'TenantManagement',
  component: TenantManagement,
  meta: {
    title: '租户管理',     // 页面标题（显示在浏览器标签、面包屑）
    icon: 'Management'      // Element Plus 图标名（菜单使用）
  }
}
```

- `title`：必填，中文页面标题。
- `icon`：选填，Element Plus 图标组件名（字符串形式）。
- `public`：选填，true 表示无需登录可访问（如 `/login`）。

### 7.5 懒加载

所有页面组件必须懒加载，使用动态 import：

```ts
const TenantManagement = () => import('@/views/TenantManagement.vue')

const routes: RouteRecordRaw[] = [
  {
    path: '/tenants',
    name: 'TenantManagement',
    component: TenantManagement,
    meta: { title: '租户管理', icon: 'Management' }
  }
]
```

禁止直接 `import TenantManagement from '@/views/TenantManagement.vue'` 同步导入页面组件。

### 7.6 路由守卫

鉴权守卫通过 `router.beforeEach` 实现，未登录跳转登录页并携带 redirect 参数：

```ts
const PUBLIC_PATHS = new Set(['/login'])

router.beforeEach((to) => {
  const authStore = useAuthStore()
  if (PUBLIC_PATHS.has(to.path)) return true
  if (!authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})
```

### 7.7 兜底路由

未匹配路径统一重定向到 Dashboard：

```ts
{ path: '/:pathMatch(.*)*', redirect: '/dashboard' }
```

### 7.8 路由历史模式

使用 `createWebHashHistory()`（hash 模式），避免后端需配置 SPA fallback。

## 8. 类型安全规范

### 8.1 类型导入

- 类型导入使用 `import type`，与值导入分离：
  ```ts
  import * as tenantApi from '@/api/tenant'
  import type { Tenant, PlanTier, TenantStatus } from '@/api/types'
  ```
- 禁止 `import { Tenant } from ...`（值导入类型），会被严格模式与 verbatimModuleSyntax 拒绝。

### 8.2 响应数据类型

所有 API 响应必须有显式类型参数：

```ts
// 正确
const { data } = useApi<ClusterOverview>(() => clusterApi.getClusterOverview())
const list = ref<Tenant[]>([])

// 错误：any
const { data } = useApi(() => clusterApi.getClusterOverview())
const list = ref([])
```

### 8.3 组件 props 类型

使用 `defineProps<T>()` 泛型形式定义 props：

```ts
interface Props {
  tenantId: string
  readonly?: boolean
}

const props = defineProps<Props>()
```

禁止使用 runtime 形式 `defineProps({ tenantId: { type: String, required: true } })`。

### 8.4 emit 类型

使用 `defineEmits<T>()` 泛型形式：

```ts
const emit = defineEmits<{
  (e: 'success', tenant: Tenant): void
  (e: 'cancel'): void
}>()
```

### 8.5 表单数据类型

表单数据使用 `reactive` + 显式 interface：

```ts
interface TenantForm {
  name: string
  code: string
  plan: PlanTier
  status: TenantStatus
  contact: string
  contactPhone: string
}

const formData = reactive<TenantForm>({
  name: '',
  code: '',
  plan: 'enterprise',
  status: 'active',
  contact: '',
  contactPhone: ''
})
```

### 8.6 枚举类型

使用字面量联合类型，禁止 `enum`：

```ts
// 正确
type TenantStatus = 'active' | 'suspended' | 'deleted'
type PlanTier = 'standard' | 'enterprise' | 'flagship' | 'internal'

// 错误
enum TenantStatus { Active = 'active', Suspended = 'suspended' }
```

### 8.7 ref 初始化

`ref` 必须有显式类型参数或能从初始值推断：

```ts
const loading = ref(false)              // 推断为 Ref<boolean>
const list = ref<Tenant[]>([])          // 显式类型
const current = ref<Tenant | null>(null) // 联合类型
```

禁止 `ref<any>(...)`。

### 8.8 computed 类型

`computed` 通常能自动推断返回类型，复杂场景加显式泛型：

```ts
const cpuPercent = computed<number>(() => {
  if (!overview.value) return 0
  return Math.round((overview.value.cpuUsed / overview.value.cpuCapacity) * 100)
})
```

## 9. 样式规范

### 9.1 作用域

- 页面私有样式使用 `<style scoped>`。
- 跨页面共享样式放在全局 CSS（`frontend/src/styles/`）。
- 禁止使用 `<style>`（无 scoped）污染全局。

### 9.2 类名约定

- 页面根：`page-root` 或语义化类名（`tenant-page`、`dashboard-page`）。
- 副标题：`sub`。
- 卡片：`card`。
- 网格：`grid g2`（2 列）、`grid g4`（4 列）。
- 工具栏：`toolbar`，弹簧 `spacer`。
- 分页容器：`pagination-wrap`。
- KPI 主数值：`kpi`，辅助说明：`meta`。

### 9.3 颜色变量

使用 CSS 变量，禁止硬编码颜色（除一次性样式外）：

```css
color: var(--muted);      /* 次要文字色 */
color: var(--primary);    /* 主色 */
background: var(--bg);    /* 背景色 */
```

如需临时颜色，使用项目调色板内的值并加注释说明。

### 9.4 间距约定

- 卡片间距：`14px` 或 `16px`。
- 工具栏 gap：`10px`。
- 表单项间距：由 Element Plus 默认控制。
- 分页上边距：`16px`。

## 10. 页面完整模板

代码示例：标准管理页面完整模板（Vue）

```vue
<template>
  <div class="page-root">
    <h1>资源管理</h1>
    <div class="sub">资源管理的功能描述。</div>

    <el-card shadow="never" class="page-card">
      <!-- 工具栏 -->
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">+ 新建</el-button>
        <el-input
          v-model="keyword"
          placeholder="按名称搜索"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="filterStatus" placeholder="状态筛选" clearable style="width: 140px" @change="handleSearch">
          <el-option label="活跃" value="active" />
          <el-option label="已暂停" value="suspended" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadList" />
      </div>

      <!-- 表格 -->
      <el-table
        v-loading="loading"
        :data="list"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? '加载失败，请重试' : '暂无数据'"
      >
        <el-table-column prop="id" label="ID" width="120" />
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑' : '新建'"
      width="520px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" label-position="right">
        <el-form-item label="名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="formData.status" style="width: 100%">
            <el-option label="活跃" value="active" />
            <el-option label="已暂停" value="suspended" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as resourceApi from '@/api/resource'
import type { Resource, ResourceStatus } from '@/api/types'

/* 列表查询 */
const loading = ref(false)
const error = ref(false)
const list = ref<Resource[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const filterStatus = ref<ResourceStatus | ''>('')

async function loadList() {
  loading.value = true
  error.value = false
  try {
    const result = await resourceApi.listResources({
      keyword: keyword.value || undefined,
      status: filterStatus.value || undefined,
      page: currentPage.value,
      pageSize: pageSize.value
    })
    list.value = result.list
    total.value = result.total
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadList()
}

/* 创建/编辑 */
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref('')

interface ResourceForm {
  name: string
  status: ResourceStatus
}

const formData = reactive<ResourceForm>({
  name: '',
  status: 'active'
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

function openCreateDialog() {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(row: Resource) {
  isEdit.value = true
  editingId.value = row.id
  formData.name = row.name
  formData.status = row.status
  dialogVisible.value = true
}

function resetForm() {
  formData.name = ''
  formData.status = 'active'
  formRef.value?.clearValidate()
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (isEdit.value) {
        await resourceApi.updateResource(editingId.value, { ...formData })
        ElMessage.success('更新成功')
      } else {
        await resourceApi.createResource({ ...formData })
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      await loadList()
    } catch {
      // 错误已由拦截器处理
    } finally {
      submitting.value = false
    }
  })
}

/* 删除 */
async function handleDelete(row: Resource) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.name}」吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await resourceApi.deleteResource(row.id)
    ElMessage.success('删除成功')
    await loadList()
  } catch {
    // 用户取消或删除失败
  }
}

/* 标签辅助 */
function statusLabel(status: ResourceStatus): string {
  const map: Record<ResourceStatus, string> = { active: '活跃', suspended: '已暂停' }
  return map[status] || status
}

function statusTagType(status: ResourceStatus): 'success' | 'warning' {
  return status === 'active' ? 'success' : 'warning'
}

/* 初始化 */
onMounted(() => {
  loadList()
})
</script>

<style scoped>
.page-root {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.page-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
```

## 11. 检查清单

新增页面时，按以下清单自检：

- [ ] 使用 `<script setup lang="ts">`，不使用 Options API
- [ ] 页面骨架：`<h1>` 标题 + `<div class="sub">` 副标题 + 主内容区
- [ ] 数据驱动区域处理了 loading / error / data / empty 四态
- [ ] 列表页使用 `el-table` + `el-pagination`，配置 `v-loading` 与 `empty-text`
- [ ] 通过 `useApi` 或手动 try/catch 调用 API，不直接用 axios
- [ ] 错误处理依赖 `client.ts` 拦截器，不重复提示
- [ ] 危险操作使用 `ElMessageBox.confirm` 确认
- [ ] 表单使用 `el-form` + `rules` 校验，提交按钮带 `:loading`
- [ ] 弹窗 `:close-on-click-modal="false"`，关闭时 `resetForm`
- [ ] 图标从 `@element-plus/icons-vue` 导入
- [ ] 所有类型使用 `import type` 导入，无 `any`
- [ ] 枚举使用字面量联合，不使用 `enum`
- [ ] props 使用 `defineProps<T>()`，emit 使用 `defineEmits<T>()`
- [ ] 路由懒加载，`meta.title` 与 `meta.icon` 配置完整
- [ ] 私有样式使用 `<style scoped>`，颜色使用 CSS 变量