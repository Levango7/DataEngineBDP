-- 作业名: cdc-user-order  (客户视角: 一个"同步任务")
-- 封装层翻译: FlinkDeployment CR, Pod 由 Flink Operator 托管 (客户无感)
-- 验证 V2: MySQL 变更经 Flink CDC 写入 Iceberg 湖层, 秒级可见

CREATE TABLE mysql_user_order (
  order_id     BIGINT,
  user_id      BIGINT,
  amount       DECIMAL(18,2),
  status       STRING,
  update_time  TIMESTAMP(3),
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'connector'      = 'mysql-cdc',
  'hostname'       = '${secret.mysql.host}',
  'port'           = '3306',
  'username'       = '${secret.mysql.user}',
  'password'       = '${secret.mysql.pass}',   -- 经封装层 Secret 注入, 不落明文
  'database-name'  = 'fin',
  'table-name'     = 'user_order'
);

CREATE TABLE iceberg_ods_user_order (
  order_id     BIGINT,
  user_id      BIGINT,
  amount       DECIMAL(18,2),
  status       STRING,
  update_time  TIMESTAMP(3),
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'format'       = 'iceberg',
  'catalog-type' = 'hadoop',
  'warehouse'    = 'lakehouse/demo-fin/trade',   -- 来自 00 中 project.storagePrefix
  'table'        = 'ods_user_order'
);

INSERT INTO iceberg_ods_user_order
SELECT order_id, user_id, amount, status, update_time
FROM mysql_user_order;
