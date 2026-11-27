#!/usr/bin/env bash
# 采集编排层与 kube-proxy 日志，为「链路2 红」的**中间链路**取同轮直证。
#
# 为什么需要它（台账 #35 遗留盲区）：
#   行为层已直证（Pod 视角连无端点 Service：v1.36.5 快速拒绝 / v1.36.6+ 10s 挂起），
#   链路层已直证（链路2 红、断言为 infra-orchestrator 读超时）。但中间那一跳——
#   「编排器 Pod 调无端点 provider 时是否真的被挂住」——此前只有源码与 manifest 推断。
#   本脚本把编排器运行日志打出来，若出现 provider 调用失败且 reason 为超时类，
#   即为该中间环节的同轮直证；否则该中间环节判为未闭合。
#
# 用法: bash scripts/collect-orchestrator-logs.sh [namespace]
# 环境变量: LOG_TAIL（取日志尾部行数，默认 800）
set -uo pipefail

NS="${1:-${K3S_NAMESPACE:-shuqing}}"
LOG_TAIL="${LOG_TAIL:-800}"

echo "=== 编排层日志采集 ==="
echo "namespace=${NS} tail=${LOG_TAIL}"

TMP="$(mktemp /tmp/orch-log-XXXXXX 2>/dev/null || echo /tmp/orch-log-fallback)"
kubectl -n "$NS" logs deploy/infra-orchestrator --tail="$LOG_TAIL" > "$TMP" 2>&1 || true

echo "--- ORCH_LOG_BEGIN ---"
cat "$TMP" 2>/dev/null || true
echo "--- ORCH_LOG_END ---"

echo "--- ORCH_LOG_MARKERS ---"
# 已知源码串（SupplyOrchestrator.listClusters 的 fail-soft 分支 + 超时类异常）
grep -nE "listClusters: provider 调用失败|listClusters failed for env|ConnectTimeout|TimeoutException|ReadTimeout|Connection refused|No endpoints|provider-selected" "$TMP" 2>/dev/null | head -80 \
  || echo "ORCH_LOG_NO_MARKER"

echo "--- ORCH_LOG_COUNTS ---"
echo "ORCH_LOG_COUNT provider_call_failed=$(grep -c 'listClusters: provider 调用失败' "$TMP" 2>/dev/null || echo 0)"
echo "ORCH_LOG_COUNT timeout_class=$(grep -cE 'ConnectTimeout|TimeoutException|ReadTimeout|Timeout on blocking read' "$TMP" 2>/dev/null || echo 0)"
echo "ORCH_LOG_COUNT connection_refused=$(grep -c 'Connection refused' "$TMP" 2>/dev/null || echo 0)"

echo "--- ENDPOINTS_SNAPSHOT ---"
kubectl -n "$NS" get endpoints -o wide 2>/dev/null || echo "ENDPOINTS_LIST_FAILED"
kubectl -n "$NS" get endpoints -o jsonpath='{range .items[*]}{.metadata.name}{" subsets="}{.subsets}{"\n"}{end}' 2>/dev/null || true

echo "--- SERVICE_SNAPSHOT ---"
kubectl -n "$NS" get svc -o wide 2>/dev/null | head -40 || true

echo "--- POD_SNAPSHOT ---"
kubectl -n "$NS" get pods -o wide 2>/dev/null | head -40 || true

echo "--- KUBE_PROXY_MODE ---"
kubectl -n kube-system logs -l k8s-app=kube-proxy --tail=300 2>/dev/null \
  | grep -iE "proxy mode|iptables|nftables" | head -20 \
  || echo "NO_KUBE_PROXY_MODE_LOG"

echo "=== 编排层日志采集结束 ==="
exit 0
