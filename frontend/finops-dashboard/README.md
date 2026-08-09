# FinOps 看板前端

## 1. 概述

数据引擎大数据平台 FinOps 看板前端，基于 Vue3 + ECharts + Element Plus 实现。

## 2. 技术栈

- Vue 3.4 + TypeScript 5.4
- Pinia 2.1（状态管理）
- Vue Router 4.3
- ECharts 5.5（图表）
- Element Plus 2.7（UI 组件库）
- Vite 6.0（构建工具）
- Axios 1.19（HTTP 客户端）

## 3. 目录结构

```
frontend/finops-dashboard/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html
└── src/
    ├── main.ts                          # 应用入口
    ├── App.vue                          # 根组件
    ├── env.d.ts                         # 类型声明
    ├── api/
    │   ├── client.ts                    # Axios HTTP 客户端
    │   └── finops.ts                    # FinOps 看板 API 封装
    ├── components/
    │   ├── EChart.vue                   # ECharts 可复用组件
    │   └── TimeWindowPicker.vue         # 时间窗口选择器
    ├── router/
    │   └── index.ts                     # 路由配置
    ├── types/
    │   └── index.ts                     # 类型定义
    └── views/
        ├── FinOpsDashboard.vue          # 看板总览（布局+导航）
        ├── Top10Resources.vue           # Top10 成本资源
        ├── CostTrend.vue                # 成本趋势
        ├── CostDetails.vue              # 成本明细
        ├── IdleResources.vue            # 闲置清单
        ├── OptimizationSuggestions.vue  # 优化建议
        ├── BillExport.vue               # 账单导出
        └── AllocationConfig.vue         # 分账配置
```

## 4. 功能页面

| 页面 | 路由 | 说明 |
|------|------|------|
| 看板总览 | /dashboard | 布局+导航 |
| Top10 成本资源 | /top10 | 柱状图+饼图+表格 |
| 成本趋势 | /trend | 折线图+堆叠面积图 |
| 成本明细 | /details | 资源粒度明细表 |
| 闲置清单 | /idle | 5 类闲置模式饼图+清单表 |
| 优化建议 | /suggestions | 建议卡片展示 |
| 账单导出 | /bill-export | CSV/Excel 导出 |
| 分账配置 | /allocation | 分账配置管理+执行 |

## 5. 开发与构建

```bash
# 安装依赖
npm install

# 开发模式
npm run dev

# 类型检查
npm run type-check

# 生产构建
npm run build
```

## 6. 配置

- API baseURL：环境变量 `VITE_API_BASE`，默认 `/api/v1`
- 开发代理目标：环境变量 `VITE_API_TARGET`，默认 `http://localhost:8085`
- JWT token：从 `localStorage.finops_token` 读取