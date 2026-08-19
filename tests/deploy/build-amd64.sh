#!/bin/bash
cd /mnt/f/nexus/DataEngineBDP/platform/encaps-layer
START_TIME=$(date +%s)
docker buildx build -f Dockerfile.multiarch --platform linux/amd64 --tag encaps-layer:amd64 --load --progress=plain . > /tmp/amd64-build.log 2>&1
BUILD_EXIT=$?
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo "AMD64_BUILD_EXIT=$BUILD_EXIT DURATION=${DURATION}s"
echo '---TAIL---'
tail -25 /tmp/amd64-build.log
