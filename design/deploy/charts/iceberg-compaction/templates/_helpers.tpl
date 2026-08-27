{{/*
Common labels
*/}}
{{- define "iceberg-compaction.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Full name
*/}}
{{- define "iceberg-compaction.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "iceberg-compaction.selectorLabels" -}}
app.kubernetes.io/name: {{ include "iceberg-compaction.fullname" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}