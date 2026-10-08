-- 台账 #56 方案③（续）：租户标识在本平台是字符串业务键 —— 补齐 encaps-layer 的两张表。
--
-- 依据与 V2__tenant_key_as_string.sql（encaps-tenant）同源：
--   全仓 39 张带 tenant_id 的表里 35 张已是 varchar；当时只放宽了本模块的
--   quotas / workspaces，而 V2 迁移的注释已把「encaps-layer 的 invite_codes /
--   user_registrations」一并点名为同批应改却遗漏的对象。
--
-- 影响（遗漏的代价）：运行时租户键是字符串 —— AuthController.localLogin() 签发 token
--   时写死 .claim("tenantId", "platform-admin")，Keycloak 主路径不放该 claim、
--   token 原样透传（sub 为 UUID）。而本组接口的 tenantId 收参是 Long，
--   非数字键在 Jackson 绑定阶段即 400，邀请码发不出来 => 注册申请（归属由邀请码决定）
--   随之不可用；而配额/工作空间域在 V2 之后已可用 => 同一部署下两域能力不对等。
--
-- 转换与 V2 同法：数字 -> 字符串是放宽转换，存量行按原值转文本保留（1 -> '1'），
-- 不需要回填规则，故 USING tenant_id::text 即可。
-- varchar(255) 与 V2 及本模块其余 Hibernate 生成列一致（V1 baseline 的 cpu_limit 等均为 varchar(255)）。
--
-- 不加字符集约束：全仓既有的 35 张 varchar tenant_id 表均无字符集校验
-- （encaps-tenant 侧的 currentTenantKey() 只校验非空），此处保持同一口径。
-- DNS-1123 那套字符集只服务于 K8s 名称/标签值场景（K8sTenantKeys），
-- 它会拒绝大写 UUID 与含下划线的键，用在纯存储列上属过度收窄，故不采用。

ALTER TABLE invite_codes       ALTER COLUMN tenant_id TYPE varchar(255) USING tenant_id::text;
ALTER TABLE user_registrations ALTER COLUMN tenant_id TYPE varchar(255) USING tenant_id::text;
