#!/usr/bin/env bash
# 探测「无 Endpoints 的 Service」在当前 k3s 下的 ClusterIP 访问行为
#
# 为什么需要它（台账 #35 遗留盲区）：
#   台账写的是「v1.36.5+k3s1 下无端点 Service 走 kube-proxy REJECT 快速失败，
#   v1.37.2+k3s1 下同现象变 10s 读超时挂起」——两者都是**推断**，从未在真集群
#   同时对照观测过。本脚本在同一集群里对同一类对象取硬证据：
#   ① 受控无端点 Service（probe-empty-svc，selector 永不匹配 ⇒ Endpoints 必空）
#   ② 仓内真实无端点 Service（未构建组件的 Service：Pod 停在 ImagePullBackOff ⇒ 无 Endpoints）
#   每个目标测两个视角（宿主机 / Pod 内），再导出 kube-proxy 的 iptables 规则，
#   让 REJECT 与 DROP 的差异有规则级证据，而不是只靠耗时反推。
#
# v2（2026-11-28，第二轮补验）新增——第一轮只拿到「宿主机 netns 的规则文本」，
# 三臂规则同文 ⇒ 无法区分两臂，机制层按 C2 判为未闭合。v2 补三条判别通道：
#   A. Pod 网络命名空间内的规则（预期为空：kube-proxy 规则在 host netns；空=确认性证据）
#   B. 宿主机 nat 表规则的**计数器增量**（探针前后快照之差 ⇒ 包是否真的到达该规则）
#   C. 帧级 ICMP 抓包（host 侧 + Pod netns 侧）⇒ 「host 是否发出 ICMP 错误」与
#      「Pod 是否收到 ICMP 错误」分开取证，区分「规则不同」与「ICMP 回送路径不同」
#
# 用法: bash scripts/probe-endpointless-service.sh [namespace]
# 环境变量: PROBE_TIMEOUT_S（单次连接超时秒数，默认 10，对齐测试侧 DEFAULT_TIMEOUT）
#           CAP_TTL_S（抓包窗口秒数，默认 18）
set -uo pipefail

NS="${1:-${K3S_NAMESPACE:-shuqing}}"
TIMEOUT_S="${PROBE_TIMEOUT_S:-10}"
CAP_TTL_S="${CAP_TTL_S:-18}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE_PY="${SCRIPT_DIR}/probe_endpointless_service.py"
CAP_DIR="$(mktemp -d /tmp/probe-cap-XXXXXX 2>/dev/null || echo /tmp/probe-cap-fallback)"

echo "=== 无端点 Service 行为探针 ==="
echo "namespace=${NS} timeout_s=${TIMEOUT_S} cap_ttl_s=${CAP_TTL_S}"
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

CTRL_IP="$(kubectl -n "$NS" get svc probe-empty-svc -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
echo "CTRL_SERVICE name=probe-empty-svc ip=${CTRL_IP:-none}"

# 帧级取证依赖 tcpdump：runner 镜像通常自带；没有则尽力装一次，装不上就显式声明盲区
if command -v tcpdump >/dev/null 2>&1; then
  echo "TCPDUMP_AVAILABLE yes"
else
  echo "TCPDUMP_INSTALL_ATTEMPT"
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq tcpdump >/dev/null 2>&1 || true
  if command -v tcpdump >/dev/null 2>&1; then
    echo "TCPDUMP_AVAILABLE yes_after_install"
  else
    echo "TCPDUMP_AVAILABLE no"
  fi
fi

# 预注册预期（防误读）：kube-proxy 规则挂在 host netns，Pod netns 无 KUBE-SERVICES 规则属预期。
echo "POD_NETNS_EXPECTATION note=kube-proxy 规则位于 host netns；Pod netns 无 KUBE-SERVICES 规则属预期，空结果仅作确认性证据"

# ────────────────────────────────────────────────────────────────────────────
# 工具函数
# ────────────────────────────────────────────────────────────────────────────

# 为某个 Pod 找到进入其网络命名空间的 nsenter 参数；输出 "--target PID --net" 或 "--net=PATH" 或空
nsenter_args_for_pod() {
  local pid="$1" pip="$2" f
  if [ -n "$pid" ] && [ "$pid" != "0" ] && sudo nsenter --target "$pid" --net -- true >/dev/null 2>&1; then
    echo "--target $pid --net"
    return 0
  fi
  for f in /var/run/netns/*; do
    [ -e "$f" ] || continue
    if sudo nsenter --net="$f" -- ip -4 addr show 2>/dev/null | grep -qF "$pip"; then
      echo "--net=$f"
      return 0
    fi
  done
  echo ""
}

# 宿主机 nat 表里命中「无端点 Service」规则的快照（含计数器）；写文件，返回 0=命中
snapshot_nat_counters() {
  local ip="$1" out="$2" s raw
  : > "$out"
  for s in iptables-save iptables-nft-save; do
    command -v "$s" >/dev/null 2>&1 || continue
    raw="$(sudo "$s" -c -t nat 2>/dev/null || true)"
    [ -n "$raw" ] || continue
    printf '%s\n' "$raw" | grep -F "KUBE-SERVICES" | grep -F "$ip" | grep -F "has no endpoints" > "$out" && return 0
    # 退路：注释措辞变化时，只要同链同 IP 也算命中
    printf '%s\n' "$raw" | grep -F "KUBE-SERVICES" | grep -F "$ip" > "$out" && return 0
  done
  return 1
}

# 输出计数器聚合；label 取 BEFORE / AFTER
emit_rule_counters() {
  local ip="$1" f="$2" label="$3" agg pk by nlines
  if [ ! -s "$f" ]; then
    echo "RULE_COUNTER_${label} ip=${ip} pkts=- bytes=- lines=0 status=not_found"
    return 0
  fi
  agg="$(awk '{ s=$0; sub(/^\[/,"",s); p=substr(s,1,index(s,"]")-1); split(p,a,":"); pk+=a[1]+0; by+=a[2]+0; n++ } END { printf "%d %d %d", pk, by, n }' "$f")"
  read -r pk by nlines <<< "$agg"
  echo "RULE_COUNTER_${label} ip=${ip} pkts=${pk} bytes=${by} lines=${nlines}"
  head -5 "$f" | while IFS= read -r line; do
    echo "RULE_COUNTER_RAW label=${label} ip=${ip} raw=${line}"
  done
}

# 输出 BEFORE/AFTER 快照与增量（单独成函数，便于本地自测）
emit_rule_counter_delta() {
  local ip="$1" bf="$2" af="$3" before_line after_line bpk apk bby aby
  before_line="$(emit_rule_counters "$ip" "$bf" BEFORE)"
  after_line="$(emit_rule_counters "$ip" "$af" AFTER)"
  printf '%s\n' "$before_line"
  printf '%s\n' "$after_line"
  bpk="$(printf '%s\n' "$before_line" | awk -F'pkts=' '/^RULE_COUNTER_BEFORE/ {split($2,a," "); print a[1]}')"
  apk="$(printf '%s\n' "$after_line" | awk -F'pkts=' '/^RULE_COUNTER_AFTER/ {split($2,a," "); print a[1]}')"
  bby="$(printf '%s\n' "$before_line" | awk -F'bytes=' '/^RULE_COUNTER_BEFORE/ {split($2,a," "); print a[1]}')"
  aby="$(printf '%s\n' "$after_line" | awk -F'bytes=' '/^RULE_COUNTER_AFTER/ {split($2,a," "); print a[1]}')"
  if [ -n "$bpk" ] && [ -n "$apk" ] && [ "$bpk" != "-" ] && [ "$apk" != "-" ]; then
    echo "RULE_COUNTER_DELTA ip=${ip} pkts=$((apk - bpk)) bytes=$((aby - bby))"
  else
    echo "RULE_COUNTER_DELTA ip=${ip} pkts=- bytes=- status=counter_unavailable"
  fi
}

# 启动抓包；输出 PID 到全局 CAP_PID
CAP_PID=""
start_capture() {
  local scope="$1" filt="$2" out="$3"
  if ! command -v tcpdump >/dev/null 2>&1; then
    echo "ICMP_CAPTURE_UNAVAILABLE scope=${scope} reason=tcpdump_missing"
    CAP_PID=""
    return 1
  fi
  sudo timeout "$CAP_TTL_S" tcpdump -i any -n -l -U "$filt" > "$out" 2>&1 &
  CAP_PID=$!
  echo "ICMP_CAPTURE_BEGIN scope=${scope} ttl_s=${CAP_TTL_S} filter=${filt}"
  return 0
}

# 等待抓包结束并输出统计
finish_capture() {
  local scope="$1" pid="$2" f="$3" total icm syn
  if [ -z "$pid" ]; then
    return 0
  fi
  wait "$pid" 2>/dev/null || true
  echo "ICMP_CAPTURE_END scope=${scope}"
  if [ ! -f "$f" ]; then
    echo "ICMP_CAPTURE_SUMMARY scope=${scope} total_lines=- icmp_lines=- syn_lines=- status=file_missing"
    return 0
  fi
  total="$(wc -l < "$f" | tr -d ' ')"
  icm="$(grep -ci 'icmp' "$f" || true)"
  syn="$(grep -c 'Flags \[S\]' "$f" || true)"
  echo "ICMP_CAPTURE_SUMMARY scope=${scope} total_lines=${total} icmp_lines=${icm} syn_lines=${syn}"
  head -40 "$f" | while IFS= read -r line; do
    echo "ICMP_CAPTURE_LINE scope=${scope} raw=${line}"
  done
}

# ────────────────────────────────────────────────────────────────────────────
# A. Pod 网络命名空间内的规则（预期为空；空=确认性证据）
# ────────────────────────────────────────────────────────────────────────────
KE_NSENTER_ARGS=""
KE_POD_IP=""
for app in knowledge-engine infra-orchestrator; do
  pod="$(kubectl -n "$NS" get pod -l "app=$app" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [ -z "$pod" ]; then
    echo "POD_NETNS_UNRESOLVED pod=${app} reason=pod_not_found"
    continue
  fi
  pip="$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.status.podIP}' 2>/dev/null || true)"
  pid="$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.status.containerStatuses[0].state.running.pid}' 2>/dev/null || true)"
  args="$(nsenter_args_for_pod "$pid" "$pip")"
  if [ -z "$args" ]; then
    echo "POD_NETNS_UNRESOLVED pod=${app} pod_name=${pod} pid=${pid:-none} reason=nsenter_unavailable"
    continue
  fi
  case "$args" in
    --target*) mode="pid" ;;
    *) mode="netns_path" ;;
  esac
  echo "POD_NETNS_POD pod=${app} pod_name=${pod} ip=${pip:-none} pid=${pid:-none} mode=${mode}"
  if [ "$app" = "knowledge-engine" ]; then
    KE_NSENTER_ARGS="$args"
    KE_POD_IP="$pip"
  fi
  for dump in "iptables-save" "iptables-legacy-save" "iptables-nft-save" "nft list ruleset"; do
    bin="${dump%% *}"
    if ! command -v "$bin" >/dev/null 2>&1; then
      echo "POD_NETNS_TOOL_UNAVAILABLE pod=${app} source=${dump}"
      continue
    fi
    out="$(sudo nsenter $args -- $dump 2>/dev/null || true)"
    if [ -z "$out" ]; then
      echo "POD_NETNS_DUMP_FAILED pod=${app} source=${dump} reason=empty_or_error"
      continue
    fi
    if [ -n "$CTRL_IP" ] && printf '%s\n' "$out" | grep -qF "$CTRL_IP"; then
      echo "POD_NETNS_RULE_HIT pod=${app} ip=${CTRL_IP} source=${dump}"
      printf '%s\n' "$out" | grep -F "$CTRL_IP" | head -5 | while IFS= read -r line; do
        echo "POD_NETNS_RULE_RAW pod=${app} source=${dump} raw=${line}"
      done
    else
      echo "POD_NETNS_NO_RULE pod=${app} ip=${CTRL_IP:-none} source=${dump}"
      if printf '%s\n' "$out" | grep -qF "KUBE-SERVICES"; then
        echo "POD_NETNS_KUBE_CHAIN_PRESENT pod=${app} source=${dump} note=Pod netns 存在 KUBE-SERVICES 链，与预期不符，需单独说明"
      fi
    fi
  done
done

# ────────────────────────────────────────────────────────────────────────────
# 目标清单
# ────────────────────────────────────────────────────────────────────────────
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

first_target=1
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
      # B. 规则计数器：探针前后各取一次快照，取差值
      snapshot_nat_counters "$ip" "$CAP_DIR/before_${name}.nat" || true
      # C. 帧级抓包：仅对首个目标抓，避免重复占用时间
      host_pid=""
      podns_pid=""
      if [ "$first_target" -eq 1 ]; then
        if command -v tcpdump >/dev/null 2>&1; then
          start_capture host "icmp or host ${KE_POD_IP}" "$CAP_DIR/host.cap"
          host_pid="$CAP_PID"
          if [ -n "$KE_NSENTER_ARGS" ]; then
            # timeout 必须放在 nsenter 内侧：timeout 杀的是 nsenter 的话，
            # 内层 tcpdump 会变成孤儿进程继续抓，污染后续日志。
            sudo nsenter $KE_NSENTER_ARGS -- timeout "$CAP_TTL_S" tcpdump -i any -n -l -U "icmp" \
              > "$CAP_DIR/podns.cap" 2>&1 &
            podns_pid=$!
            echo "ICMP_CAPTURE_BEGIN scope=podns ttl_s=${CAP_TTL_S} filter=icmp"
          else
            echo "ICMP_CAPTURE_UNAVAILABLE scope=podns reason=pod_netns_unresolved"
          fi
        else
          echo "ICMP_CAPTURE_UNAVAILABLE scope=host reason=tcpdump_missing"
          echo "ICMP_CAPTURE_UNAVAILABLE scope=podns reason=tcpdump_missing"
        fi
      fi
      kubectl -n "$NS" exec -i "$pod_name" -- "$interp" - "$ip" "$port" "$TIMEOUT_S" \
        < "$PROBE_PY" || echo "POD_PROBE_FAILED pod=${pod_name}"
      if [ "$first_target" -eq 1 ]; then
        finish_capture host "$host_pid" "$CAP_DIR/host.cap"
        finish_capture podns "$podns_pid" "$CAP_DIR/podns.cap"
      fi
      snapshot_nat_counters "$ip" "$CAP_DIR/after_${name}.nat" || true
      emit_rule_counter_delta "$ip" "$CAP_DIR/before_${name}.nat" "$CAP_DIR/after_${name}.nat"
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
  first_target=0
done

echo "=== Endpoints 快照（证明目标确实无端点）==="
kubectl -n "$NS" get endpoints -o wide 2>/dev/null || echo "ENDPOINTS_LIST_FAILED"
kubectl -n "$NS" get endpoints -o jsonpath='{range .items[*]}{.metadata.name}{" subsets="}{.subsets}{"\n"}{end}' 2>/dev/null || true

echo "=== kube-proxy 运行模式取证 ==="
kubectl -n kube-system get pods -l k8s-app=kube-proxy -o wide 2>/dev/null || echo "NO_KUBE_PROXY_POD"
kubectl -n kube-system logs -l k8s-app=kube-proxy --tail=200 2>/dev/null \
  | grep -iE "proxy mode|iptables|nftables|Using" | head -20 \
  || echo "NO_KUBE_PROXY_MODE_LOG"

echo "=== 探针结束 ==="
exit 0
