# Keycloak 角色与登录配置

> 目的：说明"谁能访问哪个端点"这件事在数擎平台里到底怎么落地，以及**本地/联调/生产**三条路径各自怎么拿到角色。
> 背景：审计发现本仓的授权声明（`@PreAuthorize("hasRole('SUPER_ADMIN')")` 等）此前**在任何环境都无法满足**
> —— 没有 realm 导入物、Keycloak Chart 也不消费角色配置。前端租户管理三页切真接口后，
> 这个缺口会直接表现为"全部 403"。

## 1. 角色模型（唯一事实来源）

鉴权链路只认 JWT 的 `realm_access.roles`：`JwtAuthFilter.extractAuthorities()` 把每个角色加 `ROLE_` 前缀
交给 Spring Security；**没有任何角色时兜底为 `ROLE_USER`**。

| realm 角色 | Spring 权限 | 能访问什么 | 代码依据 |
| --- | --- | --- | --- |
| `SUPER_ADMIN` | `ROLE_SUPER_ADMIN` | 租户 CRUD、邀请码、平台看板、K8s 集群管理、跨租户审批 | `TenantController.java:47`、`InviteController.java:97,123,166,176`、`K8sController.java:49` |
| `TENANT_ADMIN` | `ROLE_TENANT_ADMIN` | 本租户内的注册审批与查看（跨租户一律 404/403） | `RegistrationController` 的 `list`/`decision` |
| `USER` | `ROLE_USER` | 业务功能，无管理端点 | 兜底权限 |

两个必须同时存在的 claim，缺一不可：

- `realm_access.roles` —— 没有它就等于 `ROLE_USER`，所有管理端点 403
- `tenantId` —— `TenantContext` 的租户归属来源，多租户隔离与计费都依赖它；
  且请求头 `X-Tenant-Id` 与之不符会被拒（`JwtAuthFilter` 的租户一致性校验）

另有平台超管的**哨兵值**：`tenantId == "platform-admin"` 被视为平台侧身份
（`InviteController.PLATFORM_ADMIN_TENANT`），用于允许跨租户查询；普通租户即使传别人的
`tenantId` 也会被强制回落到自己租户（只收窄、不越界）。

## 2. 前端角色 ↔ realm 角色映射

前端类型定义（`frontend/src/stores/tenantAdmin.ts` 的 `UserRole`）与 realm 角色不是一套词，
按下表映射，改名时两侧要同步：

| 前端 `UserRole` | realm 角色 |
| --- | --- |
| `PLATFORM_ADMIN` | `SUPER_ADMIN` |
| `TENANT_ADMIN` | `TENANT_ADMIN` |
| `USER` | `USER` |

## 3. 三条登录路径

### 3.1 本地开发（无 Keycloak）

`app.security.local-auth.enabled=true` 时走 `AuthController` 的降级登录。
它签发 `tenantId=platform-admin` **且带 `realm_access.roles=[SUPER_ADMIN]`** 的 HMAC token，
因此本地登录后即可访问租户管理与邀请码端点（此前只写 `role: admin`，不被鉴权链路识别，
导致本地 100% 403）。角色可配：

```
app.security.local-auth.roles=SUPER_ADMIN          # 逗号分隔可给多个
```

回归测试：`platform/encaps-layer/src/test/java/.../security/LocalLoginRolesTest.java`
（钉住"token 带 SUPER_ADMIN"、"带 token 后 /api/v1/tenants 不再 403"、
"匿名仍 401"、"仅 USER 角色仍 403"）。

### 3.2 联调 / 生产（Keycloak）

```bash
export KEYCLOAK_ADMIN=admin KEYCLOAK_ADMIN_PASSWORD='<从密钥系统取，勿写进仓库>'
bash scripts/import-keycloak-realm.sh                       # 导入 realm 角色与客户端 claim 映射
bash scripts/import-keycloak-realm.sh --grant <用户> SUPER_ADMIN   # 给具体用户授角色
```

导入物是 `design/deploy/keycloak/sq-realm-roles.json`，除 realm 角色外还带两个**协议映射器**：

- `realm-roles`（`oidc-usermodel-realm-role-mapper`）：把 realm 角色写进 access token 的 `realm_access.roles`
- `tenant-id`（`oidc-usermodel-attribute-mapper`）：把用户属性 `tenantId` 写进 token

没有这两个映射器，即便 Keycloak 里有角色，token 里也不会有 —— 这是最常见的"配了角色还 403"原因。

## 4. 怎么验证配通了

```bash
# 1) 取一个 access token（directAccessGrants 已在 sq-console 客户端开启）
curl -s -X POST https://<keycloak>/realms/sq/protocol/openid-connect/token \
  -d grant_type=password -d client_id=sq-console -d username=<u> -d password=<p> | jq -r .access_token
# 2) 解 payload，确认两个 claim 都在
python - <<'PY'
import base64,json,sys
t=sys.argv[1].split('.')[1]; t+="="*(-len(t)%4)
c=json.loads(base64.urlsafe_b64decode(t))
print("realm_access.roles =", c.get("realm_access",{}).get("roles"))
print("tenantId           =", c.get("tenantId"))
PY
```

期望：`roles` 含 `SUPER_ADMIN`（或 `TENANT_ADMIN`），`tenantId` 非空。
然后用该 token 调 `GET /api/v1/tenants` 应 200；用 `TENANT_ADMIN` 调应 403（那是平台侧端点）。

## 5. 已知缺口（记载在案，需后续处理）

| 项 | 现状 | 影响 |
| --- | --- | --- |
| Keycloak Chart 无法启动 | `templates/deployment.yaml` 没有 `args`/`command`，官方镜像无子命令会直接退出；`values.config.realms` 只被渲染成 `realms: "map[...]"` 这样的环境变量字符串，Keycloak 不读 | 生产环境需先用外部方式起 Keycloak 并跑本脚本；Chart 需补启动参数 + DB 环境变量 + `--import-realm` 挂载 |
| Chart 无 Secret 注入 | 88 个 Chart 中只有 16 个（多为 Python 组件）用了 `secretKeyRef`；Java 服务与基础设施 Chart 一律 `envFrom: configMapRef` | `DB_PASSWORD`/`JWT_SECRET` 这类敏感值只能进 ConfigMap（任何能 `get configmap` 的人可读）。需统一改为 Secret 注入 |
| 无"用户从前端注册到 Keycloak"的编排 | 审批通过只改数据库状态（`RegistrationController.decide`），不建 Keycloak 用户、不发凭证 | "租户自助开通"闭环缺一环；需接 Keycloak Admin API 建用户并映射 `tenantId` 属性 |

以上三条已同步登记在 [KNOWN-FAILURES.md](KNOWN-FAILURES.md) 第六节。
