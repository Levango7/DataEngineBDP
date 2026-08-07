# 数据资产流通看板 (Asset Exchange Dashboard)

数擎大数据平台 · T039 数据资产流通前端看板（Vue3 + ECharts）。

## 功能

- **流通看板**：资产 Top N 排行 / 流通趋势图 / 收益分布 / 收益明细 / 分账明细
- **资产市场**：浏览/搜索/订阅资产
- **资产登记**：登记新资产 + 上架/审核表单
- **结算分账**：查询结算与分账明细，触发结算与分账
- **审计日志**：全过程审计日志查看 + 哈希链完整性校验

## 技术栈

- Vue 3.4 + TypeScript 5.4
- Pinia 2.1（状态管理）
- Vue Router 4.3
- Element Plus 2.7（UI 组件库）
- ECharts 5.5（图表）
- Vite 6.0（构建工具）
- Axios 1.19（HTTP 客户端）

## 快速开始

```bash
# 安装依赖
npm install

# 开发模式（端口 5174，自动代理到后端 8087）
npm run dev

# 构建
npm run build

# 预览
npm run preview
```

## 目录结构

```text
frontend/asset-exchange-dashboard/
├── src/
│   ├── api/                  # API 客户端
│   │   ├── client.ts         # axios 实例
│   │   └── assetExchange.ts  # 资产流通 API 封装
│   ├── views/                # 页面
│   │   ├── Dashboard.vue     # 流通看板（Top N/趋势/收益/分账）
│   │   ├── AssetList.vue     # 资产市场
│   │   ├── RegisterForm.vue  # 资产登记/上架表单
│   │   ├── SettlementList.vue# 结算分账明细
│   │   └── AuditLogs.vue     # 审计日志
│   ├── router/               # 路由
│   ├── styles/               # 样式
│   ├── App.vue
│   └── main.ts
├── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts
```

## 后端依赖

- `platform/asset-exchange`（默认端口 8087）
- 开发模式下 Vite 自动代理 `/api` 到后端

## 设计依据

- Phase 2 Batch 1b 任务 T039
- `design/详细设计/多平台多租户大数据平台_数据资产流通详细设计_v0.1.md`