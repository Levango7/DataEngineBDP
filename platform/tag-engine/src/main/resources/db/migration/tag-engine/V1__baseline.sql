-- Flyway 基线迁移：tag-engine
-- 由 Hibernate 按 PostgreSQL 方言生成（jakarta.persistence.schema-generation.scripts.action=create），
-- 字段与 JPA 实体一一对应，非手写。重新生成：bash scripts/gen-db-baseline.sh platform/tag-engine
-- 请勿手工编辑本文件；后续结构变更请新增 V2__xxx.sql（Flyway 校验已应用脚本的 checksum）。

create table tag_definitions (created_at timestamp(6) not null, updated_at timestamp(6) not null, status varchar(16), type varchar(16) not null check ((type in ('FACT','RULE','MINING'))), tag_id varchar(64) not null, tenant_id varchar(64) not null, column_name varchar(128), name varchar(128) not null, display_name varchar(256), description varchar(1024), value_domain_json oid, primary key (tag_id), unique (tenant_id, name));
create table tag_rules (priority integer, created_at timestamp(6) not null, updated_at timestamp(6) not null, status varchar(16), rule_id varchar(64) not null, tag_id varchar(64) not null, tenant_id varchar(64) not null, rule_value varchar(256) not null, condition oid not null, properties_json oid, primary key (rule_id));
