import client from './client'

export interface AssetPricing {
  mode: 'by_call' | 'by_data' | 'by_time' | 'subscription' | 'one_time'
  price: number
  unit: string
}

export interface Asset {
  id: string
  name: string
  type: string
  tenantId: string
  description?: string
  status: string
  qualityScore: number
  securityLevel: string
  pricing: AssetPricing
  subscriberCount: number
  createdAt: string
  updatedAt: string
}

export interface Settlement {
  id: string
  assetId: string
  tenantId: string
  period: string
  status: string
  totalAmount: number
  providerRevenue: number
  platformRevenue: number
  providerShare: number
  platformShare: number
  settledAt?: string
}

export interface Allocation {
  id: string
  settlementId: string
  assetId: string
  status: string
  providerAmount: number
  platformAmount: number
  providerAccountId?: string
  platformAccountId?: string
  allocatedAt?: string
}

export interface AuditLog {
  id: string
  action: string
  assetId?: string
  subscriptionId?: string
  settlementId?: string
  actorId: string
  result: string
  detail: Record<string, any>
  prevHash: string
  hash: string
  createdAt: string
}

export interface BillingSummary {
  assetId: string
  totalAmount: number
  totalProviderRevenue: number
  totalPlatformRevenue: number
  recordCount: number
}

export interface AssetUsage {
  assetId: string
  subscriberCount: number
  totalCalls: number
  totalDataRows: number
  totalRevenue: number
  activeSubscriptions: number
}

// 资产管理
export const registerAsset = (data: Partial<Asset>) =>
  client.post<Asset>('/assets/register', data)

export const auditAsset = (id: string, data: { result: 'approved' | 'rejected'; auditorId: string; reason?: string }) =>
  client.post<Asset>(`/assets/${id}/audit`, data)

export const publishAsset = (id: string) =>
  client.post<Asset>(`/assets/${id}/publish`)

export const listAssets = (params?: Record<string, any>) =>
  client.get<Asset[]>('/assets', { params })

export const getAsset = (id: string) =>
  client.get<Asset>(`/assets/${id}`)

export const getAssetUsage = (id: string) =>
  client.get<AssetUsage>(`/assets/${id}/usage`)

// 资产流通
export const subscribeAsset = (id: string, data: { subscriberId: string; period?: string; durationDays?: number }) =>
  client.post(`/assets/${id}/subscribe`, data)

export const downloadAsset = (id: string, data: { subscriberId: string; rows?: number }) =>
  client.post(`/assets/${id}/download`, data)

export const invokeAsset = (id: string, data: { subscriberId: string; params?: Record<string, any> }) =>
  client.post(`/assets/${id}/invoke`, data)

export const approveSubscription = (id: string, data: { action: 'approve' | 'reject'; approverId: string; reason?: string }) =>
  client.post(`/subscriptions/${id}/approve`, data)

export const chargeSubscription = (id: string, data: { usage: number; period?: string }) =>
  client.post(`/subscriptions/${id}/charge`, data)

// 结算与分账
export const getAssetBilling = (id: string) =>
  client.get<BillingSummary>(`/assets/${id}/billing`)

export const settleAsset = (id: string, data?: { period?: string; providerShare?: number; platformShare?: number }) =>
  client.post<Settlement>(`/assets/${id}/settle`, data || {})

export const listSettlements = (id: string) =>
  client.get<Settlement[]>(`/assets/${id}/settlements`)

export const allocateAsset = (id: string, data?: { providerAccountId?: string; platformAccountId?: string }) =>
  client.post<Allocation>(`/assets/${id}/allocate`, data || {})

export const listAllocations = (id: string) =>
  client.get<Allocation[]>(`/assets/${id}/allocations`)

// 审计日志
export const listAuditLogs = (params?: Record<string, any>) =>
  client.get<AuditLog[]>('/audit-logs', { params })

export const listAssetAuditLogs = (id: string) =>
  client.get<AuditLog[]>(`/assets/${id}/audit-logs`)

export const verifyIntegrity = () =>
  client.get<{ totalLogs: number; verified: boolean; brokenAt?: string; message: string }>('/audit-logs/integrity')