#!/usr/bin/env bash
# 数擎云核 SKE · etcd 运行时调优与检查 (ske tune-etcd 调用)
# 关联: manifests/etcd-tuning.yaml (调优配置) / tuning/controlplane.sh (NVMe 挂载)
# 功能:
#   - 检查 etcd 健康状态 (endpoint health + status)
#   - 检查 db 大小 (告警 > 4GiB, 危险 > 6.4GiB)
#   - 执行 defrag (可选 --force, 默认仅检查)
#   - 检查 NVMe 挂载参数 (noatime)
#   - 检查 anti-affinity 是否生效 (etcd Pod 跨节点)
set -uo pipefail

# ---- 参数 ----
FORCE_DEFRAG=false
ETCDCTL_BIN="${ETCDCTL_BIN:-etcdctl}"
ETCD_ENDPOINTS="${ETCD_ENDPOINTS:-https://127.0.0.1:2379}"
ETCD_CACERT="${ETCD_CACERT:-/etc/kubernetes/pki/etcd/ca.crt}"
ETCD_CERT="${ETCD_CERT:-/etc/kubernetes/pki/etcd/peer.crt}"
ETCD_KEY="${ETCD_KEY:-/etc/kubernetes/pki/etcd/peer.key}"
DB_WARN_BYTES=4294967296                    # 4GiB
DB_CRIT_BYTES=6442450944                    # 6GiB
DB_QUOTA_BYTES=8589934592                   # 8GiB

usage() {
  echo "用法: $0 [--force] [--endpoints URL]"
  echo "  --force     执行 defrag (默认仅检查)"
  echo "  --endpoints etcd 端点 (默认 $ETCD_ENDPOINTS)"
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE_DEFRAG=true; shift ;;
    --endpoints) ETCD_ENDPOINTS="$2"; shift 2 ;;
    *) usage ;;
  esac
done

ETCD_COMMON_ARGS=(--endpoints="$ETCD_ENDPOINTS" --cacert="$ETCD_CACERT" --cert="$ETCD_CERT" --key="$ETCD_KEY")

echo "== [SKE] etcd 运行时调优检查 =="

# ---- 1. 检查 etcd 健康状态 ----
echo "-- 1. etcd 健康状态 --"
if command -v "$ETCDCTL_BIN" >/dev/null 2>&1; then
  if "$ETCDCTL_BIN" "${ETCD_COMMON_ARGS[@]}" endpoint health >/dev/null 2>&1; then
    echo "   [OK] etcd endpoint 健康"
  else
    echo "   [FAIL] etcd endpoint 不健康, 请检查 etcd Pod 状态"
    "$ETCDCTL_BIN" "${ETCD_COMMON_ARGS[@]}" endpoint health 2>&1 | sed 's/^/   /'
  fi
  # endpoint status (leader/term/db size)
  echo "   endpoint status:"
  "$ETCDCTL_BIN" "${ETCD_COMMON_ARGS[@]}" endpoint status -w table 2>&1 | sed 's/^/   /'
else
  echo "   [SKIP] etcdctl 未安装, 跳过健康检查"
fi

# ---- 2. 检查 db 大小 ----
echo "-- 2. etcd db 大小检查 --"
if command -v "$ETCDCTL_BIN" >/dev/null 2>&1; then
  DB_SIZE=$("$ETCDCTL_BIN" "${ETCD_COMMON_ARGS[@]}" endpoint status -w json 2>/dev/null \
    | grep -o '"dbSize":[0-9]*' | grep -o '[0-9]*' || echo 0)
  if [ "$DB_SIZE" -gt 0 ]; then
    DB_SIZE_MB=$((DB_SIZE / 1048576))
    DB_PCT=$((DB_SIZE * 100 / DB_QUOTA_BYTES))
    echo "   db 大小: ${DB_SIZE_MB}MiB (${DB_PCT}% of 8GiB quota)"
    if [ "$DB_SIZE" -gt "$DB_CRIT_BYTES" ]; then
      echo "   [CRITICAL] db > 6GiB, 接近 quota! 立即执行 defrag: $0 --force"
    elif [ "$DB_SIZE" -gt "$DB_WARN_BYTES" ]; then
      echo "   [WARN] db > 4GiB, 建议执行 defrag"
    else
      echo "   [OK] db 大小正常"
    fi
  fi
fi

# ---- 3. 执行 defrag (可选) ----
echo "-- 3. etcd defrag --"
if [ "$FORCE_DEFRAG" = "true" ]; then
  if command -v "$ETCDCTL_BIN" >/dev/null 2>&1; then
    echo "   执行 compact..."
    REVISION=$("$ETCDCTL_BIN" "${ETCD_COMMON_ARGS[@]}" endpoint status -w json 2>/dev/null \
      | grep -o '"revision":[0-9]*' | grep -o '[0-9]*' || echo "")
    if [ -n "$REVISION" ]; then
      "$ETCDCTL_BIN" "${ETCD_COMMON_ARGS[@]}" compact "$REVISION" 2>&1 | sed 's/^/   /' && echo "   compact 完成 (rev=$REVISION)"
    fi
    echo "   执行 defrag (可能阻塞 10-60s)..."
    "$ETCDCTL_BIN" "${ETCD_COMMON_ARGS[@]}" --command-timeout=120s defrag 2>&1 | sed 's/^/   /'
    echo "   defrag 完成"
  else
    echo "   [SKIP] etcdctl 未安装"
  fi
else
  echo "   仅检查模式; 执行 defrag 请加 --force"
fi

# ---- 4. 检查 NVMe 挂载参数 ----
echo "-- 4. etcd 数据目录 NVMe 挂载检查 --"
ETCD_DATA_DIR="${ETCD_DATA_DIR:-/var/lib/etcd}"
if [ -d "$ETCD_DATA_DIR" ]; then
  MOUNT_INFO=$(mount | grep "$ETCD_DATA_DIR" || true)
  if [ -n "$MOUNT_INFO" ]; then
    echo "   $ETCD_DATA_DIR 挂载信息: $MOUNT_INFO"
    if echo "$MOUNT_INFO" | grep -q "noatime"; then
      echo "   [OK] noatime 已启用"
    else
      echo "   [WARN] noatime 未启用, 建议挂载项加 noatime,nodiratime"
    fi
    if echo "$MOUNT_INFO" | grep -qE "nvme|NVMe"; then
      echo "   [OK] 挂载在 NVMe 上"
    else
      echo "   [WARN] 未检测到 NVMe, 生产建议 etcd 独占 NVMe"
    fi
  else
    echo "   [WARN] $ETCD_DATA_DIR 未独立挂载, 与系统盘共享影响性能"
  fi
else
  echo "   [SKIP] $ETCD_DATA_DIR 不存在 (可能不是控制面节点)"
fi

# ---- 5. 检查 anti-affinity 是否生效 ----
echo "-- 5. etcd anti-affinity 检查 --"
if command -v kubectl >/dev/null 2>&1; then
  ETCD_NODES=$(kubectl -n kube-system get pods -l component=etcd -o jsonpath='{range .items[*]}{.spec.nodeName}{" "}{end}' 2>/dev/null || echo "")
  if [ -n "$ETCD_NODES" ]; then
    UNIQUE_NODES=$(echo "$ETCD_NODES" | tr ' ' '\n' | sort -u | wc -l)
    TOTAL_PODS=$(echo "$ETCD_NODES" | tr ' ' '\n' | grep -c . || echo 0)
    echo "   etcd Pod 数: $TOTAL_PODS, 分布节点数: $UNIQUE_NODES"
    if [ "$UNIQUE_NODES" -eq "$TOTAL_PODS" ]; then
      echo "   [OK] 每个 etcd Pod 在不同节点 (anti-affinity 生效)"
    else
      echo "   [CRITICAL] 多个 etcd Pod 在同一节点! anti-affinity 未生效, 脑裂风险"
    fi
  else
    echo "   [SKIP] 无法获取 etcd Pod 信息 (kubectl 不可用或非控制面)"
  fi
else
  echo "   [SKIP] kubectl 未安装"
fi

echo "== [SKE] etcd 运行时调优检查完成 =="