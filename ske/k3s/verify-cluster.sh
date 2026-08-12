#!/usr/bin/env bash
# 数擎云核 SKE · k3s + Istio 集群验证脚本
# 目标: 验证 k3s 集群可用 + Istio 控制面就绪 + sidecar 注入生效 + mTLS 状态
#
# 用法:
#   bash ske/k3s/verify-cluster.sh
#   bash ske/k3s/verify-cluster.sh --strict   # 严格模式，任一检查失败即退出非 0
set -uo pipefail

# ---------- 颜色与日志 ----------
log(){ echo -e "\033[36m[verify]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

STRICT=false
[[ "${1:-}" == "--strict" ]] && STRICT=true

fail(){ err "$1"; if $STRICT; then exit 1; fi; }

# ---------- kubectl 选择 ----------
if command -v kubectl >/dev/null 2>&1; then
  KUBECTL=kubectl
elif command -v k3s >/dev/null 2>&1; then
  KUBECTL="k3s kubectl"
else
  err "未找到 kubectl 或 k3s"; exit 1
fi

echo "============================================================"
log "数擎云核 SKE · k3s + Istio 集群验证"
echo "============================================================"
echo

# ---------- 1. k3s 节点 ----------
log "【1/6】k3s 节点状态"
echo "--- kubectl get nodes ---"
if ! $KUBECTL get nodes -o wide; then
  fail "无法获取节点列表，集群不可达"
fi
echo

# 检查所有节点 Ready
NOTREADY=$($KUBECTL get nodes -o jsonpath='{range .items[*]}{.metadata.name}{":"}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' | grep -v ":True" || true)
if [[ -z "$NOTREADY" ]]; then
  ok "所有节点 Ready"
else
  fail "存在非 Ready 节点: $NOTREADY"
fi
echo

# ---------- 2. 控制面 Pod ----------
log "【2/6】控制面 Pod 状态（kube-system）"
echo "--- kubectl get pods -A ---"
$KUBECTL get pods -A || fail "无法获取 Pod 列表"
echo

# 统计非 Running Pod
NOT_RUNNING=$($KUBECTL get pods -A -o jsonpath='{range .items[*]}{.metadata.namespace}{"/"}{.metadata.name}{":"}{.status.phase}{"\n"}{end}' | grep -v ":Running" | grep -v ":Succeeded" || true)
if [[ -z "$NOT_RUNNING" ]]; then
  ok "所有 Pod Running/Succeeded"
else
  warn "存在非 Running Pod（可能仍在启动）:"
  echo "$NOT_RUNNING"
fi
echo

# ---------- 3. istio-system ----------
log "【3/6】Istio 控制面（istio-system）"
if ! $KUBECTL get namespace istio-system >/dev/null 2>&1; then
  fail "namespace istio-system 不存在，请先运行 install-istio.sh"
else
  ok "namespace istio-system 存在"
fi
echo "--- kubectl get pods -n istio-system ---"
$KUBECTL get pods -n istio-system -o wide 2>/dev/null || warn "无法获取 istio-system Pod"
echo

# istiod 就绪检查
if $KUBECTL get deploy istiod -n istio-system >/dev/null 2>&1; then
  ISTIOD_READY=$($KUBECTL get deploy istiod -n istio-system -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)
  if [[ "$ISTIOD_READY" -ge 1 ]]; then
    ok "istiod 就绪 (readyReplicas=$ISTIOD_READY)"
  else
    fail "istiod 未就绪"
  fi
else
  warn "未找到 istiod Deployment"
fi
echo

# ---------- 4. sidecar 注入 ----------
log "【4/6】Sidecar 注入状态（namespace 标签）"
echo "--- namespace istio-injection 标签 ---"
$KUBECTL get namespace -o custom-columns=NAME:.metadata.name,ISTIO-INJECTION:.metadata.labels.istio-injection 2>/dev/null || \
  $KUBECTL get namespace --show-labels
echo

INJECTED_NS=$($KUBECTL get namespace -o jsonpath='{range .items[?(@.metadata.labels.istio-injection=="enabled")]}{.metadata.name}{" "}{end}' 2>/dev/null || true)
if [[ -n "$INJECTED_NS" ]]; then
  ok "已启用 sidecar 注入的 namespace: $INJECTED_NS"
else
  warn "无 namespace 启用 sidecar 注入"
fi
echo

# ---------- 5. mTLS ----------
log "【5/6】mTLS 策略状态"
if $KUBECTL get peerauthentication default-mtls -n istio-system >/dev/null 2>&1; then
  MTLS_MODE=$($KUBECTL get peerauthentication default-mtls -n istio-system -o jsonpath='{.spec.mtls.mode}' 2>/dev/null || echo "unknown")
  ok "PeerAuthentication default-mtls 存在，mode=$MTLS_MODE"
else
  warn "未找到 PeerAuthentication default-mtls"
fi
if $KUBECTL get destinationrule default-mtls -n istio-system >/dev/null 2>&1; then
  DR_TLS=$($KUBECTL get destinationrule default-mtls -n istio-system -o jsonpath='{.spec.trafficPolicy.tls.mode}' 2>/dev/null || echo "unknown")
  ok "DestinationRule default-mtls 存在，tls.mode=$DR_TLS"
else
  warn "未找到 DestinationRule default-mtls"
fi
echo

# ---------- 6. istioctl check ----------
log "【6/6】istioctl analyze（配置分析）"
if command -v istioctl >/dev/null 2>&1; then
  istioctl analyze 2>&1 || warn "istioctl analyze 报告问题（测试环境可忽略部分 warning）"
else
  warn "istioctl 未安装，跳过配置分析"
fi
echo

# ---------- 汇总 ----------
echo "============================================================"
log "验证完成"
echo "============================================================"
ok "集群: k3s 单主节点"
ok "Service Mesh: Istio $(istioctl version --remote=false 2>/dev/null | grep -oP 'client version: \K\S+' || echo 'unknown')"
ok "mTLS: $MTLS_MODE（过渡期，全量 sidecar 就绪后切 STRICT）"
echo
log "encaps-layer K8s client 模式: 需确认 K8S_MOCK_ENABLED=false（见 platform/encaps-layer/src/main/resources/application.yml）"