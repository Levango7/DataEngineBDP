-- 作业名: spark-dwd-user-order  (客户视角: 一个"批作业")
-- 封装层翻译: SparkApplication CR, Pod 由 Spark Operator 托管 (客户无感)
-- 验证 V3: 从湖层 ods 产出主题层 dwd/dws, 数据共享同一 warehouse, 无冗余拷贝

-- 明细层 dwd
INSERT OVERWRITE iceberg_dwd_user_order
SELECT
  order_id,
  user_id,
  amount,
  status,
  DATE(update_time) AS order_date,
  update_time
FROM iceberg_ods_user_order
WHERE update_time >= current_date;

-- 汇总层 dws (近1日)
INSERT OVERWRITE iceberg_dws_user_order_1d
SELECT
  order_date,
  COUNT(*)                AS order_cnt,
  SUM(amount)             AS total_amount,
  COUNT(DISTINCT user_id) AS uv
FROM iceberg_dwd_user_order
GROUP BY order_date;
