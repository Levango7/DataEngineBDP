# Realtime Lane（CDC→Iceberg 端到端验收泳道）

立项件 `docs/realtime-chain-completion-plan.md` Phase D 判据的泳道基建：
**CDC 灌一行 → Iceberg 表可查，P95 ≤ 60s**（首现口径：每行注入时刻 → 该行在
Iceberg 表中首次可读时刻）。

## 链路形态

PostgreSQL 16（逻辑复制 wal_level=logical，pgoutput）→ `CdcFrameworkMain`
作业进程（`flink-connector-postgres-cdc` 直连，**无需 Kafka/Debezium
Connect**——source 内反序列化器直接产 ChangeRecord）→ Iceberg HadoopCatalog
（`file://` warehouse）。

## 文件

- `LaneTools.java`：目标表 create / count / dump 三模式读回工具
- `lane-inject.sh`：带时间戳注入（`clock_timestamp()`）
- `lane-burst.sh`：注入与首现追踪并行（真 P95 口径）
- `measure2.py`：P95 计算与判据断言（超阈值 exit 1）
- `lane-postgres-iceberg-ci.yaml`：作业配置模板（占位符由执行环境替换）
- `.github/workflows/it-realtime-lane.yml`：CI 泳道（nightly + dispatch，
  不进 PR 必检，#141 范式）

## 环境变量约定

- `LANE_CP`：作业/工具 classpath（模块依赖树 + iceberg-api jar；
  `mvn dependency:build-classpath` 产出后前置 kafka-clients 3.9.2——
  kafka-clients 4.x 移除了 Debezium 1.9 依赖的 `Sanitizer`）
- `LANE_LOG`：注入/追踪日志目录

## 本机复跑（WSL 无 Docker 泳道，2026-11-15 首测配方）

1. Postgres 16：`wal_level = logical`；建角色（superuser——Debezium 自动建
   `FOR ALL TABLES` publication 需要）、库 `bdp_lane`、表 `public.mv_sales_lane`
   （id bigint 主键，name text，amount double precision，src_ts timestamptz）
2. `mvn dependency:build-classpath` → `LANE_CP`（前置 kafka-clients 3.9.2，
   拼 iceberg-api-1.12.0）
3. `java ... LaneTools create <warehouse> dw mv_sales_lane`
4. 作业常驻须 **systemd 托管**（wsl.exe 会话销毁会带走 nohup/setsid 后台进程）：
   `systemd-run --unit=bdp-lane-job ... CdcFrameworkMain --config <yaml>`
5. `lane-burst.sh` 注入并追踪 → `measure2.py` 断言

## 口径与边界

- Java 17 运行 JVM 需完整模块放行集（与 surefire argLine 同款 add-opens）
- 本机为共享开发机，性能数据为下界；CI runner 数据同理
- 首现追踪的采样分辨率约 2-3 秒（每轮 dump 含 JVM 启动）
- 首测记录（2026-11-15，WSL）：n=25 ⇒ p95=11.3s；n=50 ⇒ p95=20.1s
