#!/usr/bin/env bash
# ===========================================================================
# 数据引擎大数据平台 · Python 组件集成测试本地运行脚本（Linux/macOS Bash）
#
# 用途：在本地环境运行 5 个 Python 组件的集成测试：
#   - asset-exchange      (port 8087)
#   - business-portal     (port 8088)
#   - open-api-catalog    (port 8090)
#   - industry-templates  (port 8091)
#   - knowledge-engine    (port 8080)
#
# 流程：
#   1. 检查 Python 版本（要求 3.10+）
#   2. 检查集成测试依赖（pytest / httpx / requests）
#   3. 检查各 Python 组件依赖是否已安装
#   4. 运行 pytest 只执行 Python 组件测试文件
#   5. 输出测试报告（HTML + 控制台摘要）
#
# 组件由 conftest.py 中的 session 级 fixture 自动启动/停止。
# 若组件已外部启动，设置环境变量：export SQ_IT_SKIP_PYTHON_START=1
#
# 用法：
#   ./run_python_tests.sh                          # 运行全部 Python 组件测试
#   ./run_python_tests.sh test_knowledge_engine.py # 运行单个组件测试
#   ./run_python_tests.sh -v                       # 详细日志
# ===========================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}================================================${NC}"
echo -e "${CYAN}  数据引擎大数据平台 · Python 组件集成测试${NC}"
echo -e "${CYAN}================================================${NC}"
echo ""

# ---------------------------------------------------------------------------
# 1. 检查 Python 版本
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[1/4] 检查 Python 环境...${NC}"
if ! command -v python3 &> /dev/null; then
    echo -e "${RED}  ✗ 未找到 python3，请先安装 Python 3.10+${NC}"
    exit 1
fi
PY_VERSION=$(python3 --version 2>&1)
echo -e "${GREEN}  ✓ ${PY_VERSION}${NC}"
PYTHON=python3

# ---------------------------------------------------------------------------
# 2. 检查集成测试依赖
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[2/4] 检查集成测试依赖...${NC}"
for dep in pytest httpx requests; do
    if ! $PYTHON -c "import $dep" 2>/dev/null; then
        echo -e "${YELLOW}  ⚠ 缺少依赖: $dep，正在安装...${NC}"
        pip install "$dep" || pip3 install "$dep"
    fi
    VER=$($PYTHON -c "import $dep; print(getattr($dep, '__version__', 'unknown'))" 2>&1)
    echo -e "${GREEN}  ✓ $dep ($VER)${NC}"
done

# ---------------------------------------------------------------------------
# 3. 检查 Python 组件依赖
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[3/4] 检查 Python 组件依赖...${NC}"
declare -A COMPONENTS=(
    ["asset-exchange"]="asset_exchange:platform/asset-exchange"
    ["business-portal"]="business_portal:platform/business-portal"
    ["open-api-catalog"]="openapi_catalog:platform/open-api-catalog"
    ["industry-templates"]="industry_templates:platform/industry-templates"
    ["knowledge-engine"]="knowledge_engine:platform/knowledge-engine"
)
for name in "${!COMPONENTS[@]}"; do
    IFS=':' read -r pkg dir <<< "${COMPONENTS[$name]}"
    comp_dir="$PROJECT_ROOT/$dir"
    if ! PYTHONPATH="$comp_dir" $PYTHON -c "import $pkg" 2>/dev/null; then
        echo -e "${YELLOW}  ⚠ $name: 依赖未安装，尝试 pip install...${NC}"
        if [ -f "$comp_dir/requirements.txt" ]; then
            pip install -r "$comp_dir/requirements.txt" 2>/dev/null || true
        fi
        if PYTHONPATH="$comp_dir" $PYTHON -c "import $pkg" 2>/dev/null; then
            echo -e "${GREEN}  ✓ $name${NC}"
        else
            echo -e "${RED}  ✗ $name: 依赖安装失败，请手动检查${NC}"
        fi
    else
        echo -e "${GREEN}  ✓ $name${NC}"
    fi
done

# ---------------------------------------------------------------------------
# 4. 运行 pytest
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[4/4] 运行集成测试...${NC}"
echo ""

# 默认运行全部 5 个 Python 组件测试
TEST_FILES=(
    "test_asset_exchange.py"
    "test_business_portal.py"
    "test_open_api_catalog.py"
    "test_industry_templates.py"
    "test_knowledge_engine.py"
)

# 解析参数：若第一个参数是测试文件名则只运行该文件
VERBOSE=""
if [ $# -gt 0 ]; then
    if [[ "$1" == *.py ]]; then
        TEST_FILES=("$1")
        shift
    fi
    if [[ "$1" == "-v" || "$1" == "--verbose" ]]; then
        VERBOSE="--log-cli-level=INFO"
    fi
fi

PYTEST_ARGS=()
for f in "${TEST_FILES[@]}"; do
    PYTEST_ARGS+=("$SCRIPT_DIR/$f")
done
PYTEST_ARGS+=(
    "-v"
    "--tb=short"
    "--html=$SCRIPT_DIR/report_python.html"
    "--self-contained-html"
)
if [ -n "$VERBOSE" ]; then
    PYTEST_ARGS+=("$VERBOSE")
fi

echo -e "${CYAN}pytest ${PYTEST_ARGS[*]}${NC}"
echo ""

$PYTHON -m pytest "${PYTEST_ARGS[@]}" || EXIT_CODE=$?
EXIT_CODE=${EXIT_CODE:-0}

echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}================================================${NC}"
    echo -e "${GREEN}  ✓ 所有测试通过${NC}"
    echo -e "${GREEN}================================================${NC}"
else
    echo -e "${RED}================================================${NC}"
    echo -e "${RED}  ✗ 部分测试失败（退出码 $EXIT_CODE）${NC}"
    echo -e "${RED}================================================${NC}"
fi
echo ""
echo -e "${CYAN}HTML 报告: $SCRIPT_DIR/report_python.html${NC}"

exit $EXIT_CODE