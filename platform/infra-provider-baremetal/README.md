# infra-provider-baremetal

L0.2 本地数据中心裸金属供应 Provider，属于 DataEngineBDP 平台的 `platform/` 模块。

## 概述

通过 **DMTF Redfish API** 对物理机进行带外管理（电源控制、BIOS、PXE 启动），结合 **PXE 装机** 与 **kubeadm** 初始化，为本地数据中心提供 Kubernetes 集群的自动化供应能力。

## 架构

```
┌──────────────────────────────────────────────────────┐
│                  REST API (Gin)                       │
│  /api/v1/clusters/baremetal  (JWT 鉴权)              │
├──────────────────────────────────────────────────────┤
│              BareMetalService (编排)                  │
├──────────────┬──────────────────┬────────────────────┤
│ RedfishClient│  K8sBootstrap    │   GORM (SQLite/PG) │
│ (BMC 带外)   │  (kubeadm/SSH)   │   (持久化)         │
└──────────────┴──────────────────┴────────────────────┘
```

## REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/clusters/baremetal` | 创建集群（指定物理机列表 + K8s 配置） |
| DELETE | `/api/v1/clusters/baremetal/{id}` | 销毁集群 |
| GET | `/api/v1/clusters/baremetal/{id}` | 查询集群状态 |
| GET | `/api/v1/clusters/baremetal` | 列出所有集群 |
| GET | `/api/v1/clusters/baremetal/{id}/nodes` | 查询节点列表 |
| POST | `/api/v1/clusters/baremetal/{id}/scale` | 扩缩容 |
| POST | `/api/v1/auth/login` | 签发 JWT Token |
| GET | `/healthz` `/readyz` | 健康探针 |

## 供应流程

1. **创建集群** → 持久化集群与节点（state=pending）
2. **硬件供应**（每节点）：Redfish 健康检查 → 采集硬件信息 → 设置 PXE 启动 → 开机
3. **OS 安装**：节点通过 PXE 引导完成 OS 安装（cloud-init）
4. **K8s 初始化**：首个控制平面 `kubeadm init` → 其余节点 `kubeadm join` → 安装 CNI
5. **销毁**：`kubeadm reset` → Redfish 关机 → 清理 DB

## 目录结构

```
platform/infra-provider-baremetal/
├── go.mod / go.sum
├── Dockerfile              # 多阶段构建，CGO_ENABLED=0
├── .golangci.yml           # golangci-lint 配置
├── README.md
└── src/
    ├── main.go             # 入口
    ├── config/
    │   ├── config.go       # 配置加载
    │   └── config.yaml     # 配置文件
    └── internal/
        ├── handler/        # REST API handler
        ├── service/        # 核心供应逻辑 + Redfish + K8s
        ├── model/          # 数据模型
        └── middleware/     # JWT 鉴权
```

## 构建

```bash
# 本地编译
go build -o infra-provider-baremetal ./src

# Docker 构建
docker build -t infra-provider-baremetal:0.1.0 .

# 运行
./infra-provider-baremetal --config ./src/config/config.yaml
```

## 测试

```bash
go test ./... -count=1
```

## 技术栈

- Go 1.23.4 + Gin v1.10
- GORM v1.25 + glebarez/sqlite（纯 Go SQLite 驱动，CGO_ENABLED=0）
- golang-jwt/v5（JWT HS256 鉴权）
- DMTF Redfish API（BMC 带外管理）
- kubeadm（K8s 集群初始化）