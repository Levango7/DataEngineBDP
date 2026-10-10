{{/*
Expand the name of the chart.
*/}}
{{- define "real-time-pipeline.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "real-time-pipeline.fullname" -}}
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
{{- define "real-time-pipeline.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "real-time-pipeline.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "real-time-pipeline.selectorLabels" -}}
app.kubernetes.io/name: {{ include "real-time-pipeline.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "real-time-pipeline.configmapName" -}}
{{- printf "%s-config" (include "real-time-pipeline.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "real-time-pipeline.serviceName" -}}
{{- include "real-time-pipeline.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "real-time-pipeline.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "real-time-pipeline.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
