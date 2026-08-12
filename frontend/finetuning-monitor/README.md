# 微调过程监控前端

> T033 微调→评测→部署闭环过程监控前端，Vue3 + ECharts + WebSocket

## 1. 概述

本前端提供微调→评测→部署一键闭环的可视化监控：
- **闭环任务列表页**：展示所有闭环任务及状态
- **闭环任务详情页**：实时展示微调过程（loss/lr/GPU 利用率 ECharts 图表）
- **WebSocket 实时推送**：微调指标实时更新
- **版本管理页**：模型版本历史、版本对比
- **部署管理页**：已部署模型列表、部署/停止操作

## 2. 技术栈

- Vue 3.4 + TypeScript
- Vite 6.0
- ECharts 5.5（训练指标可视化）
- Element Plus 2.7（UI 组件库）
- Pinia 2.1（状态管理）
- vue-router 4.3（路由）
- axios 1.19（HTTP 客户端）
- WebSocket（实时进度推送）

## 3. 页面

| 路径 | 页面 | 说明 |
|------|------|------|
| `/loop-tasks` | 闭环任务列表 | 展示所有闭环任务及状态 |
| `/loop-tasks/:taskId` | 闭环任务详情 | 实时展示微调过程（ECharts 图表） |
| `/versions` | 版本管理 | 模型版本历史、版本对比、回滚 |
| `/deployments` | 部署管理 | 已部署模型列表、部署/停止操作 |

## 4. 启动

```bash
cd frontend/finetuning-monitor
npm install
npm run dev    # 开发模式，端口 5175
npm run build  # 生产构建
```

## 5. 配置

通过环境变量配置：

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `VITE_API_BASE` | /api/v1/loop | 闭环编排 API 基础路径 |
| `VITE_API_TARGET` | http://localhost:18088 | 开发代理目标 |
| `VITE_WS_BASE` | ws://localhost:18088 | WebSocket 基础地址 |
| `VITE_REGISTRY_BASE` | /api/v1/registry | 模型仓库 API 基础路径 |

## 6. 目录结构

```
finetuning-monitor/
├── src/
│   ├── main.ts              # 入口
│   ├── App.vue              # 根组件
│   ├── env.d.ts             # 类型声明
│   ├── api/
│   │   ├── client.ts        # HTTP 客户端
│   │   └── loop.ts          # 闭环 API
│   ├── router/
│   │   └── index.ts         # 路由配置
│   ├── components/
│   │   └── EChart.vue       # ECharts 通用组件
│   ├── views/
│   │   ├── LoopTasks.vue            # 闭环任务列表页
│   │   ├── LoopTaskDetail.vue       # 闭环任务详情页
│   │   ├── VersionManagement.vue    # 版本管理页
│   │   └── DeploymentManagement.vue # 部署管理页
│   └── types/
│       └── index.ts         # 类型定义
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
└── tsconfig.node.json
```