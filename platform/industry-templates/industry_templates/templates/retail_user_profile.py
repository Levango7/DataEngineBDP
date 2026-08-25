"""零售用户画像模板 (retail-user-profile).

业务场景：用户画像标签体系，覆盖：
    交易数据 → 标签计算 → 人群圈选 → 营销推荐

对齐设计文档第 3 节"行业模板清单"中的 retail-inventory-opt 同类零售场景。
"""

from __future__ import annotations

from industry_templates.models import (
    ComputeLogicConfig,
    ComputeLogicStep,
    DataFlowConfig,
    DataFlowNode,
    Industry,
    ParameterType,
    Template,
    TemplateMeta,
    TemplateParameter,
    TemplateStatus,
    VisualizationConfig,
    VisualizationPanel,
)


def build_template() -> Template:
    """构建零售用户画像模板."""
    meta = TemplateMeta(
        id="retail-user-profile",
        name="用户画像标签体系",
        industry=Industry.RETAIL,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "零售用户画像模板：从交易数据到营销推荐全链路，"
            "覆盖标签计算、人群圈选、个性化推荐。"
            "基于标签引擎 + OLAP + 推荐算法，开箱即用。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        tags=["零售", "画像", "标签", "圈选", "推荐"],
        icon="🛍️",
    )

    parameters = [
        TemplateParameter(
            name="datasource.trade_db",
            type=ParameterType.DATASOURCE,
            description="交易数据库 JDBC（订单/支付/退款）",
            required=True,
            placeholder="${TRADE_DB_JDBC}",
        ),
        TemplateParameter(
            name="datasource.user_db",
            type=ParameterType.DATASOURCE,
            description="用户数据库 JDBC（基础信息/行为）",
            required=True,
            placeholder="${USER_DB_JDBC}",
        ),
        TemplateParameter(
            name="datasource.behavior_log",
            type=ParameterType.DATASOURCE,
            description="行为日志源（浏览/收藏/加购）",
            required=True,
            placeholder="${BEHAVIOR_LOG_PATH}",
        ),
        TemplateParameter(
            name="tag.freshness_window",
            type=ParameterType.INTEGER,
            description="新鲜度窗口（天），用于 RFM 计算",
            defaultValue=30,
            required=True,
        ),
        TemplateParameter(
            name="tag.high_value_threshold",
            type=ParameterType.FLOAT,
            description="高价值用户消费阈值（元）",
            defaultValue=5000.0,
            required=True,
        ),
        TemplateParameter(
            name="recommend.algorithm",
            type=ParameterType.ENUM,
            description="推荐算法",
            defaultValue="item_cf",
            enumOptions=["item_cf", "user_cf", "als", "deep_fm"],
            required=True,
        ),
        TemplateParameter(
            name="recommend.top_k",
            type=ParameterType.INTEGER,
            description="推荐 Top-K",
            defaultValue=10,
            required=True,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="标签计算调度周期",
            defaultValue="0 3 * * *",
            required=True,
        ),
    ]

    # 数据流：交易数据 → 标签计算 → 人群圈选 → 营销推荐
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_trade",
                name="采集交易数据",
                nodeType="source",
                layer="ods",
                description="采集订单/支付/退款交易流水",
                outputs=["ods.trade_detail"],
                config={
                    "source": "${TRADE_DB_JDBC}",
                    "tables": ["order", "payment", "refund"],
                },
            ),
            DataFlowNode(
                id="collect_user",
                name="采集用户基础信息",
                nodeType="source",
                layer="ods",
                description="采集用户基础属性",
                outputs=["ods.user_base"],
                config={"source": "${USER_DB_JDBC}"},
            ),
            DataFlowNode(
                id="collect_behavior",
                name="采集行为日志",
                nodeType="source",
                layer="ods",
                description="采集浏览/收藏/加购行为",
                outputs=["ods.user_behavior"],
                config={"source": "${BEHAVIOR_LOG_PATH}"},
            ),
            DataFlowNode(
                id="compute_rfm",
                name="RFM 标签计算",
                nodeType="transform",
                layer="dwd",
                description="计算 R/F/M 三维标签",
                inputs=["ods.trade_detail"],
                outputs=["dwd.user_rfm"],
                config={
                    "freshness_window": "${tag.freshness_window}",
                },
            ),
            DataFlowNode(
                id="compute_value_tag",
                name="价值标签计算",
                nodeType="transform",
                layer="dwd",
                description="高/中/低价值用户标签",
                inputs=["dwd.user_rfm", "ods.user_base"],
                outputs=["dwd.user_value_tag"],
                config={
                    "high_value_threshold": "${tag.high_value_threshold}",
                },
            ),
            DataFlowNode(
                id="compute_behavior_tag",
                name="行为偏好标签",
                nodeType="transform",
                layer="dwd",
                description="品类偏好/价格偏好/渠道偏好",
                inputs=["ods.user_behavior", "ods.trade_detail"],
                outputs=["dwd.user_behavior_tag"],
            ),
            DataFlowNode(
                id="merge_profile",
                name="画像合并",
                nodeType="transform",
                layer="dws",
                description="合并所有标签为用户画像宽表",
                inputs=[
                    "dwd.user_rfm",
                    "dwd.user_value_tag",
                    "dwd.user_behavior_tag",
                ],
                outputs=["dws.user_profile"],
            ),
            DataFlowNode(
                id="audience_select",
                name="人群圈选",
                nodeType="transform",
                layer="ads",
                description="按标签组合圈选目标人群",
                inputs=["dws.user_profile"],
                outputs=["ads.audience_segment"],
            ),
            DataFlowNode(
                id="recommend",
                name="营销推荐",
                nodeType="sink",
                layer="ads",
                description="为目标人群生成个性化推荐列表",
                inputs=["ads.audience_segment", "ods.user_behavior"],
                outputs=["ads.recommend_result"],
                config={
                    "algorithm": "${recommend.algorithm}",
                    "top_k": "${recommend.top_k}",
                },
            ),
        ],
        schedule="${schedule.cron}",
        description="用户画像数据流：交易/用户/行为 → RFM/价值/偏好 → 画像 → 圈选 → 推荐",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="sql_rfm",
                name="RFM 计算 SQL",
                stepType="sql",
                description="计算 Recency/Frequency/Monetary 三维标签",
                inputs=["ods.trade_detail"],
                outputs=["dwd.user_rfm"],
                code=(
                    "CREATE TABLE dwd.user_rfm AS\n"
                    "SELECT\n"
                    "    user_id,\n"
                    "    DATEDIFF(CURRENT_DATE, MAX(order_time)) AS recency,\n"
                    "    COUNT(DISTINCT order_id) AS frequency,\n"
                    "    SUM(pay_amount) AS monetary\n"
                    "FROM ods.trade_detail\n"
                    "WHERE order_status = 'PAID'\n"
                    "  AND order_time >= DATE_SUB(CURRENT_DATE, ${tag.freshness_window})\n"
                    "GROUP BY user_id;"
                ),
            ),
            ComputeLogicStep(
                id="sql_value_tag",
                name="价值标签 SQL",
                stepType="sql",
                description="划分高/中/低价值用户",
                inputs=["dwd.user_rfm"],
                outputs=["dwd.user_value_tag"],
                code=(
                    "CREATE TABLE dwd.user_value_tag AS\n"
                    "SELECT\n"
                    "    user_id,\n"
                    "    CASE\n"
                    "        WHEN monetary >= ${tag.high_value_threshold} THEN 'HIGH_VALUE'\n"
                    "        WHEN monetary >= ${tag.high_value_threshold}/3 THEN 'MID_VALUE'\n"
                    "        ELSE 'LOW_VALUE'\n"
                    "    END AS value_tag\n"
                    "FROM dwd.user_rfm;"
                ),
            ),
            ComputeLogicStep(
                id="python_behavior_tag",
                name="行为偏好标签",
                stepType="feature",
                description="基于行为日志计算品类/价格/渠道偏好",
                inputs=["ods.user_behavior"],
                outputs=["dwd.user_behavior_tag"],
                code=(
                    "from collections import Counter\n"
                    "category_pref = Counter(b.category for b in behaviors)\n"
                    "price_pref = 'high' if avg_price > median_price else 'low'\n"
                    "channel_pref = Counter(b.channel for b in behaviors).most_common(1)"
                ),
            ),
            ComputeLogicStep(
                id="sql_audience",
                name="人群圈选 SQL",
                stepType="sql",
                description="按标签组合圈选目标人群",
                inputs=["dws.user_profile"],
                outputs=["ads.audience_segment"],
                code=(
                    "-- 圈选高价值且偏好高端品类的用户\n"
                    "SELECT user_id FROM dws.user_profile\n"
                    "WHERE value_tag = 'HIGH_VALUE'\n"
                    "  AND category_pref LIKE '%高端%'\n"
                    "  AND recency <= 30;"
                ),
            ),
            ComputeLogicStep(
                id="python_recommend",
                name="推荐算法",
                stepType="model",
                description="基于 ${recommend.algorithm} 算法生成推荐",
                inputs=["ads.audience_segment", "ods.user_behavior"],
                outputs=["ads.recommend_result"],
                code=(
                    "from recommend import ${recommend.algorithm}\n"
                    "model = ${recommend.algorithm}(top_k=${recommend.top_k})\n"
                    "model.fit(behavior_matrix)\n"
                    "for user in audience:\n"
                    "    recs = model.recommend(user)"
                ),
                params={"algorithm": "${recommend.algorithm}"},
            ),
        ],
        description="用户画像计算逻辑：RFM SQL + 价值标签 + 行为偏好 + 圈选 + 推荐算法",
    )

    # 可视化
    visualization = VisualizationConfig(
        title="用户画像标签体系仪表盘",
        panels=[
            VisualizationPanel(
                id="panel_value_dist",
                title="用户价值分布",
                chartType="pie",
                description="高/中/低价值用户占比",
                dataSource="dwd.user_value_tag",
                width=6,
                height=300,
                config={
                    "field": "value_tag",
                    "values": ["HIGH_VALUE", "MID_VALUE", "LOW_VALUE"],
                },
            ),
            VisualizationPanel(
                id="panel_rfm_scatter",
                title="RFM 散点",
                chartType="scatter",
                description="R/F/M 三维分布（F vs M，颜色编码 R）",
                dataSource="dwd.user_rfm",
                width=12,
                height=340,
                config={
                    "xField": "frequency",
                    "yField": "monetary",
                    "colorField": "recency",
                },
            ),
            VisualizationPanel(
                id="panel_category_pref",
                title="品类偏好 Top10",
                chartType="bar",
                description="用户品类偏好排行",
                dataSource="dwd.user_behavior_tag",
                width=6,
                height=320,
                config={"xField": "count", "yField": "category"},
            ),
            VisualizationPanel(
                id="panel_audience_size",
                title="人群圈选规模",
                chartType="gauge",
                description="目标人群规模与占比",
                dataSource="ads.audience_segment",
                width=6,
                height=300,
                config={"valueField": "size", "maxField": "total_users"},
            ),
            VisualizationPanel(
                id="panel_recommend_ctr",
                title="推荐效果趋势",
                chartType="line",
                description="推荐 CTR / 转化率趋势",
                dataSource="ads.recommend_result",
                width=12,
                height=320,
                config={
                    "xField": "date",
                    "yField": ["ctr", "conversion_rate"],
                },
            ),
        ],
        description="用户画像仪表盘：价值分布 / RFM 散点 / 品类偏好 / 圈选规模 / 推荐效果",
    )

    readme = (
        "# 用户画像标签体系模板\n\n"
        "## 业务场景\n"
        "零售用户画像：交易数据 → 标签计算 → 人群圈选 → 营销推荐。\n\n"
        "## 适用场景\n"
        "- 电商、零售、品牌商的精准营销\n"
        "- 会员运营、私域流量运营\n"
        "- 个性化推荐与千人千面\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.trade_db | datasource | 是 | - | 交易库 JDBC |\n"
        "| tag.freshness_window | int | 是 | 30 | RFM 窗口（天）|\n"
        "| tag.high_value_threshold | float | 是 | 5000 | 高价值阈值（元）|\n"
        "| recommend.algorithm | enum | 是 | item_cf | 推荐算法 |\n"
        "| recommend.top_k | int | 是 | 10 | 推荐 Top-K |\n\n"
        "## 升级注意事项\n"
        "- 标签体系扩展需保持向后兼容（只增不删）\n"
        "- 推荐算法切换需 A/B 测试验证效果"
    )

    return Template(
        meta=meta,
        parameters=parameters,
        dataFlow=dataFlow,
        computeLogic=computeLogic,
        visualization=visualization,
        readme=readme,
        validationSchema={
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["datasource", "tag", "recommend"],
        },
    )
