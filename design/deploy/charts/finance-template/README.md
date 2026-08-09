# finance-template Helm Chart（T019）

> 数据引擎大数据平台 L5.3 行业应用模板 - 金融行业模板部署 Chart
> 基于 T018 金融模板，提供 Helm Chart 打包 + 多环境 values 覆盖 + 部署验证。

## 1. 概述

本 Chart 是金融行业模板的部署包装，使 `helm install finance-template` 可一键部署模板资产到目标集群。

Chart 不部署实际工作负载（Doris/DolphinScheduler/Superset/Keycloak 由各自独立 Chart 部署），而是：

1. 将模板资产（21 DDL / 5 DAG / 3 Dashboard / 3 RBAC / docs）打包为 ConfigMap
2. 创建导入 Job，挂载 ConfigMap，依次将资产导入到对应组件
3. 通过 Helm hook（post-install/post-upgrade）自动触发导入
4. 提供部署验证 Job（`helm test`）校验导入结果
5. 支持 dev/staging/prod 三套环境 values 覆盖

### 模板资产清单

| 资产类型 | 数量 | 说明 |
|---|---|---|
| DDL 表 | 21 张 | 5 业务域（风控/客户/账户/交易/信贷） |
| DAG 调度 | 5 个 | 风控特征日计算、客户标签更新、账户日终清算、交易反洗钱、信贷评分月度 |
| Dashboard | 3 个 | 风控视角、客户视角、交易视角 |
| RBAC 角色 | 3 个 | 风控员、合规员、客户经理（最小权限原则） |
| 文档 | 3 份 | 业务模型、数据分级、脱敏规则 |

## 2. 目录结构

```
finance-template/
├── Chart.yaml                          # Chart 元信息
├── values.yaml                         # 默认 values（base，环境无关）
├── values-dev.yaml                     # dev 环境覆盖
├── values-staging.yaml                 # staging 环境覆盖
├── values-prod.yaml                    # prod 环境覆盖
├── .helmignore                         # 打包忽略列表
├── README.md                           # 本文档
├── sync-assets.sh                      # 资产同步脚本（T018 -> assets/）
├── package.sh                          # 打包脚本（同步 + lint + template + package）
├── assets/                             # 模板资产（由 sync-assets.sh 生成）
│   ├── ddl/                            #   DDL 表定义
│   ├── dag/                            #   DAG 调度作业
│   ├── dashboard/                      #   Dashboard 仪表盘
│   ├── rbac/                           #   RBAC 角色权限
│   └── docs/                           #   业务文档
├── ci/
│   └── lint.sh                         # CI lint 脚本
└── templates/
    ├── _helpers.tpl                    # 辅助函数
    ├── configmap-assets.yaml           # ConfigMap 挂载模板资产
    ├── secret-credentials.yaml         # Secret 凭据占位（dev）
    ├── serviceaccount.yaml             # ServiceAccount
    ├── rolebinding.yaml                # Role + RoleBinding
    ├── import-job.yaml                 # 导入 Job（核心）
    ├── verification-job.yaml           # 部署验证 Job（helm test）
    ├── NOTES.txt                       # 安装说明
    └── tests/
        ├── test-connectivity.yaml      # 连通性测试
        └── test-assets.yaml            # 资产完整性测试
```

## 3. 前置条件

- Kubernetes 集群已就绪（版本 ≥ 1.28）
- Helm 3.14+ 已安装
- Apache Doris 2.1+ 已部署
- Apache DolphinScheduler 3.2+ 已部署
- Apache Superset 4.0+ 已部署
- Keycloak 24+ 已部署
- 目标命名空间已创建（或使用 `--create-namespace`）
- 所需 Secret 已创建（doris-credentials / ds-credentials / superset-credentials / keycloak-credentials）

## 4. 快速开始

### 4.1 同步模板资产

```bash
# 从 T018 金融模板同步资产到 chart 内 assets/ 目录
bash design/deploy/charts/finance-template/sync-assets.sh
```

### 4.2 dev 环境部署

```bash
# dry-run 验证
helm install finance-template design/deploy/charts/finance-template \
  -f design/deploy/charts/finance-template/values-dev.yaml \
  --dry-run -n finance-dev

# 实际安装
helm install finance-template design/deploy/charts/finance-template \
  -f design/deploy/charts/finance-template/values-dev.yaml \
  --create-namespace -n finance-dev

# 查看状态
helm status finance-template -n finance-dev

# 运行部署验证
helm test finance-template -n finance-dev
```

### 4.3 staging 环境部署

```bash
helm install finance-template design/deploy/charts/finance-template \
  -f design/deploy/charts/finance-template/values-staging.yaml \
  --create-namespace -n finance-staging
```

### 4.4 prod 环境部署

```bash
helm install finance-template design/deploy/charts/finance-template \
  -f design/deploy/charts/finance-template/values-prod.yaml \
  --create-namespace -n finance-prod
```

## 5. 多环境 values 覆盖

| 配置项 | dev | staging | prod |
|---|---|---|---|
| 命名空间 | finance-dev | finance-staging | finance-prod |
| Secret 创建 | true（占位） | false（外部注入） | false（外部注入） |
| 导入 Job CPU | 500m | 1000m | 2000m |
| 导入 Job 内存 | 512Mi | 1Gi | 2Gi |
| Job 重试次数 | 1 | 3 | 5 |
| Job 超时 | 600s | 1200s | 3600s |
| 失败回滚 | false | true | true |
| 容错继续 | true | false | false |
| RBAC 同步 Doris | false | false | true |
| 镜像拉取策略 | Always | IfNotPresent | IfNotPresent |
| 镜像标签 | 5.3.0-dev | 5.3.0-staging | 5.3.0 |
| 节点选择器 | 无 | sq.io/env=staging | sq.io/env=prod + 专用池 |
| 国密 | false | false | false（可覆盖） |

## 6. 部署验证

### 6.1 helm test

```bash
# 运行所有测试（连通性 + 资产完整性 + 部署验证）
helm test finance-template -n finance-dev
```

### 6.2 验证项

| 验证项 | 期望值 | 说明 |
|---|---|---|
| 连通性 | 4 组件可达 | Doris / DS / Superset / Keycloak |
| DDL 表数 | 21 | Doris 中表数量 |
| DAG 数 | 5 | DolphinScheduler 中 DAG 数量 |
| Dashboard 数 | 3 | Superset 中 Dashboard 数量 |
| RBAC 角色数 | 3 | Keycloak 中角色数量 |

### 6.3 手动验证

```bash
# 验证 DDL 表
mysql -h <doris_fe> -P 9030 -u root -p --execute "SHOW TABLES FROM db_finance"

# 验证 DAG
curl http://<ds_host>:12345/dolphinscheduler/projects -H "Authorization: Bearer <token>"

# 验证 Dashboard
curl http://<superset_host>:8088/api/v1/dashboard/ -H "Authorization: Bearer <token>"

# 验证 RBAC
curl http://<keycloak_host>:8080/admin/realms/finance-template-realm/roles \
  -H "Authorization: Bearer <admin_token>"
```

## 7. 升级与卸载

```bash
# 升级
helm upgrade finance-template design/deploy/charts/finance-template \
  -f design/deploy/charts/finance-template/values-dev.yaml -n finance-dev

# 卸载
helm uninstall finance-template -n finance-dev
```

## 8. 打包

```bash
# 完整流程：同步资产 + lint + 多环境 template 验证 + 打包
bash design/deploy/charts/finance-template/package.sh package

# 仅 lint + template 验证
bash design/deploy/charts/finance-template/package.sh
```

## 9. CI 集成

```bash
# CI lint 校验
bash design/deploy/charts/finance-template/ci/lint.sh
```

## 10. 关键配置项

| 参数 | 默认值 | 说明 |
|---|---|---|
| `template.autoImport` | true | 是否启用自动导入 |
| `namespace` | finance | 目标命名空间 |
| `configMap.create` | true | 是否创建 ConfigMap |
| `secret.create` | false | 是否创建占位 Secret（dev=true） |
| `importJob.enabled` | true | 是否创建导入 Job |
| `importJob.image.repository` | shuqing/template-importer | 导入器镜像 |
| `importJob.backoffLimit` | 3 | Job 重试次数 |
| `importJob.onFailure.rollback` | true | 失败回滚 |
| `rbacSync.enabled` | true | 是否同步 RBAC |
| `rbacSync.syncToDoris` | false | 是否同步表级权限到 Doris |
| `verification.enabled` | true | 是否创建验证 Job |
| `serviceAccount.create` | true | 是否创建 ServiceAccount |

## 11. 关联文档

- T018 金融模板源：`platform/industry-templates/templates/finance/`
- T018 原始 Helm Chart：`platform/industry-templates/templates/finance/helm/`
- 模板元数据：`platform/industry-templates/templates/finance/template-metadata.yaml`
- 部署骨架：`design/deploy/README.md`
- 环境配置：`design/deploy/profiles/`