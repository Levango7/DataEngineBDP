#!/usr/bin/env bash
# ============================================================
# 覆盖率门禁脚本
# 用途：检查各语言覆盖率是否达到门禁阈值，未达标则退出非零
# 调用：bash scripts/coverage-gate.sh
# CI 集成：在 ci.yml markdown-lint job 之前调用
# ============================================================
set -euo pipefail

# 门禁阈值（百分比）
JAVA_MIN_COVERAGE=70
GO_MIN_COVERAGE=70
PYTHON_MIN_COVERAGE=70
FRONTEND_MIN_COVERAGE=80

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "============================================"
echo "  覆盖率门禁检查"
echo "============================================"
echo "Java 最低覆盖率:   ${JAVA_MIN_COVERAGE}%"
echo "Go 最低覆盖率:     ${GO_MIN_COVERAGE}%"
echo "Python 最低覆盖率: ${PYTHON_MIN_COVERAGE}%"
echo "前端最低覆盖率:    ${FRONTEND_MIN_COVERAGE}%"
echo ""

fail_count=0

# ============================================================
# Java JaCoCo 覆盖率检查
# 检查 platform/ 下所有 target/site/jacoco/jacoco.xml
# ============================================================
check_java_coverage() {
    echo "--- Java JaCoCo 覆盖率 ---"
    if ! find platform -path "*/target/site/jacoco/jacoco.xml" 2>/dev/null | grep -q .; then
        echo -e "${YELLOW}[WARN] 未发现 JaCoCo 报告，跳过 Java 覆盖率检查${NC}"
        return 0
    fi
    while IFS= read -r cov; do
        mod_dir=$(dirname "$(dirname "$(dirname "$cov")")")
        mod_name=$(basename "$mod_dir")
        # 从 XML 提取 line coverage（粗略解析）
        if command -v python3 &>/dev/null; then
            coverage=$(python3 -c "
import xml.etree.ElementTree as ET
try:
    tree = ET.parse('$cov')
    root = tree.getroot()
    for counter in root.findall('counter'):
        if counter.get('type') == 'LINE':
            missed = int(counter.get('missed', 0))
            covered = int(counter.get('covered', 0))
            total = missed + covered
            if total > 0:
                print(round(covered * 100 / total, 2))
            else:
                print(0)
            break
    else:
        print(0)
except Exception:
    print(0)
" 2>/dev/null)
        else
            coverage="N/A"
        fi
        if [ "$coverage" = "N/A" ]; then
            echo -e "${YELLOW}[SKIP] $mod_name: 无法解析覆盖率${NC}"
        elif (( $(echo "$coverage >= $JAVA_MIN_COVERAGE" | bc -l 2>/dev/null || echo 0) )); then
            echo -e "${GREEN}[PASS] $mod_name: ${coverage}%${NC}"
        else
            echo -e "${RED}[FAIL] $mod_name: ${coverage}% (低于 ${JAVA_MIN_COVERAGE}%)${NC}"
            fail_count=$((fail_count + 1))
        fi
    done < <(find platform -path "*/target/site/jacoco/jacoco.xml" 2>/dev/null)
}

# ============================================================
# Go 覆盖率检查
# 检查 go-coverage-reports/ 下的 .cov 文件
# ============================================================
check_go_coverage() {
    echo "--- Go 覆盖率 ---"
    if ! find . -name "*.cov" -path "*/go-coverage-reports/*" 2>/dev/null | grep -q .; then
        echo -e "${YELLOW}[WARN] 未发现 Go 覆盖率报告，跳过${NC}"
        return 0
    fi
    while IFS= read -r cov; do
        mod_name=$(basename "$cov" .cov)
        # 使用 go tool cover 计算总覆盖率
        if command -v go &>/dev/null; then
            coverage=$(go tool cover -func="$cov" 2>/dev/null | tail -1 | awk '{print $NF}' | tr -d '%')
        else
            coverage="N/A"
        fi
        if [ "$coverage" = "N/A" ] || [ -z "$coverage" ]; then
            echo -e "${YELLOW}[SKIP] $mod_name: 无法解析覆盖率${NC}"
        elif (( $(echo "$coverage >= $GO_MIN_COVERAGE" | bc -l 2>/dev/null || echo 0) )); then
            echo -e "${GREEN}[PASS] $mod_name: ${coverage}%${NC}"
        else
            echo -e "${RED}[FAIL] $mod_name: ${coverage}% (低于 ${GO_MIN_COVERAGE}%)${NC}"
            fail_count=$((fail_count + 1))
        fi
    done < <(find . -name "*.cov" -path "*/go-coverage-reports/*" 2>/dev/null)
}

# ============================================================
# Python 覆盖率检查
# 检查 python-coverage-reports/ 下的 *-coverage.xml
# ============================================================
check_python_coverage() {
    echo "--- Python 覆盖率 ---"
    if ! find . -name "*-coverage.xml" -path "*/python-coverage-reports/*" 2>/dev/null | grep -q .; then
        echo -e "${YELLOW}[WARN] 未发现 Python 覆盖率报告，跳过${NC}"
        return 0
    fi
    while IFS= read -r cov; do
        mod_name=$(basename "$cov" -coverage.xml)
        if command -v python3 &>/dev/null; then
            coverage=$(python3 -c "
import xml.etree.ElementTree as ET
try:
    tree = ET.parse('$cov')
    root = tree.getroot()
    total = float(root.attrib.get('line-rate', 0))
    print(round(total * 100, 2))
except Exception:
    print(0)
" 2>/dev/null)
        else
            coverage="N/A"
        fi
        if [ "$coverage" = "N/A" ]; then
            echo -e "${YELLOW}[SKIP] $mod_name: 无法解析覆盖率${NC}"
        elif (( $(echo "$coverage >= $PYTHON_MIN_COVERAGE" | bc -l 2>/dev/null || echo 0) )); then
            echo -e "${GREEN}[PASS] $mod_name: ${coverage}%${NC}"
        else
            echo -e "${RED}[FAIL] $mod_name: ${coverage}% (低于 ${PYTHON_MIN_COVERAGE}%)${NC}"
            fail_count=$((fail_count + 1))
        fi
    done < <(find . -name "*-coverage.xml" -path "*/python-coverage-reports/*" 2>/dev/null)
}

# ============================================================
# 前端覆盖率检查
# 检查 frontend/coverage/ 下的 coverage-summary.json
# ============================================================
check_frontend_coverage() {
    echo "--- 前端覆盖率 ---"
    if [ ! -f "frontend/coverage/coverage-summary.json" ]; then
        echo -e "${YELLOW}[WARN] frontend/coverage/coverage-summary.json 不存在，跳过${NC}"
        return 0
    fi
    if command -v python3 &>/dev/null; then
        coverage=$(python3 -c "
import json
try:
    with open('frontend/coverage/coverage-summary.json') as f:
        data = json.load(f)
    total = data.get('total', {})
    lines = total.get('lines', {}).get('pct', 0)
    print(lines)
except Exception:
    print(0)
" 2>/dev/null)
    else
        coverage="N/A"
    fi
    if [ "$coverage" = "N/A" ]; then
        echo -e "${YELLOW}[SKIP] frontend: 无法解析覆盖率${NC}"
    elif (( $(echo "$coverage >= $FRONTEND_MIN_COVERAGE" | bc -l 2>/dev/null || echo 0) )); then
        echo -e "${GREEN}[PASS] frontend: ${coverage}%${NC}"
    else
        echo -e "${RED}[FAIL] frontend: ${coverage}% (低于 ${FRONTEND_MIN_COVERAGE}%)${NC}"
        fail_count=$((fail_count + 1))
    fi
}

# 执行检查
check_java_coverage
echo ""
check_go_coverage
echo ""
check_python_coverage
echo ""
check_frontend_coverage

# 汇总
echo ""
echo "============================================"
if [ "$fail_count" -eq 0 ]; then
    echo -e "${GREEN}  覆盖率门禁检查通过${NC}"
    exit 0
else
    echo -e "${RED}  覆盖率门禁检查失败：${fail_count} 个组件未达标${NC}"
    exit 1
fi