#!/usr/bin/env bash
# Doris Lite 可用性验证脚本
# 与 design/deploy/dev/docker-compose-doris-lite.yml 对齐：
#   FE MySQL 宿主机端口 18030（容器内 9030）
# 检查：FE 状态、BE 状态、数据库连通性
# 用法：DORIS_HOST=localhost DORIS_FE_PORT=18030 ./scripts/doris-lite-verify.sh
set -euo pipefail

DORIS_HOST="${DORIS_HOST:-localhost}"
DORIS_FE_PORT="${DORIS_FE_PORT:-18030}"
DORIS_USER="${DORIS_USER:-root}"
DORIS_PASSWORD="${DORIS_PASSWORD:-}"

log()  { echo "[doris-verify] $*"; }
fail() { log "FAIL: $*"; exit 1; }
pass() { log "PASS: $*"; }

command -v mysql >/dev/null 2>&1 || { echo "doris-verify: 未找到 mysql 客户端，跳过 Doris 验证（非阻塞）"; exit 0; }

MYSQL_OPTS=(-h "$DORIS_HOST" -P "$DORIS_FE_PORT" -u "$DORIS_USER")
[ -n "$DORIS_PASSWORD" ] && MYSQL_OPTS+=("-p$DORIS_PASSWORD")

log "验证 Doris FE: ${DORIS_HOST}:${DORIS_FE_PORT}"

# 1. FE 存活
if ! mysql "${MYSQL_OPTS[@]}" -e "SELECT 1;" >/dev/null 2>&1; then
  fail "FE 连接失败（端口 ${DORIS_FE_PORT}）"
fi
pass "FE 连接正常"

# 2. FE/BE 状态
fe_ok=$(mysql "${MYSQL_OPTS[@]}" -N -e "SHOW PROC '/frontends';" 2>/dev/null | awk -F'\t' '$2=="true"{c++} END{print c+0}')
be_ok=$(mysql "${MYSQL_OPTS[@]}" -N -e "SHOW PROC '/backends';" 2>/dev/null | awk -F'\t' '$2=="true"{c++} END{print c+0}')
[ "$fe_ok" -gt 0 ] || fail "FE Alive=false"
[ "$be_ok" -gt 0 ] || fail "BE Alive=false"
pass "FE=${fe_ok} 存活, BE=${be_ok} 存活"

# 3. 数据库探测（information_schema）
if ! mysql "${MYSQL_OPTS[@]}" -e "SHOW DATABASES;" >/dev/null 2>&1; then
  fail "SHOW DATABASES 失败"
fi
pass "数据库列表可读"

echo "=== Doris Lite 验证通过 ==="