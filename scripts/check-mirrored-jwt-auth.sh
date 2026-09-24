#!/usr/bin/env bash
# MIRRORED FILE 一致性校验：jwt_auth.py 全部副本必须逐字节一致
#
# 本脚本是「副本清单」的唯一准据 —— 各副本文件头的文档字符串不再重复列举清单，
# 以避免清单漂移（曾出现：清单里含已删除的 platform/registry/app/jwt_auth.py，
# 而真实存在的 industry-templates / operations-api 两个副本却漏登记，
# 导致这两处游离在校验之外、静默漂移）。
#
# 新增或删除副本时，必须同步维护下面的清单。
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
  "platform/batch-pipeline/batch_pipeline/api/jwt_auth.py" \
  "platform/industry-templates/industry_templates/api/jwt_auth.py" \
  "platform/operations-api/operations_api/jwt_auth.py"; do
  if ! diff -q "$base" "$f" >/dev/null; then
    echo "::error::MIRRORED FILE 不同步: $f 与 $base 不一致"
    exit 1
  fi
done
echo "jwt_auth.py 全部镜像一致"
