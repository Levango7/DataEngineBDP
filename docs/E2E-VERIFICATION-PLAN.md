# 端到端验证计划

> 创建日期: 2026-09-15 | 对应评估报告建议② | 状态: 待执行（需 Docker Desktop）

---

## 目标

选择 1-2 个真实可部署组件（sql-gateway + catalog），在本地 Docker 环境完成端到端验证，把"80% 端到端可用"从口径变成证据。

---

## 前置条件

1. **Docker Desktop** 已启动（当前状态：已安装 v29.7.2 但 Desktop 未运行）
2. **Java 17** 可用（`E:/dev-tools/jdk17.0.20_8`）
3. **16GB 内存**（核心栈最小可跑）

---

## 执行步骤

### Step 1：启动核心基础设施

```bash
cd F:/Nexus/DataEngineBDP/docker
docker compose -f docker-compose.core.yml up -d
```

启动 5 个核心服务：
- PostgreSQL 16（端口 5432，含 12 个数据库）
- MinIO（端口 9000/9001）
- Trino（端口 8080）
- Kafka（端口 9092）
- Redis（端口 6379）

验证：`docker ps` 确认 5 个容器全部 healthy

### Step 2：构建并启动 sql-gateway

```bash
# 设置 Java 17
export JAVA_HOME="E:/dev-tools/jdk17.0.20_8"
export PATH="$JAVA_HOME/bin:$PATH"

# 构建 sql-gateway（跳过测试）
cd F:/Nexus/DataEngineBDP
mvn package -DskipTests -pl platform/sql-gateway -am -q

# 启动 sql-gateway（连接 PostgreSQL）
java -jar platform/sql-gateway/target/sql-gateway-*.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/encaps_gateway \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres
```

验证：`curl http://localhost:8081/actuator/health` 返回 `{"status":"UP"}`

### Step 3：构建并启动 catalog（Go）

```bash
cd F:/Nexus/DataEngineBDP/platform/catalog
go build -o catalog .
./catalog --port=8082 --db=postgres://postgres:postgres@localhost:5432/catalog
```

验证：`curl http://localhost:8082/health` 返回 OK

### Step 4：启动前端

```bash
cd F:/Nexus/DataEngineBDP/frontend
npm run dev
```

验证：浏览器访问 `http://localhost:5173` 能加载登录页

### Step 5：运行 E2E 测试

```bash
cd F:/Nexus/DataEngineBDP/frontend
npx playwright test tests/e2e/sql-workbench.spec.ts --reporter=verbose
npx playwright test tests/e2e/gateway.spec.ts --reporter=verbose
```

### Step 6：验证 API 端到端

```bash
# 登录获取 token
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r '.data.token')

# 查询 SQL 网关
curl -s http://localhost:8081/api/v1/sql/execute \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT 1","engine":"trino"}'

# 查询 Catalog
curl -s http://localhost:8082/api/v1/catalog/tables \
  -H "Authorization: Bearer $TOKEN"
```

---

## 预期结果

| 验证项 | 预期 | 实际 |
|--------|------|------|
| 5 个基础设施容器 | 全部 healthy | 待验证 |
| sql-gateway 健康 | UP | 待验证 |
| catalog 健康 | OK | 待验证 |
| 前端登录 | 登录页可见 | 待验证 |
| SQL 执行 | 返回查询结果 | 待验证 |
| Catalog 表列表 | 返回表列表 | 待验证 |
| E2E 测试 | sql-workbench + gateway 通过 | 待验证 |

---

## 验证报告模板

验证完成后，在 `docs/` 下创建 `E2E-VERIFICATION-REPORT.md`：

```markdown
# 端到端验证报告

> 验证日期: YYYY-MM-DD | 验证环境: 本地 Docker

## 验证结果

| 验证项 | 结果 | 证据 |
|--------|------|------|
| 基础设施 | ✅/❌ | docker ps 输出 |
| sql-gateway | ✅/❌ | health 响应 |
| catalog | ✅/❌ | health 响应 |
| 前端登录 | ✅/❌ | 截图 |
| SQL 执行 | ✅/❌ | API 响应 |
| E2E 测试 | ✅/❌ | 测试报告 |

## 结论

[基于验证结果的结论]
```

---

## 注意事项

1. **端口冲突**：确保 5432/9000/8080/9092/6379/8081/8082/5173 端口未被占用
2. **内存**：核心栈约需 4GB，sql-gateway + catalog 约需 2GB，总计 ~6GB
3. **首次构建**：Maven 和 Go 构建可能需要 5-10 分钟
4. **Docker Desktop**：需先启动 Docker Desktop，等待 Docker Engine 就绪