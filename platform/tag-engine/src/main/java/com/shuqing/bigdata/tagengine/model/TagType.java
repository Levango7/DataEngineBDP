package com.shuqing.bigdata.tagengine.model;

/**
 * 标签类型枚举。
 *
 * <p>对应标签画像详细设计 §3：</p>
 * <ul>
 *   <li>{@link #FACT}       — 事实标签：湖仓事实表字段直接映射</li>
 *   <li>{@link #RULE}       — 规则标签：业务规则配置 SQL 匹配</li>
 *   <li>{@link #MINING}     — 挖掘标签：算法模型离线训练产出</li>
 * </ul>
 */
public enum TagType {

    /** 事实标签：直接字段映射，例如注册天数、累计消费 */
    FACT,

    /** 规则标签：SQL 规则匹配，例如高频用户（30 日下单≥5） */
    RULE,

    /** 挖掘标签：算法模型预测，例如价格敏感度、品类偏好簇 */
    MINING
}