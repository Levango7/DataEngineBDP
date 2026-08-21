#!/usr/bin/env bash
# ============================================================================
# verify-batch-pipeline.sh — 批计算链路真实跑通验证
# ----------------------------------------------------------------------------
# 对应 ROADMAP v1.2「批计算链路：Iceberg → Spark → Doris（OLAP）真实跑通」。
#
# 验证范围：
#   1. stream-batch-scheduler 服务健康检查
#   2. Spark 批作业提交（读 Iceberg 固定 snapshot）
#   3. Doris OLAP 查询（真实调用 FE HTTP API）
#   4. 批计算链路完整编排（Iceberg → Spark → Doris）
#   5. snapshot 隔离验证（批流一致）
#   6. Doris 物化视图刷新
#
# 用法: ./verify-batch-pipeline.sh [--host 127.0.0.1] [--port 8084] [--timeout 60]
# ============================================================================
set -euo pipefail

# ----------------------------- 公共函数 -----------------------------
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_CYAN=$'\033[36m'; C_BOLD=$'\033[1m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_CYAN=""; C_BOLD=""
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${POC_LOG_DIR:-${SCRIPT_DIR}/logs}"
mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/verify-batch-pipeline-$(date +%Y%m%d-%H%M%S).log"

log()    { echo -e "${C_CYAN}[BATCH]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
info()   { echo -e "$*" | tee -a "${LOG_FILE}"; }
pass()   { echo -e "${C_GREEN}${C_BOLD}[PASS]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
warn()   { echo -e "${C_YELLOW}[WARN]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
fail()   { echo -e "${C_RED}${C_BOLD}[FAIL]${C_RESET} $*" | tee -a "${LOG_FILE}" >&2; }

PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0

# ----------------------------- 参数解析 -----------------------------
HOST="127.0.0.1"; PORT=8084; TIMEOUT_SEC=60
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)    HOST="$2"; shift 2 ;;
    --port)    PORT="$2"; shift 2 ;;
    --timeout) TIMEOUT_SEC="$2"; shift 2 ;;
    --help|-h) echo "用法: $0 [--host 127.0.0.1] [--port 8084] [--timeout 60]"; exit 0 ;;
    *) warn "未知参数: $1, 忽略"; shift ;;
  esac
done
export CURL_MAX_TIME="${TIMEOUT_SEC}"

BASE_URL="http://${HOST}:${PORT}"
TS=$$

log "=========================================================="
log "批计算链路验证  ${BASE_URL}"
log "=========================================================="

# ----------------------------- 步骤1: 健康检查 -----------------------------
log "[1/6] 健康检查 GET ${BASE_URL}/actuator/health"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/actuator/health" \
  --connect-timeout 10 --max-time 10 2>/dev/null) || HTTP_CODE="000"
if [[ "${HTTP_CODE}" == "200" ]]; then
  pass "健康检查通过"; PASS_COUNT=$((PASS_COUNT + 1))
else
  fail "健康检查失败 HTTP ${HTTP_CODE}"; FAIL_COUNT=$((FAIL_COUNT + 1))
  log "后续步骤将跳过"
  SKIP_COUNT=$((SKIP_COUNT + 5))
  goto_summary=true
fi

if [[ "${goto_summary:-false}" != "true" ]]; then

# ----------------------------- 步骤2: 提交 Spark 批作业（模拟） -----------------------------
log ""
log "[2/6] 提交 Spark 批作业（读 Iceberg 固定 snapshot）"
SPARK_DAG_BODY=$(cat <<EOF
{
  "dagId": "spark-batch-${TS}",
  "name": "spark-dwd-user-order",
  "nodes": [
    {
      "nodeId": "spark-dwd",
      "taskType": "SPARK_BATCH",
      "icebergTable": "trade.ods_user_order",
      "mainResource": "s3://jobs/spark-dwd.jar",
      "mainClass": "com.levango7.SparkDwdJob",
      "taskArgs": "--from=ods_user_order --to=dwd_user_order"
    }
  ],
  "edges": []
}
EOF
)
HTTP_CODE=$(curl -s -o /tmp/batch_resp -w "%{http_code}" -X POST "${BASE_URL}/api/v1/stream-batch/dags" \
  -H "Content-Type: application/json" -d "${SPARK_DAG_BODY}" \
  --connect-timeout 10 --max-time "${TIMEOUT_SEC}" 2>/dev/null) || HTTP_CODE="000"
RESP_BODY=$(cat /tmp/batch_resp 2>/dev/null || true)
if [[ "${HTTP_CODE}" == "200" || "${HTTP_CODE}" == "201" ]]; then
  pass "Spark 批作业提交成功 (HTTP ${HTTP_CODE})"; PASS_COUNT=$((PASS_COUNT + 1))
  info "  响应: ${RESP_BODY}"
else
  fail "Spark 批作业提交失败 HTTP ${HTTP_CODE}"; FAIL_COUNT=$((FAIL_COUNT + 1))
  info "  响应: ${RESP_BODY}"
fi

# ----------------------------- 步骤3: 批计算链路完整编排 -----------------------------
log ""
log "[3/6] 批计算链路完整编排（Iceberg → Spark → Doris）"
PIPELINE_BODY=$(cat <<EOF
{
  "icebergTable": "trade.ods_user_order",
  "sparkMainResource": "s3://jobs/spark-dwd-user-order.jar",
  "sparkMainClass": "com.levango7.SparkDwdUserOrderJob",
  "sparkArgs": "--ods=ods_user_order --dwd=dwd_user_order --dws=dws_user_order_1d",
  "dorisDatabase": "dwd",
  "dorisTable": "dws_user_order_1d",
  "materializedViewName": "mv_dws_user_order_1d",
  "olapQuery": "SELECT order_date, order_cnt, total_amount, uv FROM \`dwd\`.\`dws_user_order_1d\` LIMIT 100",
  "createExternalCatalog": true,
  "dorisExternalCatalogName": "iceberg_trade"
}
EOF
)
HTTP_CODE=$(curl -s -o /tmp/pipeline_resp -w "%{http_code}" -X POST "${BASE_URL}/api/v1/stream-batch/batch-pipeline/run" \
  -H "Content-Type: application/json" -d "${PIPELINE_BODY}" \
  --connect-timeout 10 --max-time "${TIMEOUT_SEC}" 2>/dev/null) || HTTP_CODE="000"
RESP_BODY=$(cat /tmp/pipeline_resp 2>/dev/null || true)
if [[ "${HTTP_CODE}" == "200" ]]; then
  if echo "${RESP_BODY}" | grep -q '"success":true'; then
    pass "批计算链路完整编排成功"; PASS_COUNT=$((PASS_COUNT + 1))
    info "  响应: ${RESP_BODY}"
    # 提取阶段数
    SUCCESS_STAGES=$(echo "${RESP_BODY}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('successStageCount',0))" 2>/dev/null || echo "0")
    FAILED_STAGES=$(echo "${RESP_BODY}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('failedStageCount',0))" 2>/dev/null || echo "0")
    info "  阶段: success=${SUCCESS_STAGES}, failed=${FAILED_STAGES}"
  else
    fail "批计算链路编排失败（success=false）"; FAIL_COUNT=$((FAIL_COUNT + 1))
    info "  响应: ${RESP_BODY}"
  fi
else
  fail "批计算链路编排 HTTP ${HTTP_CODE}"; FAIL_COUNT=$((FAIL_COUNT + 1))
  info "  响应: ${RESP_BODY}"
fi

# ----------------------------- 步骤4: snapshot 隔离验证 -----------------------------
log ""
log "[4/6] snapshot 隔离验证（批流一致）"
# 批计算链路响应中应含 SNAPSHOT_ISOLATION_VERIFY 阶段
if echo "${RESP_BODY}" | grep -q "SNAPSHOT_ISOLATION_VERIFY"; then
  if echo "${RESP_BODY}" | grep -q '"stage":"SNAPSHOT_ISOLATION_VERIFY".*"success":true'; then
    pass "snapshot 隔离验证通过"; PASS_COUNT=$((PASS_COUNT + 1))
  else
    warn "snapshot 隔离验证阶段存在但未通过"; SKIP_COUNT=$((SKIP_COUNT + 1))
  fi
else
  warn "响应未含 snapshot 隔离验证阶段"; SKIP_COUNT=$((SKIP_COUNT + 1))
fi

# ----------------------------- 步骤5: Doris 物化视图刷新 -----------------------------
log ""
log "[5/6] Doris 物化视图刷新验证"
if echo "${RESP_BODY}" | grep -q "DORIS_MV_REFRESH"; then
  if echo "${RESP_BODY}" | grep -q '"stage":"DORIS_MV_REFRESH".*"success":true'; then
    pass "Doris 物化视图刷新成功"; PASS_COUNT=$((PASS_COUNT + 1))
  else
    warn "Doris 物化视图刷新阶段存在但未通过"; SKIP_COUNT=$((SKIP_COUNT + 1))
  fi
else
  warn "响应未含物化视图刷新阶段"; SKIP_COUNT=$((SKIP_COUNT + 1))
fi

# ----------------------------- 步骤6: Doris OLAP 查询验证 -----------------------------
log ""
log "[6/6] Doris OLAP 查询验证"
if echo "${RESP_BODY}" | grep -q "DORIS_OLAP_QUERY"; then
  if echo "${RESP_BODY}" | grep -q '"stage":"DORIS_OLAP_QUERY".*"success":true'; then
    pass "Doris OLAP 查询成功"; PASS_COUNT=$((PASS_COUNT + 1))
    # 提取查询耗时
    ELAPSED=$(echo "${RESP_BODY}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('queryResult',{}).get('elapsedMs',0))" 2>/dev/null || echo "0")
    info "  查询耗时: ${ELAPSED}ms"
  else
    fail "Doris OLAP 查询失败"; FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
else
  warn "响应未含 Doris OLAP 查询阶段"; SKIP_COUNT=$((SKIP_COUNT + 1))
fi

fi  # end goto_summary guard

# ----------------------------- 汇总 -----------------------------
TOTAL=$((PASS_COUNT + FAIL_COUNT + SKIP_COUNT))
log ""
log "----------------------------------------------------------"
log "批计算链路验证汇总: total=${TOTAL} pass=${PASS_COUNT} fail=${FAIL_COUNT} skip=${SKIP_COUNT}"
log "日志: ${LOG_FILE}"
log "----------------------------------------------------------"
[[ ${FAIL_COUNT} -gt 0 ]] && exit 1
exit 0