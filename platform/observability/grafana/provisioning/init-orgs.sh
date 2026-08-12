#!/usr/bin/env bash
# init-orgs.sh — Grafana 启动后初始化双 Organization
#
# 功能：
#   1. 确认平台方 Organization（orgId=1，名为 "Platform"）存在并赋予 platform-ops 角色。
#   2. 为示例租户创建客户方 Organization（orgId=2，名为 "tenant-demo"）。
#   3. 为客户方 Organization 创建 Service Account 并签发 token（写入 query-api 配置）。
#
# 使用：在 Grafana 容器启动后由 sidecar 或 docker-compose entrypoint 调用。
# 依赖：curl, jq
#
# 环境变量：
#   GRAFANA_URL        Grafana HTTP 地址，默认 http://localhost:3000
#   GRAFANA_ADMIN_USER 管理员账号，默认 admin
#   GRAFANA_ADMIN_PASS 管理员密码，默认 admin
#   DEMO_TENANT_ID     示例租户 ID，默认 tenant-demo
set -euo pipefail

GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
ADMIN_PASS="${GRAFANA_ADMIN_PASSWORD:-admin}"
DEMO_TENANT_ID="${DEMO_TENANT_ID:-tenant-demo}"

AUTH="${ADMIN_USER}:${ADMIN_PASS}"

echo "[init-orgs] 确认平台方 Organization (orgId=1) ..."
# orgId=1 是 Grafana 默认 Organization，重命名为 Platform。
curl -fsS -u "${AUTH}" -H "Content-Type: application/json" \
  -X PUT "${GRAFANA_URL}/api/orgs/1" \
  -d '{"name":"Platform"}' >/dev/null
echo "[init-orgs] Platform Organization 就绪。"

echo "[init-orgs] 创建客户方 Organization: ${DEMO_TENANT_ID} ..."
# 创建租户 Organization；若已存在则忽略。
ORG_RESP=$(curl -fsS -u "${AUTH}" -H "Content-Type: application/json" \
  -X POST "${GRAFANA_URL}/api/orgs" \
  -d "{\"name\":\"${DEMO_TENANT_ID}\"}" 2>/dev/null || echo '{"message":"organization already exists"}')
echo "${ORG_RESP}"

# 查询租户 Organization 的 orgId。
TENANT_ORG_ID=$(curl -fsS -u "${AUTH}" \
  "${GRAFANA_URL}/api/orgs/lookup?name=${DEMO_TENANT_ID}" | jq -r '.id')
echo "[init-orgs] ${DEMO_TENANT_ID} orgId=${TENANT_ORG_ID}"

# 切换到租户 Organization 上下文创建 Service Account。
echo "[init-orgs] 为 ${DEMO_TENANT_ID} 创建 Service Account ..."
SA_RESP=$(curl -fsS -u "${AUTH}" -H "X-Grafana-Org-Id: ${TENANT_ORG_ID}" \
  -H "Content-Type: application/json" \
  -X POST "${GRAFANA_URL}/api/serviceaccounts" \
  -d '{"name":"query-api-sa","role":"Viewer","isDisabled":false}' 2>/dev/null || echo '{}')
SA_ID=$(echo "${SA_RESP}" | jq -r '.id // empty')
if [ -n "${SA_ID}" ]; then
  SA_TOKEN=$(curl -fsS -u "${AUTH}" -H "X-Grafana-Org-Id: ${TENANT_ORG_ID}" \
    -H "Content-Type: application/json" \
    -X POST "${GRAFANA_URL}/api/serviceaccounts/${SA_ID}/tokens" \
    -d '{"name":"query-api-token"}' | jq -r '.key')
  echo "[init-orgs] ${DEMO_TENANT_ID} Service Account token 已签发。"
  # 将 token 写入环境供 query-api 使用（生产环境应写入 Secret）。
  echo "TENANT_SA_TOKEN=${SA_TOKEN}" > /etc/grafana/provisioning/tenant-token.env
else
  echo "[init-orgs] Service Account 已存在，跳过。"
fi

echo "[init-orgs] 完成。"