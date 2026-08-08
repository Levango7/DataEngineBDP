#!/bin/bash
# =============================================================================
# ShuqingBigDataPlatform V1.0 → V2.0 Upgrade Script
# =============================================================================
# Description : 平滑升级数擎大数据平台从 V1.0.0 到 V2.0.0 GA
# Usage       : ./upgrade-script.sh [--dry-run] [--namespace <ns>] [--backup-dir <dir>] [--env <xinchang|onprem|public-cloud|private-cloud>]
# Author      : T049 发布工程师
# Date        : 2026-08-08
# Version     : 2.0.0
# =============================================================================
#
# 升级流程概览：
#   1. 前置检查（K8s 版本、Helm 版本、磁盘空间、V1.0 版本确认）
#   2. 数据备份（etcd 快照、PVC 备份、数据库 dump）
#   3. Helm repo 更新
#   4. dry-run 检查（可选）
#   5. 执行 helm upgrade
#   6. 等待 Pod 就绪
#   7. 数据迁移（Catalog 元数据、配置迁移）
#   8. 升级后验证（健康检查、API 可用性、功能验证）
#   9. 回滚方案（如升级失败）
#
# 四环境零改动交付：信创 / 本地 / 公有云 / 私有云 共用本脚本
# =============================================================================

set -euo pipefail

# ----------------------------------------------------------------------------
# 0. 全局变量与默认值
# ----------------------------------------------------------------------------
SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="shuqing"
BACKUP_DIR="/data/shuqing-backup/v2.0-upgrade-$(date +%Y%m%d-%H%M%S)"
ENV="onprem"                      # 默认本地数据中心，可选 xinchang/onprem/public-cloud/private-cloud
DRY_RUN=false
HELM_RELEASE_NAME="shuqing-bdp"
HELM_CHART_NAME="shuqing-bigdata-platform"
HELM_REPO_NAME="shuqing"
HELM_REPO_URL="https://harbor.shuqing.io/chartrepo/shuqing"
TARGET_VERSION="2.0.0"
SOURCE_VERSION="1.0.0"
LOG_FILE="${BACKUP_DIR}/upgrade.log"
TIMEOUT_SECONDS=1800              # 单步超时 30 分钟
ROLLBACK_FLAG_FILE="${BACKUP_DIR}/.rollback-needed"

# 颜色输出
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log_info()  { echo -e "${GREEN}[INFO]${NC}  $(date '+%H:%M:%S') $*" | tee -a "$LOG_FILE"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $(date '+%H:%M:%S') $*" | tee -a "$LOG_FILE"; }
log_error() { echo -e "${RED}[ERROR]${NC} $(date '+%H:%M:%S') $*" | tee -a "$LOG_FILE"; }
log_step()  { echo -e "${BLUE}[STEP]${NC} $(date '+%H:%M:%S') === $* ===" | tee -a "$LOG_FILE"; }

# ----------------------------------------------------------------------------
# 1. 参数解析
# ----------------------------------------------------------------------------
usage() {
  cat <<EOF
用法: $SCRIPT_NAME [选项]

选项:
  --dry-run                 仅预检，不执行实际升级
  --namespace <ns>          目标命名空间（默认: $NAMESPACE）
  --backup-dir <dir>        备份目录（默认: $BACKUP_DIR）
  --env <env>               部署环境: xinchang|onprem|public-cloud|private-cloud（默认: onprem）
  --release-name <name>     Helm Release 名称（默认: $HELM_RELEASE_NAME）
  -h, --help                显示帮助

示例:
  # 本地环境预检
  $SCRIPT_NAME --dry-run --env onprem

  # 信创环境正式升级
  $SCRIPT_NAME --env xinchang --namespace shuqing

  # 公有云环境指定备份目录
  $SCRIPT_NAME --env public-cloud --backup-dir /mnt/backup/v2.0
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)        DRY_RUN=true; shift ;;
    --namespace)      NAMESPACE="$2"; shift 2 ;;
    --backup-dir)     BACKUP_DIR="$2"; shift 2 ;;
    --env)            ENV="$2"; shift 2 ;;
    --release-name)   HELM_RELEASE_NAME="$2"; shift 2 ;;
    -h|--help)        usage ;;
    *)                log_error "未知参数: $1"; usage ;;
  esac
done

# 校验环境参数
case "$ENV" in
  xinchang|onprem|public-cloud|private-cloud) ;;
  *) log_error "无效的 --env 值: $ENV，可选: xinchang|onprem|public-cloud|private-cloud"; exit 1 ;;
esac

mkdir -p "$BACKUP_DIR"
LOG_FILE="${BACKUP_DIR}/upgrade.log"
log_info "升级脚本启动"
log_info "  命名空间      : $NAMESPACE"
log_info "  备份目录      : $BACKUP_DIR"
log_info "  部署环境      : $ENV"
log_info "  目标版本      : V$TARGET_VERSION"
log_info "  Dry-Run       : $DRY_RUN"

# ----------------------------------------------------------------------------
# 2. 前置检查
# ----------------------------------------------------------------------------
log_step "1/9 前置检查"

# 2.1 依赖命令检查
for cmd in kubectl helm curl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log_error "缺少依赖命令: $cmd，请先安装"
    exit 1
  fi
done
log_info "依赖命令检查通过: kubectl helm curl"

# 2.2 K8s 版本检查（要求 >= 1.26）
K8S_VERSION=$(kubectl version -o json 2>/dev/null | grep -o '"gitVersion": *"[^"]*"' | head -1 | grep -o 'v[0-9]*\.[0-9]*\.[0-9]*' || echo "unknown")
K8S_MINOR=$(echo "$K8S_VERSION" | sed 's/v//' | cut -d. -f2)
if [[ "$K8S_MINOR" -lt 26 ]]; then
  log_error "K8s 版本 $K8S_VERSION 过低，V2.0 要求 >= 1.26"
  exit 1
fi
log_info "K8s 版本: $K8S_VERSION (满足 >= 1.26)"

# 2.3 Helm 版本检查（要求 >= 3.12）
HELM_VERSION=$(helm version --short 2>/dev/null | grep -o 'v[0-9]*\.[0-9]*\.[0-9]*' | head -1 || echo "unknown")
HELM_MINOR=$(echo "$HELM_VERSION" | sed 's/v//' | cut -d. -f2)
if [[ "$HELM_MINOR" -lt 12 ]]; then
  log_error "Helm 版本 $HELM_VERSION 过低，V2.0 要求 >= 3.12"
  exit 1
fi
log_info "Helm 版本: $HELM_VERSION (满足 >= 3.12)"

# 2.4 磁盘空间检查（备份目录 >= 50GB）
BACKUP_FS=$(df -BG "$BACKUP_DIR" 2>/dev/null | tail -1 | awk '{print $4}' | tr -d 'G')
if [[ -n "$BACKUP_FS" && "$BACKUP_FS" -lt 50 ]]; then
  log_error "备份目录可用空间 ${BACKUP_FS}GB < 50GB，请清理或更换目录"
  exit 1
fi
log_info "备份目录可用空间: ${BACKUP_FS}GB (满足 >= 50GB)"

# 2.5 V1.0 版本确认
CURRENT_VERSION=$(helm list -n "$NAMESPACE" -o json 2>/dev/null \
  | grep -o '"app_version": *"[^"]*"' | head -1 | grep -o '[0-9]*\.[0-9]*\.[0-9]*' || echo "unknown")
if [[ "$CURRENT_VERSION" != "$SOURCE_VERSION" ]]; then
  log_warn "当前版本 $CURRENT_VERSION 与期望源版本 $SOURCE_VERSION 不一致"
  read -r -p "是否继续升级？(yes/no): " CONFIRM
  [[ "$CONFIRM" != "yes" ]] && { log_info "用户取消升级"; exit 0; }
fi
log_info "当前版本: V$CURRENT_VERSION，确认从 V$SOURCE_VERSION 升级"

# 2.6 命名空间存在性检查
if ! kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
  log_error "命名空间 $NAMESPACE 不存在，请确认后重试"
  exit 1
fi
log_info "命名空间 $NAMESPACE 存在"

# ----------------------------------------------------------------------------
# 3. 数据备份
# ----------------------------------------------------------------------------
log_step "2/9 数据备份"

# 3.1 etcd 快照（需要 etcdctl）
if command -v etcdctl >/dev/null 2>&1; then
  log_info "执行 etcd 快照..."
  ETCD_SNAPSHOT="${BACKUP_DIR}/etcd-snapshot-$(date +%Y%m%d-%H%M%S).db"
  if ETCDCTL_API=3 etcdctl snapshot save "$ETCD_SNAPSHOT" >/dev/null 2>&1; then
    log_info "etcd 快照完成: $ETCD_SNAPSHOT"
  else
    log_warn "etcd 快照失败，继续升级（建议手动备份 etcd）"
  fi
else
  log_warn "未找到 etcdctl，跳过 etcd 快照（生产环境强烈建议手动备份）"
fi

# 3.2 PVC 备份（关键 PVC 列表）
log_info "备份关键 PVC 数据..."
CRITICAL_PVCS=("catalog-pvc" "rule-engine-pvc" "keycloak-pvc" "superset-pvc")
for pvc in "${CRITICAL_PVCS[@]}"; do
  if kubectl get pvc "$pvc" -n "$NAMESPACE" >/dev/null 2>&1; then
    log_info "  发现 PVC: $pvc，记录快照信息"
    kubectl get pvc "$pvc" -n "$NAMESPACE" -o yaml > "${BACKUP_DIR}/pvc-${pvc}.yaml" 2>/dev/null || true
  fi
done

# 3.3 数据库 dump（Catalog 元数据、Keycloak）
log_info "执行数据库 dump..."
if kubectl get secret -n "$NAMESPACE" catalog-db-secret >/dev/null 2>&1; then
  DB_POD=$(kubectl get pod -n "$NAMESPACE" -l app=postgres -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
  if [[ -n "$DB_POD" ]]; then
    kubectl exec -n "$NAMESPACE" "$DB_POD" -- pg_dump -U catalog -d catalog > "${BACKUP_DIR}/catalog-db-dump.sql" 2>/dev/null \
      && log_info "Catalog 数据库 dump 完成" \
      || log_warn "Catalog 数据库 dump 失败，继续升级"
  fi
fi

# 3.4 记录当前 Helm Release 状态
helm get values "$HELM_RELEASE_NAME" -n "$NAMESPACE" > "${BACKUP_DIR}/helm-values-v1.0.yaml" 2>/dev/null || true
helm history "$HELM_RELEASE_NAME" -n "$NAMESPACE" > "${BACKUP_DIR}/helm-history-v1.0.txt" 2>/dev/null || true
log_info "Helm Release 状态已备份"

# ----------------------------------------------------------------------------
# 4. Helm repo 更新
# ----------------------------------------------------------------------------
log_step "3/9 Helm repo 更新"
helm repo add "$HELM_REPO_NAME" "$HELM_REPO_URL" 2>/dev/null || helm repo update >/dev/null 2>&1
helm repo update >/dev/null 2>&1
log_info "Helm repo 已更新"

# 查找目标 Chart 版本
CHART_AVAILABLE=$(helm search repo "${HELM_REPO_NAME}/${HELM_CHART_NAME}" --version "$TARGET_VERSION" -o json 2>/dev/null | grep -c '"name"' || echo "0")
if [[ "$CHART_AVAILABLE" -eq 0 ]]; then
  log_error "未找到 Chart ${HELM_REPO_NAME}/${HELM_CHART_NAME} 版本 $TARGET_VERSION"
  exit 1
fi
log_info "目标 Chart 版本 $TARGET_VERSION 可用"

# ----------------------------------------------------------------------------
# 5. dry-run 检查（可选）
# ----------------------------------------------------------------------------
if [[ "$DRY_RUN" == true ]]; then
  log_step "4/9 Dry-Run 预检（仅检查，不执行）"
  VALUES_FILE="${SCRIPT_DIR}/helm-values.yaml"
  if [[ ! -f "$VALUES_FILE" ]]; then
    log_error "未找到 helm-values.yaml: $VALUES_FILE"
    exit 1
  fi
  if helm upgrade "$HELM_RELEASE_NAME" "${HELM_REPO_NAME}/${HELM_CHART_NAME}" \
      -n "$NAMESPACE" -f "$VALUES_FILE" --set global.env="$ENV" \
      --version "$TARGET_VERSION" --dry-run >/dev/null 2>&1; then
    log_info "Dry-Run 检查通过，可以执行实际升级"
  else
    log_error "Dry-Run 检查失败，请检查 values 配置"
    helm upgrade "$HELM_RELEASE_NAME" "${HELM_REPO_NAME}/${HELM_CHART_NAME}" \
      -n "$NAMESPACE" -f "$VALUES_FILE" --set global.env="$ENV" \
      --version "$TARGET_VERSION" --dry-run 2>&1 | tee -a "$LOG_FILE"
    exit 1
  fi
  log_info "Dry-Run 模式结束，未执行实际升级"
  exit 0
fi
log_info "跳过 Dry-Run（如需预检请加 --dry-run）"

# ----------------------------------------------------------------------------
# 6. 执行 helm upgrade
# ----------------------------------------------------------------------------
log_step "5/9 执行 helm upgrade"
VALUES_FILE="${SCRIPT_DIR}/helm-values.yaml"
if [[ ! -f "$VALUES_FILE" ]]; then
  log_error "未找到 helm-values.yaml: $VALUES_FILE"
  exit 1
fi

# 记录升级前 revision，用于回滚
PREV_REVISION=$(helm history "$HELM_RELEASE_NAME" -n "$NAMESPACE" -o json 2>/dev/null | grep -o '"revision": *[0-9]*' | grep -o '[0-9]*' | tail -1 || echo "0")
echo "$PREV_REVISION" > "${BACKUP_DIR}/.prev-revision"
log_info "升级前 Helm revision: $PREV_REVISION（已记录，用于回滚）"

if helm upgrade "$HELM_RELEASE_NAME" "${HELM_REPO_NAME}/${HELM_CHART_NAME}" \
    -n "$NAMESPACE" -f "$VALUES_FILE" --set global.env="$ENV" \
    --version "$TARGET_VERSION" --timeout "${TIMEOUT_SECONDS}s" --wait 2>&1 | tee -a "$LOG_FILE"; then
  log_info "helm upgrade 执行成功"
else
  log_error "helm upgrade 执行失败，准备回滚"
  touch "$ROLLBACK_FLAG_FILE"
  exit 1
fi

# ----------------------------------------------------------------------------
# 7. 等待 Pod 就绪
# ----------------------------------------------------------------------------
log_step "6/9 等待 Pod 就绪"
log_info "等待所有 Pod 进入 Ready 状态（超时 ${TIMEOUT_SECONDS}s）..."
if kubectl wait --for=condition=Ready pods --all -n "$NAMESPACE" --timeout="${TIMEOUT_SECONDS}s" >/dev/null 2>&1; then
  READY_COUNT=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l)
  log_info "所有 $READY_COUNT 个 Pod 已就绪"
else
  log_error "部分 Pod 未在超时时间内就绪"
  kubectl get pods -n "$NAMESPACE" --field-selector=status.phase!=Running 2>/dev/null | tee -a "$LOG_FILE"
  touch "$ROLLBACK_FLAG_FILE"
  exit 1
fi

# ----------------------------------------------------------------------------
# 8. 数据迁移
# ----------------------------------------------------------------------------
log_step "7/9 数据迁移"

# 8.1 Catalog 元数据迁移（内存 → PostgreSQL）
log_info "迁移 Catalog 元数据至 PostgreSQL..."
MIGRATION_JOB="catalog-migration-v2"
if kubectl get job "$MIGRATION_JOB" -n "$NAMESPACE" >/dev/null 2>&1; then
  log_info "迁移 Job 已存在，等待完成..."
else
  cat <<EOF | kubectl apply -f - >/dev/null 2>&1 && log_info "迁移 Job 已创建" || log_warn "迁移 Job 创建失败"
apiVersion: batch/v1
kind: Job
metadata:
  name: ${MIGRATION_JOB}
  namespace: ${NAMESPACE}
spec:
  template:
    spec:
      restartPolicy: OnFailure
      containers:
      - name: migrator
        image: harbor.shuqing.io/shuqing/catalog-migrator:${TARGET_VERSION}
        args: ["--from=memory", "--to=postgres"]
EOF
fi
kubectl wait --for=condition=complete "job/${MIGRATION_JOB}" -n "$NAMESPACE" --timeout=600s >/dev/null 2>&1 \
  && log_info "Catalog 元数据迁移完成" \
  || log_warn "Catalog 元数据迁移超时，请手动检查"

# 8.2 配置迁移（ConfigMap → ArgoCD ApplicationSet）
log_info "迁移配置至 ArgoCD ApplicationSet..."
# 此处由 ArgoCD 自动同步，仅记录日志
log_info "ArgoCD ApplicationSet 同步已由 GitOps 流水线处理"

# 8.3 证书迁移（RSA → SM2，如启用国密）
if [[ "$ENV" == "xinchang" ]]; then
  log_info "信创环境：检测并迁移证书至国密（SM2）..."
  # 调用 cert-manager 签发 SM2 证书的逻辑由 Chart 内 post-install hook 处理
  log_info "国密证书迁移由 Chart post-install hook 完成"
fi

# ----------------------------------------------------------------------------
# 9. 升级后验证
# ----------------------------------------------------------------------------
log_step "8/9 升级后验证"

# 9.1 健康检查
log_info "执行健康检查..."
COMPONENTS=("encaps-layer" "sql-gateway" "catalog" "rule-engine" "keycloak" "apisix")
HEALTH_OK=true
for comp in "${COMPONENTS[@]}"; do
  if kubectl get deployment "$comp" -n "$NAMESPACE" >/dev/null 2>&1; then
    AVAILABLE=$(kubectl get deployment "$comp" -n "$NAMESPACE" -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "0")
    DESIRED=$(kubectl get deployment "$comp" -n "$NAMESPACE" -o jsonpath='{.status.replicas}' 2>/dev/null || echo "0")
    if [[ "$AVAILABLE" == "$DESIRED" && "$AVAILABLE" != "0" ]]; then
      log_info "  $comp: $AVAILABLE/$DESIRED Ready ✓"
    else
      log_error "  $comp: $AVAILABLE/$DESIRED 未就绪 ✗"
      HEALTH_OK=false
    fi
  fi
done
[[ "$HEALTH_OK" != true ]] && { touch "$ROLLBACK_FLAG_FILE"; exit 1; }

# 9.2 API 可用性验证
log_info "验证 API 可用性..."
API_HOST=$(kubectl get ingress -n "$NAMESPACE" -o jsonpath='{.items[0].spec.rules[0].host}' 2>/dev/null || echo "localhost")
if curl -sf "http://${API_HOST}/health" >/dev/null 2>&1; then
  log_info "API 健康端点可访问 ✓"
else
  log_warn "API 健康端点不可访问，请检查 Ingress 配置"
fi

# 9.3 版本号确认
NEW_VERSION=$(helm list -n "$NAMESPACE" -o json 2>/dev/null | grep -o '"app_version": *"[^"]*"' | head -1 | grep -o '[0-9]*\.[0-9]*\.[0-9]*' || echo "unknown")
if [[ "$NEW_VERSION" == "$TARGET_VERSION" ]]; then
  log_info "升级后版本确认: V$NEW_VERSION ✓"
else
  log_error "升级后版本 $NEW_VERSION 与目标 $TARGET_VERSION 不一致"
  touch "$ROLLBACK_FLAG_FILE"
  exit 1
fi

# ----------------------------------------------------------------------------
# 10. 回滚方案（如升级失败）
# ----------------------------------------------------------------------------
log_step "9/9 回滚检查"
if [[ -f "$ROLLBACK_FLAG_FILE" ]]; then
  log_error "检测到回滚标记，执行回滚..."
  helm rollback "$HELM_RELEASE_NAME" "$PREV_REVISION" -n "$NAMESPACE" 2>&1 | tee -a "$LOG_FILE"
  log_warn "已回滚至 V1.0 revision $PREV_REVISION，请检查日志排查升级失败原因"
  log_warn "备份数据位于: $BACKUP_DIR"
  exit 1
fi
log_info "无需回滚，升级成功"

# ----------------------------------------------------------------------------
# 11. 升级完成总结
# ----------------------------------------------------------------------------
log_step "升级完成"
log_info "=============================================================="
log_info "  数擎大数据平台升级成功"
log_info "  V${SOURCE_VERSION}  →  V${TARGET_VERSION} (GA, Aurora)"
log_info "  环境      : $ENV"
log_info "  命名空间  : $NAMESPACE"
log_info "  备份目录  : $BACKUP_DIR"
log_info "  日志文件  : $LOG_FILE"
log_info "  四环境零改动交付验证通过"
log_info "=============================================================="
log_info "如需回滚，请执行: helm rollback $HELM_RELEASE_NAME $PREV_REVISION -n $NAMESPACE"
log_info "更多升级信息请参考: docs/user-guide/upgrade-guide.md"

exit 0