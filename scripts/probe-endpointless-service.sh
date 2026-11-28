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
# v3（2026-11-28，第三轮补验）新增——v2 的 B 通道只查 `iptables-save -c -t nat`，
# 而 kube-proxy 对「无端点 Service」下的 REJECT 规则挂在 **filter** 表的
# KUBE-SERVICES 链 ⇒ 计数器恒为 status=counter_unavailable（台账 #35 盲区 d）。
# v3 的 B 通道改为「源 × 表」双维遍历（源：iptables-save/legacy-save/nft-save，
# 全空再回退 nft 原生；表：filter/nat），各源独立成行、跨源不相加，
# 命中组合里 KUBE-SERVICES 链定为 primary。另把宿主机视角也纳入计数器差值
# （v2 只在 Pod 视角分支取快照 ⇒ 宿主机视角的包从未被计入）。
# A 通道同步修：容器 PID 改为遍历 containerStatuses 选有 running pid 者，
# 并保留 nsenter 的 stderr 原因（v2 把失败原因吞成 reason=empty_or_error）。
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

# ────────────────────────────────────────────────────────────────────────────
# B 通道：kube-proxy 规则计数器（无端点 Service 的 REJECT 规则）
#
# v2 的缺陷（台账 #35 续九段盲区 d）：只用 `iptables-save -c -t nat` 取计数器，
# 而 kube-proxy 对「无端点 Service」下的 REJECT 规则挂在 **filter** 表的
# KUBE-SERVICES 链 ⇒ nat 表取不到 ⇒ 三臂恒为 status=counter_unavailable。
#
# v3 的取数契约：
#   源优先级: iptables-save > iptables-legacy-save > iptables-nft-save > nft
#   表优先级: filter > nat（kube-proxy 的 Service 规则只落这两张表）
#   每个「源 × 表」单独成快照文件；命中组合里 KUBE-SERVICES 链定为 primary，
#   避免 legacy / nft 两条路径暴露同一 ruleset 时重复计数（各源独立成行，
#   跨源绝不相加）。
#   iptables 系三源全空时，才回退到 nft 原生 ruleset（kube-proxy nftables 模式）。
#
# 本段用 BEGIN/END 标记圈定，供 scripts/selftest_probe_counter_parsing.sh
# 抽取后做本地夹具自测与变异对照。
# ────────────────────────────────────────────────────────────────────────────
# BEGIN_COUNTER_FUNCTIONS
COUNTER_SOURCES="iptables-save iptables-legacy-save iptables-nft-save"
COUNTER_TABLES="filter nat"

# 从 iptables-save -c 输出里挑出「含目标 IP 的规则行」。
# 边界匹配：10.43.0.5 不得误命中 10.43.0.50。
_iptables_rules_for_ip() {
  awk -v ip="$1" '
    BEGIN { ip_re = ip; gsub(/\./, "\\.", ip_re) }
    /^\[[0-9]+:[0-9]+\][[:space:]]+-A[[:space:]]/ {
      if ($0 ~ ("(^|[^0-9.])" ip_re "([^0-9.]|$)")) print
    }
  '
}

# 聚合快照文件里的计数器，按规则链分组输出 "chain pkts bytes lines"
_aggregate_counter_rows() {
  awk '
    /^\[[0-9]+:[0-9]+\][[:space:]]+-A[[:space:]]/ {
      cnt = substr($0, 2, index($0, "]") - 2)
      split(cnt, c, ":")
      cpk[$3] += c[1] + 0
      cby[$3] += c[2] + 0
      cln[$3]++
      if (!($3 in seen)) { seen[$3] = 1; order[++n] = $3 }
    }
    END {
      for (i = 1; i <= n; i++) {
        ch = order[i]
        printf "%s %d %d %d\n", ch, cpk[ch], cby[ch], cln[ch]
      }
    }
  ' "$1"
}

# nft 原生 ruleset → 归一化成 [pkts:bytes] -A CHAIN ... 形式，按表分文件
_nft_rules_for_ip() {
  local ip="$1" prefix="$2"
  local raw
  raw="$(sudo nft list ruleset 2>/dev/null || true)"
  [ -n "$raw" ] || return 1
  printf '%s\n' "$raw" | awk -v ip="$ip" -v prefix="$prefix" '
    function has_ip(s,   p, pre, post) {
      p = index(s, ip)
      while (p > 0) {
        pre = (p > 1) ? substr(s, p - 1, 1) : ""
        post = substr(s, p + length(ip), 1)
        if (pre !~ /^[0-9.]$/ && post !~ /^[0-9.]$/) return 1
        s = substr(s, p + length(ip))
        p = index(s, ip)
      }
      return 0
    }
    /^table[ \t]+/ { tbl = $3; next }
    /^[ \t]*chain[ \t]+/ {
      line = $0
      sub(/^[ \t]*chain[ \t]+/, "", line)
      sub(/[ \t]*\{.*$/, "", line)
      ch = line
      next
    }
    {
      if (tbl == "" || ch == "") next
      if (!has_ip($0)) next
      pk = 0; by = 0
      if (match($0, /counter packets [0-9]+ bytes [0-9]+/)) {
        seg = substr($0, RSTART, RLENGTH)
        split(seg, a, " ")
        pk = a[3] + 0
        by = a[5] + 0
      }
      out = prefix ".nft." tbl
      printf "[%d:%d] -A %s %s\n", pk, by, ch, $0 > out
      files[out] = 1
    }
    END { for (f in files) close(f) }
  '
  : > "${prefix}.nft.index"
  local f tbl
  for f in "${prefix}".nft.*; do
    [ -f "$f" ] || continue
    case "$f" in *.index) continue ;; esac
    tbl="${f##*.nft.}"
    if [ -s "$f" ]; then
      printf 'nft %s hit %s\n' "$tbl" "$f" >> "${prefix}.nft.index"
    else
      printf 'nft %s empty %s\n' "$tbl" "$f" >> "${prefix}.nft.index"
    fi
  done
  [ -s "${prefix}.nft.index" ]
}

# 快照：遍历「源 × 表」，命中则写快照文件并在 index 里登记；返回 0=至少一个组合命中
snapshot_rule_counters() {
  local ip="$1" prefix="$2"
  local s t raw out
  rm -f "${prefix}.index"
  : > "${prefix}.index"
  local hit=0
  for s in $COUNTER_SOURCES; do
    command -v "$s" >/dev/null 2>&1 || continue
    for t in $COUNTER_TABLES; do
      raw="$(sudo "$s" -c -t "$t" 2>/dev/null || true)"
      [ -n "$raw" ] || continue
      out="${prefix}.${s}.${t}"
      printf '%s\n' "$raw" | _iptables_rules_for_ip "$ip" > "$out"
      if [ -s "$out" ]; then
        hit=1
        printf '%s %s hit %s\n' "$s" "$t" "$out" >> "${prefix}.index"
      else
        printf '%s %s empty %s\n' "$s" "$t" "$out" >> "${prefix}.index"
      fi
    done
  done
  # iptables 系三源全空才回退 nft 原生（避免与 iptables 系重复计数同一 ruleset）
  if [ "$hit" -eq 0 ] && command -v nft >/dev/null 2>&1; then
    if _nft_rules_for_ip "$ip" "$prefix"; then
      local nsrc ntbl nstatus nfile
      while read -r nsrc ntbl nstatus nfile; do
        [ -n "$nsrc" ] || continue
        [ "$nstatus" = "hit" ] && hit=1
        printf '%s %s %s %s\n' "$nsrc" "$ntbl" "$nstatus" "$nfile" >> "${prefix}.index"
      done < "${prefix}.nft.index"
    fi
  fi
  [ "$hit" -eq 1 ]
}

# 读取某次快照的归一化行： "source table chain pkts bytes lines"
_collect_counter_rows() {
  local prefix="$1"
  local index_f="${prefix}.index"
  [ -s "$index_f" ] || return 0
  local src tbl status file chain pk by ln
  while read -r src tbl status file; do
    [ "$status" = "hit" ] || continue
    [ -f "$file" ] || continue
    while read -r chain pk by ln; do
      [ -n "$chain" ] || continue
      printf '%s %s %s %s %s %s\n' "$src" "$tbl" "$chain" "$pk" "$by" "$ln"
    done < <(_aggregate_counter_rows "$file")
  done < "$index_f"
}

# 在归一化行里查某个 (source, table, chain) 的 pkts/bytes；查不到返回 1
_lookup_counter_row() {
  local rows="$1" src="$2" tbl="$3" chain="$4"
  local s t c p b _l
  while read -r s t c p b _l; do
    [ -n "$s" ] || continue
    if [ "$s" = "$src" ] && [ "$t" = "$tbl" ] && [ "$c" = "$chain" ]; then
      printf '%s %s' "$p" "$b"
      return 0
    fi
  done <<< "$rows"
  return 1
}

# 输出计数器聚合；label 取 BEFORE / AFTER，view 取 host / pod
emit_rule_counters() {
  local ip="$1" prefix="$2" label="$3" view="$4"
  local rows
  rows="$(_collect_counter_rows "$prefix")"
  if [ -z "$rows" ]; then
    echo "RULE_COUNTER_${label} ip=${ip} view=${view} pkts=- bytes=- lines=0 status=not_found"
    return 0
  fi
  local primary_key="" first_combo="" chain_picked=0
  local src tbl chain pk by ln
  while read -r src tbl chain pk by ln; do
    [ -n "$src" ] || continue
    if [ -z "$primary_key" ]; then
      primary_key="${src}|${tbl}|${chain}"
      first_combo="${src}|${tbl}"
    elif [ "${src}|${tbl}" = "$first_combo" ] && [ "$chain" = "KUBE-SERVICES" ] && [ "$chain_picked" -eq 0 ]; then
      primary_key="${src}|${tbl}|${chain}"
      chain_picked=1
    fi
  done <<< "$rows"
  while read -r src tbl chain pk by ln; do
    [ -n "$src" ] || continue
    local primary=0
    if [ "${src}|${tbl}|${chain}" = "$primary_key" ]; then
      primary=1
    fi
    echo "RULE_COUNTER_${label} ip=${ip} view=${view} source=${src} table=${tbl} chain=${chain} pkts=${pk} bytes=${by} lines=${ln} primary=${primary}"
  done <<< "$rows"
}

# 输出 BEFORE/AFTER 增量（单独成函数，便于本地自测）
emit_rule_counter_delta() {
  local ip="$1" bprefix="$2" aprefix="$3" view="$4"
  local brows arows
  brows="$(_collect_counter_rows "$bprefix")"
  arows="$(_collect_counter_rows "$aprefix")"
  if [ -z "$brows" ] || [ -z "$arows" ]; then
    echo "RULE_COUNTER_DELTA ip=${ip} view=${view} pkts=- bytes=- status=counter_unavailable"
    return 0
  fi
  local primary_key="" first_combo="" chain_picked=0
  local bsrc btbl bchain bpk bby _bln
  while read -r bsrc btbl bchain bpk bby _bln; do
    [ -n "$bsrc" ] || continue
    if [ -z "$primary_key" ]; then
      primary_key="${bsrc}|${btbl}|${bchain}"
      first_combo="${bsrc}|${btbl}"
    elif [ "${bsrc}|${btbl}" = "$first_combo" ] && [ "$bchain" = "KUBE-SERVICES" ] && [ "$chain_picked" -eq 0 ]; then
      primary_key="${bsrc}|${btbl}|${bchain}"
      chain_picked=1
    fi
  done <<< "$brows"
  local any=0 missing=0 apk_aby="" apk aby primary
  while read -r bsrc btbl bchain bpk bby _bln; do
    [ -n "$bsrc" ] || continue
    if ! apk_aby="$(_lookup_counter_row "$arows" "$bsrc" "$btbl" "$bchain")"; then
      echo "RULE_COUNTER_DELTA ip=${ip} view=${view} source=${bsrc} table=${btbl} chain=${bchain} pkts=- bytes=- status=counter_missing_after"
      missing=1
      continue
    fi
    apk="${apk_aby%% *}"
    aby="${apk_aby##* }"
    primary=0
    if [ "${bsrc}|${btbl}|${bchain}" = "$primary_key" ]; then
      primary=1
    fi
    echo "RULE_COUNTER_DELTA ip=${ip} view=${view} source=${bsrc} table=${btbl} chain=${bchain} pkts=$((apk - bpk)) bytes=$((aby - bby)) primary=${primary}"
    any=1
  done <<< "$brows"
  if [ "$any" -eq 0 ]; then
    echo "RULE_COUNTER_DELTA ip=${ip} view=${view} pkts=- bytes=- status=counter_unavailable"
  elif [ "$missing" -eq 1 ]; then
    echo "RULE_COUNTER_DELTA ip=${ip} view=${view} status=partial_mismatch note=部分规则在 after 快照中缺失"
  fi
}
# END_COUNTER_FUNCTIONS

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
  # 选「有 running pid」的容器：containerStatuses[0] 可能不是 running 的那个
  # （v2 取 [0].state.running.pid ⇒ 常见 pid=none，且错误原因被 2>/dev/null 吞掉）
  pids="$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{range .status.containerStatuses[*]}{.state.running.pid}{" "}{end}' 2>/dev/null || true)"
  pid=""
  for p in $pids; do
    if [ -n "$p" ] && [ "$p" != "0" ]; then
      pid="$p"
      break
    fi
  done
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
    # stderr 单独落盘再回显：v2 用 2>/dev/null 把失败原因吞掉，四源全
    # reason=empty_or_error，无法区分「nsenter 失败」与「工具在该 netns 不可用」
    errf="${CAP_DIR}/netns_err_${app}_$(printf '%s' "$dump" | tr ' ' '_').txt"
    nsout="$(sudo nsenter $args -- $dump 2>"$errf" || true)"
    nserr="$(head -2 "$errf" 2>/dev/null | tr '\n' ' ' | cut -c1-200)"
    if [ -z "$nsout" ]; then
      echo "POD_NETNS_DUMP_FAILED pod=${app} source=${dump} reason=empty_or_error stderr=${nserr:-none}"
      continue
    fi
    out="$nsout"
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
  # B. 宿主机视角计数器：探针前后各取一次快照，取差值
  # （v2 只在 Pod 视角分支里取快照 ⇒ 宿主机视角的包从未被计入；v3 两侧各取一次）
  snapshot_rule_counters "$ip" "$CAP_DIR/host_before_${name}" || true
  python3 "$PROBE_PY" "$ip" "$port" "$TIMEOUT_S" || true
  snapshot_rule_counters "$ip" "$CAP_DIR/host_after_${name}" || true
  emit_rule_counters "$ip" "$CAP_DIR/host_before_${name}" BEFORE host
  emit_rule_counters "$ip" "$CAP_DIR/host_after_${name}" AFTER host
  emit_rule_counter_delta "$ip" "$CAP_DIR/host_before_${name}" "$CAP_DIR/host_after_${name}" host
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
      # B. Pod 视角计数器：探针前后各取一次快照，取差值
      snapshot_rule_counters "$ip" "$CAP_DIR/pod_before_${name}" || true
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
      snapshot_rule_counters "$ip" "$CAP_DIR/pod_after_${name}" || true
      emit_rule_counters "$ip" "$CAP_DIR/pod_before_${name}" BEFORE pod
      emit_rule_counters "$ip" "$CAP_DIR/pod_after_${name}" AFTER pod
      emit_rule_counter_delta "$ip" "$CAP_DIR/pod_before_${name}" "$CAP_DIR/pod_after_${name}" pod
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
