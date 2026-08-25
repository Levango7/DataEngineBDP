export declare function formatTime(ts: string | number | Date, range?: string): string

export interface MetricsSummary {
  totalCost: number
  success: number
  error: number
}

export declare function summarizeMetrics(
  list: Array<Record<string, any>> | null | undefined
): MetricsSummary
