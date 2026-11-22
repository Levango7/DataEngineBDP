{{/*
Expand the name of the chart.
*/}}
{{- define "keycloak.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "keycloak.fullname" -}}
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
{{- define "keycloak.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "keycloak.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "keycloak.selectorLabels" -}}
app.kubernetes.io/name: {{ include "keycloak.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
{{- end -}}

{{/*
ConfigMap name.
*/}}
{{- define "keycloak.configmapName" -}}
{{- printf "%s-config" (include "keycloak.fullname" .) -}}
{{- end -}}

{{/*
Service name.
*/}}
{{- define "keycloak.serviceName" -}}
{{- include "keycloak.fullname" . -}}
{{- end -}}

{{/*
MFA realm 片段（台账 #131）：把 values.mfa 渲染成 Keycloak realm 表示法的字段集合。
用法：
  · realmImport 路径 —— mergeOverwrite 到每个待导入 realm 上（--import-realm 生效）；
  · fragment 路径 —— 单独渲染成 ConfigMap，供 scripts/import-keycloak-realm.sh 的 kcadm 路径使用。
字段口径：otpPolicy / webAuthnPolicy / requiredActions 均为 realm 表示法里的顶层属性。
*/}}
{{- define "keycloak.mfaRealm" -}}
{{- $mfa := .Values.mfa -}}
otpPolicy:
  type: {{ $mfa.otpPolicy.type | quote }}
  algorithm: {{ $mfa.otpPolicy.algorithm | quote }}
  digits: {{ $mfa.otpPolicy.digits | int }}
  period: {{ $mfa.otpPolicy.period | int }}
  lookAheadWindow: {{ $mfa.otpPolicy.lookAheadWindow | int }}
webAuthnPolicy:
  enabled: {{ $mfa.webAuthnPolicy.enabled }}
  attestationType: {{ $mfa.webAuthnPolicy.attestationType | quote }}
  userVerificationRequirement: {{ $mfa.webAuthnPolicy.userVerificationRequirement | quote }}
  rpName: {{ $mfa.webAuthnPolicy.rpName | quote }}
{{- if $mfa.webAuthnPolicy.rpId }}
  rpId: {{ $mfa.webAuthnPolicy.rpId | quote }}
{{- end }}
requiredActions:
  - id: {{ $mfa.requiredAction | quote }}
    name: {{ $mfa.requiredActionName | quote }}
    enabled: true
    defaultAction: true
{{- if $mfa.browserFlow.enabled }}
browserFlow: {{ $mfa.browserFlow.alias | quote }}
authenticationFlows:
  - alias: {{ $mfa.browserFlow.alias | quote }}
    description: {{ $mfa.browserFlow.description | quote }}
    providerId: basic-flow
    topLevel: true
    builtIn: false
    authenticationExecutions:
      - authenticator: auth-cookie
        authenticatorFlow: false
        requirement: ALTERNATIVE
        priority: 10
        userSetupAllowed: false
      - authenticator: auth-otp-form
        authenticatorFlow: false
        requirement: CONDITIONAL
        priority: 30
        userSetupAllowed: false
        conditionalAuthFlow: true
        authenticationExecutions:
          - authenticator: conditional-user-configured
            authenticatorFlow: false
            requirement: REQUIRED
            priority: 10
            userSetupAllowed: false
{{- end }}
{{- end -}}

