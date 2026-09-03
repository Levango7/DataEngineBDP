"""M2 服务化：batch-pipeline FastAPI 服务壳.

对齐 asset-exchange 服务约定：
    - create_app 工厂 + app.state 依赖注入
    - 平台标准 jwt_auth.py（MIRRORED FILE 第十处副本）
    - 业务路由统一 Depends(getAuthContext)；健康端点匿名豁免（CONVENTIONS.md）
    - 全局异常处理器统一 {error, message} 响应

职责边界：API 只做提交/查询薄壳（设计 §3.2），批处理主体仍是
run_pipeline 进程（K8s Job / CLI）；提交的批次在服务内后台线程执行。
租户 id 一律来自 JWT claim / X-Tenant-Id 头（deps.getTenantId），
绝不信任请求体——请求体里的 run_dir/state_dir 等路径字段会被
apply_tenant 强制覆盖为租户分区路径。
"""
