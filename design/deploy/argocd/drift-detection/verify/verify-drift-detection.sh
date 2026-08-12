#!/usr/bin/env bash
# ============================================================================
# 漂移检测体系验证脚本 · verify-drift-detection.sh
# ============================================================================
# 作用：验证 T004 漂移检测体系所有组件是否就绪
# 用法：bash verify-drift-detection.sh
# 退出码：0=全部通过，非 0=有失败项
# ============================================================================
set -uo pipefail

ARGOCD_NS="${ARGOCD_NS:-argocd}"
PASS=0
FAIL=0
WARN=0

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ----------------------------------------------------------------------------
# 检查函数
# ----------------------------------------------------------------------------
check() {
  local name=$1
  local cmd=$2
  local expect=${3:-}

  printf "${BLUE}[检查]${NC} %s ... " "$name"
  local result
  result=$(eval "$cmd" 2>&1) || true

  if [[ -n "$expect" ]]; then
    if echo "$result" | grep -qE "$expect"; then
      printf "${GREEN}通过${NC}\n"
      PASS=$((PASS + 1))
    else
      printf "${RED}失败${NC}\n"
      printf "  期望: %s\n" "$expect"
      printf "  实际: %s\n" "$result"
      FAIL=$((FAIL + 1))
    fi
  else
    if eval "$cmd" &>/dev/null; then
      printf "${GREEN}通过${NC}\n"
      PASS=$((PASS + 1))
    else
      printf "${RED}失败${NC}\n"
      printf "  错误: %s\n" "$result"
      FAIL=$((FAIL + 1))
    fi
  fi
}

check_warn() {
  local name=$1
  local cmd=$2

  printf "${BLUE}[检查]${NC} %s ... " "$name"
  if eval "$cmd" &>/dev/null; then
    printf "${GREEN}通过${NC}\n"
    PASS=$((PASS + 1))
  else
    printf "${YELLOW}警告${NC}\n"
    WARN=$((WARN + 1))
  fi
}

section() {
  echo ""
  printf "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
  printf "${BLUE}  %s${NC}\n" "$1"
  printf "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"
}

# ============================================================================
# 验证开始
# ============================================================================
echo "============================================================"
echo "  T004 GitOps 漂移检测体系验证"
echo "  时间: $(date -u +%FT%TZ)"
echo "  ArgoCD Namespace: $ARGOCD_NS"
echo "============================================================"

# ----------------------------------------------------------------------------
# 1. 前置依赖检查
# ----------------------------------------------------------------------------
section "1. 前置依赖检查"

check "kubectl 可用" "command -v kubectl"
check "argocd CLI 可用" "command -v argocd"
check "jq 可用" "command -v jq"
check "ArgoCD namespace 存在" "kubectl get namespace $ARGOCD_NS"
check "ArgoCD server 运行中" "kubectl get deploy argocd-server -n $ARGOCD_NS" "argocd-server"

# ----------------------------------------------------------------------------
# 2. T003 Application 检查
# ----------------------------------------------------------------------------
section "2. T003 Application 检查（前置）"

check "root-dev Application 存在" "kubectl get application root-dev -n $ARGOCD_NS"
check "root-staging Application 存在" "kubectl get application root-staging -n $ARGOCD_NS"
check "root-prod Application 存在" "kubectl get application root-prod -n $ARGOCD_NS"
check "root-dev 已 Synced" "kubectl get application root-dev -n $ARGOCD_NS -o jsonpath='{.status.sync.status}'" "Synced"
check "root-prod 已 Synced" "kubectl get application root-prod -n $ARGOCD_NS -o jsonpath='{.status.sync.status}'" "Synced"

# ----------------------------------------------------------------------------
# 3. 漂移策略检查
# ----------------------------------------------------------------------------
section "3. 漂移策略检查"

check "drift-policy ConfigMap 存在" "kubectl get configmap drift-policy -n $ARGOCD_NS"
check "drift-ignore-differences ConfigMap 存在" "kubectl get configmap drift-ignore-differences -n $ARGOCD_NS"
check "drift-severity-rules ConfigMap 存在" "kubectl get configmap drift-severity-rules -n $ARGOCD_NS"
check "drift-policy 含 env-policy" "kubectl get configmap drift-policy -n $ARGOCD_NS -o jsonpath='{.data.env-policy.yaml}'" "environments"

# ----------------------------------------------------------------------------
# 4. 漂移检测检查
# ----------------------------------------------------------------------------
section "4. 漂移检测检查"

check "resource-customizations ConfigMap 存在" "kubectl get configmap argocd-drift-customizations -n $ARGOCD_NS"
check "diff-server-config ConfigMap 存在" "kubectl get configmap argocd-diff-config -n $ARGOCD_NS"
check "detect-drift.sh 存在" "test -f detection/detect-drift.sh"
check "detect-drift.sh 可执行" "test -x detection/detect-drift.sh"

# 尝试运行检测脚本（dry-run）
check_warn "detect-drift.sh 可运行（dry-run）" "bash detection/detect-drift.sh --dry-run --app root-dev"

# ----------------------------------------------------------------------------
# 5. 自动修复检查
# ----------------------------------------------------------------------------
section "5. 自动修复检查"

check "self-heal-dev.yaml 存在" "test -f remediation/self-heal-dev.yaml"
check "self-heal-staging.yaml 存在" "test -f remediation/self-heal-staging.yaml"
check "self-heal-prod.yaml 存在" "test -f remediation/self-heal-prod.yaml"
check "remediation-job.yaml 存在" "test -f remediation/remediation-job.yaml"
check "remediate.sh 存在" "test -f remediation/remediate.sh"

# 检查 selfHeal 是否已启用
check "root-dev selfHeal=true" "kubectl get application root-dev -n $ARGOCD_NS -o jsonpath='{.spec.syncPolicy.automated.selfHeal}'" "true"
check "root-prod selfHeal=true" "kubectl get application root-prod -n $ARGOCD_NS -o jsonpath='{.spec.syncPolicy.automated.selfHeal}'" "true"

# 检查修复 CronJob
check_warn "drift-remediation CronJob 存在" "kubectl get cronjob drift-remediation -n $ARGOCD_NS"
check_warn "argocd-remediation-sa 存在" "kubectl get serviceaccount argocd-remediation-sa -n $ARGOCD_NS"

# ----------------------------------------------------------------------------
# 6. 告警通知检查
# ----------------------------------------------------------------------------
section "6. 告警通知检查"

check "argocd-notifications-cm 存在" "kubectl get configmap argocd-notifications-cm -n $ARGOCD_NS"
check "notifications-cm 含 slack 服务" "kubectl get configmap argocd-notifications-cm -n $ARGOCD_NS -o jsonpath='{.data.service\.slack}'" "slack"
check "notifications-cm 含 drift 模板" "kubectl get configmap argocd-notifications-cm -n $ARGOCD_NS -o jsonpath='{.data.template\.app-drift-detected}'" "drift"
check "drift-alert-rules ConfigMap 存在" "kubectl get configmap drift-alert-rules -n $ARGOCD_NS"
check "drift-slack-templates ConfigMap 存在" "kubectl get configmap drift-slack-templates -n $ARGOCD_NS"
check "drift-alert-routing ConfigMap 存在" "kubectl get configmap drift-alert-routing -n $ARGOCD_NS"
check_warn "notifications-secret 存在" "kubectl get secret argocd-notifications-secret -n $ARGOCD_NS"

# 检查 Application 是否订阅通知
check_warn "root-prod 已订阅 drift 通知" "kubectl get application root-prod -n $ARGOCD_NS -o jsonpath='{.metadata.annotations}'" "notifications"

# ----------------------------------------------------------------------------
# 7. 合规审计检查
# ----------------------------------------------------------------------------
section "7. 合规审计检查"

check "drift-audit-config ConfigMap 存在" "kubectl get configmap drift-audit-config -n $ARGOCD_NS"
check "audit-config 含 SIEM 配置" "kubectl get configmap drift-audit-config -n $ARGOCD_NS -o jsonpath='{.data.siem-config\.yaml}'" "siem"
check "drift-compliance-rules ConfigMap 存在" "kubectl get configmap drift-compliance-rules -n $ARGOCD_NS"
check "compliance-rules 含 Rego 规则" "kubectl get configmap drift-compliance-rules -n $ARGOCD_NS -o jsonpath='{.data.rule-1-argocd-only\.rego}'" "package"
check_warn "drift-audit-export CronJob 存在" "kubectl get cronjob drift-audit-export -n $ARGOCD_NS"
check_warn "drift-audit-scripts ConfigMap 存在" "kubectl get configmap drift-audit-scripts -n $ARGOCD_NS"

# ----------------------------------------------------------------------------
# 8. 监控面板检查
# ----------------------------------------------------------------------------
section "8. 监控面板检查"

check "drift-dashboard.json 存在" "test -f dashboards/drift-dashboard.json"
check "drift-metrics.yaml 存在" "test -f dashboards/drift-metrics.yaml"
check_warn "PrometheusRule 已部署" "kubectl get prometheusrule drift-detection-rules -n monitoring"

# ----------------------------------------------------------------------------
# 9. 文档完整性检查
# ----------------------------------------------------------------------------
section "9. 文档完整性检查"

check "README.md 存在" "test -f README.md"
check "drift-detection-strategy.md 存在" "test -f detection/drift-detection-strategy.md"
check "remediation-strategy.md 存在" "test -f remediation/remediation-strategy.md"
check "alerting-channels.md 存在" "test -f alerting/alerting-channels.md"
check "audit-strategy.md 存在" "test -f audit/audit-strategy.md"

# ----------------------------------------------------------------------------
# 10. 端到端验证（可选）
# ----------------------------------------------------------------------------
section "10. 端到端验证"

# 检查是否有漂移事件
check_warn "无未处理漂移事件" "! kubectl get configmap -n $ARGOCD_NS -l drift-event=true,remediation-status=pending 2>/dev/null | grep -v NAME"

# 检查是否有熔断
check_warn "无熔断状态" "! kubectl get configmap -n $ARGOCD_NS -l drift-circuit-breaker=true 2>/dev/null | grep -v NAME"

# ============================================================================
# 汇总
# ============================================================================
echo ""
echo "============================================================"
printf "  ${GREEN}通过: $PASS${NC}  ${RED}失败: $FAIL${NC}  ${YELLOW}警告: $WARN${NC}\n"
echo "============================================================"

if [[ $FAIL -eq 0 ]]; then
  printf "${GREEN}✅ 漂移检测体系验证通过${NC}\n"
  exit 0
else
  printf "${RED}❌ 漂移检测体系验证失败（$FAIL 项失败）${NC}\n"
  exit 1
fi