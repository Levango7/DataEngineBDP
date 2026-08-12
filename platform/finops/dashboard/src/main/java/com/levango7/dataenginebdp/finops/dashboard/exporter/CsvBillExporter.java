package com.levango7.dataenginebdp.finops.dashboard.exporter;

import com.opencsv.CSVWriter;
import com.levango7.dataenginebdp.finops.dashboard.model.BillSummary;
import com.levango7.dataenginebdp.finops.dashboard.model.ResourceCostDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * CSV 账单导出器。
 *
 * <p>支持两种导出模式：</p>
 * <ul>
 *   <li>明细导出：按资源粒度，每行一个资源</li>
 *   <li>汇总导出：按 tenant/namespace/workspace 聚合</li>
 * </ul>
 */
@Service
public class CsvBillExporter {

    private static final Logger log = LoggerFactory.getLogger(CsvBillExporter.class);

    /**
     * 导出明细账单 CSV。
     */
    public InputStream exportDetails(List<ResourceCostDetail> details) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        CSVWriter writer = new CSVWriter(osw);

        // BOM 头确保 Excel 正确识别 UTF-8
        baos.write(0xEF);
        baos.write(0xBB);
        baos.write(0xBF);

        // 表头
        writer.writeNext(new String[]{
                "资源ID", "资源类型", "租户", "namespace", "工作空间",
                "CPU成本", "内存成本", "存储成本", "GPU成本", "网络成本",
                "总成本", "GPU型号", "窗口起始", "窗口结束"
        });

        // 数据行
        for (ResourceCostDetail d : details) {
            Map<String, BigDecimal> costs = d.getDimensionCosts() != null ? d.getDimensionCosts() : Map.of();
            writer.writeNext(new String[]{
                    d.getResourceId(),
                    d.getResourceType(),
                    d.getTenant(),
                    d.getNamespace(),
                    d.getWorkspace() != null ? d.getWorkspace() : "",
                    costs.getOrDefault("CPU", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("MEMORY", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("STORAGE", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("GPU", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("NETWORK", BigDecimal.ZERO).toPlainString(),
                    d.getTotalCost().toPlainString(),
                    d.getGpuModel() != null ? d.getGpuModel() : "",
                    d.getStart() != null ? d.getStart().toString() : "",
                    d.getEnd() != null ? d.getEnd().toString() : ""
            });
        }
        writer.close();
        log.info("CSV 明细账单导出完成: {} 行", details.size());
        return new ByteArrayInputStream(baos.toByteArray());
    }

    /**
     * 导出汇总账单 CSV。
     */
    public InputStream exportSummary(List<BillSummary> summaries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        CSVWriter writer = new CSVWriter(osw);

        // BOM 头
        baos.write(0xEF);
        baos.write(0xBB);
        baos.write(0xBF);

        // 表头
        writer.writeNext(new String[]{
                "聚合维度", "聚合键", "总成本",
                "CPU成本", "内存成本", "存储成本", "GPU成本", "网络成本",
                "资源数量", "明细条数"
        });

        for (BillSummary s : summaries) {
            Map<String, BigDecimal> costs = s.getDimensionCosts() != null ? s.getDimensionCosts() : Map.of();
            writer.writeNext(new String[]{
                    s.getGroupBy(),
                    s.getGroupKey(),
                    s.getTotalCost().toPlainString(),
                    costs.getOrDefault("CPU", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("MEMORY", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("STORAGE", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("GPU", BigDecimal.ZERO).toPlainString(),
                    costs.getOrDefault("NETWORK", BigDecimal.ZERO).toPlainString(),
                    String.valueOf(s.getResourceCount()),
                    String.valueOf(s.getDetailCount())
            });
        }
        writer.close();
        log.info("CSV 汇总账单导出完成: {} 行", summaries.size());
        return new ByteArrayInputStream(baos.toByteArray());
    }
}