package com.levango7.dataenginebdp.finops.dashboard.exporter;

import com.levango7.dataenginebdp.finops.dashboard.model.BillSummary;
import com.levango7.dataenginebdp.finops.dashboard.model.ResourceCostDetail;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Excel 账单导出器。
 *
 * <p>使用 Apache POI 生成 .xlsx 文件，支持明细与汇总两个 Sheet：</p>
 * <ul>
 *   <li>明细 Sheet：按资源粒度，每行一个资源</li>
 *   <li>汇总 Sheet：按 tenant/namespace/workspace 聚合</li>
 * </ul>
 */
@Service
public class ExcelBillExporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelBillExporter.class);

    /**
     * 导出明细账单 Excel（单 Sheet）。
     */
    public InputStream exportDetails(List<ResourceCostDetail> details) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("成本明细");

        // 表头样式
        CellStyle headerStyle = createHeaderStyle(workbook);

        // 表头
        String[] headers = {
                "资源ID", "资源类型", "租户", "namespace", "工作空间",
                "CPU成本", "内存成本", "存储成本", "GPU成本", "网络成本",
                "总成本", "GPU型号", "窗口起始", "窗口结束"
        };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // 数据行
        for (int i = 0; i < details.size(); i++) {
            ResourceCostDetail d = details.get(i);
            Row row = sheet.createRow(i + 1);
            Map<String, BigDecimal> costs = d.getDimensionCosts() != null ? d.getDimensionCosts() : Map.of();
            int col = 0;
            row.createCell(col++).setCellValue(d.getResourceId());
            row.createCell(col++).setCellValue(d.getResourceType());
            row.createCell(col++).setCellValue(d.getTenant());
            row.createCell(col++).setCellValue(d.getNamespace());
            row.createCell(col++).setCellValue(d.getWorkspace() != null ? d.getWorkspace() : "");
            row.createCell(col++).setCellValue(costs.getOrDefault("CPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("MEMORY", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("STORAGE", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("GPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("NETWORK", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(d.getTotalCost().doubleValue());
            row.createCell(col++).setCellValue(d.getGpuModel() != null ? d.getGpuModel() : "");
            row.createCell(col++).setCellValue(d.getStart() != null ? d.getStart().toString() : "");
            row.createCell(col).setCellValue(d.getEnd() != null ? d.getEnd().toString() : "");
        }
        autoSizeColumns(sheet, headers.length);

        return toInputStream(workbook);
    }

    /**
     * 导出汇总账单 Excel（单 Sheet）。
     */
    public InputStream exportSummary(List<BillSummary> summaries) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("成本汇总");

        CellStyle headerStyle = createHeaderStyle(workbook);
        String[] headers = {
                "聚合维度", "聚合键", "总成本",
                "CPU成本", "内存成本", "存储成本", "GPU成本", "网络成本",
                "资源数量", "明细条数"
        };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        for (int i = 0; i < summaries.size(); i++) {
            BillSummary s = summaries.get(i);
            Row row = sheet.createRow(i + 1);
            Map<String, BigDecimal> costs = s.getDimensionCosts() != null ? s.getDimensionCosts() : Map.of();
            int col = 0;
            row.createCell(col++).setCellValue(s.getGroupBy());
            row.createCell(col++).setCellValue(s.getGroupKey());
            row.createCell(col++).setCellValue(s.getTotalCost().doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("CPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("MEMORY", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("STORAGE", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("GPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("NETWORK", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(s.getResourceCount());
            row.createCell(col).setCellValue(s.getDetailCount());
        }
        autoSizeColumns(sheet, headers.length);

        return toInputStream(workbook);
    }

    /**
     * 导出完整账单 Excel（明细 + 汇总两个 Sheet）。
     */
    public InputStream exportFull(List<ResourceCostDetail> details,
                                  List<BillSummary> summaries) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        CellStyle headerStyle = createHeaderStyle(workbook);

        // Sheet 1: 明细
        Sheet detailSheet = workbook.createSheet("成本明细");
        String[] detailHeaders = {
                "资源ID", "资源类型", "租户", "namespace", "工作空间",
                "CPU成本", "内存成本", "存储成本", "GPU成本", "网络成本",
                "总成本", "GPU型号", "窗口起始", "窗口结束"
        };
        Row detailHeaderRow = detailSheet.createRow(0);
        for (int i = 0; i < detailHeaders.length; i++) {
            Cell cell = detailHeaderRow.createCell(i);
            cell.setCellValue(detailHeaders[i]);
            cell.setCellStyle(headerStyle);
        }
        for (int i = 0; i < details.size(); i++) {
            ResourceCostDetail d = details.get(i);
            Row row = detailSheet.createRow(i + 1);
            Map<String, BigDecimal> costs = d.getDimensionCosts() != null ? d.getDimensionCosts() : Map.of();
            int col = 0;
            row.createCell(col++).setCellValue(d.getResourceId());
            row.createCell(col++).setCellValue(d.getResourceType());
            row.createCell(col++).setCellValue(d.getTenant());
            row.createCell(col++).setCellValue(d.getNamespace());
            row.createCell(col++).setCellValue(d.getWorkspace() != null ? d.getWorkspace() : "");
            row.createCell(col++).setCellValue(costs.getOrDefault("CPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("MEMORY", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("STORAGE", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("GPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("NETWORK", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(d.getTotalCost().doubleValue());
            row.createCell(col++).setCellValue(d.getGpuModel() != null ? d.getGpuModel() : "");
            row.createCell(col++).setCellValue(d.getStart() != null ? d.getStart().toString() : "");
            row.createCell(col).setCellValue(d.getEnd() != null ? d.getEnd().toString() : "");
        }
        autoSizeColumns(detailSheet, detailHeaders.length);

        // Sheet 2: 汇总
        Sheet summarySheet = workbook.createSheet("成本汇总");
        String[] summaryHeaders = {
                "聚合维度", "聚合键", "总成本",
                "CPU成本", "内存成本", "存储成本", "GPU成本", "网络成本",
                "资源数量", "明细条数"
        };
        Row summaryHeaderRow = summarySheet.createRow(0);
        for (int i = 0; i < summaryHeaders.length; i++) {
            Cell cell = summaryHeaderRow.createCell(i);
            cell.setCellValue(summaryHeaders[i]);
            cell.setCellStyle(headerStyle);
        }
        for (int i = 0; i < summaries.size(); i++) {
            BillSummary s = summaries.get(i);
            Row row = summarySheet.createRow(i + 1);
            Map<String, BigDecimal> costs = s.getDimensionCosts() != null ? s.getDimensionCosts() : Map.of();
            int col = 0;
            row.createCell(col++).setCellValue(s.getGroupBy());
            row.createCell(col++).setCellValue(s.getGroupKey());
            row.createCell(col++).setCellValue(s.getTotalCost().doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("CPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("MEMORY", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("STORAGE", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("GPU", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(costs.getOrDefault("NETWORK", BigDecimal.ZERO).doubleValue());
            row.createCell(col++).setCellValue(s.getResourceCount());
            row.createCell(col).setCellValue(s.getDetailCount());
        }
        autoSizeColumns(summarySheet, summaryHeaders.length);

        log.info("Excel 完整账单导出完成: 明细 {} 行, 汇总 {} 行", details.size(), summaries.size());
        return toInputStream(workbook);
    }

    /**
     * 创建表头样式。
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        return style;
    }

    /**
     * 自动列宽。
     */
    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * Workbook → InputStream。
     */
    private InputStream toInputStream(Workbook workbook) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        workbook.write(baos);
        workbook.close();
        return new ByteArrayInputStream(baos.toByteArray());
    }
}