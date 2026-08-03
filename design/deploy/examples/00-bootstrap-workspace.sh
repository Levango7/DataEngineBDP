#!/usr/bin/env bash
# 客户视角：用封装层 CLI (dqctl) 建工作空间与数据项目
# 底层翻译见 封装层详细设计 v0.1 §4 R1/R2 —— 客户全程无 K8s 概念
set -euo pipefail

DQCTL="${DQCTL:-dqctl}"

echo "==> [客户] 创建金融演示工作空间 (demo-fin)"
$DQCTL workspace create \
  --name demo-fin \
  --display-name "金融演示空间" \
  --quota "cpu=16,memory=64Gi,storage=500Gi" \
  --tenant-type internal
# 封装层翻译: Namespace ws-demo-fin + ResourceQuota + NetworkPolicy(deny-all)

echo "==> [客户] 在工作空间内创建交易域数据项目 (trade)"
$DQCTL project create \
  --workspace demo-fin \
  --name trade \
  --display-name "交易域" \
  --storage-prefix "lakehouse/demo-fin/trade"
# 封装层翻译: 标签 ws=demo-fin,project=trade + 存储前缀 lakehouse/demo-fin/trade/
#           作为所有 Iceberg 表 warehouse 路径（统一存储 v0.1）

echo "==> 工作空间与项目就绪，可提交作业（见 01~04）"
