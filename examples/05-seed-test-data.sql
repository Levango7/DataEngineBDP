-- 测试数据种子脚本 (非硬编码演示数据, 落地 MySQL 测试库, cleanup.sh 可一键清理)
-- 执行: mysql -h<host> -u<user> -p fin < 05-seed-test-data.sql
-- 说明: 数据仅用于端到端演示, 不含任何真实业务值; 演示结束务必运行 cleanup.sh
CREATE DATABASE IF NOT EXISTS fin;
USE fin;

DROP TABLE IF EXISTS user_order;
CREATE TABLE user_order (
  order_id    BIGINT       PRIMARY KEY,
  user_id     BIGINT,
  amount      DECIMAL(18,2),
  status      VARCHAR(16),
  update_time DATETIME
);

INSERT INTO user_order (order_id, user_id, amount, status, update_time) VALUES
  (1, 1001,   99.90, 'paid',     NOW()),
  (2, 1002, 1200.00, 'paid',    NOW()),
  (3, 1003,   50.00, 'pending', NOW()),
  (4, 1001, 2300.00, 'paid',    NOW()),
  (5, 1004,  800.00, 'refunded', NOW());
-- 5 行测试数据, 用于验证 CDC 入湖 / 主题建模 / 联邦查询
