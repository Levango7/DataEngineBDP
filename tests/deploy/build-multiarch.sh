#!/bin/bash
cd /mnt/f/nexus/DataEngineBDP/platform/encaps-layer
START_TIME=$(date +%s)
docker buildx build -f Dockerfile.multiarch \
  --platform linux/amd64,linux/arm64 \
  --tag encaps-layer:multiarch \
  --output type=oci,dest=/tmp/multiarch-image.tar \
  --progress=plain . > /tmp/multiarch-build.log 2>&1
BUILD_EXIT=$?
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo "MULTIARCH_BUILD_EXIT=$BUILD_EXIT DURATION=${DURATION}s"
echo '---TAIL---'
tail -40 /tmp/multiarch-build.log