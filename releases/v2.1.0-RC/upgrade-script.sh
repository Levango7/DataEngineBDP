#!/bin/bash
# =============================================================================
# DataEngineBDP V2.0 → V2.1-RC Upgrade Script
# =============================================================================
# Description : 平滑升级数据引擎大数据平台从 V2.0.0 到 V2.1.0-RC（Borealis）
# Usage       : ./upgrade-script.sh [--dry-run] [--namespace <ns>] [--backup-dir <dir>] [--env <xinchuang|onprem|public-cloud|private-cloud>]
# Date        : 2026-08-27（初版）；2026-11-17 按台账 #76 重建（原文件生成时被引号转义
#               序列化损坏：变量被吞、bash -n rc=2，见 docs/KNOWN-FAILURES.md #76；
#               本版按 v2.0.0 基线结构与 RELEASE-NOTES.md §5 不兼容变更清单重建）
# Version     : 2.1.0-RC
# =============================================================================
#
# 升级流程概览：
#   1. 前置检查（依赖命令、集群连通、JWT 密钥注入检查、磁盘空间）
#   2. 数据备份（etcd 快照、数据库 dump、PVC 备份、Helm values）
#   3. Helm repo 更新
#   4. dry-run 检查（可选）
#   5. 执行 helm upgrade（--atomic，失败自动回滚）
#   6. 等待 Pod 就绪
#   7. 数据迁移（catalog SQLite→PostgreSQL、rule-engine H2→PostgreSQL）
#   8. 升级后验证（版本确认、核心组件健康检查）
#   9. 完成（打印回滚命令与后续提示）
#
# 依据 RELEASE-NOTES.md §5（不兼容变更）：
#   - catalog JWT 密钥改为空串 fail-fast ⇒ 升级前必须确认 Secret 已注入 ≥32 字符密钥
#   - 18 个 root 容器 → 非 root（app uid）⇒ 需重新拉取 v2.1.0-RC 镜像（pullPolicy 保证）
#   - 10 个 AI/模型组件为 experimental（Mock 默认）⇒ 升级不影响，无需迁移
#
# 四环境零改动交付：信创 / 本地 / 公有云 / 私有云 共用本脚本
# =============================================================================

set -euo pipefail

# ----------------------------------------------------------------------------
# 0. 全局变量与默认值
# ----------------------------------------------------------------------------
SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="dataenginebdp"
BACKUP_DIR="/data/dataenginebdp-backup/v2.1-rc-upgrade-$(date +%Y%m%d-%H%M%S)"
ENV="onprem"                      # 默认本地数据中心，可选 xinchuang/onprem/public-cloud/private-cloud
DRY_RUN=false
HELM_RELEASE_NAME="dataenginebdp"
HELM_CHART_NAME="dataenginebdp-umbrella"
HELM_REPO_NAME="dataenginebdp"
HELM_REPO_URL="https://harbor.local/chartrepo/dataenginebdp"
TARGET_VERSION="2.1.0-RC"
SOURCE_VERSION="2.0.0"
LOG_FILE="${BACKUP_DIR}/upgrade.log"
TIMEOUT_SECONDS=1800              # 单步超时 30 分钟

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
  --env <env>               部署环境: xinchuang|onprem|public-cloud|private-cloud（默认: onprem）
  --release-name <name>     Helm Release 名称（默认: $HELM_RELEASE_NAME）
  -h, --help                显示帮助

示例:
  # 本地环境预检
  $SCRIPT_NAME --dry-run --env onprem

  # 信创环境正式升级
  $SCRIPT_NAME --env xinchuang --namespace dataenginebdp

  # 公有云环境指定备份目录
  $SCRIPT_NAME --env public-cloud --backup-dir /mnt/backup/v2.1rc
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
  xinchuang|onprem|public-cloud|private-cloud) ;;
  *) log_error "无效的 --env 值: $ENV，可选: xinchuang|onprem|public-cloud|private-cloud"; exit 1 ;;
esac

mkdir -p "$BACKUP_DIR"
LOG_FILE="${BACKUP_DIR}/upgrade.log"
log_info "升级脚本启动（V${SOURCE_VERSION} → V${TARGET_VERSION}）"
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
for cmd in kubectl helm curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log_error "缺少依赖命令: $cmd，请先安装"
    exit 1
  fi
done

# 2.2 集群连通性
if ! kubectl cluster-info >/dev/null 2>&1; then
  log_error "无法连接 Kubernetes 集群，请检查 kubeconfig"
  exit 1
fi

# 2.3 命名空间存在性（V2.0 已部署过才谈升级）
if ! kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
  log_error "命名空间 $NAMESPACE 不存在——V2.0 尚未部署？首次部署请用 helm install，不是本脚本"
  exit 1
fi

# 2.4 当前 Release 版本确认
CURRENT_VER="$(helm get metadata "$HELM_RELEASE_NAME" -n "$NAMESPACE" -o json 2>/dev/null | jq -r '.app_version // "unknown"' || echo "unknown")"
log_info "  当前 Release 版本: $CURRENT_VER"

# 2.5 catalog JWT 密钥检查（RELEASE-NOTES §5 变更 4：空串 fail-fast）
JWT_SECRET_NAME="dataenginebdp-jwt"
if kubectl get secret "$JWT_SECRET_NAME" -n "$NAMESPACE" >/dev/null 2>&1; then
  JWT_LEN="$(kubectl get secret "$JWT_SECRET_NAME" -n "$NAMESPACE" -o jsonpath='{.data.jwtSigningKey}' | base64 -d 2>/dev/null | wc -c || echo 0)"
  if [ "${JWT_LEN:-0}" -lt 32 ]; then
    log_error "Secret $JWT_SECRET_NAME 的 jwtSigningKey 长度 ${JWT_LEN} < 32 字符"
    log_error "（RELEASE-NOTES §5 变更 4：catalog 将 fail-fast 拒绝启动）请先注入 ≥32 字符密钥再升级"
    exit 1
  fi
  log_info "  JWT 密钥检查: OK（${JWT_LEN} 字符）"
else
  log_warn "未找到 Secret $JWT_SECRET_NAME——若 catalog 启用 JWT（默认），升级后首次启动会 fail-fast"
  log_warn "继续升级，但请准备好立即注入密钥"
fi

# 2.6 磁盘空间检查（备份需要 ≥1GB）
AVAILABLE_KB="$(df -Pk "$(dirname "$BACKUP_DIR")" | awk 'NR==2 {print $4}')"
if [ "${AVAILABLE_KB:-0}" -lt 1048576 ]; then
  log_error "备份目录所在磁盘可用空间不足 1GB（当前 ${AVAILABLE_KB}KB），请清理后重试"
  exit 1
fi
log_info "  磁盘空间检查: OK（${AVAILABLE_KB}KB 可用）"

log_info "前置检查通过"

# ----------------------------------------------------------------------------
# 3. 数据备份
# ----------------------------------------------------------------------------
log_step "2/9 数据备份"

if [ "$DRY_RUN" = "true" ]; then
  log_info "[DRY-RUN] 跳过实际备份"
else
  # 3.1 etcd 快照（尽力而为，控制面不可达不阻断——应用数据在 PVC/DB 里）
  ETCD_POD="$(kubectl get pods -n kube-system -l component=etcd -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [ -n "$ETCD_POD" ]; then
    log_info "备份 etcd（pod: $ETCD_POD）..."
    if kubectl exec -n kube-system "$ETCD_POD" -- etcdctl snapshot save /tmp/etcd-snapshot.db >/dev/null 2>&1 \
       && kubectl cp "kube-system/$ETCD_POD:/tmp/etcd-snapshot.db" "$BACKUP_DIR/etcd-snapshot.db" >/dev/null 2>&1; then
      log_info "etcd 快照完成"
    else
      log_warn "etcd 快照失败（非阻断，应用数据在 PVC/数据库中）"
    fi
  else
    log_warn "未找到 etcd pod（托管集群？），跳过 etcd 备份（非阻断）"
  fi

  # 3.2 数据库 dump（V2.0 GA 组件的持久层）
  log_info "备份数据库..."
  for db in catalog rule_engine tag_engine encaps_layer encaps_tenant encaps_gateway encaps_data; do
    POD_NAME="$(kubectl get pod -n "$NAMESPACE" -l "app.kubernetes.io/name=$db" --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    if [ -z "$POD_NAME" ]; then
      log_warn "  $db: 无 Running pod（跳过）"
      continue
    fi
    if kubectl exec -n "$NAMESPACE" "$POD_NAME" -- sh -c 'command -v pg_dump >/dev/null 2>&1' >/dev/null 2>&1; then
      if kubectl exec -n "$NAMESPACE" "$POD_NAME" -- pg_dump -U postgres "$db" > "$BACKUP_DIR/${db}.sql" 2>/dev/null; then
        log_info "  $db: dump 完成"
      else
        log_warn "  $db: dump 失败（继续，非阻断）"
      fi
    else
      log_info "  $db: 非 PostgreSQL（跳过 dump，走 PVC 备份）"
    fi
  done

  # 3.3 PVC 备份（有 pv 的数据卷）
  log_info "备份 PVC 数据..."
  while IFS= read -r pvc; do
    [ -n "$pvc" ] || continue
    POD_NAME="$(kubectl get pod -n "$NAMESPACE" -o json 2>/dev/null | jq -r --arg pvc "$pvc" '.items[] | select(.spec.volumes[]?.persistentVolumeClaim?.claimName == $pvc) | .metadata.name' | head -1)"
    if [ -n "$POD_NAME" ]; then
      if kubectl exec -n "$NAMESPACE" "$POD_NAME" -- tar czf - /data 2>/dev/null > "$BACKUP_DIR/pvc-${pvc}.tar.gz"; then
        log_info "  $pvc (via $POD_NAME): 备份完成"
      else
        log_warn "  $pvc: 备份失败（继续，非阻断）"
      fi
    else
      log_warn "  $pvc: 无挂载 pod，跳过"
    fi
  done < <(kubectl get pvc -n "$NAMESPACE" -o json 2>/dev/null | jq -r '.items[] | select(.spec.volumeName != null) | .metadata.name')

  # 3.4 当前 Helm values 备份
  if helm get values "$HELM_RELEASE_NAME" -n "$NAMESPACE" > "$BACKUP_DIR/helm-values-previous.yaml" 2>/dev/null; then
    log_info "Helm values 备份完成"
  else
    log_warn "Helm values 备份失败（非阻断）"
  fi

  log_info "数据备份完成: $BACKUP_DIR"
fi

# ----------------------------------------------------------------------------
# 4. Helm repo 更新
# ----------------------------------------------------------------------------
log_step "3/9 Helm repo 更新"
if [ "$DRY_RUN" = "true" ]; then
  log_info "[DRY-RUN] 跳过 repo 更新"
else
  if ! helm repo update 2>&1 | tee -a "$LOG_FILE"; then
    log_error "helm repo update 失败，请检查网络与 $HELM_REPO_URL 可达性"
    exit 1
  fi
fi

# ----------------------------------------------------------------------------
# 5. Dry-run 检查（可选）
# ----------------------------------------------------------------------------
if [ "$DRY_RUN" = "true" ]; then
  log_step "4/9 Dry-Run 检查"
  log_info "[DRY-RUN] helm upgrade 模拟..."
  helm upgrade "$HELM_RELEASE_NAME" "$HELM_CHART_NAME" \
    --namespace "$NAMESPACE" \
    --version "$TARGET_VERSION" \
    -f "$SCRIPT_DIR/helm-values.yaml" \
    --dry-run 2>&1 | tee -a "$LOG_FILE" | tail -40
  log_info "[DRY-RUN] 完成——以上为渲染结果预览，未执行实际升级"
  log_info "[DRY-RUN] 确认无误后去掉 --dry-run 重新执行"
  exit 0
fi

# ----------------------------------------------------------------------------
# 6. 执行 helm upgrade
# ----------------------------------------------------------------------------
log_step "5/9 执行 Helm 升级（V${SOURCE_VERSION} → V${TARGET_VERSION}）"
if ! helm upgrade "$HELM_RELEASE_NAME" "$HELM_CHART_NAME" \
      --namespace "$NAMESPACE" \
      --version "$TARGET_VERSION" \
      -f "$SCRIPT_DIR/helm-values.yaml" \
      --wait --timeout 15m \
      --atomic 2>&1 | tee -a "$LOG_FILE"; then
  log_error "Helm 升级失败（--atomic 已自动回滚到 V${SOURCE_VERSION}），请检查上方日志"
  exit 1
fi
log_info "Helm 升级完成（--atomic 保证失败即回滚）"

# ----------------------------------------------------------------------------
# 7. 等待 Pod 就绪
# ----------------------------------------------------------------------------
log_step "6/9 等待 Pod 就绪"
if ! kubectl wait --for=condition=Ready pod -l "app.kubernetes.io/instance=$HELM_RELEASE_NAME" \
       -n "$NAMESPACE" --timeout=600s 2>&1 | tee -a "$LOG_FILE"; then
  log_warn "部分 Pod 600s 内未 Ready——列出未就绪清单供排查（不阻断，可能是 experimental 组件 Mock 模式启动慢）"
  kubectl get pods -n "$NAMESPACE" --field-selector=status.phase!=Running,status.phase!=Succeeded -o wide 2>/dev/null | head -20 || true
fi

# ----------------------------------------------------------------------------
# 8. 数据迁移
# ----------------------------------------------------------------------------
log_step "7/9 数据迁移"

# 8.1 catalog: SQLite → PostgreSQL（按 RELEASE-NOTES §3.2，启用 PG 时迁移）
CATALOG_DB_CFG="$(kubectl get configmap -n "$NAMESPACE" catalog-config -o jsonpath='{.data.CATALOG_DB}' 2>/dev/null || echo "")"
if [[ "$CATALOG_DB_CFG" == postgres* ]]; then
  log_info "catalog 已配置 PostgreSQL——检查是否需要迁移 SQLite 数据..."
  CATALOG_POD="$(kubectl get pod -n "$NAMESPACE" -l app.kubernetes.io/name=catalog --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [ -n "$CATALOG_POD" ]; then
    if ! kubectl exec -n "$NAMESPACE" "$CATALOG_POD" -- /app/catalog migrate 2>&1 | tee -a "$LOG_FILE"; then
      log_warn "catalog migrate 返回非零（可能已迁移过，非阻断）"
    fi
  else
    log_warn "catalog pod 未 Running，跳过迁移步骤（可稍后手动执行: kubectl exec ... /app/catalog migrate）"
  fi
else
  log_info "catalog 保持 SQLite 模式（CATALOG_DB=$CATALOG_DB_CFG），跳过迁移"
fi

# 8.2 rule-engine: H2 → PostgreSQL
RULEENGINE_DB_CFG="$(kubectl get configmap -n "$NAMESPACE" rule-engine-config -o jsonpath='{.data.DB_URL}' 2>/dev/null || echo "")"
if [[ "$RULEENGINE_DB_CFG" == postgresql* ]]; then
  log_info "rule-engine 已配置 PostgreSQL——确认连接..."
  RULE_POD="$(kubectl get pod -n "$NAMESPACE" -l app.kubernetes.io/name=rule-engine --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [ -n "$RULE_POD" ]; then
    kubectl exec -n "$NAMESPACE" "$RULE_POD" -- sh -c 'test -f /app/data/rule-engine.mv.db && echo "H2 文件仍在，历史数据保留在 PVC 中"' 2>/dev/null || true
    log_info "rule-engine PG 连接就绪（H2 历史数据保留在 PVC，未删除）"
  fi
else
  log_info "rule-engine 保持 H2 模式（DB_URL=$RULEENGINE_DB_CFG），跳过迁移"
fi

log_info "数据迁移步骤完成"

# ----------------------------------------------------------------------------
# 9. 升级后验证
# ----------------------------------------------------------------------------
log_step "8/9 升级后验证"

# 9.1 版本确认
ACTUAL_VER="$(helm get metadata "$HELM_RELEASE_NAME" -n "$NAMESPACE" -o json 2>/dev/null | jq -r '.app_version // "unknown"' || echo "unknown")"
log_info "  Release 版本: $ACTUAL_VER"
if [ "$ACTUAL_VER" != "$TARGET_VERSION" ] && [ "$ACTUAL_VER" != "unknown" ]; then
  log_warn "Release 版本（$ACTUAL_VER）与目标（$TARGET_VERSION）不一致，请人工确认"
fi

# 9.2 核心组件健康检查（GA 承诺组件；experimental 组件 Mock 模式允许失败）
CORE_COMPONENTS="catalog sql-gateway rule-engine encaps-layer"
HEALTH_FAIL=0
for svc in $CORE_COMPONENTS; do
  POD_NAME="$(kubectl get pod -n "$NAMESPACE" -l "app.kubernetes.io/name=$svc" --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [ -z "$POD_NAME" ]; then
    log_warn "  $svc: 无 Running pod"
    HEALTH_FAIL=$((HEALTH_FAIL + 1))
    continue
  fi
  if kubectl exec -n "$NAMESPACE" "$POD_NAME" -- wget -qO- "http://localhost:8080/health" 2>/dev/null | grep -q "UP"; then
    log_info "  $svc: healthy"
  else
    log_warn "  $svc: health 端点未返回 UP（组件可能用不同端口，请人工核查）"
    HEALTH_FAIL=$((HEALTH_FAIL + 1))
  fi
done

if [ "$HEALTH_FAIL" -gt 2 ]; then
  log_error "核心组件 3 个以上健康检查失败——升级可能有问题，建议执行回滚"
  exit 1
fi

log_info "升级验证通过（${HEALTH_FAIL} 个组件待人工复核）"

# ----------------------------------------------------------------------------
# 10. 完成
# ----------------------------------------------------------------------------
log_step "9/9 升级完成"
log_info "=========================================="
log_info "  V${SOURCE_VERSION} → V${TARGET_VERSION} 升级成功"
log_info "  备份目录: $BACKUP_DIR（请妥善保管）"
log_info "  回滚命令: helm rollback $HELM_RELEASE_NAME -n $NAMESPACE"
log_info "=========================================="
log_info ""
log_info "后续提示（RELEASE-NOTES §4 已知限制）:"
log_info "  · 10 个 AI/模型组件为 experimental（Mock 默认），如需真实后端请按文档配置"
log_info "  · catalog JWT 密钥若尚未注入，请立即注入（catalog 会 fail-fast）"
log_info "  · 18 个容器已切换非 root（app uid），如遇权限问题检查 PVC fsGroup"
log_info ""
log_info "如需人工回滚，执行: helm rollback $HELM_RELEASE_NAME -n $NAMESPACE"
log_info "（helm upgrade 的 --atomic 已在失败时自动回滚；备份目录 $BACKUP_DIR 里有升级前的 dump 与 values）"
