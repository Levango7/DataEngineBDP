{{/*
Expand the name of the chart.
*/}}
{{- define "nacos.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name（截断 63 字符，末尾去 -）。
*/}}
{{- define "nacos.fullname" -}}
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
Common labels
*/}}
{{- define "nacos.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "nacos.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels
*/}}
{{- define "nacos.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nacos.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
Fail-fast 校验：auth.enabled 且 tokenSecretKey 为空时渲染失败。
生产环境必须显式注入 ≥32 字符 Base64 tokenSecretKey。
*/}}
{{- define "nacos.tokenSecretKey" -}}
{{- if and .Values.auth.enabled (not .Values.auth.tokenSecretKey) -}}
{{- fail "auth.enabled=true 但 auth.tokenSecretKey 为空：生产必须注入 ≥32 字符 Base64 tokenSecretKey" -}}
{{- end -}}
{{- .Values.auth.tokenSecretKey -}}
{{- end -}}