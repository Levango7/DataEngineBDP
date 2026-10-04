# 跨域数据面复制测试

> 在本地双 S3 兼容端点（缺省 MinIO）上复现「源域写入 → 跨域复制 → 目标域读取 → sha256 校验」真实链路，
> 产出**非 simulate** 的验证报告。

## 目录结构

```
tests/cross-domain-replication/
├── results/                       # 运行产物（.gitignore 忽略，不入库）
│   ├── cdr-report-<时间戳>.json   # 单次运行的完整报告（机器可读）
│   ├── cdr-report-<时间戳>.md     # 同次运行的可读报告
│   ├── cdr-report-latest.json     # 最近一次运行副本
│   ├── cdr-report-latest.md
│   └── mvn-it.log                 # 脚本运行时的 Maven 日志
└── README.md
```

本目录只存放**产物与说明**；可执行资产（编排脚本、compose、IT）分散在仓库既有位置，见下节。

## 链路入口

| 环节 | 位置 | 说明 |
| --- | --- | --- |
| 编排脚本（推荐入口） | `scripts/infra/test-replication-it.sh` | 起容器 → 等健康 → 跑 IT → 断言产物为 `real`，拒绝 simulate |
| 复现环境 | `platform/storage-io/docker/docker-compose.replication.yml` | 双 S3 兼容实例（缺省 MinIO；源 `:9100` / 目标 `:9110`）+ `mc` 建桶 `xdomain` |
| 集成测试 | `platform/storage-io/src/test/java/.../replication/CrossDomainReplicationIT.java` | 门禁 `@EnabledIfSystemProperty(named="replication.it", matches="true")` |
| 被测实现 | `platform/storage-io/src/main/java/.../replication/ObjectReplicator.java` | 复制流程：list → stat → LWW 决策 → 流式 sha256 → 读回比对 |
| 冲突策略 | `platform/storage-io/src/main/java/.../replication/LastWriteWinsPolicy.java` | 基于 S3 `HeadObject.lastModified`（秒级精度）的 LWW |
| 存储接口扩展 | `platform/storage-io/src/main/java/.../storage/api/ObjectStore.java` | 新增 `statObject(key)` 与 `endpoint()` |

**隐式依赖**：复制前缀必须是 `_system/` 开头（IT 内为 `_system/xdomain/demo/`）。
无租户上下文时，`TenantPathMapper` 只对 `_system/` 前缀直通，其他前缀会 fail-closed。

## 前置条件

1. `docker` + `docker compose` 可用
2. `mvn` 可用（JDK 17+）
3. 端口 `9100` / `9110` 未被占用

## 运行方式

### 方式一：编排脚本（一键，推荐）

```bash
bash scripts/infra/test-replication-it.sh            # 跑完自动清理容器
bash scripts/infra/test-replication-it.sh --keep     # 保留容器便于调试
bash scripts/infra/test-replication-it.sh --down-only # 仅清理容器
```

脚本会断言 `results/cdr-report-*.json` 中：`mode=real`、存在 64 位 `sourceSha256`、
`allVerified=true`，并显式拒绝任何 `simulate` / `mock` 标记。

### 方式二：手动两步

```bash
# 1) 起双实例（缺省 MinIO，含建桶）
docker compose -f platform/storage-io/docker/docker-compose.replication.yml up -d

# 2) 跑集成测试（默认关闭，需显式开关）
mvn -pl platform/storage-io test \
    -Dtest=CrossDomainReplicationIT \
    -Dreplication.it=true
```

## 可配置项

| 名称 | 类型 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `CDR_SOURCE_ENDPOINT` | 环境变量 | `http://localhost:9100` | 源域端点 |
| `CDR_TARGET_ENDPOINT` | 环境变量 | `http://localhost:9110` | 目标域端点 |
| `CDR_ACCESS_KEY` / `CDR_SECRET_KEY` | 环境变量 | `minioadmin` | 仅开发用，生产需覆盖 |
| `CDR_BUCKET` | 环境变量 | `xdomain` | 参与复制的桶 |
| `CDR_RESULTS_DIR` | 环境变量 | `tests/cross-domain-replication/results/` | 产物目录 |
| `cdr.resultsDir` | `-D` 系统属性 | 同上 | 产物目录（优先级高于环境变量） |

## 测试场景与产物

IT 单次运行覆盖四类动作并落盘报告：

| 场景 | 布置 | 期望动作 |
| --- | --- | --- |
| 常规对象 | 源有、目标无 | `COPY` |
| 冲突-源更新 | 先写目标（旧），后写源（新） | `OVERWRITE`（LWW 源优先） |
| 冲突-目标更新 | 先写源（旧），后写目标（新） | `SKIP`（不回退） |
| 幂等复跑 | 紧接第一次再跑一遍 | 全部收敛为 `SKIP`，零字节传输 |

报告含逐对象的源/目标 sha256、字节数与耗时；`verified=true` 表示流式 sha256 与目标读回结果一致。

> **`verified` 语义**：仅 `COPY` / `OVERWRITE` 会传输并独立读回校验，其 `verified` 为 `true`（内容一致）或 `false`（校验失败）。
> `SKIP` 未传输字节，`sourceSha256` / `targetSha256` 为空、`verified` 恒为 `false`，语义是**不适用**而非校验失败；
> 汇总字段 `allVerified` 仅在「无 FAILED 且全部已传输对象校验通过」时为 `true`，`SKIP` 不参与该判定。

## 最新结果摘要

详见 `results/cdr-report-latest.md`。

- 首次运行：`total=3 copied=1 overwritten=1 skipped=1 failed=0 verified=2 bytes=7168`
- 幂等复跑：`copied=0 overwritten=0 skipped=3 failed=0 bytes=0`
- `allVerified=true`，`mode=real`（无 simulate 分支）

## 与单元测试的关系

| 层级 | 位置 | 是否需 Docker |
| --- | --- | --- |
| 单元（Fake 内存存储） | `ObjectReplicatorTest`（9 例） | 否 |
| 单元（Mockito 交互验证） | `ObjectReplicatorMockTest`（3 例） | 否 |
| 集成（本目录链路） | `CrossDomainReplicationIT`（门禁） | 是 |

日常回归用 `mvn -pl platform/storage-io test`（集成测试默认跳过）；只有显式传
`-Dreplication.it=true` 才会触达本目录链路。
