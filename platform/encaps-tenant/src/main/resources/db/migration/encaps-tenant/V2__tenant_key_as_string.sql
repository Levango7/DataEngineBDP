-- 台账 #56 方案③：租户标识在本平台是字符串业务键。
-- 依据：全仓 39 张带 tenant_id 的表里 35 张已是 varchar，只有本模块的 quotas / workspaces
-- （以及 encaps-layer 的 invite_codes / user_registrations）写成了 bigint；这个前提使任何
-- 非数字租户（Keycloak 的 tenantId 用户属性、本地降级的 platform-admin）在配额/工作空间域整体不可用。
-- 数字 -> 字符串是放宽转换：存量行按原值转文本保留（1 -> '1'），不需要回填规则。
-- varchar(255) 与本模块其余 Hibernate 生成列一致（V1 baseline 里的 cpu_limit 等均为 varchar(255)）。
ALTER TABLE quotas     ALTER COLUMN tenant_id TYPE varchar(255) USING tenant_id::text;
ALTER TABLE workspaces ALTER COLUMN tenant_id TYPE varchar(255) USING tenant_id::text;
