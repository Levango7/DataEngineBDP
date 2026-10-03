-- Flyway 基线迁移：infra-provider-xinchang
-- 由 Hibernate 按 PostgreSQL 方言生成（jakarta.persistence.schema-generation.scripts.action=create），
-- 字段与 JPA 实体一一对应，非手写。重新生成：bash scripts/gen-db-baseline.sh platform/infra-provider-xinchang
-- 请勿手工编辑本文件；后续结构变更请新增 V2__xxx.sql（Flyway 校验已应用脚本的 checksum）。

create table xinchang_cluster (created_at timestamp(6) with time zone not null, updated_at timestamp(6) with time zone not null, status varchar(16) not null check ((status in ('CREATING','RUNNING','SCALING','DESTROYING','DESTROYED','FAILED'))), k8s_version varchar(32), cluster_name varchar(63) not null, cluster_id varchar(64) not null, control_plane_endpoint varchar(64), tenant_id varchar(64) not null, error_message varchar(4096), metadata_json oid, nodes_json oid, primary key (cluster_id));
