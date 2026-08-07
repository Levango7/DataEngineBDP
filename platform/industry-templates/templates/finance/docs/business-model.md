# 金融业务模型设计

> 归属：数擎大数据平台 · L5.3 行业应用模板 · 金融模板（finance）
> 对标：JR/T 0197《金融数据安全 数据安全分级指南》、JR/T 0171《个人金融信息保护技术规范》
> 关联：L3.5 资产目录（业务域/实体注册打标）；L3.7 安全脱敏（按数据分级下发脱敏策略）；L4.4 BI 可视化（按业务域组织仪表盘）
> 用途：作为金融行业模板的业务模型基线，指导 ODS/DWD/DWS 分层建表、资产目录挂载与脱敏策略生成

## 第1章 业务域划分

金融行业模板将业务数据划分为 5 个业务域，域间通过外键/主题关联，域内高内聚、域间低耦合，便于按域授权、按域脱敏、按域治理。

```mermaid
graph LR
    subgraph 风控域[风控域]
        RM[风控模型]
        RR[风控规则]
        RF[风控特征]
        RE[风控审批]
    end
    subgraph 客户域[客户域]
        CI[客户基本信息]
        CT[客户标签]
        CP[客户画像]
        CR[客户关系]
    end
    subgraph 账户域[账户域]
        AI[账户信息]
        AB[账户余额]
        AT[账户流水]
        AS[账户状态]
    end
    subgraph 交易域[交易域]
        TR[交易记录]
        TF[交易流水]
        TA[交易反洗钱]
        TM[交易监控]
    end
    subgraph 信贷域[信贷域]
        LA[贷款申请]
        LC[贷款合同]
        RP[还款计划]
        CS[信贷评分]
    end

    风控域 -.评估.-> 客户域
    风控域 -.评估.-> 交易域
    风控域 -.评估.-> 信贷域
    客户域 --持有--> 账户域
    客户域 --发起--> 交易域
    客户域 --申请--> 信贷域
    账户域 --产生--> 交易域
    信贷域 --关联--> 账户域
```

### 1.1 风控域

负责风控模型、规则、特征的管理与审批执行，覆盖申请风控、行为风控、反欺诈、反洗钱识别等场景。

| 子域 | 说明 | 关键实体 |
|---|---|---|
| 风控模型 | 评分卡/ML 模型的元信息与版本 | risk_model |
| 风控规则 | 规则集、阈值、命中动作 | risk_rule |
| 风控特征 | 特征定义、来源、统计口径 | risk_feature |
| 风控审批 | 一次评估的输入、命中、决策 | risk_evaluation、risk_alert |

### 1.2 客户域

承载客户基本信息、标签、画像与客户间关系，是风控、账户、交易、信贷域的主数据来源。

| 子域 | 说明 | 关键实体 |
|---|---|---|
| 客户基本信息 | 自然人/对公客户法定属性 | customer |
| 客户标签 | 业务标签、风险标签 | customer_tag |
| 客户画像 | 计算型画像指标 | customer_profile |
| 客户关系 | 担保、关联、家庭关系 | customer_relation |

### 1.3 账户域

记录账户生命周期、余额、流水与状态变更，是交易与信贷的资金承载。

| 子域 | 说明 | 关键实体 |
|---|---|---|
| 账户信息 | 账户主表 | account |
| 账户余额 | 日终/实时余额快照 | account_balance |
| 账户流水 | 入账明细 | account_transaction |
| 账户状态 | 状态变更日志 | account_status_log |

### 1.4 交易域

记录交易全链路：发起、明细、反洗钱命中与监控告警。

| 子域 | 说明 | 关键实体 |
|---|---|---|
| 交易记录 | 交易主表 | transaction |
| 交易流水 | 交易明细行 | transaction_detail |
| 交易反洗钱 | AML 命中与可疑报告 | aml_alert |
| 交易监控 | 监控规则命中与告警 | transaction_monitor |

### 1.5 信贷域

覆盖贷款申请、合同、还款计划与信贷评分全流程。

| 子域 | 说明 | 关键实体 |
|---|---|---|
| 贷款申请 | 进件、审批、放款 | loan_application |
| 贷款合同 | 合同主表与条款 | loan_contract |
| 还款计划 | 期次、应还、实还 | repayment_plan |
| 信贷评分 | 内部评分与外部征信 | credit_score |

## 第2章 ER图设计

本章为每个业务域给出 ER 图，统一采用 Mermaid `erDiagram` 语法，关系基数标注采用 `||--o{`（1 对 0..N）、`||--|{`（1 对 1..N）、`||--||`（1 对 1）、`}o--o{`（0..N 对 0..N）等符号。

### 2.1 风控域ER图

```mermaid
erDiagram
    risk_model ||--o{ risk_rule : "包含"
    risk_model ||--o{ risk_feature : "使用"
    risk_model ||--o{ risk_evaluation : "执行"
    risk_evaluation ||--o{ risk_alert : "产生"
    risk_rule ||--o{ risk_alert : "命中"

    risk_model {
        string model_id PK
        string model_name
        string model_type "评分卡/ML/规则集"
        string version
        string status "草稿/上线/下线"
        string owner
        timestamp created_at
    }
    risk_rule {
        string rule_id PK
        string model_id FK
        string rule_name
        string expression
        string action "通过/拒绝/人工"
        int priority
        timestamp updated_at
    }
    risk_feature {
        string feature_id PK
        string model_id FK
        string feature_name
        string source_table
        string stat_expr
        string dtype
    }
    risk_evaluation {
        string eval_id PK
        string model_id FK
        string biz_id "申请单/交易号"
        string biz_type "申请/交易/行为"
        string decision "通过/拒绝/人工"
        decimal score
        timestamp eval_at
    }
    risk_alert {
        string alert_id PK
        string eval_id FK
        string rule_id FK
        string alert_level "低/中/高"
        string alert_status "待处理/已处理"
        timestamp alert_at
    }
```

关系说明：
- `risk_model 1 -- N risk_rule`：一个模型包含多条规则。
- `risk_model 1 -- N risk_feature`：一个模型使用多个特征。
- `risk_evaluation N -- 1 risk_model`：一次评估由一个模型执行。
- `risk_evaluation 1 -- N risk_alert`：一次评估可产生多条告警。
- `risk_rule 1 -- N risk_alert`：一条规则可在多次评估中命中告警。

### 2.2 客户域ER图

```mermaid
erDiagram
    customer ||--o{ customer_tag : "打标"
    customer ||--|| customer_profile : "画像"
    customer ||--o{ customer_relation : "主体"
    customer ||--o{ customer_relation : "客体"

    customer {
        string customer_id PK
        string customer_type "个人/对公"
        string name
        string id_card
        string phone
        string address
        timestamp created_at
    }
    customer_tag {
        string tag_id PK
        string customer_id FK
        string tag_code
        string tag_value
        string tag_source "人工/规则/模型"
        timestamp tagged_at
    }
    customer_profile {
        string profile_id PK
        string customer_id FK
        decimal assets_total
        decimal liabilities_total
        string risk_level
        timestamp computed_at
    }
    customer_relation {
        string relation_id PK
        string subject_customer_id FK
        string object_customer_id FK
        string relation_type "担保/关联/家庭"
        decimal amount
        timestamp valid_from
        timestamp valid_to
    }
```

关系说明：
- `customer 1 -- N customer_tag`：一个客户可打多个标签。
- `customer 1 -- 1 customer_profile`：一个客户对应一份画像快照。
- `customer 1 -- N customer_relation`：客户作为主体或客体参与多组关系。

### 2.3 账户域ER图

```mermaid
erDiagram
    account ||--o{ account_balance : "快照"
    account ||--o{ account_transaction : "入账"
    account ||--o{ account_status_log : "变更"

    account {
        string account_id PK
        string customer_id FK
        string account_type "借记/贷记/对公"
        string currency
        string status "正常/冻结/销户"
        timestamp opened_at
        timestamp closed_at
    }
    account_balance {
        string balance_id PK
        string account_id FK
        decimal amount
        string balance_type "可用/冻结/总余额"
        date snapshot_date
        timestamp updated_at
    }
    account_transaction {
        string txn_id PK
        string account_id FK
        decimal amount
        string direction "借/贷"
        string counter_account
        timestamp posted_at
        string summary
    }
    account_status_log {
        string log_id PK
        string account_id FK
        string from_status
        string to_status
        string reason
        timestamp changed_at
    }
```

关系说明：
- `account 1 -- N account_balance`：一个账户存在多日/多类型余额快照。
- `account 1 -- N account_transaction`：一个账户产生多条入账明细。
- `account 1 -- N account_status_log`：一个账户的状态变更全程留痕。

### 2.4 交易域ER图

```mermaid
erDiagram
    transaction ||--o{ transaction_detail : "明细"
    transaction ||--o| aml_alert : "命中"
    transaction ||--|| transaction_monitor : "监控"

    transaction {
        string transaction_id PK
        string customer_id FK
        string debit_account_id FK
        string credit_account_id FK
        decimal amount
        string currency
        string channel "柜面/网银/移动"
        timestamp occurred_at
    }
    transaction_detail {
        string detail_id PK
        string transaction_id FK
        string item_code
        decimal item_amount
        string item_type
    }
    aml_alert {
        string aml_id PK
        string transaction_id FK
        string scenario "分散转入/集中转出/快进快出"
        string risk_level "低/中/高"
        string report_status "未上报/已上报"
        timestamp detected_at
    }
    transaction_monitor {
        string monitor_id PK
        string transaction_id FK
        string rule_code
        string result "正常/预警/拦截"
        timestamp checked_at
    }
```

关系说明：
- `transaction 1 -- N transaction_detail`：一笔交易可包含多行明细。
- `transaction 1 -- 0..1 aml_alert`：一笔交易最多命中一条 AML 告警。
- `transaction 1 -- 1 transaction_monitor`：每笔交易均经监控规则校验。

### 2.5 信贷域ER图

```mermaid
erDiagram
    loan_application ||--o| loan_contract : "签约"
    loan_contract ||--|{ repayment_plan : "分期"
    loan_application }o--|| credit_score : "依据"
    customer ||--o{ loan_application : "申请"

    customer {
        string customer_id PK
        string name
    }
    loan_application {
        string application_id PK
        string customer_id FK
        string product_code
        decimal apply_amount
        int apply_term "期数"
        string status "申请/审批/放款/拒绝"
        timestamp applied_at
    }
    loan_contract {
        string contract_id PK
        string application_id FK
        string contract_no
        decimal principal
        decimal rate
        timestamp signed_at
        timestamp maturity_at
    }
    repayment_plan {
        string plan_id PK
        string contract_id FK
        int term_no
        decimal due_principal
        decimal due_interest
        date due_date
        string repay_status "未还/部分/已还"
    }
    credit_score {
        string score_id PK
        string customer_id FK
        string score_source "内部/征信"
        int score
        string grade
        timestamp computed_at
    }
```

关系说明：
- `loan_application 1 -- 0..1 loan_contract`：申请通过后签约一份合同。
- `loan_contract 1 -- 1..N repayment_plan`：一份合同生成至少一期还款计划。
- `loan_application N -- 1 credit_score`：一次申请依据一份评分。
- `customer 1 -- N loan_application`：一个客户可多次申请。

## 第3章 域间关联总览

域间通过外键弱关联，不在物理层做强一致约束，由 L3.5 资产目录登记血缘。

```mermaid
graph LR
    customer -- "1:N" --> account
    customer -- "1:N" --> transaction
    customer -- "1:N" --> loan_application
    customer -- "1:1" --> credit_score
    account -- "1:N" --> account_transaction
    transaction -- "N:1" --> account
    loan_application -- "1:1" --> loan_contract
    loan_contract -- "1:N" --> repayment_plan
    risk_model -- "1:N" --> risk_evaluation
    risk_evaluation -- "N:1" --> transaction
    risk_evaluation -- "N:1" --> loan_application
    transaction -- "1:1" --> transaction_monitor
    transaction -- "1:0..1" --> aml_alert
```

| 关联 | 基数 | 说明 |
|---|---|---|
| customer → account | 1 : N | 一个客户持有多类账户 |
| customer → transaction | 1 : N | 客户作为交易一方 |
| customer → loan_application | 1 : N | 客户多次贷款申请 |
| account → account_transaction | 1 : N | 账户产生入账明细 |
| transaction → account | N : 1 | 交易关联借/贷账户 |
| loan_application → loan_contract | 1 : 1 | 申请通过后签约 |
| loan_contract → repayment_plan | 1 : N | 合同分期还款 |
| risk_evaluation → transaction | N : 1 | 风控评估以交易为输入 |
| risk_evaluation → loan_application | N : 1 | 风控评估以申请为输入 |
| transaction → aml_alert | 1 : 0..1 | 交易至多命中一条 AML 告警 |
| transaction → transaction_monitor | 1 : 1 | 每笔交易均经监控校验 |