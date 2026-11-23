#!/usr/bin/env bash
# 基座 registry manifest 探测的**共用实现**（台账 #116）。
#
# 为什么抽到共用位置：
#   scripts/pick-base-registry.sh 的「选源」要回答的是「这个源的某个 tag 拉不拉得动」；
#   scripts/check-pinned-downloads.sh 的「预检」要回答的是「我们钉的基座 tag 在 registry 上
#   还拉不拉得动」。两者问的是同一件事（tag 级 manifest 可达性），口径必须一致
#   ⇒ 只允许一份实现。**禁止再写第三份。**
#
# 本文件只提供**探测**与**码表**；四态判定（OK / FAIL / DEGRADED / INCONCLUSIVE）与
# 是否阻断的映射仍归 scripts/check-pinned-downloads.sh 的既有 classify / policy_verdict
# （不新增判定分支，见该脚本注释）。
#
# 用法（source 进来，不要直接执行）：
#   . "$(dirname "${BASH_SOURCE[0]}")/lib/registry-probe.sh"
#   url="$(registry_tag_probe_url <host> <name:tag>)"
#   out="$(registry_probe_tag <host> <name:tag> [probe_cmd] [timeout])"   # 回 "<code> <bytes>"
#   registry_code_alive    <code>   # 宿主存活（共用 OK 码表）
#   registry_code_answered <code>   # 存活且有实质应答（OK 码表去掉 404）
#
# 环境变量：
#   REGISTRY_PROBE_TIMEOUT  单次探测墙钟上限（秒），默认 8

# 匿名 HEAD manifest 的"正常但拿不到内容"应答：401 是在要 token（Docker Hub 的规范行为）、
# 404 是懒加载尚未缓存。它们都证明宿主在正常应答，不能当作不可用；
# 真故障是 000（TLS 超时/连接被掐，台账 #93 实测形态）与 5xx（实测 daocloud 回 500）。
# 206（部分内容）也是合法的 manifest 应答（代理/缓存链路上会出现）⇒ 同样算"在正常应答"。
REGISTRY_PROBE_OK_CODES="200 206 301 302 307 308 401 404"

# tag 级四态判定用的"有实质应答"码表 ＝ 共用 OK 码表去掉 404：
# 404 在**宿主存活**这一层算活着（加速器懒加载未缓存），但在**这个 tag**这一层是
# "这一源没有这个 tag"（确定性 404）⇒ 单列，不能混进 GOOD。
REGISTRY_PROBE_ANSWERED_CODES="200 206 301 302 307 308 401"

REGISTRY_PROBE_TIMEOUT="${REGISTRY_PROBE_TIMEOUT:-8}"

# docker.io 这个宿主名**不能直接按 https://docker.io/v2/ 探**（那是官网域名，回 302）；
# Docker Hub 的 registry API 在 registry-1.docker.io。
registry_probe_host() {
  case "$1" in
    docker.io | registry-1.docker.io | index.docker.io) printf 'registry-1.docker.io' ;;
    *) printf '%s' "$1" ;;
  esac
}

# tag 级 manifest 的探测 URL：https://<registry>/v2/<name>/manifests/<tag>
registry_tag_probe_url() {
  local host="$1" spec="$2" name tag
  name="${spec%%:*}"
  tag="${spec##*:}"
  printf 'https://%s/v2/%s/manifests/%s\n' "$(registry_probe_host "$host")" "$name" "$tag"
}

# 探一次 tag 级 manifest。回 "<code> <bytes>"；探不到回 "000 0"。
#   $1=host  $2=name:tag  [$3=外部探针命令]  [$4=超时秒数]
# 外部探针命令的约定：回 "<code> <bytes>" 或只有 "<code>"（bytes 记 0）；空串＝连不上。
registry_probe_tag() {
  local host="$1" spec="$2" cmd="${3:-}" timeout="${4:-$REGISTRY_PROBE_TIMEOUT}"
  local url out code size
  url="$(registry_tag_probe_url "$host" "$spec")"
  if [ -n "$cmd" ]; then
    out="$("$cmd" "$host" "$spec" 2>/dev/null || true)"
  else
    out="$(curl -s -o /dev/null -m "$timeout" -w '%{http_code} %{size_download}' \
      -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json' \
      "$url" 2>/dev/null || true)"
  fi
  out="${out//$'\r'/}"
  out="${out//$'\n'/}"
  if [ -z "$out" ]; then
    code="000"; size="0"
  elif [[ "$out" == *" "* ]]; then
    code="${out%% *}"; size="${out##* }"
  else
    code="$out"; size="0"
  fi
  [ -n "$code" ] || code="000"
  case "$size" in ''|*[!0-9]*) size="0" ;; esac
  printf '%s %s' "$code" "$size"
}

# 宿主存活？（共用 OK 码表）
registry_code_alive() {
  case " $REGISTRY_PROBE_OK_CODES " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

# 存活且有实质应答？（OK 码表去掉 404：404＝这一源没有这个 tag）
registry_code_answered() {
  case " $REGISTRY_PROBE_ANSWERED_CODES " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}
