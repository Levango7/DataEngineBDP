{{/*
Expand the name of the chart.
*/}}
{{- define "infra-provider-private.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "infra-provider-private.fullname" -}}
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
{{- define "infra-provider-private.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "infra-provider-private.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "infra-provider-private.selectorLabels" -}}
app.kubernetes.io/name: {{ include "infra-provider-private.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "infra-provider-private.configmapName" -}}
{{- printf "%s-config" (include "infra-provider-private.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "infra-provider-private.serviceName" -}}
{{- include "infra-provider-private.fullname" . -}}
{{- end -}}

{{/*
Service account name.
*/}}
{{- define "infra-provider-private.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "infra-provider-private.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
