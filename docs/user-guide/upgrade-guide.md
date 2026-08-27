# 数据引擎大数据平台升级指南（V1.0 → V2.0）

> 版本：从 V1.0.0 升级至 V2.0.0 | 适用对象：平台运维工程师 | 更新日期：2026-08-08

## 第1章 升级概述

### 1.1 升级范围

本指南覆盖数据引擎大数据平台从 V1.0.0 至 V2.0.0 的完整升级流程。V2.0 在 V1.0 基础上新增云原生、AI、数据联邦、实时数仓、行业模板五大能力，并完成 99 个工程任务交付。

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

---

# 数据引擎大数据平台升级指南（V2.0 → V2.1.0-RC）

> 版本：从 V2.0.0 升级至 V2.1.0-RC | 适用对象：平台运维工程师 | 更新日期：2026-08-28

## 第1章 升级概述

### 1.1 升级范围

本指南覆盖数据引擎大数据平台从 V2.0.0 至 V2.1.0-RC 的完整升级流程。V2.1.0-RC 在 V2.0.0 基础上进行安全加固、生产化修复和行业生态扩展，重点完成 K8s 安全策略全量推广、容器非 root 化、配置中心 Apollo→Nacos 迁移、多架构构建扩容以及医疗/交通/教育/农牧四大行业模板扩展。

### 1.2 V2.1.0-RC 关键变更

| 变更类别 | 变更项 | V2.0.0 | V2.1.0-RC |
|----------|--------|--------|-----------|
| Security | K8s 安全策略模板 | 部分覆盖 | 81 个 Chart 添加 NetworkPolicy + ServiceMonitor，新增 namespace-security Chart |
| Security | Dockerfile HEALTHCHECK | 无 | 17 个应用 Dockerfile 添加健康检查 |
| Security | 硬编码密码 | docker-compose 明文 | 改为环境变量引用 |
| Security | 容器非 root | 部分 root | 18 个 Dockerfile 全部非 root 化 |
| Fixed | 前端覆盖率 | functions 49.56% | functions 50.43% |
| Fixed | 多架构构建 | 5 个组件 | 10 个组件（tag-engine / karmada×3 / open-api-catalog） |
| Changed | 配置中心 | Apollo | Nacos |
| Changed | v2.0.0 定级 | GA | RC（勘误） |
| Added | 行业模板 | 金融/能源/政务 | 新增医疗/交通/教育/农牧 |
| Added | 金丝雀交付 | 无 | Argo Rollouts 金丝雀发布 |

### 1.3 兼容性

- **API 兼容**：V2.0 全部 API 端点在 V2.1.0-RC 保持向后兼容，仅新增端点
- **配置兼容**：Apollo→Nacos 迁移需手动执行配置导入，平台提供 `dqctl config migrate` 工具
- **Helm 兼容**：V2.1.0-RC Chart 可读取 V2.0 Release 状态；新增 NetworkPolicy / ServiceMonitor 模板默认 `enabled: false`，不影响存量部署
- **数据兼容**：V2.0 数据格式在 V2.1.0-RC 可直接读取，无需数据迁移
- **镜像兼容**：容器非 root 化后，挂载卷的文件权限需调整为非 root 用户可读写

## 第2章 前置条件

### 2.1 版本确认

```bash
# 检查当前 Helm Release 版本
helm list -n shuqing-system
# 期望输出：shuqing-bigdata  v2.0.0  deployed

# 检查 Pod 镜像版本
kubectl get deployment -n shuqing-system -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'

# 检查健康端点
curl http://encaps-layer:8080/api/v1/health
# 期望响应包含 "version":"2.0.0"
```

确认当前版本为 V2.0.0。若版本低于 V2.0.0，需先按《V1.0 → V2.0 升级指南》升级至 V2.0.0。

### 2.2 环境要求

- **K8s 集群版本**：≥ 1.27（NetworkPolicy 与 ServiceMonitor 依赖）
- **Helm 版本**：≥ 3.12
- **ArgoCD 版本**：≥ 2.7（若启用 GitOps，金丝雀交付需 Argo Rollouts 1.4+）
- **容器运行时**：containerd 1.7+ 或 CRI-O 1.27+（支持非 root 容器）
- **存储类**：支持 PVC 权限调整（非 root UID/GID 可读写）

### 2.3 数据备份确认

```bash
# 1) 备份 V2.0.0 Helm values
helm get values ${RELEASE} -n ${NAMESPACE} -o yaml > /backup/values-v2.0.0-$(date +%Y%m%d).yaml

# 2) 备份 Apollo 配置（迁移前必须）
kubectl exec -n shuqing-system deployment/apollo-portal -- \
  curl -X GET http://localhost:8070/openapi/v1/configs/export > /backup/apollo-configs-$(date +%Y%m%d).zip

# 3) 备份数据库
mysqldump -h doris-fe -P 9030 -u root -p information_schema > /backup/doris-meta-$(date +%Y%m%d).sql
pg_dump -h keycloak-postgresql -U keycloak keycloak > /backup/keycloak-$(date +%Y%m%d).sql

# 4) PVC 备份（Velero）
velero backup create pre-upgrade-v21 -n shuqing-system,shuqing-infra

# 5) 验证备份完整性
ls -lh /backup/*-$(date +%Y%m%d)*
velero backup describe pre-upgrade-v21 --details
```

### 2.4 维护窗口确认

- **预计停机时间**：15~30 分钟（Apollo→Nacos 切换为主耗时）
- **建议维护窗口**：业务低峰期（如凌晨 02:00~05:00）
- **回滚预案**：准备 V2.0.0 Helm Chart 与 Apollo 配置备份，可在 10 分钟内回滚
- **通知范围**：全部租户用户、业务方、运维团队

## 第3章 升级步骤

### 3.1 备份

执行第 2.3 节的数据备份流程，确认以下备份文件齐全：

```bash
# 验证备份文件
ls -lh /backup/values-v2.0.0-*.yaml
ls -lh /backup/apollo-configs-*.zip
ls -lh /backup/doris-meta-*.sql
ls -lh /backup/keycloak-*.sql
velero backup get pre-upgrade-v21
```

### 3.2 版本更新

```bash
# 0) 设置变量
export NAMESPACE=shuqing-system
export RELEASE=shuqing-bigdata
export CHART=shuqing/shuqing-bigdata
export NEW_VERSION=2.1.0-rc

# 1) 更新 Helm repo
helm repo update

# 2) 准备 V2.1 values.yaml（在 V2.0 基础上新增 V2.1 参数）
# 参见 3.3、3.4 节配置迁移与安全策略启用

# 3) dry-run 检查
helm upgrade ${RELEASE} ${CHART} \
  -n ${NAMESPACE} \
  -f values.yaml \
  --version ${NEW_VERSION} \
  --dry-run > upgrade-dry-run-v21.yaml 2>&1

cat upgrade-dry-run-v21.yaml | grep -i error

# 4) 执行升级
helm upgrade ${RELEASE} ${CHART} \
  -n ${NAMESPACE} \
  -f values.yaml \
  --version ${NEW_VERSION} \
  --timeout 20m \
  --wait

# 5) 验证升级
kubectl get pods -n ${NAMESPACE}
kubectl wait --for=condition=Ready pod -n ${NAMESPACE} --timeout=600s
```

### 3.3 配置迁移（Apollo → Nacos）

V2.1.0-RC 将配置中心从 Apollo 切换为 Nacos，需手动执行配置导入。

#### 3.3.1 导出 Apollo 配置

```bash
# 1) 导出 Apollo 全量配置
kubectl exec -n shuqing-system deployment/apollo-portal -- \
  curl -X GET http://localhost:8070/openapi/v1/configs/export > /tmp/apollo-all.zip

# 2) 解压并查看
unzip /tmp/apollo-all.zip -d /tmp/apollo-configs/
ls -lh /tmp/apollo-configs/
```

#### 3.3.2 导入 Nacos

```bash
# 1) 部署 Nacos（Helm 升级时自动部署，或独立部署）
helm upgrade nacos shuqing/nacos \
  -n shuqing-infra \
  -f nacos-values.yaml \
  --version 2.3.2 \
  --wait

# 2) 使用迁移工具导入配置
dqctl config migrate \
  --from apollo \
  --from-dir /tmp/apollo-configs/ \
  --to nacos \
  --to-addr http://nacos:8848 \
  --namespace shuqing

# 3) 验证配置导入结果
dqctl config list --server nacos --namespace shuqing
```

#### 3.3.3 更新应用配置指向 Nacos

```yaml
# values.yaml 中更新配置中心指向
configCenter:
  provider: nacos  # apollo → nacos
  serverAddr: http://nacos:8848
  namespace: shuqing
  group: DEFAULT_GROUP
  dataId: shuqing-bigdata.yaml
```

```bash
# 重启应用以加载 Nacos 配置
kubectl rollout restart deployment -n ${NAMESPACE}
kubectl rollout status deployment -n ${NAMESPACE} --timeout=300s
```

### 3.4 安全策略启用

V2.1.0-RC 新增 NetworkPolicy 与 ServiceMonitor 模板，默认 `enabled: false`，按需启用。

#### 3.4.1 启用 NetworkPolicy

```yaml
# values.yaml 中启用 NetworkPolicy
networkPolicy:
  enabled: true
  # namespace-security Chart 配置
  namespaceSecurity:
    enabled: true
    # 默认拒绝全部入站，按白名单放行
    defaultDeny: true
    # 放行规则
    ingress:
      - from:
          - namespaceSelector:
              matchLabels:
                shuqing.io/system: "true"
```

```bash
# 验证 NetworkPolicy 生效
kubectl get networkpolicy -n ${NAMESPACE}
# 期望：81 个 Chart 对应的 NetworkPolicy 全部创建
```

#### 3.4.2 启用 ServiceMonitor

```yaml
# values.yaml 中启用 ServiceMonitor
serviceMonitor:
  enabled: true
  labels:
    release: prometheus  # Prometheus Operator 识别标签
  interval: 30s
  scrapeTimeout: 10s
```

```bash
# 验证 ServiceMonitor 生效
kubectl get servicemonitor -n ${NAMESPACE}
# 期望：81 个 Chart 对应的 ServiceMonitor 全部创建

# 验证 Prometheus 采集目标
kubectl exec -n shuqing-infra deployment/prometheus -- \
  wget -qO- http://localhost:9090/api/v1/targets | jq '.data.activeTargets | length'
```

#### 3.4.3 容器非 root 化验证

```bash
# 验证全部 Pod 非 root 运行
kubectl get pods -n ${NAMESPACE} -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].securityContext.runAsUser}{"\n"}{end}'
# 期望：全部 runAsUser 非 0（如 1000）

# 验证 Dockerfile HEALTHCHECK 生效
kubectl get pods -n ${NAMESPACE} -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[0].ready}{"\n"}{end}'
# 期望：全部 ready=true
```

### 3.5 验证

#### 3.5.1 Helm lint 全量 Chart

```bash
# 对 81 个 Chart 执行 lint
for chart in $(helm search repo shuqing/ -o json | jq -r '.[].name'); do
  helm lint ${chart} --version 2.1.0-rc || echo "LINT FAILED: ${chart}"
done

# 期望：全部 Chart lint 通过
```

#### 3.5.2 健康检查端点验证

```bash
# 验证 17 个应用的 HEALTHCHECK 端点
for svc in encaps-layer sql-gateway catalog rule-engine ai-assistant; do
  echo "=== ${svc} ==="
  curl -s http://${svc}:8080/api/v1/health | jq '.status, .version'
done

# 期望：status=UP, version=2.1.0-rc
```

#### 3.5.3 前端构建验证

```bash
# 前端构建并验证覆盖率
cd frontend
npm ci
npm run build
npm run test:coverage

# 期望：functions 覆盖率 ≥ 50.43%
# 检查覆盖率报告
cat coverage/coverage-summary.json | jq '.total.functions.pct'
```

#### 3.5.4 多架构构建验证

```bash
# 验证 10 个组件多架构镜像（amd64 + arm64）
for img in tag-engine karmada-apiserver karmada-aggregated-apiserver karmada-controller-manager open-api-catalog; do
  docker manifest inspect shuqing/${img}:2.1.0-rc | jq '.manifests[].platform'
done

# 期望：每个镜像包含 amd64 与 arm64 两个平台
```

#### 3.5.5 行业模板验证

```bash
# 验证新增行业模板（医疗/交通/教育/农牧）
helm search repo shuqing/ -o json | jq -r '.[].name' | grep -E 'medical|transport|education|farming'

# 部署示例（医疗模板）
helm upgrade shuqing-medical shuqing/industry-medical \
  -n shuqing-medical \
  --version 2.1.0-rc \
  --dry-run
```

#### 3.5.6 Argo Rollouts 金丝雀验证

```bash
# 验证 Argo Rollouts 控制器
kubectl get rollout -n ${NAMESPACE}

# 触发金丝雀发布
kubectl argo rollouts get rollout encaps-layer -n ${NAMESPACE} --watch
# 期望：按 20% → 40% → 60% → 80% → 100% 渐进切流
```

## 第4章 回滚方案

若升级失败或验证不通过，执行回滚：

### 4.1 Helm 回滚

```bash
# 1) Helm 回滚到 V2.0.0 Release
helm rollback ${RELEASE} 0 -n ${NAMESPACE}  # 0 表示上一个版本

# 2) 若 Helm 回滚失败，从 Velero 备份恢复
velero restore create --from-backup pre-upgrade-v21
```

### 4.2 恢复 Apollo 配置

```bash
# 1) 回滚 values.yaml 中配置中心指向
# 将 configCenter.provider 改回 apollo

# 2) 重启应用
kubectl rollout restart deployment -n ${NAMESPACE}
kubectl rollout status deployment -n ${NAMESPACE} --timeout=300s

# 3) 验证 Apollo 配置加载
kubectl logs -n ${NAMESPACE} -l app=encaps-layer | grep "Apollo config loaded"
```

### 4.3 验证回滚

```bash
# 验证版本回滚至 V2.0.0
curl http://encaps-layer:8080/api/v1/health
# 期望：version=2.0.0

helm list -n ${NAMESPACE}
# 期望：shuqing-bigdata  v2.0.0  deployed
```

## 第5章 已知限制

1. **AI / 模型组件**：10 个 AI / 模型组件（含 tag-engine、karmada×3、open-api-catalog 等）仍为 experimental，不建议生产环境启用
2. **四环境部署验证**：开发/测试/预发/生产四环境部署验证进行中，生产环境升级前需确认目标环境验证通过
3. **前端覆盖率**：functions 覆盖率 50.43%，未达 85% 目标，后续版本持续补齐
4. **默认 H2 / SQLite**：部分组件默认使用 H2 / SQLite，生产环境需切换为 PostgreSQL，参见《运维手册》数据库配置章节
5. **Apollo→Nacos 迁移不可逆**：迁移后 Apollo 配置不再同步，回滚需从备份恢复
6. **NetworkPolicy 影响**：启用 NetworkPolicy 后，自定义网络策略可能被拒绝，需按白名单逐项放行
7. **非 root 权限**：容器非 root 化后，挂载卷需调整 `fsGroup` 或 initContainer 修正权限
8. **v2.0.0 定级勘误**：v2.0.0 由 GA 勘误为 RC，已部署 v2.0.0-ga 的环境需确认实际版本号