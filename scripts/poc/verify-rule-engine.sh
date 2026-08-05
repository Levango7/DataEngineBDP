#!/usr/bin/env bash
# ============================================================================
# verify-rule-engine.sh — 数擎大数据平台规则引擎端到端验证
# ----------------------------------------------------------------------------
# 验证范围:
#   1. 健康检查        GET    /api/v1/health
#   2. 列出规则类型    GET    /api/v1/rules/types  → 期望 ["DQ","MASK","ALERT"]
#   3. 创建规则        POST   /api/v1/rules
#   4. 列出规则        GET    /api/v1/rules
#   5. 执行规则        POST   /api/v1/rules/execute
#   6. 删除规则        DELETE /api/v1/rules/{id}
#
# 默认端口: 8083  (与 platform/rule-engine/README.md 一致)
# 用法: ./verify-rule-engine.sh [--host 127.0.0.1] [--port 8083] [--timeout 30]
# ============================================================================
set -euo pipefail

# ----------------------------- 公共函数 -----------------------------
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'; C_CYAN=$'\033[36m'; C_BOLD=$'\033[1m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""; C_BOLD=""
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${POC_LOG_DIR:-${SCRIPT_DIR}/logs}"
mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/verify-rule-engine-$(date +%Y%m%d-%H%M%S).log"

log()    { echo -e "${C_CYAN}[RULENG]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
info()   { echo -e "$*" | tee -a "${LOG_FILE}"; }
pass()   { echo -e "${C_GREEN}${C_BOLD}[PASS]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
warn()   { echo -e "${C_YELLOW}[WARN]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
fail()   { echo -e "${C_RED}${C_BOLD}[FAIL]${C_RESET} $*" | tee -a "${LOG_FILE}" >&2; }

PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0

assert_step() {
  local desc="$1"; shift
  local start_ts="$1"; shift
  local rc=0
  "$@" || rc=$?
  local elapsed=$(( $(date +%s) - start_ts ))
  if [[ $rc -eq 0 ]]; then
    pass "${desc} (耗时 ${elapsed}s)"; PASS_COUNT=$((PASS_COUNT + 1))
  else
    fail "${desc} (耗时 ${elapsed}s, rc=${rc})"; FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
  return $rc
}

json_tool() {
  if command -v jq >/dev/null 2>&1; then
    jq "$@"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "
import sys, json, re
data = json.load(sys.stdin)
args = sys.argv[1:]
if args and args[0] == '-r' and len(args) >= 2:
    expr = args[1]
    m = re.match(r'^\.([A-Za-z_][A-Za-z0-9_]*)$', expr)
    if m:
        val = data.get(m.group(1), '')
        print(val if val is not None else '')
    else:
        print(json.dumps(data))
else:
    print(json.dumps(data, indent=2))
" "$@"
  else
    echo "MISSING_JSON_TOOL"; return 127
  fi
}

HTTP_BODY=""; HTTP_CODE=""; HTTP_ELAPSED=0
_http_request() {
  local method="$1"; local url="$2"; local body="${3:-}"; local expect_code="${4:-200}"
  local tmp_file; tmp_file="$(mktemp)"
  local start_ts end_ts
  start_ts=$(date +%s%3N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1000))')
  local http_code
  if [[ -n "${body}" ]]; then
    http_code=$(curl -s -o "${tmp_file}" -w "%{http_code}" -X "${method}" "${url}" \
                  -H "Content-Type: application/json" -d "${body}" \
                  --connect-timeout 10 --max-time "${CURL_MAX_TIME:-60}" 2>/dev/null) || http_code="000"
  else
    http_code=$(curl -s -o "${tmp_file}" -w "%{http_code}" -X "${method}" "${url}" \
                  --connect-timeout 10 --max-time "${CURL_MAX_TIME:-60}" 2>/dev/null) || http_code="000"
  fi
  end_ts=$(date +%s%3N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1000))')
  HTTP_ELAPSED=$(( end_ts - start_ts )); HTTP_CODE="${http_code}"
  HTTP_BODY="$(cat "${tmp_file}" 2>/dev/null || true)"; rm -f "${tmp_file}"
  [[ "${HTTP_CODE}" == "${expect_code}" ]]
}
http_get()    { _http_request GET    "$1" ""   "${2:-200}"; }
http_post()   { _http_request POST   "$1" "$2" "${3:-201}"; }
http_delete() { _http_request DELETE "$1" ""   "${2:-204}"; }

# ----------------------------- 参数解析 -----------------------------
HOST="127.0.0.1"; PORT="8083"; TIMEOUT_SEC=30
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)    HOST="$2"; shift 2 ;;
    --port)    PORT="$2"; shift 2 ;;
    --timeout) TIMEOUT_SEC="$2"; shift 2 ;;
    --help|-h) echo "用法: $0 [--host 127.0.0.1] [--port 8083] [--timeout 30]"; exit 0 ;;
    *) warn "未知参数: $1, 忽略"; shift ;;
  esac
done
export CURL_MAX_TIME="${TIMEOUT_SEC}"

BASE_URL="http://${HOST}:${PORT}"
RULE_NAME="poc-null-check-$$"
RULE_ID=""
goto_summary=false

# ----------------------------- 前置检查 -----------------------------
log "=========================================================="
log "规则引擎验证  ${BASE_URL}"
log "=========================================================="
log "前置检查: curl / json 工具"
if ! command -v curl >/dev/null 2>&1; then fail "缺少依赖: curl"; exit 2; fi
if command -v jq >/dev/null 2>&1; then
  info "  jq: $(jq --version)"
else
  warn "  jq 不可用, 降级到 python3"
  command -v python3 >/dev/null 2>&1 || { fail "python3 也不可用"; exit 2; }
fi

# ----------------------------- 步骤 1: 健康检查 -----------------------------
step_start=$(date +%s)
log "[1/6] 健康检查 GET ${BASE_URL}/api/v1/health"
if assert_step "健康检查" "${step_start}" http_get "${BASE_URL}/api/v1/health" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  fail "健康检查失败, 后续步骤将跳过"
  SKIP_COUNT=$((SKIP_COUNT + 5)); goto_summary=true
fi

if [[ "${goto_summary}" != "true" ]]; then

# ----------------------------- 步骤 2: 列出规则类型 -----------------------------
step_start=$(date +%s)
log "[2/5] 列出规则类型 GET ${BASE_URL}/api/v1/rules/types  (期望含 DQ, MASK, ALERT)"
if assert_step "列出规则类型" "${step_start}" http_get "${BASE_URL}/api/v1/rules/types" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  if echo "${HTTP_BODY}" | grep -q '"DQ"' \
     && echo "${HTTP_BODY}" | grep -q '"MASK"' \
     && echo "${HTTP_BODY}" | grep -q '"ALERT"'; then
    pass "  规则类型包含 DQ + MASK + ALERT"
  else
    warn "  规则类型未同时包含 DQ/MASK/ALERT, 实际: ${HTTP_BODY}"
  fi
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
fi

# ----------------------------- 步骤 3: 创建规则 -----------------------------
step_start=$(date +%s)
log "[3/6] 创建规则 POST ${BASE_URL}/api/v1/rules  (name=${RULE_NAME}, type=DQ)"
RULE_BODY=$(cat <<EOF
{
  "name":"${RULE_NAME}",
  "description":"PoC 验证用空值检查规则",
  "type":"DQ",
  "expression":"user_id IS NOT NULL",
  "severity":"ERROR",
  "enabled":true
}
EOF
)
if assert_step "创建规则" "${step_start}" http_post "${BASE_URL}/api/v1/rules" "${RULE_BODY}" 201; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  RULE_ID=$(echo "${HTTP_BODY}" | json_tool -r '.id' 2>/dev/null || echo "")
  if [[ -z "${RULE_ID}" || "${RULE_ID}" == "null" ]]; then
    RULE_ID=$(echo "${HTTP_BODY}" | json_tool -r '.name' 2>/dev/null || echo "${RULE_NAME}")
    warn "  响应未返回 id, 回退使用 name=${RULE_ID}"
  fi
  info "  规则ID: ${RULE_ID}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  if [[ "${HTTP_CODE}" == "200" ]]; then
    pass "  (兼容: 实际返回 200)"; PASS_COUNT=$((PASS_COUNT + 1)); FAIL_COUNT=$((FAIL_COUNT - 1))
    RULE_ID=$(echo "${HTTP_BODY}" | json_tool -r '.id' 2>/dev/null || echo "${RULE_NAME}")
  else
    SKIP_COUNT=$((SKIP_COUNT + 3)); goto_summary=true
  fi
fi
fi

if [[ "${goto_summary}" != "true" ]]; then

# ----------------------------- 步骤 4: 列出规则 -----------------------------
step_start=$(date +%s)
log "[4/6] 列出规则 GET ${BASE_URL}/api/v1/rules"
if assert_step "列出规则" "${step_start}" http_get "${BASE_URL}/api/v1/rules" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  if echo "${HTTP_BODY}" | grep -q "${RULE_NAME}"; then
    pass "  列表包含新创建规则 ${RULE_NAME}"
  else
    warn "  列表未显式包含 ${RULE_NAME}"
  fi
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
fi

# ----------------------------- 步骤 5: 执行规则 -----------------------------
step_start=$(date +%s)
log "[5/6] 执行规则 POST ${BASE_URL}/api/v1/rules/execute  (ruleId=${RULE_ID})"
EXEC_BODY=$(cat <<EOF
{"ruleId":${RULE_ID:-1},"context":{"table":"t_user","tenantId":"poc"},"tenantId":"poc"}
EOF
)
# RULE_ID 若非数字则改为 1 (兼容按 name 查询的实现)
if ! [[ "${RULE_ID}" =~ ^[0-9]+$ ]]; then
  EXEC_BODY=$(cat <<EOF
{"ruleName":"${RULE_NAME}","context":{"table":"t_user","tenantId":"poc"},"tenantId":"poc"}
EOF
)
fi
if assert_step "执行规则" "${step_start}" http_post "${BASE_URL}/api/v1/rules/execute" "${EXEC_BODY}" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  STATUS=$(echo "${HTTP_BODY}" | json_tool -r '.status' 2>/dev/null || echo "")
  if [[ -n "${STATUS}" ]]; then
    pass "  规则执行状态: ${STATUS}"
  else
    warn "  响应未返回 status 字段"
  fi
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
fi

# ----------------------------- 步骤 6: 删除规则 -----------------------------
step_start=$(date +%s)
log "[6/6] 删除规则 DELETE ${BASE_URL}/api/v1/rules/${RULE_ID}"
if assert_step "删除规则" "${step_start}" http_delete "${BASE_URL}/api/v1/rules/${RULE_ID}" 204; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  if [[ "${HTTP_CODE}" == "200" ]]; then
    pass "  (兼容: 实际返回 200)"; PASS_COUNT=$((PASS_COUNT + 1)); FAIL_COUNT=$((FAIL_COUNT - 1))
  fi
fi

fi  # end goto_summary guard

# ----------------------------- 汇总 -----------------------------
TOTAL=$((PASS_COUNT + FAIL_COUNT + SKIP_COUNT))
log "----------------------------------------------------------"
log "规则引擎验证汇总: total=${TOTAL} pass=${PASS_COUNT} fail=${FAIL_COUNT} skip=${SKIP_COUNT}"
log "日志: ${LOG_FILE}"
log "----------------------------------------------------------"
[[ ${FAIL_COUNT} -gt 0 ]] && exit 1
exit 0