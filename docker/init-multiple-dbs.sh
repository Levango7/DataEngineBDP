#!/bin/bash
# PostgreSQL 多库初始化脚本
# 由 docker-compose.core.yml 挂载到 /docker-entrypoint-initdb.d/

set -euo pipefail

# 默认数据库已由 POSTGRES_DB 创建
# 这里创建额外的数据库

DATABASES="catalog rule_engine tag_engine encaps_layer encaps_tenant encaps_gateway encaps_data infra_orchestrator infra_cloud infra_private infra_xinchang"

for db in $DATABASES; do
  echo "Creating database: $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE $db;
    GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;
EOSQL
done

echo "All databases created successfully"