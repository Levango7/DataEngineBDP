# 数擎大数据平台升级指南（V1.0 → V2.0）

> 版本：从 V1.0.0 升级至 V2.0.0 | 适用对象：平台运维工程师 | 更新日期：2026-08-08

## 第1章 升级概述

### 1.1 升级范围

本指南覆盖数擎大数据平台从 V1.0.0 至 V2.0.0 的完整升级流程。V2.0 在 V1.0 基础上新增云原生、AI、数据联邦、实时数仓、行业模板五大能力，并完成 99 个工程任务交付。

### 1.2 V2.0 关键变更

| 能力域 | V1.0 | V2.0 |
|--------|------|------|
| 部署模式 | Helm 直部署 | Helm + ArgoCD GitOps + Istio Service Mesh |
| AI 能力 | 无 | NL2SQL、AI 助手、多模态切片器、混合检索重排 |
| 数据联邦 | 无 | 手写 SQL 解析 + 跨源归并引擎、跨源 Join、5 种外部源虚拟化 |
| 实时数仓 | 离线为主 | Flink CDC + Iceberg V2 upsert + Doris 物化视图 |
| 行业模板 | 无 | 金融/能源/政务三个行业模板 |
| 安全合规 | JWT + RBAC | 等保三级 + 国密（SM2/SM3/SM4） |
| SQL 网关 | 基础路由 | Trino+Doris 路由 + 查询改写 + 跨源查询 |
| 多租户 | Namespace 隔离 | Namespace + ResourceQuota + NetworkPolicy 三重隔离 |

### 1.3 兼容性

- **API 兼容**：V1.0 全部 API 端点在 V2.0 保持向后兼容，仅新增端点
- **数据兼容**：V1.0 数据格式在 V2.0 可直接读取
- **配置兼容**：V1.0 values.yaml 参数在 V2.0 保留，新增参数有默认值
- **Helm 兼容**：V2.0 Chart 可读取 V1.0 Release 状态

## 第2章 升级前检查清单

### 2.1 V1.0 版本确认

```bash
# 检查当前 Helm Release 版本
helm list -n shuqing-system
# 期望输出：shuqing-bigdata  v1.0.0  deployed

# 检查 Pod 镜像版本
kubectl get deployment -n shuqing-system -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'

# 检查健康端点
curl http://encaps-layer:8080/api/v1/health
# 期望响应包含 "version":"1.0.0"
```

确认当前版本为 V1.0.0。若版本低于 V1.0.0，需先升级至 V1.0.0。

### 2.2 数据备份确认

```bash
# 1) etcd 快照
ETCDCTL_API=3 etcdctl snapshot save /backup/pre-upgrade-etcd-$(date +%Y%m%d).db

# 2) PVC 备份（Velero）
velero backup create pre-upgrade-v2 -n shuqing-system,shuqing-infra

# 3) Doris 元数据备份
mysqldump -h doris-fe -P 9030 -u root -p information_schema > /backup/doris-meta-$(date +%Y%m%d).sql

# 4) Catalog 数据库备份
kubectl exec -n shuqing-system deployment/catalog -- cp /app/catalog.db /tmp/catalog.db.bak
kubectl cp shuqing-system/$(kubectl get pod -n shuqing-system -l app=catalog -o jsonpath='{.items[0].metadata.name}'):/tmp/catalog.db.bak /backup/catalog-$(date +%Y%m%d).db

# 5) Keycloak 数据库备份
pg_dump -h keycloak-postgresql -U keycloak keycloak > /backup/keycloak-$(date +%Y%m%d).sql

# 6) 验证备份完整性
ls -lh /backup/pre-upgrade-*
velero backup describe pre-upgrade-v2 --details
```

### 2.3 兼容性检查

```bash
# 1) 检查 K8s 版本（需 >= 1.28）
kubectl version --short

# 2) 检查 Helm 版本（需 >= 3.14）
helm version --short

# 3) 检查 StorageClass
kubectl get storageclass

# 4) 检查 V1.0 自定义资源是否与 V2.0 冲突
kubectl get crd | grep shuqing

# 5) 运行兼容性检查工具
dqctl upgrade pre-check --from 1.0.0 --to 2.0.0
```

### 2.4 维护窗口确认

- **预计停机时间**：30~60 分钟（数据迁移量决定）
- **建议维护窗口**：业务低峰期（如凌晨 02:00~05:00）
- **回滚预案**：准备 V1.0 Helm Chart 与备份，可在 15 分钟内回滚
- **通知范围**：全部租户用户、业务方、运维团队

## 第3章 升级路径

V2.0 采用分阶段发布，建议按以下路径渐进升级：

```
V1.0.0 → V2.0.0-alpha → V2.0.0-beta → V2.0.0-rc → V2.0.0-ga
```

| 阶段 | 版本 | 用途 | 建议环境 |
|------|------|------|----------|
| Alpha | v2.0.0-alpha | 功能验证，可能有缺陷 | 开发环境 |
| Beta | v2.0.0-beta | 功能完整，性能待优化 | 测试环境 |
| RC | v2.0.0-rc | 候选发布，仅修缺陷 | 预发环境 |
| GA | v2.0.0-ga | 正式发布 | 生产环境 |

每个阶段建议至少运行 3 天，验证通过后再升级至下一阶段。

## 第4章 Helm 升级步骤

### 4.1 标准升级流程

```bash
# 0) 设置变量
export NAMESPACE=shuqing-system
export RELEASE=shuqing-bigdata
export CHART=shuqing/shuqing-bigdata
export NEW_VERSION=2.0.0

# 1) 备份 V1.0 数据（参见第2章）

# 2) 更新 Helm repo
helm repo update

# 3) 准备 V2.0 values.yaml（在 V1.0 基础上新增 V2.0 参数）
# 参见 4.2 节 values.yaml 迁移

# 4) dry-run 检查（不实际执行，仅校验）
helm upgrade ${RELEASE} ${CHART} \
  -n ${NAMESPACE} \
  -f values.yaml \
  --version ${NEW_VERSION} \
  --dry-run > upgrade-dry-run.yaml 2>&1

# 检查 dry-run 输出，确认无错误
cat upgrade-dry-run.yaml | grep -i error

# 5) 执行升级
helm upgrade ${RELEASE} ${CHART} \
  -n ${NAMESPACE} \
  -f values.yaml \
  --version ${NEW_VERSION} \
  --timeout 30m \
  --wait

# 6) 验证升级
kubectl get pods -n ${NAMESPACE}
kubectl wait --for=condition=Ready pod -n ${NAMESPACE} --timeout=600s

# 7) 健康检查
curl http://encaps-layer:8080/api/v1/health
curl http://sql-gateway:8081/api/v1/health
curl http://catalog:8082/api/v1/health
curl http://rule-engine:8083/api/v1/health
```

### 4.2 values.yaml 新参数

V2.0 在 V1.0 基础上新增以下参数（均有默认值，可按需调整）：

```yaml
# V2.0 新增：AI 能力
ai:
  enabled: true
  nl2sql:
    model: glm-4
    endpoint: http://llm-gateway:8086
  assistant:
    enabled: true

# V2.0 新增：数据联邦
dataFederation:
  enabled: true
  virtualTable:
    cacheEnabled: true
    cacheTtl: 600s

# V2.0 新增：实时数仓
realtime:
  enabled: true
  flink:
    replicas: 2
  iceberg:
    version: V2
    upsertMode: true

# V2.0 新增：行业模板
industryTemplates:
  enabled: true
  finance: true
  energy: true
  government: true

# V2.0 新增：Service Mesh
istio:
  enabled: true
  injection: true

# V2.0 新增：国密
security:
  crypto:
    provider: GM  # GM(国密) / STANDARD
```

### 4.3 ArgoCD GitOps 升级

若使用 ArgoCD 管理，升级流程：

```bash
# 1) 更新 Git 仓库中的 Chart 版本
cd shuqing-deploy
sed -i 's/version: 1.0.0/version: 2.0.0/' overlays/production/Chart.yaml
git commit -am "Upgrade to V2.0.0"
git push origin main

# 2) ArgoCD 自动同步
argocd app sync shuqing-bigdata

# 3) 监控同步状态
argocd app get shuqing-bigdata
```

## 第5章 数据迁移

### 5.1 元数据迁移（Catalog 表结构变更）

V2.0 Catalog 表结构有变更，平台提供自动迁移工具：

```bash
# 1) 备份 V1.0 Catalog 数据
kubectl cp shuqing-system/$(kubectl get pod -n shuqing-system -l app=catalog -o jsonpath='{.items[0].metadata.name}'):/app/catalog.db /backup/catalog-v1.db

# 2) 升级 Catalog 镜像（Helm 升级时自动完成）

# 3) 触发自动迁移（GORM AutoMigrate）
kubectl exec -n shuqing-system deployment/catalog -- \
  curl -X POST http://localhost:8082/api/v1/catalog/migrate

# 4) 验证迁移结果
kubectl exec -n shuqing-system deployment/catalog -- \
  sqlite3 /app/catalog.db ".tables"
```

### 5.2 配置迁移（values.yaml 新参数）

V1.0 values.yaml 中部分参数在 V2.0 重命名或废弃：

| V1.0 参数 | V2.0 参数 | 说明 |
|-----------|-----------|------|
| `gateway.engine` | `sqlGateway.defaultEngine` | 重命名 |
| `tenant.isolation` | `tenant.isolationMode: namespace-quota-network` | 默认值升级为三重隔离 |
| `security.jwt.issuer` | `security.jwt.issuer`（不变） | 保持兼容 |
| - | `ai.enabled` | 新增，默认 true |
| - | `dataFederation.enabled` | 新增，默认 true |
| - | `realtime.enabled` | 新增，默认 true |

迁移工具：

```bash
# 自动迁移 values.yaml
dqctl upgrade migrate-values \
  --from values-v1.yaml \
  --to values-v2.yaml \
  --from-version 1.0.0 \
  --to-version 2.0.0

# 检查迁移结果
diff values-v1.yaml values-v2.yaml
```

### 5.3 作业迁移（DAG 格式变更）

V2.0 DolphinScheduler DAG 格式有微调，平台提供迁移脚本：

```bash
# 1) 导出 V1.0 DAG
dqctl dag export --version 1.0 --output /tmp/dags-v1/

# 2) 转换 DAG 格式
dqctl dag migrate --input /tmp/dags-v1/ --output /tmp/dags-v2/ --to-version 2.0

# 3) 导入 V2.0
dqctl dag import --input /tmp/dags-v2/ --namespace ns-<tenant>
```

### 5.4 Keycloak Realm 迁移

V2.0 新增 AI 助手、行业模板等客户端，需更新 Keycloak Realm：

```bash
# 1) 导出 V1.0 Realm
kubectl exec -n shuqing-system deployment/keycloak -- \
  /opt/keycloak/bin/kc.sh export --realm shuqing --file /tmp/realm-v1.json

# 2) 合并 V2.0 新增客户端
jq -s '.[0] * .[1]' /tmp/realm-v1.json realm-v2-additions.json > /tmp/realm-v2.json

# 3) 导入 V2.0 Realm
kubectl cp /tmp/realm-v2.json shuqing-system/$(kubectl get pod -n shuqing-system -l app=keycloak -o jsonpath='{.items[0].metadata.name}'):/tmp/realm-v2.json
kubectl exec -n shuqing-system deployment/keycloak -- \
  /opt/keycloak/bin/kc.sh import --file /tmp/realm-v2.json
```

## 第6章 升级后验证

### 6.1 功能验证清单

| 验证项 | 验证方法 | 期望结果 |
|--------|----------|----------|
| 全部 Pod 就绪 | `kubectl get pods -A` | 全部 Running |
| 健康检查 | curl 各组件 /health | status=UP, version=2.0.0 |
| 租户列表 | `dqctl tenant list` | V1.0 租户全部保留 |
| SQL 执行 | `dqctl sql execute --sql "SELECT 1"` | 返回 1 |
| 跨源查询 | 执行跨源 SQL | 成功返回结果 |
| AI 助手 | NL2SQL 查询 | 生成 SQL 并执行 |
| 行业模板 | `helm list` | 模板 Chart 部署成功 |
| Doris 查询 | `mysql -h doris-fe -e "SHOW DATABASES"` | 数据库保留 |
| Trino 查询 | `trino-cli --execute "SELECT 1"` | 返回 1 |
| 调度作业 | DolphinScheduler UI | V1.0 DAG 全部保留 |
| BI 看板 | Superset UI | V1.0 看板全部保留 |
| 监控告警 | Grafana UI | 看板数据正常 |
| 日志收集 | Loki 查询 | 日志正常写入 |

### 6.2 性能基线对比

```bash
# 1) 运行性能基线测试
dqctl benchmark run --suite standard --output /tmp/benchmark-v2.json

# 2) 对比 V1.0 基线
dqctl benchmark compare \
  --baseline /tmp/benchmark-v1.json \
  --current /tmp/benchmark-v2.json

# 期望：V2.0 性能不低于 V1.0 的 90%
```

### 6.3 回滚方案

若升级失败或验证不通过，执行回滚：

```bash
# 1) Helm 回滚
helm rollback ${RELEASE} 0 -n ${NAMESPACE}  # 0 表示上一个版本

# 2) 若 Helm 回滚失败，从备份恢复
velero restore create --from-backup pre-upgrade-v2

# 3) 恢复 Catalog 数据
kubectl cp /backup/catalog-v1.db shuqing-system/$(kubectl get pod -n shuqing-system -l app=catalog -o jsonpath='{.items[0].metadata.name}'):/app/catalog.db

# 4) 恢复 Keycloak
kubectl exec -n shuqing-system deployment/keycloak -- \
  /opt/keycloak/bin/kc.sh import --file /tmp/realm-v1.json

# 5) 验证回滚
curl http://encaps-layer:8080/api/v1/health
# 期望：version=1.0.0
```

## 第7章 已知限制与注意事项

### 7.1 已知限制

1. **AI 模型依赖**：NL2SQL 需要外部 LLM 服务（GLM/Qwen/GPT），需提前部署或配置 API Key
2. **国密兼容**：启用国密（SM2/SM3/SM4）后，与外部标准 TLS 客户端不兼容，需双向国密客户端
3. **跨源查询性能**：跨源 Join 大数据量时性能不如数据搬运后单源查询，建议对高频跨源场景创建物化视图
4. **Istio Sidecar 资源**：每个 Pod 注入 Sidecar 额外消耗约 0.1C/100Mi，规划资源时需预留
5. **Flink State 迁移**：V1.0 无 Flink 作业，V2.0 新建 Flink 作业无状态迁移问题；若 V1.0 已自建 Flink 作业，需手动迁移 Savepoint

### 7.2 注意事项

1. **升级顺序**：先升级 CRD，再升级系统组件，最后升级业务组件
2. **数据库迁移**：Catalog 元数据迁移不可逆，务必先备份
3. **网络策略**：V2.0 默认启用 NetworkPolicy，可能影响 V1.0 自定义网络策略，需检查
4. **镜像拉取**：V2.0 镜像较大（约 5GB），预拉取可减少升级停机时间
5. **配置校验**：升级前务必通过 `helm upgrade --dry-run` 校验配置
6. **回滚窗口**：建议升级后保留 7 天回滚窗口，期间保留 V1.0 备份
7. **租户通知**：升级期间租户无法访问，需提前 24 小时通知

## 第8章 分阶段升级建议

### 8.1 阶段一：Alpha 验证（开发环境）

```bash
# 在开发环境部署 V2.0-alpha
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  -n shuqing-dev -f values-alpha.yaml --version 2.0.0-alpha

# 验证全部 V2.0 新功能
dqctl test functional --suite v2-new-features
```

### 8.2 阶段二：Beta 验证（测试环境）

```bash
# 在测试环境部署 V2.0-beta，导入生产数据样本
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  -n shuqing-test -f values-beta.yaml --version 2.0.0-beta

# 运行集成测试与性能测试
dqctl test integration --suite full
dqctl benchmark run --suite standard
```

### 8.3 阶段三：RC 验证（预发环境）

```bash
# 在预发环境部署 V2.0-rc，使用生产配置
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  -n shuqing-staging -f values-production.yaml --version 2.0.0-rc

# 业务方验收
# 运行 7 天稳定性观察
```

### 8.4 阶段四：GA 发布（生产环境）

```bash
# 在生产环境执行标准升级流程（参见第4章）
# 升级后观察 24 小时，确认稳定后关闭回滚窗口
```

## 第9章 附录

### 9.1 升级检查脚本

```bash
#!/bin/bash
# upgrade-check.sh — 升级前自动检查
set -e

echo "=== 1. 检查当前版本 ==="
helm list -n shuqing-system | grep shuqing-bigdata

echo "=== 2. 检查 K8s 版本 ==="
kubectl version --short | grep Server

echo "=== 3. 检查 Helm 版本 ==="
helm version --short

echo "=== 4. 检查 StorageClass ==="
kubectl get storageclass

echo "=== 5. 检查 Pod 状态 ==="
kubectl get pods -A | grep -v Running | grep -v Completed

echo "=== 6. 检查备份 ==="
ls -lh /backup/pre-upgrade-*

echo "=== 7. dry-run 检查 ==="
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  -n shuqing-system -f values.yaml --version 2.0.0 --dry-run > /dev/null 2>&1 && echo "dry-run OK" || echo "dry-run FAILED"

echo "=== 检查完成 ==="
```

### 9.2 相关文档

- 《运维手册》（ops-manual.md）
- 《用户手册》（user-manual.md）
- 《API 参考文档》（api-reference.md）
- 《行业模板使用指南》（industry-template-guide.md）
- 《变更日志》（../../CHANGELOG.md）