#!/usr/bin/env bash
# =============================================================================
# 跨域数据面 POC 编排脚本
#
# 目标：本地起两个真实 MinIO（源域/目标域），运行 CrossDomainReplicationIT，
#       打通一条真实的数据复制 + 读取校验链路，并断言产物为真实（非 simulate）。
#
# 用法：
#   bash scripts/infra/test-replication-it.sh
#   bash scripts/infra/test-replication-it.sh --keep        # 保留容器（调试）
#   bash scripts/infra/test-replication-it.sh --down-only   # 仅清理容器
#
# 依赖：docker + docker compose + mvn
# =============================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "${CYAN}[STEP]${NC}  $*"; }

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODULE_DIR="${PROJECT_ROOT}/platform/storage-io"
COMPOSE_FILE="${MODULE_DIR}/docker/docker-compose.replication.yml"
RESULTS_DIR="${PROJECT_ROOT}/tests/cross-domain-replication/results"
SOURCE_ENDPOINT="http://localhost:9100"
TARGET_ENDPOINT="http://localhost:9110"

KEEP=false
DOWN_ONLY=false
for arg in "$@"; do
    case "$arg" in
        --keep)      KEEP=true ;;
        --down-only) DOWN_ONLY=true ;;
        *) log_warn "未知选项: $arg" ;;
    esac
done

compose() { docker compose -f "${COMPOSE_FILE}" "$@"; }

cleanup() {
    if [ "${KEEP}" = "true" ]; then
        log_warn "保留容器（--keep）；清理请执行: bash $0 --down-only"
        return 0
    fi
    log_step "清理容器..."
    compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

check_deps() {
    local missing=()
    command -v docker >/dev/null 2>&1 || missing+=("docker")
    command -v mvn    >/dev/null 2>&1 || missing+=("mvn")
    if [ ${#missing[@]} -gt 0 ]; then
        log_error "缺少依赖: ${missing[*]}"; exit 1
    fi
    docker compose version >/dev/null 2>&1 || { log_error "docker compose 不可用"; exit 1; }
}

wait_healthy() {
    local name="$1" url="$2" i=0
    log_step "等待 ${name} 就绪: ${url}"
    while ! curl -fsS "${url}" >/dev/null 2>&1; do
        i=$((i + 1))
        if [ $i -ge 60 ]; then log_error "${name} 120s 未就绪"; exit 1; fi
        sleep 2
    done
    log_info "${name} 就绪"
}

check_deps

if [ "${DOWN_ONLY}" = "true" ]; then
    compose down -v || true
    log_info "容器已清理"; exit 0
fi

log_step "启动双 MinIO（源 ${SOURCE_ENDPOINT} / 目标 ${TARGET_ENDPOINT}）..."
compose up -d
wait_healthy "minio-source" "${SOURCE_ENDPOINT}/minio/health/live"
wait_healthy "minio-target" "${TARGET_ENDPOINT}/minio/health/live"

log_step "运行 CrossDomainReplicationIT（真实复制链路）..."
mkdir -p "${RESULTS_DIR}"
cd "${MODULE_DIR}"
set +e
mvn -B test \
    -Dtest=CrossDomainReplicationIT \
    -Dreplication.it=true \
    -Dcdr.resultsDir="${RESULTS_DIR}" \
    2>&1 | tee "${RESULTS_DIR}/mvn-it.log"
rc=${PIPESTATUS[0]}
set -e

LATEST_JSON="$(ls -1t "${RESULTS_DIR}"/cdr-report-*.json 2>/dev/null | head -1 || true)"
if [ -z "${LATEST_JSON}" ]; then
    log_error "未生成报告产物（应为 ${RESULTS_DIR}/cdr-report-*.json）"; exit 1
fi

log_step "校验产物真实性: ${LATEST_JSON}"
if grep -qiE 'simulate|"mode"[[:space:]]*:[[:space:]]*"mock"' "${LATEST_JSON}"; then
    log_error "产物命中 simulate/mock 标记，拒绝通过"; exit 1
fi
grep -q '"mode"[[:space:]]*:[[:space:]]*"real"' "${LATEST_JSON}" \
    || { log_error "产物缺少 mode=real 标识"; exit 1; }
grep -qE '"sourceSha256"[[:space:]]*:[[:space:]]*"[0-9a-f]{64}"' "${LATEST_JSON}" \
    || { log_error "产物缺少真实 sha256"; exit 1; }
grep -q '"allVerified"[[:space:]]*:[[:space:]]*true' "${LATEST_JSON}" \
    || { log_error "产物未全部校验通过"; exit 1; }

if [ ${rc} -ne 0 ]; then
    log_error "集成测试失败（退出码 ${rc}），详见 ${RESULTS_DIR}/mvn-it.log"; exit ${rc}
fi

log_info "真实跨域复制链路打通，产物: ${LATEST_JSON}"
echo -e "${CYAN}============================================================${NC}"
echo -e "${GREEN}  跨域数据面 POC: 通过（真实复制 + sha256 校验 + LWW 冲突）${NC}"
echo -e "${CYAN}============================================================${NC}"
