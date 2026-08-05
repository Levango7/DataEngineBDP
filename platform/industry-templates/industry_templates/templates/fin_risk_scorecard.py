"""金融风控评分卡模板 (fin-risk-scorecard).

业务场景：信贷风控模型，覆盖申请评分全流程：
    数据采集 → 特征工程 → 模型评分 → 风险等级

对齐设计文档第 3 节"行业模板清单"中的 fin-risk-scorecard。
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
    """构建金融风控评分卡模板."""
    meta = TemplateMeta(
        id="fin-risk-scorecard",
        name="风控评分卡",
        industry=Industry.FINANCE,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "信贷风控评分卡模板：申请评分全流程，"
            "从数据采集、特征工程到模型评分与风险等级划分。"
            "基于 XGBoost + 规则引擎 + 模型服务，开箱即用。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        installCount=128,
        rating=4.7,
        tags=["金融", "风控", "评分卡", "XGBoost", "信贷"],
        icon="💰",
    )

    parameters = [
        TemplateParameter(
            name="datasource.order_db",
            type=ParameterType.DATASOURCE,
            description="订单数据库 JDBC 连接（信贷订单表）",
            required=True,
            placeholder="${ORDER_DB_JDBC}",
        ),
        TemplateParameter(
            name="datasource.user_db",
            type=ParameterType.DATASOURCE,
            description="用户数据库 JDBC 连接（用户基础信息）",
            required=True,
            placeholder="${USER_DB_JDBC}",
        ),
        TemplateParameter(
            name="datasource.credit_bureau",
            type=ParameterType.DATASOURCE,
            description="征信局数据接口（外部征信查询）",
            required=False,
            placeholder="${CREDIT_BUREAU_URL}",
        ),
        TemplateParameter(
            name="scoring.threshold_high",
            type=ParameterType.FLOAT,
            description="高风险阈值（评分高于此值拒绝）",
            defaultValue=0.85,
            required=True,
        ),
        TemplateParameter(
            name="scoring.threshold_medium",
            type=ParameterType.FLOAT,
            description="中风险阈值（评分高于此值人工复核）",
            defaultValue=0.65,
            required=True,
        ),
        TemplateParameter(
            name="model.xgboost.max_depth",
            type=ParameterType.INTEGER,
            description="XGBoost 最大树深",
            defaultValue=6,
            required=True,
        ),
        TemplateParameter(
            name="model.xgboost.n_estimators",
            type=ParameterType.INTEGER,
            description="XGBoost 树数量",
            defaultValue=200,
            required=True,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="评分批处理调度周期（cron）",
            defaultValue="0 2 * * *",
            required=True,
        ),
        TemplateParameter(
            name="alert.webhook",
            type=ParameterType.STRING,
            description="高风险告警 webhook",
            required=False,
        ),
    ]

    # 数据流：数据采集 → 特征工程 → 模型评分 → 风险等级
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_order",
                name="采集信贷订单",
                nodeType="source",
                layer="ods",
                description="从订单库采集信贷申请订单",
                outputs=["ods.loan_order"],
                config={
                    "source": "${ORDER_DB_JDBC}",
                    "table": "loan_order",
                    "incremental": "apply_time",
                },
            ),
            DataFlowNode(
                id="collect_user",
                name="采集用户信息",
                nodeType="source",
                layer="ods",
                description="从用户库采集用户基础信息",
                outputs=["ods.user_info"],
                config={
                    "source": "${USER_DB_JDBC}",
                    "table": "user_info",
                },
            ),
            DataFlowNode(
                id="collect_credit",
                name="采集征信数据",
                nodeType="source",
                layer="ods",
                description="从征信局采集用户征信报告",
                outputs=["ods.credit_report"],
                config={
                    "source": "${CREDIT_BUREAU_URL}",
                    "method": "api",
                },
            ),
            DataFlowNode(
                id="feature_eng",
                name="特征工程",
                nodeType="transform",
                layer="dwd",
                description="构造评分卡特征：基础/信用/行为/还款能力",
                inputs=["ods.loan_order", "ods.user_info", "ods.credit_report"],
                outputs=["dwd.risk_feature"],
                config={
                    "features": [
                        "age", "income_monthly", "employment_years",
                        "credit_history_months", "overdue_count_12m",
                        "debt_to_income", "credit_utilization",
                    ],
                },
            ),
            DataFlowNode(
                id="model_score",
                name="模型评分",
                nodeType="transform",
                layer="dws",
                description="XGBoost 模型计算违约概率",
                inputs=["dwd.risk_feature"],
                outputs=["dws.risk_score"],
                config={
                    "model": "xgboost",
                    "max_depth": "${model.xgboost.max_depth}",
                    "n_estimators": "${model.xgboost.n_estimators}",
                },
            ),
            DataFlowNode(
                id="risk_grade",
                name="风险等级划分",
                nodeType="sink",
                layer="ads",
                description="按阈值划分高/中/低风险，输出最终决策",
                inputs=["dws.risk_score"],
                outputs=["ads.risk_decision"],
                config={
                    "threshold_high": "${scoring.threshold_high}",
                    "threshold_medium": "${scoring.threshold_medium}",
                },
            ),
        ],
        schedule="${schedule.cron}",
        description="信贷风控评分卡数据流：ODS→DWD→DWS→ADS 四层",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="sql_dwd_feature",
                name="特征工程 SQL",
                stepType="sql",
                description="构造 DWD 风控特征宽表",
                inputs=["ods.loan_order", "ods.user_info", "ods.credit_report"],
                outputs=["dwd.risk_feature"],
                code=(
                    "CREATE TABLE dwd.risk_feature AS\n"
                    "SELECT\n"
                    "    o.order_id, o.user_id, o.apply_amount,\n"
                    "    u.age, u.income_monthly, u.employment_years,\n"
                    "    c.credit_history_months, c.overdue_count_12m,\n"
                    "    c.total_debt / u.income_monthly AS debt_to_income,\n"
                    "    c.credit_used / c.credit_limit AS credit_utilization\n"
                    "FROM ods.loan_order o\n"
                    "JOIN ods.user_info u ON o.user_id = u.user_id\n"
                    "LEFT JOIN ods.credit_report c ON o.user_id = c.user_id;"
                ),
            ),
            ComputeLogicStep(
                id="xgboost_score",
                name="XGBoost 评分",
                stepType="model",
                description="XGBoost 二分类模型预测违约概率",
                inputs=["dwd.risk_feature"],
                outputs=["dws.risk_score"],
                code=(
                    "import xgboost as xgb\n"
                    "model = xgb.XGBClassifier(\n"
                    "    max_depth=${model.xgboost.max_depth},\n"
                    "    n_estimators=${model.xgboost.n_estimators},\n"
                    "    objective='binary:logistic',\n"
                    ")\n"
                    "model.fit(X_train, y_train)\n"
                    "scores = model.predict_proba(X)[:, 1]"
                ),
                params={
                    "modelType": "xgboost",
                    "objective": "binary:logistic",
                },
            ),
            ComputeLogicStep(
                id="rule_grade",
                name="风险等级规则",
                stepType="rule",
                description="按阈值划分风险等级",
                inputs=["dws.risk_score"],
                outputs=["ads.risk_decision"],
                code=(
                    "IF score >= ${scoring.threshold_high} THEN 'HIGH_RISK'  # 拒绝\n"
                    "ELIF score >= ${scoring.threshold_medium} THEN 'MEDIUM_RISK'  # 人工复核\n"
                    "ELSE 'LOW_RISK'  # 自动通过"
                ),
            ),
            ComputeLogicStep(
                id="alert_high_risk",
                name="高风险告警",
                stepType="scoring",
                description="高风险订单触发 webhook 告警",
                inputs=["ads.risk_decision"],
                outputs=["alert.high_risk"],
                code=(
                    "if decision == 'HIGH_RISK' and ${alert.webhook}:\n"
                    "    notify(${alert.webhook}, order_id, score)"
                ),
            ),
        ],
        description="风控评分卡计算逻辑：SQL 特征工程 + XGBoost 模型 + 规则分级 + 告警",
    )

    # 可视化
    visualization = VisualizationConfig(
        title="信贷风控评分卡仪表盘",
        panels=[
            VisualizationPanel(
                id="panel_score_dist",
                title="评分分布",
                chartType="histogram",
                description="违约概率分布直方图",
                dataSource="dws.risk_score",
                width=12,
                height=320,
                config={"xField": "score", "bins": 50},
            ),
            VisualizationPanel(
                id="panel_risk_pie",
                title="风险等级占比",
                chartType="pie",
                description="高/中/低风险订单占比",
                dataSource="ads.risk_decision",
                width=6,
                height=300,
                config={
                    "field": "risk_grade",
                    "values": ["LOW_RISK", "MEDIUM_RISK", "HIGH_RISK"],
                },
            ),
            VisualizationPanel(
                id="panel_apply_trend",
                title="申请趋势",
                chartType="line",
                description="近 30 日申请量与通过率趋势",
                dataSource="ads.risk_decision",
                width=6,
                height=300,
                config={"xField": "date", "yField": ["apply_count", "pass_rate"]},
            ),
            VisualizationPanel(
                id="panel_feature_importance",
                title="特征重要性",
                chartType="bar",
                description="XGBoost 特征重要性排序",
                dataSource="model.feature_importance",
                width=12,
                height=340,
                config={"xField": "importance", "yField": "feature"},
            ),
        ],
        description="风控评分卡 BI 仪表盘：评分分布 / 风险占比 / 申请趋势 / 特征重要性",
    )

    readme = (
        "# 风控评分卡模板\n\n"
        "## 业务场景\n"
        "信贷风控评分卡，覆盖申请评分全流程：数据采集 → 特征工程 → 模型评分 → 风险等级。\n\n"
        "## 适用场景\n"
        "- 消费金融、信用卡发卡、小额贷款\n"
        "- 需要可解释性 + 模型预测结合的场景\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.order_db | datasource | 是 | - | 订单库 JDBC |\n"
        "| datasource.user_db | datasource | 是 | - | 用户库 JDBC |\n"
        "| scoring.threshold_high | float | 是 | 0.85 | 高风险阈值 |\n"
        "| scoring.threshold_medium | float | 是 | 0.65 | 中风险阈值 |\n"
        "| model.xgboost.max_depth | int | 是 | 6 | XGBoost 树深 |\n"
        "| schedule.cron | string | 是 | 0 2 * * * | 调度周期 |\n\n"
        "## 升级注意事项\n"
        "- 模型版本变更需重新训练，建议保留旧模型对比评估\n"
        "- 阈值变更需业务复核，避免通过率突变"
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
            "required": ["datasource"],
        },
    )