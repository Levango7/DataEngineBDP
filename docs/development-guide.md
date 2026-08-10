# 开发指南

> 本指南描述数据引擎大数据平台的开发环境搭建、各语言构建命令、测试命令、代码规范与调试技巧。

## 环境要求

### 必需工具

| 工具 | 最低版本 | 推荐版本 | 安装方式 |
| --- | --- | --- | --- |
| JDK | 17 | 17.0.10 (LTS) | [Adoptium](https://adoptium.net/) |
| Maven | 3.9 | 3.9.6 | [maven.apache.org](https://maven.apache.org/download.cgi) |
| Go | 1.23 | 1.23.4 | [go.dev](https://go.dev/dl/) |
| Python | 3.11 | 3.11.8 | [python.org](https://www.python.org/downloads/) |
| Node.js | 20 | 20.11 LTS | [nodejs.org](https://nodejs.org/) |
| Git | 2.40 | 2.44 | [git-scm.com](https://git-scm.com/) |
| Docker | 24.0 | 24.0.7 | [docker.com](https://www.docker.com/) |
| Make | 4.0 | 4.4 | 系统包管理器 |

### 可选工具

| 工具 | 用途 |
| --- | --- |
| kubectl | 本地集群调试 |
| Helm | Chart 本地调试 |
| kind | 本地 K8s 集群 |
| jq | JSON 处理 |
| httpie | API 调试 |

### 环境变量

```bash
# Java
export JAVA_HOME=/path/to/jdk-17
export PATH=$JAVA_HOME/bin:$PATH

# Go
export GOROOT=/path/to/go-1.23
export GOPATH=$HOME/go
export PATH=$GOROOT/bin:$GOPATH/bin:$PATH

# Python（建议使用虚拟环境）
python -m venv .venv
source .venv/bin/activate  # Linux/macOS
.venv\Scripts\activate     # Windows

# Node.js（建议使用 nvm 管理版本）
nvm use 20
```

## 项目结构

```
DataEngineBDP/
├── platform/          # 自研组件（31 个）
│   ├── encaps-layer/  # Java / Spring Boot
│   ├── sql-gateway/   # Java / Spring Boot
│   ├── rule-engine/   # Java / Spring Boot
│   ├── catalog/       # Go / Gin
│   ├── dqctl/         # Go CLI / Cobra
│   ├── llmops/        # Python / FastAPI
│   └── ...
├── frontend/          # Vue3 + TypeScript
├── ske/               # SKE 发行版
├── design/deploy/charts/  # Helm Chart
├── tests/integration/    # 集成测试
└── scripts/poc/      # PoC 脚本
```

## 各语言构建命令

### Java 组件

Java 组件基于 Spring Boot 3.2 + Maven 3.9，Java 17。

```bash
# 编译单个组件
mvn -f platform/encaps-layer/pom.xml clean compile

# 打包单个组件（含测试）
mvn -f platform/encaps-layer/pom.xml clean package

# 打包单个组件（跳过测试）
mvn -f platform/encaps-layer/pom.xml clean package -DskipTests

# 运行单个组件
java -jar platform/encaps-layer/target/encaps-layer-*.jar

# 构建 Docker 镜像
docker build -t shuqing/encaps-layer:latest platform/encaps-layer/
```

全部 Java 组件清单：

| 组件 | 构建命令 |
| --- | --- |
| encaps-layer | `mvn -f platform/encaps-layer/pom.xml clean package` |
| sql-gateway | `mvn -f platform/sql-gateway/pom.xml clean package` |
| rule-engine | `mvn -f platform/rule-engine/pom.xml clean package` |
| tag-engine | `mvn -f platform/tag-engine/pom.xml clean package` |
| metadata-collector | `mvn -f platform/governance/metadata-collector/pom.xml clean package` |
| lineage-analyzer | `mvn -f platform/governance/lineage-analyzer/pom.xml clean package` |
| infra-provider-xinchang | `mvn -f platform/infra-provider-xinchang/pom.xml clean package` |
| infra-provider-cloud | `mvn -f platform/infra-provider-cloud/pom.xml clean package` |
| infra-provider-private | `mvn -f platform/infra-provider-private/pom.xml clean package` |
| infra-orchestrator | `mvn -f platform/infra-orchestrator/pom.xml clean package` |

### Go 组件

Go 组件基于 Go 1.23 + Gin / Cobra。

```bash
# 下载依赖
go -C platform/catalog mod download

# 编译单个组件
go -C platform/catalog build -o bin/catalog ./cmd/catalog

# 运行单个组件
go -C platform/catalog run ./cmd/catalog

# 构建 CLI 工具
go -C platform/dqctl build -o bin/dqctl ./cmd/dqctl

# 构建 Docker 镜像
docker build -t shuqing/catalog:latest platform/catalog/
```

全部 Go 组件清单：

| 组件 | 构建命令 |
| --- | --- |
| catalog | `go -C platform/catalog build ./...` |
| dqctl | `go -C platform/dqctl build ./...` |
| vector-engine | `go -C platform/vector-engine build ./...` |
| llm-gateway | `go -C platform/llm-gateway build ./...` |
| infra-provider-baremetal | `go -C platform/infra-provider-baremetal build ./...` |

### Python 组件

Python 组件基于 Python 3.11 + FastAPI + Pydantic。

```bash
# 安装依赖（开发模式）
pip install -e platform/llmops/

# 运行单个组件
python -m llmops.main

# 或使用 uvicorn
uvicorn platform.llmops.main:app --reload --port 8000

# 构建 Docker 镜像
docker build -t shuqing/llmops:latest platform/llmops/
```

全部 Python 组件清单：

| 组件 | 安装命令 |
| --- | --- |
| llmops | `pip install -e platform/llmops/` |
| knowledge-engine | `pip install -e platform/knowledge-engine/` |
| ml-platform | `pip install -e platform/ml-platform/` |
| industry-templates | `pip install -e platform/industry-templates/` |
| business-portal | `pip install -e platform/business-portal/` |
| open-api-catalog | `pip install -e platform/open-api-catalog/` |
| asset-exchange | `pip install -e platform/asset-exchange/` |
| operations | `pip install -e design/deploy/services/operations/` |

### 前端

前端基于 Vue3 + TypeScript strict + Vite 6 + Pinia。

```bash
cd frontend

# 安装依赖
npm install

# 开发模式（热重载）
npm run dev

# 类型检查
npm run type-check

# 代码检查
npm run lint

# 代码格式化
npm run format

# 构建生产包
npm run build

# 预览构建结果
npm run preview
```

### Helm Chart

```bash
# 生成全部 Chart
python design/deploy/charts/_generate_charts.py

# 验证全部 Chart
python design/deploy/charts/_validate_charts.py

# 单个 Chart 模板渲染验证
helm template spark design/deploy/charts/spark -f design/deploy/values/spark-values.yaml

# lint 单个 Chart
helm lint design/deploy/charts/spark
```

## 测试命令

### 单元测试

```bash
# Java 单元测试
mvn -f platform/encaps-layer/pom.xml test
mvn -f platform/sql-gateway/pom.xml test
mvn -f platform/rule-engine/pom.xml test

# Go 单元测试
go -C platform/catalog test ./...
go -C platform/dqctl test ./...
go -C platform/vector-engine test ./...

# Python 单元测试
pytest platform/llmops/tests/ -v
pytest platform/knowledge-engine/tests/ -v

# 前端单元测试
cd frontend && npm run test && cd ..
```

### 集成测试

集成测试位于 `tests/integration/`，使用 pytest + docker-compose 编排。

```bash
cd tests/integration

# 启动依赖
docker-compose up -d

# 运行全部集成测试
pytest -v

# 运行单个组件集成测试
pytest test_catalog.py -v
pytest test_encaps.py -v
pytest test_rule_engine.py -v
pytest test_sql_gateway.py -v

# 生成测试报告
pytest -v --html=report.html --self-contained-html

# 清理
docker-compose down
```

### 端到端 PoC

```bash
# 运行全部 PoC
bash scripts/poc/run-poc.sh

# 逐组件验证
bash scripts/poc/verify-encaps.sh
bash scripts/poc/verify-sql-gateway.sh
bash scripts/poc/verify-rule-engine.sh
bash scripts/poc/verify-catalog.sh
```

### 测试覆盖率

```bash
# Java 覆盖率（JaCoCo）
mvn -f platform/encaps-layer/pom.xml test
# 报告位于 platform/encaps-layer/target/site/jacoco/index.html

# Go 覆盖率
go -C platform/catalog test -coverprofile=coverage.out ./...
go tool cover -html=platform/catalog/coverage.out

# Python 覆盖率
pytest platform/llmops/tests/ --cov=llmops --cov-report=html

# 前端覆盖率
cd frontend && npm run test -- --coverage && cd ..
```

## 代码规范

### Java

- 格式化：`mvn spotless:apply`
- 检查：`mvn spotless:check`
- 类名 PascalCase，方法 / 变量 camelCase，常量 UPPER_SNAKE_CASE。
- public 方法必须有 Javadoc。
- 异常使用自定义 `BusinessException` / `SystemException`，不裸抛 `RuntimeException`。

### Go

- 格式化：`gofmt -s -w .`
- 检查：`go vet ./...`
- 包名全小写单单词。
- 导出标识符必须有注释，注释以标识符名称开头。
- 不忽略 error 返回值。

### Python

- 格式化：`black . && isort .`
- 检查：`ruff check . && mypy --strict .`
- 遵循 PEP 8。
- 函数与类必须有 docstring（Google 风格）。
- 异步优先使用 `async def`。

### TypeScript / Vue

- 格式化：`npm run format`
- 检查：`npm run lint && npm run type-check`
- 启用 `strict: true`，禁止 `any`。
- 使用 Composition API + `<script setup lang="ts">`。
- 组件名 PascalCase。

### 命名约定

详见 [CONVENTIONS.md](../CONVENTIONS.md)。

## 调试技巧

### Java 组件调试

```bash
# 以调试模式启动
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
  -jar platform/encaps-layer/target/encaps-layer-*.jar

# IntelliJ IDEA 远程调试：Run -> Edit Configurations -> Remote JVM Debug，端口 5005
```

### Go 组件调试

```bash
# 以调试模式启动
dlv debug ./cmd/catalog --headless --listen=:2345 --api-version=2

# VS Code launch.json 配置：
# {
#   "type": "go",
#   "request": "attach",
#   "name": "Attach to Go",
#   "mode": "remote",
#   "port": 2345,
#   "host": "127.0.0.1"
# }
```

### Python 组件调试

```bash
# 热重载模式
uvicorn platform.llmops.main:app --reload --port 8000

# 断点调试
python -m pdb -m llmops.main

# VS Code：在 launch.json 中配置 FastAPI 调试
```

### 前端调试

```bash
# 开发模式（含热重载与 sourcemap）
npm run dev

# VS Code + Vue DevTools 扩展
# 浏览器安装 Vue.js devtools 扩展
```

### K8s 组件调试

```bash
# 端口转发至本地
kubectl port-forward -n shuqing-system svc/encaps-layer 8080:80

# 查看日志
kubectl logs -n shuqing-system -f deployment/encaps-layer

# 进入 Pod
kubectl exec -n shuqing-system -it deployment/encaps-layer -- /bin/sh
```

## Docker 本地构建

### 构建全部组件镜像

```bash
# Java 组件
docker build -t shuqing/encaps-layer:latest platform/encaps-layer/
docker build -t shuqing/sql-gateway:latest platform/sql-gateway/
docker build -t shuqing/rule-engine:latest platform/rule-engine/

# Go 组件
docker build -t shuqing/catalog:latest platform/catalog/
docker build -t shuqing/dqctl:latest platform/dqctl/

# Python 组件
docker build -t shuqing/llmops:latest platform/llmops/

# 前端
docker build -t shuqing/frontend:latest frontend/
```

### 多 arch 镜像构建

```bash
# 创建 buildx builder
docker buildx create --name shuqing-builder --use

# 构建并推送多 arch 镜像
docker buildx build --platform linux/amd64,linux/arm64 \
  -t shuqing/encaps-layer:latest \
  --push platform/encaps-layer/
```

### 本地运行

```bash
# 使用 docker-compose 启动全部依赖
cd tests/integration
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f encaps-layer

# 清理
docker-compose down -v
```

## CI/CD

CI/CD 流水线定义于 `.github/workflows/`。

| 工作流 | 文件 | 触发条件 | 内容 |
| --- | --- | --- | --- |
| CI | `.github/workflows/ci.yml` | push / PR | 多语言构建 + 单元测试 + 集成测试 + lint |
| Release | `.github/workflows/release.yml` | tag 推送 | 多 arch 镜像构建 + Helm Chart 打包 + GitHub Release 发布 |

### 本地模拟 CI

```bash
# 安装 act（GitHub Actions 本地运行器）
# https://github.com/nektos/act

# 模拟 CI 工作流
act -W .github/workflows/ci.yml

# 模拟 Release 工作流
act -W .github/workflows/release.yml
```