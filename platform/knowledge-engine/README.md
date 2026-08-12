# sq-knowledge-engine

数据引擎大数据平台 · 智能数据层 · 知识工程引擎 (L4.5.2)

## 定位
从文本中抽取实体与关系，构建知识图谱（NebulaGraph），并提供图查询 API。
复用 `governance/lineage-analyzer` 的血缘图谱基础设施思路（接口抽象 + Mock 实现）。

## 架构
- **接口抽象**：`GraphStore` / `EntityExtractor` / `RelationExtractor`
- **Mock 实现**：内存图存储 + 规则匹配抽取（用于测试与无外部依赖场景）
- **NebulaGraph 实现**：通过 `nebula3-python` SDK 调用 NebulaGraph
- **LLM 实现**：调用 `platform/llm-gateway` 进行 NER 与关系抽取

## 配置
| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `KE_HOST` | `0.0.0.0` | 监听地址 |
| `KE_PORT` | `8080` | 监听端口 |
| `KE_STORE_TYPE` | `mock` | 图存储类型: `mock` / `nebula` |
| `KE_EXTRACTOR_TYPE` | `mock` | 抽取器类型: `mock` / `llm` |
| `KE_NEBULA_HOST` | `127.0.0.1` | NebulaGraph GraphD 主机 |
| `KE_NEBULA_PORT` | `9669` | NebulaGraph GraphD 端口 |
| `KE_NEBULA_USER` | `root` | NebulaGraph 用户名 |
| `KE_NEBULA_PASSWORD` | `nebula` | NebulaGraph 密码 |
| `KE_LLM_GATEWAY_URL` | `http://localhost:8080` | LLM 网关地址 |

## 开发
```bash
pip install -e ".[test]"
pytest tests/
```

## 运行
```bash
python main.py
# API 文档: http://localhost:8080/docs
```