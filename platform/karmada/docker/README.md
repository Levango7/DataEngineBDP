# Karmada 多集群 Docker 模拟环境

## 概述

本目录用 Docker Compose 模拟 Karmada 控制面 + 3 个成员集群，用于集成测试和本地开发。

借鉴 Phase 1 经验：K3s 在 CI 环境不稳定，改用纯 Docker 容器模拟集群 API。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  Docker Compose 网络: karmada-net                        │
│                                                         │
│  ┌──────────────┐   ┌──────────────────────────────┐   │
│  │ karmada-api  │   │ mock-xinchang-cluster (8091) │   │
│  │ (8090)       │   │ 鲲鹏/麒麟 信创集群           │   │
│  │ 控制台API    │   ├──────────────────────────────┤   │
│  │              │   │ mock-local-cluster   (8092)  │   │
│  │              │   │ 本地标准 K8s 集群            │   │
│  │              │   ├──────────────────────────────┤   │
│  │              │   │ mock-cce-cluster     (8093)  │   │
│  │              │   │ 华为云 CCE 集群             │   │
│  └──────────────┘   └──────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## 集群配置

| 容器名               | 端口 | 集群名            | 类型      | 架构  | 用途       |
|----------------------|------|-------------------|-----------|-------|------------|
| it-karmada-api       | 8090 | -                 | -         | -     | 控制台 API |
| it-karmada-xinchang  | 8091 | xinchang-cluster  | xinchang  | arm64 | 信创集群   |
| it-karmada-local     | 8092 | local-cluster     | local     | amd64 | 本地集群   |
| it-karmada-cce       | 8093 | cce-cluster       | cloud     | amd64 | 公有云集群 |

## 使用方法

### 启动

```bash
docker compose -f platform/karmada/docker/docker-compose.yml up -d --build
```

### 验证

```bash
# 控制台 API 健康检查
curl http://localhost:8090/api/v1/health

# 信创集群健康检查
curl http://localhost:8091/healthz

# 查看集群信息
curl http://localhost:8091/apis/cluster
curl http://localhost:8092/apis/cluster
curl http://localhost:8093/apis/cluster
```

### 停止

```bash
docker compose -f platform/karmada/docker/docker-compose.yml down -v
```

## Mock 集群 API

每个 mock 集群提供以下端点：

| 方法   | 路径                        | 说明                   |
|--------|-----------------------------|------------------------|
| GET    | /healthz                    | 健康检查               |
| GET    | /apis/cluster               | 集群元数据（标签/状态）|
| GET    | /apis/deployments           | 部署列表               |
| POST   | /apis/deployments           | 接收部署               |
| GET    | /apis/propagation-policies  | 已同步策略列表         |
| POST   | /apis/propagation-policies  | 接收传播策略           |