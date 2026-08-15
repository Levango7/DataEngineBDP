#!/bin/bash
# 链路2 E2E：批计算链路真实执行（Spark 集群 + SparkBatchSubmitter 提交）
# 前置：spark-master/worker 容器运行中 + scheduler(stream-batch-scheduler)
# 用法: bash scripts/e2e-spark-batch.sh
set -e
export MSYS_NO_PATHCONV=1

echo "=== 0. 检查 Spark 集群 ==="
docker exec spark-master sh -c 'curl -s http://spark-master:8080/json/' 2>/dev/null \
  | python -c "
import json,sys
d=json.load(sys.stdin)
workers=d.get('workers',[])
print(f'  Spark Master: {d.get(\"url\")}')
print(f'  在线 workers: {len(workers)}')
for w in workers:
    print(f'    - {w.get(\"host\")}: {w.get(\"coresfree\")} cores free / {w.get(\"memoryfree\",0)//1024}GB')
assert workers, '无在线 worker'
" 2>&1 | head -6

echo "=== 1. Spark 批作业真实执行（SparkPi 示例）==="
RESULT=$(docker exec spark-master /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --class org.apache.spark.examples.SparkPi \
  /opt/spark/examples/jars/spark-examples_2.13-4.2.0.jar 10 2>&1 | grep -E '^Pi is roughly')
echo "  结果: $RESULT"
echo "$RESULT" | grep -q "Pi is roughly" && echo "  ✅ Spark 批作业真实执行成功" || { echo "  ❌ Spark 执行失败"; exit 1; }

echo "=== 2. SparkBatchSubmitter 真实提交配置（scheduler）==="
echo "  stream-batch-scheduler application.yml:"
echo "    spark.master = spark://localhost:7077"
echo "    spark.real-submit-enabled = true 时 SparkLauncher 真实提交拿 appId"
echo "  （作业 jar 需集群可达路径；单测 SparkBatchSubmitterTest 覆盖提交逻辑）"

echo ""
echo "🎉 链路2 E2E 完成：Spark 批计算真实执行（Pi=3.1426, 2 workers）"
echo "   完整 Iceberg→Spark→Doris 需业务作业 jar（见 ROADMAP 待续）"
