#!/usr/bin/env bash
# MIRRORED FILE 一致性校验：jwt_auth.py 四处副本必须逐字节一致
set -euo pipefail
base="platform/llmops/llmops/api/jwt_auth.py"
for f in \
  "platform/ml-platform/ml_platform/api/jwt_auth.py" \
  "platform/nl2sql/jwt_auth.py" \
  "platform/llm-gateway/evaluation/app/jwt_auth.py"; do
  if ! diff -q "$base" "$f" >/dev/null; then
    echo "::error::MIRRORED FILE 不同步: $f 与 $base 不一致"
    exit 1
  fi
done
echo "jwt_auth.py 四处镜像一致"