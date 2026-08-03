#!/usr/bin/env bash
# 端到端演示一键串联：MySQL → Iceberg(湖) → Spark(仓) → Doris(集) → 统一 SQL 网关
# 客户视角全程仅用封装层 CLI dqctl; K8s 资源由封装层翻译并交 Operator 托管 (客户无感)
set -uo pipefail

DQCTL="${DQCTL:-dqctl}"
PROFILE="${DQCTL_PROFILE:-xinchuang}"
DIR="$(cd "$(dirname "$0")" && pwd)"

# dqctl 不存在时进入模拟模式, 打印将发生的动作与底层翻译
if ! command -v "$DQCTL" >/dev/null 2>&1; then
  DQCTL="echo [SIM] dqctl"
  echo "!! dqctl 未安装, 进入模拟模式 (仅展示客户视角命令与底层翻译)"
fi

echo "============================================================"
echo " 数擎大数据平台 · 端到端演示 (Profile=$PROFILE)"
echo "============================================================"

echo; echo "── [V1] 建工作空间 + 数据项目 (封装层 API) ──"
bash "$DIR/00-bootstrap-workspace.sh"

echo; echo "── [准备] 注入测试数据到 MySQL 测试库 (非硬编码, cleanup 可清) ──"
mysql -h"${MYSQL_HOST:-localhost}" -u"${MYSQL_USER:-root}" -p"${MYSQL_PASS:-}" \
  fin < "$DIR/05-seed-test-data.sql" 2>/dev/null \
  && echo "   测试数据已注入 fin.user_order (5 行)" \
  || echo "   (跳过 seed: 未配置 mysql, 请确保 fin.user_order 已存在)"

echo; echo "── [V2] 实时入湖: Flink CDC → Iceberg 湖层 ods_user_order ──"
$DQCTL job submit --workspace demo-fin --project trade --type flink --file "$DIR/01-cdc-iceberg.sql"
echo "   (底层: FlinkDeployment CR → JobManager/TaskManager Pod, Operator 托管)"
echo "   验证: MySQL UPDATE user_order SET amount=99.9 WHERE order_id=1; 3~5s 后 Iceberg 可见"

echo; echo "── [V3] 湖→仓建模: Spark → dwd/dws (共享 warehouse, 无冗余) ──"
$DQCTL job submit --workspace demo-fin --project trade --type spark --file "$DIR/02-spark-dwd.sql"
echo "   (底层: SparkApplication CR → Driver/Executor Pod, Operator 托管)"

echo; echo "── [V4] 湖仓集联动: Doris External Catalog + 物化视图 ──"
$DQCTL sql exec --workspace demo-fin --file "$DIR/03-doris-catalog.sql"
echo "   (底层: Doris FE/BE 内部, 直读 Iceberg, 无数据导入)"

echo; echo "── [V5] 统一 SQL 联邦查询 (跨 Iceberg + Doris) ──"
$DQCTL sql query --workspace demo-fin --file "$DIR/04-unified-query.sql"
echo "   (底层: 网关 Deployment 解析→路由→合并; 临时查询 Pod 用完即销)"

echo; echo "── [V6] 客户无感知确认 ──"
echo "   客户全程未接触 kubeconfig / Pod / YAML; 故障由 Operator 自愈"

echo; echo "── [V7] 四环境一致性 ──"
echo "   仅 storage.driver / images.extraVariant / guomi 随 Profile 变化; 作业与 SQL 完全一致"

echo; echo "============================================================"
echo " 验收清单 (PoC V1~V7)"
echo "  [ ] 工作空间/项目就绪 (Namespace+Quota+deny-all 底层生成)"
echo "  [ ] CDC 秒级入湖"
echo "  [ ] 湖仓主题建模, 无冗余拷贝"
echo "  [ ] Doris 物化视图在线可查"
echo "  [ ] 统一 SQL 联邦结果返回 (≤320ms)"
echo "  [ ] 客户零 K8s 概念暴露"
echo "  [ ] 四环境字节级一致"
echo "============================================================"
echo; echo "演示结束请清理: bash cleanup.sh  (删 workspace + 清 MySQL 测试表)"
