-- 统一 SQL 网关 联邦查询 (客户视角: 一条 SQL 查全部, 不指定引擎)
-- 对应 统一 SQL 网关 v0.1 §3: Parser→Planner→Router→Executor→Merger, 脱敏下推
-- 验证 V5: 单条 SQL 跨 Iceberg(湖仓) 与 Doris(集) 关联, 网关返回合并结果

SELECT
  d.order_date,
  d.order_cnt,
  d.total_amount,
  m.user_id AS vip_user
FROM iceberg_trade.dwd.dws_user_order_1d d
LEFT JOIN iceberg_trade.dwd.dwd_user_order m
  ON d.order_date = m.order_date AND m.amount > 1000
WHERE d.order_date = CURRENT_DATE;
