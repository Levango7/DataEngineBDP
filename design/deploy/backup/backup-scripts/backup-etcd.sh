#!/bin/bash
# ============================================================================
# etcd 备份脚本 - 数据引擎大数据平台
# 关联：design/deploy/backup/backup-strategy.md §3.4
# 用途：每小时 etcd 快照 + 加密上传对象存储 + 完整性验证
# 执行：K8s CronJob 每小时 00 分
# 依赖：etcdctl、gzip、openssl、mc（MinIO Client）
# 出口码：0=成功 1=参数错误 2=快照失败 3=加密失败 4=上传失败 5=验证失败
# ============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# 配置（环境变量覆盖）
# ---------------------------------------------------------------------------
ETCD_ENDPOINTS="${ETCD_ENDPOINTS:-https://etcd.kube-system.svc.cluster.local:2379}"
ETCD_CACERT="${ETCD_CACERT:-/etc/etcd/ca.crt}"
ETCD_CERT="${ETCD_CERT:-/etc/etcd/peer.crt}"
ETCD_KEY="${ETCD_KEY:-/etc/etcd/peer.key}"
BACKUP_DIR="${BACKUP_DIR:-/tmp/backup}"
RETENTION_HOURS="${RETENTION_HOURS:-168}"  # 7 天 = 168 小时

# 对象存储配置
S3_ENDPOINT="${S3_ENDPOINT:-http://minio.sq-engine.svc.cluster.local:9000}"
S3_BUCKET="${S3_BUCKET:-shuqing-backup}"
S3_PATH="${S3_PATH:-etcd}"
MC_ALIAS="${MC_ALIAS:-backup-target}"

# 加密配置
ENCRYPT_ALGO="${ENCRYPT_ALGO:-aes-256-cbc}"  # 信创: sm4-cbc
ENCRYPT_KEY_FILE="${ENCRYPT_KEY_FILE:-/etc/backup/encryption-key}"

# 监控指标输出
METRICS_FILE="${METRICS_FILE:-/tmp/node-exporter/backup_etcd.prom}"

# ---------------------------------------------------------------------------
# 日志函数
# ---------------------------------------------------------------------------
log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >&2
}

# ---------------------------------------------------------------------------
# 监控指标输出
# ---------------------------------------------------------------------------
emit_metrics() {
  local status="$1" duration="$2" size="$3"
  mkdir -p "$(dirname "$METRICS_FILE")"
  cat > "$METRICS_FILE" <<EOF
# HELP backup_etcd_success etcd backup success (1=success, 0=failure)
# TYPE backup_etcd_success gauge
backup_etcd_success ${status}
# HELP backup_etcd_duration_seconds etcd backup duration in seconds
# TYPE backup_etcd_duration_seconds gauge
backup_etcd_duration_seconds ${duration}
# HELP backup_etcd_size_bytes etcd backup size in bytes
# TYPE backup_etcd_size_bytes gauge
backup_etcd_size_bytes ${size}
# HELP backup_etcd_last_success_timestamp_seconds Unix timestamp of last successful etcd backup
# TYPE backup_etcd_last_success_timestamp_seconds gauge
backup_etcd_last_success_timestamp_seconds $(date +%s)
EOF
}

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
preflight() {
  log "前置检查..."
  command -v etcdctl >/dev/null 2>&1 || { log "ERROR: etcdctl 未安装"; exit 1; }
  command -v openssl >/dev/null 2>&1 || { log "ERROR: openssl 未安装"; exit 1; }
  command -v mc >/dev/null 2>&1 || { log "ERROR: mc (MinIO Client) 未安装"; exit 1; }
  [[ -f "$ENCRYPT_KEY_FILE" ]] || { log "ERROR: 加密密钥文件不存在: $ENCRYPT_KEY_FILE"; exit 1; }
  [[ -f "$ETCD_CACERT" ]] || { log "ERROR: etcd CA 证书不存在: $ETCD_CACERT"; exit 1; }

  mkdir -p "$BACKUP_DIR"
  mc alias set "$MC_ALIAS" "$S3_ENDPOINT" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" --api S3v4
  log "前置检查通过"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
main() {
  local start_time; start_time=$(date +%s)
  local ts; ts=$(date '+%Y%m%d-%H%M%S')
  local snapshot_file="$BACKUP_DIR/etcd-${ts}.snapshot"
  local gzip_file="$BACKUP_DIR/etcd-${ts}.snapshot.gz"
  local enc_file="$BACKUP_DIR/etcd-${ts}.snapshot.gz.enc"
  local s3_object="$S3_PATH/etcd-${ts}.snapshot.gz.enc"

  preflight

  log "开始 etcd 快照..."

  # 1. etcdctl snapshot save
  if ! ETCDCTL_API=3 etcdctl \
    --endpoints="$ETCD_ENDPOINTS" \
    --cacert="$ETCD_CACERT" \
    --cert="$ETCD_CERT" \
    --key="$ETCD_KEY" \
    snapshot save "$snapshot_file" >/dev/null 2>&1; then
    log "ERROR: etcd 快照失败"
    emit_metrics 0 0 0
    exit 2
  fi

  # 2. 验证快照完整性
  if ! ETCDCTL_API=3 etcdctl snapshot status "$snapshot_file" >/dev/null 2>&1; then
    log "ERROR: etcd 快照完整性验证失败"
    rm -f "$snapshot_file"
    emit_metrics 0 0 0
    exit 5
  fi
  log "快照完整性验证通过"

  # 3. gzip 压缩
  gzip -c "$snapshot_file" > "$gzip_file"
  rm -f "$snapshot_file"

  # 4. 加密
  if ! openssl enc -"$ENCRYPT_ALGO" -salt -pbkdf2 \
    -in "$gzip_file" \
    -out "$enc_file" \
    -pass file:"$ENCRYPT_KEY_FILE"; then
    log "ERROR: 加密失败"
    rm -f "$gzip_file"
    emit_metrics 0 0 0
    exit 3
  fi
  rm -f "$gzip_file"

  # 5. 上传对象存储
  if ! mc cp "$enc_file" "$MC_ALIAS/$S3_BUCKET/$s3_object" >/dev/null 2>&1; then
    log "ERROR: 上传到对象存储失败"
    rm -f "$enc_file"
    emit_metrics 0 0 0
    exit 4
  fi

  local size; size=$(stat -c%s "$enc_file" 2>/dev/null || stat -f%z "$enc_file")
  rm -f "$enc_file"

  # 6. 清理过期备份
  log "清理超过 ${RETENTION_HOURS} 小时的备份..."
  mc find "$MC_ALIAS/$S3_BUCKET/$S3_PATH/" \
    --older-than "${RETENTION_HOURS}h" \
    --exec "mc rm {}" 2>/dev/null || true

  local end_time; end_time=$(date +%s)
  local duration=$((end_time - start_time))

  log "etcd 备份成功: $s3_object ($size bytes, ${duration}s)"
  emit_metrics 1 "$duration" "$size"
  exit 0
}

main "$@"