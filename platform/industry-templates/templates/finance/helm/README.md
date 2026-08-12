# Helm Chart README - 金融行业模板
# ============================================================================
# 用途：使金融行业模板可被 helm install 引用部署
# ============================================================================

## 1. 概述

本 Helm Chart 是金融行业模板的包装，使 `helm install finance-template` 可一键部署模板资产到目标集群。

Chart 不部署实际工作负载（Doris/DolphinScheduler/Superset/Keycloak 由各自独立 Chart 部署），而是：

1. 将模板资产（DDL/DAG/Dashboard/RBAC/docs）打包为 ConfigMap
2. 创建一个导入 Job，挂载 ConfigMap，依次将资产导入到对应组件
3. 通过 Helm hook（post-install/post-upgrade）自动触发导入

## 2. 前置条件

- Kubernetes 集群已就绪（版本 ≥ 1.28）
- Helm 3.14+ 已安装
- Apache Doris 2.1+ 已部署
- Apache DolphinScheduler 3.2+ 已部署
- Apache Superset 4.0+ 已部署
- Keycloak 24+ 已部署
- 目标命名空间 finance 已创建
- 所需 Secret 已创建（doris-credentials / ds-credentials / superset-credentials / keycloak-credentials）

## 3. 安装

```bash
# 解压模板包
tar -xzf finance-template.tar.gz
cd finance-template/helm

# dry-run 验证
helm install finance-template . --dry-run -n finance

# 实际安装
helm install finance-template . -n finance

# 查看状态
helm status finance-template -n finance
```

## 4. 升级

```bash
helm upgrade finance-template . -n finance
```

## 5. 卸载

```bash
helm uninstall finance-template -n finance
```

## 6. 配置

编辑 `values.yaml` 自定义部署参数，主要配置项：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `template.autoImport` | true | 是否启用自动导入 |
| `namespace` | finance | 目标命名空间 |
| `target.doris.feHost` | doris-fe.doris.svc.cluster.local | Doris FE 地址 |
| `target.dolphinscheduler.host` | dolphinscheduler-api... | DolphinScheduler API 地址 |
| `target.superset.host` | superset.superset.svc... | Superset 地址 |
| `target.keycloak.host` | keycloak.keycloak.svc... | Keycloak 地址 |
| `importJob.enabled` | true | 是否创建导入 Job |
| `importJob.image.repository` | shuqing/template-importer | 导入器镜像 |
| `rbacSync.enabled` | true | 是否同步 RBAC 到 Keycloak |

## 7. Chart 结构

```
helm/
├── Chart.yaml              # Chart 元信息
├── values.yaml             # 默认配置
├── README.md               # 本文档
├── .helmignore             # 打包忽略列表
└── templates/
    ├── configmap-assets.tpl  # ConfigMap 挂载模板资产
    └── import-job.tpl        # 导入 Job
```