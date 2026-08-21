#!/usr/bin/env bash
# ============================================================================
# run-poc.sh — 数据引擎大数据平台 端到端 PoC 主编排脚本
# ----------------------------------------------------------------------------
# 替换 design/详细设计/多平台多租户大数据平台_端到端PoC详细设计_v0.1.md 中的
# SIM 模拟脚本, 改为真实调用:
#   阶段1: SKE 集群拉起 (调用 ske/ske.sh up)
#   阶段2: Helm 部署大数据组件 (spark/flink/trino/doris/kafka/...)
#   阶段3: 封装层验证     (verify-encaps.sh)
#   阶段4: SQL 网关验证   (verify-sql-gateway.sh)
#   阶段5: Catalog 验证   (verify-catalog.sh)
#   阶段6: 规则引擎验证   (verify-rule-engine.sh)
#   阶段7: 端到端数据流验证 (创建租户 → 执行 SQL → 验证结果)
#
# 用法:
#   ./run-poc.sh [--skip-cluster] [--skip-helm] [--skip-platform]
#                [--profile local|xinchuang|onprem] [--mode dev|prod]
#                [--target kind|wsl2] [--timeout 300] [--host 127.0.0.1]
#
# 退出码:
#   0  全部阶段 PASS
#   1  存在 FAIL 阶段
#   2  前置依赖缺失
# ============================================================================
set -euo pipefail

# ----------------------------- 公共函数 -----------------------------
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'; C_CYAN=$'\033[36m'; C_MAGENTA=$'\033[35m'; C_BOLD=$'\033[1m'
else
  C_RESET=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""; C_MAGENTA=""; C_BOLD=""
fi

# 项目根目录 (run-poc.sh 位于 <root>/scripts/poc/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"
REPORT_DIR="${SCRIPT_DIR}/reports"
mkdir -p "${LOG_DIR}" "${REPORT_DIR}"

# 主日志文件 (汇总所有阶段)
TS="$(date +%Y%m%d-%H%M%S)"
MAIN_LOG="${LOG_DIR}/poc-${TS}.log"
MAIN_REPORT="${REPORT_DIR}/poc-report-${TS}.md"

# 同时输出到终端与主日志
echo_poc()  { echo -e "${C_MAGENTA}${C_BOLD}[PoC]${C_RESET} $*" | tee -a "${MAIN_LOG}"; }
echo_info() { echo -e "$*" | tee -a "${MAIN_LOG}"; }
echo_pass() { echo -e "${C_GREEN}${C_BOLD}[PASS]${C_RESET} $*" | tee -a "${MAIN_LOG}"; }
echo_warn() { echo -e "${C_YELLOW}[WARN]${C_RESET} $*" | tee -a "${MAIN_LOG}"; }
echo_fail() { echo -e "${C_RED}${C_BOLD}[FAIL]${C_RESET} $*" | tee -a "${MAIN_LOG}" >&2; }
echo_stage() {
  echo "" | tee -a "${MAIN_LOG}" >/dev/null
  echo -e "${C_BLUE}${C_BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${C_RESET}" | tee -a "${MAIN_LOG}"
  echo -e "${C_BLUE}${C_BOLD} $* ${C_RESET}" | tee -a "${MAIN_LOG}"
  echo -e "${C_BLUE}${C_BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${C_RESET}" | tee -a "${MAIN_LOG}"
}

# 阶段结果收集: STAGE_NAME / STAGE_RC / STAGE_ELAPSED / SUMMARY_LINES
declare -a STAGE_NAMES=()
declare -a STAGE_RCS=()
declare -a STAGE_ELAPSEDS=()
declare -a STAGE_LOGS=()

# 运行某阶段: run_stage <stage_name> <timeout_sec> <cmd...>
run_stage() {
  local stage_name="$1"; shift
  local stage_timeout="$1"; shift
  local stage_log="${LOG_DIR}/stage-${stage_name}-${TS}.log"
  local start_ts end_ts rc
  start_ts=$(date +%s)

  echo_stage "阶段 [${stage_name}] 开始 (timeout=${stage_timeout}s)"
  echo_info "  命令: $*"
  echo_info "  日志: ${stage_log}"

  # 使用 timeout 包裹, 子进程输出同时写入 stage_log 与主日志
  set +e
  timeout --preserve-status "${stage_timeout}" bash -c "$*" 2>&1 | tee -a "${stage_log}" | tee -a "${MAIN_LOG}"
  rc=${PIPESTATUS[0]}
  set -e
  end_ts=$(date +%s)
  local elapsed=$(( end_ts - start_ts ))

  STAGE_NAMES+=("${stage_name}")
  STAGE_RCS+=("${rc}")
  STAGE_ELAPSEDS+=("${elapsed}")
  STAGE_LOGS+=("${stage_log}")

  if [[ ${rc} -eq 0 ]]; then
    echo_pass "阶段 [${stage_name}] PASS (耗时 ${elapsed}s)"
  elif [[ ${rc} -eq 124 ]]; then
    echo_fail "阶段 [${stage_name}] TIMEOUT (超过 ${stage_timeout}s)"
  else
    echo_fail "阶段 [${stage_name}] FAIL (耗时 ${elapsed}s, rc=${rc})"
  fi
  return ${rc}
}

# ----------------------------- 参数解析 -----------------------------
SKIP_CLUSTER=false
SKIP_HELM=false
SKIP_PLATFORM=false
SKIP_E2E=false
SKE_PROFILE="local"
SKE_MODE="dev"
SKE_TARGET="kind"
STAGE_TIMEOUT=300
HOST="127.0.0.1"

usage() {
  cat <<EOF
用法: $0 [选项]

选项:
  --skip-cluster       跳过阶段1 (SKE 集群拉起, 假设集群已存在)
  --skip-helm          跳过阶段2 (Helm 部署大数据组件)
  --skip-platform      跳过阶段3-6 (平台组件 API 验证)
  --skip-e2e           跳过阶段7 (端到端数据流验证)
  --profile <name>     SKE profile: local|xinchuang|onprem|publiccloud|privatecloud  (默认: local)
  --mode <name>        SKE mode: dev|prod  (默认: dev)
  --target <name>      SKE target: kind|wsl2  (默认: kind)
  --timeout <sec>      单阶段超时秒数  (默认: 300)
  --host <ip>          平台组件主机  (默认: 127.0.0.1)
  --help, -h           显示帮助

示例:
  # 全量端到端 PoC
  ./run-poc.sh

  # 集群已存在, 仅验证平台 API
  ./run-poc.sh --skip-cluster --skip-helm

  # 仅运行端到端数据流验证
  ./run-poc.sh --skip-cluster --skip-helm --skip-platform
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-cluster) SKIP_CLUSTER=true; shift ;;
    --skip-helm)    SKIP_HELM=true; shift ;;
    --skip-platform) SKIP_PLATFORM=true; shift ;;
    --skip-e2e)     SKIP_E2E=true; shift ;;
    --profile)      SKE_PROFILE="$2"; shift 2 ;;
    --mode)         SKE_MODE="$2"; shift 2 ;;
    --target)       SKE_TARGET="$2"; shift 2 ;;
    --timeout)      STAGE_TIMEOUT="$2"; shift 2 ;;
    --host)         HOST="$2"; shift 2 ;;
    --help|-h)      usage; exit 0 ;;
    *) echo_warn "未知参数: $1, 忽略"; shift ;;
  esac
done

# ----------------------------- 前置依赖检查 -----------------------------
echo_poc "=========================================================="
echo_poc "数据引擎大数据平台 · 端到端 PoC 编排"
echo_poc "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo_poc "项目根: ${PROJECT_ROOT}"
echo_poc "主日志: ${MAIN_LOG}"
echo_poc "报告:   ${MAIN_REPORT}"
echo_poc "=========================================================="
echo_poc "选项: skip-cluster=${SKIP_CLUSTER} skip-helm=${SKIP_HELM} skip-platform=${SKIP_PLATFORM} skip-e2e=${SKIP_E2E}"
echo_poc "      profile=${SKE_PROFILE} mode=${SKE_MODE} target=${SKE_TARGET} timeout=${STAGE_TIMEOUT}s host=${HOST}"
echo_poc ""

echo_poc "[前置] 依赖检查..."
MISSING_DEPS=()

# bash 4+ 检查 (使用关联数组等特性)
if [[ -z "${BASH_VERSINFO:-}" ]] || [[ ${BASH_VERSINFO[0]} -lt 4 ]]; then
  echo_fail "  bash 版本 < 4, 当前: ${BASH_VERSION:-unknown}"
  MISSING_DEPS+=("bash>=4")
fi

for dep in curl timeout date tee mktemp; do
  if command -v "${dep}" >/dev/null 2>&1; then
    echo_info "  ${dep}: $(command -v ${dep})"
  else
    echo_fail "  缺少依赖: ${dep}"
    MISSING_DEPS+=("${dep}")
  fi
done

# jq 可选, 降级到 python3
if command -v jq >/dev/null 2>&1; then
  echo_info "  jq: $(jq --version)"
elif command -v python3 >/dev/null 2>&1; then
  echo_warn "  jq 不可用, 降级到 python3 (路径: $(command -v python3))"
else
  echo_fail "  jq 与 python3 均不可用, 无法解析 JSON"
  MISSING_DEPS+=("jq-or-python3")
fi

# 集群相关检查 (仅在不跳过时)
if [[ "${SKIP_CLUSTER}" != "true" ]]; then
  for dep in docker kubectl helm; do
    if command -v "${dep}" >/dev/null 2>&1; then
      echo_info "  ${dep}: $(command -v ${dep})"
    else
      echo_warn "  缺少: ${dep} (阶段1/2 可能失败)"
    fi
  done
fi

if [[ ${#MISSING_DEPS[@]} -gt 0 ]]; then
  echo_fail "前置依赖缺失: ${MISSING_DEPS[*]}"
  exit 2
fi
echo_pass "[前置] 依赖检查通过"

# ----------------------------- 阶段 1: SKE 集群拉起 -----------------------------
if [[ "${SKIP_CLUSTER}" == "true" ]]; then
  echo_warn "阶段 [SKE-UP] 已跳过 (--skip-cluster)"
  STAGE_NAMES+=("SKE-UP"); STAGE_RCS+=("0"); STAGE_ELAPSEDS+=("0"); STAGE_LOGS+=("(skipped)")
else
  SKE_SCRIPT="${PROJECT_ROOT}/ske/ske.sh"
  if [[ ! -x "${SKE_SCRIPT}" && ! -f "${SKE_SCRIPT}" ]]; then
    echo_fail "SKE 脚本不存在: ${SKE_SCRIPT}"
    STAGE_NAMES+=("SKE-UP"); STAGE_RCS+=("127"); STAGE_ELAPSEDS+=("0"); STAGE_LOGS+=("(missing)")
  else
    chmod +x "${SKE_SCRIPT}" 2>/dev/null || true
    run_stage "SKE-UP" "${STAGE_TIMEOUT}" \
      "bash '${SKE_SCRIPT}' up --profile ${SKE_PROFILE} --mode ${SKE_MODE} --target ${SKE_TARGET}" \
      || true
  fi
fi

# ----------------------------- 阶段 2: Helm 部署大数据组件 -----------------------------
if [[ "${SKIP_HELM}" == "true" ]]; then
  echo_warn "阶段 [HELM-DEPLOY] 已跳过 (--skip-helm)"
  STAGE_NAMES+=("HELM-DEPLOY"); STAGE_RCS+=("0"); STAGE_ELAPSEDS+=("0"); STAGE_LOGS+=("(skipped)")
else
  CHARTS_DIR="${PROJECT_ROOT}/design/deploy/charts"
  if [[ ! -d "${CHARTS_DIR}" ]]; then
    echo_fail "Helm Charts 目录不存在: ${CHARTS_DIR}"
    STAGE_NAMES+=("HELM-DEPLOY"); STAGE_RCS+=("127"); STAGE_ELAPSEDS+=("0"); STAGE_LOGS+=("(missing)")
  else
    # 13 个组件 Chart, 顺序部署 (有依赖关系: 先存储/消息, 再计算/查询)
    # 顺序: keycloak → kafka → spark → flink → trino → doris → iotdb
    #       → dolphinscheduler → seatunnel → superset → apisix → governance → theia
    HELM_CMD=$(cat <<'EOF'
set -e
CHARTS_DIR="__CHARTS_DIR__"
NS="shuqing-poc"
echo "[HELM] 创建 namespace ${NS} (若不存在)"
kubectl get ns "${NS}" >/dev/null 2>&1 || kubectl create ns "${NS}"

# 顺序部署每个 Chart; 失败一个不中断整体, 但记录失败
FAILED=()
for chart in keycloak kafka spark flink trino doris iotdb dolphinscheduler seatunnel superset apisix governance theia; do
  chart_dir="${CHARTS_DIR}/${chart}"
  if [[ ! -d "${chart_dir}" ]]; then
    echo "[WARN] Chart 不存在: ${chart_dir}, 跳过"
    continue
  fi
  echo "[HELM] helm upgrade --install ${chart} ${chart_dir} -n ${NS}"
  if helm upgrade --install "${chart}" "${chart_dir}" -n "${NS}" --wait --timeout 5m; then
    echo "[PASS] ${chart} 部署成功"
  else
    echo "[FAIL] ${chart} 部署失败"
    FAILED+=("${chart}")
  fi
done

if [[ ${#FAILED[@]} -gt 0 ]]; then
  echo "[FAIL] 以下 Chart 部署失败: ${FAILED[*]}"
  exit 1
fi
echo "[PASS] 全部 Chart 部署成功"
EOF
)
    HELM_CMD="${HELM_CMD//__CHARTS_DIR__/${CHARTS_DIR}}"
    run_stage "HELM-DEPLOY" "${STAGE_TIMEOUT}" "${HELM_CMD}" || true
  fi
fi

# ----------------------------- 阶段 3-6: 平台组件 API 验证 -----------------------------
if [[ "${SKIP_PLATFORM}" == "true" ]]; then
  echo_warn "阶段 [ENCAPS/SQLGW/CATALOG/RULENG] 已跳过 (--skip-platform)"
  for s in ENCAPS SQLGW CATALOG RULENG; do
    STAGE_NAMES+=("${s}"); STAGE_RCS+=("0"); STAGE_ELAPSEDS+=("0"); STAGE_LOGS+=("(skipped)")
  done
else
  # 阶段3: 封装层 (port 8080)
  run_stage "ENCAPS" "${STAGE_TIMEOUT}" \
    "bash '${SCRIPT_DIR}/verify-encaps.sh' --host ${HOST} --port 8080 --timeout 60" || true

  # 阶段4: SQL 网关 (port 8081)
  run_stage "SQLGW" "${STAGE_TIMEOUT}" \
    "bash '${SCRIPT_DIR}/verify-sql-gateway.sh' --host ${HOST} --port 8081 --timeout 60" || true

  # 阶段5: Catalog (port 8082)
  run_stage "CATALOG" "${STAGE_TIMEOUT}" \
    "bash '${SCRIPT_DIR}/verify-catalog.sh' --host ${HOST} --port 8082 --timeout 60" || true

  # 阶段6: 规则引擎 (port 8083)
  run_stage "RULENG" "${STAGE_TIMEOUT}" \
    "bash '${SCRIPT_DIR}/verify-rule-engine.sh' --host ${HOST} --port 8083 --timeout 60" || true
fi

# ----------------------------- 阶段 7: 端到端数据流验证 -----------------------------
if [[ "${SKIP_E2E}" == "true" ]]; then
  echo_warn "阶段 [E2E-FLOW] 已跳过 (--skip-e2e)"
  STAGE_NAMES+=("E2E-FLOW"); STAGE_RCS+=("0"); STAGE_ELAPSEDS+=("0"); STAGE_LOGS+=("(skipped)")
else
  # 端到端完整数据流验证（覆盖设计文档 §3-§10 全链路）
  # 调用 verify-e2e-dataflow.sh，覆盖 V1~V7、V4.5、V5.5 全部验证点：
  #   步骤1: 封装层建工作空间（V1）
  #   步骤2: Flink CDC 实时入湖（V2）
  #   步骤3: Spark 湖→仓主题建模（V3）
  #   步骤4: Doris 湖仓集联动（V4）
  #   步骤4.5: 治理闭环 元数据/质量/血缘（V4.5）
  #   步骤5: 统一 SQL 联邦查询（V5）
  #   步骤5.5: BI 可视化（V5.5）
  #   步骤6: 客户无感知（V6）
  #   步骤7: 四环境一致性（V7）
  run_stage "E2E-FLOW" "${STAGE_TIMEOUT}" \
    "bash '${SCRIPT_DIR}/verify-e2e-dataflow.sh' --host ${HOST} --timeout ${STAGE_TIMEOUT}" || true
fi

# ----------------------------- 汇总报告 -----------------------------
echo ""
echo_poc "=========================================================="
echo_poc "端到端 PoC 汇总"
echo_poc "=========================================================="

TOTAL_PASS=0; TOTAL_FAIL=0; TOTAL_SKIP=0
for i in "${!STAGE_NAMES[@]}"; do
  name="${STAGE_NAMES[$i]}"
  rc="${STAGE_RCS[$i]}"
  elapsed="${STAGE_ELAPSEDS[$i]}"
  log="${STAGE_LOGS[$i]}"
  if [[ "${log}" == "(skipped)" ]]; then
    status="${C_YELLOW}SKIP${C_RESET}"; TOTAL_SKIP=$((TOTAL_SKIP + 1))
  elif [[ "${rc}" == "0" ]]; then
    status="${C_GREEN}${C_BOLD}PASS${C_RESET}"; TOTAL_PASS=$((TOTAL_PASS + 1))
  else
    status="${C_RED}${C_BOLD}FAIL${C_RESET}"; TOTAL_FAIL=$((TOTAL_FAIL + 1))
  fi
  printf "  [%s] %-15s  rc=%-3s  耗时=%-4ss  log=%s\n" \
    "${status}" "${name}" "${rc}" "${elapsed}" "${log}" | tee -a "${MAIN_LOG}"
done

echo "" | tee -a "${MAIN_LOG}" >/dev/null
echo_poc "总计: pass=${TOTAL_PASS}  fail=${TOTAL_FAIL}  skip=${TOTAL_SKIP}"

# 生成 Markdown 报告
{
  echo "# 数据引擎大数据平台 · 端到端 PoC 报告"
  echo ""
  echo "- 时间: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "- 项目根: \`${PROJECT_ROOT}\`"
  echo "- 主日志: \`${MAIN_LOG}\`"
  echo "- 选项: skip-cluster=${SKIP_CLUSTER}, skip-helm=${SKIP_HELM}, skip-platform=${SKIP_PLATFORM}, skip-e2e=${SKIP_E2E}"
  echo "- profile=${SKE_PROFILE}, mode=${SKE_MODE}, target=${SKE_TARGET}, timeout=${STAGE_TIMEOUT}s, host=${HOST}"
  echo ""
  echo "## 阶段结果"
  echo ""
  echo "| 阶段 | 状态 | 耗时(s) | 退出码 | 日志 |"
  echo "|------|------|---------|--------|------|"
  for i in "${!STAGE_NAMES[@]}"; do
    name="${STAGE_NAMES[$i]}"; rc="${STAGE_RCS[$i]}"; elapsed="${STAGE_ELAPSEDS[$i]}"; log="${STAGE_LOGS[$i]}"
    if [[ "${log}" == "(skipped)" ]]; then status="SKIP"
    elif [[ "${rc}" == "0" ]]; then status="PASS"; else status="FAIL"; fi
    echo "| ${name} | ${status} | ${elapsed} | ${rc} | \`${log}\` |"
  done
  echo ""
  echo "## 汇总"
  echo ""
  echo "- PASS: ${TOTAL_PASS}"
  echo "- FAIL: ${TOTAL_FAIL}"
  echo "- SKIP: ${TOTAL_SKIP}"
  echo ""
  if [[ ${TOTAL_FAIL} -eq 0 ]]; then
    echo "## 结论"
    echo ""
    echo "**端到端 PoC 全部阶段通过。**"
  else
    echo "## 结论"
    echo ""
    echo "**端到端 PoC 存在 ${TOTAL_FAIL} 个失败阶段, 请查看对应日志排查。**"
  fi
} > "${MAIN_REPORT}"

echo_poc "Markdown 报告: ${MAIN_REPORT}"

if [[ ${TOTAL_FAIL} -gt 0 ]]; then
  echo_fail "端到端 PoC 存在失败阶段"
  exit 1
fi
echo_pass "端到端 PoC 全部通过"
exit 0