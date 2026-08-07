# K3s 端到端集成联调测试报告

> 生成时间: 2026-08-07 19:11:37
> 测试环境: K3s (namespace=shuqing)

## 测试摘要

| 指标 | 值 |
|------|-----|
| 链路总数 | 4 |
| 通过链路 | 1 |
| 失败链路 | 3 |
| 通过率 | 1/4 (25%) |

## K3s 集群环境

### Pod 状态

| Pod | Phase | Ready | Restarts |
|-----|-------|-------|----------|
| asset-exchange-5bb45cb6f-vnd5m | Running | ❌ | 54 |
| business-portal-dc94df44-99z99 | Running | ❌ | 52 |
| catalog-787d7fbc5b-ts7v5 | Running | ❌ | 55 |
| encaps-layer-d7f56785c-dpdfz | Running | ❌ | 10 |
| industry-templates-7894b879c7-lsjz4 | Running | ❌ | 54 |
| infra-orchestrator-6ff7f7d7d6-zfgfh | Running | ❌ | 21 |
| infra-provider-baremetal-74b6446c47-677jt | Pending | ❌ | 0 |
| infra-provider-cloud-655696f6c-cr8mj | Running | ❌ | 21 |
| infra-provider-private-d9c9b6cb9-pbcjg | Running | ❌ | 21 |
| infra-provider-xinchang-7dc994dc4-rtjz2 | Running | ❌ | 21 |
| knowledge-engine-56944dd7f6-nw5zf | Running | ❌ | 21 |
| lineage-analyzer-6d46f44dc7-mwxpz | Running | ❌ | 21 |
| llm-gateway-69869b89d5-kzt24 | Pending | ❌ | 0 |
| llmops-69484f6c4d-rhm45 | Running | ❌ | 52 |
| metadata-collector-6fdcdb5bd4-8rlhg | Running | ❌ | 37 |
| ml-platform-84767d88b8-btml6 | Running | ❌ | 53 |
| nl2sql-57f95dbb69-bttkt | Running | ❌ | 50 |
| open-api-catalog-6b965f8b44-9jwgp | Running | ❌ | 53 |
| rule-engine-69d49fc694-pr2km | Running | ❌ | 10 |
| sql-gateway-79cdfd5c4b-hs62l | Running | ❌ | 10 |
| tag-engine-54bdd457fd-d4pjt | Running | ❌ | 21 |
| vector-engine-76f57b5-sb2rl | Running | ❌ | 55 |

### Service ClusterIP

| Service | ClusterIP | Port |
|---------|-----------|------|
| asset-exchange | 10.43.21.0 | 8087 |
| business-portal | 10.43.142.198 | 8088 |
| catalog | 10.43.48.204 | 8082 |
| encaps-layer | 10.43.246.140 | 8080 |
| industry-templates | 10.43.163.108 | 8091 |
| infra-orchestrator | 10.43.116.180 | 8085 |
| infra-provider-baremetal | 10.43.105.117 | 8080 |
| infra-provider-cloud | 10.43.176.184 | 8084 |
| infra-provider-private | 10.43.240.111 | 8084 |
| infra-provider-xinchang | 10.43.255.189 | 8081 |
| knowledge-engine | 10.43.14.135 | 8080 |
| lineage-analyzer | 10.43.255.152 | 8086 |
| llm-gateway | 10.43.3.92 | 8084 |
| llmops | 10.43.219.218 | 8080 |
| metadata-collector | 10.43.136.44 | 8084 |
| ml-platform | 10.43.65.75 | 8080 |
| nl2sql | 10.43.12.231 | 8093 |
| open-api-catalog | 10.43.157.78 | 8090 |
| rule-engine | 10.43.247.213 | 8083 |
| sql-gateway | 10.43.248.243 | 8081 |
| tag-engine | 10.43.100.24 | 8080 |
| vector-engine | 10.43.207.32 | 8086 |

## 链路测试详情

### 链路1: NL2SQL→SQL网关→查询 — ✅ 通过

- **耗时**: 85.69s
- **退出码**: 0

| 测试 | 状态 |
|------|------|
| test_nl2sql_health | ⏭️ SKIPPED |
| test_sql_gateway_health | ✅ PASSED |
| test_generate_simple_query | ⏭️ SKIPPED |
| test_generate_with_table_hints | ⏭️ SKIPPED |
| test_execute_via_gateway | ⏭️ SKIPPED |
| test_gateway_execute_trino | ✅ PASSED |
| test_gateway_execute_doris | ✅ PASSED |
| test_full_chain_nl_to_result | ⏭️ SKIPPED |

<details>
<summary>pytest 输出摘要</summary>

```
test_chain1_nl2sql_to_query.py::TestChain1HealthCheck::test_nl2sql_health SKIPPED [ 12%]
test_chain1_nl2sql_to_query.py::TestChain1HealthCheck::test_sql_gateway_health PASSED [ 25%]
test_chain1_nl2sql_to_query.py::TestChain1Nl2SqlGenerate::test_generate_simple_query SKIPPED [ 37%]
test_chain1_nl2sql_to_query.py::TestChain1Nl2SqlGenerate::test_generate_with_table_hints SKIPPED [ 50%]
test_chain1_nl2sql_to_query.py::TestChain1Nl2SqlExecute::test_execute_via_gateway SKIPPED [ 62%]
test_chain1_nl2sql_to_query.py::TestChain1SqlGatewayExecute::test_gateway_execute_trino PASSED [ 75%]
test_chain1_nl2sql_to_query.py::TestChain1SqlGatewayExecute::test_gateway_execute_doris PASSED [ 87%]
test_chain1_nl2sql_to_query.py::TestChain1EndToEnd::test_full_chain_nl_to_result SKIPPED [100%]
=================== 3 passed, 5 skipped in 84.70s (0:01:24) ====================
```

</details>

---

### 链路2: 编排引擎→Agent→工具调用 — ❌ 失败

- **耗时**: 79.94s
- **退出码**: 1

| 测试 | 状态 |
|------|------|
| test_orchestrator_health | ✅ PASSED |
| test_knowledge_engine_health | ✅ PASSED |
| test_list_providers | ✅ PASSED |
| test_list_environments | ✅ PASSED |
| test_list_clusters | ❌ FAILED |
| test_create_cluster | ❌ FAILED |
| test_create_knowledge_space | ✅ PASSED |
| test_extract_knowledge | ✅ PASSED |
| test_list_spaces | ✅ PASSED |
| test_orchestrate_agent_tools | ✅ PASSED |
| FAILED | ⚠️ test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_list_clusters |
| FAILED | ⚠️ test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_create_cluster |

<details>
<summary>pytest 输出摘要</summary>

```
test_chain2_orchestrator_agent.py::TestChain2HealthCheck::test_orchestrator_health PASSED [ 10%]
test_chain2_orchestrator_agent.py::TestChain2HealthCheck::test_knowledge_engine_health PASSED [ 20%]
test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_list_providers PASSED [ 30%]
test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_list_environments PASSED [ 40%]
test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_list_clusters FAILED [ 50%]
test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_create_cluster FAILED [ 60%]
test_chain2_orchestrator_agent.py::TestChain2AgentToolCall::test_create_knowledge_space PASSED [ 70%]
test_chain2_orchestrator_agent.py::TestChain2AgentToolCall::test_extract_knowledge PASSED [ 80%]
test_chain2_orchestrator_agent.py::TestChain2AgentToolCall::test_list_spaces PASSED [ 90%]
test_chain2_orchestrator_agent.py::TestChain2EndToEnd::test_orchestrate_agent_tools PASSED [100%]
    assert passed, detail
E   AssertionError: 请求异常: HTTPConnectionPool(host='10.43.116.180', port=8085): Read timed out. (read timeout=10)
    assert passed, detail
E   AssertionError: status=403, phase=None, cluster=it-test-cluster-6e3100df
FAILED test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_list_clusters - AssertionError: 请求异常: HTTPConnectionPool(host='10.43.116.180', port=8085): Read timed out. (read timeout=10)
FAILED test_chain2_orchestrator_agent.py::TestChain2Orchestrator::test_create_cluster - AssertionError: status=403, phase=None, cluster=it-test-cluster-6e3100df
==================== 2 failed, 8 passed in 79.42s (0:01:19) ====================
```

</details>

---

### 链路3: 查询改写→物化视图路由 — ❌ 失败

- **耗时**: 47.06s
- **退出码**: 1

| 测试 | 状态 |
|------|------|
| test_sql_gateway_health | ✅ PASSED |
| test_list_views | ❌ FAILED |
| test_add_view | ❌ FAILED |
| test_rewrite_execute | ❌ FAILED |
| test_route_decision | ❌ FAILED |
| test_candidates | ❌ FAILED |
| test_list_rules | ❌ FAILED |
| test_add_view_then_rewrite | ❌ FAILED |
| FAILED | ⚠️ test_chain3_query_rewrite_mv.py::TestChain3ViewManagement::test_list_views |
| FAILED | ⚠️ test_chain3_query_rewrite_mv.py::TestChain3ViewManagement::test_add_view |
| FAILED | ⚠️ test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_rewrite_execute |
| FAILED | ⚠️ test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_route_decision |
| FAILED | ⚠️ test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_candidates |
| FAILED | ⚠️ test_chain3_query_rewrite_mv.py::TestChain3RuleManagement::test_list_rules |
| FAILED | ⚠️ test_chain3_query_rewrite_mv.py::TestChain3EndToEnd::test_add_view_then_rewrite |

<details>
<summary>pytest 输出摘要</summary>

```
test_chain3_query_rewrite_mv.py::TestChain3HealthCheck::test_sql_gateway_health PASSED [ 12%]
test_chain3_query_rewrite_mv.py::TestChain3ViewManagement::test_list_views FAILED [ 25%]
test_chain3_query_rewrite_mv.py::TestChain3ViewManagement::test_add_view FAILED [ 37%]
test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_rewrite_execute FAILED [ 50%]
test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_route_decision FAILED [ 62%]
test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_candidates FAILED [ 75%]
test_chain3_query_rewrite_mv.py::TestChain3RuleManagement::test_list_rules FAILED [ 87%]
test_chain3_query_rewrite_mv.py::TestChain3EndToEnd::test_add_view_then_rewrite FAILED [100%]
    assert passed, detail
E   AssertionError: status=403, body=
    assert passed, detail
E   AssertionError: status=403, view=mv_test_91e13971, body=
    assert passed, detail
E   AssertionError: status=403, rewritten=None, matchedView=None
    assert passed, detail
E   AssertionError: status=403, matched=None, viewName=None
    assert passed, detail
E   AssertionError: status=403, body=
    assert passed, detail
E   AssertionError: status=403, body=
    assert passed, detail
E   AssertionError: 链路异常: 添加视图失败: 
FAILED test_chain3_query_rewrite_mv.py::TestChain3ViewManagement::test_list_views - AssertionError: status=403, body=
FAILED test_chain3_query_rewrite_mv.py::TestChain3ViewManagement::test_add_view - AssertionError: status=403, view=mv_test_91e13971, body=
FAILED test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_rewrite_execute - AssertionError: status=403, rewritten=None, matchedView=None
FAILED test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_route_decision - AssertionError: status=403, matched=None, viewName=None
FAILED test_chain3_query_rewrite_mv.py::TestChain3RewriteRoute::test_candidates - AssertionError: status=403, body=
FAILED test_chain3_query_rewrite_mv.py::TestChain3RuleManagement::test_list_rules - AssertionError: status=403, body=
FAILED test_chain3_query_rewrite_mv.py::TestChain3EndToEnd::test_add_view_then_rewrite - AssertionError: 链路异常: 添加视图失败: 
========================= 7 failed, 1 passed in 46.53s =========================
```

</details>

---

### 链路4: SecurityFacade→加解密 — ❌ 失败

- **耗时**: 47.32s
- **退出码**: 1

| 测试 | 状态 |
|------|------|
| test_encaps_health | ✅ PASSED |
| test_security_status | ❌ FAILED |
| test_mask_phone | ❌ FAILED |
| test_mask_id_card | ❌ FAILED |
| test_mask_email | ❌ FAILED |
| test_auth_check | ❌ FAILED |
| test_list_audit_events | ❌ FAILED |
| test_mask_then_audit | ❌ FAILED |
| FAILED | ⚠️ test_chain4_security_crypto.py::TestChain4SecurityStatus::test_security_status |
| FAILED | ⚠️ test_chain4_security_crypto.py::TestChain4Mask::test_mask_phone |
| FAILED | ⚠️ test_chain4_security_crypto.py::TestChain4Mask::test_mask_id_card |
| FAILED | ⚠️ test_chain4_security_crypto.py::TestChain4Mask::test_mask_email |
| FAILED | ⚠️ test_chain4_security_crypto.py::TestChain4AuthCheck::test_auth_check |
| FAILED | ⚠️ test_chain4_security_crypto.py::TestChain4Audit::test_list_audit_events |
| FAILED | ⚠️ test_chain4_security_crypto.py::TestChain4EndToEnd::test_mask_then_audit |

<details>
<summary>pytest 输出摘要</summary>

```
test_chain4_security_crypto.py::TestChain4HealthCheck::test_encaps_health PASSED [ 12%]
test_chain4_security_crypto.py::TestChain4SecurityStatus::test_security_status FAILED [ 25%]
test_chain4_security_crypto.py::TestChain4Mask::test_mask_phone FAILED   [ 37%]
test_chain4_security_crypto.py::TestChain4Mask::test_mask_id_card FAILED [ 50%]
test_chain4_security_crypto.py::TestChain4Mask::test_mask_email FAILED   [ 62%]
test_chain4_security_crypto.py::TestChain4AuthCheck::test_auth_check FAILED [ 75%]
test_chain4_security_crypto.py::TestChain4Audit::test_list_audit_events FAILED [ 87%]
test_chain4_security_crypto.py::TestChain4EndToEnd::test_mask_then_audit FAILED [100%]
    assert passed, detail
E   AssertionError: status=403, enabled=None, crypto=None, mask=None
    assert passed, detail
E   AssertionError: status=403, type=None, masked=
    assert passed, detail
E   AssertionError: status=403, type=None, masked=
    assert passed, detail
E   AssertionError: status=403, type=None, masked=
    assert passed, detail
E   AssertionError: status=403, allowed=None, principal=None
    assert passed, detail
E   AssertionError: status=403, body=
    assert passed, detail
E   AssertionError: 链路异常: SecurityFacade 状态查询失败
FAILED test_chain4_security_crypto.py::TestChain4SecurityStatus::test_security_status - AssertionError: status=403, enabled=None, crypto=None, mask=None
FAILED test_chain4_security_crypto.py::TestChain4Mask::test_mask_phone - AssertionError: status=403, type=None, masked=
FAILED test_chain4_security_crypto.py::TestChain4Mask::test_mask_id_card - AssertionError: status=403, type=None, masked=
FAILED test_chain4_security_crypto.py::TestChain4Mask::test_mask_email - AssertionError: status=403, type=None, masked=
FAILED test_chain4_security_crypto.py::TestChain4AuthCheck::test_auth_check - AssertionError: status=403, allowed=None, principal=None
FAILED test_chain4_security_crypto.py::TestChain4Audit::test_list_audit_events - AssertionError: status=403, body=
FAILED test_chain4_security_crypto.py::TestChain4EndToEnd::test_mask_then_audit - AssertionError: 链路异常: SecurityFacade 状态查询失败
========================= 7 failed, 1 passed in 46.83s =========================
```

</details>

---

## 结论

⚠️ **部分链路测试通过**（1/4），请检查失败链路的详细日志。

### 失败原因排查建议

1. **Pod 未就绪**: 检查 `kubectl get pods -n shuqing`，确认 READY=1/1
2. **服务不可达**: 检查 Service ClusterIP 是否可访问（`curl http://<IP>:<port>/health`）
3. **JWT 认证失败**: 确认 JWT_SECRET 与各组件配置一致
4. **Pod 不断重启**: 检查 `kubectl logs <pod> -n shuqing` 查看启动错误
5. **环境变量冲突**: 检查 K8s Service 环境变量是否与应用配置冲突
