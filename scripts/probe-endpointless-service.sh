#!/usr/bin/env bash
# 探测「无 Endpoints 的 Service」在当前 k3s 下的 ClusterIP 访问行为
#
# 为什么需要它（台账 #35 续八遗留盲区）：
#   台账写的是「v1.36.5+k3s1 下无端点 Service 走 kube-proxy REJECT 快速失败，
#   v1.37.2+k3s1 下同现象变 10s 读超时挂起」——两者都是**推断**，从未在真集群
#   同时对照观测过。本脚本在同一集群里对同一类对象取硬证据：
#   ① 受控无端点 Service（probe-empty-svc，selector 永不匹配 ⇒ Endpoints 必空）
#   ② 仓内真实无端点 Service（未构建组件的 Service：Pod 停在 ImagePullBackOff ⇒ 无 Endpoints）
#   每个目标测两个视角（宿主机 / Pod 内），再导出 kube-proxy 的 iptables 规则，
#   让 REJECT 与 DROP 的差异有规则级证据，而不是只靠耗时反推。
#
# 用法: bash scripts/probe-endpointless-service.sh [namespace]
# 环境变量: PROBE_TIMEOUT_S（单次连接超时秒数，默认 10，对齐测试侧 DEFAULT_TIMEOUT）
set -uo pipefail

NS="${1:-${K3S_NAMESPACE:-shuqing}}"
TIMEOUT_S="${PROBE_TIMEOUT_S:-10}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE_PY="${SCRIPT_DIR}/probe_endpointless_service.py"

echo "=== 无端点 Service 行为探针 ==="
echo "namespace=${NS} timeout_s=${TIMEOUT_S}"
k3s --version 2>/dev/null | head -1 || true
kubectl version --client 2>/dev/null | head -1 || true

# ① 受控无端点 Service：selector 指向一个永不匹配的 label
kubectl -n "$NS" apply -f - >/dev/null <<'YAML'
apiVersion: v1
kind: Service
metadata:
  name: probe-empty-svc
  labels:
    app: probe-empty-svc
spec:
  selector:
    app: probe-empty-never-match
  ports:
    - name: http
      port: 8080
      targetPort: 8080
YAML
# 等 kube-proxy 把新 Service 的规则同步进 iptables（否则会测到"规则还没建"的假阴性）
sleep 5

targets=""
add_target() {
  name="$1"
  port="$2"
  ip="$(kubectl -n "$NS" get svc "$name" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
  if [ -z "$ip" ] || [ "$ip" = "None" ]; then
    echo "SKIP_TARGET name=${name} reason=service_not_found"
    return 0
  fi
  subsets="$(kubectl -n "$NS" get endpoints "$name" -o jsonpath='{.subsets}' 2>/dev/null || true)"
  echo "TARGET name=${name} ip=${ip} port=${port} endpoints_subsets=[${subsets}]"
  targets="${targets} ${name}:${ip}:${port}"
}

add_target probe-empty-svc 8080
add_target llm-gateway 8084
add_target infra-provider-cloud 8084
add_target infra-provider-private 8084
add_target catalog 8082

# Pod 视角用 knowledge-engine（链路2 的调用方之一，镜像自带 python3）
pod_name="$(kubectl -n "$NS" get pod -l app=knowledge-engine -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
echo "POD_VIEW_TARGET pod=${pod_name:-none}"

for t in $targets; do
  name="${t%%:*}"
  rest="${t#*:}"
  ip="${rest%%:*}"
  port="${rest##*:}"
  echo "--- 探针 ${name} (${ip}:${port}) 宿主机视角 ---"
  python3 "$PROBE_PY" "$ip" "$port" "$TIMEOUT_S" || true
  echo "--- 探针 ${name} (${ip}:${port}) Pod 内视角 ---"
  if [ -n "$pod_name" ]; then
    interp=""
    for cand in python3 python; do
      if kubectl -n "$NS" exec "$pod_name" -- sh -c "command -v ${cand}" >/dev/null 2>&1; then
        interp="$cand"
        break
      fi
    done
    if [ -n "$interp" ]; then
      kubectl -n "$NS" exec -i "$pod_name" -- "$interp" - "$ip" "$port" "$TIMEOUT_S" \
        < "$PROBE_PY" || echo "POD_PROBE_FAILED pod=${pod_name}"
    else
      echo "SKIP_POD_VIEW reason=no_python_in_pod"
    fi
  else
    echo "SKIP_POD_VIEW reason=no_knowledge_engine_pod"
  fi
  echo "--- kube-proxy 规则（${ip}）---"
  # 不能只看 iptables-save：k3s 新版 kube-proxy 可能跑 nftables 模式，
  # 此时 iptables-save 为空会造成「无规则 ⇒ DROP」的假阴性。四种来源全取。
  found=0
  for dump in "iptables-save" "iptables-legacy-save" "iptables-nft-save" "nft list ruleset"; do
    if command -v "${dump%% *}" >/dev/null 2>&1; then
      out="$(sudo ${dump} 2>/dev/null | grep -F "$ip" || true)"
      if [ -n "$out" ]; then
        found=1
        echo "RULE_HIT source=${dump} ip=${ip}"
        echo "$out" | head -20
      fi
    fi
  done
  if [ "$found" -eq 0 ]; then
    echo "NO_RULE_FOUND ip=${ip} (iptables-save/legacy/nft/nftables 四源均未命中)"
  fi
done

echo "=== kube-proxy 运行模式取证 ==="
kubectl -n kube-system get pods -l k8s-app=kube-proxy -o wide 2>/dev/null || echo "NO_KUBE_PROXY_POD"
kubectl -n kube-system logs -l k8s-app=kube-proxy --tail=200 2>/dev/null \
  | grep -iE "proxy mode|iptables|nftables|Using" | head -20 \
  || echo "NO_KUBE_PROXY_MODE_LOG"

echo "=== 探针结束 ==="
