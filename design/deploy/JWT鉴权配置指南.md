# JWT 鉴权配置指南（AUTH_MODE fail-fast 配套）

> 归属：多平台多租户大数据平台 · 部署配套文档
> 版本：v1.0 ｜ 日期：2026-08-29 ｜ 状态：生效
> 关联：`CONVENTIONS.md §9 安全规范`；`design/deploy/charts/{llmops,ml-platform,knowledge-engine,nl2sql,open-api-catalog,asset-exchange}`；`docs/user-guide/api-reference.md 第2章`

---

## 1. 背景

自 **2026-08-29** 起，平台 Python 服务的鉴权组件 `jwt_auth.py` 启用 **K8s 环境 fail-fast**：

> 在 K8s 环境（`KUBERNETES_SERVICE_HOST` 已设置）下，若 `AUTH_MODE` 环境变量**未显式设置**，
> 服务将**拒绝启动**并打印明确错误——防止生产集群内出现"匿名 admin 放行"的高危配置。

本地/测试（非 K8s）环境不受影响：未设置 `AUTH_MODE` 时仍按历史行为匿名放行（`none`）。

## 2. 涉及服务

以下 6 个 Python 服务使用 `jwt_auth.py`，其 Helm Chart 已内置鉴权配置（`auth` 段）：

| 服务 | Chart | 部署要求 |
| --- | --- | --- |
| llmops | `design/deploy/charts/llmops` | 需 Secret |
| ml-platform | `design/deploy/charts/ml-platform` | 需 Secret |
| knowledge-engine | `design/deploy/charts/knowledge-engine` | 需 Secret |
| nl2sql | `design/deploy/charts/nl2sql` | 需 Secret |
| open-api-catalog | `design/deploy/charts/open-api-catalog` | 需 Secret |
| asset-exchange | `design/deploy/charts/asset-exchange` | 需 Secret |

> 其余 Python/Go 服务（registry、business-portal、model-finetuning、ai-assistant 等）未接入 `jwt_auth.py`，不适用本指南。

## 3. 部署前必做：创建 JWT Secret

**任何 K8s 环境（dev/staging/prod）部署前，必须先创建以下 Secret**，否则上述 6 个服务将拒绝启动：

```bash
kubectl create secret generic shuqing-jwt-secret \
  --namespace <your-namespace> \
  --from-literal=jwt-secret='<32+ 字节的随机密钥>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 3.1 密钥要求

| 项 | 要求 |
| --- | --- |
| Secret 名称 | `shuqing-jwt-secret`（Chart 默认引用，可在 `values.yaml` 的 `auth.existingSecretName` 覆盖） |
| 键名 | `jwt-secret`（可在 `values.yaml` 的 `auth.jwtSecretKey` 覆盖） |
| 密钥强度 | ≥ 32 字节随机字符串，建议 `openssl rand -base64 48` |
| 与签发端一致性 | **必须**与 JWT 签发端（Keycloak / encaps-layer 的 `app.security.jwt.secret`）使用**相同密钥**，否则接口全部 401 |

生成随机密钥示例：

```bash
openssl rand -base64 48
```

### 3.2 校验 Secret

```bash
kubectl get secret shuqing-jwt-secret -n <namespace> -o jsonpath='{.data.jwt-secret}' | base64 -d | wc -c
# 输出应 >= 32
```

## 4. 三种鉴权模式

Chart 默认 `auth.authMode: jwt`（生产安全默认）。按需覆盖：

| 场景 | `auth.authMode` 值 | 说明 |
| --- | --- | --- |
| 生产 / 预发 | `jwt`（默认） | 强制 Bearer JWT 校验，无 token 或无效 token 返回 401 |
| 本地/测试（K8s 内联演示） | `none`（**显式设置**） | 匿名 admin 放行；仅限非生产，且必须显式声明 |
| 本地开发（非 K8s） | 无需设置 | 缺省 `none`，不影响 `docker compose` / 直连运行 |

覆盖方式（以 llmops 为例）：

```bash
helm upgrade --install llmops design/deploy/charts/llmops \
  --namespace <namespace> \
  --set auth.authMode=none \
  -f design/deploy/values/llmops-values.yaml
```

## 5. 部署后验证

```bash
# 1. 服务应正常 Running（不再是 CrashLoopBackOff）
kubectl get pods -n <namespace> | grep -E 'llmops|ml-platform|knowledge-engine|nl2sql|open-api-catalog|asset-exchange'

# 2. 无 token 访问应返回 401（jwt 模式）
kubectl exec -it deploy/llmops -n <namespace> -- curl -s -o /dev/null -w '%{http_code}' \
  http://localhost:<port>/api/v1/health
# 注：/health 通常放行；请用业务端点验证（如 /whoami、/api/v1/xxx）

# 3. 带 token 访问应返回 200（token 由签发端签发，密钥一致）
curl -s -H "Authorization: Bearer <jwt-token>" https://<platform-domain>/api/v1/llmops/...
```

## 6. 常见问题

### 6.1 服务 CrashLoopBackOff，日志出现 `AUTH_MODE 未显式设置...拒绝以匿名 admin 模式启动`

**原因**：K8s 环境 + 未创建 Secret 且未显式设置 `AUTH_MODE`。
**解决**：按第 3 节创建 `shuqing-jwt-secret` 后滚动重启；或（仅非生产）显式 `--set auth.authMode=none`。

### 6.2 服务起来了但接口全部 401

**原因**：`JWT_SECRET` 与签发端不一致，或 token 已过期/issuer 不匹配。
**解决**：核对 Secret 中 `jwt-secret` 与签发端 `app.security.jwt.secret` / Keycloak client secret 一致；检查 `JWT_EXPECTED_ISSUER`（若设置）与 token `iss` 一致。

### 6.3 本地开发突然起不来

**原因**：本地环境变量残留 `KUBERNETES_SERVICE_HOST`（罕见）。
**解决**：显式设置 `AUTH_MODE=none`（本地/测试专用），或清除该环境变量。

## 7. 相关链接

- 鉴权实现：`platform/llmops/llmops/api/jwt_auth.py`（七处镜像副本，CI 强制一致）
- JWT 密钥轮换：`docs/JWT-KEY-ROTATION-GUIDE.md`
- API 认证说明：`docs/user-guide/api-reference.md 第2章`
