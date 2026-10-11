{{/*
Expand the name of the chart.
*/}}
{{- define "karmada-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "karmada-api.fullname" -}}
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

{{- define "karmada-api.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "karmada-api.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{- define "karmada-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "karmada-api.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{- define "karmada-api.configmapName" -}}
{{- printf "%s-config" (include "karmada-api.fullname" .) -}}
{{- end -}}

{{- define "karmada-api.serviceName" -}}
{{- include "karmada-api.fullname" . -}}
{{- end -}}
