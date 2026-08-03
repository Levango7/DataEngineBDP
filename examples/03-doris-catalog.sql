-- 集层: Doris 经 External Catalog 直读 Iceberg, 高频汇总建物化视图加速
-- 封装层在数据项目初始化时已自动建 catalog; 此处仅建物化视图 (客户视角: "建在线表")
-- 验证 V4: Doris 直读 Iceberg 无需导入, 物化视图承载在线查询

CREATE EXTERNAL CATALOG IF NOT EXISTS iceberg_trade PROPERTIES (
  "type"            = "iceberg",
  "iceberg.catalog.type" = "hadoop",
  "warehouse"       = "lakehouse/demo-fin/trade"
);

-- 集层物化视图: 直接基于 Iceberg dws 层
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dws_user_order_1d
DISTRIBUTED BY HASH(order_date)
AS
SELECT
  order_date,
  order_cnt,
  total_amount,
  uv
FROM iceberg_trade.dwd.dws_user_order_1d;
