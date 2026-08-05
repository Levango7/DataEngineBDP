# dqctl

数擎大数据平台命令行管理工具，基于 Cobra + Viper 构建，提供声明式资源管理能力。

## 功能

- `dqctl init` — 交互式初始化配置文件（`~/.dqctl/config.yaml`）
- `dqctl apply -f <file>` — 应用声明式资源配置（YAML）
- `dqctl query <sql>` — 通过 SQL 网关执行 SQL 查询
- `dqctl status` — 查询平台各组件健康状态
- `dqctl version` — 输出版本信息

## 安装

### 源码构建

```bash
go build -o dqctl .
```

### Docker 构建

```bash
docker build -t dqctl:0.1.0 .
```

## 使用示例

```bash
# 初始化配置
dqctl init

# 应用声明式资源
dqctl apply -f workspace.yaml

# 仅校验不执行
dqctl apply -f workspace.yaml --dry-run

# 执行 SQL 查询
dqctl query "SELECT * FROM users LIMIT 10" --engine trino

# 查询平台状态
dqctl status

# 查看版本
dqctl version
```

## 全局参数

| 参数 | 说明 |
| --- | --- |
| `--config` | 指定配置文件路径（默认 `~/.dqctl/config.yaml`） |
| `--tenant` | 指定租户 ID |
| `--verbose, -v` | 输出详细日志 |
| `--output, -o` | 输出格式（json/yaml/table） |

## 配置文件示例

```yaml
platform_url: https://platform.example.com
tenant_id: default
token: <your-token>
output: table
```