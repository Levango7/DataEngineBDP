package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;

/**
 * IoTDB 数据源适配器接口——对接 Apache IoTDB 时序数据库。
 *
 * <p>IoTDB 是面向工业物联网场景的时序数据库，数据模型为"设备-测点-时间戳"。
 * 本适配器在 {@link BaseAdapter} 之上扩展 IoTDB 特有能力：</p>
 * <ul>
 *   <li>时间范围下推（Time Range Pushdown）：将时间谓词下推为 IoTDB 查询的时间过滤</li>
 *   <li>降采样下推（Downsampling Pushdown）：将聚合 + 时间分组下推为 IoTDB 内置降采样</li>
 *   <li>设备过滤下推（Device Filter Pushdown）：将设备路径谓词下推为 IoTDB 查询路径</li>
 *   <li>对齐查询（Aligned Query）：利用 IoTDB 对齐时间戳能力减少数据传输</li>
 * </ul>
 *
 * <p>IoTDB SQL 语法与标准 SQL 差异较大（如 {@code select s1 from root.sg.d}），
 * {@link #getDialect()} 返回 {@code ANSI} 但下推 SQL 生成需特殊处理。</p>
 *
 * @author shuqing-bigdata
 */
public interface IoTDBAdapter extends BaseAdapter {

    /**
     * 执行时间范围下推。
     *
     * <p>将形如 {@code time >= '2024-01-01' AND time < '2024-02-01'} 的谓词
     * 转为 IoTDB 查询的时间范围参数。</p>
     *
     * @param timeFilter 时间谓词
     * @return IoTDB 时间范围字符串（如 "2024-01-01,2024-02-01"）
     */
    String pushDownTimeRange(String timeFilter);

    /**
     * 执行降采样下推。
     *
     * <p>将 {@code GROUP BY time_interval(time, 1h)} 下推为 IoTDB 内置降采样，
     * 避免传输原始时序数据。</p>
     *
     * @param aggFunc    聚合函数（如 "mean"）
     * @param timeColumn 时间列名
     * @param interval   降采样间隔（如 "1h"）
     * @return IoTDB 降采样查询片段
     */
    String pushDownDownsampling(String aggFunc, String timeColumn, String interval);

    /**
     * 将设备路径谓词转为 IoTDB 查询路径。
     *
     * <p>如谓词 {@code device = 'root.sg.d1'} 转为查询路径 {@code root.sg.d1.*}。</p>
     *
     * @param deviceFilter 设备路径谓词
     * @return IoTDB 查询路径
     */
    String toQueryPath(String deviceFilter);

    /**
     * 判断某聚合函数是否支持 IoTDB 内置降采样。
     *
     * @param aggFunc 聚合函数名（如 "mean"、"max"、"sum"）
     * @return {@code true} 表示支持
     */
    boolean supportsDownsampling(String aggFunc);

    @Override
    default DataSourceConfig.Type getAdapterType() {
        return DataSourceConfig.Type.IOTDB;
    }
}