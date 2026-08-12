# 多集群调度与故障迁移 - 运营后台

## 概述

本目录是 T027 多集群故障迁移的运营后台前端，使用 Vue3 + ECharts + Element Plus 构建。

## 功能页面

| 路径                  | 页面             | 说明                                       |
|-----------------------|------------------|--------------------------------------------|
| /                     | 集群健康看板     | ECharts 仪表盘展示 CPU/内存负载，状态色块  |
| /override-policies    | OverridePolicy 管理 | 集群本地化配置 CRUD                     |
| /failover-history     | 迁移历史         | 迁移事件时间线，手动触发迁移               |
| /replica-plans        | 副本权重分配     | 权重方案 CRUD，动态调整权重                |
| /failover-policies    | 故障迁移策略     | 主集群/备用集群/检测窗口/迁移超时配置      |

## 开发

```bash
cd frontend/multi-cluster-dashboard
npm install
npm run dev      # 开发服务器 http://localhost:5175
npm run build    # 生产构建
```

## 技术栈

- Vue 3.4 + TypeScript
- Element Plus 2.7
- ECharts 5.5
- Pinia 2.1
- Vue Router 4.3
- Vite 6.0