#!/bin/bash
# ============================================================================
# 资产同步脚本 - 从 T018 金融模板同步资产到 T019 Chart（T019）
# ============================================================================
# 用途：将 platform/industry-templates/templates/finance/ 下的模板资产
#       同步到 design/deploy/charts/finance-template/assets/ 目录，
#       供 Helm Chart 打包为 ConfigMap。
# 用法： bash sync-assets.sh
# ============================================================================
set -euo pipefail

# 脚本所在目录（chart 根目录）
CHART_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 项目根目录（向上 4 级：finance-template -> charts -> deploy -> design -> root）
PROJECT_ROOT="$(cd "${CHART_DIR}/../../../.." && pwd)"

# T018 模板源目录
SRC_DIR="${PROJECT_ROOT}/platform/industry-templates/templates/finance"
# 资产目标目录
DST_DIR="${CHART_DIR}/assets"

echo "=========================================="
echo "金融模板资产同步（T018 -> T019）"
echo "=========================================="
echo "源: ${SRC_DIR}"
echo "目标: ${DST_DIR}"
echo "=========================================="

# 检查源目录存在
if [ ! -d "${SRC_DIR}" ]; then
  echo "ERROR: 源目录不存在: ${SRC_DIR}"
  exit 1
fi

# 清空目标目录
rm -rf "${DST_DIR}"
mkdir -p "${DST_DIR}"

# 同步各资产子目录
ASSET_DIRS=("ddl" "dag" "dashboard" "rbac" "docs")
for dir in "${ASSET_DIRS[@]}"; do
  if [ -d "${SRC_DIR}/${dir}" ]; then
    echo "同步 ${dir}/..."
    cp -r "${SRC_DIR}/${dir}" "${DST_DIR}/${dir}"
    FILE_COUNT=$(find "${DST_DIR}/${dir}" -type f | wc -l)
    echo "  ${FILE_COUNT} 个文件"
  else
    echo "WARN: 源目录缺少 ${dir}/，跳过"
  fi
done

# 同步模板元数据
if [ -f "${SRC_DIR}/template-metadata.yaml" ]; then
  echo "同步 template-metadata.yaml..."
  cp "${SRC_DIR}/template-metadata.yaml" "${DST_DIR}/"
fi

# 统计
echo "=========================================="
echo "同步完成"
echo "=========================================="
echo "资产统计:"
echo "  DDL 文件:       $(find "${DST_DIR}/ddl" -name '*.sql' | wc -l) 个"
echo "  DAG 文件:        $(find "${DST_DIR}/dag" -name '*.json' | wc -l) 个"
echo "  Dashboard 文件:  $(find "${DST_DIR}/dashboard" -name '*.json' | wc -l) 个"
echo "  RBAC 文件:       $(find "${DST_DIR}/rbac" -name '*.yaml' | wc -l) 个"
echo "  文档文件:        $(find "${DST_DIR}/docs" -type f | wc -l) 个"
echo "  总文件数:        $(find "${DST_DIR}" -type f | wc -l) 个"
echo "=========================================="
echo "下一步: helm lint ${CHART_DIR}"
echo "        helm template finance-template ${CHART_DIR} -f ${CHART_DIR}/values-dev.yaml"
echo "=========================================="