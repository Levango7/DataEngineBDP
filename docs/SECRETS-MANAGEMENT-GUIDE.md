# Secrets 管理指南

> 适用范围：DataEngineBDP / finance-template Helm Chart + ArgoCD 漂移告警
> 维护者：平台安全团队
> 更新日期：2026-08-20
> 状态：生产可用

---

## 1. 背景与目标

DataEngineBDP 项目在 Helm values 与 ArgoCD 告警配置中使用了 8 处 `REPLACE_WITH_*` 占位符凭证。
这些占位符是**安全设计**：禁止在 Git 仓库中填入真实凭证，必须通过外部 Secrets 管理机制在部署时注入。

本指南目标：
1. 列出全部占位符位置，便于审计与轮换；
2. 提供 4 种生产级 Secrets 管理方案（A/B/C/D），含优缺点对比；
3. 给出推荐方案与逐步替换操作指南；
4. 提供可直接套用的 CRD 模板（见 `design/deploy/templates/`）。

**重要原则：**
- 本指南**不修改**任何现有占位符文件，它们是安全基线；
- 真实凭证只允许存在于外部密钥管理系统（Vault / AWS SM / KMS 等）或 SealedSecret 加密产物中；
- 任何情况下禁止将明文凭证提交到 Git。

---

## 2. 占位符凭证清单（共 8 处）

### 2.1 finance-template 生产 values（4 处）

| # | 文件路径 | 行号 | 字段路径 | 占位符 |
|---|---------|------|---------|--------|
| 1 | `design/deploy/charts/finance-template/values.yaml` | L126 | `secret.doris.password` | `REPLACE_WITH_DORIS_PASSWORD` |
| 2 | `design/deploy/charts/finance-template/values.yaml` | L128 | `secret.dolphinscheduler.token` | `REPLACE_WITH_DOLPHINSCHEDULER_TOKEN` |
| 3 | `design/deploy/charts/finance-template/values.yaml` | L130 | `secret.superset.token` | `REPLACE_WITH_SUPERSET_TOKEN` |
| 4 | `design/deploy/charts/finance-template/values.yaml` | L132 | `secret.keycloak.adminPassword` | `REPLACE_WITH_KEYCLOAK_ADMIN_PASSWORD` |

说明：`secret.create: false`，生产环境不创建占位 Secret，由外部机制注入。

### 2.2 finance-template dev values（4 处）

| # | 文件路径 | 行号 | 字段路径 | 占位符 |
|---|---------|------|---------|--------|
| 5 | `design/deploy/charts/finance-template/values-dev.yaml` | L62 | `secret.doris.password` | `REPLACE_WITH_DORIS_PASSWORD` |
| 6 | `design/deploy/charts/finance-template/values-dev.yaml` | L64 | `secret.dolphinscheduler.token` | `REPLACE_WITH_DOLPHINSCHEDULER_TOKEN` |
| 7 | `design/deploy/charts/finance-template/values-dev.yaml` | L66 | `secret.superset.token` | `REPLACE_WITH_SUPERSET_TOKEN` |
| 8 | `design/deploy/charts/finance-template/values-dev.yaml` | L68 | `secret.keycloak.adminPassword` | `REPLACE_WITH_KEYCLOAK_ADMIN_PASSWORD` |

说明：`secret.create: true`，dev 环境允许创建占位 Secret 便于快速启动；**生产环境禁止使用**。

### 2.3 ArgoCD 漂移告警 webhook-secret（4 处，与上面 8 处不重复计数）

> 任务说明中标注 8 处占位符指 2.1 + 2.2 共 8 处；webhook-secret 为独立 4 处，本指南一并覆盖。

| # | 文件路径 | 行号 | 字段 | 占位符 |
|---|---------|------|------|--------|
| 9 | `design/deploy/argocd/drift-detection/alerting/webhook-secret.yaml` | L23 | `slack-token` | `REPLACE_WITH_SLACK_BOT_TOKEN` |
| 10 | 同上 | L27 | `email-password` | `REPLACE_WITH_EMAIL_PASSWORD` |
| 11 | 同上 | L31 | `dingtalk-token` | `REPLACE_WITH_DINGTALK_TOKEN` |
| 12 | 同上 | L35 | `pagerduty-key` | `REPLACE_WITH_PAGERDUTY_KEY` |

说明：`stringData` 中 `email-username` 为明文邮箱地址（非密钥），保留原值。

---

## 3. 方案对比

### 方案 A：Kubernetes 原生 Secret + kubectl create secret

**思路：** 用 `kubectl create secret generic` 手工创建 Secret，绕过 Git。

**操作示例：**
```bash
# 1. 创建 finance-template 凭据 Secret
kubectl create secret generic finance-template-secrets \
  --namespace=finance \
  --from-literal=doris-password='<真实口令>' \
  --from-literal=dolphinscheduler-token='<真实令牌>' \
  --from-literal=superset-token='<真实令牌>' \
  --from-literal=keycloak-admin-password='<真实口令>'

# 2. 创建 ArgoCD 通知 Secret
kubectl create secret generic argocd-notifications-secret \
  --namespace=argocd \
  --from-literal=slack-token='xoxb-xxxx' \
  --from-literal=email-password='<SMTP口令>' \
  --from-literal=dingtalk-token='<access_token>' \
  --from-literal=pagerduty-key='<integration_key>'
```

**优点：**
- 零依赖，K8s 内置能力；
- 操作简单，适合临时验证、dev 环境。

**缺点：**
- **无法 GitOps**：Secret 不在 Git，漂移检测困难；
- **无版本控制**：轮换无历史可查；
- **无加密静态存储**（除非开启 etcd encryption at rest）；
- 多集群分发需重复手工操作，易出错。

**适用场景：** dev/测试环境、紧急临时凭证、小规模单集群。

---

### 方案 B：SealedSecrets（Bitnami）

**思路：** 用 `kubeseal` 以非对称私钥加密 Secret，得到 `SealedSecret` CRD，可安全入库 Git；集群内 controller 解密还原为原生 Secret。

**核心组件：**
- `sealed-secrets-controller`（部署于 kube-system）；
- 私钥对（RSA 4096），controller 持有私钥，CI/开发者持公钥；
- `kubeseal` CLI。

**操作示例：**
```bash
# 1. 安装 controller
helm install sealed-secrets sealed-secrets/sealed-secrets \
  --namespace=kube-system \
  --set secretKey.secretName=sealed-secrets-private-key

# 2. 用 kubeseal 生成 SealedSecret（写入 finance namespace）
kubectl create secret generic finance-template-secrets \
  --namespace=finance \
  --from-literal=doris-password='<真实口令>' \
  --dry-run=client -o yaml | \
  kubeseal --controller-namespace=kube-system \
  --format yaml > finance-template-sealed.yaml

# 3. 加密产物可入库 Git
kubectl apply -f finance-template-sealed.yaml
```

**优点：**
- **GitOps 友好**：加密产物可入库 Git，ArgoCD 可直接同步；
- **静态加密**：即使仓库泄露，无 controller 私钥无法解密；
- **轮换可追溯**：Git 历史记录每次轮换；
- 部署简单，单一 controller。

**缺点：**
- 私钥泄露即全盘失守，需严格保护 controller 私钥（建议 HSM/KMS 托管）；
- 轮换私钥需 re-seal 全部 SealedSecret，运维成本高；
- 跨集群需共享私钥或各自独立私钥；
- 加密绑定 namespace/name，重命名需重新 seal。

**适用场景：** 中小规模 GitOps、单/少集群、私钥可妥善托管。

**模板：** 见 `design/deploy/templates/sealed-secret-example.yaml`。

---

### 方案 C：External Secrets Operator + Vault / AWS Secrets Manager

**思路：** 真实凭证存放在外部密钥管理系统（HashiCorp Vault / AWS Secrets Manager / GCP Secret Manager / Azure Key Vault）；集群内 `External Secrets Operator`（ESO）拉取并生成原生 Secret。Git 中只存 `ExternalSecret` CRD（引用指针，无敏感数据）。

**核心组件：**
- `external-secrets-operator`；
- `SecretStore` / `ClusterSecretStore`：指向外部系统并配置认证；
- `ExternalSecret`：声明需要哪些 key、映射到 K8s Secret 的哪些字段。

**操作示例（Vault）：**
```bash
# 1. 在 Vault 写入凭证
vault kv put secret/finance-template \
  doris-password='<真实口令>' \
  dolphinscheduler-token='<真实令牌>' \
  superset-token='<真实令牌>' \
  keycloak-admin-password='<真实口令>'

vault kv put secret/argocd-notifications \
  slack-token='xoxb-xxxx' \
  email-password='<SMTP口令>' \
  dingtalk-token='<access_token>' \
  pagerduty-key='<integration_key>'

# 2. 集群内创建 SecretStore（认证用 K8s ServiceAccount + Vault JWT auth）
# 3. 创建 ExternalSecret（见模板）
```

**优点：**
- **凭证单一真相源**：Vault/SM 为权威，K8s 仅缓存；
- **动态密钥支持**：可对接 Vault 动态生成 DB 凭证，自动轮换；
- **审计完善**：Vault/SM 自带访问审计日志；
- **多集群共享**：外部系统统一供给，无需 re-seal；
- **GitOps 友好**：ExternalSecret CRD 无敏感数据，可入库 Git；
- 细粒度权限（Vault policy / IAM）。

**缺点：**
- 依赖外部系统可用性，需高可用部署 Vault；
- 学习曲线与运维成本较高；
- 需要为 ESO 配置外部系统认证（JWT/OIDC/静态 token）；
- 网络故障时 Secret 无法刷新（已有缓存仍可用）。

**适用场景：** 生产环境、多集群、需要动态密钥与审计、企业级密钥管理。

**模板：** 见 `design/deploy/templates/external-secret-example.yaml`。

---

### 方案 D：SOPS + age / GPG

**思路：** 用 Mozilla SOPS 加密 YAML/JSON 文件中的敏感字段（保留 key 明文，仅加密 value），加密文件入库 Git；部署时用 Helm secrets / ArgoCD KSOPS 解密。

**核心组件：**
- `sops` CLI；
- `age` 或 `GPG` 作为加密后端；
- `helm-secrets` 插件 或 ArgoCD `ksops` plugin。

**操作示例（age）：**
```bash
# 1. 生成 age 密钥对
age-keygen -o age-key.txt   # 私钥妥善保管，公钥 age1... 入库

# 2. 写明文 values，用 sops 加密
cat > secrets.yaml <<EOF
doris-password: <真实口令>
dolphinscheduler-token: <真实令牌>
EOF
sops --encrypt --age age1... --in-place secrets.yaml

# 3. 部署时解密
helm secrets upgrade finance-template ./charts/finance-template \
  -f secrets.yaml
```

**优点：**
- **GitOps 友好**：加密文件入库 Git，结构可读（key 明文）；
- **细粒度加密**：可只加密部分字段；
- **多接收者**：age 支持多公钥，团队多人解密；
- 无需集群内 controller，解密在 CI/ArgoCD 侧。

**缺点：**
- 私钥泄露即全盘失守；
- ArgoCD 集成需 KSOPS plugin，配置略复杂；
- 无动态密钥、无审计；
- 跨集群需共享私钥。

**适用场景：** GitOps 优先、不依赖外部密钥系统、团队规模可控。

---

## 4. 方案对比矩阵

| 维度 | A 原生 Secret | B SealedSecrets | C External Secrets | D SOPS |
|------|--------------|-----------------|--------------------|--------|
| GitOps 友好 | ❌ | ✅ | ✅ | ✅ |
| 入库 Git 安全 | N/A | ✅ 加密 | ✅ 仅引用 | ✅ 加密 |
| 动态密钥轮换 | ❌ | ❌ | ✅ | ❌ |
| 审计日志 | ❌ | ⚠️ Git 历史 | ✅ 外部系统 | ⚠️ Git 历史 |
| 多集群分发 | ❌ 手工 | ⚠️ 共享私钥 | ✅ 原生 | ⚠️ 共享私钥 |
| 运维复杂度 | 低 | 中 | 高 | 中 |
| 依赖外部系统 | 无 | 无 | Vault/SM | 无 |
| 私钥泄露爆炸半径 | N/A | 全部 | 单 key | 全部 |
| 生产推荐 | ⚠️ 仅临时 | ✅ GitOps | ✅✅ 首选 | ✅ 备选 |

---

## 5. 推荐方案

### 5.1 生产环境首选：方案 C（External Secrets Operator + Vault）

**理由：**
- 凭证单一真相源，便于轮换与审计；
- 支持动态密钥（DB 临时凭证），安全上限最高；
- 多集群天然支持，无需 re-seal；
- 与 ArgoCD GitOps 完美兼容（ExternalSecret CRD 入库 Git）。

**配套：**
- Vault 高可用部署（Raft storage）；
- 用 K8s ServiceAccount JWT 认证 Vault（无静态 token）；
- ExternalSecret 设置 `refreshInterval: 1h`，自动同步轮换。

### 5.2 GitOps 简化场景：方案 B（SealedSecrets）

**理由：**
- 无需运维 Vault，部署成本低；
- 加密产物入库 Git，ArgoCD 直接同步；
- 适合中小规模或 Vault 未就绪的过渡阶段。

### 5.3 dev 环境：方案 A（kubectl create secret）

**理由：** 快速验证，无需额外组件；配合 `values-dev.yaml` 的 `secret.create: true` 占位 Secret 即可启动。

### 5.4 不推荐

- **禁止**任何方案将明文凭证写入 Git；
- **禁止**生产环境使用 `values-dev.yaml` 的占位 Secret；
- 方案 D 作为方案 B 的等价替代，团队若已用 SOPS 可保留。

---

## 6. 替换步骤（逐步操作指南）

### 6.1 生产环境替换为 External Secrets（方案 C，推荐）

**前置条件：**
- 已部署 External Secrets Operator（`external-secrets` namespace）；
- 已部署 Vault 并配置 KV v2 secrets engine；
- 已配置 Vault JWT auth + policy 允许 finance / argocd 两个 namespace 的 ServiceAccount 读取对应路径。

**步骤：**

1. **在 Vault 写入真实凭证**
   ```bash
   vault kv put secret/finance-template \
     doris-password='<生产口令>' \
     dolphinscheduler-token='<生产令牌>' \
     superset-token='<生产令牌>' \
     keycloak-admin-password='<生产口令>'

   vault kv put secret/argocd-notifications \
     slack-token='xoxb-<生产>' \
     email-password='<SMTP生产口令>' \
     dingtalk-token='<生产access_token>' \
     pagerduty-key='<生产integration_key>'
   ```

2. **创建 SecretStore**（每个 namespace 一个，指向 Vault）
   ```yaml
   apiVersion: external-secrets.io/v1beta1
   kind: SecretStore
   metadata:
     name: vault-backend
     namespace: finance
   spec:
     provider:
       vault:
         server: "https://vault.vault.svc:8200"
         path: "secret"
         version: "v2"
         auth:
           kubernetes:
             mountPath: "kubernetes"
             role: "finance-role"
             serviceAccountRef:
               name: default
   ```

3. **应用 ExternalSecret**（见模板 `design/deploy/templates/external-secret-example.yaml`）
   ```bash
   kubectl apply -f design/deploy/templates/external-secret-example.yaml -n argocd
   # finance namespace 同理
   ```

4. **修改 Helm values 关闭占位 Secret 创建**
   - 生产 `values.yaml`：`secret.create: false`（已是默认）；
   - 通过 `--set secret.create=false` 或 overlay 覆盖；
   - 在 chart 模板中引用 ESO 生成的 Secret 名称（如 `finance-template-secrets`）。

5. **验证**
   ```bash
   kubectl get externalsecret -n finance
   kubectl get secret finance-template-secrets -n finance -o yaml
   kubectl get secret argocd-notifications-secret -n argocd -o yaml
   ```
   确认 `STATUS: Ready` 且 Secret 已生成。

6. **ArgoCD 同步**：ExternalSecret CRD 入库 Git，ArgoCD 自动同步。

### 6.2 GitOps 简化替换为 SealedSecrets（方案 B）

**前置条件：** 已部署 sealed-secrets-controller，已安装 kubeseal CLI。

**步骤：**

1. **生成明文 Secret（dry-run）**
   ```bash
   kubectl create secret generic finance-template-secrets \
     --namespace=finance \
     --from-literal=doris-password='<生产口令>' \
     --from-literal=dolphinscheduler-token='<生产令牌>' \
     --from-literal=superset-token='<生产令牌>' \
     --from-literal=keycloak-admin-password='<生产口令>' \
     --dry-run=client -o yaml > /tmp/finance-secret.yaml
   ```

2. **kubeseal 加密**
   ```bash
   kubeseal --format=yaml \
     --controller-namespace=kube-system \
     < /tmp/finance-secret.yaml > design/deploy/templates/finance-template-sealed.yaml
   ```

3. **入库 Git 并 apply**（见模板 `design/deploy/templates/sealed-secret-example.yaml`）
   ```bash
   kubectl apply -f design/deploy/templates/finance-template-sealed.yaml
   ```

4. **修改 Helm values**：`secret.create: false`，chart 引用 controller 还原的 Secret。

5. **验证**
   ```bash
   kubectl get sealedsecret -n finance
   kubectl get secret finance-template-secrets -n finance
   ```

### 6.3 dev 环境快速替换（方案 A）

```bash
kubectl create secret generic finance-template-secrets \
  --namespace=finance-dev \
  --from-literal=doris-password='dev-doris-pwd' \
  --from-literal=dolphinscheduler-token='dev-ds-token' \
  --from-literal=superset-token='dev-superset-token' \
  --from-literal=keycloak-admin-password='dev-keycloak-pwd'
```
dev 环境可保留 `values-dev.yaml` 的 `secret.create: true` 占位 Secret 用于冒烟；生产环境必须切换到 6.1 或 6.2。

---

## 7. 凭证轮换流程

| 方案 | 轮换步骤 |
|------|---------|
| C External Secrets | 在 Vault 更新 KV，ESO 下个 `refreshInterval` 自动同步；无需改 Git |
| B SealedSecrets | 重新 `kubeseal` 生成 SealedSecret，提交 Git，ArgoCD 同步 |
| A 原生 | `kubectl delete` + `kubectl create` 重建 |
| D SOPS | 重新 `sops --encrypt --in-place`，提交 Git |

**轮换频率建议：**
- `dolphinscheduler.token` / `superset.token`：每 90 天；
- `keycloak.adminPassword`：每 180 天；
- `doris.password`：每 180 天或人员变动时；
- webhook token/key：每 90 天或泄露怀疑时。

---

## 8. 审计与合规

- **Git 扫描**：CI 中用 `gitleaks` / `trufflehog` 扫描，确保无明文凭证；
- **Vault 审计**：开启 Vault audit device（file/syslog），记录所有 KV 访问；
- **K8s RBAC**：限制 `secrets` 资源 get/list 权限，仅授权应用 ServiceAccount；
- **etcd encryption at rest**：开启 K8s encryption，双重保护静态 Secret；
- **定期轮换**：按 §7 频率执行，记录轮换事件。

---

## 9. 相关文件

| 文件 | 说明 |
|------|------|
| `design/deploy/charts/finance-template/values.yaml` | 生产 values，占位符（不修改） |
| `design/deploy/charts/finance-template/values-dev.yaml` | dev values，占位符（不修改） |
| `design/deploy/argocd/drift-detection/alerting/webhook-secret.yaml` | ArgoCD 告警 Secret，占位符（不修改） |
| `design/deploy/templates/sealed-secret-example.yaml` | SealedSecret CRD 模板（方案 B） |
| `design/deploy/templates/external-secret-example.yaml` | ExternalSecret CRD 模板（方案 C） |
| `docs/SECRETS-MANAGEMENT-GUIDE.md` | 本指南 |

---

## 10. FAQ

**Q1：为什么不直接在 values.yaml 填真实凭证？**
A：Git 历史会永久保留明文，即使后续删除也可从历史恢复；且多环境共享同一仓库易交叉泄露。占位符是安全基线。

**Q2：SealedSecret 私钥如何备份？**
A：用 `kubectl get secret -n kube-system sealed-secrets-private-key -o yaml` 导出，加密后存入离线介质（如 KMS/HSM/U盘），严禁入库 Git。

**Q3：External Secrets Operator 故障时已有 Secret 是否失效？**
A：不会。ESO 仅负责刷新，已生成的原生 Secret 独立存在，应用仍可读取；仅轮换会暂停。

**Q4：能否混用方案 B 和 C？**
A：可以。例如 finance-template 用 C（对接 Vault），argocd-notifications 用 B（GitOps 简化）。按 namespace/凭证类型选择即可。

**Q5：ArgoCD 如何识别 SealedSecret / ExternalSecret？**
A：需在 ArgoCD 安装对应 CRD 与（如需）KSOPS plugin；ExternalSecret 由 ESO controller 处理，ArgoCD 只需同步 CRD 即可。