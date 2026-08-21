-- PostgreSQL 16 初始化脚本
-- DataEngineBDP 基础设施共享数据库
-- 数据库：dataengine，用户：deadmin

-- 创建扩展
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gin";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- 创建应用schema
CREATE SCHEMA IF NOT EXISTS metadata AUTHORIZATION deadmin;
CREATE SCHEMA IF NOT EXISTS lineage AUTHORIZATION deadmin;
CREATE SCHEMA IF NOT EXISTS ml_tracking AUTHORIZATION deadmin;
CREATE SCHEMA IF NOT EXISTS audit AUTHORIZATION deadmin;

-- 元数据表
CREATE TABLE IF NOT EXISTS metadata.tables (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    schema_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(256) NOT NULL,
    description TEXT,
    owner VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(schema_name, table_name)
);

-- 字段元数据
CREATE TABLE IF NOT EXISTS metadata.fields (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    table_id UUID REFERENCES metadata.tables(id) ON DELETE CASCADE,
    field_name VARCHAR(256) NOT NULL,
    field_type VARCHAR(128) NOT NULL,
    nullable BOOLEAN DEFAULT true,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(table_id, field_name)
);

-- 血缘关系表
CREATE TABLE IF NOT EXISTS lineage.edges (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_field_id UUID REFERENCES metadata.fields(id) ON DELETE CASCADE,
    target_field_id UUID REFERENCES metadata.fields(id) ON DELETE CASCADE,
    transformation_type VARCHAR(64),
    transformation_expr TEXT,
    job_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 审计日志
CREATE TABLE IF NOT EXISTS audit.query_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_name VARCHAR(128),
    query_text TEXT,
    query_source VARCHAR(64),
    duration_ms BIGINT,
    status VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 权限
GRANT USAGE ON SCHEMA metadata TO deadmin;
GRANT USAGE ON SCHEMA lineage TO deadmin;
GRANT USAGE ON SCHEMA ml_tracking TO deadmin;
GRANT USAGE ON SCHEMA audit TO deadmin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA metadata TO deadmin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA lineage TO deadmin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA ml_tracking TO deadmin;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA audit TO deadmin;

-- 注释
COMMENT ON SCHEMA metadata IS '元数据存储schema';
COMMENT ON SCHEMA lineage IS '血缘关系schema';
COMMENT ON SCHEMA ml_tracking IS '机器学习追踪schema';
COMMENT ON SCHEMA audit IS '审计日志schema';