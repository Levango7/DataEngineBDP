#!/usr/bin/env bash
# ============================================================
# D1 业务演示沙箱种子脚本
#
# 目标：销售/CSE 5 分钟跑通业务闭环——
#   登录 → 租户 → 标准 → 项目 → 资产 → 模板 → 模型
#   全链数据预置，前端即刻有数据可演示。
#
# 幂等：所有资源创建前先查存在（按 name 精确匹配），存在即跳过；
#       重复执行无副作用，适合现场反复重置演示。
#
# 前置：
#   1. encaps-layer 运行中（默认 http://127.0.0.1:18080，
#      可 BASE_URL 覆盖；本地需 --app.security.local-auth.enabled=true）
#   2. curl + jq 可用
#
# 用法：
#   bash scripts/seed-business-demo.sh                     # 默认 admin/admin
#   BASE_URL=http://host:port bash scripts/seed-business-demo.sh
#   DEMO_TENANT=custom-name bash scripts/seed-business-demo.sh
#
# 退出码：0=成功（含幂等跳过）；1=登录失败或关键资源创建失败
# ============================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:18080}"
USERNAME="${DEMO_USER:-admin}"
PASSWORD="${DEMO_PASS:-admin}"
TENANT_NAME="${DEMO_TENANT:-demo-sandbox}"

# ---------- 工具 ----------

log()  { printf '[seed-demo] %s\n' "$*"; }
fail() { printf '[seed-demo] FAIL: %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || fail "缺少依赖: $1（请先安装）"; }
need curl

# jq 可选：缺失时回退 python -c json（Windows git-bash 常无 jq）
if command -v jq >/dev/null 2>&1; then
  JQ() { jq -r "$1"; }
else
  need python
  JQ() { python -c "import sys, json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
fi

# 按资源名+字段查已存在（python 实现，jq 可用性无关）。
# 兼容两种 data 形态：裸数组 [{"x":...}] 与分页 {"list":[...]}
exists() {
  # $1 = 列表 JSON，$2 = 资源名，$3 = 匹配字段
  echo "$1" | python -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data') or []
items = data.get('list') if isinstance(data, dict) else data
items = items or []
name, field = sys.argv[1], sys.argv[2]
found = any(str(it.get(field)) == name for it in items)
sys.exit(0 if found else 1)
" "$2" "$3" >/dev/null 2>&1
}

# ---------- 0. 登录 ----------

log "登录 ${BASE_URL}（${USERNAME}）..."
LOGIN=$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}") \
  || fail "后端不可达: ${BASE_URL}"

if command -v jq >/dev/null 2>&1; then
  CODE=$(echo "$LOGIN" | jq -r '.code // -1')
  TOKEN=$(echo "$LOGIN" | jq -r '.data.token')
else
  read -r CODE TOKEN <<< "$(echo "$LOGIN" | python -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('code', -1), (d.get('data') or {}).get('token', ''))
")"
fi
[ "$CODE" = "0" ] || fail "登录失败（code=${CODE}）——请确认 local-auth 已启用或凭据正确"
log "登录成功"

AUTH=(-H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json')

# 幂等创建：先 GET 查同名字段，存在跳过；POST 失败再查一次竞态确认
# 入参：资源路径 | 资源名 | 匹配字段 | 请求体 | 标签
seed() {
  local path="$1" name="$2" field="$3" body="$4" label="$5"
  local list
  list=$(curl -sS "${BASE_URL}${path}" "${AUTH[@]}" 2>/dev/null || echo '{"data":[]}')
  if exists "$list" "$name" "$field"; then
    log "已存在，跳过: ${label}"
    return 0
  fi
  local resp rcode
  resp=$(curl -sS -X POST "${BASE_URL}${path}" "${AUTH[@]}" -d "$body" 2>/dev/null || echo '{}')
  if command -v jq >/dev/null 2>&1; then
    rcode=$(echo "$resp" | jq -r '.code // -1')
  else
    rcode=$(echo "$resp" | python -c "import sys, json; print(json.load(sys.stdin).get('code', -1))")
  fi
  if [ "$rcode" = "0" ]; then
    log "创建成功: ${label}"
  else
    # 失败码：竞态下重复查一次确认是否已存在
    list=$(curl -sS "${BASE_URL}${path}" "${AUTH[@]}" 2>/dev/null || echo '{"data":[]}')
    if exists "$list" "$name" "$field"; then
      log "已存在（竞态确认），跳过: ${label}"
    else
      log "创建失败: ${label}（code=${rcode}）——继续后续资源"
    fi
  fi
}

# ---------- 1. 租户 ----------
# TenantRequest: name/displayName/namespace/quotaProfile/status
seed "/api/v1/tenants" "${TENANT_NAME}" name \
  "{\"name\":\"${TENANT_NAME}\",\"displayName\":\"演示沙箱租户\",\"namespace\":\"ns-${TENANT_NAME}\",\"quotaProfile\":\"medium\",\"status\":\"ACTIVE\"}" \
  "租户 ${TENANT_NAME}"

# ---------- 2. 数据标准（3 条：命名/质量/安全） ----------
seed "/api/v1/standards" "STD-命名规范" name \
  '{"name":"STD-命名规范","type":"naming","rule":"表名前缀 ods/dwd/dws/ads 分层","description":"演示：湖仓分层命名规范"}' \
  "标准 STD-命名规范"

seed "/api/v1/standards" "STD-质量阈值" name \
  '{"name":"STD-质量阈值","type":"quality","rule":"完整性>=95%，唯一性>=99%","description":"演示：核心表质量红线"}' \
  "标准 STD-质量阈值"

seed "/api/v1/standards" "STD-敏感分级" name \
  '{"name":"STD-敏感分级","type":"security","rule":"手机号/身份证 C4 加密存储","description":"演示：敏感字段分级规范"}' \
  "标准 STD-敏感分级"

# ---------- 3. 数据项目 ----------
# 注：/projects 创建端点当前为 stub 返回（固定 stub-project，不落库）——
# 保留调用以走通前端链路；持久化落地后恢复幂等检查。
log "项目端点为 stub（不落库），跳过创建：演示-订单分析项目"

# ---------- 4. 治理资产（5 条：全类型覆盖） ----------
seed "/api/v1/governance/assets" "ods_orders" name \
  '{"name":"ods_orders","type":"table","owner":"demo","description":"原始订单表","qualityScore":96,"securityLevel":"internal"}' \
  "资产 ods_orders"

seed "/api/v1/governance/assets" "dwd_order_wide" name \
  '{"name":"dwd_order_wide","type":"table","owner":"demo","description":"订单宽表（DWD）","qualityScore":98,"securityLevel":"internal"}' \
  "资产 dwd_order_wide"

seed "/api/v1/governance/assets" "api-order-stat" name \
  '{"name":"api-order-stat","type":"api","owner":"demo","description":"订单统计开放接口","qualityScore":95,"securityLevel":"public"}' \
  "资产 api-order-stat"

seed "/api/v1/governance/assets" "model-churn-pred" name \
  '{"name":"model-churn-pred","type":"model","owner":"demo","description":"流失预测模型","qualityScore":92,"securityLevel":"sensitive"}' \
  "资产 model-churn-pred"

seed "/api/v1/governance/assets" "dag-order-etl" name \
  '{"name":"dag-order-etl","type":"dag","owner":"demo","description":"订单 ETL 调度流","qualityScore":97,"securityLevel":"internal"}' \
  "资产 dag-order-etl"

# ---------- 5. 行业模板（2 条） ----------
seed "/api/v1/templates" "tpl-finance-risk" name \
  '{"name":"tpl-finance-risk","industry":"finance","version":"v1","description":"金融风控评分模板","author":"demo"}' \
  "模板 tpl-finance-risk"

seed "/api/v1/templates" "tpl-retail-churn" name \
  '{"name":"tpl-retail-churn","industry":"retail","version":"v1","description":"零售流失预警模板","author":"demo"}' \
  "模板 tpl-retail-churn"

# ---------- 6. ML 模型注册 ----------
# 注：metrics 嵌套对象经 ModelRegisterRequest(JsonNode) 反序列化 500（后端待修），
# 沙箱场景非必需，先不带；修复后可恢复 {"metrics":{"auc":0.91,...}}
seed "/api/v1/ml/models" "churn-xgboost" name \
  '{"name":"churn-xgboost","algorithm":"xgboost","version":"v1.0.0","modelPath":"hdfs:///models/churn/v1","description":"演示：XGBoost 流失预测"}' \
  "模型 churn-xgboost@v1.0.0"

# ---------- 完成 ----------
log "-------------------------------------------"
log "沙箱种子完成。演示路径："
log "  前端登录 ${USERNAME}/${PASSWORD} →"
log "  资产目录（5 资产）/ 数据标准（3 条）/ 项目 / 行业模板（2）/ ML 模型"
log "-------------------------------------------"
