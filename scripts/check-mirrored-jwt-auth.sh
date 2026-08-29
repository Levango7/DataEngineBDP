#!/usr/bin/env bash
# MIRRORED FILE 一致性校验：jwt_auth.py 九处副本必须逐字节一致
set -euo pipefail
base="platform/llmops/llmops/api/jwt_auth.py"
for f in \
  "platform/ml-platform/ml_platform/api/jwt_auth.py" \
  "platform/nl2sql/jwt_auth.py" \
  "platform/llm-gateway/evaluation/app/jwt_auth.py" \
  "platform/knowledge-engine/knowledge_engine/api/jwt_auth.py" \
  "platform/asset-exchange/asset_exchange/api/jwt_auth.py" \
  "platform/open-api-catalog/openapi_catalog/api/jwt_auth.py" \
  "platform/business-portal/business_portal/api/jwt_auth.py" \
  "platform/registry/app/jwt_auth.py"; do
  if ! diff -q "$base" "$f" >/dev/null; then
    echo "::error::MIRRORED FILE 不同步: $f 与 $base 不一致"
    exit 1
  fi
done
echo "jwt_auth.py 九处镜像一致"
