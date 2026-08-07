# 数擎大数据平台 · ArgoCD GitOps 部署配置

> 版本：v0.1 ｜ 日期：2026-08-07 ｜ 状态：可部署
> 所属：T003 ArgoCD GitOps 部署
> 依赖：T001 k3s 集群已就绪（`ske/k3s/`）

---

## 1. 目标

在 T001 搭建的 k3s 集群上部署 ArgoCD，实现：

1. **ArgoCD 安装**：stable manifest 安装到 `argocd` namespace
2. **应用仓库配置**：GitHub `Levango7/DataEngineBDP` 作为唯一配置源
3. **同步策略**：自动同步 + 自愈（selfHeal） + 修剪（prune）
4. **环境分离**：dev / staging / prod 三环境通过 Kustomize overlay 隔离
5. **回滚机制**：基于 Git history 的回滚 + ArgoCD 原生回滚
6. **UI 可访问**：port-forward / NodePort 暴露 argocd-server

---

## 2. 目录结构

```text
design/deploy/argocd/
├── README.md                          # 本文档
├── install/
│   ├── namespace.yaml                 # argocd namespace（pod-security 标签）
│   └── install-argocd.sh              # 一键安装脚本（manifest 方式）
├── projects/                          # AppProject 多环境隔离
│   ├── platform-dev.yaml              # dev 环境 Project
│   ├── platform-staging.yaml          # staging 环境 Project
│   └── platform-prod.yaml             # prod 环境 Project
├── repositories/
│   └── repo-credentials.yaml          # Git 仓库注册 + 凭证 Secret
├── applications/                      # ArgoCD Application（每环境一个根 App）
│   ├── root-dev.yaml                  # dev 根 Application（Kustomize overlay）
│   ├── root-staging.yaml              # staging 根 Application
│   └── root-prod.yaml                 # prod 根 Application
├── applicationsets/
│   └── platform-engines.yaml          # ApplicationSet 多环境批量生成
├── kustomize/                         # Kustomize 环境分离
│   ├── base/                          # 基础资源
│   │   ├── kustomization.yaml
│   │   ├── namespace.yaml
│   │   └── deployment.yaml
│   └── overlays/                      # 环境覆盖
│       ├── dev/
│       │   ├── kustomization.yaml
│       │   └── patch.yaml
│       ├── staging/
│       │   ├── kustomization.yaml
│       │   └── patch.yaml
│       └── prod/
│           ├── kustomization.yaml
│           └── patch.yaml
├── sync-policies/
│   └── sync-policy.yaml               # 同步策略参考定义
├── rollback/
│   ├── rollback-strategy.md           # 回滚策略文档
│   └── rollback.sh                    # 回滚操作脚本
└── verify/
    └── verify-argocd.sh               # ArgoCD 部署验证脚本
```

---

## 3. 快速开始

### 3.1 前置

- T001 k3s 集群已就绪（`kubectl get nodes` 返回 Ready）
- 在 WSL2 Ubuntu 终端内执行（k3s kubeconfig 已写入 `~/.kube/config`）
- 项目挂载路径：`/mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform`

### 3.2 一键部署

```bash
cd /mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform

# Step 1: 创建 namespace
kubectl apply -f design/deploy/argocd/install/namespace.yaml

# Step 2: 安装 ArgoCD（stable manifest）
bash design/deploy/argocd/install/install-argocd.sh

# Step 3: 配置应用仓库凭证
kubectl apply -f design/deploy/argocd/repositories/repo-credentials.yaml

# Step 4: 创建 AppProject（dev/staging/prod 三环境）
kubectl apply -f design/deploy/argocd/projects/

# Step 5: 部署根 Application（Kustomize overlay）
kubectl apply -f design/deploy/argocd/applications/

# Step 6: 验证
bash design/deploy/argocd/verify/verify-argocd.sh
```

### 3.3 访问 ArgoCD UI

```bash
# 方式一：port-forward（推荐本地访问）
kubectl port-forward svc/argocd-server -n argocd 8080:443
# 浏览器访问 https://localhost:8080

# 方式二：NodePort（k3s 无 LoadBalancer，用 NodePort 暴露）
kubectl patch svc argocd-server -n argocd -p '{"spec":{"type":"NodePort"}}'

# 获取 admin 密码
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d; echo
```

---

## 4. 关键设计

### 4.1 应用仓库

| 项 | 值 |
| --- | --- |
| 仓库 URL | `https://github.com/Levango7/DataEngineBDP` |
| 默认分支 | `main` |
| 配置路径 | `design/deploy/argocd/kustomize/overlays/<env>` |
| 凭证方式 | HTTPS + Personal Access Token（PAT，存 Secret） |

> 生产环境应改用 SSH deploy key 或 GitHub App 凭证，PAT 仅用于 dev/staging 联调。

### 4.2 同步策略

```yaml
syncPolicy:
  automated:
    prune: true        # 修剪：Git 中删除的资源在集群中同步删除
    selfHeal: true     # 自愈：集群漂移自动回滚到 Git 声明
  syncOptions:
    - CreateNamespace=true
    - ApplyOutOfSyncOnly=true
  retry:
    limit: 3
    backoff:
      duration: 5s
      factor: 2
      maxDuration: 3m
```

### 4.3 环境分离（Kustomize overlay）

```text
kustomize/
├── base/                # 三环境共享基础资源
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   └── deployment.yaml
└── overlays/
    ├── dev/             # dev：1 副本 + 低资源 + debug 日志
    ├── staging/        # staging：2 副本 + 中资源 + info 日志
    └── prod/           # prod：3 副本 + 高资源 + warn 日志 + PDB
```

每环境通过 `patch.yaml` 覆盖副本数、资源限额、镜像 tag、环境变量。

### 4.4 回滚机制

| 场景 | 方式 | 命令 |
| --- | --- | --- |
| 应用层回滚 | Git revert + ArgoCD 自动同步 | `git revert <commit> && git push` |
| ArgoCD 原生回滚 | 回退到历史 sync revision | `argocd app rollback <app> <revision>` |
| 紧急回滚 | 暂停自动同步 + 手动 apply 旧版本 | 见 `rollback/rollback.sh` |

详见 `rollback/rollback-strategy.md`。

---

## 5. 验证清单

| 检查项 | 命令 | 期望 |
| --- | --- | --- |
| ArgoCD Pod | `kubectl get pods -n argocd` | 全 Running |
| argocd-server | `kubectl get deploy argocd-server -n argocd` | readyReplicas≥1 |
| AppProject | `kubectl get appproject -n argocd` | platform-dev/staging/prod |
| Application | `kubectl get application -n argocd` | root-dev/staging/prod Synced |
| UI 可访问 | `kubectl port-forward svc/argocd-server -n argocd 8080:443` | https://localhost:8080 可登录 |
| 同步状态 | `argocd app get root-dev` | Status=Healthy Synced |

一键验证：`bash design/deploy/argocd/verify/verify-argocd.sh`

---

## 6. 与 T001 / V2.0 的关系

```
┌──────────────────────────────────────────────────┐
│  ArgoCD GitOps（本目录）                          │
│  ├─ Application（Kustomize overlay 多环境）       │
│  ├─ AppProject（dev/staging/prod 隔离）           │
│  └─ 同步策略（auto + selfHeal + prune）           │
├──────────────────────────────────────────────────┤
│  Istio Service Mesh（T001，ske/k3s/）             │
│  ├─ istiod + sidecar 注入 + mTLS                  │
├──────────────────────────────────────────────────┤
│  k3s 单主节点（T001，ske/k3s/install-k3s.sh）     │
└──────────────────────────────────────────────────┘
```

ArgoCD 自身部署在 `argocd` namespace，**不注入** Istio sidecar（避免控制面循环依赖）：

```bash
kubectl label namespace argocd istio-injection=disabled --overwrite
```