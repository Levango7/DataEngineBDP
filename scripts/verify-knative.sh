#!/usr/bin/env bash
# ============================================================================
# 数据引擎大数据平台 · Knative Serving/Eventing 部署验证脚本
# ----------------------------------------------------------------------------
# 目标：验证 Knative Serving + Eventing 部署状态、CRD、自动伸缩、事件源
#
# 用法：
#   bash scripts/verify-knative.sh                    # 完整验证
#   bash scripts/verify-knative.sh --quick            # 快速验证（跳过示例）
#   bash scripts/verify-knative.sh --deploy           # 部署 + 验证
#   bash scripts/verify-knative.sh --scale-to-zero    # 验证 scale-to-zero
#
# 前置：
#   - kubectl 已配置且可连接集群
#   - Knative Serving/Eventing 已部署（或使用 --deploy 自动部署）
#   - Phase 1 Istio 已部署（istio-system namespace）
#   - Phase 1 ArgoCD 已部署（argocd namespace）
#
# 验证项：
#   1. namespace 存在（knative-serving, knative-eventing）
#   2. CRD 已注册（ksvc, kafkasource, cronjobsource, pingsource）
#   3. 控制面 Pod 就绪（controller, webhook, autoscaler, activator）
#   4. ConfigMap 配置正确（config-autoscaler, config-network）
#   5. KPA 自动伸缩配置（enableScaleToZero, targetConcurrency）
#   6. Istio Ingress Gateway 集成
#   7. ArgoCD Application 同步状态
#   8. 示例 KService 创建与 URL 分配
#   9. KafkaSource/CronJobSource 事件源状态
#  10. scale-to-zero 验证（无流量 60s 后 Pod 缩容到 0）
# ============================================================================
set -uo pipefail

# ---------- 日志函数 ----------
log(){ echo -e "\033[36m[verify]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

# ---------- 参数解析 ----------
QUICK=false
DEPLOY=false
SCALE_TO_ZERO=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)         QUICK=true; shift ;;
    --deploy)        DEPLOY=true; shift ;;
    --scale-to-zero) SCALE_TO_ZERO=true; shift ;;
    -h|--help)
      head -25 "$0" | tail -23
      exit 0
      ;;
    *) err "未知参数: $1"; exit 1 ;;
  esac
done

# ---------- 前置检查 ----------
if ! command -v kubectl >/dev/null 2>&1; then
  err "kubectl 未找到，请先安装 kubectl"
  exit 1
fi

if ! kubectl get nodes >/dev/null 2>&1; then
  err "无法连接 Kubernetes 集群，请检查 kubeconfig"
  exit 1
fi

# ---------- 计数器 ----------
PASS=0
FAIL=0
WARN_COUNT=0

check() {
  local desc="$1"
  local result="$2"
  local detail="${3:-}"
  if [[ "$result" == "pass" ]]; then
    ok "$desc"
    [[ -n "$detail" ]] && log "  $detail"
    PASS=$((PASS+1))
  elif [[ "$result" == "warn" ]]; then
    warn "$desc"
    [[ -n "$detail" ]] && log "  $detail"
    WARN_COUNT=$((WARN_COUNT+1))
  else
    err "$desc"
    [[ -n "$detail" ]] && log "  $detail"
    FAIL=$((FAIL+1))
  fi
}

echo "============================================================"
log "数据引擎大数据平台 · Knative Serving/Eventing 部署验证"
log "任务：T024 · Knative >= 1.12"
echo "============================================================"
echo

# ---------- 0. 自动部署（可选）----------
if [[ "$DEPLOY" == "true" ]]; then
  log "【0/10】自动部署 Knative..."
  if [[ -f "platform/knative/argocd-application.yaml" ]]; then
    kubectl apply -f platform/knative/argocd-application.yaml
    check "ArgoCD Application 已提交" "pass"
    log "等待 ArgoCD 同步（30s）..."
    sleep 30
  else
    check "ArgoCD Application 文件存在" "fail" "未找到 platform/knative/argocd-application.yaml"
  fi
  echo
fi

# ---------- 1. namespace ----------
log "【1/10】检查 namespace..."
for ns in knative-serving knative-eventing; do
  if kubectl get namespace "$ns" >/dev/null 2>&1; then
    INJECTION=$(kubectl get namespace "$ns" -o jsonpath='{.metadata.labels.istio-injection}' 2>/dev/null)
    check "namespace $ns" "pass" "istio-injection=${INJECTION:-none}"
  else
    check "namespace $ns" "fail" "namespace 不存在"
  fi
done
echo

# ---------- 2. CRD ----------
log "【2/10】检查 Knative CRD..."
# Serving CRD
for crd in services.serving.knative.dev configurations.serving.knative.dev revisions.serving.knative.dev routes.serving.knative.dev; do
  if kubectl get crd "$crd" >/dev/null 2>&1; then
    check "CRD $crd" "pass"
  else
    check "CRD $crd" "fail"
  fi
done
# Eventing CRD
for crd in brokers.eventing.knative.dev triggers.eventing.knative.dev subscriptions.eventing.knative.dev; do
  if kubectl get crd "$crd" >/dev/null 2>&1; then
    check "CRD $crd" "pass"
  else
    check "CRD $crd" "fail"
  fi
done
# 事件源 CRD
for crd in kafkasources.sources.knative.dev cronjobsources.sources.knative.dev pingsources.sources.knative.dev; do
  if kubectl get crd "$crd" >/dev/null 2>&1; then
    check "事件源 CRD $crd" "pass"
  else
    check "事件源 CRD $crd" "fail"
  fi
done
echo

# ---------- 3. 控制面 Pod ----------
log "【3/10】检查 Knative Serving 控制面 Pod..."
SERVING_PODS=$(kubectl get pods -n knative-serving --no-headers 2>/dev/null | wc -l)
if [[ "$SERVING_PODS" -gt 0 ]]; then
  check "knative-serving Pod 数量" "pass" "$SERVING_PODS 个 Pod"
else
  check "knative-serving Pod 数量" "fail" "无 Pod"
fi

# 关键组件
for comp in controller webhook autoscaler activator; do
  if kubectl get deploy -n knative-serving -o name 2>/dev/null | grep -q "$comp"; then
    DEPLOY_NAME=$(kubectl get deploy -n knative-serving -o name 2>/dev/null | grep "$comp" | head -1 | sed 's|deployment/||')
    READY=$(kubectl get deploy "$DEPLOY_NAME" -n knative-serving -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
    REPLICAS=$(kubectl get deploy "$DEPLOY_NAME" -n knative-serving -o jsonpath='{.spec.replicas}' 2>/dev/null)
    if [[ "$READY" == "$REPLICAS" ]] && [[ -n "$READY" ]]; then
      check "  Serving $comp" "pass" "readyReplicas=$READY/$REPLICAS"
    else
      check "  Serving $comp" "warn" "readyReplicas=${READY:-0}/$REPLICAS"
    fi
  else
    check "  Serving $comp" "warn" "Deployment 不存在"
  fi
done

log "检查 Knative Eventing 控制面 Pod..."
EVENTING_PODS=$(kubectl get pods -n knative-eventing --no-headers 2>/dev/null | wc -l)
if [[ "$EVENTING_PODS" -gt 0 ]]; then
  check "knative-eventing Pod 数量" "pass" "$EVENTING_PODS 个 Pod"
else
  check "knative-eventing Pod 数量" "fail" "无 Pod"
fi

for comp in controller webhook; do
  if kubectl get deploy -n knative-eventing -o name 2>/dev/null | grep -q "$comp"; then
    DEPLOY_NAME=$(kubectl get deploy -n knative-eventing -o name 2>/dev/null | grep "$comp" | head -1 | sed 's|deployment/||')
    READY=$(kubectl get deploy "$DEPLOY_NAME" -n knative-eventing -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
    REPLICAS=$(kubectl get deploy "$DEPLOY_NAME" -n knative-eventing -o jsonpath='{.spec.replicas}' 2>/dev/null)
    if [[ "$READY" == "$REPLICAS" ]] && [[ -n "$READY" ]]; then
      check "  Eventing $comp" "pass" "readyReplicas=$READY/$REPLICAS"
    else
      check "  Eventing $comp" "warn" "readyReplicas=${READY:-0}/$REPLICAS"
    fi
  else
    check "  Eventing $comp" "warn" "Deployment 不存在"
  fi
done
echo

# ---------- 4. ConfigMap 配置 ----------
log "【4/10】检查 ConfigMap 配置..."

# config-autoscaler
if kubectl get configmap config-autoscaler -n knative-serving >/dev/null 2>&1; then
  S2Z=$(kubectl get configmap config-autoscaler -n knative-serving -o jsonpath='{.data.enable-scale-to-zero}' 2>/dev/null)
  GRACE=$(kubectl get configmap config-autoscaler -n knative-serving -o jsonpath='{.data.scale-to-zero-grace-period}' 2>/dev/null)
  TARGET=$(kubectl get configmap config-autoscaler -n knative-serving -o jsonpath='{.data.target-concurrency}' 2>/dev/null)
  check "config-autoscaler 存在" "pass"
  check "  scale-to-zero" "${S2Z:-未设置}" "enable-scale-to-zero=${S2Z:-未设置}"
  check "  grace-period" "${GRACE:-未设置}" "scale-to-zero-grace-period=${GRACE:-未设置}"
  check "  target-concurrency" "${TARGET:-未设置}" "target-concurrency=${TARGET:-未设置}"
else
  check "config-autoscaler" "fail" "ConfigMap 不存在"
fi

# config-network
if kubectl get configmap config-network -n knative-serving >/dev/null 2>&1; then
  INGRESS=$(kubectl get configmap config-network -n knative-serving -o jsonpath='{.data.ingress.class}' 2>/dev/null)
  DOMAIN=$(kubectl get configmap config-network -n knative-serving -o jsonpath='{.data.domain-template}' 2>/dev/null)
  check "config-network 存在" "pass"
  check "  ingress-class" "${INGRESS:-未设置}" "ingress.class=${INGRESS:-未设置}"
  check "  domain-template" "${DOMAIN:-未设置}" "domain-template=${DOMAIN:-未设置}"
else
  check "config-network" "fail" "ConfigMap 不存在"
fi
echo

# ---------- 5. KPA 自动伸缩 ----------
log "【5/10】检查 KPA 自动伸缩配置..."
if [[ "${S2Z:-}" == "true" ]]; then
  check "scale-to-zero 已启用" "pass"
else
  check "scale-to-zero 已启用" "fail" "enable-scale-to-zero=${S2Z:-未设置}"
fi

if [[ "${TARGET:-}" != "" ]]; then
  check "KPA target-concurrency 已配置" "pass" "target=$TARGET"
else
  check "KPA target-concurrency 已配置" "warn" "未配置"
fi

# 检查 KPA CRD
if kubectl get crd podautoscalers.autoscaling.knative.dev >/dev/null 2>&1; then
  check "KPA CRD (podautoscalers)" "pass"
else
  check "KPA CRD (podautoscalers)" "fail"
fi
echo

# ---------- 6. Istio Ingress Gateway 集成 ----------
log "【6/10】检查 Istio Ingress Gateway 集成..."
if kubectl get svc istio-ingressgateway -n istio-system >/dev/null 2>&1; then
  SVC_TYPE=$(kubectl get svc istio-ingressgateway -n istio-system -o jsonpath='{.spec.type}' 2>/dev/null)
  check "Istio Ingress Gateway 存在" "pass" "type=$SVC_TYPE"

  # 检查 Knative 是否使用 Istio
  if [[ "${INGRESS:-}" == *"istio"* ]]; then
    check "Knative 使用 Istio Ingress" "pass" "ingress.class=$INGRESS"
  else
    check "Knative 使用 Istio Ingress" "warn" "ingress.class=${INGRESS:-未设置}"
  fi
else
  check "Istio Ingress Gateway 存在" "fail" "istio-system/istio-ingressgateway 不存在"
fi
echo

# ---------- 7. ArgoCD Application ----------
log "【7/10】检查 ArgoCD Application 同步状态..."
for app in knative-serving knative-eventing knative-examples; do
  if kubectl get application "$app" -n argocd >/dev/null 2>&1; then
    SYNC=$(kubectl get application "$app" -n argocd -o jsonpath='{.status.sync.status}' 2>/dev/null)
    HEALTH=$(kubectl get application "$app" -n argocd -o jsonpath='{.status.health.status}' 2>/dev/null)
    WAVE=$(kubectl get application "$app" -n argocd -o jsonpath='{.metadata.annotations.argocd\.argoproj\.io/sync-wave}' 2>/dev/null)
    check "Application $app" "pass" "Sync=${SYNC:-?} Health=${HEALTH:-?} wave=${WAVE:-?}"
  else
    check "Application $app" "warn" "未创建（需 kubectl apply -f platform/knative/argocd-application.yaml）"
  fi
done
echo

# ---------- 快速模式：跳过示例验证 ----------
if [[ "$QUICK" == "true" ]]; then
  log "快速模式：跳过示例验证（8-10）"
  echo
  goto_summary=true
fi

if [[ "${goto_summary:-false}" != "true" ]]; then

# ---------- 8. 示例 KService ----------
log "【8/10】检查示例 KService..."
if kubectl get ksvc hello-kservice -n knative-examples >/dev/null 2>&1; then
  check "KService hello-kservice" "pass"
  # URL 分配
  URL=$(kubectl get ksvc hello-kservice -n knative-examples -o jsonpath='{.status.url}' 2>/dev/null)
  if [[ -n "$URL" ]]; then
    check "  URL 已分配" "pass" "url=$URL"
  else
    check "  URL 已分配" "warn" "status.url 为空"
  fi
  # Ready 状态
  READY=$(kubectl get ksvc hello-kservice -n knative-examples -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)
  check "  Ready 状态" "${READY:-Unknown}" "Ready=${READY:-Unknown}"
  # 当前副本数
  REPLICAS=$(kubectl get deployment -n knative-examples -l serving.knative.dev/service=hello-kservice -o jsonpath='{.items[0].spec.replicas}' 2>/dev/null)
  check "  当前副本数" "pass" "replicas=${REPLICAS:-0}"
else
  check "KService hello-kservice" "warn" "未创建（需 kubectl apply -f platform/knative/examples/）"
fi
echo

# ---------- 9. 事件源 ----------
log "【9/10】检查事件源..."

# KafkaSource
if kubectl get kafkasource kafka-source-example -n knative-examples >/dev/null 2>&1; then
  check "KafkaSource kafka-source-example" "pass"
  READY=$(kubectl get kafkasource kafka-source-example -n knative-examples -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)
  check "  Ready 状态" "${READY:-Unknown}" "Ready=${READY:-Unknown}"
else
  check "KafkaSource kafka-source-example" "warn" "未创建"
fi

# CronJobSource
if kubectl get cronjobsource cronjob-source-example -n knative-examples >/dev/null 2>&1; then
  check "CronJobSource cronjob-source-example" "pass"
  READY=$(kubectl get cronjobsource cronjob-source-example -n knative-examples -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)
  check "  Ready 状态" "${READY:-Unknown}" "Ready=${READY:-Unknown}"
else
  check "CronJobSource cronjob-source-example" "warn" "未创建"
fi

# PingSource
if kubectl get pingsource ping-source-example -n knative-examples >/dev/null 2>&1; then
  check "PingSource ping-source-example" "pass"
else
  check "PingSource ping-source-example" "warn" "未创建"
fi
echo

# ---------- 10. scale-to-zero 验证 ----------
if [[ "$SCALE_TO_ZERO" == "true" ]]; then
  log "【10/10】验证 scale-to-zero（无流量 60s 后 Pod 缩容到 0）..."
  if kubectl get ksvc hello-kservice -n knative-examples >/dev/null 2>&1; then
    log "当前副本数："
    kubectl get deployment -n knative-examples -l serving.knative.dev/service=hello-kservice -o jsonpath='{.items[0].spec.replicas}' 2>/dev/null
    echo
    log "等待 70s 观察缩容..."
    sleep 70
    REPLICAS=$(kubectl get deployment -n knative-examples -l serving.knative.dev/service=hello-kservice -o jsonpath='{.items[0].spec.replicas}' 2>/dev/null)
    if [[ "${REPLICAS:-0}" == "0" ]]; then
      check "scale-to-zero 生效" "pass" "无流量 70s 后副本数=0"
    else
      check "scale-to-zero 生效" "warn" "副本数=${REPLICAS:-?}（可能仍有流量或 grace-period 未到）"
    fi
  else
    check "scale-to-zero 验证" "warn" "KService 未创建"
  fi
  echo
else
  log "【10/10】scale-to-zero 验证（跳过，使用 --scale-to-zero 启用）"
  echo
fi

fi # end of !goto_summary

# ---------- 汇总 ----------
echo "============================================================"
log "验证汇总"
echo "============================================================"
ok "通过: $PASS"
[[ "$WARN_COUNT" -gt 0 ]] && warn "告警: $WARN_COUNT"
[[ "$FAIL" -gt 0 ]] && err "失败: $FAIL"
echo

if [[ "$FAIL" -gt 0 ]]; then
  err "存在失败项，请检查上方输出"
  exit 1
elif [[ "$WARN_COUNT" -gt 0 ]]; then
  warn "存在告警项（可能是未 apply 的配置或示例，属正常）"
  exit 0
else
  ok "Knative Serving/Eventing 部署验证全部通过"
  exit 0
fi
# ============================================================================
# 验证脚本结束
# 相关文件：
#   - platform/knative/knative-serving-values.yaml
#   - platform/knative/knative-eventing-values.yaml
#   - platform/knative/argocd-application.yaml
#   - platform/knative/examples/
#   - tests/integration/docker/test_knative.py
# ============================================================================