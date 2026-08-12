# 开放 API 服务目录前端 (open-api-dashboard)

数据引擎大数据平台 · L5.5 开放 API 服务目录前端

## 功能页面

- **API 目录** (`/catalog`) - API 列表浏览、检索、订阅入口
- **一键生成** (`/generate`) - SQL/模型/函数三种来源一键生成 RESTful API
- **订阅管理** (`/subscriptions`) - 订阅列表、Key 颁发、限流配置
- **用量看板** (`/dashboard`) - 调用量趋势、状态码分布、延迟分布、计费统计（ECharts）
- **API 详情** (`/api-detail/:id`) - API 元数据、参数、统计、APISIX 配置

## 技术栈

- Vue 3.4 + Composition API
- Vue Router 4
- Pinia 2（状态管理）
- Element Plus 2.6（UI 组件库）
- ECharts 5.5 + vue-echarts 6.7（图表）
- Axios 1.6（HTTP 客户端）
- Vite 5（构建工具）

## 开发

```bash
# 安装依赖
npm install

# 启动开发服务器（默认 5173 端口，代理 /api 到 8090）
npm run dev

# 构建
npm run build

# 预览构建结果
npm run preview
```

## 目录结构

```
src/
├── api/              # API 接口封装
│   ├── http.js       # Axios 实例 + 拦截器
│   ├── catalog.js    # API 目录相关接口
│   └── subscription.js # 订阅相关接口
├── stores/           # Pinia store
│   ├── catalog.js    # API 目录 store
│   └── subscription.js # 订阅 store
├── views/            # 页面视图
│   ├── CatalogView.vue       # API 目录
│   ├── GenerateView.vue      # 一键生成
│   ├── SubscriptionsView.vue # 订阅管理
│   ├── DashboardView.vue     # 用量看板
│   └── ApiDetailView.vue     # API 详情
├── router/           # 路由
│   └── index.js
├── App.vue           # 根组件
└── main.js           # 入口
```

## 后端依赖

前端通过 `/api/v1` 前缀代理到后端 `open-api-catalog` 服务（默认端口 8090）。