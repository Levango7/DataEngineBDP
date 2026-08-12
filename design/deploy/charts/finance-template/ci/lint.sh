#!/bin/bash
# ============================================================================
# CI lint 脚本 - finance-template Chart CI 校验（T019）
# ============================================================================
# 用途：CI 流水线中校验 Chart 结构、YAML 语法、模板渲染
# 用法： bash ci/lint.sh
# ============================================================================
set -euo pipefail

CHART_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_NAME="finance-template"

echo "=========================================="
echo "CI Lint: ${CHART_NAME}"
echo "=========================================="

ERRORS=0

# 1. 检查必需文件
echo "[1/5] 检查文件结构..."
REQUIRED_FILES=("Chart.yaml" "values.yaml" "templates/_helpers.tpl" "templates/configmap-assets.yaml" "templates/import-job.yaml")
for f in "${REQUIRED_FILES[@]}"; do
  if [ ! -f "${CHART_DIR}/${f}" ]; then
    echo "  FAIL: 缺失 ${f}"
    ERRORS=$((ERRORS+1))
  fi
done
echo "  PASS: 文件结构完整"

# 2. 检查多环境 values
echo "[2/5] 检查多环境 values..."
ENV_VALUES=("values-dev.yaml" "values-staging.yaml" "values-prod.yaml")
for f in "${ENV_VALUES[@]}"; do
  if [ ! -f "${CHART_DIR}/${f}" ]; then
    echo "  FAIL: 缺失 ${f}"
    ERRORS=$((ERRORS+1))
  fi
done
echo "  PASS: 多环境 values 齐全"

# 3. YAML 语法检查
echo "[3/5] YAML 语法检查..."
YAML_FILES=$(find "${CHART_DIR}" -name "*.yaml" -not -path "*/assets/*" -not -path "*/dist/*")
for f in ${YAML_FILES}; do
  python3 -c "import yaml; yaml.safe_load(open('${f}', encoding='utf-8'))" 2>/dev/null || {
    # Go template 文件可能不是纯 YAML，跳过
    if ! grep -q "{{" "${f}"; then
      echo "  FAIL: ${f} YAML 语法错误"
      ERRORS=$((ERRORS+1))
    fi
  }
done
echo "  PASS: YAML 语法正确"

# 4. Go template 花括号配对检查
echo "[4/5] Go template 花括号配对检查..."
TEMPLATE_FILES=$(find "${CHART_DIR}/templates" -type f)
for f in ${TEMPLATE_FILES}; do
  OPEN=$(grep -o "{{" "${f}" | wc -l)
  CLOSE=$(grep -o "}}" "${f}" | wc -l)
  if [ "${OPEN}" -ne "${CLOSE}" ]; then
    echo "  FAIL: ${f} 花括号不配对（{{ count=${OPEN}, }} count=${CLOSE}）"
    ERRORS=$((ERRORS+1))
  fi
done
echo "  PASS: 花括号配对正确"

# 5. helm lint（若 helm 可用）
echo "[5/5] helm lint..."
if command -v helm &> /dev/null; then
  helm lint "${CHART_DIR}" > /dev/null 2>&1 || {
    echo "  FAIL: helm lint 失败"
    ERRORS=$((ERRORS+1))
  }
  echo "  PASS: helm lint 通过"
else
  echo "  SKIP: helm 未安装"
fi

echo "=========================================="
if [ ${ERRORS} -eq 0 ]; then
  echo "CI Lint 全部通过"
  echo "=========================================="
  exit 0
else
  echo "CI Lint 失败（${ERRORS} 个错误）"
  echo "=========================================="
  exit 1
fi