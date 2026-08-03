#!/usr/bin/env bash
# 一键清理演示环境与测试数据
# 客户视角: 用封装层 CLI 删工作空间 (底层级联回收 K8s / Iceberg / Doris 资源)
# 外部源: MySQL 测试表由本脚本显式清理 (封装层不负责外部数据源)
set -uo pipefail

DQCTL="${DQCTL:-dqctl}"
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "==> [客户] 删除演示工作空间 (级联回收底层资源)"
if command -v "$DQCTL" >/dev/null 2>&1; then
  $DQCTL workspace delete --name demo-fin --force
else
  echo "[SIM] dqctl workspace delete --name demo-fin --force"
  echo "   (底层翻译: 删 Namespace ws-demo-fin + Iceberg 表 ods/dwd/dws + Doris MV)"
fi

echo "==> 清理 MySQL 测试库表 (外部源, 需显式清理)"
mysql -h"${MYSQL_HOST:-localhost}" -u"${MYSQL_USER:-root}" \
  -p"${MYSQL_PASS:-}" -e "DROP TABLE IF EXISTS fin.user_order;" 2>/dev/null \
  && echo "   fin.user_order 已清理" \
  || echo "   (跳过: 未配置 mysql 或表已不存在)"

echo "==> 演示环境已清理, 可重新运行: bash run-demo.sh"
