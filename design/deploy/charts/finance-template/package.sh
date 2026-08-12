#!/bin/bash
# ============================================================================
# Chart 打包脚本 - 打包 finance-template Helm Chart（T019）
# ============================================================================
# 用途：同步资产 + lint + 多环境 template 验证 + package
# 用法： bash package.sh [环境]
#   不传参数: 仅 lint + template 验证
#   package: 打包为 .tgz
# ============================================================================
set -euo pipefail

CHART_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_NAME="finance-template"
ACTION="${1:-lint}"

echo "=========================================="
echo "finance-template Helm Chart ${ACTION}"
echo "=========================================="

# Step 1: 同步资产
echo "[1/4] 同步模板资产..."
bash "${CHART_DIR}/sync-assets.sh"

# Step 2: helm lint
echo "[2/4] helm lint..."
helm lint "${CHART_DIR}"
echo "  PASS: helm lint"

# Step 3: 多环境 template 验证
echo "[3/4] 多环境 helm template 验证..."
ENVS=("dev" "staging" "prod")
for env in "${ENVS[@]}"; do
  VALUES_FILE="${CHART_DIR}/values-${env}.yaml"
  if [ -f "${VALUES_FILE}" ]; then
    echo "  渲染 ${env} 环境..."
    OUTPUT=$(helm template "${CHART_NAME}" "${CHART_DIR}" -f "${VALUES_FILE}" 2>&1) || {
      echo "  FAIL: ${env} 环境渲染失败"
      echo "${OUTPUT}"
      exit 1
    }
    RESOURCE_COUNT=$(echo "${OUTPUT}" | grep -c "^kind:" || true)
    echo "    PASS: ${env} 环境渲染成功（${RESOURCE_COUNT} 个资源）"
  fi
done

# Step 4: 打包（可选）
if [ "${ACTION}" = "package" ]; then
  echo "[4/4] helm package..."
  DIST_DIR="${CHART_DIR}/dist"
  mkdir -p "${DIST_DIR}"
  helm package "${CHART_DIR}" -d "${DIST_DIR}"
  echo "  打包完成: ${DIST_DIR}/"
  ls -la "${DIST_DIR}/"
else
  echo "[4/4] 跳过打包（使用 'bash package.sh package' 打包）"
fi

echo "=========================================="
echo "全部完成"
echo "=========================================="