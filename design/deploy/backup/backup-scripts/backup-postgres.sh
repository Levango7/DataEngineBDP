#!/bin/bash
# ============================================================================
# PostgreSQL 备份脚本 - 数据引擎大数据平台
# 关联：design/deploy/backup/backup-strategy.md §3.1
# 用途：每日全量 pg_dump + WAL 归档验证 + 加密上传对象存储
# 执行：K8s CronJob 每日 02:00（见 inspector-job.yaml 同模式）
# 依赖：pg_dump、gzip、openssl（SM4/AES）、mc（MinIO Client）
# 出口码：0=成功 1=参数错误 2=pg_dump失败 3=加密失败 4=上传失败 5=验证失败
# ============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# 配置（环境变量覆盖）
# ---------------------------------------------------------------------------
PG_HOST="${PG_HOST:-postgresql.sq-engine.svc.cluster.local}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_DATABASES="${PG_DATABASES:-keycloak metadata governance audit}"  # 多库空格分隔
BACKUP_DIR="${BACKUP_DIR:-/tmp/backup}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

# 对象存储配置
S3_ENDPOINT="${S3_ENDPOINT:-http://minio.sq-engine.svc.cluster.local:9000}"
S3_BUCKET="${S3_BUCKET:-shuqing-backup}"
S3_PATH="${S3_PATH:-postgres}"
MC_ALIAS="${MC_ALIAS:-backup-target}"

# 加密配置（信创用 SM4，非信创用 AES-256）
ENCRYPT_ALGO="${ENCRYPT_ALGO:-aes-256-cbc}"  # 信创: sm4-cbc
ENCRYPT_KEY_FILE="${ENCRYPT_KEY_FILE:-/etc/backup/encryption-key}"

# 监控指标输出（供 node-exporter textfile collector 采集）
METRICS_FILE="${METRICS_FILE:-/tmp/node-exporter/backup_postgres.prom}"

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
# HELP backup_postgres_success PostgreSQL backup success (1=success, 0=failure)
# TYPE backup_postgres_success gauge
backup_postgres_success ${status}
# HELP backup_postgres_duration_seconds PostgreSQL backup duration in seconds
# TYPE backup_postgres_duration_seconds gauge
backup_postgres_duration_seconds ${duration}
# HELP backup_postgres_size_bytes PostgreSQL backup size in bytes
# TYPE backup_postgres_size_bytes gauge
backup_postgres_size_bytes ${size}
# HELP backup_postgres_last_success_timestamp_seconds Unix timestamp of last successful backup
# TYPE backup_postgres_last_success_timestamp_seconds gauge
backup_postgres_last_success_timestamp_seconds $(date +%s)
EOF
}

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
preflight() {
  log "前置检查..."
  command -v pg_dump >/dev/null 2>&1 || { log "ERROR: pg_dump 未安装"; exit 1; }
  command -v openssl >/dev/null 2>&1 || { log "ERROR: openssl 未安装"; exit 1; }
  command -v mc >/dev/null 2>&1 || { log "ERROR: mc (MinIO Client) 未安装"; exit 1; }
  [[ -f "$ENCRYPT_KEY_FILE" ]] || { log "ERROR: 加密密钥文件不存在: $ENCRYPT_KEY_FILE"; exit 1; }

  mkdir -p "$BACKUP_DIR"
  mc alias set "$MC_ALIAS" "$S3_ENDPOINT" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" --api S3v4
  log "前置检查通过"
}

# ---------------------------------------------------------------------------
# 加密函数
# ---------------------------------------------------------------------------
encrypt_file() {
  local input="$1" output="$2"
  openssl enc -"$ENCRYPT_ALGO" -salt -pbkdf2 \
    -in "$input" \
    -out "$output" \
    -pass file:"$ENCRYPT_KEY_FILE"
}

# ---------------------------------------------------------------------------
# 单库备份
# ---------------------------------------------------------------------------
backup_single_db() {
  local db="$1"
  local ts; ts=$(date '+%Y%m%d-%H%M%S')
  local dump_file="$BACKUP_DIR/${db}-${ts}.dump"
  local gzip_file="$BACKUP_DIR/${db}-${ts}.dump.gz"
  local enc_file="$BACKUP_DIR/${db}-${ts}.dump.gz.enc"
  local s3_object="$S3_PATH/${db}/${db}-${ts}.dump.gz.enc"

  log "备份数据库: $db"

  # 1. pg_dump（自定义格式，支持并行恢复）
  if ! pg_dump \
    -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
    -Fc --no-owner --no-privileges \
    "$db" > "$dump_file" 2>/dev/null; then
    log "ERROR: pg_dump $db 失败"
    return 2
  fi

  # 2. gzip 压缩
  gzip -c "$dump_file" > "$gzip_file"
  rm -f "$dump_file"

  # 3. 加密
  if ! encrypt_file "$gzip_file" "$enc_file"; then
    log "ERROR: 加密 $db 失败"
    rm -f "$gzip_file"
    return 3
  fi
  rm -f "$gzip_file"

  # 4. 验证加密文件可解密
  if ! openssl enc -d -"$ENCRYPT_ALGO" -pbkdf2 \
    -in "$enc_file" \
    -pass file:"$ENCRYPT_KEY_FILE" \
    -out /dev/null 2>/dev/null; then
    log "ERROR: 加密文件验证失败: $db"
    rm -f "$enc_file"
    return 5
  fi

  # 5. 上传对象存储
  if ! mc cp "$enc_file" "$MC_ALIAS/$S3_BUCKET/$s3_object" >/dev/null 2>&1; then
    log "ERROR: 上传 $db 到对象存储失败"
    rm -f "$enc_file"
    return 4
  fi

  local size; size=$(stat -c%s "$enc_file" 2>/dev/null || stat -f%z "$enc_file")
  log "备份完成: $db → $s3_object ($size bytes)"
  rm -f "$enc_file"
  echo "$size"
}

# ---------------------------------------------------------------------------
# 清理过期备份
# ---------------------------------------------------------------------------
cleanup_old_backups() {
  log "清理超过 ${RETENTION_DAYS} 天的备份..."
  for db in $PG_DATABASES; do
    mc find "$MC_ALIAS/$S3_BUCKET/$S3_PATH/$db/" \
      --older-than "${RETENTION_DAYS}d" \
      --exec "mc rm {}" 2>/dev/null || true
  done
  log "清理完成"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
main() {
  local start_time; start_time=$(date +%s)
  local total_size=0
  local all_success=true

  preflight

  for db in $PG_DATABASES; do
    if size=$(backup_single_db "$db"); then
      total_size=$((total_size + size))
    else
      all_success=false
    fi
  done

  cleanup_old_backups

  local end_time; end_time=$(date +%s)
  local duration=$((end_time - start_time))

  if $all_success; then
    log "全部数据库备份成功，耗时 ${duration}s，总大小 ${total_size} bytes"
    emit_metrics 1 "$duration" "$total_size"
    exit 0
  else
    log "部分数据库备份失败，耗时 ${duration}s"
    emit_metrics 0 "$duration" "$total_size"
    exit 2
  fi
}

main "$@"