#!/bin/bash
# 手工多架构构建脚本（amd64+arm64），产出一张 OCI tar，供离线验证 Dockerfile.multiarch。
# 由 tests/deploy/MULTI-ARCH-BUILD-REPORT.md 引用；CI 不跑这条（多架构冒烟在 nightly-e2e 的
# ARM64 Build Smoke 那条码了 BASE_REGISTRY 的腿里）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="$ROOT/platform/encaps-layer"
LOG="/tmp/multiarch-build.log"

# 台账 #88：这类"本地手工构建"最容易漏传宿主——Dockerfile.multiarch 的 FROM 走
# ${BASE_REGISTRY}，不传就吃 ARG 默认值（加速器），红的时候本地脚本比 CI 更难看懂。
BASE_REGISTRY="$(bash "$ROOT/scripts/pick-base-registry.sh" || true)"
BASE_REGISTRY="${BASE_REGISTRY:-docker.io}"
echo "[build-multiarch] BASE_REGISTRY=$BASE_REGISTRY"

cd "$CTX" || exit 1
START_TIME=$(date +%s)
docker buildx build -f Dockerfile.multiarch \
  --build-arg "BASE_REGISTRY=$BASE_REGISTRY" \
  --platform linux/amd64,linux/arm64 \
  --tag encaps-layer:multiarch \
  --output type=oci,dest=/tmp/multiarch-image.tar \
  --progress=plain . >"$LOG" 2>&1
BUILD_EXIT=$?
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo "MULTIARCH_BUILD_EXIT=$BUILD_EXIT DURATION=${DURATION}s"
echo '---TAIL---'
tail -40 "$LOG"
exit "$BUILD_EXIT"
