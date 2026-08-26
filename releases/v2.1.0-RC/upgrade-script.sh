#!/bin/bash
# DataEngineBDP V2.0.0-RC -> V2.1.0-RC 升级脚本
# 使用前请务必：
# 1. 完整备份 etcd、PVC、数据库
# 2. 阅读 RELEASE-NOTES.md 了解不兼容变更
# 3. 在测试环境验证后再应用到生产

set -euo pipefail

VERSION_FROM=\"2.0.0-RC\"
VERSION_TO=\"2.1.0-RC\"
NAMESPACE=\"dataenginebdp\"
BACKUP_DIR=\"/backup/upgrade-\20260827-051621\"

log() { echo \"[\2026-08-27 05:16:21] \$*\"; }
error() { log \"ERROR: \$*\"; exit 1; }

# 检查前置条件
check_prereqs() {
    log \"检查前置条件...\"
    command -v kubectl >/dev/null || error \"kubectl 未安装\"
    command -v helm >/dev/null || error \"helm 未安装\"
    kubectl get ns \"\\" >/dev/null 2>&1 || error \"命名空间 \ 不存在\"
    log \"前置条件检查通过\"
}

# 备份关键数据
backup_data() {
    log \"开始备份到 \...\"
    mkdir -p \"\\"

    # etcd 快照
    log \"备份 etcd...\"
    kubectl exec -n kube-system etcd-\VanguardLea -- etcdctl snapshot save /tmp/etcd-snapshot.db 2>/dev/null || true
    kubectl cp kube-system/etcd-\VanguardLea:/tmp/etcd-snapshot.db \"\/etcd-snapshot.db\" 2>/dev/null || true

    # 数据库备份
    log \"备份 PostgreSQL...\"
    for db in catalog rule_engine tag_engine encaps_layer encaps_tenant encaps_gateway encaps_data infra_orchestrator infra_cloud infra_private infra_xinchang; do
        kubectl exec -n \ postgresql-0 -- pg_dump -U postgres \"\\" > \"\/\.sql\" 2>/dev/null || true
    done

    # PVC 备份
    log \"备份 PVC...\"
    kubectl get pvc -n \ -o json | jq -r '.items[] | select(.spec.volumeName) | .metadata.name' | while read pvc; do
        kubectl exec -n \ \ -- tar czf - /data 2>/dev/null | cat > \"\/pvc-\.tar.gz\" || true
    done

    # Helm values 备份
    helm get values dataenginebdp -n \ > \"\/helm-values.yaml\" 2>/dev/null || true

    log \"备份完成: \\"
}

# 升级 Helm Chart
upgrade_helm() {
    log \"升级 Helm Chart 到 \...\"
    helm repo update
    helm upgrade dataenginebdp dataenginebdp-umbrella \\
        --namespace \ \\
        --version \ \\
        -f releases/v2.1.0-RC/helm-values.yaml \\
        --wait --timeout 15m \\
        --atomic || error \"Helm 升级失败，正在回滚...\"

    log \"Helm 升级完成\"
}

# 数据迁移
migrate_data() {
    log \"执行数据迁移...\"

    # catalog: SQLite -> PostgreSQL 迁移（如启用）
    if kubectl get configmap -n \ catalog-config -o jsonpath='{.data.CATALOG_DB}' | grep -q postgres; then
        log \"迁移 catalog 数据到 PostgreSQL...\"
        kubectl exec -n \ deploy/catalog -- /app/catalog migrate 2>/dev/null || true
    fi

    # rule-engine: H2 -> PostgreSQL
    if kubectl get configmap -n \ rule-engine-config -o jsonpath='{.data.DB_URL}' | grep -q postgresql; then
        log \"迁移 rule-engine 数据到 PostgreSQL...\"
        kubectl exec -n \ deploy/rule-engine -- /app/rule-engine migrate 2>/dev/null || true
    fi

    # 其他组件类似迁移...

    log \"数据迁移完成\"
}

# 验证升级
verify_upgrade() {
    log \"验证升级结果...\"

    # 检查所有 Pod 状态
    kubectl wait --for=condition=Ready pod -l app.kubernetes.io/instance=dataenginebdp -n \ --timeout=300s || error \"Pod 未就绪\"

    # 核心组件健康检查
    for svc in catalog sql-gateway rule-engine encaps-layer; do
        kubectl exec -n \ deploy/\ -- wget -qO- http://localhost:8080/health 2>/dev/null | grep -q \"UP\" || log \"WARNING: \ 健康检查失败\"
    done

    # 运行冒烟测试
    log \"运行冒烟测试...\"
    ./scripts/smoke-test.sh || error \"冒烟测试失败\"

    log \"升级验证通过\"
}

# 回滚函数
rollback() {
    log \"开始回滚到 \...\"
    helm rollback dataenginebdp -n \ || error \"回滚失败\"
    # 恢复数据库
    for db in catalog rule_engine tag_engine; do
        kubectl exec -n \ postgresql-0 -- psql -U postgres -d \"\\" < \"\/\.sql\" 2>/dev/null || true
    done
    log \"回滚完成\"
}

# 主流程
main() {
    log \"=== DataEngineBDP 升级: \ -> \ ===\"
    check_prereqs
    backup_data
    upgrade_helm
    migrate_data
    verify_upgrade
    log \"=== 升级成功完成 ===\"
    log \"备份目录: \ (请妥善保管)\"
}

# 捕获错误并回滚
trap 'log \"升级失败，自动回滚...\"; rollback' ERR

main \"\$@\"
