# 轻量 Doris 本地实测环境（Windows + Docker Desktop / WSL2）

## 用途
- 验证 sql-gateway → Doris 查询链路（JDBC 真实提交）
- 验证 Doris 真实扫描字节采集（audit_log 指纹匹配，DorisScanStatsClient）
- 统一运维台 / 成本计费联调

## 镜像
```
docker.m.daocloud.io/apache/doris:doris-fe-2.1.7
docker.m.daocloud.io/apache/doris:doris-be-2.1.7
```

## 启动（FE + BE）

```bash
# FE（健康检查 http://localhost:18031/api/health）
docker run -d --name sq-doris-lite-fe \
  --network dev_sq-doris-lite-net \
  -e FE_SERVERS=fe1:172.30.88.2:9010 \
  -p 18030:9030 -p 18031:8030 -p 18035:9010 \
  docker.m.daocloud.io/apache/doris:doris-fe-2.1.7

# BE（需先设置 WSL2 sysctl，见下节）
docker run -d --name sq-doris-lite-be \
  --network dev_sq-doris-lite-net --ip 172.30.88.3 \
  -e BE_ADDR=172.30.88.3:9050 \
  -e FE_SERVERS=fe1:172.30.88.2:9010 \
  -v dev_sq-doris-be-data:/opt/apache-doris/be/storage \
  docker.m.daocloud.io/apache/doris:doris-be-2.1.7
```

## ⚠️ 关键前置：WSL2 sysctl（Docker Desktop 必须）

Doris BE 启动硬性要求：
- `vm.max_map_count >= 2000000`（默认 1048576 会致 BE 退出）
- 禁用 swap（`vm.swappiness=0`）

```bash
# 每次 Docker Desktop 启动后需执行（docker-desktop 发行版重启会重置）
wsl -d docker-desktop -u root -- sysctl -w vm.max_map_count=2000000
wsl -d docker-desktop -u root -- sysctl -w vm.swappiness=0
```

**持久化**（避免每次手动）：在 Windows 用户目录创建 `%USERPROFILE%\.wslconfig`：
```ini
[wsl2]
# 如需持久 sysctl，可在 WSL 发行版内配置 /etc/sysctl.d/，
# 但 docker-desktop 受管发行版重启重置，建议在 Docker Desktop 启动后
# 用上方的 wsl 命令设置，或写一个启动辅助脚本。
```

## 启用审计日志（扫描字节采集必需）

```bash
docker exec sq-doris-lite-fe mysql -uroot -P9030 -h127.0.0.1 \
  -e "SET GLOBAL enable_audit_plugin=true;"
```

## 验证链路

```bash
# 1. 健康
curl -s http://localhost:18031/api/health   # online_backend_num=1

# 2. 建表 + 查询
docker exec sq-doris-lite-fe mysql -uroot -P9030 -h127.0.0.1 -e "SELECT COUNT(*) FROM sq_test.scan_bytes_probe;"

# 3. 扫描字节（等 audit_log 异步批写 ~60s）
docker exec sq-doris-lite-fe mysql -uroot -P9030 -h127.0.0.1 \
  -e "SELECT scan_bytes FROM __internal_schema.audit_log WHERE is_query=1 AND stmt LIKE 'SELECT city%' ORDER BY time DESC LIMIT 1;"
# 期望：scan_bytes=122880（真实扫描字节，供计费 est=false）

# 4. sql-gateway 连接（环境变量注入，K8s 默认不变）
# DORIS_URL=http://localhost:18030  →  resolveDorisJdbcUrl 转 jdbc:mysql://localhost:18030
```

## 故障排查
| 症状 | 原因 | 处理 |
|------|------|------|
| BE 容器 Exited(0) | max_map_count 不足 | 执行 sysctl 修复后重启 BE |
| `Please disable swap memory` | swappiness 未归零 | `sysctl -w vm.swappiness=0` |
| BE 心跳 Connection refused | 容器 IP 变化 | 用 `--ip` 固定 IP 重建 BE |
| audit_log 无记录 | 审计插件未开 / 异步批写延迟 | `SET GLOBAL enable_audit_plugin=true`，等待 ~60s |
