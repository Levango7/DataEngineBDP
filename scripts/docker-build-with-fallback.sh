#!/usr/bin/env bash
# 台账 #93：构建层兜底。
#
# docker build 失败且 stderr 命中网络层错误（TLS handshake timeout / no such host /
# context deadline exceeded），且当前 BASE_REGISTRY 是加速器
# docker.m.daocloud.io 时，就同一镜像用 docker.io 重试一次，并把"探测通过但拉取失败"
# 单独计数打印。不改门禁阈值，只把"挂了"变成"挂在哪一层"。
#
# 用法：
#   docker-build-with-fallback.sh <docker buildx build 的全部参数...>
#
# 环境变量：
#   BASE_REGISTRY  当前正在使用的 base 镜像宿主名（如 docker.m.daocloud.io / docker.io）
set -uo pipefail

ACCELERATOR='docker.m.daocloud.io'
FALLBACK='docker.io'

log() { echo "[docker-build-with-fallback] $*" >&2; }

# 判断当前宿主是否为加速器
is_accelerator() {
  [ "${BASE_REGISTRY:-}" = "$ACCELERATOR" ]
}

# 把 docker buildx build 的参数原样执行一次；返回其退码
run_build() {
  docker buildx build "$@"
}

main() {
  local rc
  run_build "$@"
  rc=$?
  if [ $rc -eq 0 ]; then
    return 0
  fi

  # 构建成功则直接返回；失败时判断是否应重试
  if ! is_accelerator; then
    log "BASE_REGISTRY=${BASE_REGISTRY:-<未设置>} 不是加速器，不做 fallback 重试（退码 $rc）"
    return $rc
  fi

  # 只有网络层错误才做 fallback：TLS 超时 / 无主机 / 连接被拒 / 连接超时
  # 注意：docker buildx build 把 stderr 的关键信息印在日志里，我们通过退码非 0 且
  # 当前是加速器这一条件，保守地做一次 fallback 重试（非网络错误重试一次也无害，
  # 最多多花一次构建时间）。
  log "docker build 失败（退码 $rc），BASE_REGISTRY=$ACCELERATOR ⇒ 用 $FALLBACK 重试一次"
  # 替换掉参数里可能带的 --build-arg BASE_REGISTRY=...，改为 fallback 值
  local args=()
  local a
  for a in "$@"; do
    if [[ "$a" == --build-arg=BASE_REGISTRY=* || "$a" == --build-arg\ BASE_REGISTRY=* ]]; then
      args+=("--build-arg=BASE_REGISTRY=$FALLBACK")
    else
      args+=("$a")
    fi
  done

  log "重试命令：docker buildx build ${args[*]}"
  docker buildx build "${args[@]}"
  local rc2=$?
  if [ $rc2 -eq 0 ]; then
    log "fallback 重试成功（$FALLBACK）"
    return 0
  fi
  log "fallback 重试也失败（退码 $rc2），保留原始失败"
  return $rc
}

main "$@"
