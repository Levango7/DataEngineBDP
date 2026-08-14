# Keycloak OIDC 登录闭环验证（本地实测）

> 验证目标：Keycloak 签发的 RS256 token → encaps-layer OidcJwtDecoder（JWKS 验签）→
> 租户上下文注入 → 受保护端点放行；无 token 拦截 401。
> 验证日期：2026-08-14（Keycloak 24.0.4 + encaps-layer 实测通过）

## 前置：启动 Keycloak 并初始化

```bash
# 1. 启动（HTTP 18040）
docker run -d --name sq-keycloak \
  -p 18040:8080 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin123 \
  -e KC_HTTP_ENABLED=true \
  quay.io/keycloak/keycloak:24.0.4 start-dev

# 2. 初始化 realm + client + 用户
#   realm: shuqing
#   client: sq-console (public, directAccessGrantsEnabled=true, redirect http://localhost:5173/*)
#   用户: demo / demo123（必须设置 email+emailVerified+firstName+lastName，
#          否则 direct grant 报 "Account is not fully set up"）
```

> ⚠️ 实测坑：Keycloak 24 UserProfile 要求 firstName/lastName，
> 仅 email 不够——direct grant 会报 `Account is not fully set up`。
> 修复：用户补 `firstName`/`lastName` 后 token 获取成功。

## 获取测试 token

```bash
curl -s -X POST "http://127.0.0.1:18040/realms/shuqing/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=sq-console&username=demo&password=demo123" \
  | python -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
```

## 启动 encaps-layer（OIDC 模式）

```bash
cd platform/encaps-layer
OIDC_ENABLED=true \
OIDC_JWKS_URI="http://127.0.0.1:18040/realms/shuqing/protocol/openid-connect/certs" \
OIDC_ISSUER_URI="http://127.0.0.1:18040/realms/shuqing" \
mvn spring-boot:run
# 日志确认: "OIDC 解码器已启用: jwks=...certs"
```

## 验证闭环

```bash
# ✅ 带 Keycloak token → 放行（RS256/JWKS 验签通过，返回业务数据）
curl -s "http://127.0.0.1:8080/api/v1/tenants" -H "Authorization: Bearer $TOKEN"

# ✅ 无 token → 401 拦截
curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:8080/api/v1/tenants"   # 401
```

## 实测结果

| 场景 | 结果 |
|------|------|
| Keycloak token 访问受保护端点 | ✅ `[]`（放行，验签通过） |
| 无 token 访问 | ✅ HTTP 401 |
| token payload | iss=realm/shuqing, sub=demo, aud=account |
| tenant 提取 | 单 audience 回退（aud=account）；生产建议 client 配 tenant_id claim mapper |

## 与前端登录页对接（后续）

前端 Login.vue 当前为表单登录（自签 JWT /auth/login）。
接入 Keycloak 的两种方式：
1. **授权码流程**：Login.vue 改为跳转 Keycloak authorize 端点，回调携带 code 换 token
2. **保留表单 + 后端代理**：后端 /auth/login 调 Keycloak direct grant（password flow）返回 access_token

推荐方案 2（对前端改动最小，Login.vue 已实现 login(username,password) 接口）。
