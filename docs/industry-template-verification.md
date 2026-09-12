# 行业模板验证方案

> 版本：2026-09-11 ｜ 状态：**方案设计** ｜ 关联文档：[行业模板使用指南](user-guide/industry-template-guide.md)、[组件成熟度矩阵](component-maturity.md)、[部署指南](deployment-guide.md)

## 一、文档目的

本文档定义数据引擎大数据平台（DataEngineBDP）行业模板的验证环境、验证步骤、验证标准与验证报告模板，确保行业模板在真实 K8s 集群上可部署、可访问、可持久化、可清理。

## 二、模板清单与口径统一

### 2.1 模板数量口径

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| **内置模板**（`platform/industry-templates/templates/`） | 5 套 | finance / retail / manufacturing / government / energy，含 DDL + DAG + Dashboard |
| **新增模板**（`platform/industry-templates/industry_templates/templates/`） | 4 套 | medical / transportation / education / agriculture |
| **模板合计** | **9 套** | 覆盖金融、零售、制造、政务、能源、医疗、交通、教育、农牧 9 个数据密集型行业 |
| **Helm Chart 包装**（`platform/industry-templates/charts/`） | 9 套 | 每套模板对应 1 个 Helm Chart（`*-template/`），含 ConfigMap 打包模板资产 + Deployment + Service |

> **口径说明**：3 套基础模板已实现（finance / retail / manufacturing），目标 9 套，已完成 3/9。其余 6 套（government / energy / medical / transportation / education / agriculture）骨架已交付，待完整实现。与 ROADMAP.md、operational-loop.md 口径统一。

### 2.2 模板清单

| 序号 | 模板名称 | 行业 | 模板文件 | Chart 目录 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | finance | 金融 | `fin_risk_scorecard.py` + `templates/finance/dag/` | `charts/finance-template/` | ✅ 已交付 |
| 2 | retail | 零售 | `retail_user_profile.py` + `templates/retail/dag/` | `charts/retail-template/` | ✅ 已交付 |
| 3 | manufacturing | 制造 | `mfg_quality_inspection.py` + `templates/manufacturing/dag/` | `charts/manufacturing-template/` | ✅ 已交付 |
| 4 | government | 政务 | `gov_public_services.py` + `templates/government/dag/` | `charts/government-template/` | ✅ 已交付 |
| 5 | energy | 能源 | `energy_iot.py` + `templates/energy/dag/` | `charts/energy-template/` | ✅ 已交付 |
| 6 | medical | 医疗 | `med_emr.py` | `charts/medical-template/` | ✅ 已交付 |
| 7 | transportation | 交通 | `trans_traffic.py` | `charts/transportation-template/` | ✅ 已交付 |
| 8 | education | 教育 | `edu_student.py` | `charts/education-template/` | ✅ 已交付 |
| 9 | agriculture | 农牧 | `agri_crop.py` | `charts/agriculture-template/` | ✅ 已交付 |

## 三、验证环境要求

### 3.1 基础环境

| 组件 | 最低版本 | 用途 | 验证方式 |
| --- | --- | --- | --- |
| K8s 集群 | 1.28 | 模板部署目标 | `kubectl version` |
| Helm | 3.14 | Chart 部署工具 | `helm version` |
| 存储类（StorageClass） | — | PVC 持久化 | `kubectl get storageclass` |
| kubectl | 1.28 | 集群操作 | `kubectl version --client` |

### 3.2 平台依赖

| 依赖 | 说明 | 部署方式 |
| --- | --- | --- |
| industry-templates 服务 | 模板管理 API（FastAPI） | `helm install industry-templates design/deploy/charts/industry-templates/` |
| Spark | 批计算引擎（DAG 执行） | `helm install spark design/deploy/charts/spark/` |
| Doris | OLAP 引擎（Dashboard 查询） | `helm install doris design/deploy/charts/doris/` |
| MinIO | 对象存储（模板资产存储） | `helm install minio design/deploy/charts/minio/` |

### 3.3 环境准备命令

```bash
# 1. 确认集群就绪
kubectl get nodes

# 2. 确认存储类
kubectl get storageclass

# 3. 部署平台依赖
helm install minio design/deploy/charts/minio/ -f design/deploy/values/minio-values.yaml
helm install spark design/deploy/charts/spark/ -f design/deploy/values/spark-values.yaml
helm install doris design/deploy/charts/doris/ -f design/deploy/values/doris-values.yaml

# 4. 部署 industry-templates 服务
helm install industry-templates design/deploy/charts/industry-templates/

# 5. 等待服务就绪
kubectl wait --for=condition=ready pod -l app=industry-templates --timeout=300s
```

## 四、验证步骤

### 4.1 单模板验证流程

对每套模板执行以下 4 步验证：

```
helm install → helm test → 功能验证 → 清理
```

#### 步骤 1：Helm Install（部署）

```bash
# 部署模板 Chart
helm install ${TEMPLATE_NAME}-test platform/industry-templates/charts/${TEMPLATE_NAME}-template/

# 等待部署就绪
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=${TEMPLATE_NAME}-test --timeout=300s

# 验证 Helm Release 状态
helm status ${TEMPLATE_NAME}-test
```

**预期结果**：`helm status` 显示 `STATUS: deployed`，所有 Pod 为 `Running` 状态。

#### 步骤 2：Helm Test（内置测试）

```bash
# 运行 Chart 内置 helm test
helm test ${TEMPLATE_NAME}-test
```

**预期结果**：`helm test` 返回 `PASS`，测试 Pod 完成退出码 0。

#### 步骤 3：功能验证

```bash
# 3a. 服务可访问性验证
TEMPLATE_SVC=$(kubectl get svc -l app.kubernetes.io/instance=${TEMPLATE_NAME}-test -o jsonpath='{.items[0].metadata.name}')
kubectl port-forward svc/${TEMPLATE_SVC} 8080:80 &
sleep 5
curl -s http://localhost:8080/health | grep -q '"status":"healthy"'

# 3b. 模板资产验证（ConfigMap 包含 DDL/DAG/Dashboard）
kubectl get configmap -l app.kubernetes.io/instance=${TEMPLATE_NAME}-test

# 3c. 数据持久化验证（PVC 已绑定）
kubectl get pvc -l app.kubernetes.io/instance=${TEMPLATE_NAME}-test -o jsonpath='{range .items[*]}{.status.phase}{"\n"}{end}' | grep -q Bound

# 3d. 模板 API 验证（通过 industry-templates 服务查询模板列表）
curl -s http://industry-templates:80/api/v1/templates | grep -q "${TEMPLATE_NAME}"
```

#### 步骤 4：清理

```bash
# 卸载 Chart
helm uninstall ${TEMPLATE_NAME}-test

# 验证清理干净（无残留资源）
kubectl get all -l app.kubernetes.io/instance=${TEMPLATE_NAME}-test 2>/dev/null | wc -l | grep -q 0
```

### 4.2 全量验证脚本

```bash
#!/bin/bash
# verify-all-templates.sh — 全量行业模板验证
set -euo pipefail

TEMPLATES=("finance" "retail" "manufacturing" "government" "energy" "medical" "transportation" "education" "agriculture")
PASS=0; FAIL=0; RESULTS=()

for tpl in "${TEMPLATES[@]}"; do
  echo "========== 验证模板: ${tpl} =========="
  if bash verify-single-template.sh "${tpl}"; then
    echo "✅ ${tpl} 验证通过"
    PASS=$((PASS + 1))
    RESULTS+=("✅ ${tpl}")
  else
    echo "❌ ${tpl} 验证失败"
    FAIL=$((FAIL + 1))
    RESULTS+=("❌ ${tpl}")
  fi
done

echo "========== 验证汇总 =========="
echo "通过: ${PASS} / 9"
echo "失败: ${FAIL} / 9"
for r in "${RESULTS[@]}"; do echo "  ${r}"; done

if [ "${FAIL}" -eq 0 ]; then
  echo "🎉 全部行业模板验证通过"
  exit 0
else
  echo "⚠️ ${FAIL} 个模板验证失败"
  exit 1
fi
```

### 4.3 Helm 真部署验证（K3s nightly）

> 对应 [PROJECT-ROADMAP-DETAILED.md](PROJECT-ROADMAP-DETAILED.md) P2 里程碑

```bash
# 1. 拉起 K3s nightly 集群
k3d cluster create template-test --agents 3

# 2. 设置部署模式为 helm
export INDUSTRY_TEMPLATES_DEPLOY_MODE=helm

# 3. 运行全量验证
bash verify-all-templates.sh

# 4. 清理集群
k3d cluster delete template-test
```

## 五、验证标准

### 5.1 部署成功标准

| 验证项 | 标准 | 命令 |
| --- | --- | --- |
| Helm Release | `STATUS: deployed` | `helm status ${RELEASE}` |
| Pod 状态 | 全部 `Running` | `kubectl get pods -l app.kubernetes.io/instance=${RELEASE}` |
| Service 存在 | 至少 1 个 Service | `kubectl get svc -l app.kubernetes.io/instance=${RELEASE}` |
| ConfigMap 存在 | 包含模板资产 | `kubectl get configmap -l app.kubernetes.io/instance=${RELEASE}` |

### 5.2 服务可访问标准

| 验证项 | 标准 | 命令 |
| --- | --- | --- |
| 健康检查 | HTTP 200 + `"status":"healthy"` | `curl http://${SVC}:80/health` |
| 模板列表 | API 返回包含模板名称 | `curl http://industry-templates:80/api/v1/templates` |
| 模板详情 | API 返回模板 DDL/DAG/Dashboard | `curl http://industry-templates:80/api/v1/templates/${NAME}` |

### 5.3 数据持久化标准

| 验证项 | 标准 | 命令 |
| --- | --- | --- |
| PVC 绑定 | `STATUS: Bound` | `kubectl get pvc -l app.kubernetes.io/instance=${RELEASE}` |
| 数据写入 | 模板资产写入 MinIO | `mc ls minio/templates/${NAME}/` |
| 重启后数据保留 | Pod 重启后数据不丢失 | 删除 Pod → 等待重建 → 验证数据 |

### 5.4 卸载干净标准

| 验证项 | 标准 | 命令 |
| --- | --- | --- |
| Helm Release 删除 | `helm list` 无残留 | `helm list \| grep ${RELEASE}` 返回空 |
| K8s 资源删除 | 无残留 Pod/Svc/ConfigMap/PVC | `kubectl get all -l app.kubernetes.io/instance=${RELEASE}` 返回空 |
| PVC 清理 | PVC 已删除（或按策略保留） | `kubectl get pvc -l app.kubernetes.io/instance=${RELEASE}` 返回空 |

## 六、验证报告模板

```markdown
# 行业模板验证报告

> 验证日期：YYYY-MM-DD ｜ 验证人：___ ｜ 集群：___ ｜ Helm 版本：___

## 一、验证环境

| 项目 | 值 |
| --- | --- |
| K8s 版本 | ___ |
| Helm 版本 | ___ |
| 存储类 | ___ |
| 集群节点数 | ___ |

## 二、验证结果汇总

| 模板 | 部署 | helm test | 服务可访问 | 数据持久化 | 卸载干净 | 总结果 |
| --- | --- | --- | --- | --- | --- | --- |
| finance | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| retail | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| manufacturing | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| government | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| energy | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| medical | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| transportation | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| education | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |
| agriculture | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ | ✅/❌ |

**汇总**：通过 ___ / 9，失败 ___ / 9

## 三、失败项详情

（仅记录失败项，含错误日志与根因分析）

### 模板：___
- **失败步骤**：___
- **错误信息**：___
- **根因分析**：___
- **修复建议**：___

## 四、结论

- [ ] 全部 9 套模板验证通过，可发布
- [ ] 存在失败项，需修复后重新验证
```

## 七、CI 集成

### 7.1 Nightly 验证 Job

建议在 CI nightly 流水线中加入行业模板验证 Job：

```yaml
# .github/workflows/nightly-template-test.yml
name: Nightly Industry Template Test
on:
  schedule:
    - cron: '0 2 * * *'  # 每日凌晨 2 点
jobs:
  template-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup K3s
        run: curl -sfL https://get.k3s.io | sh -
      - name: Setup Helm
        run: |
          curl https://get.helm.sh/helm-v3.14.0-linux-amd64.tar.gz | tar xz
          sudo mv linux-amd64/helm /usr/local/bin/
      - name: Deploy Platform Dependencies
        run: |
          helm install minio design/deploy/charts/minio/
          helm install spark design/deploy/charts/spark/
          helm install doris design/deploy/charts/doris/
          helm install industry-templates design/deploy/charts/industry-templates/
      - name: Run Template Verification
        run: bash scripts/verify-all-templates.sh
      - name: Upload Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: template-test-report
          path: template-test-report.md
```

### 7.2 PR 门禁

对涉及 `platform/industry-templates/` 的 PR，触发单模板验证：

```yaml
# .github/workflows/pr-template-test.yml
name: PR Template Test
on:
  pull_request:
    paths:
      - 'platform/industry-templates/**'
jobs:
  affected-template-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Detect Affected Templates
        run: |
          # 检测 PR 变更影响的模板
          git diff --name-only origin/main...HEAD | grep -oP 'charts/\K[^-]+(?=-template)' | sort -u > affected.txt
      - name: Run Affected Template Tests
        run: |
          while read tpl; do
            bash scripts/verify-single-template.sh "${tpl}"
          done < affected.txt
```

## 八、关联文档索引

| 文档 | 路径 | 关联内容 |
| --- | --- | --- |
| 行业模板使用指南 | [user-guide/industry-template-guide.md](user-guide/industry-template-guide.md) | 模板使用说明 |
| 组件成熟度矩阵 | [component-maturity.md](component-maturity.md) | industry-templates 组件成熟度 |
| 部署指南 | [deployment-guide.md](deployment-guide.md) | Helm Chart 部署 |
| 项目路线图（详细） | [PROJECT-ROADMAP-DETAILED.md](PROJECT-ROADMAP-DETAILED.md) | P2 里程碑（helm 真部署演练） |
| 路线图 | [../ROADMAP.md](../ROADMAP.md) | v2.1 行业生态扩展 |

## 九、变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-11 | 首次创建：行业模板验证方案（9 套模板口径统一 + 验证环境/步骤/标准/报告模板 + CI 集成） |