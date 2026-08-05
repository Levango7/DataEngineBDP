#!/usr/bin/env bash
# ============================================================================
# verify-encaps.sh — 数擎大数据平台封装层（encaps-layer）端到端验证
# ----------------------------------------------------------------------------
# 验证范围:
#   1. 健康检查        GET    /api/v1/health
#   2. 创建租户        POST   /api/v1/tenants
#   3. 列出租户        GET    /api/v1/tenants
#   4. 获取租户        GET    /api/v1/tenants/{id}
#   5. 更新租户        PUT    /api/v1/tenants/{id}
#   6. 删除租户        DELETE /api/v1/tenants/{id}
#
# 默认端口: 8080  (与 platform/encaps-layer/README.md 一致)
# 用法: ./verify-encaps.sh [--host 127.0.0.1] [--port 8080] [--timeout 30]
# ============================================================================
set -euo pipefail

# ----------------------------- 公共函数 -----------------------------
# 颜色输出 (使用 ANSI 转义码, 不依赖 tput)
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'
  C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'
  C_CYAN=$'\033[36m'
  C_BOLD=$'\033[1m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""; C_BOLD=""
fi

# 日志文件 (每个 verify-* 脚本独立日志, run-poc.sh 会聚合)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${POC_LOG_DIR:-${SCRIPT_DIR}/logs}"
mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/verify-encaps-$(date +%Y%m%d-%H%M%S).log"

# 同时输出到终端与日志文件
log()    { echo -e "${C_CYAN}[ENCAPS]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
info()   { echo -e "$*" | tee -a "${LOG_FILE}"; }
pass()   { echo -e "${C_GREEN}${C_BOLD}[PASS]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
warn()   { echo -e "${C_YELLOW}[WARN]${C_RESET} $*" | tee -a "${LOG_FILE}"; }
fail()   { echo -e "${C_RED}${C_BOLD}[FAIL]${C_RESET} $*" | tee -a "${LOG_FILE}" >&2; }

# 计数器
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

# 单步断言: 用法 assert_step "步骤描述" curl_exit_code
assert_step() {
  local desc="$1"; shift
  local start_ts="$1"; shift
  local rc=0
  "$@" || rc=$?
  local elapsed
  elapsed=$(( $(date +%s) - start_ts ))
  if [[ $rc -eq 0 ]]; then
    pass "${desc} (耗时 ${elapsed}s)"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    fail "${desc} (耗时 ${elapsed}s, rc=${rc})"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
  return $rc
}

# JSON 解析工具: 优先 jq, 降级到 python3 -m json.tool
json_tool() {
  if command -v jq >/dev/null 2>&1; then
    jq "$@"
  elif command -v python3 >/dev/null 2>&1; then
    # 仅支持 -r '.field' 形式的简单查询, 复杂查询请用 jq
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
    echo "MISSING_JSON_TOOL"
    return 127
  fi
}

# HTTP 请求封装: http_get / http_post / http_put / http_delete
# 用法: http_get <url> [expect_code]
#       http_post <url> <json_body> [expect_code]
# 全局: HTTP_BODY / HTTP_CODE / HTTP_ELAPSED
HTTP_BODY=""; HTTP_CODE=""; HTTP_ELAPSED=0

_http_request() {
  local method="$1"; local url="$2"; local body="${3:-}"
  local expect_code="${4:-200}"
  local tmp_file
  tmp_file="$(mktemp)"
  local start_ts end_ts
  start_ts=$(date +%s%3N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1000))')

  local http_code
  if [[ -n "${body}" ]]; then
    http_code=$(curl -s -o "${tmp_file}" -w "%{http_code}" \
                  -X "${method}" "${url}" \
                  -H "Content-Type: application/json" \
                  -d "${body}" \
                  --connect-timeout 10 \
                  --max-time "${CURL_MAX_TIME:-60}" 2>/dev/null) || http_code="000"
  else
    http_code=$(curl -s -o "${tmp_file}" -w "%{http_code}" \
                  -X "${method}" "${url}" \
                  --connect-timeout 10 \
                  --max-time "${CURL_MAX_TIME:-60}" 2>/dev/null) || http_code="000"
  fi
  end_ts=$(date +%s%3N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1000))')
  HTTP_ELAPSED=$(( end_ts - start_ts ))
  HTTP_CODE="${http_code}"
  HTTP_BODY="$(cat "${tmp_file}" 2>/dev/null || true)"
  rm -f "${tmp_file}"

  if [[ "${HTTP_CODE}" == "${expect_code}" ]]; then
    return 0
  else
    return 1
  fi
}

http_get()    { _http_request GET    "$1" ""       "${2:-200}"; }
http_post()   { _http_request POST   "$1" "$2"     "${3:-201}"; }
http_put()    { _http_request PUT    "$1" "$2"     "${3:-200}"; }
http_delete() { _http_request DELETE "$1" ""       "${2:-204}"; }

# ----------------------------- 参数解析 -----------------------------
HOST="127.0.0.1"
PORT="8080"
TIMEOUT_SEC=30

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)    HOST="$2"; shift 2 ;;
    --port)    PORT="$2"; shift 2 ;;
    --timeout) TIMEOUT_SEC="$2"; shift 2 ;;
    --help|-h)
      echo "用法: $0 [--host 127.0.0.1] [--port 8080] [--timeout 30]"
      exit 0 ;;
    *) warn "未知参数: $1, 忽略"; shift ;;
  esac
done

export CURL_MAX_TIME="${TIMEOUT_SEC}"

BASE_URL="http://${HOST}:${PORT}"
TENANT_NAME="poc-tenant-$$"
TENANT_DISPLAY="PoC租户-$$"
TENANT_NAMESPACE="ws-poc-$$"
TENANT_QUOTA="base"   # 严格遵循 CONVENTIONS.md §1: base / standard / flagship
TENANT_ID=""

# ----------------------------- 前置检查 -----------------------------
log "=========================================================="
log "封装层验证  ${BASE_URL}"
log "=========================================================="
log "前置检查: curl / json 工具"

if ! command -v curl >/dev/null 2>&1; then
  fail "缺少依赖: curl, 请先安装"
  exit 2
fi
if command -v jq >/dev/null 2>&1; then
  info "  jq: $(jq --version)"
else
  warn "  jq 不可用, 降级到 python3 -m json.tool (仅支持简单查询)"
  if ! command -v python3 >/dev/null 2>&1; then
    fail "jq 与 python3 均不可用, 无法解析 JSON"
    exit 2
  fi
fi

# ----------------------------- 步骤 1: 健康检查 -----------------------------
step_start=$(date +%s)
log "[1/6] 健康检查 GET ${BASE_URL}/api/v1/health"
if assert_step "健康检查" "${step_start}" http_get "${BASE_URL}/api/v1/health" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  fail "健康检查失败, 后续步骤将跳过"
  SKIP_COUNT=$((SKIP_COUNT + 5))
  goto_summary=true
fi

if [[ "${goto_summary:-false}" != "true" ]]; then

# ----------------------------- 步骤 2: 创建租户 -----------------------------
step_start=$(date +%s)
log "[2/6] 创建租户 POST ${BASE_URL}/api/v1/tenants"
CREATE_BODY=$(cat <<EOF
{"name":"${TENANT_NAME}","displayName":"${TENANT_DISPLAY}","namespace":"${TENANT_NAMESPACE}","quotaProfile":"${TENANT_QUOTA}"}
EOF
)
if assert_step "创建租户" "${step_start}" http_post "${BASE_URL}/api/v1/tenants" "${CREATE_BODY}" 201; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  TENANT_ID=$(echo "${HTTP_BODY}" | json_tool -r '.id' 2>/dev/null || echo "")
  if [[ -z "${TENANT_ID}" || "${TENANT_ID}" == "null" ]]; then
    # 部分实现以 name 作为 id, 兼容
    TENANT_ID=$(echo "${HTTP_BODY}" | json_tool -r '.name' 2>/dev/null || echo "${TENANT_NAME}")
    warn "  响应未返回 id 字段, 回退使用 name=${TENANT_ID}"
  fi
  info "  租户ID: ${TENANT_ID}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  SKIP_COUNT=$((SKIP_COUNT + 4))
  goto_summary=true
fi
fi

if [[ "${goto_summary:-false}" != "true" ]]; then

# ----------------------------- 步骤 3: 列出租户 -----------------------------
step_start=$(date +%s)
log "[3/6] 列出租户 GET ${BASE_URL}/api/v1/tenants"
if assert_step "列出租户" "${step_start}" http_get "${BASE_URL}/api/v1/tenants" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  # 验证响应中包含刚创建的租户
  if echo "${HTTP_BODY}" | grep -q "${TENANT_NAME}"; then
    pass "  列表包含新创建租户 ${TENANT_NAME}"
  else
    warn "  列表未显式包含 ${TENANT_NAME}, 可能因分页或字段差异"
  fi
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
fi

# ----------------------------- 步骤 4: 获取单个租户 -----------------------------
step_start=$(date +%s)
log "[4/6] 获取租户 GET ${BASE_URL}/api/v1/tenants/${TENANT_ID}"
if assert_step "获取租户" "${step_start}" http_get "${BASE_URL}/api/v1/tenants/${TENANT_ID}" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
fi

# ----------------------------- 步骤 5: 更新租户 -----------------------------
step_start=$(date +%s)
log "[5/6] 更新租户 PUT ${BASE_URL}/api/v1/tenants/${TENANT_ID}"
UPDATE_BODY=$(cat <<EOF
{"name":"${TENANT_NAME}","displayName":"PoC租户-已更新-$$","namespace":"${TENANT_NAMESPACE}","quotaProfile":"standard"}
EOF
)
if assert_step "更新租户" "${step_start}" http_put "${BASE_URL}/api/v1/tenants/${TENANT_ID}" "${UPDATE_BODY}" 200; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
fi

# ----------------------------- 步骤 6: 删除租户 -----------------------------
step_start=$(date +%s)
log "[6/6] 删除租户 DELETE ${BASE_URL}/api/v1/tenants/${TENANT_ID}"
if assert_step "删除租户" "${step_start}" http_delete "${BASE_URL}/api/v1/tenants/${TENANT_ID}" 204; then
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
else
  info "  HTTP ${HTTP_CODE}  body=${HTTP_BODY}"
  # 部分实现以 200 返回空体, 兼容
  if [[ "${HTTP_CODE}" == "200" ]]; then
    pass "  (兼容: 实际返回 200 而非 204, 视为成功)"
    PASS_COUNT=$((PASS_COUNT + 1))
    FAIL_COUNT=$((FAIL_COUNT - 1))
  fi
fi

fi  # end of goto_summary guard

# ----------------------------- 汇总 -----------------------------
TOTAL=$((PASS_COUNT + FAIL_COUNT + SKIP_COUNT))
log "----------------------------------------------------------"
log "封装层验证汇总: total=${TOTAL} pass=${PASS_COUNT} fail=${FAIL_COUNT} skip=${SKIP_COUNT}"
log "日志: ${LOG_FILE}"
log "----------------------------------------------------------"

if [[ ${FAIL_COUNT} -gt 0 ]]; then
  exit 1
fi
exit 0