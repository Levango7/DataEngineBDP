{{/*
Expand the name of the chart.
*/}}
{{- define "failover-engine.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "failover-engine.fullname" -}}
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

{{- define "failover-engine.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "failover-engine.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{- define "failover-engine.selectorLabels" -}}
app.kubernetes.io/name: {{ include "failover-engine.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{- define "failover-engine.configmapName" -}}
{{- printf "%s-config" (include "failover-engine.fullname" .) -}}
{{- end -}}

{{- define "failover-engine.serviceName" -}}
{{- include "failover-engine.fullname" . -}}
{{- end -}}
