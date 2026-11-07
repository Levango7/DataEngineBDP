{{/*
Expand the name of the chart.
*/}}
{{- define "data-standard.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "data-standard.fullname" -}}
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
{{- define "data-standard.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "data-standard.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "data-standard.selectorLabels" -}}
app.kubernetes.io/name: {{ include "data-standard.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "data-standard.configmapName" -}}
{{- printf "%s-config" (include "data-standard.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "data-standard.serviceName" -}}
{{- include "data-standard.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "data-standard.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "data-standard.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
