#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
云原生组件版本统一化脚本
统一到 2025-2026 最新稳定版（只升级不降级）
"""
import os
import sys

ROOT = r"F:\nexus\DataEngineBDP"

# 修改清单: (相对路径, 旧字符串, 新字符串, 描述)
CHANGES = [
    # ===== 1. K8s 版本 1.30.0 -> 1.31.0 =====
    ("ske/manifests/kubeadm-config.yaml",
     "kubernetesVersion: v1.30.0", "kubernetesVersion: v1.31.0",
     "K8s kubeadm v1.30.0 -> v1.31.0"),
    ("ske/manifests/kubeadm-config.wsl2.yaml",
     "kubernetesVersion: v1.30.0", "kubernetesVersion: v1.31.0",
     "K8s kubeadm wsl2 v1.30.0 -> v1.31.0"),

    # ===== 2. ArgoCD 2.13.0 =====
    ("design/deploy/argocd/install/install-argocd.sh",
     "bash design/deploy/argocd/install/install-argocd.sh --version v2.11.0",
     "bash design/deploy/argocd/install/install-argocd.sh --version v2.13.0",
     "ArgoCD install 注释 v2.11.0 -> v2.13.0"),
    ("design/deploy/argocd/drift-detection/remediation/remediation-job.yaml",
     "image: argoproj/argocd:v2.7.0", "image: argoproj/argocd:v2.13.0",
     "ArgoCD remediation image v2.7.0 -> v2.13.0"),

    # ===== 3. Argo Rollouts 1.7.1 -> 1.8.0 =====
    ("design/deploy/charts/argo-rollouts/Chart.yaml",
     'appVersion: "1.7.1"', 'appVersion: "1.8.0"',
     "Argo Rollouts Chart appVersion 1.7.1 -> 1.8.0"),
    ("design/deploy/charts/argo-rollouts/values.yaml",
     'tag: "v1.7.1"', 'tag: "v1.8.0"',
     "Argo Rollouts image tag v1.7.1 -> v1.8.0"),

    # ===== 4. Cilium 1.15.0 -> 1.16.0 =====
    ("design/deploy/charts/cni-cilium/values.yaml",
     'tag: "v1.15.0"', 'tag: "v1.16.0"',
     "Cilium image tag v1.15.0 -> v1.16.0"),

    # ===== 5. Velero 1.13.0 -> 1.15.0 =====
    ("design/deploy/charts/velero/values.yaml",
     'tag: "v1.13.0"', 'tag: "v1.15.0"',
     "Velero image tag v1.13.0 -> v1.15.0"),

    # ===== 6. Karmada 1.10.0 -> 1.12.0 =====
    ("design/deploy/charts/karmada/values.yaml",
     'tag: "v1.10.0"', 'tag: "v1.12.0"',
     "Karmada image tag v1.10.0 -> v1.12.0"),

    # ===== 7. KEDA 2.13.0 -> 2.16.0 =====
    ("design/deploy/charts/keda/values.yaml",
     'tag: "2.13.0"', 'tag: "2.16.0"',
     "KEDA image tag 2.13.0 -> 2.16.0"),

    # ===== 8. Cert-Manager 1.14.0 -> 1.16.0 =====
    ("design/deploy/charts/cert-manager/values.yaml",
     'tag: "v1.14.0"', 'tag: "v1.16.0"',
     "Cert-Manager image tag v1.14.0 -> v1.16.0"),

    # ===== 9. Keycloak 24.0.4/24.0.5 -> 25.0.0 =====
    ("design/deploy/charts/keycloak/values.yaml",
     'tag: "24.0.4"', 'tag: "25.0.0"',
     "Keycloak chart image tag 24.0.4 -> 25.0.0"),
    ("design/deploy/values/keycloak-values.yaml",
     'version: "24.0.5"', 'version: "25.0.0"',
     "Keycloak values version 24.0.5 -> 25.0.0"),

    # ===== 10. Flink 1.18.0 -> 1.20.0 =====
    ("design/deploy/charts/flink/values.yaml",
     'tag: "1.18.0"', 'tag: "1.20.0"',
     "Flink chart image tag 1.18.0 -> 1.20.0"),
    ("design/deploy/values/flink-values.yaml",
     'flinkImage: "sq-flink:1.18-0.1.0"', 'flinkImage: "sq-flink:1.20.0-0.1.0"',
     "Flink values flinkImage 1.18 -> 1.20.0"),
    ("design/deploy/values/flink-values.yaml",
     'flinkVersion: "1.18.0"', 'flinkVersion: "1.20.0"',
     "Flink values flinkVersion 1.18.0 -> 1.20.0"),
    ("design/deploy/values/flink-values.yaml",
     "# Flink 1.18 定制 Helm values", "# Flink 1.20 定制 Helm values",
     "Flink values 注释 1.18 -> 1.20"),
    ("design/deploy/values/env/dev/flink-values.yaml",
     'tag: "1.18.0"', 'tag: "1.20.0"',
     "Flink dev env tag 1.18.0 -> 1.20.0"),
    ("design/deploy/values/env/staging/flink-values.yaml",
     'tag: "1.18.0"', 'tag: "1.20.0"',
     "Flink staging env tag 1.18.0 -> 1.20.0"),
    ("design/deploy/values/env/prod/flink-values.yaml",
     'tag: "1.18.0"', 'tag: "1.20.0"',
     "Flink prod env tag 1.18.0 -> 1.20.0"),

    # ===== 11. Trino 438 -> 460 =====
    ("design/deploy/charts/trino/values.yaml",
     'tag: "438"', 'tag: "460"',
     "Trino chart image tag 438 -> 460"),
    ("design/deploy/values/trino-values.yaml",
     'image: "trino:438"', 'image: "trino:460"',
     "Trino values image 438 -> 460"),
    ("design/deploy/values/trino-values.yaml",
     "# Trino 438 定制 Helm values", "# Trino 460 定制 Helm values",
     "Trino values 注释 438 -> 460"),
    ("design/deploy/values/env/dev/trino-values.yaml",
     'tag: "438"', 'tag: "460"',
     "Trino dev env tag 438 -> 460"),
    ("design/deploy/values/env/staging/trino-values.yaml",
     'tag: "438"', 'tag: "460"',
     "Trino staging env tag 438 -> 460"),
    ("design/deploy/values/env/prod/trino-values.yaml",
     'tag: "438"', 'tag: "460"',
     "Trino prod env tag 438 -> 460"),

    # ===== 12. Kafka 3.7.0/3.7.1 -> 3.8.1 =====
    ("design/deploy/charts/kafka/values.yaml",
     'tag: "3.7.0"', 'tag: "3.8.1"',
     "Kafka chart image tag 3.7.0 -> 3.8.1"),
    ("design/deploy/values/kafka-values.yaml",
     'tag: "3.7.0"', 'tag: "3.8.1"',
     "Kafka values image tag 3.7.0 -> 3.8.1"),
    ("design/deploy/values/kafka-values.yaml",
     'version: "3.7.1"', 'version: "3.8.1"',
     "Kafka values version 3.7.1 -> 3.8.1"),
    ("design/deploy/values/env/dev/kafka-values.yaml",
     'tag: "3.7.0"', 'tag: "3.8.1"',
     "Kafka dev env tag 3.7.0 -> 3.8.1"),
    ("design/deploy/values/env/staging/kafka-values.yaml",
     'tag: "3.7.0"', 'tag: "3.8.1"',
     "Kafka staging env tag 3.7.0 -> 3.8.1"),
    ("design/deploy/values/env/prod/kafka-values.yaml",
     'tag: "3.7.0"', 'tag: "3.8.1"',
     "Kafka prod env tag 3.7.0 -> 3.8.1"),
    # SeaTunnel kafka connector 版本对齐
    ("design/deploy/values/seatunnel-values.yaml",
     '        version: "3.7.1"', '        version: "3.8.1"',
     "SeaTunnel kafka connector version 3.7.1 -> 3.8.1"),

    # ===== 13. Superset 3.1.0/4.0.2 -> 4.1.0 =====
    ("design/deploy/charts/superset/values.yaml",
     'tag: "3.1.0"', 'tag: "4.1.0"',
     "Superset chart image tag 3.1.0 -> 4.1.0"),
    ("design/deploy/values/superset-values.yaml",
     'version: "4.0.2"', 'version: "4.1.0"',
     "Superset values version 4.0.2 -> 4.1.0"),
    ("design/deploy/values/env/dev/superset-values.yaml",
     'tag: "3.1.0"', 'tag: "4.1.0"',
     "Superset dev env tag 3.1.0 -> 4.1.0"),
    ("design/deploy/values/env/staging/superset-values.yaml",
     'tag: "3.1.0"', 'tag: "4.1.0"',
     "Superset staging env tag 3.1.0 -> 4.1.0"),
    ("design/deploy/values/env/prod/superset-values.yaml",
     'tag: "3.1.0"', 'tag: "4.1.0"',
     "Superset prod env tag 3.1.0 -> 4.1.0"),

    # ===== 14. APISIX 3.9.1 -> 3.11.0 =====
    ("design/deploy/charts/apisix/values.yaml",
     'tag: "3.9.1"', 'tag: "3.11.0"',
     "APISIX chart image tag 3.9.1 -> 3.11.0"),
    ("design/deploy/values/apisix-values.yaml",
     'version: "3.9.1"', 'version: "3.11.0"',
     "APISIX values version 3.9.1 -> 3.11.0"),

    # ===== 15. Spark 3.5.1 -> 3.5.3 =====
    ("design/deploy/values/spark-values.yaml",
     'sparkImage: "sq-spark:3.5-0.1.0"', 'sparkImage: "sq-spark:3.5.3-0.1.0"',
     "Spark values sparkImage 3.5 -> 3.5.3"),
    ("design/deploy/values/spark-values.yaml",
     'sparkVersion: "3.5.1"', 'sparkVersion: "3.5.3"',
     "Spark values sparkVersion 3.5.1 -> 3.5.3"),
    ("design/deploy/values/spark-values.yaml",
     'image: "sq-spark-shuffle:3.5-0.1.0"', 'image: "sq-spark-shuffle:3.5.3-0.1.0"',
     "Spark shuffle image 3.5 -> 3.5.3"),

    # ===== 16. K8s 版本相关组件对齐 1.31 =====
    ("design/deploy/charts/cluster-autoscaler/values.yaml",
     'tag: "v1.28.0"', 'tag: "v1.31.0"',
     "cluster-autoscaler tag v1.28.0 -> v1.31.0 (对齐 K8s 1.31)"),
    ("design/deploy/charts/ske-infra/values.yaml",
     'tag: "1.28.0-0.1.0"', 'tag: "1.31.0-0.1.0"',
     "ske-infra tag 1.28.0 -> 1.31.0 (对齐 K8s 1.31)"),
]


def main():
    modified_files = set()
    success_count = 0
    fail_count = 0
    skip_count = 0

    for rel_path, old, new, desc in CHANGES:
        full_path = os.path.join(ROOT, rel_path)
        if not os.path.isfile(full_path):
            print(f"[MISS] 文件不存在: {rel_path}")
            fail_count += 1
            continue

        with open(full_path, "r", encoding="utf-8") as f:
            content = f.read()

        if old not in content:
            print(f"[SKIP] 未找到目标字符串 (可能已修改): {desc} | {rel_path}")
            skip_count += 1
            continue

        count = content.count(old)
        if count > 1:
            print(f"[WARN] 多次匹配 ({count}处): {desc} | {rel_path} - 仍将全部替换")

        new_content = content.replace(old, new)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(new_content)

        modified_files.add(rel_path)
        success_count += 1
        print(f"[OK]  {desc} | {rel_path}")

    print("\n" + "=" * 70)
    print(f"修改成功: {success_count}  跳过(已修改): {skip_count}  失败: {fail_count}")
    print(f"涉及文件数: {len(modified_files)}")
    print("=" * 70)
    print("\n修改的文件清单:")
    for f in sorted(modified_files):
        print(f"  {f}")

    # 输出文件清单到 stdout 供 git add 使用
    print("\n__FILES__")
    for f in sorted(modified_files):
        print(f)


if __name__ == "__main__":
    main()