# 数擎大数据平台 · 控制台前端

将控制台 HTML 原型 v0.3 迁移为 Vue 3 + TypeScript + Vite 工程。

## 技术栈

- Vue 3.4+（Composition API + `<script setup>`）
- TypeScript 5+
- Vite 5+
- Vue Router 4（hash 模式）
- Pinia（状态管理）
- 不依赖任何 UI 组件库 / CSS 框架 / 图标库，保持原型自研风格

## 工程结构

```
frontend/
├── index.html              # Vite 入口
├── package.json
├── tsconfig.json / tsconfig.node.json
├── vite.config.ts
├── src/
│   ├── main.ts             # 应用入口
│   ├── App.vue             # 根组件（挂载 Icons + DefaultLayout）
│   ├── env.d.ts            # Vue 类型声明
│   ├── router/index.ts     # 39 条路由（19 实页 + 20 占位 + 兜底）
│   ├── stores/app.ts       # Pinia store（工作空间 / 待办 / Toast）
│   ├── layouts/DefaultLayout.vue
│   ├── components/
│   │   ├── Sidebar.vue     # 侧边栏（6 分组 35 项）
│   │   ├── TopBar.vue      # 顶栏（工作空间切换 + 环境标签 + 头像）
│   │   ├── Toast.vue
│   │   ├── Drawer.vue      # 通用抽屉
│   │   ├── Modal.vue       # 通用模态框
│   │   └── Icons.vue       # SVG symbol 定义
│   ├── composables/useTabs.ts
│   ├── views/              # 19 个页面 + Roadmap 占位
│   └── styles/main.css     # 全局样式（从 HTML 迁移）
└── README.md
```

## 运行

```bash
npm install
npm run dev      # 启动开发服务器（默认 http://localhost:5173）
npm run build    # 类型检查 + 生产构建
npm run preview  # 预览构建产物
```

## 与 HTML 原型的对应关系

| HTML pane (`data-pane`) | Vue 路由 | View 组件 | 说明 |
|---|---|---|---|
| dashboard | /dashboard | Dashboard.vue | 工作台 |
| workspaces | /workspaces | Workspaces.vue | 工作空间（不在导航） |
| projects | /projects | Projects.vue | 数据项目（不在导航） |
| integrate | /integrate | Integrate.vue | 数据集成 |
| develop | /develop | Develop.vue | 数据开发 IDE |
| sql | /sql | Sql.vue | 统一 SQL 查询 |
| govern | /govern | Govern.vue | 资产目录 |
| standard | /standard | Standard.vue | 数据标准 |
| quality | /quality | Quality.vue | 数据质量 |
| lineage | /lineage | Lineage.vue | 血缘分析 |
| sec | /sec | Sec.vue | 安全脱敏 |
| vector | /vector | Vector.vue | 向量库 |
| kb | /kb | Kb.vue | 知识工程 |
| llmops | /llmops | Llmops.vue | LLMOps |
| gateway | /gateway | Gateway.vue | 大模型网关 |
| analyze | /analyze | Analyze.vue | BI 分析 |
| ops | /ops | Ops.vue | 运维中心（不在导航） |
| account | /account | Account.vue | 账户与配额（不在导航） |
| admin | /admin | Admin.vue | 运营后台 |
| roadmap | /infra-* /eng-* /... | Roadmap.vue | 20 个占位路由 |

### 交互迁移

| HTML 函数 | Vue 实现 |
|---|---|
| `goto(v)` | `router.push('/xxx')` |
| `toast(msg)` | `store.showToast(msg)` + Toast.vue |
| `approve()/reject()` | `store.approve(id)/reject(id)` |
| `updateBadge()` | `store.todoCount` computed |
| `drawer(id)/modal(id)` | `<Drawer>` / `<Modal>` 组件 + `v-if` |
| `tab(el)` | `tab` ref + `v-if` |
| `runJob()/runSql()` | 视图内函数 + 响应式 log 数组 |
| 工作空间切换 | `store.setWorkspace()` + TopBar 菜单 |

## 主题

浅色主题：白底 + 青绿色主色 `#2f6f6a`，与 HTML 原型完全一致。