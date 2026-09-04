-- 能源行业模板 DDL - 设备告警事件表（ADS）
CREATE TABLE IF NOT EXISTS ads.alert_events (
    event_id    BIGINT        COMMENT '告警ID',
    device_id   VARCHAR(64)   COMMENT '设备编号',
    alert_type  VARCHAR(32)   COMMENT '告警类型',
    alert_level VARCHAR(16)   COMMENT '告警级别',
    ts          DATETIME      COMMENT '告警时间',
    PRIMARY KEY (event_id)
) DUPLICATE KEY(event_id) COMMENT '设备告警事件'
DISTRIBUTED BY HASH(event_id) BUCKETS 8
PROPERTIES ("replication_num" = "1");