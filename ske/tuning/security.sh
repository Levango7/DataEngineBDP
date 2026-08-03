#!/usr/bin/env bash
# 数擎云核 SKE · K8s 集群安全加固检查 (ske security-check 调用)
# 关联: manifests/pod-security.yaml / manifests/apiserver-hardening.yaml / manifests/network-policies.yaml
# 功能:
#   - 检查 PodSecurityPolicy / Pod Security Admission 是否启用
#   - 检查所有 Namespace 是否有 deny-all NetworkPolicy
#   - 检查 APIServer 是否禁用匿名访问
#   - 检查 kubelet 是否禁用 --allow-privileged
#   - 检查 etcd 是否启用 TLS
#   - 检查 RBAC 是否启用
#   - 输出安全检查报告 (通过/警告/失败)
set -uo pipefail

# ---- 颜色与报告 ----
PASS=0; WARN=0; FAIL=0
GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; NC=$'\033[0m'

report_pass() { echo "  ${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
report_warn() { echo "  ${YELLOW}[WARN]${NC} $1"; WARN=$((WARN+1)); }
report_fail() { echo "  ${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); }

KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
# 系统组件白名单 Namespace (不检查 deny-all)
SYSTEM_NS="kube-system kube-public kube-node-lease ske-system cilium-system observability sq-system"

has_kubectl() { command -v "$KUBECTL_BIN" >/dev/null 2>&1; }

echo "== [SKE] K8s 集群安全加固检查 =="
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# ---- 1. 检查 PodSecurityPolicy / Pod Security Admission ----
echo "-- 1. Pod 安全策略检查 --"
if has_kubectl; then
  # Pod Security Admission (K8s >= 1.25)
  PSA_NS=$("$KUBECTL_BIN" get namespaces -o jsonpath='{range .items[*]}{.metadata.name}{":"}{.metadata.labels.pod-security\.kubernetes\.io/enforce}{"\n"}{end}' 2>/dev/null || echo "")
  if echo "$PSA_NS" | grep -q "restricted"; then
    report_pass "Pod Security Admission (restricted) 已在部分 Namespace 启用"
  else
    report_warn "未检测到 Pod Security Admission restricted 级别"
  fi
  # PodSecurityPolicy (K8s < 1.25)
  if "$KUBECTL_BIN" get psp ske-restricted >/dev/null 2>&1; then
    report_pass "PodSecurityPolicy ske-restricted 存在"
  else
    report_warn "PodSecurityPolicy ske-restricted 不存在 (K8s >= 1.25 由 PSA 接管)"
  fi
  # 检查是否有特权 Pod 在租户 Namespace 运行
  PRIV_PODS=$("$KUBECTL_BIN" get pods --all-namespaces -o json \
    | grep -c '"privileged":true' 2>/dev/null || echo 0)
  if [ "$PRIV_PODS" -gt 0 ]; then
    report_warn "检测到 $PRIV_PODS 个特权 Pod (确认是否为系统组件白名单)"
  else
    report_pass "无特权 Pod 运行"
  fi
else
  report_warn "kubectl 不可用, 跳过 Pod 安全策略检查"
fi

# ---- 2. 检查所有 Namespace 是否有 deny-all NetworkPolicy ----
echo "-- 2. NetworkPolicy 租户隔离检查 --"
if has_kubectl; then
  ALL_NS=$("$KUBECTL_BIN" get namespaces -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || echo "")
  for NS in $ALL_NS; do
    # 跳过系统 Namespace
    echo "$SYSTEM_NS" | grep -qw "$NS" && continue
    # 检查是否有 deny-all 策略
    if "$KUBECTL_BIN" -n "$NS" get networkpolicy ske-default-deny-all >/dev/null 2>&1; then
      : # 静默通过
    else
      report_fail "Namespace $NS 缺少 deny-all NetworkPolicy (租户未隔离!)"
    fi
  done
  report_pass "deny-all NetworkPolicy 检查完成 (失败项见上)"
else
  report_warn "kubectl 不可用, 跳过 NetworkPolicy 检查"
fi

# ---- 3. 检查 APIServer 是否禁用匿名访问 ----
echo "-- 3. APIServer 安全检查 --"
if has_kubectl; then
  # 检查匿名访问 (anonymous-auth 应为 false)
  ANON_CHECK=$("$KUBECTL_BIN" auth can-i --as=system:anonymous get pods 2>/dev/null || echo "yes")
  if [ "$ANON_CHECK" = "no" ]; then
    report_pass "匿名访问已禁用 (system:anonymous 无权限)"
  else
    report_fail "匿名访问未禁用! APIServer 需设置 --anonymous-auth=false"
  fi
  # 检查 AlwaysPullImages admission (通过检查 Pod imagePullPolicy 间接)
  # 检查 audit log 是否配置
  if "$KUBECTL_BIN" -n kube-system get configmap ske-apiserver-hardening >/dev/null 2>&1; then
    report_pass "APIServer 加固 ConfigMap 存在"
  else
    report_warn "APIServer 加固 ConfigMap 不存在 (见 manifests/apiserver-hardening.yaml)"
  fi
else
  report_warn "kubectl 不可用, 跳过 APIServer 检查"
fi

# ---- 4. 检查 kubelet 是否禁用 --allow-privileged ----
echo "-- 4. kubelet 安全检查 --"
if has_kubectl; then
  # 通过 Node status 检查 kubelet 配置
  KUBELET_PRIV=$("$KUBECTL_BIN" get --raw='/api/v1/nodes' 2>/dev/null \
    | grep -o '"allowPrivileged":[a-z]*' || echo "")
  if echo "$KUBELET_PRIV" | grep -q "false"; then
    report_pass "kubelet --allow-privileged=false"
  elif [ -z "$KUBELET_PRIV" ]; then
    report_warn "无法获取 kubelet allowPrivileged 配置 (K8s >= 1.15 已移除此选项, 由 admission 控制)"
  else
    report_fail "kubelet allowPrivileged=true (应由 PodSecurityPolicy/PSA 控制)"
  fi
else
  report_warn "kubectl 不可用, 跳过 kubelet 检查"
fi

# ---- 5. 检查 etcd 是否启用 TLS ----
echo "-- 5. etcd TLS 检查 --"
ETCD_CERT="/etc/kubernetes/pki/etcd/server.crt"
if [ -f "$ETCD_CERT" ]; then
  report_pass "etcd 证书文件存在 ($ETCD_CERT)"
  # 检查证书有效期
  if command -v openssl >/dev/null 2>&1; then
    EXPIRY=$(openssl x509 -in "$ETCD_CERT" -noout -enddate 2>/dev/null | cut -d= -f2 || echo "")
    if [ -n "$EXPIRY" ]; then
      EXPIRY_EPOCH=$(date -d "$EXPIRY" +%s 2>/dev/null || echo 0)
      NOW_EPOCH=$(date +%s)
      if [ "$EXPIRY_EPOCH" -gt 0 ]; then
        DAYS_LEFT=$(( (EXPIRY_EPOCH - NOW_EPOCH) / 86400 ))
        if [ "$DAYS_LEFT" -gt 30 ]; then
          report_pass "etcd 证书有效期剩余 ${DAYS_LEFT} 天"
        else
          report_warn "etcd 证书即将过期 (剩余 ${DAYS_LEFT} 天)"
        fi
      fi
    fi
  fi
else
  report_fail "etcd 证书不存在, TLS 可能未启用"
fi
# 检查 etcd 是否监听 HTTPS
if command -v ss >/dev/null 2>&1; then
  if ss -tlnp 2>/dev/null | grep -q ":2379"; then
    report_pass "etcd 监听 2379 端口"
  else
    report_warn "未检测到 etcd 2379 端口监听 (可能不是控制面节点)"
  fi
fi

# ---- 6. 检查 RBAC 是否启用 ----
echo "-- 6. RBAC 检查 --"
if has_kubectl; then
  # 检查 clusterrolebindings 是否存在
  CRB_COUNT=$("$KUBECTL_BIN" get clusterrolebindings -o jsonpath='{.items}' 2>/dev/null | grep -o '"kind"' | wc -l || echo 0)
  if [ "$CRB_COUNT" -gt 5 ]; then
    report_pass "RBAC 已启用 (ClusterRoleBinding 数量: $CRB_COUNT)"
  else
    report_fail "RBAC 可能未启用 (ClusterRoleBinding 数量过少)"
  fi
  # 检查是否有过度授权的 ClusterRoleBinding (system:masters 直接绑定)
  if "$KUBECTL_BIN" get clusterrolebinding -o json 2>/dev/null \
    | grep -q '"name":"system:masters"'; then
    report_warn "存在 system:masters 绑定 (特权组, 限制使用)"
  fi
else
  report_warn "kubectl 不可用, 跳过 RBAC 检查"
fi

# ---- 7. 检查 Secret 加密 (静态加密) ----
echo "-- 7. Secret 静态加密检查 --"
if has_kubectl; then
  # 创建测试 Secret 检查 etcd 中是否加密
  TEST_NS="ske-security-test-$$"
  "$KUBECTL_BIN" create namespace "$TEST_NS" >/dev/null 2>&1 || true
  "$KUBECTL_BIN" -n "$TEST_NS" create secret generic ske-enc-test --from-literal=key=testvalue >/dev/null 2>&1 || true
  if ETCDCTL_BIN="${ETCDCTL_BIN:-etcdctl}" command -v etcdctl >/dev/null 2>&1; then
    # 检查 etcd 中 Secret 是否明文 (需要 etcdctl 访问)
    ENC_CHECK=$("$ETCDCTL_BIN" --endpoints=https://127.0.0.1:2379 \
      --cacert=/etc/kubernetes/pki/etcd/ca.crt \
      --cert=/etc/kubernetes/pki/etcd/peer.crt \
      --key=/etc/kubernetes/pki/etcd/peer.key \
      get "/kubernetes/secrets/$TEST_NS/ske-enc-test" 2>/dev/null | grep -c "testvalue" || echo 0)
    if [ "$ENC_CHECK" -eq 0 ]; then
      report_pass "Secret 静态加密已启用 (etcd 中无明文)"
    else
      report_warn "Secret 可能未静态加密 (etcd 中检测到明文)"
    fi
  else
    report_warn "etcdctl 不可用, 无法验证 Secret 静态加密"
  fi
  "$KUBECTL_BIN" delete namespace "$TEST_NS" >/dev/null 2>&1 || true
else
  report_warn "kubectl 不可用, 跳过 Secret 加密检查"
fi

# ---- 报告汇总 ----
echo ""
echo "========================================"
echo "  安全检查报告汇总"
echo "========================================"
echo "  ${GREEN}通过: $PASS${NC}"
echo "  ${YELLOW}警告: $WARN${NC}"
echo "  ${RED}失败: $FAIL${NC}"
echo "========================================"
if [ "$FAIL" -gt 0 ]; then
  echo "  ${RED}存在安全风险! 请修复 FAIL 项${NC}"
  exit 1
elif [ "$WARN" -gt 0 ]; then
  echo "  ${YELLOW}存在警告项, 建议排查${NC}"
  exit 0
else
  echo "  ${GREEN}所有检查通过${NC}"
  exit 0
fi