#!/bin/bash
# 本地开发一键启动（登录验证环境）
# 用法: bash scripts/dev-up.sh
# 前置: Docker Desktop 运行中
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== 1. 启动 Keycloak（OIDC 身份源）==="
if ! docker ps --format '{{.Names}}' | grep -q '^sq-keycloak$'; then
  docker rm -f sq-keycloak 2>/dev/null || true
  docker run -d --name sq-keycloak -p 18040:8080 \
    -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin123 \
    -e KC_HTTP_ENABLED=true \
    quay.io/keycloak/keycloak:24.0.4 start-dev
  echo "  Keycloak 已启动，等待就绪..."
  until curl -s -o /dev/null "http://127.0.0.1:18040/realms/master/.well-known/openid-configuration"; do sleep 3; done
  echo "  Keycloak 就绪"
else
  echo "  Keycloak 已在运行"
fi

echo "=== 2. 初始化 Keycloak realm/client/用户（幂等）==="
TOKEN=$(curl -s -X POST "http://127.0.0.1:18040/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=admin-cli&username=admin&password=admin123" | \
  python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null || true)
if [ -z "$TOKEN" ]; then
  echo "  ⚠️ 获取 admin token 失败，跳过初始化（若 realm 已存在可忽略）"
else
  # realm（存在则忽略 409）
  curl -s -o /dev/null -X POST "http://127.0.0.1:18040/admin/realms" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"realm":"shuqing","enabled":true}' || true
  # client
  curl -s -o /dev/null -X POST "http://127.0.0.1:18040/admin/realms/shuqing/clients" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"clientId":"sq-console","enabled":true,"protocol":"openid-connect","publicClient":true,"directAccessGrantsEnabled":true,"redirectUris":["http://localhost:5173/*"],"webOrigins":["http://localhost:5173"]}' || true
  # 用户 demo（存在则忽略 409）
  KCID=$(curl -s "http://127.0.0.1:18040/admin/realms/shuqing/users?username=demo" \
    -H "Authorization: Bearer $TOKEN" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')" 2>/dev/null)
  if [ -z "$KCID" ]; then
    curl -s -o /dev/null -X POST "http://127.0.0.1:18040/admin/realms/shuqing/users" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -d '{"username":"demo","enabled":true,"email":"demo@example.com","emailVerified":true,"firstName":"Demo","lastName":"User","credentials":[{"type":"password","value":"demo123","temporary":false}]}'
  else
    # 补 email/firstName（Keycloak 24 UserProfile 要求）
    curl -s -o /dev/null -X PUT "http://127.0.0.1:18040/admin/realms/shuqing/users/$KCID" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -d '{"email":"demo@example.com","emailVerified":true,"firstName":"Demo","lastName":"User"}'
  fi
  echo "  realm/client/用户 已就绪（demo/demo123）"
fi

echo "=== 3. 启动 encaps-layer（OIDC 模式）==="
echo "  Windows 下后台进程会被 bash 清理，请另开终端运行（进程保持）："
echo "    scripts/start-encaps.bat    # 双击或 cmd 运行（前台保持）"
echo "  等待 encaps 就绪（最多 90s）..."
for i in $(seq 1 30); do
  if curl -s -o /dev/null "http://127.0.0.1:8080/api/v1/health"; then
    echo "  encaps-layer 就绪"
    break
  fi
  sleep 3
done

echo ""
echo "✅ 环境就绪！登录账号: demo / demo123"
echo "   前端启动: cd frontend && npm run dev（vite 代理已指向 127.0.0.1）"
echo "   登录端点验证: curl -X POST http://127.0.0.1:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"demo\",\"password\":\"demo123\"}'"
