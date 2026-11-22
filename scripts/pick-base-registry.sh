#!/usr/bin/env bash
# 选 base 镜像源：按可达性在候选里择一，把宿主名（不含 /library）打到 stdout。
#
# 为什么要有这个脚本（台账 #72）：Dockerfile 里的 FROM 全部钉死 docker.m.daocloud.io，
# 加速器一抖（本轮实测：/v2/ 回 502、CI 里是 net/http: TLS handshake timeout）就有
# 6 条非必需腿反复红，而工作流里没有任何地方能切源——只能改文件。
#
# 语义约定：**只返回宿主**（如 docker.m.daocloud.io / docker.io），路径由 Dockerfile 自己写全
# （官方镜像是 `${BASE_REGISTRY}/library/<名>`）。原因：官方镜像在 Docker Hub 的规范路径就是
# library/*，宿主后直接跟名字不是规范形态。
#
# 用法：
#   BASE_REGISTRY="$(bash scripts/pick-base-registry.sh)"     # 供 --build-arg 用
#   bash scripts/pick-base-registry.sh --self-test            # 离线自测（不碰网络）
#
# 环境变量：
#   BASE_REGISTRY  显式指定则原样返回，不探测（本地/一次性覆盖用）
#   BASE_REGISTRY_TIMEOUT  单次探测超时秒数，默认 8
#   BASE_REGISTRY_CANDIDATES  覆盖候选序列（空格分隔），便于本地试验
#   BASE_REGISTRY_TAG_PROBE  tag 级探测用的「镜像:tag」（默认 library/alpine:3.20）；
#                     置空则跳过 tag 级探测，退回只探 /v2/ 的旧行为
#   BASE_REGISTRY_TAG_PROBE_CMD  自测注入点：给 tag 级探测返回一个 HTTP 状态码
set -uo pipefail

CANDIDATES_DEFAULT=('docker.m.daocloud.io' 'docker.io')
# 全部候选都探不通时取它：加速器只是便利层，上游才是真源
FALLBACK='docker.io'
TIMEOUT="${BASE_REGISTRY_TIMEOUT:-8}"
# tag 级探测的代表镜像：alpine 运行层是全仓 Dockerfile 里最高频的一类，且匿名就能问出
# 这个加速器此刻能不能把一次真拉取走完。置空即关闭这层探测。
TAG_PROBE="${BASE_REGISTRY_TAG_PROBE:-library/alpine:3.20}"
# tag 级 manifest 探测实现与 OK 码表已抽到 scripts/lib/registry-probe.sh
# （台账 #116：与 scripts/check-pinned-downloads.sh 的预检共用同一份口径）
# ⇒ 这里**不再另写一份**探测逻辑，改水位/改口径只改那一个文件。
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)/registry-probe.sh"

log() { echo "[pick-base-registry] $*" >&2; }

# 探活用的 URL。docker.io 这个宿主名**不能直接按 https://docker.io/v2/ 探**：
# 那是官网域名，回的是 302（跳 hub.docker.com），永远进不了"200/401 算可用"的判据。
# 实测 2026-10-21 CI job `119158037486`：`候选 docker.io 不可用（HTTP 302）` +
# `警告：所有候选都不可用` —— 也就是说这轮 CI 里上游候选从来没被真正探活过，
# 全靠 main() 末尾的 fail-soft 恰好也返回 docker.io 才没出事。
# Docker Hub 的 registry API 在 registry-1.docker.io，它回 401 才是"活着且在要 token"的正常形态；
# 返回给构建用的宿主名仍保持 docker.io（FROM 认它，且与 Dockerfile 里的 library/ 路径相配）。
probe_url() {
  case "$1" in
    docker.io | registry-1.docker.io | index.docker.io)
      printf 'https://registry-1.docker.io/v2/\n' ;;
    *) printf 'https://%s/v2/\n' "$1" ;;
  esac
}

# 探一次 /v2/。返回 200 或 401 视为"可用"——401 恰恰说明 registry 活着、在要 token；
# 超时(000)、5xx、连接拒绝一律视为不可用。
probe() {
  local host="$1" url code
  url="$(probe_url "$host")"
  if [ -n "${BASE_REGISTRY_PROBE_CMD:-}" ]; then
    # 自测注入点：外部命令负责给一个 HTTP 状态码（或空串模拟彻底取不到）
    code="$($BASE_REGISTRY_PROBE_CMD "$host" 2>/dev/null || true)"
  else
    code="$(curl -s -o /dev/null -m "$TIMEOUT" -w '%{http_code}' "$url" 2>/dev/null || true)"
  fi
  case "$code" in
    200|401)
      # 成功路径也要留痕（台账 #88）：此前只有失败分支打印，于是 CI 日志里
      # 「选了哪个源」之外什么也看不到——既不知道探了哪些候选，也不知道回的是哪个码。
      log "候选 $host 探活通过（$url 回 HTTP $code）"
      return 0 ;;
    *) log "候选 $host 不可用（$url 回 HTTP ${code:-空}）"; return 1 ;;
  esac
}

# tag 级 manifest 的探测 URL 与探测实现都在共用的 scripts/lib/registry-probe.sh 里
# （台账 #116）⇒ 这里只留薄封装，别把实现抄回来。
tag_probe_url() {
  registry_tag_probe_url "$@"
}

probe_tag() {
  local host="$1" url code
  if [ -z "$TAG_PROBE" ]; then
    log "候选 $host 跳过 tag 级探测（BASE_REGISTRY_TAG_PROBE 置空）"
    return 0
  fi
  url="$(tag_probe_url "$host" "$TAG_PROBE")"
  code="$(registry_probe_tag "$host" "$TAG_PROBE" "${BASE_REGISTRY_TAG_PROBE_CMD:-}" "$TIMEOUT")"
  code="${code%% *}"
  if registry_code_alive "$code"; then
    log "候选 $host 的 tag 探测通过（$url 回 HTTP $code，代表镜像 $TAG_PROBE）"
    return 0
  fi
  log "候选 $host 的 /v2/ 通，但 tag 探测不可用（$url 回 HTTP ${code:-空}）⇒ 不选它"
  return 1
}

candidates() {
  if [ -n "${BASE_REGISTRY_CANDIDATES:-}" ]; then
    # shellcheck disable=SC2086
    set -- $BASE_REGISTRY_CANDIDATES
    printf '%s\n' "$@"
  else
    printf '%s\n' "${CANDIDATES_DEFAULT[@]}"
  fi
}

self_test() {
  local tmp rc=0
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/pick-base-selftest.XXXXXX")"
  trap 'rm -rf "$tmp"' RETURN

  # 桩：按候选出现顺序返回预置状态码
  cat >"$tmp/stub.sh" <<'STUB'
#!/usr/bin/env bash
# $1=宿主；查表返回预置码
case "$1" in
  host-a) echo "${CODE_HOST_A:-200}" ;;
  host-b) echo "${CODE_HOST_B:-200}" ;;
  *) echo "000" ;;
esac
STUB
  chmod +x "$tmp/stub.sh"

  # 桩：tag 级探测（manifest HEAD）的状态码，同样按候选查表
  cat >"$tmp/tagstub.sh" <<'TAGSTUB'
#!/usr/bin/env bash
# $1=宿主；查表返回预置码
case "$1" in
  host-a) echo "${TAGCODE_HOST_A:-200}" ;;
  host-b) echo "${TAGCODE_HOST_B:-200}" ;;
  *) echo "000" ;;
esac
TAGSTUB
  chmod +x "$tmp/tagstub.sh"

  run_picker() {  # 用桩跑一次，返回选中的宿主（两级探测都走桩，保证自测不联网）
    BASE_REGISTRY_PROBE_CMD="$tmp/stub.sh" BASE_REGISTRY_TAG_PROBE_CMD="$tmp/tagstub.sh" \
      BASE_REGISTRY_CANDIDATES="host-a host-b" bash "$0" 2>/dev/null | tail -1
  }

  check() {
    local name="$1" want="$2" got="$3"
    if [ "$want" = "$got" ]; then
      echo "  [PASS] $name（=$got）"
    else
      echo "  [FAIL] $name 期望 $want，实为 '$got'"; rc=1
    fi
  }

  echo "SELFTEST（桩探测，不碰网络）"
  check "首候选 200 ⇒ 选它" "host-a" "$(CODE_HOST_A=200 CODE_HOST_B=200 run_picker)"
  check "401 也算可用" "host-a" "$(CODE_HOST_A=401 CODE_HOST_B=200 run_picker)"
  check "首候选 502 ⇒ 落到次候选" "host-b" "$(CODE_HOST_A=502 CODE_HOST_B=200 run_picker)"
  check "首候选超时(000) ⇒ 落到次候选" "host-b" "$(CODE_HOST_A=000 CODE_HOST_B=401 run_picker)"
  check "全不可用 ⇒ 取上游真源 docker.io" "docker.io" \
    "$(CODE_HOST_A=502 CODE_HOST_B=502 BASE_REGISTRY_CANDIDATES="host-a host-b" bash "$0" 2>/dev/null | tail -1)"
  # 显式覆盖优先，且必须原样返回（含带 /library 的老形态也不自作聪明改写）
  check "显式 BASE_REGISTRY 原样返回" "docker.m.daocloud.io/library" \
    "$(BASE_REGISTRY='docker.m.daocloud.io/library' bash "$0" 2>/dev/null | tail -1)"
  # 探活 URL 的映射：docker.io 必须探 registry API 宿主，否则探到的永远是官网 302
  check "docker.io 探活打到 registry-1.docker.io" "https://registry-1.docker.io/v2/" "$(probe_url docker.io)"
  check "加速器宿主按原名探" "https://docker.m.daocloud.io/v2/" "$(probe_url docker.m.daocloud.io)"
  # 302 不是"可用"：官网跳转恰恰说明这个 URL 不是 registry API
  check "302 视为不可用（落到次候选）" "host-b" "$(CODE_HOST_A=302 CODE_HOST_B=401 run_picker)"
  run_picker_both() {  # /v2/ 与 tag 两级都用桩
    BASE_REGISTRY_PROBE_CMD="$tmp/stub.sh" BASE_REGISTRY_TAG_PROBE_CMD="$tmp/tagstub.sh" \
      BASE_REGISTRY_CANDIDATES="host-a host-b" bash "$0" 2>/dev/null | tail -1
  }

  echo "SELFTEST（tag 级探测：/v2/ 通不等于 tag 拉得动，台账 #93）"
  check "/v2/ 200 但 tag 回 500 ⇒ 不选它" "host-b" \
    "$(TAGCODE_HOST_A=500 TAGCODE_HOST_B=200 run_picker_both)"
  check "tag TLS 超时(000) ⇒ 不选它" "host-b" \
    "$(TAGCODE_HOST_A=000 TAGCODE_HOST_B=401 run_picker_both)"
  check "tag 401（Hub 匿名 HEAD 在要 token）⇒ 仍可选" "host-a" \
    "$(TAGCODE_HOST_A=401 TAGCODE_HOST_B=200 run_picker_both)"
  check "tag 404（加速器懒加载未缓存）⇒ 仍可选" "host-a" \
    "$(TAGCODE_HOST_A=404 TAGCODE_HOST_B=200 run_picker_both)"
  check "两候选 tag 全 500 ⇒ 回落 docker.io" "docker.io" \
    "$(TAGCODE_HOST_A=500 TAGCODE_HOST_B=500 run_picker_both)"
  check "BASE_REGISTRY_TAG_PROBE 置空 ⇒ 跳过 tag 层（旧行为）" "host-a" \
    "$(BASE_REGISTRY_TAG_PROBE='' BASE_REGISTRY_PROBE_CMD="$tmp/stub.sh" BASE_REGISTRY_TAG_PROBE_CMD="$tmp/tagstub.sh" \
       BASE_REGISTRY_CANDIDATES="host-a host-b" bash "$0" 2>/dev/null | tail -1)"
  check "tag URL 走 registry API 宿主（docker.io）" "https://registry-1.docker.io/v2/library/alpine/manifests/3.20" \
    "$(tag_probe_url docker.io library/alpine:3.20)"
  check "tag URL 按加速器原名（daocloud）" "https://docker.m.daocloud.io/v2/library/python/manifests/3.11-slim" \
    "$(tag_probe_url docker.m.daocloud.io library/python:3.11-slim)"

  # 可见性自证（台账 #88）：CI 日志里读得到逐候选明细才算数。
  # 此前 probe / probe_tag 只在失败分支打印，成功路径只有一行「选定 base 镜像源：…」
  # ⇒ 日志里既看不到探了哪些候选，也看不到各回了什么码、有没有过 tag 级探测。
  capture_stderr() {  # 可见性就在 stderr 这一层（log() 写的正是 stderr）
    BASE_REGISTRY_PROBE_CMD="$tmp/stub.sh" BASE_REGISTRY_TAG_PROBE_CMD="$tmp/tagstub.sh" \
      BASE_REGISTRY_CANDIDATES="host-a host-b" bash "$0" 2>&1 >/dev/null
  }
  assert_contains() {  # $1=名称 $2=实际输出 $3=期望子串
    local name="$1" out="$2" want="$3"
    case "$out" in
      *"$want"*) echo "  [PASS] $name" ;;
      *) echo "  [FAIL] $name：输出里找不到「$want」"; rc=1 ;;
    esac
  }
  echo "SELFTEST（CI 日志可见性：成功路径也必须逐候选留痕，台账 #88）"
  ok_out="$(CODE_HOST_A=200 CODE_HOST_B=200 capture_stderr)"
  assert_contains "成功路径打印逐候选探活明细" "$ok_out" "host-a 探活通过"
  assert_contains "成功路径打印 tag 级探测明细" "$ok_out" "tag 探测通过"
  assert_contains "成功路径打印本轮探测个数" "$ok_out" "本轮探测 1 个候选"
  assert_contains "选定行保留既有口径（既有 grep 不失效）" "$ok_out" "选定 base 镜像源：host-a"
  bad_out="$(CODE_HOST_A=502 CODE_HOST_B=500 capture_stderr)"
  assert_contains "失败候选逐个打印不可用原因（首候选）" "$bad_out" "host-a 不可用"
  assert_contains "失败候选逐个打印不可用原因（次候选）" "$bad_out" "host-b 不可用"
  assert_contains "回落时打印本轮探测个数" "$bad_out" "本轮探测 2 个候选"
  return "$rc"
}

# 把本轮探测结论写进 CI 的 step summary：job 日志之外再多一处可核对的留痕，
# 免得「选了哪个源」只能靠翻几百 MB 日志反推（台账 #88 的可见性诉求）。
write_summary() {  # $1=选定源 $2=探测个数 $3=一句话结论
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  {
    echo "### base 镜像源探测"
    echo ""
    echo "- 结论：$3"
    echo "- 选定源：$1"
    echo "- 本轮探测候选数：$2"
  } >>"$GITHUB_STEP_SUMMARY"
}

main() {
  if [ "${1:-}" = "--self-test" ]; then
    self_test
    exit $?
  fi
  if [ -n "${BASE_REGISTRY:-}" ]; then
    log "使用显式 BASE_REGISTRY=$BASE_REGISTRY（跳过探测）"
    printf '%s\n' "$BASE_REGISTRY"
    exit 0
  fi
  local h tried=0
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    tried=$((tried + 1))
    if probe "$h" && probe_tag "$h"; then
      log "选定 base 镜像源：$h"
      log "本轮探测 $tried 个候选，选中 $h（前 $((tried - 1)) 个被判不可用）"
      if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "base_registry=$h" >>"$GITHUB_OUTPUT"
      fi
      write_summary "$h" "$tried" "候选第 $tried 个通过 /v2/ 探活与 tag 级 manifest 探测"
      printf '%s\n' "$h"
      exit 0
    fi
  done < <(candidates)
  log "警告：所有候选都不可用，落到上游 $FALLBACK——构建期可能变慢或撞 Docker Hub 限流，但不吊死在坏掉的加速器上"
  log "本轮探测 $tried 个候选，全部不可用 ⇒ 落到 $FALLBACK"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "base_registry=$FALLBACK" >>"$GITHUB_OUTPUT"
  fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "::warning::base 镜像源全部不可达，已回落 $FALLBACK（台账 #72）" >>"$GITHUB_STEP_SUMMARY"
  fi
  write_summary "$FALLBACK" "$tried" "全部候选不可用，已回落到上游 $FALLBACK"
  printf '%s\n' "$FALLBACK"
}

main "$@"
