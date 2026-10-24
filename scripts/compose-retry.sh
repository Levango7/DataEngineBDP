#!/usr/bin/env bash
# docker compose 这条路的 base 源兜底（台账 #93① 的补集）。
#
# scripts/build-retry.sh 只包住了显式的 `docker build` / `docker buildx build`，
# 而 CI 里还有一批腿是 `docker compose up` 时**顺带构建**镜像的。2026-10-23 实测：
#   - Playwright E2E（job 120622656117）在 `maven:3.9-eclipse-temurin-17` 上
#     `net/http: TLS handshake timeout`（打向 docker.m.daocloud.io）
#   - ZAP DAST（job 120622656508）在 `python:3.11-slim` 上同签名
#   - ZAP DAST（job 120583916887）在构建内部拉 `spring-core:7.0.9` 时
#     Maven Central `Connection reset`
# 三条都没有任何兜底 ⇒ 一次抖动就把腿判红，而 exit 1 不告诉你是哪一层。
#
# BASE_REGISTRY 对 compose 是**环境变量**（tests/integration/docker-compose.yml 的
# x-base-args 读 ${BASE_REGISTRY:-…}），不是命令行参数，所以这里改的是环境而不是 argv。
#
# 用法（把整条 compose 命令交给它）：
#   bash scripts/compose-retry.sh docker compose up -d --wait --wait-timeout 600
#
# 退出码（与 build-retry.sh 同一口径，#86：一码一义）：
#   0  成功（首次成功，或重试后成功）
#   1  失败，且输出不含传输类签名 ⇒ 代码/配置类真失败，不重试
#   2  失败，且两次都命中传输类签名 ⇒ 基础设施类
#   64 用法错误
#
# 环境变量：
#   BASE_REGISTRY            本次 compose 构建用的 base 镜像源（选源脚本注入）
#   BASE_REGISTRY_FALLBACK   当前源是加速器时改用的兜底源，默认 docker.io
#   BASE_REGISTRY_ACCEL      加速器宿主名，默认 docker.m.daocloud.io
#                            （compose 文件里 BASE_REGISTRY 未设时的默认值就是它）
#   COMPOSE_RETRY_LOG        给定前缀则把每次尝试的日志落盘，便于 CI 取证
set -uo pipefail

# 传输类签名：base 镜像源的 TLS/DNS/超时 + 构建内部拉依赖的网络中断。
# 只认这些才重试——否则会把"代码写错"再跑一遍，白烧一倍时间。
TRANSPORT_PATTERN='TLS handshake timeout|no such host|context deadline exceeded|i/o timeout|connection reset by peer|TLS handshake failure|Could not transfer artifact|Connection reset'
FALLBACK="${BASE_REGISTRY_FALLBACK:-docker.io}"
ACCEL="${BASE_REGISTRY_ACCEL:-docker.m.daocloud.io}"

log() { echo "[compose-retry] $*" >&2; }

run_once() {
  local tag="$1"; shift
  RUN_LOG_PATH=""
  if [ -n "${COMPOSE_RETRY_LOG:-}" ]; then
    RUN_LOG_PATH="${COMPOSE_RETRY_LOG}.${tag}.log"
    "$@" >"$RUN_LOG_PATH" 2>&1
    RUN_RC=$?
    cat "$RUN_LOG_PATH"
  else
    RUN_LOG_PATH="$(mktemp -t compose-retry.XXXXXX)"
    "$@" >"$RUN_LOG_PATH" 2>&1
    RUN_RC=$?
    cat "$RUN_LOG_PATH"
  fi
}

main() {
  if [ "${1:-}" = "--self-test" ]; then
    self_test
    exit $?
  fi
  if [ "$#" -eq 0 ]; then
    echo "用法：$0 <docker compose …up 命令及其参数…>" >&2
    exit 64
  fi

  local current first_log rc new
  current="${BASE_REGISTRY:-}"
  log "本次 compose 的 base 镜像源：${current:-<未设，由 compose 文件默认值决定>}；兜底源：${FALLBACK}"

  run_once first "$@"
  rc=$RUN_RC
  first_log="$RUN_LOG_PATH"
  if [ "$rc" -eq 0 ]; then
    rm -f "$first_log"
    log "compose 成功（首次尝试，重试 0 次）"
    return 0
  fi

  if ! grep -qE "$TRANSPORT_PATTERN" "$first_log" 2>/dev/null; then
    rm -f "$first_log"
    log "失败但未命中传输类签名 ⇒ 代码/配置类失败，不重试（exit 1）"
    return 1
  fi

  # 当前源是加速器（含"未设 ⇒ compose 默认就是加速器"）时换上游；
  # 否则源已是上游，换源无处可换，只做同参重试（覆盖构建内部依赖下载被 reset 的形态）。
  if [ -z "$current" ] || [ "$current" = "$ACCEL" ]; then
    new="$FALLBACK"
    log "传输类失败且当前源是加速器 ⇒ 换 ${new} 重试一次"
  else
    new="$current"
    log "传输类失败但当前源 ${current} 不是加速器 ⇒ 同参重试一次（不换源）"
  fi

  BASE_REGISTRY="$new"
  export BASE_REGISTRY
  run_once retry "$@"
  rc=$RUN_RC
  rm -f "$first_log" "$RUN_LOG_PATH"

  if [ "$rc" -eq 0 ]; then
    log "重试成功（源 ${current:-<未设>} → ${new}，重试 1 次）——这是一次真实的传输抖动，值得记一笔"
    return 0
  fi
  log "重试仍失败（源 ${new}，重试 1 次，exit 2）"
  return 2
}

self_test() {
  local tmp rc=0
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/compose-retry-selftest.XXXXXX")"
  trap 'rm -rf "$tmp"' RETURN

  # 桩：记下每次尝试看到的 BASE_REGISTRY，按 STUB_MODE 决定行为
  cat >"$tmp/stub.sh" <<'STUB'
#!/usr/bin/env bash
echo "BASE_REGISTRY=${BASE_REGISTRY-<unset>}" >> "$STUB_ENV_FILE"
case "${STUB_MODE:-ok}" in
  ok) echo "Container started"; exit 0 ;;
  transport) echo 'failed to do request: Head "https://docker.m.daocloud.io/v2/library/maven/manifests/3.9-eclipse-temurin-17": net/http: TLS handshake timeout' >&2; exit 1 ;;
  reset) echo 'Could not transfer artifact org.springframework:spring-core:jar:7.0.9: Connection reset' >&2; exit 1 ;;
  code) echo 'COPY failed: file not found in build context' >&2; exit 1 ;;
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
  attempts() { wc -l <"$1" 2>/dev/null | tr -d ' '; }

  echo "SELFTEST（桩 compose 命令，不碰网络、不调 docker）"

  # 1) 首次成功 ⇒ 0，且只有一次尝试
  : >"$tmp/e1"; STUB_ENV_FILE="$tmp/e1" STUB_MODE=ok \
    BASE_REGISTRY=docker.m.daocloud.io bash "$0" "$tmp/stub.sh" up -d --wait 2>/dev/null
  check "首次成功 ⇒ 0" 0 $?
  check "首次成功只尝试 1 次" 1 "$(attempts "$tmp/e1")"

  # 2) 代码类失败 ⇒ 1，不重试
  : >"$tmp/e2"; STUB_ENV_FILE="$tmp/e2" STUB_MODE=code \
    BASE_REGISTRY=docker.m.daocloud.io bash "$0" "$tmp/stub.sh" up -d 2>/dev/null
  check "代码类失败 ⇒ 1" 1 $?
  check "代码类失败不重试" 1 "$(attempts "$tmp/e2")"

  # 3) 传输类失败 + 当前源是加速器 ⇒ 换 docker.io 重试
  : >"$tmp/e3"; STUB_ENV_FILE="$tmp/e3" STUB_MODE=transport \
    BASE_REGISTRY=docker.m.daocloud.io bash "$0" "$tmp/stub.sh" up -d 2>/dev/null
  check "加速器传输失败且重试仍失败 ⇒ 2" 2 $?
  check "重试确实换到上游源" "BASE_REGISTRY=docker.io" "$(sed -n '2p' "$tmp/e3")"

  # 4) 未设 BASE_REGISTRY（compose 文件默认值就是加速器）⇒ 也要换源重试
  : >"$tmp/e4"; STUB_ENV_FILE="$tmp/e4" STUB_MODE=transport \
    env -u BASE_REGISTRY bash "$0" "$tmp/stub.sh" up -d 2>/dev/null
  check "未设源时传输失败 ⇒ 2" 2 $?
  check "未设源时重试补上上游源" "BASE_REGISTRY=docker.io" "$(sed -n '2p' "$tmp/e4")"

  # 5) 当前源已是上游 ⇒ 不换源，只做同参重试
  : >"$tmp/e5"; STUB_ENV_FILE="$tmp/e5" STUB_MODE=reset \
    BASE_REGISTRY=docker.io bash "$0" "$tmp/stub.sh" up -d 2>/dev/null
  check "上游源遇 reset ⇒ 2" 2 $?
  check "上游源不换源（两次同一取值）" "BASE_REGISTRY=docker.io" "$(sed -n '2p' "$tmp/e5")"
  check "上游源仍重试一次" 2 "$(attempts "$tmp/e5")"

  # 6) flaky：首次传输失败、重试成功 ⇒ 0，两次尝试
  cat >"$tmp/flaky.sh" <<'FLAKY'
#!/usr/bin/env bash
echo "BASE_REGISTRY=${BASE_REGISTRY-<unset>}" >> "$STUB_ENV_FILE"
n=$(grep -c . "$STUB_ENV_FILE")
if [ "$n" -le 1 ]; then echo "net/http: TLS handshake timeout" >&2; exit 1; fi
echo "Container started"; exit 0
FLAKY
  chmod +x "$tmp/flaky.sh"
  : >"$tmp/e6"; STUB_ENV_FILE="$tmp/e6" \
    BASE_REGISTRY=docker.m.daocloud.io bash "$0" "$tmp/flaky.sh" up -d 2>/dev/null
  check "首次传输失败、换源后成功 ⇒ 0" 0 $?
  check "确实尝试了两次" 2 "$(attempts "$tmp/e6")"

  # 7) 无参数 ⇒ 64
  bash "$0" 2>/dev/null
  check "无参数 ⇒ 64（用法错误）" 64 $?

  return "$rc"
}

main "$@"
