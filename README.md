# MAOP 数据引擎 BDP

> Big Data Platform — 基于 Spring Boot 3.2.5 的微服务架构大数据平台引擎

## 项目简介

MAOP 数据引擎 BDP 是一个多模块微服务架构的大数据平台，提供数据封装、SQL 网关、联邦查询、数据治理、成本运营、标签引擎等功能。

## 技术栈

- **语言**: Java 17
- **框架**: Spring Boot 3.2.5
- **构建**: Gradle 8.5（多项目）
- **数据库**: PostgreSQL 16
- **缓存**: Redis 7
- **SQL 引擎**: Apache Calcite
- **认证**: JWT (JJWT)
- **容器化**: Docker + Docker Compose

## 模块清单

| 模块 | 说明 | 类型 | 端口 |
|------|------|------|------|
| bigdata-encaps | 大数据封装层 | Spring Boot | 8081 |
| encaps | 封装层（含国密） | Spring Boot | 8082 |
| federated | 联邦查询 | Spring Boot | 8083 |
| finops | 成本运营 | Spring Boot | 8084 |
| function | 函数服务 | Spring Boot | 8086 |
| governance | 数据治理 | Spring Boot | 8087 |
| infra | 基础设施编排 | Spring Boot | 8088 |
| ruleengine | 规则引擎 | Spring Boot | 8090 |
| sqlgateway | SQL 网关 | Spring Boot | 8091 |
| streambatch | 流批一体 | Spring Boot | 8092 |
| tagengine | 标签引擎 | Spring Boot | 8093 |
| common-health | 共享健康检查库 | 库模块 | - |
| flinkcdc | Flink CDC | 库模块 | - |
| rule | 规则定义 | 库模块 | - |

## 快速开始

### 前置条件

- JDK 17+
- Gradle 8.5+（或使用 Gradle Wrapper）
- Docker 24+（可选，用于容器化部署）

### 本地构建

```bash
# 编译所有模块（跳过测试）
gradle build -x test

# 运行测试
gradle test

# 构建可执行 jar
gradle bootJar
```

### Docker 部署

```bash
# 构建并启动全部服务
docker compose up -d

# 查看日志
docker compose logs -f

# 停止
docker compose down
```

## 项目结构

```
dataenginebdp/
├── build.gradle                    # 根构建文件
├── settings.gradle                 # 模块声明
├── docker-compose.yml              # 容器编排
├── com/levango7/dataenginebdp/     # 源码根
│   ├── bigdata/encaps/             # 大数据封装
│   ├── common-health/              # 共享健康检查
│   ├── encaps/                     # 封装层
│   ├── federated/                  # 联邦查询
│   ├── finops/                     # 成本运营
│   ├── flinkcdc/                   # Flink CDC
│   ├── function/                   # 函数服务
│   ├── governance/                 # 数据治理
│   ├── infra/                      # 基础设施
│   ├── rule/                       # 规则定义
│   ├── ruleengine/                 # 规则引擎
│   ├── sqlgateway/                 # SQL 网关
│   ├── streambatch/                # 流批一体
│   └── tagengine/                  # 标签引擎
├── .github/workflows/              # CI/CD 流水线
└── docs/                           # 文档
```

## CI/CD

项目使用 GitHub Actions 进行持续集成和部署，流水线包括：
1. **Build** — 编译所有模块
2. **Test** — 执行单元测试
3. **Image** — 构建并推送 Docker 镜像
4. **Deploy** — 部署到目标环境

## 许可证

私有项目，版权所有。