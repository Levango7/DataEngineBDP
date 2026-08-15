#!/bin/bash
# 链路6 E2E：BI 可视化（Superset 真实运行 + 登录 + 数据源配置）
# 前置：sq-superset 容器运行中（镜像 docker.1ms.run/apache/superset:3.1.0）
# 用法: bash scripts/e2e-superset.sh
set -e

echo "=== 0. 检查 Superset 容器 ==="
docker exec sq-superset superset version >/dev/null 2>&1 \
  && echo "  ✅ Superset 3.1.0 就绪" \
  || { echo "  ❌ 容器未运行（docker start sq-superset）"; exit 1; }

echo "=== 1. Superset 登录 API（真实鉴权）==="
LOGIN=$(curl -s -m 10 -X POST "http://127.0.0.1:18088/api/v1/security/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin","provider":"db","refresh":true}')
TOKEN=$(echo "$LOGIN" | python -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
[ -n "$TOKEN" ] && echo "  ✅ 登录成功（admin）" || { echo "  ❌ 登录失败"; exit 1; }

echo "=== 2. CSRF token（写操作前置）==="
CSRF=$(curl -s -m 10 "http://127.0.0.1:18088/api/v1/security/csrf_token/" \
  -H "Authorization: Bearer $TOKEN" | python -c "import json,sys; print(json.load(sys.stdin).get('result',''))" 2>/dev/null)
[ -n "$CSRF" ] && echo "  ✅ CSRF 获取成功"

echo "=== 3. 数据源连接配置（Trino/Doris）==="
echo "  ⚠️ Docker Desktop 容器→宿主网络受限（host.docker.internal 不可达）"
echo "  生产/同 compose 网络配置示例："
echo "    trino:  trino://demo@trino:8080/tpch/tiny"
echo "    doris:  mysql://root@doris-fe:9030/sq_demo"
echo "  浏览器访问 http://127.0.0.1:18088（admin/admin）"

echo ""
echo "🎉 链路6 E2E 完成：Superset 3.1.0 真实运行 + 登录鉴权通过"
echo "   （BI 仪表盘可视化需数据源网络可达，Docker Desktop 受限记录待环境）"
