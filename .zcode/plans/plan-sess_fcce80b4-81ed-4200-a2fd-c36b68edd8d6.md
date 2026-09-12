# B1: Storybook 引入 + 公共组件抽离

## 目标
在干净的 session 中完成 Storybook 8.x 搭建 + 从 47 个视图中抽离高频重复 UI 片段到 `src/components/ui/`，每个组件配 Stories + 单元测试，所有视图回改 import 后通过 type-check + vitest + e2e smoke。

## Phase 0: 环境预检与依赖安装
1. 验证 Node >= 22（`node --version`）
2. 确认 `node_modules` 存在且完整，否则执行 `npm install`
3. 安装 Storybook 8.x：
   ```
   npm install -D @storybook/vue3-vite @storybook/vue3 @storybook/addon-essentials @storybook/addon-interactions @storybook/blocks storybook
   ```
4. 在 `.gitignore` 追加 `storybook-static/`

## Phase 1: Storybook 配置（`.storybook/`）
创建 3 个配置文件：

### `.storybook/main.ts`
- framework: `@storybook/vue3-vite`
- stories glob: `../src/components/ui/**/*.stories.@(ts|tsx)`
- addons: essentials, interactions, blocks
- viteFinal: 复制 `@` alias、`define` 常量（`__APP_VERSION__`, `__APP_ENV__`）

### `.storybook/preview.ts`
- 注册 Element Plus（全量导入 + icons）
- 注册 vue-i18n（复用 `src/i18n/index.ts` 实例）
- 注册 Pinia（创建测试用 pinia 实例）
- 设置全局 decorators 包裹 `<el-config-provider>` 和 i18n/pinia provider
- 定义全局 args/argTypes 主题 token

### `.storybook/vite-final.ts`（如需要独立抽取）
- 处理 workspace alias 兼容

添加 scripts 到 `package.json`：
```json
"storybook": "storybook dev -p 6006",
"build-storybook": "storybook build"
```

## Phase 2: 公共组件抽离（`src/components/ui/`）

基于视图分析，抽离以下 8 个组件（按优先级排序）：

### 2.1 `PageCard.vue`
- 封装 `el-card shadow="never" class="page-card"` + 可选 title/subtitle slot
- Props: `title?: string`, `subtitle?: string`
- Slots: default, header-actions
- 来源: DataSourceManagement:8, Dashboard, ClusterOverview 等几乎所有视图

### 2.2 `Toolbar.vue`
- 封装 flex toolbar 布局（create btn + search + filter + spacer + refresh）
- Props: `showCreate?: boolean`, `createLabel?: string`, `searchPlaceholder?: string`, `showRefresh?: boolean`
- Events: `@create`, `@search`, `@refresh`
- Slots: filters (for custom filter selects), actions (for extra buttons)
- 来源: DataSourceManagement:10-53, TenantManagement, WorkspaceManagement, JobManagement 等

### 2.3 `StatusTag.vue`
- 封装 `el-tag` + status→type 映射逻辑
- Props: `status: string`, `statusMap?: Record<string, 'success'|'info'|'warning'|'danger'>`, `label?: string`
- 来源: DataSourceManagement:96-100, TenantManagement, ClusterOverview 等

### 2.4 `ConfirmDialog.vue`
- 封装 ElMessageBox.confirm 为声明式组件（可选，或保持函数式但统一 wrapper）
- Props: `visible`, `title`, `message`, `confirmText`, `cancelText`, `type`
- Events: `@confirm`, `@cancel`
- 来源: DataSourceManagement:527-536, 多处删除确认

### 2.5 `EmptyState.vue`
- 空状态占位（icon + message + optional action button）
- Props: `message?: string`, `icon?: Component`, `actionLabel?: string`
- Events: `@action`
- 来源: 各列表页 empty-text 场景

### 2.6 `StatCard.vue`
- KPI 数值卡片（大数字 + 标签 + 趋势指示）
- Props: `value: number|string`, `label: string`, `trend?: 'up'|'down'|'flat'`, `trendValue?: string`
- 来源: Dashboard, ClusterOverview 统计区块

### 2.7 `PageHeader.vue`
- 页面标题 + 副标题 + 操作区
- Props: `title: string`, `subtitle?: string`
- Slots: actions
- 来源: 所有视图顶部 h1 + .sub 模式

### 2.8 `FilterBar.vue`
- 独立筛选条（多 filter select + search + clear）
- Props: `filters: FilterConfig[]`, `modelValue: Record<string, any>`
- Events: `@update:modelValue`, `@clear`
- 来源: 多视图筛选区

每个组件同时创建：
- `ComponentName.stories.ts`：3-5 个 stories（default, variants, edge cases）
- `ComponentName.spec.ts`：基础渲染 + props 响应 + emit 测试

## Phase 3: 视图回改
逐视图替换内联 UI 为新组件 import，每改一批（5-8 个视图）跑一次：
1. `npm run type-check`
2. `npm run test`（vitest）
3. 手动验证 Storybook stories 渲染正常

优先回改高频使用组件的视图：
- PageCard → 全部 ~40 视图
- Toolbar → ~15 管理页
- StatusTag → ~10 列表页
- PageHeader → ~30 视图
- StatCard → Dashboard, ClusterOverview (~5 视图)
- EmptyState → ~10 列表页
- ConfirmDialog → ~8 有删除操作的视图
- FilterBar → ~6 复杂筛选视图

## Phase 4: 验证门禁
1. `npm run type-check` — 零错误
2. `npm run test` — 全绿（含新组件 spec）
3. `npm run build` — 生产构建成功
4. `npm run storybook` — dev server 启动无报错
5. `npm run build-storybook` — 静态构建成功
6. E2E smoke（如本地可跑）：`npm run test:e2e` 确认无回归

## 风险与缓解
- **i18n 在 Storybook 中不工作**：preview.ts 中完整初始化 i18n 实例，stories 中使用 `useI18n()` 或 `$t`
- **Element Plus 样式缺失**：preview.ts 中导入 element-plus/dist/index.css + 暗色主题 CSS vars
- **Pinia store 依赖**：stories 中提供 mock store 或最小 pinia 实例
- **define 常量未定义**：viteFinal 中复制 define 配置
- **回改引入回归**：分批回改 + 每批验证，不一次性全改
- **node_modules 损坏**：Phase 0 先验证，必要时 rm -rf + reinstall

## 交付物
- `.storybook/` 目录（main.ts, preview.ts）
- `src/components/ui/` 下 8 个组件 + stories + specs
- ~40 视图文件 import 路径更新
- package.json 新增 storybook scripts + devDependencies
- .gitignore 追加 storybook-static/
- type-check + vitest + build 全绿
</parameter>
<parameter name="allowedPrompts">[{"tool": "Bash", "prompt": "install npm dependencies"}, {"tool": "Bash", "prompt": "run type-check"}, {"tool": "Bash", "prompt": "run vitest tests"}, {"tool": "Bash", "prompt": "run vite build"}, {"tool": "Bash", "prompt": "run storybook build"}, {"tool": "Bash", "prompt": "check node version"}, {"tool": "Bash", "prompt": "list directory contents"}]