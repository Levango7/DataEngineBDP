#!/usr/bin/env bash
# 数据引擎大数据平台 · ArgoCD 回滚脚本
# 目标：安全回滚指定 Application 到历史 revision
#
# 用法：
#   bash design/deploy/argocd/rollback/rollback.sh <app-name> [revision-id]
#   bash design/deploy/argocd/rollback/rollback.sh root-prod          # 交互选择 revision
#   bash design/deploy/argocd/rollback/rollback.sh root-prod 19       # 直接回滚到 ID=19
#
# 前置：argocd CLI 已安装并登录（argocd login <server> --username admin --password <pass>）
set -uo pipefail

# ---------- 日志 ----------
log(){ echo -e "\033[36m[rollback]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

# ---------- 参数 ----------
APP_NAME="${1:-}"
TARGET_REVISION="${2:-}"

if [[ -z "$APP_NAME" ]]; then
  err "用法: bash rollback.sh <app-name> [revision-id]"
  err "示例: bash rollback.sh root-prod 19"
  exit 1
fi

# ---------- 前置检查 ----------
if ! command -v argocd >/dev/null 2>&1; then
  err "argocd CLI 未安装"
  err "安装: curl -sSL -o /usr/local/bin/argocd https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-amd64"
  exit 1
fi

# 检查 Application 是否存在
if ! argocd app get "$APP_NAME" >/dev/null 2>&1; then
  err "Application '$APP_NAME' 不存在或无法访问"
  exit 1
fi

log "=== 回滚 Application: $APP_NAME ==="
echo

# ---------- Step 1: 保存当前状态快照 ----------
log "【Step 1/5】保存当前状态快照..."
SNAPSHOT_DIR="/tmp/argocd-rollback-snapshots"
mkdir -p "$SNAPSHOT_DIR"
SNAPSHOT_FILE="$SNAPSHOT_DIR/${APP_NAME}-$(date +%Y%m%d-%H%M%S).yaml"
argocd app manifest "$APP_NAME" > "$SNAPSHOT_FILE" 2>/dev/null
ok "快照已保存: $SNAPSHOT_FILE"
echo

# ---------- Step 2: 查看同步历史 ----------
log "【Step 2/5】同步历史（最近 10 次）..."
HISTORY=$(argocd app history "$APP_NAME" 2>/dev/null)
echo "$HISTORY" | head -15
echo

# ---------- Step 3: 选择回滚目标 revision ----------
if [[ -z "$TARGET_REVISION" ]]; then
  log "【Step 3/5】请输入回滚目标 revision ID（上方 ID 列）:"
  read -r TARGET_REVISION
  if [[ -z "$TARGET_REVISION" ]]; then
    err "未输入 revision ID，取消回滚"
    exit 1
  fi
fi

# 确认回滚
warn "即将回滚 $APP_NAME 到 revision ID=$TARGET_REVISION"
warn "此操作会：1) 暂停自动同步 2) 回滚到历史 revision 3) 验证状态"
read -r -p "确认回滚？(yes/no): " CONFIRM
if [[ "$CONFIRM" != "yes" ]]; then
  log "已取消"; exit 0
fi
echo

# ---------- Step 4: 暂停自动同步 + 执行回滚 ----------
log "【Step 4/5】暂停自动同步（避免 selfHeal 覆盖回滚）..."
argocd app set "$APP_NAME" --sync-policy none 2>/dev/null
ok "自动同步已暂停"

log "执行回滚到 revision ID=$TARGET_REVISION..."
if ! argocd app rollback "$APP_NAME" "$TARGET_REVISION"; then
  err "回滚失败，恢复自动同步..."
  argocd app set "$APP_NAME" --sync-policy automated 2>/dev/null
  exit 1
fi
ok "回滚已执行"
echo

# ---------- Step 5: 验证 ----------
log "【Step 5/5】验证回滚状态..."
sleep 5

APP_STATUS=$(argocd app get "$APP_NAME" 2>/dev/null)
SYNC_STATUS=$(echo "$APP_STATUS" | grep -E 'Sync Status' | awk '{print $3}')
HEALTH_STATUS=$(echo "$APP_STATUS" | grep -E 'Health Status' | awk '{print $3}')

log "同步状态: $SYNC_STATUS"
log "健康状态: $HEALTH_STATUS"

if [[ "$SYNC_STATUS" == "Synced" ]] && [[ "$HEALTH_STATUS" == "Healthy" ]]; then
  ok "回滚成功！Application 已 Synced + Healthy"
else
  warn "回滚后状态: Sync=$SYNC_STATUS Health=$HEALTH_STATUS"
  warn "请检查: argocd app get $APP_NAME"
fi

echo
echo "============================================================"
ok "回滚完成: $APP_NAME → revision $TARGET_REVISION"
echo "============================================================"
log "后续步骤（重要）:"
echo "  1. 在 Git 中修复故障根因:"
echo "     git revert <faulty-commit> && git push origin main"
echo "  2. 等待 ArgoCD 同步到修复版本:"
echo "     argocd app wait $APP_NAME --sync --health"
echo "  3. 恢复自动同步:"
echo "     argocd app set $APP_NAME --sync-policy automated"
echo "  4. 验证 selfHeal 恢复:"
echo "     argocd app get $APP_NAME | grep -E 'Auto-Prune|Self-Heal'"
echo
log "快照文件: $SNAPSHOT_FILE"