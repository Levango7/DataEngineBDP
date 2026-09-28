#!/usr/bin/env bash
# 生成 Java 模块的 PostgreSQL 基线建表脚本（Flyway V1__baseline.sql）
#
# 为什么要脚本而不是手写 DDL：JPA 实体是表结构的唯一真相源。手写会漏字段、
# 漏类型精度（numeric 精度、timestamp 时区、@Lob 映射成 oid 等），
# 而生产 profile 用 ddl-auto=validate —— 差一个字段就启动失败。
#
# 原理：用 Hibernate 的 schema-generation 脚本导出能力（不连真实库也能产出方言 DDL），
# 数据源指向内存 H2，方言强制 PostgreSQLDialect，只导出脚本不建表。
#
# 用法：
#   bash scripts/gen-db-baseline.sh platform/encaps-layer
#   bash scripts/gen-db-baseline.sh platform/finops/billing
#
# 前置：该模块必须能独立启动 Spring 上下文（多数模块需要 app.k8s.mock-enabled=true）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="${1:?用法: bash scripts/gen-db-baseline.sh <模块目录，如 platform/encaps-layer>}"
MODULE_DIR="$REPO_ROOT/$MODULE"
POM="$MODULE_DIR/pom.xml"

if [[ ! -f "$POM" ]]; then
  echo "ERROR: 未找到 $POM" >&2
  exit 2
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "ERROR: 需要先 export JAVA_HOME 指向 JDK 17（本仓默认 mvn 可能落在 JDK 8 上）" >&2
  exit 2
fi

TMP_SQL="$MODULE_DIR/target/baseline-postgres.sql"
OUT_SQL="$MODULE_DIR/src/main/resources/db/migration/V1__baseline.sql"

# 挑一个真正空闲的端口：随机端口可能与上一个残留实例冲突，
# 冲突时 Spring Boot 会以 "Port ... was already in use" 失败并留下空 DDL
pick_free_port() {
  for _ in $(seq 1 20); do
    local p=$((18000 + RANDOM % 800))
    if ! netstat -ano 2>/dev/null | grep -q "TCP.*:$p " ; then
      echo "$p"
      return 0
    fi
  done
  echo "ERROR: 找不到空闲端口（18000-18799）" >&2
  return 1
}

PORT=$(pick_free_port) || exit 1

echo "==> 生成 $MODULE 的 PostgreSQL DDL"
rm -f "$TMP_SQL"

# 用 jvmArguments（-D 系统属性）而非 run.arguments（程序参数）：
# 实测 run.arguments 形式下应用能启动但 schema-generation 脚本不产出
# （H2 URL 里的分号与空值参数在程序参数解析中被吞掉），jvmArguments 稳定可用。
JVM_ARGS="-Dserver.port=$PORT"
JVM_ARGS="$JVM_ARGS -Dspring.datasource.url=jdbc:h2:mem:gen;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
JVM_ARGS="$JVM_ARGS -Dspring.datasource.driver-class-name=org.h2.Driver"
JVM_ARGS="$JVM_ARGS -Dspring.jpa.hibernate.ddl-auto=none"
JVM_ARGS="$JVM_ARGS -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
JVM_ARGS="$JVM_ARGS -Dspring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false"
JVM_ARGS="$JVM_ARGS -Dspring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create"
# 必须用相对路径：fork 出的应用工作目录就是模块 basedir，而 Git Bash 的绝对路径
# （/f/Nexus/...）Windows JVM 解析不了，会静默不产出文件（实测踩过）
JVM_ARGS="$JVM_ARGS -Dspring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/baseline-postgres.sql"
JVM_ARGS="$JVM_ARGS -Dapp.security.jwt.secret=baseline-generation-only-secret-key-at-least-256-bits"
JVM_ARGS="$JVM_ARGS -Dapp.k8s.mock-enabled=true"
JVM_ARGS="$JVM_ARGS -Dspring.flyway.enabled=false"

# 后台启动 → 等待 DDL 文件出现 → 结束进程（上下文启动即导出脚本，无需等就绪探针）
timeout 300 mvn -o -q -f "$POM" spring-boot:run \
  -Dspring-boot.run.jvmArguments="$JVM_ARGS" \
  > "$MODULE_DIR/target/gen-db-baseline.log" 2>&1 &
RUN_PID=$!

for _ in $(seq 1 90); do
  if [[ -f "$TMP_SQL" ]]; then break; fi
  if ! kill -0 "$RUN_PID" 2>/dev/null; then break; fi
  sleep 2
done

kill "$RUN_PID" 2>/dev/null || true
pkill -f "spring-boot:run" 2>/dev/null || true
sleep 1

if [[ ! -f "$TMP_SQL" ]]; then
  echo "FAIL: 未生成 DDL，见 $MODULE_DIR/target/gen-db-baseline.log" >&2
  tail -25 "$MODULE_DIR/target/gen-db-baseline.log" >&2 || true
  exit 1
fi

mkdir -p "$(dirname "$OUT_SQL")"
{
  echo "-- Flyway 基线迁移：$(basename "$MODULE")"
  echo "-- 由 Hibernate 按 PostgreSQL 方言生成（jakarta.persistence.schema-generation.scripts.action=create），"
  echo "-- 字段与 JPA 实体一一对应，非手写。重新生成：bash scripts/gen-db-baseline.sh $MODULE"
  echo "-- 请勿手工编辑本文件；后续结构变更请新增 V2__xxx.sql（Flyway 校验已应用脚本的 checksum）。"
  echo ""
  cat "$TMP_SQL"
} > "$OUT_SQL"

TABLES=$(grep -c "^create table" "$OUT_SQL" || true)
echo "OK: 已生成 $OUT_SQL（${TABLES} 张表）"
echo "提示：还需在该模块 application-prod.yml 启用 spring.flyway（enabled/schemas/create-schemas）"
