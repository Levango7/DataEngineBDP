#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量生成 33 个 Helm Chart 骨架（修复版）。
关联设计：design/详细设计/多平台多租户大数据平台_部署清单详细设计_v0.1.md §7
说明：仅生成 Chart 骨架级默认值，完整生产配置请通过 -f values-xxx.yaml 覆盖。
注意：包含 Go template {{ }} 语法的文件必须用普通字符串 + replace，不能用 f-string。
"""

import os

# 国内镜像源前缀
IMAGE_PREFIX = "docker.m.daocloud.io"

# 33 个新增 Chart 元数据清单
# 字段: name, description, app_version, image_repo, image_tag, service_port,
#       category, namespace, keywords(逗号分隔), replicas, cpu_req, mem_req, cpu_lim, mem_lim
CHARTS = [
    # ===== 基础设施类 (9) =====
    ("ske-infra", "SKE 发行版基础配置 - 自研 Kubernetes Engine 调优与七大支柱", "1.28.0",
     "sq-ske-infra", "1.28.0-0.1.0", 6443, "infrastructure", "kube-system",
     "ske,kubernetes,infra", 1, "0.5", "512Mi", "1", "1Gi"),
    ("cni-cilium", "Cilium CNI - eBPF 网络栈取代 kube-proxy", "1.15.0",
     "cilium/cilium", "v1.15.0", 4240, "infrastructure", "kube-system",
     "cni,cilium,network", 1, "0.5", "512Mi", "2", "2Gi"),
    ("csi-juicefs", "JuiceFS CSI 驱动 - 云原生对象存储挂载", "1.2.0",
     "juicedata/juicefs-csi-driver", "v1.2.0", 9443, "infrastructure", "kube-system",
     "csi,juicefs,storage", 2, "0.5", "512Mi", "1", "1Gi"),
    ("csi-ceph", "Ceph CSI 驱动 - RBD/CephFS 存储接入", "3.9.0",
     "quay.io/cephcsi/cephcsi", "v3.9.0", 8080, "infrastructure", "kube-system",
     "csi,ceph,storage", 2, "0.5", "512Mi", "1", "1Gi"),
    ("metallb", "MetalLB 负载均衡 - 裸金属集群 LoadBalancer 实现", "0.14.0",
     "metallb/metallb-controller", "v0.14.0", 7472, "infrastructure", "kube-system",
     "loadbalancer,metallb,bgp", 1, "0.5", "256Mi", "1", "512Mi"),
    ("node-problem-detector", "节点问题检测 - 内核与 kubelet 异常巡检", "0.8.0",
     "k8s.gcr.io/node-problem-detector", "v0.8.0", 20256, "infrastructure", "kube-system",
     "node,health,detector", 3, "0.1", "128Mi", "0.5", "256Mi"),
    ("cluster-autoscaler", "集群自动扩缩容 - 节点池弹性扩容", "1.28.0",
     "k8s.gcr.io/cluster-autoscaler", "v1.28.0", 8085, "infrastructure", "kube-system",
     "autoscaler,cluster,elastic", 1, "0.5", "512Mi", "2", "2Gi"),
    ("keda", "KEDA 弹性伸缩 - 事件驱动自动扩缩容", "2.13.0",
     "ghcr.io/kedacore/keda", "2.13.0", 8080, "infrastructure", "kube-system",
     "keda,autoscaling,event", 1, "0.5", "256Mi", "1", "1Gi"),
    ("descheduler", "重调度器 - 节点负载均衡再平衡", "0.30.0",
     "k8s.gcr.io/descheduler", "v0.30.0", 8080, "infrastructure", "kube-system",
     "descheduler,scheduling,balance", 1, "0.5", "256Mi", "1", "1Gi"),

    # ===== 大数据引擎类 (10) =====
    ("hive-metastore", "Hive Metastore 元数据服务 - 兼容 Hive 3.1 协议", "3.1.3",
     "sq-hive-metastore", "3.1.3-0.1.0", 9083, "engine", "sq-engine",
     "hive,metastore,big-data", 2, "1", "2Gi", "2", "4Gi"),
    ("iceberg-rest", "Iceberg REST Catalog - 表格式 REST 元服务", "0.7.0",
     "tabulario/iceberg-rest-catalog", "0.7.0", 8181, "engine", "sq-engine",
     "iceberg,catalog,rest", 2, "0.5", "1Gi", "1", "2Gi"),
    ("zookeeper", "ZooKeeper 协调服务 - 分布式一致性", "3.8.3",
     "bitnami/zookeeper", "3.8.3", 2181, "engine", "sq-engine",
     "zookeeper,coordination", 3, "0.5", "1Gi", "1", "2Gi"),
    ("minio", "MinIO 对象存储 - S3 兼容私有存储", "2024.8.3",
     "minio/minio", "RELEASE.2024-08-03T04-33-23Z", 9000, "engine", "sq-engine",
     "minio,s3,storage", 4, "1", "2Gi", "2", "4Gi"),
    ("redis", "Redis 缓存 - 高性能键值存储", "7.2.4",
     "bitnami/redis", "7.2.4", 6379, "engine", "sq-engine",
     "redis,cache,nosql", 1, "0.5", "512Mi", "1", "1Gi"),
    ("postgresql", "PostgreSQL 数据库 - 关系型存储", "16.4.0",
     "bitnami/postgresql", "16.4.0", 5432, "engine", "sq-engine",
     "postgres,database,sql", 1, "1", "2Gi", "2", "4Gi"),
    ("elasticsearch", "Elasticsearch 搜索引擎 - 全文检索与分析", "8.14.0",
     "docker.elastic.co/elasticsearch/elasticsearch", "8.14.0", 9200, "engine", "sq-engine",
     "elasticsearch,search", 2, "1", "2Gi", "2", "4Gi"),
    ("nebula-graph", "NebulaGraph 图数据库 - 大规模图计算", "3.6.0",
     "vesoft/nebula-graphd", "v3.6.0", 9669, "engine", "sq-engine",
     "nebula,graph,database", 2, "1", "2Gi", "2", "4Gi"),
    ("milvus", "Milvus 向量数据库 - 向量检索引擎", "2.4.0",
     "milvusdb/milvus", "v2.4.0", 19530, "engine", "sq-intelligent",
     "milvus,vector,database", 2, "1", "2Gi", "2", "4Gi"),
    ("mlflow", "MLflow 模型管理 - 实验追踪与模型注册", "2.14.0",
     "sq-mlflow", "2.14.0-0.1.0", 5000, "engine", "sq-intelligent",
     "mlflow,mlops,ml", 1, "0.5", "1Gi", "1", "2Gi"),

    # ===== 数据集成与调度类 (1) =====
    ("airflow", "Airflow 调度编排 - DAG 工作流引擎", "2.9.0",
     "apache/airflow", "2.9.0", 8080, "integration", "sq-dev",
     "airflow,scheduler,workflow", 1, "1", "2Gi", "2", "4Gi"),

    # ===== 治理类 (4) =====
    ("metadata-collector", "元数据采集 - 多源元数据自动抓取", "0.1.0",
     "sq-metadata-collector", "0.1.0", 8080, "governance", "sq-governance",
     "metadata,collector,governance", 1, "0.5", "1Gi", "1", "2Gi"),
    ("lineage-analyzer", "血缘分析 - 表级/字段级血缘与影响分析", "0.1.0",
     "sq-lineage-analyzer", "0.1.0", 8080, "governance", "sq-governance",
     "lineage,governance", 1, "0.5", "1Gi", "1", "2Gi"),
    ("data-quality", "数据质量 - 自研规则引擎质量稽核", "0.1.0",
     "sq-data-quality", "0.1.0", 8080, "governance", "sq-governance",
     "quality,governance,rule", 1, "0.5", "1Gi", "1", "2Gi"),
    ("asset-catalog", "资产目录 - 数据资产编目与检索", "0.1.0",
     "sq-asset-catalog", "0.1.0", 8080, "governance", "sq-governance",
     "catalog,asset,governance", 1, "0.5", "1Gi", "1", "2Gi"),

    # ===== 开发工具类 (2) =====
    ("jupyter", "Jupyter Notebook - 交互式数据科学环境", "4.2.0",
     "jupyter/scipy-notebook", "python-3.11", 8888, "devtool", "sq-dev",
     "jupyter,notebook,dev", 1, "1", "2Gi", "2", "4Gi"),
    ("vscode-server", "VS Code 远程 - code-server Web IDE", "4.20.0",
     "coder/code-server", "4.20.0", 8080, "devtool", "sq-dev",
     "vscode,ide,dev", 1, "0.5", "1Gi", "1", "2Gi"),

    # ===== 智能数据层类 (6) =====
    ("vector-engine", "向量检索引擎 - 嵌入向量存储与检索", "0.1.0",
     "sq-vector-engine", "0.1.0", 8080, "intelligent", "sq-intelligent",
     "vector,embedding,intelligent", 2, "1", "2Gi", "2", "4Gi"),
    ("knowledge-engine", "知识工程引擎 - RAG 与知识图谱融合", "0.1.0",
     "sq-knowledge-engine", "0.1.0", 8080, "intelligent", "sq-intelligent",
     "knowledge,rag,intelligent", 2, "1", "2Gi", "2", "4Gi"),
    ("llmops", "LLMOps 平台 - 大模型训练部署运营", "0.1.0",
     "sq-llmops", "0.1.0", 8080, "intelligent", "sq-intelligent",
     "llm,ops,intelligent", 1, "1", "2Gi", "2", "4Gi"),
    ("llm-gateway", "大模型网关 - 统一模型接口与路由", "0.1.0",
     "sq-llm-gateway", "0.1.0", 8080, "intelligent", "sq-intelligent",
     "llm,gateway,intelligent", 2, "0.5", "1Gi", "1", "2Gi"),
    ("tag-engine", "标签画像引擎 - Doris 标签与人群圈选", "0.1.0",
     "sq-tag-engine", "0.1.0", 8080, "intelligent", "sq-intelligent",
     "tag,portrait,intelligent", 1, "1", "2Gi", "2", "4Gi"),
    ("ml-platform", "机器学习平台 - MLlib/MLflow 训练编排", "0.1.0",
     "sq-ml-platform", "0.1.0", 8080, "intelligent", "sq-intelligent",
     "ml,platform,intelligent", 1, "1", "2Gi", "2", "4Gi"),

    # ===== 平台核心类 (10) =====
    ("encaps-layer", "封装层 - K8s 产品化业务语义翻译", "0.1.0",
     "sq-encapsulation", "0.1.0", 8080, "platform", "sq-system",
     "encapsulation,platform", 2, "0.5", "1Gi", "1", "2Gi"),
    ("sql-gateway", "SQL 网关 - Calcite/ANTLR 联邦查询", "0.1.0",
     "sq-sql-gateway", "0.1.0", 8080, "platform", "sq-engine",
     "sql,gateway,federation", 2, "1", "2Gi", "2", "4Gi"),
    ("catalog", "自研 Catalog - 轻量元数据目录服务", "0.1.0",
     "sq-catalog", "0.1.0", 8080, "platform", "sq-governance",
     "catalog,metadata,platform", 2, "0.5", "1Gi", "1", "2Gi"),
    ("rule-engine", "规则引擎 - 数据质量规则执行", "0.1.0",
     "sq-rule-engine", "0.1.0", 8080, "platform", "sq-governance",
     "rule,engine,quality", 1, "0.5", "1Gi", "1", "2Gi"),
    ("dqctl", "dqctl CLI - 数据质量运维命令行", "0.1.0",
     "sq-dqctl", "0.1.0", 8080, "platform", "sq-governance",
     "dqctl,cli,quality", 1, "0.5", "512Mi", "1", "1Gi"),
    ("infra-orchestrator", "跨环境编排 - 四环境统一编排器", "0.1.0",
     "sq-infra-orchestrator", "0.1.0", 8080, "platform", "sq-system",
     "orchestrator,infra,platform", 1, "0.5", "1Gi", "1", "2Gi"),
    ("infra-provider-xinchang", "信创供应 - 鲲鹏/海光/飞腾资源供应", "0.1.0",
     "sq-infra-provider-xinchuang", "0.1.0", 8080, "platform", "sq-system",
     "provider,xinchuang,infra", 1, "0.5", "512Mi", "1", "1Gi"),
    ("infra-provider-baremetal", "本地 DC 供应 - 裸金属资源供应", "0.1.0",
     "sq-infra-provider-baremetal", "0.1.0", 8080, "platform", "sq-system",
     "provider,baremetal,infra", 1, "0.5", "512Mi", "1", "1Gi"),
    ("infra-provider-cloud", "公有云供应 - 公有云 VM 资源供应", "0.1.0",
     "sq-infra-provider-cloud", "0.1.0", 8080, "platform", "sq-system",
     "provider,cloud,infra", 1, "0.5", "512Mi", "1", "1Gi"),
    ("infra-provider-private", "私有云供应 - 私有云 VM 资源供应", "0.1.0",
     "sq-infra-provider-private", "0.1.0", 8080, "platform", "sq-system",
     "provider,private,infra", 1, "0.5", "512Mi", "1", "1Gi"),

    # ===== 监控运维类 (4) =====
    ("prometheus", "Prometheus 监控 - 指标采集与时序存储", "2.54.0",
     "prom/prometheus", "v2.54.0", 9090, "monitor", "sq-monitor",
     "prometheus,monitor,metrics", 1, "1", "2Gi", "2", "4Gi"),
    ("grafana", "Grafana 面板 - 可视化监控仪表盘", "11.2.0",
     "grafana/grafana", "11.2.0", 3000, "monitor", "sq-monitor",
     "grafana,monitor,dashboard", 1, "0.5", "512Mi", "1", "1Gi"),
    ("loki", "Loki 日志 - 日志聚合与查询", "3.2.0",
     "grafana/loki", "3.2.0", 3100, "monitor", "sq-monitor",
     "loki,log,monitor", 1, "0.5", "1Gi", "1", "2Gi"),
    ("tempo", "Tempo 链路追踪 - 分布式追踪后端", "2.6.0",
     "grafana/tempo", "2.6.0", 3200, "monitor", "sq-monitor",
     "tempo,trace,monitor", 1, "0.5", "1Gi", "1", "2Gi"),
]


def gen_chart_yaml(name, desc, app_version, category, namespace, keywords):
    """生成 Chart.yaml（无 Go template 语法，可用 f-string）"""
    kw_list = "\n".join(f"  - {k}" for k in keywords.split(","))
    return f"""apiVersion: v2
name: {name}
description: "{desc} - 数据引擎大数据平台"
type: application
version: 0.1.0
appVersion: "{app_version}"
home: https://github.com/DataEngineBDP
maintainers:
  - name: DataEngineBDP Team
    email: platform@shuqing.example.com
keywords:
{kw_list}
  - shuqing-platform
  - {category}
# 部署目标 namespace（仅文档语义，实际由 helm -n 指定）
namespace: {namespace}
"""


def gen_values_yaml(name, image_repo, image_tag, service_port,
                    replicas, cpu_req, mem_req, cpu_lim, mem_lim):
    """生成 values.yaml（无 Go template 语法，可用 f-string）"""
    full_image = f"{IMAGE_PREFIX}/{image_repo}"
    return f"""# ============================================================================
# {name} Helm Chart 默认 values
# 关联：design/deploy/values/{name}-values.yaml（深度调优配置）
# 说明：本文件为 Chart 骨架级默认值，完整生产配置请引用上述 values 文件。
# ============================================================================

# ---- 镜像配置（国内镜像源 docker.m.daocloud.io）----
image:
  repository: "{full_image}"
  tag: "{image_tag}"
  pullPolicy: IfNotPresent

# ---- 副本数 ----
replicaCount: {replicas}

# ---- Service 配置 ----
service:
  type: ClusterIP
  port: {service_port}
  # 容器端口
  containerPort: {service_port}

# ---- 资源配额（默认 standard 档）----
resources:
  requests:
    cpu: "{cpu_req}"
    memory: "{mem_req}"
  limits:
    cpu: "{cpu_lim}"
    memory: "{mem_lim}"

# ---- 健康检查 ----
probes:
  liveness:
    enabled: true
    httpGet:
      path: /healthz
      port: {service_port}
    initialDelaySeconds: 30
    periodSeconds: 10
    failureThreshold: 3
  readiness:
    enabled: true
    httpGet:
      path: /readyz
      port: {service_port}
    initialDelaySeconds: 10
    periodSeconds: 5
    failureThreshold: 3

# ---- Pod 调度 ----
nodeSelector: {{}}
tolerations: []
affinity: {{}}

# ---- 水平自动扩缩容 ----
autoscaling:
  enabled: false
  minReplicas: {replicas}
  maxReplicas: {max(replicas * 3, replicas + 1)}
  targetCPUUtilizationPercentage: 80
  targetMemoryUtilizationPercentage: 80

# ---- PodDisruptionBudget ----
podDisruptionBudget:
  enabled: false
  minAvailable: 1

# ---- Ingress ----
ingress:
  enabled: false
  className: apisix
  annotations: {{}}
  hosts:
    - host: {name}.shuqing.local
      paths:
        - path: /
          pathType: Prefix
  tls: []

# ---- 环境变量 ----
env:
  LOG_LEVEL: info
  JAVA_OPTS: ""

# ---- ConfigMap 数据（骨架，按需扩展）----
config:
  application.conf: |
    # {name} 默认配置，完整配置请引用 design/deploy/values/{name}-values.yaml

# ---- 引用提示 ----
# 完整配置请通过 -f design/deploy/values/{name}-values.yaml 覆盖
# 渲染命令： helm template {name} design/deploy/charts/{name} -f design/deploy/values/{name}-values.yaml
"""


# ===== 以下函数包含 Go template {{ }} 语法，使用普通字符串 + replace =====

def gen_helpers_tpl(name):
    """生成 _helpers.tpl（含 Go template 语法）"""
    template = r"""{{/*
Expand the name of the chart.
*/}}
{{- define "__NAME__.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "__NAME__.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Chart labels.
*/}}
{{- define "__NAME__.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "__NAME__.selectorLabels" . }}
{{- if .Chart.AppVersion -}}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "__NAME__.selectorLabels" -}}
app.kubernetes.io/name: {{ include "__NAME__.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "__NAME__.configmapName" -}}
{{- printf "%s-config" (include "__NAME__.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "__NAME__.serviceName" -}}
{{- include "__NAME__.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "__NAME__.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "__NAME__.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
"""
    return template.replace("__NAME__", name)


def gen_deployment_yaml(name):
    """生成 deployment.yaml（含 Go template 语法）"""
    template = r"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "__NAME__.fullname" . }}
  labels:
    {{- include "__NAME__.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount | int }}
  selector:
    matchLabels:
      {{- include "__NAME__.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "__NAME__.selectorLabels" . | nindent 8 }}
      annotations:
        # ConfigMap 变更时滚动重启
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    spec:
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.service.containerPort | int }}
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "__NAME__.configmapName" . }}
          {{- if .Values.probes.liveness.enabled }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.httpGet.path | quote }}
              port: {{ .Values.probes.liveness.httpGet.port | int }}
            initialDelaySeconds: {{ .Values.probes.liveness.initialDelaySeconds | int }}
            periodSeconds: {{ .Values.probes.liveness.periodSeconds | int }}
            failureThreshold: {{ .Values.probes.liveness.failureThreshold | int }}
          {{- end }}
          {{- if .Values.probes.readiness.enabled }}
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.httpGet.path | quote }}
              port: {{ .Values.probes.readiness.httpGet.port | int }}
            initialDelaySeconds: {{ .Values.probes.readiness.initialDelaySeconds | int }}
            periodSeconds: {{ .Values.probes.readiness.periodSeconds | int }}
            failureThreshold: {{ .Values.probes.readiness.failureThreshold | int }}
          {{- end }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
"""
    return template.replace("__NAME__", name)


def gen_service_yaml(name):
    """生成 service.yaml（含 Go template 语法）"""
    template = r"""apiVersion: v1
kind: Service
metadata:
  name: {{ include "__NAME__.serviceName" . }}
  labels:
    {{- include "__NAME__.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port | int }}
      targetPort: http
      protocol: TCP
      name: http
  selector:
    {{- include "__NAME__.selectorLabels" . | nindent 4 }}
"""
    return template.replace("__NAME__", name)


def gen_configmap_yaml(name):
    """生成 configmap.yaml（含 Go template 语法）"""
    template = r"""apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "__NAME__.configmapName" . }}
  labels:
    {{- include "__NAME__.labels" . | nindent 4 }}
data:
  {{- range $key, $val := .Values.config }}
  {{ $key }}: |
    {{- $val | nindent 4 }}
  {{- end }}
"""
    return template.replace("__NAME__", name)


def gen_ingress_yaml(name):
    """生成 ingress.yaml（含 Go template 语法）"""
    template = r"""{{- if .Values.ingress.enabled -}}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "__NAME__.fullname" . }}
  labels:
    {{- include "__NAME__.labels" . | nindent 4 }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  ingressClassName: {{ .Values.ingress.className }}
  {{- with .Values.ingress.tls }}
  tls:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  rules:
    {{- range .Values.ingress.hosts }}
    - host: {{ .host | quote }}
      http:
        httpPaths:
          {{- range .paths }}
          - path: {{ .path | quote }}
            pathType: {{ .pathType }}
            backend:
              service:
                name: {{ include "__NAME__.serviceName" $ }}
                port:
                  number: {{ $.Values.service.port | int }}
          {{- end }}
    {{- end }}
{{- end -}}
"""
    return template.replace("__NAME__", name)


def gen_hpa_yaml(name):
    """生成 hpa.yaml（含 Go template 语法）"""
    template = r"""{{- if .Values.autoscaling.enabled -}}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "__NAME__.fullname" . }}
  labels:
    {{- include "__NAME__.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "__NAME__.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas | int }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas | int }}
  metrics:
    {{- if .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage | int }}
    {{- end }}
    {{- if .Values.autoscaling.targetMemoryUtilizationPercentage }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetMemoryUtilizationPercentage | int }}
    {{- end }}
{{- end -}}
"""
    return template.replace("__NAME__", name)


def gen_pdb_yaml(name):
    """生成 pdb.yaml（含 Go template 语法）"""
    template = r"""{{- if .Values.podDisruptionBudget.enabled -}}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "__NAME__.fullname" . }}
  labels:
    {{- include "__NAME__.labels" . | nindent 4 }}
spec:
  {{- if .Values.podDisruptionBudget.minAvailable }}
  minAvailable: {{ .Values.podDisruptionBudget.minAvailable | int }}
  {{- end }}
  {{- if .Values.podDisruptionBudget.maxUnavailable }}
  maxUnavailable: {{ .Values.podDisruptionBudget.maxUnavailable | int }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "__NAME__.selectorLabels" . | nindent 6 }}
{{- end -}}
"""
    return template.replace("__NAME__", name)


def gen_notes_txt(name, service_port):
    """生成 NOTES.txt（含 Go template 语法）"""
    template = r"""###############################################################################
# __NAME__ Chart 安装完成
###############################################################################

{{ .Chart.Name }}-{{ .Release.Name }} 已部署到集群。

1. 获取 Pod 状态:
   kubectl get pods -l "app.kubernetes.io/instance={{ .Release.Name }}" -n {{ .Release.Namespace }}

2. 获取 Service 信息:
   kubectl get svc {{ include "__NAME__.serviceName" . }} -n {{ .Release.Namespace }}

3. 端口转发访问（本地调试）:
   kubectl port-forward svc/{{ include "__NAME__.serviceName" . }} __PORT__:__PORT__ -n {{ .Release.Namespace }}

4. 查看日志:
   kubectl logs -l "app.kubernetes.io/instance={{ .Release.Name }}" -n {{ .Release.Namespace }}

###############################################################################
# 完整生产配置请引用:
#   helm upgrade --install {{ .Release.Name }} design/deploy/charts/__NAME__ \
#     -f design/deploy/values/__NAME__-values.yaml -n {{ .Release.Namespace }}
###############################################################################
"""
    return template.replace("__NAME__", name).replace("__PORT__", str(service_port))


def write_file(path, content):
    """写文件（自动创建父目录）"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)


def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    created = []

    for chart in CHARTS:
        (name, desc, app_version, image_repo, image_tag, service_port,
         category, namespace, keywords, replicas,
         cpu_req, mem_req, cpu_lim, mem_lim) = chart

        chart_dir = os.path.join(base_dir, name)
        templates_dir = os.path.join(chart_dir, "templates")

        # Chart.yaml
        write_file(
            os.path.join(chart_dir, "Chart.yaml"),
            gen_chart_yaml(name, desc, app_version, category, namespace, keywords),
        )
        # values.yaml
        write_file(
            os.path.join(chart_dir, "values.yaml"),
            gen_values_yaml(name, image_repo, image_tag, service_port,
                            replicas, cpu_req, mem_req, cpu_lim, mem_lim),
        )
        # templates/*
        write_file(os.path.join(templates_dir, "_helpers.tpl"), gen_helpers_tpl(name))
        write_file(os.path.join(templates_dir, "deployment.yaml"), gen_deployment_yaml(name))
        write_file(os.path.join(templates_dir, "service.yaml"), gen_service_yaml(name))
        write_file(os.path.join(templates_dir, "configmap.yaml"), gen_configmap_yaml(name))
        write_file(os.path.join(templates_dir, "ingress.yaml"), gen_ingress_yaml(name))
        write_file(os.path.join(templates_dir, "hpa.yaml"), gen_hpa_yaml(name))
        write_file(os.path.join(templates_dir, "pdb.yaml"), gen_pdb_yaml(name))
        write_file(os.path.join(templates_dir, "NOTES.txt"), gen_notes_txt(name, service_port))

        created.append(name)

    print(f"✅ 共创建 {len(created)} 个 Chart:")
    for n in created:
        print(f"   - {n}")


if __name__ == "__main__":
    main()
