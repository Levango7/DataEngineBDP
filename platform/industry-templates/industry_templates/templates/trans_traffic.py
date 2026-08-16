"""交通行业模板 (trans-traffic-flow).

业务场景：路网流量预测 + 车辆轨迹分析 + 智能调度优化，覆盖：
    卡口采集 → 轨迹还原 → 流量统计 → 预测模型 → 调度优化

对齐 ROADMAP v2.1「交通行业模板：路网流量预测 + 车辆轨迹分析 + 智能调度优化」。
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
    """构建交通行业模板."""
    meta = TemplateMeta(
        id="trans-traffic-flow",
        name="交通流量预测与轨迹分析",
        industry=Industry.TRANSPORTATION,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "交通行业模板：卡口/ETC 数据接入、车辆轨迹还原、路网流量统计、"
            "时空预测模型与信号调度优化。适用于城市交通治理与高速运营。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        installCount=0,
        rating=4.4,
        tags=["交通", "流量预测", "轨迹", "时空", "调度"],
        icon="🚗",
    )

    parameters = [
        TemplateParameter(
            name="datasource.toll_db",
            type=ParameterType.DATASOURCE,
            description="卡口/ETC 过车数据库 JDBC",
            required=True,
            placeholder="${TOLL_DB_JDBC}",
        ),
        TemplateParameter(
            name="forecast.window_hours",
            type=ParameterType.INTEGER,
            description="预测时间窗（小时）",
            defaultValue=24,
            required=True,
        ),
        TemplateParameter(
            name="forecast.model",
            type=ParameterType.ENUM,
            description="预测模型",
            defaultValue="lstm",
            enumOptions=["lstm", "xgboost", "graphwave", "statistical"],
            required=True,
        ),
        TemplateParameter(
            name="trajectory.replay_window",
            type=ParameterType.INTEGER,
            description="轨迹还原时间窗（分钟）",
            defaultValue=30,
            required=True,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="流量统计 cron",
            defaultValue="*/5 * * * *",
            required=True,
        ),
    ]

    # 数据流：卡口采集 → 轨迹还原 → 流量统计 → 预测 → 调度
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_toll",
                name="采集卡口过车",
                nodeType="source",
                layer="ods",
                description="采集卡口/ETC 过车记录",
                outputs=["ods.toll_pass"],
                config={"source": "${TOLL_DB_JDBC}"},
            ),
            DataFlowNode(
                id="trajectory_replay",
                name="车辆轨迹还原",
                nodeType="transform",
                layer="dwd",
                description="按车牌+时间窗还原完整行驶轨迹",
                inputs=["ods.toll_pass"],
                outputs=["dwd.vehicle_traj"],
                config={"window": "${trajectory.replay_window}"},
            ),
            DataFlowNode(
                id="flow_stat",
                name="路网流量统计",
                nodeType="transform",
                layer="dws",
                description="按路段/时段聚合车流量",
                inputs=["ods.toll_pass"],
                outputs=["dws.road_flow"],
            ),
            DataFlowNode(
                id="flow_forecast",
                name="流量预测",
                nodeType="transform",
                layer="dws",
                description="时空模型预测未来流量",
                inputs=["dws.road_flow"],
                outputs=["dws.flow_pred"],
                config={"model": "${forecast.model}", "window": "${forecast.window_hours}"},
            ),
            DataFlowNode(
                id="signal_optimize",
                name="信号调度优化",
                nodeType="sink",
                layer="ads",
                description="基于预测生成信号配时/诱导建议",
                inputs=["dws.flow_pred"],
                outputs=["ads.signal_plan"],
            ),
        ],
        schedule="${schedule.cron}",
        description="交通数据流：卡口 → 轨迹/流量 → 预测 → 调度",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="python_traj",
                name="轨迹还原",
                stepType="feature",
                description="按车牌排序卡口记录还原轨迹",
                inputs=["ods.toll_pass"],
                outputs=["dwd.vehicle_traj"],
                code=(
                    "from trajectory import Replayer\n"
                    "replayer = Replayer(window_min=${trajectory.replay_window})\n"
                    "for vehicle in toll_pass.groupby('plate'):\n"
                    "    save_traj(replayer.replay(vehicle))"
                ),
            ),
            ComputeLogicStep(
                id="sql_flow",
                name="流量聚合",
                stepType="sql",
                description="按路段+5分钟窗口聚合流量",
                inputs=["ods.toll_pass"],
                outputs=["dws.road_flow"],
                code=(
                    "SELECT road_id, FLOOR(ts / 300) AS slot, COUNT(*) AS flow\n"
                    "FROM toll_pass GROUP BY road_id, FLOOR(ts / 300)"
                ),
            ),
            ComputeLogicStep(
                id="python_forecast",
                name="流量预测",
                stepType="model",
                description="${forecast.model} 时空预测",
                inputs=["dws.road_flow"],
                outputs=["dws.flow_pred"],
                code=(
                    "from models import FlowForecaster\n"
                    "model = FlowForecaster('${forecast.model}')\n"
                    "pred = model.predict(road_flow, horizon=${forecast.window_hours})\n"
                    "save_pred(pred)"
                ),
            ),
        ],
        description="交通计算逻辑：轨迹还原 → SQL 聚合 → 流量预测",
    )

    # 可视化
    visualization = VisualizationConfig(
        panels=[
            VisualizationPanel(
                id="flow_heatmap",
                title="路网流量热力",
                chartType="map",
                description="路段流量热力图",
                dataSource="dws.road_flow",
                config={"geo": "city_roads", "metric": "flow"},
            ),
            VisualizationPanel(
                id="pred_compare",
                title="预测 vs 实际",
                chartType="line",
                description="预测流量与实际对比",
                dataSource="dws.flow_pred",
                config={"x": "ts", "series": ["pred", "actual"]},
            ),
            VisualizationPanel(
                id="congestion_rank",
                title="拥堵路段 TOP10",
                chartType="table",
                description="实时拥堵路段排名",
                dataSource="dws.road_flow",
                config={"columns": ["road_id", "flow", "speed"]},
            ),
        ],
    )

    readme = (
        "# 交通流量预测与轨迹分析模板\n\n"
        "## 适用场景\n"
        "- 城市交通大脑：路网流量监测与拥堵预警\n"
        "- 高速公路运营：断面流量预测与诱导调度\n"
        "- 智慧交通信号优化\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.toll_db | datasource | 是 | - | 卡口库 JDBC |\n"
        "| forecast.model | enum | 是 | lstm | 预测模型 |\n"
        "| forecast.window_hours | int | 是 | 24 | 预测窗 |\n"
        "| trajectory.replay_window | int | 是 | 30 | 轨迹窗(分) |\n\n"
        "## 升级注意事项\n"
        "- 预测模型需按季节/节假日重训\n"
        "- 卡口设备增减需更新路网拓扑"
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
            "required": ["datasource", "forecast", "trajectory"],
        },
    )
