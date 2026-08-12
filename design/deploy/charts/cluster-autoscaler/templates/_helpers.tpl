{{/*
Expand the name of the chart.
*/}}
{{- define "cluster-autoscaler.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "cluster-autoscaler.fullname" -}}
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
{{- define "cluster-autoscaler.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "cluster-autoscaler.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "cluster-autoscaler.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cluster-autoscaler.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "cluster-autoscaler.configmapName" -}}
{{- printf "%s-config" (include "cluster-autoscaler.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "cluster-autoscaler.serviceName" -}}
{{- include "cluster-autoscaler.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "cluster-autoscaler.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "cluster-autoscaler.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
