{{/*
Expand the name of the chart.
*/}}
{{- define "infra-provider-cloud.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "infra-provider-cloud.fullname" -}}
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
{{- define "infra-provider-cloud.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "infra-provider-cloud.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "infra-provider-cloud.selectorLabels" -}}
app.kubernetes.io/name: {{ include "infra-provider-cloud.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "infra-provider-cloud.configmapName" -}}
{{- printf "%s-config" (include "infra-provider-cloud.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "infra-provider-cloud.serviceName" -}}
{{- include "infra-provider-cloud.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "infra-provider-cloud.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "infra-provider-cloud.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
