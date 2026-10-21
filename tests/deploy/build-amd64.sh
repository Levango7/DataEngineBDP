#!/bin/bash
# 手工单架构（amd64）构建脚本，产出一张可 --load 进本机 docker 的镜像，
# 用来对照 ARM64 腿（nightly-e2e 的 ARM64 Build Smoke）在 QEMU 下的构建日志。
# 由 tests/deploy/MULTI-ARCH-BUILD-REPORT.md 引用；CI 不跑这条。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="$ROOT/platform/encaps-layer"
LOG="/tmp/amd64-build.log"

# 台账 #88：同 build-multiarch.sh——本地手工腿也要参选源，否则吃 Dockerfile.multiarch
# 的 ARG 默认宿主（加速器），红的时候日志里连"用的哪个源"都看不出来。
BASE_REGISTRY="$(bash "$ROOT/scripts/pick-base-registry.sh" || true)"
BASE_REGISTRY="${BASE_REGISTRY:-docker.io}"
echo "[build-amd64] BASE_REGISTRY=$BASE_REGISTRY"

cd "$CTX" || exit 1
START_TIME=$(date +%s)
docker buildx build -f Dockerfile.multiarch \
  --build-arg "BASE_REGISTRY=$BASE_REGISTRY" \
  --platform linux/amd64 \
  --tag encaps-layer:amd64 \
  --load --progress=plain . >"$LOG" 2>&1
BUILD_EXIT=$?
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo "AMD64_BUILD_EXIT=$BUILD_EXIT DURATION=${DURATION}s"
echo '---TAIL---'
tail -25 "$LOG"
exit "$BUILD_EXIT"
