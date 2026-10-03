-- Flyway 基线迁移：infra-provider-cloud
-- 由 Hibernate 按 PostgreSQL 方言生成（jakarta.persistence.schema-generation.scripts.action=create），
-- 字段与 JPA 实体一一对应，非手写。重新生成：bash scripts/gen-db-baseline.sh platform/infra-provider-cloud
-- 请勿手工编辑本文件；后续结构变更请新增 V2__xxx.sql（Flyway 校验已应用脚本的 checksum）。

create table cloud_cluster (node_count integer not null, created_at timestamp(6) with time zone not null, updated_at timestamp(6) with time zone not null, k8s_bootstrap_status varchar(16), provider varchar(16) not null, status varchar(16) not null, id varchar(36) not null, tenant_id varchar(64), workspace_id varchar(64) not null, cluster_name varchar(128) not null, k8s_api_server_endpoint varchar(256), error_message varchar(2048), nodes_json TEXT, primary key (id));
create index idx_cloud_cluster_provider on cloud_cluster (provider);
create index idx_cloud_cluster_workspace on cloud_cluster (workspace_id);
create index idx_cloud_cluster_status on cloud_cluster (status);
create index idx_cloud_cluster_tenant on cloud_cluster (tenant_id);
create index idx_cloud_cluster_provider_tenant on cloud_cluster (provider, tenant_id);
