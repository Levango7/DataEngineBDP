#!/usr/bin/env bash
# base 镜像源的构建层兜底（台账 #93 的①）。
#
# 为什么要有这个脚本：`scripts/pick-base-registry.sh` 只能在构建**开始前**判断某个源
# 此刻像不像活的（/v2/ 与 tag 级 HEAD 两道探测）。但 2026-10-23 的真实抖动形态是
# **按请求随机失败**——同一轮 CI 里 40 个镜像从同一个加速器拉成功，而
# `golang:1.26-alpine`（job 120428648218）与 `eclipse-temurin:17-jre`（job 436182667）
# 各失败一次，签名都是 `net/http: TLS handshake timeout`。探测层再密也拦不住这种。
#
# 所以兜底放在构建层：同一镜像用上游源重试**一次**，并把「探测通过但拉取失败」
# 单独打印出来，让"挂在哪一层"在日志里可读（#93 的③）。
#
# 用法（把 `docker build` / `docker buildx build` 整条命令交给它）：
#   bash scripts/build-retry.sh docker build --build-arg "BASE_REGISTRY=$BASE_REGISTRY" -t x -f Df .
#
# 退出码（沿用 #86 的分类口径：一码一义，不再把两类失败混成一个 1）：
#   0  构建成功（首次成功，或换源重试后成功）
#   1  构建失败，但失败与镜像源传输无关（代码/依赖/上下文等真失败）
#   2  构建失败，且两次尝试都命中传输类签名 ⇒ 基础设施类（base 镜像源不可达）
#   64 用法错误
#
# 环境变量：
#   BASE_REGISTRY_FALLBACK   重试时改用的源，默认 docker.io
#   BASE_REGISTRY_ACCEL      「加速器」宿主名，只有当前源等于它才换源重试，默认 docker.m.daocloud.io
#   BASE_RETRY_LOG           把每次尝试的日志落盘到此前缀（默认不落盘），便于 CI 取证据
#   BASE_HANG_TIMEOUT        单次构建的挂起超时秒数，默认 600（台账 #97）
#
# 2026-10-31 增补（台账 #97）：daocloud 还有第三种抖动形态——**无输出挂起**：拉取既不报错
# 也不退出，传输类签名永远等不到，单组件吃满整个 job 预算（PR #745 三次 45m18s 撞墙的根因：
# 21:36 构建正常推进后在 spark-cluster 拉取上静默挂起 28 分钟）。所以给每次 attempt 套
# per-attempt 超时，超时（exit 124/137）写入 BUILD_HANG_TIMEOUT 标记行并归入传输类，
# 让既有换源兜底得以触发。
set -uo pipefail

# 传输类失败签名。只认这些才换源重试——否则会把"代码写错"也拿去重试一遍，白烧 2 倍时间。
# BUILD_HANG_TIMEOUT 是 note_hang 写入日志的标记行（挂起超时按传输类处理，见上）。
TRANSPORT_PATTERN='TLS handshake timeout|no such host|context deadline exceeded|i/o timeout|connection reset by peer|TLS handshake failure|BUILD_HANG_TIMEOUT'
FALLBACK="${BASE_REGISTRY_FALLBACK:-docker.io}"
ACCEL="${BASE_REGISTRY_ACCEL:-docker.m.daocloud.io}"
HANG_TIMEOUT="${BASE_HANG_TIMEOUT:-600}"

log() { echo "[build-retry] $*" >&2; }

# 从参数里取 BASE_REGISTRY 的取值：兼容
#   --build-arg BASE_REGISTRY=x / --build-arg=BASE_REGISTRY=x / BASE_REGISTRY=x
registry_in_args() {
  local a found=""
  for a in "$@"; do
    case "$a" in
      BASE_REGISTRY=*) found="${a#BASE_REGISTRY=}" ;;
      --build-arg=BASE_REGISTRY=*) found="${a#--build-arg=BASE_REGISTRY=}" ;;
    esac
  done
  printf '%s' "$found"
}

# 把参数里的 BASE_REGISTRY 换成给定值，其余原样保留
rewrite_registry_arg() {
  local new="$1"; shift
  local a
  for a in "$@"; do
    case "$a" in
      BASE_REGISTRY=*) printf '%s\n' "BASE_REGISTRY=$new" ;;
      --build-arg=BASE_REGISTRY=*) printf '%s\n' "--build-arg=BASE_REGISTRY=$new" ;;
      *) printf '%s\n' "$a" ;;
    esac
  done
}

# 挂起超时标记（台账 #97）：timeout 杀掉的 attempt（exit 124/137）写标记行进日志，
# 使其命中 TRANSPORT_PATTERN ⇒ 归入传输类、触发换源兜底，而不是被误判为代码失败。
note_hang() {
  local log_path="$1" rc="$2"
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
    echo "BUILD_HANG_TIMEOUT: 构建超过 ${HANG_TIMEOUT}s 被强制终止（无输出挂起形态）⇒ 按传输类处理" >> "$log_path"
  fi
}

# 跑一次构建。输出原样打到 stdout；同时把本次输出路径记到 RUN_LOG_PATH、退码记到 RUN_RC。
# 整条命令套 per-attempt 挂起超时（台账 #97）：daocloud 的无输出挂起形态靠它收敛到兜底重试。
run_once() {
  local tag="$1"; shift
  RUN_LOG_PATH=""
  if [ -n "${BASE_RETRY_LOG:-}" ]; then
    RUN_LOG_PATH="${BASE_RETRY_LOG}.${tag}.log"
    timeout "${HANG_TIMEOUT}" "$@" >"$RUN_LOG_PATH" 2>&1
    RUN_RC=$?
    note_hang "$RUN_LOG_PATH" "$RUN_RC"
    cat "$RUN_LOG_PATH"
  else
    RUN_LOG_PATH="$(mktemp -t build-retry.XXXXXX)"
    timeout "${HANG_TIMEOUT}" "$@" >"$RUN_LOG_PATH" 2>&1
    RUN_RC=$?
    note_hang "$RUN_LOG_PATH" "$RUN_RC"
    cat "$RUN_LOG_PATH"
  fi
}

main() {
  if [ "${1:-}" = "--self-test" ]; then
    self_test
    exit $?
  fi
  if [ "$#" -eq 0 ]; then
    echo "用法：$0 <构建命令及其参数…>" >&2
    exit 64
  fi

  local current first_log rc
  current="$(registry_in_args "$@")"
  log "本次构建的 base 镜像源：${current:-<未传 BASE_REGISTRY>}；传输类失败时的兜底源：${FALLBACK}"

  run_once first "$@"
  rc=$RUN_RC
  first_log="$RUN_LOG_PATH"
  if [ "$rc" -eq 0 ]; then
    rm -f "$first_log"
    log "构建成功（首次尝试，重试 0 次）"
    return 0
  fi

  if ! grep -qE "$TRANSPORT_PATTERN" "$first_log" 2>/dev/null; then
    rm -f "$first_log"
    log "构建失败但未命中传输类签名 ⇒ 代码/依赖类失败，不重试（exit 1）"
    return 1
  fi

  # 只有当前源就是那个加速器时才换源：换源的目标是"绕过加速器"，不是"随便换个陌生的源"
  if [ "$current" != "$ACCEL" ]; then
    rm -f "$first_log"
    log "传输类失败，但当前源 ${current:-<未传>} 不是加速器 ${ACCEL} ⇒ 不换源（基础设施类，exit 2）"
    return 2
  fi

  log "探测层通过但拉取失败（传输类）⇒ 换 ${FALLBACK} 重试一次（台账 #93①）"
  local -a retry_args
  mapfile -t retry_args < <(rewrite_registry_arg "$FALLBACK" "$@")
  run_once retry "${retry_args[@]}"
  rc=$RUN_RC
  rm -f "$first_log"

  if [ "$rc" -eq 0 ]; then
    rm -f "$RUN_LOG_PATH"
    log "换源重试成功（源 ${current} → ${FALLBACK}，重试 1 次）——这是一次真实的源抖动，值得记一笔"
    return 0
  fi
  rm -f "$RUN_LOG_PATH"
  log "换源重试仍失败（源 ${current} → ${FALLBACK}，重试 1 次，exit 2）"
  return 2
}

self_test() {
  local tmp rc=0
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/build-retry-selftest.XXXXXX")"
  trap 'rm -rf "$tmp"' RETURN

  # 桩：记录收到的参数（用于断言换源确实生效），按 STUB_MODE 决定行为
  cat >"$tmp/stub.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$STUB_ARGS_FILE"
case "${STUB_MODE:-ok}" in
  ok) echo "BUILD OK"; exit 0 ;;
  transport) echo 'ERROR: failed to do request: Head "https://x/v2/...": net/http: TLS handshake timeout' >&2; exit 1 ;;
  code) echo "ERROR: COPY failed: file not found" >&2; exit 1 ;;
  *) exit 1 ;;
esac
STUB
  chmod +x "$tmp/stub.sh"

  check() {
    local name="$1" want="$2" got="$3"
    if [ "$want" = "$got" ]; then
      echo "  [PASS] $name（=$got）"
    else
      echo "  [FAIL] $name 期望 $want，实为 '$got'"; rc=1
    fi
  }

  echo "SELFTEST（桩构建命令，不碰网络、不调 docker）"
  STUB_ARGS_FILE="$tmp/args" STUB_MODE=ok \
    bash "$0" "$tmp/stub.sh" --build-arg "BASE_REGISTRY=docker.m.daocloud.io" 2>/dev/null
  check "首次成功 ⇒ 0" 0 $?
  STUB_ARGS_FILE="$tmp/args" STUB_MODE=code \
    bash "$0" "$tmp/stub.sh" --build-arg "BASE_REGISTRY=docker.m.daocloud.io" 2>/dev/null
  check "代码类失败 ⇒ 1（不重试）" 1 $?
  STUB_ARGS_FILE="$tmp/args" STUB_MODE=transport \
    bash "$0" "$tmp/stub.sh" --build-arg "BASE_REGISTRY=docker.m.daocloud.io" 2>/dev/null
  check "传输类失败且重试仍失败 ⇒ 2" 2 $?
  STUB_ARGS_FILE="$tmp/args" STUB_MODE=transport \
    bash "$0" "$tmp/stub.sh" --build-arg "BASE_REGISTRY=docker.io" 2>/dev/null
  check "当前源已是 docker.io ⇒ 2（不是加速器，不换源）" 2 $?

  # 换源确实生效：stub 记录的参数里 BASE_REGISTRY 应变成 docker.io
  STUB_ARGS_FILE="$tmp/args" STUB_MODE=transport \
    bash "$0" "$tmp/stub.sh" --build-arg "BASE_REGISTRY=docker.m.daocloud.io" 2>/dev/null >/dev/null
  check "重试时 --build-arg 被改写为上游源" "BASE_REGISTRY=docker.io" \
    "$(grep -m1 '^BASE_REGISTRY=' "$tmp/args" 2>/dev/null)"

  # 一次传输类失败 + 一次成功后应返回 0
  cat >"$tmp/flaky.sh" <<'FLAKY'
#!/usr/bin/env bash
n=$(cat "$FLAKY_COUNT" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$FLAKY_COUNT"
if [ "$n" -le 1 ]; then echo "net/http: TLS handshake timeout" >&2; exit 1; fi
echo "BUILD OK"; exit 0
FLAKY
  chmod +x "$tmp/flaky.sh"
  FLAKY_COUNT="$tmp/count" bash "$0" "$tmp/flaky.sh" --build-arg "BASE_REGISTRY=docker.m.daocloud.io" 2>/dev/null
  check "首次传输失败、换源后成功 ⇒ 0" 0 $?
  check "确实尝试了两次" 2 "$(cat "$tmp/count" 2>/dev/null)"

  # 未传 BASE_REGISTRY 且传输失败 ⇒ 无处可换，2
  STUB_ARGS_FILE="$tmp/args" STUB_MODE=transport bash "$0" "$tmp/stub.sh" --tag t 2>/dev/null
  check "未传 BASE_REGISTRY ⇒ 不换源，2" 2 $?
  # 无参数 ⇒ 64
  bash "$0" 2>/dev/null
  check "无参数 ⇒ 64（用法错误）" 64 $?

  return "$rc"
}

main "$@"
