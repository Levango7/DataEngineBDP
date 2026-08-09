#!/usr/bin/env bash
# ============================================================================
# 数据引擎大数据平台 全链路性能压测一键运行脚本
#
# T046 交付物 · 启动 Docker 服务 → 运行压测 → 生成报告 → 清理
#
# 用法:
#   bash run_perf.sh                          # 全流程（启动+压测+报告+清理）
#   bash run_perf.sh --skip-start             # 跳过服务启动（服务已运行）
#   bash run_perf.sh --skip-cleanup           # 跳过清理（保留服务运行）
#   bash run_perf.sh --only-benchmark         # 仅运行基准压测
#   bash run_perf.sh --only-sla               # 仅运行 SLA 验证
#   bash run_perf.sh --ci                     # CI 模式（降低压测强度）
#
# 环境变量:
#   PERF_REQUESTS_PER_USER  每用户请求数（默认 20，CI 建议 10）
#   PERF_STABILITY_DURATION 稳定性测试时长秒（默认 30，生产 1800）
#   PERF_SAMPLE_INTERVAL    资源采样间隔秒（默认 0.5）
# ============================================================================

set -euo pipefail

# ============================================================================
# 配置
# ============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
INTEGRATION_DIR="$PROJECT_ROOT/tests/integration"
PERF_DIR="$SCRIPT_DIR"
REPORT_DIR="$PERF_DIR/reports"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "${BLUE}[STEP]${NC} $*"; }

# 参数解析
SKIP_START=false
SKIP_CLEANUP=false
ONLY_BENCHMARK=false
ONLY_SLA=false
CI_MODE=false

for arg in "$@"; do
    case "$arg" in
        --skip-start)     SKIP_START=true ;;
        --skip-cleanup)   SKIP_CLEANUP=true ;;
        --only-benchmark) ONLY_BENCHMARK=true ;;
        --only-sla)       ONLY_SLA=true ;;
        --ci)             CI_MODE=true ;;
        --help|-h)
            head -20 "$0" | tail -18
            exit 0
            ;;
        *)
            log_warn "未知参数: $arg"
            ;;
    esac
done

# CI 模式调整压测强度
if [ "$CI_MODE" = true ]; then
    export PERF_REQUESTS_PER_USER="${PERF_REQUESTS_PER_USER:-10}"
    export PERF_STABILITY_DURATION="${PERF_STABILITY_DURATION:-10}"
    export PERF_SAMPLE_INTERVAL="${PERF_SAMPLE_INTERVAL:-1.0}"
    log_info "CI 模式：压测强度已降低（requests_per_user=10, stability=10s）"
fi

# 创建报告目录
mkdir -p "$REPORT_DIR"

# 压测结果文件
PYTEST_JSON="$REPORT_DIR/pytest-results-${TIMESTAMP}.json"
HTML_REPORT="$REPORT_DIR/perf-report-${TIMESTAMP}.html"
JSON_REPORT="$REPORT_DIR/perf-report-${TIMESTAMP}.json"

# ============================================================================
# 步骤 1: 检查依赖
# ============================================================================
log_step "步骤 1/5: 检查依赖"

# 检查 Python
if ! command -v python &>/dev/null; then
    log_error "Python 未安装，请先安装 Python 3.11+"
    exit 1
fi
log_info "Python: $(python --version 2>&1)"

# 检查 pytest
if ! python -m pytest --version &>/dev/null; then
    log_warn "pytest 未安装，正在安装依赖..."
    pip install -r "$PERF_DIR/requirements.txt"
fi
log_info "pytest: $(python -m pytest --version 2>&1)"

# ============================================================================
# 步骤 2: 启动 Docker 服务
# ============================================================================
if [ "$SKIP_START" = false ]; then
    log_step "步骤 2/5: 启动 Docker 服务"

    if ! command -v docker &>/dev/null; then
        log_warn "Docker 未安装，跳过服务启动（将依赖已运行的服务）"
    else
        COMPOSE_FILE="$INTEGRATION_DIR/docker-compose.yml"
        if [ -f "$COMPOSE_FILE" ]; then
            log_info "启动 Docker Compose 服务..."
            docker-compose -f "$COMPOSE_FILE" up -d
            log_info "等待服务就绪（30s）..."
            sleep 30
            log_info "Docker 服务状态:"
            docker-compose -f "$COMPOSE_FILE" ps
        else
            log_warn "docker-compose.yml 不存在: $COMPOSE_FILE"
        fi
    fi
else
    log_step "步骤 2/5: 跳过服务启动（--skip-start）"
fi

# ============================================================================
# 步骤 3: 运行性能压测
# ============================================================================
log_step "步骤 3/5: 运行性能压测"

# 确定测试目标
TEST_TARGETS=""
if [ "$ONLY_BENCHMARK" = true ]; then
    TEST_TARGETS="$PERF_DIR/test_performance_benchmark.py"
    log_info "仅运行基准压测"
elif [ "$ONLY_SLA" = true ]; then
    TEST_TARGETS="$PERF_DIR/test_sla_verification.py"
    log_info "仅运行 SLA 验证"
else
    TEST_TARGETS="$PERF_DIR/test_performance_benchmark.py $PERF_DIR/test_sla_verification.py"
    log_info "运行全部性能测试（基准压测 + SLA 验证）"
fi

log_info "测试目标: $TEST_TARGETS"
log_info "压测参数: requests_per_user=${PERF_REQUESTS_PER_USER:-20}, stability=${PERF_STABILITY_DURATION:-30}s"

# 运行 pytest（生成 JSON 结果供报告使用）
log_info "开始运行 pytest..."
python -m pytest $TEST_TARGETS \
    -v \
    --tb=short \
    --json-report \
    --json-report-file="$PYTEST_JSON" \
    --html="$REPORT_DIR/pytest-report-${TIMESTAMP}.html" \
    --self-contained-html \
    2>&1 | tee "$REPORT_DIR/pytest-output-${TIMESTAMP}.log" || {
    log_warn "部分测试失败或跳过（详见日志: $REPORT_DIR/pytest-output-${TIMESTAMP}.log）"
}

log_info "pytest 完成，结果已保存至: $PYTEST_JSON"

# ============================================================================
# 步骤 4: 生成性能压测报告
# ============================================================================
log_step "步骤 4/5: 生成性能压测报告"

if [ -f "$PYTEST_JSON" ]; then
    log_info "从 pytest 结果生成 HTML + JSON 报告..."
    python "$PERF_DIR/perf_report.py" \
        --pytest-json "$PYTEST_JSON" \
        --output "$HTML_REPORT" \
        --format both
else
    log_warn "pytest JSON 结果不存在，生成空白报告..."
    python "$PERF_DIR/perf_report.py" \
        --output "$HTML_REPORT" \
        --format both
fi

log_info "报告已生成:"
log_info "  HTML: $HTML_REPORT"
log_info "  JSON: $JSON_REPORT"

# ============================================================================
# 步骤 5: 清理
# ============================================================================
if [ "$SKIP_CLEANUP" = false ]; then
    log_step "步骤 5/5: 清理 Docker 服务"

    if command -v docker &>/dev/null; then
        COMPOSE_FILE="$INTEGRATION_DIR/docker-compose.yml"
        if [ -f "$COMPOSE_FILE" ]; then
            log_info "停止 Docker Compose 服务..."
            docker-compose -f "$COMPOSE_FILE" down --remove-orphans
            log_info "Docker 服务已停止"
        fi
    fi
else
    log_step "步骤 5/5: 跳过清理（--skip-cleanup）"
fi

# ============================================================================
# 汇总
# ============================================================================
echo ""
echo "========================================"
echo "📊 全链路性能压测完成"
echo "========================================"
echo "时间戳:     $TIMESTAMP"
echo "报告目录:   $REPORT_DIR"
echo "HTML 报告:  $HTML_REPORT"
echo "JSON 报告:  $JSON_REPORT"
echo "pytest 日志: $REPORT_DIR/pytest-output-${TIMESTAMP}.log"
echo "========================================"
echo ""
log_info "完成！请打开 HTML 报告查看详细结果。"