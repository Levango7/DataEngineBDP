"""农牧行业模板 (agri-crop-yield).

业务场景：种植/养殖监测 + 气象关联分析 + 产量预测，覆盖：
    物联采集 → 环境监测 → 气象关联 → 产量预测 → 农事建议

对齐 ROADMAP v2.1「农牧行业模板：种植/养殖监测 + 气象关联分析 + 产量预测」。
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
    """构建农牧行业模板."""
    meta = TemplateMeta(
        id="agri-crop-yield",
        name="农牧监测与产量预测",
        industry=Industry.AGRICULTURE,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "农牧行业模板：大棚/养殖场物联监测、气象关联分析、作物产量预测" "与农事建议。服务智慧农业与乡村振兴。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        installCount=0,
        rating=4.4,
        tags=["农牧", "物联网", "气象", "产量预测", "智慧农业"],
        icon="🌾",
    )

    parameters = [
        TemplateParameter(
            name="datasource.iotdb",
            type=ParameterType.DATASOURCE,
            description="IoTDB 物联时序库连接（温湿度/土壤/设备）",
            required=True,
            placeholder="${IOTDB_URL}",
        ),
        TemplateParameter(
            name="datasource.weather_api",
            type=ParameterType.DATASOURCE,
            description="气象数据 API（降雨/温度/日照）",
            required=True,
            placeholder="${WEATHER_API}",
        ),
        TemplateParameter(
            name="monitor.temp_low",
            type=ParameterType.FLOAT,
            description="低温告警阈值（℃）",
            defaultValue=5.0,
            required=True,
        ),
        TemplateParameter(
            name="monitor.soil_moisture_low",
            type=ParameterType.FLOAT,
            description="土壤湿度下限（%）",
            defaultValue=30.0,
            required=True,
        ),
        TemplateParameter(
            name="yield.model",
            type=ParameterType.ENUM,
            description="产量预测模型",
            defaultValue="gradient_boost",
            enumOptions=["gradient_boost", "lstm", "linear"],
            required=True,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="监测统计 cron",
            defaultValue="*/10 * * * *",
            required=True,
        ),
    ]

    # 数据流：物联采集 → 环境监测 → 气象关联 → 产量预测 → 农事建议
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_iot",
                name="采集物联数据",
                nodeType="source",
                layer="ods",
                description="采集大棚/养殖场温湿度土壤传感器数据",
                outputs=["ods.farm_iot"],
                config={"source": "${IOTDB_URL}"},
            ),
            DataFlowNode(
                id="collect_weather",
                name="采集气象数据",
                nodeType="source",
                layer="ods",
                description="采集区域气象数据",
                outputs=["ods.weather"],
                config={"source": "${WEATHER_API}"},
            ),
            DataFlowNode(
                id="env_monitor",
                name="环境监测告警",
                nodeType="transform",
                layer="dwd",
                description="温湿度/土壤阈值监测与告警",
                inputs=["ods.farm_iot"],
                outputs=["dwd.env_alert"],
                config={"temp_low": "${monitor.temp_low}", "soil_low": "${monitor.soil_moisture_low}"},
            ),
            DataFlowNode(
                id="weather_corr",
                name="气象关联分析",
                nodeType="transform",
                layer="dws",
                description="物联数据与气象因子关联",
                inputs=["ods.farm_iot", "ods.weather"],
                outputs=["dws.env_weather"],
            ),
            DataFlowNode(
                id="yield_pred",
                name="产量预测",
                nodeType="transform",
                layer="dws",
                description="基于环境+气象预测作物产量",
                inputs=["dws.env_weather"],
                outputs=["dws.yield_pred"],
                config={"model": "${yield.model}"},
            ),
            DataFlowNode(
                id="advice_gen",
                name="农事建议生成",
                nodeType="sink",
                layer="ads",
                description="生成灌溉/保温/用药建议",
                inputs=["dwd.env_alert", "dws.yield_pred"],
                outputs=["ads.farm_advice"],
            ),
        ],
        schedule="${schedule.cron}",
        description="农牧数据流：物联/气象 → 监测/关联 → 产量预测 → 农事建议",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="sql_monitor",
                name="环境监测",
                stepType="sql",
                description="阈值监测生成告警",
                inputs=["ods.farm_iot"],
                outputs=["dwd.env_alert"],
                code=(
                    "SELECT plot_id, ts, sensor_type, value, "
                    "CASE WHEN sensor_type='temp' AND value < ${monitor.temp_low} THEN 'LOW_TEMP' "
                    "WHEN sensor_type='soil_moisture' AND value < ${monitor.soil_moisture_low} "
                    "THEN 'LOW_MOISTURE' END AS alert\n"
                    "FROM farm_iot WHERE value < ${monitor.temp_low} "
                    "OR (sensor_type='soil_moisture' AND value < ${monitor.soil_moisture_low})"
                ),
            ),
            ComputeLogicStep(
                id="python_weather_corr",
                name="气象关联",
                stepType="feature",
                description="物联与气象因子时序对齐",
                inputs=["ods.farm_iot", "ods.weather"],
                outputs=["dws.env_weather"],
                code=(
                    "from pandas import merge_asof\n"
                    "env = merge_asof(farm_iot, weather, on='ts', by='plot_id')\n"
                    "save(env)"
                ),
            ),
            ComputeLogicStep(
                id="python_yield",
                name="产量预测",
                stepType="model",
                description="${yield.model} 产量回归",
                inputs=["dws.env_weather"],
                outputs=["dws.yield_pred"],
                code=(
                    "from models import YieldModel\n"
                    "model = YieldModel('${yield.model}')\n"
                    "pred = model.predict(env_weather)\n"
                    "save_pred(pred)"
                ),
            ),
        ],
        description="农牧计算逻辑：SQL 监测 → 关联特征 → 产量模型",
    )

    # 可视化
    visualization = VisualizationConfig(
        panels=[
            VisualizationPanel(
                id="env_trend",
                title="环境监测趋势",
                chartType="line",
                description="温湿度/土壤趋势",
                dataSource="ods.farm_iot",
                config={"x": "ts", "series": ["temp", "soil_moisture"]},
            ),
            VisualizationPanel(
                id="alert_list",
                title="告警事件",
                chartType="table",
                description="环境阈值告警列表",
                dataSource="dwd.env_alert",
                config={"columns": ["plot_id", "alert", "ts"]},
            ),
            VisualizationPanel(
                id="yield_map",
                title="产量预测分布",
                chartType="map",
                description="各棚区产量预测",
                dataSource="dws.yield_pred",
                config={"geo": "farm_plots", "metric": "yield_pred"},
            ),
        ],
    )

    readme = (
        "# 农牧监测与产量预测模板\n\n"
        "## 适用场景\n"
        "- 设施农业：大棚环境监测与自动控制联动\n"
        "- 种植业：产量预测与农事决策\n"
        "- 养殖场：环境/健康监测\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.iotdb | datasource | 是 | - | 物联时序库 |\n"
        "| monitor.temp_low | float | 是 | 5 | 低温阈值 |\n"
        "| monitor.soil_moisture_low | float | 是 | 30 | 土壤湿度下限 |\n"
        "| yield.model | enum | 是 | gradient_boost | 预测模型 |\n\n"
        "## 升级注意事项\n"
        "- 产量模型需按作物/区域分模型重训\n"
        "- 传感器校准影响监测准确性"
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
            "required": ["datasource", "monitor", "yield"],
        },
    )
