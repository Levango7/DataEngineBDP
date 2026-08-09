#!/usr/bin/env bash
# =============================================================================
# E2E 测试一键运行脚本
# =============================================================================
# 功能：
#   1. 启动 Docker 服务（docker-compose up -d）；
#   2. 等待服务就绪；
#   3. 运行 E2E 测试套件（跨领域 + 28 项需求验收）；
#   4. 生成 HTML + JSON 报告；
#   5. 清理 Docker 服务（可选，--no-cleanup 跳过）。
#
# 用法：
#   ./run_e2e.sh                    # 默认：启动服务→测试→生成报告→清理
#   ./run_e2e.sh --no-cleanup       # 不清理 Docker 服务（便于排查）
#   ./run_e2e.sh --no-docker        # 跳过 Docker 启动（服务已外部启动）
#   ./run_e2e.sh --html custom.html # 指定 HTML 报告输出路径
#
# 退出码：
#   0 - 全部通过
#   1 - 有失败用例
#   2 - 环境错误（Docker/Python 缺失）
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
NO_CLEANUP=0
NO_DOCKER=0
HTML_REPORT="e2e_report.html"
JUNIT_REPORT="e2e-junit.xml"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-cleanup) NO_CLEANUP=1; shift ;;
    --no-docker)  NO_DOCKER=1;  shift ;;
    --html)       HTML_REPORT="$2"; shift 2 ;;
    --junit)      JUNIT_REPORT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      exit 2
      ;;
  esac
done

# ---------------------------------------------------------------------------
# 路径与常量
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_COMPOSE="$PROJECT_ROOT/tests/integration/docker-compose.yml"
E2E_DIR="$SCRIPT_DIR"

echo "=========================================="
echo "  数据引擎大数据平台 E2E 测试套件"
echo "=========================================="
echo "项目根目录: $PROJECT_ROOT"
echo "E2E 目录:   $E2E_DIR"
echo "HTML 报告:  $HTML_REPORT"
echo "Junit 报告: $JUNIT_REPORT"
echo "=========================================="

# ---------------------------------------------------------------------------
# 环境检查
# ---------------------------------------------------------------------------
command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 || {
  echo "错误：未找到 Python 可执行文件" >&2
  exit 2
}
PYTHON="$(command -v python3 || command -v python)"
echo "使用 Python: $PYTHON"
$PYTHON --version

command -v pytest >/dev/null 2>&1 || {
  echo "pytest 未安装，尝试安装依赖..." >&2
  $PYTHON -m pip install -r "$E2E_DIR/requirements.txt" || {
    echo "依赖安装失败" >&2
    exit 2
  }
}

# ---------------------------------------------------------------------------
# 启动 Docker 服务
# ---------------------------------------------------------------------------
if [[ "$NO_DOCKER" -eq 0 ]]; then
  command -v docker >/dev/null 2>&1 || {
    echo "警告：未找到 docker，跳过 Docker 启动（测试将自动 skip）"
    NO_DOCKER=1
  }
fi

if [[ "$NO_DOCKER" -eq 0 ]] && [[ -f "$DOCKER_COMPOSE" ]]; then
  echo "[1/5] 启动 Docker 服务..."
  docker compose -f "$DOCKER_COMPOSE" up -d --wait || {
    echo "警告：Docker 服务启动失败，测试将自动 skip 不可用的用例"
  }
  echo "      Docker 服务已启动"
else
  echo "[1/5] 跳过 Docker 启动（--no-docker 或未找到 docker-compose.yml）"
fi

# ---------------------------------------------------------------------------
# 运行 E2E 测试
# ---------------------------------------------------------------------------
echo "[2/5] 运行 E2E 测试套件..."

TEST_EXIT=0
$PYTHON -m pytest "$E2E_DIR" \
  -v \
  --junitxml="$JUNIT_REPORT" \
  -o junit_family=junit1 \
  --tb=short \
  || TEST_EXIT=$?

echo "      测试执行完成，退出码: $TEST_EXIT"

# ---------------------------------------------------------------------------
# 生成报告
# ---------------------------------------------------------------------------
echo "[3/5] 生成 HTML + JSON 报告..."
$PYTHON "$E2E_DIR/e2e_report.py" \
  --junit "$JUNIT_REPORT" \
  --output "$HTML_REPORT" \
  || echo "警告：报告生成失败"

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
echo "[4/5] 测试汇总:"
if [[ -f "$JUNIT_REPORT" ]]; then
  TOTAL=$(grep -c "<testcase" "$JUNIT_REPORT" || echo 0)
  FAILED=$(grep -c "<failure" "$JUNIT_REPORT" || echo 0)
  SKIPPED=$(grep -c "<skipped" "$JUNIT_REPORT" || echo 0)
  ERRORS=$(grep -c "<error" "$JUNIT_REPORT" || echo 0)
  PASSED=$((TOTAL - FAILED - SKIPPED - ERRORS))
  echo "      总用例: $TOTAL"
  echo "      通过:   $PASSED"
  echo "      失败:   $FAILED"
  echo "      跳过:   $SKIPPED"
  echo "      错误:   $ERRORS"
fi

# ---------------------------------------------------------------------------
# 清理
# ---------------------------------------------------------------------------
if [[ "$NO_CLEANUP" -eq 0 ]] && [[ "$NO_DOCKER" -eq 0 ]] && [[ -f "$DOCKER_COMPOSE" ]]; then
  echo "[5/5] 清理 Docker 服务..."
  docker compose -f "$DOCKER_COMPOSE" down --remove-orphans || true
else
  echo "[5/5] 跳过清理（--no-cleanup 或 --no-docker）"
fi

echo "=========================================="
echo "  E2E 测试完成"
echo "  HTML 报告: $HTML_REPORT"
echo "  Junit 报告: $JUNIT_REPORT"
echo "=========================================="

exit $TEST_EXIT