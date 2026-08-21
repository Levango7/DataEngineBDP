package com.shuqing.bigdata.encaps.quota;

/**
 * K8s Quantity 容量比较器。
 *
 * <p>K8s 资源量格式（{@link io.fabric8.kubernetes.api.model.Quantity}）：</p>
 * <ul>
 *   <li>CPU：纯数字（核数，如 {@code 4}）或毫核（如 {@code 500m} = 0.5 核）</li>
 *   <li>内存/存储：数字 + 后缀（{@code Ki/Mi/Gi/Ti/Pi/Ei} 二进制 或 {@code k/M/G/T/P/E} 十进制）</li>
 * </ul>
 *
 * <p>本比较器将所有量归一化为最小单位（CPU→毫核，内存→字节）后按 long 比较，
 * 避免浮点精度问题。</p>
 */
final class QuantityComparator {

    private QuantityComparator() {
    }

    /**
     * 判断 {@code used + requested > hard}。
     *
     * @param used      已用量（K8s Quantity 字符串）
     * @param requested 新请求量
     * @param hard      硬上限
     * @return true 表示超限
     */
    static boolean exceeds(String used, String requested, String hard) {
        try {
            // 判断单位类型：CPU（无后缀或 m）vs 内存/存储（Ki/Mi/Gi 等）
            if (isCpuQuantity(used) && isCpuQuantity(requested) && isCpuQuantity(hard)) {
                long usedMillis = toCpuMillis(used);
                long reqMillis = toCpuMillis(requested);
                long hardMillis = toCpuMillis(hard);
                return usedMillis + reqMillis > hardMillis;
            }
            // 内存/存储按字节比较
            long usedBytes = toBytes(used);
            long reqBytes = toBytes(requested);
            long hardBytes = toBytes(hard);
            return usedBytes + reqBytes > hardBytes;
        } catch (Exception e) {
            // 解析失败时不判定超限，避免误拒
            return false;
        }
    }

    private static boolean isCpuQuantity(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        String trimmed = s.trim();
        // 纯数字或以 m 结尾且无其他字母
        if (trimmed.endsWith("m")) {
            String num = trimmed.substring(0, trimmed.length() - 1);
            return isNumeric(num);
        }
        return isNumeric(trimmed);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** CPU → 毫核（1 核 = 1000m） */
    private static long toCpuMillis(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        String trimmed = s.trim();
        if (trimmed.endsWith("m")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim());
        }
        double cores = Double.parseDouble(trimmed);
        return (long) (cores * 1000);
    }

    /** 内存/存储 → 字节 */
    private static long toBytes(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        String trimmed = s.trim();
        // 二进制后缀
        if (trimmed.endsWith("Ki")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L;
        }
        if (trimmed.endsWith("Mi")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L * 1024L;
        }
        if (trimmed.endsWith("Gi")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L * 1024L * 1024L;
        }
        if (trimmed.endsWith("Ti")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L * 1024L * 1024L * 1024L;
        }
        if (trimmed.endsWith("Pi")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L * 1024L * 1024L * 1024L * 1024L;
        }
        if (trimmed.endsWith("Ei")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()) * 1024L * 1024L * 1024L * 1024L * 1024L * 1024L;
        }
        // 十进制后缀
        if (trimmed.endsWith("k")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 1000L;
        }
        if (trimmed.endsWith("M")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 1000L * 1000L;
        }
        if (trimmed.endsWith("G")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 1000L * 1000L * 1000L;
        }
        if (trimmed.endsWith("T")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 1000L * 1000L * 1000L * 1000L;
        }
        if (trimmed.endsWith("P")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 1000L * 1000L * 1000L * 1000L * 1000L;
        }
        if (trimmed.endsWith("E")) {
            return Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()) * 1000L * 1000L * 1000L * 1000L * 1000L * 1000L;
        }
        // 纯数字（字节）
        return Long.parseLong(trimmed);
    }
}