# 模板资产目录（T019）

本目录存放从 T018 金融模板同步的资产文件，供 Helm Chart 打包为 ConfigMap。

## 资产来源

- 源目录：`platform/industry-templates/templates/finance/`
- 同步脚本：`bash ../sync-assets.sh`

## 目录结构

```
assets/
├── ddl/           # 21 张 DDL 表定义（5 业务域）
│   ├── 01_risk_control_ddl.sql
│   ├── 02_customer_ddl.sql
│   ├── 03_account_ddl.sql
│   ├── 04_transaction_ddl.sql
│   └── 05_credit_ddl.sql
├── dag/            # 5 个 DAG 调度作业
│   ├── risk_feature_daily.json
│   ├── customer_tag_update.json
│   ├── account_eod_settlement.json
│   ├── transaction_aml_check.json
│   └── credit_score_monthly.json
├── dashboard/      # 3 个 Superset Dashboard
│   ├── risk_dashboard.json
│   ├── customer_dashboard.json
│   └── transaction_dashboard.json
├── rbac/           # 3 个 RBAC 角色权限定义
│   ├── roles.yaml
│   ├── permissions.yaml
│   └── role-permissions.yaml
├── docs/           # 业务文档
│   ├── business-model.md
│   ├── data-classification.md
│   └── desensitize-rules.yaml
└── template-metadata.yaml
```

## 同步命令

```bash
# 从 T018 同步资产到本目录
bash sync-assets.sh
```

## 注意

- 本目录内容由 `sync-assets.sh` 自动生成，请勿手动修改
- 修改模板资产请到 T018 源目录：`platform/industry-templates/templates/finance/`
- 同步后需重新打包 Chart：`bash package.sh package`