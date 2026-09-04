-- transportation 行业模板 DDL - dwd.vehicle_traj (车辆轨迹)
CREATE TABLE IF NOT EXISTS dwd.vehicle_traj (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '车辆轨迹'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
