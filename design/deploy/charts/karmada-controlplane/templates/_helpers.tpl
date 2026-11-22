{{/*
karmada-controlplane 共用模板片段。

本父 chart 只做"包装层"：真正的资源由 vendored 的上游 chart（charts/karmada/）渲染，
因此这里的 define 仅服务于父 chart 自身的元信息与标签口径。
*/}}

{{- define "karmada-controlplane.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "karmada-controlplane.fullname" -}}
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

{{- define "karmada-controlplane.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "karmada-controlplane.selectorLabels" -}}
app.kubernetes.io/name: {{ include "karmada-controlplane.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "karmada-controlplane.labels" -}}
helm.sh/chart: {{ include "karmada-controlplane.chart" . }}
{{ include "karmada-controlplane.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
