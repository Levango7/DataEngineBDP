{{/*
============================================================================
finance-template Chart 辅助函数（T019）
============================================================================
模块：T019 金融模板 Helm Chart
用途：提供 Chart 内复用的名称、标签等辅助函数
*/}}

{{/*
Expand the name of the chart.
*/}}
{{- define "finance-template.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "finance-template.fullname" -}}
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
{{- define "finance-template.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "finance-template.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "finance-template.selectorLabels" -}}
app.kubernetes.io/name: {{ include "finance-template.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
Template metadata labels (shuqing.io/ custom labels).
*/}}
{{- define "finance-template.templateLabels" -}}
shuqing.io/template: {{ .Values.template.name | quote }}
shuqing.io/template-version: {{ .Values.template.version | quote }}
shuqing.io/category: industry-template
shuqing.io/industry: finance
{{- end -}}

{{/*
Template metadata annotations.
*/}}
{{- define "finance-template.templateAnnotations" -}}
shuqing.io/business-domains: "risk,customer,account,transaction,credit"
shuqing.io/tables: "21"
shuqing.io/dags: "5"
shuqing.io/dashboards: "3"
shuqing.io/roles: "3"
shuqing.io/source-task: "T018"
shuqing.io/chart-task: "T019"
{{- end -}}

{{/*
ConfigMap name for template assets.
*/}}
{{- define "finance-template.configmapName" -}}
{{- printf "%s-assets" .Values.configMap.namePrefix -}}
{{- end -}}

{{/*
Import Job name.
*/}}
{{- define "finance-template.importJobName" -}}
{{- printf "%s-import" .Values.configMap.namePrefix -}}
{{- end -}}

{{/*
Verification Job name.
*/}}
{{- define "finance-template.verifyJobName" -}}
{{- printf "%s-verify" .Values.configMap.namePrefix -}}
{{- end -}}

{{/*
ServiceAccount name.
*/}}
{{- define "finance-template.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "finance-template.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Target namespace (prefer .Release.Namespace, fallback to .Values.namespace).
*/}}
{{- define "finance-template.namespace" -}}
{{- if .Release.Namespace -}}
{{- .Release.Namespace -}}
{{- else -}}
{{- .Values.namespace -}}
{{- end -}}
{{- end -}}

{{/*
Image helper: render repository:tag.
*/}}
{{- define "finance-template.image" -}}
{{- printf "%s:%s" .repository .tag -}}
{{- end -}}

{{/*
资产 ConfigMap 的卷投射（items）：键名以 "_" 替代 "/"（K8s ConfigMap 键字符集
仅允许 [-._a-zA-Z0-9]，含 "/" 会被 API Server 拒绝——KNOWN-FAILURES #36 同类缺陷），
path 字段还原原始目录结构，使容器内仍为 /templates/ddl/*.sql 等既有布局。
configmap-assets.yaml 的键名生成必须与本 helper 保持同一替换规则
（由 scripts/check-chart-asset-keys.py 静态守护）。
*/}}
{{- define "finance-template.assetsItems" -}}
{{- if .Values.configMap.assets.ddl.enabled -}}
{{- range $path, $_ := .Files.Glob "assets/ddl/**" }}
- key: {{ $path | replace "assets/" "" | replace "/" "_" }}
  path: {{ $path | replace "assets/" "" }}
{{- end -}}
{{- end -}}
{{- if .Values.configMap.assets.dag.enabled -}}
{{- range $path, $_ := .Files.Glob "assets/dag/**" }}
- key: {{ $path | replace "assets/" "" | replace "/" "_" }}
  path: {{ $path | replace "assets/" "" }}
{{- end -}}
{{- end -}}
{{- if .Values.configMap.assets.dashboard.enabled -}}
{{- range $path, $_ := .Files.Glob "assets/dashboard/**" }}
- key: {{ $path | replace "assets/" "" | replace "/" "_" }}
  path: {{ $path | replace "assets/" "" }}
{{- end -}}
{{- end -}}
{{- if .Values.configMap.assets.rbac.enabled -}}
{{- range $path, $_ := .Files.Glob "assets/rbac/**" }}
- key: {{ $path | replace "assets/" "" | replace "/" "_" }}
  path: {{ $path | replace "assets/" "" }}
{{- end -}}
{{- end -}}
{{- if .Values.configMap.assets.docs.enabled -}}
{{- range $path, $_ := .Files.Glob "assets/docs/**" }}
- key: {{ $path | replace "assets/" "" | replace "/" "_" }}
  path: {{ $path | replace "assets/" "" }}
{{- end -}}
{{- end -}}
{{- if .Values.configMap.includeMetadata }}
- key: template-metadata.yaml
  path: template-metadata.yaml
{{- end }}
{{- end -}}