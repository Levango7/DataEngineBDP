#!/bin/bash
# 链路4 E2E：前端 SQL 工作台 → sql-gateway → Trino 真实查询
# 前置：Docker Desktop 运行中（脚本会自动启动 Trino 容器 + 恢复 Doris BE）
# 用法: bash scripts/e2e-trino-query.sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== 0. 检查 Docker ==="
docker info >/dev/null 2>&1 || { echo "❌ Docker Desktop 未运行，请先启动"; exit 1; }

echo "=== 1. 启动 Trino 容器（1ms 源）==="
if ! docker ps --format '{{.Names}}' | grep -q '^sq-trino$'; then
  docker rm -f sq-trino 2>/dev/null || true
  docker run -d --name sq-trino -p 18082:8080 \
    docker.1ms.run/trinodb/trino:462
fi
until curl -s -o /dev/null "http://127.0.0.1:18082/v1/info"; do sleep 3; done
echo "  ✅ Trino 就绪 (18082)"

echo "=== 2. 恢复 Doris BE（WSL sysctl 重置修复）==="
wsl -d docker-desktop -u root -- sysctl -w vm.max_map_count=2000000 2>/dev/null || true
wsl -d docker-desktop -u root -- sysctl -w vm.swappiness=0 2>/dev/null || true
docker start sq-doris-lite-be 2>/dev/null || true
echo "  ⏳ Doris BE 注册中（跳过，链路4核心走 Trino）"

echo "=== 3. 构建并启动 sql-gateway ==="
cd "$ROOT/platform/sql-gateway"
mvn -B -q clean package -DskipTests 2>/dev/null || true
PID_8081=$(netstat -ano 2>/dev/null | grep ':8081.*LISTEN' | head -1 | awk '{print $NF}')
[ -n "$PID_8081" ] && taskkill //PID $PID_8081 //F >/dev/null 2>&1 || true
TRINO_URL="http://127.0.0.1:18082" DORIS_URL="http://127.0.0.1:18030" \
  nohup java -jar target/sql-gateway-0.1.0-exec.jar > /tmp/sqlgw.log 2>&1 &
until curl -s -o /dev/null "http://127.0.0.1:8081/actuator/health"; do sleep 3; done
echo "  ✅ sql-gateway 就绪 (8081)"

echo "=== 4. 生成 JWT ==="
TOKEN=$(python - << 'PY'
import base64, hmac, hashlib, json, time
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=")
h = b64(b'{"alg":"HS256"}')
now = int(time.time())
p = b64(json.dumps({"sub":"admin","tenantId":"tenant-demo","iat":now,"exp":now+1800,"iss":"shuqing-bigdata"}).encode())
sig = b64(hmac.new(b"dev-secret-key-change-in-production-at-least-256-bits", h+b"."+p, hashlib.sha256).digest())
print((h+b"."+p+b"."+sig).decode())
PY
)
echo "  ✅ token 就绪"

echo "=== 5. 链路4核心：sql-gateway → Trino 真实查询（tpch.tiny）==="
curl -s -m 40 -X POST "http://127.0.0.1:8081/api/v1/sql/execute" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"sql":"SELECT regionkey, name FROM tpch.tiny.region ORDER BY regionkey","engine":"trino","tenantId":"tenant-demo"}' \
  | python -c "
import json, sys
d = json.load(sys.stdin)
print('  status:', d.get('status'))
print('  engine:', d.get('engine'))
print('  columns:', d.get('columns'))
print('  rows:', d.get('rows'))
assert d.get('status') == 'SUCCESS', f'查询失败: {d}'
assert d.get('rows'), '返回数据为空'
print('  ✅ 链路4打通：前端 SQL → sql-gateway → Trino → 真实数据')
"

echo ""
echo "🎉 链路4 E2E 完成：tpch.tiny.region 真实数据返回"
echo "前端验证: 浏览器 http://127.0.0.1:5173 SQL 工作台输入同款 SQL 即可"
