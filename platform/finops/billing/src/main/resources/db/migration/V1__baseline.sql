-- Flyway 基线迁移：billing
-- 由 Hibernate 按 PostgreSQL 方言生成（jakarta.persistence.schema-generation.scripts.action=create），
-- 字段与 JPA 实体一一对应，非手写。重新生成：bash scripts/gen-db-baseline.sh platform/finops/billing
-- 请勿手工编辑本文件；后续结构变更请新增 V2__xxx.sql（Flyway 校验已应用脚本的 checksum）。

create table billing (total_amount numeric(18,4) not null, generated_at timestamp(6) with time zone not null, period_end timestamp(6) with time zone not null, period_start timestamp(6) with time zone not null, billing_period varchar(16) not null, status varchar(32) not null, tenant_id varchar(128) not null, note varchar(1024), id varchar(255) not null, items_json oid not null, primary key (id));
create index idx_billing_tenant on billing (tenant_id);
create index idx_billing_period on billing (billing_period);
create index idx_billing_tenant_period on billing (tenant_id, billing_period);
create index idx_billing_status on billing (status);
