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
set -uo pipefail

CANDIDATES_DEFAULT=('docker.m.daocloud.io' 'docker.io')
# 全部候选都探不通时取它：加速器只是便利层，上游才是真源
FALLBACK='docker.io'
TIMEOUT="${BASE_REGISTRY_TIMEOUT:-8}"

log() { echo "[pick-base-registry] $*" >&2; }

# 探一次 /v2/。返回 200 或 401 视为"可用"——401 恰恰说明 registry 活着、在要 token；
# 超时(000)、5xx、连接拒绝一律视为不可用。
probe() {
  local host="$1" code
  if [ -n "${BASE_REGISTRY_PROBE_CMD:-}" ]; then
    # 自测注入点：外部命令负责给一个 HTTP 状态码（或空串模拟彻底取不到）
    code="$($BASE_REGISTRY_PROBE_CMD "$host" 2>/dev/null || true)"
  else
    code="$(curl -s -o /dev/null -m "$TIMEOUT" -w '%{http_code}' "https://${host}/v2/" 2>/dev/null || true)"
  fi
  case "$code" in
    200|401) return 0 ;;
    *) log "候选 $host 不可用（HTTP ${code:-空}）"; return 1 ;;
  esac
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

  run_picker() {  # 用桩跑一次，返回选中的宿主
    BASE_REGISTRY_PROBE_CMD="$tmp/stub.sh" BASE_REGISTRY_CANDIDATES="host-a host-b" \
      bash "$0" 2>/dev/null | tail -1
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
  return "$rc"
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
  local h
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    if probe "$h"; then
      log "选定 base 镜像源：$h"
      if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "base_registry=$h" >>"$GITHUB_OUTPUT"
      fi
      printf '%s\n' "$h"
      exit 0
    fi
  done < <(candidates)
  log "警告：所有候选都不可用，落到上游 $FALLBACK——构建期可能变慢或撞 Docker Hub 限流，但不吊死在坏掉的加速器上"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "base_registry=$FALLBACK" >>"$GITHUB_OUTPUT"
    echo "::warning::base 镜像源全部不可达，已回落 $FALLBACK（台账 #72）" >>"$GITHUB_STEP_SUMMARY"
  fi
  printf '%s\n' "$FALLBACK"
}

main "$@"
