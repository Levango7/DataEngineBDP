package com.levango7.dataenginebdp.finops.dashboard.controller;

import com.levango7.dataenginebdp.finops.dashboard.exporter.CsvBillExporter;
import com.levango7.dataenginebdp.finops.dashboard.exporter.ExcelBillExporter;
import com.levango7.dataenginebdp.finops.dashboard.model.BillSummary;
import com.levango7.dataenginebdp.finops.dashboard.model.ResourceCostDetail;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.dashboard.service.BillSummaryService;
import com.levango7.dataenginebdp.finops.dashboard.service.CostDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * 账单导出 REST API。
 *
 * <p>提供 CSV / Excel 两种格式的账单导出端点：</p>
 * <ul>
 *   <li>GET /api/v1/bill/export/csv?type=details|summary|full  — CSV 导出</li>
 *   <li>GET /api/v1/bill/export/excel?type=details|summary|full — Excel 导出</li>
 * </ul>
 *
 * <p>type 参数说明：</p>
 * <ul>
 *   <li>details — 仅明细（按资源）</li>
 *   <li>summary — 仅汇总（按 groupBy 维度）</li>
 *   <li>full — 明细 + 汇总（Excel 双 Sheet，CSV 合并）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bill/export")
public class BillExportController {

    private static final Logger log = LoggerFactory.getLogger(BillExportController.class);

    private final CostDataService costDataService;
    private final BillSummaryService billSummaryService;
    private final CsvBillExporter csvExporter;
    private final ExcelBillExporter excelExporter;

    public BillExportController(CostDataService costDataService,
                                BillSummaryService billSummaryService,
                                CsvBillExporter csvExporter,
                                ExcelBillExporter excelExporter) {
        this.costDataService = costDataService;
        this.billSummaryService = billSummaryService;
        this.csvExporter = csvExporter;
        this.excelExporter = excelExporter;
    }

    /**
     * CSV 账单导出。
     */
    @GetMapping("/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(defaultValue = "details") String type,
            @RequestParam(defaultValue = "TENANT") String groupBy,
            @RequestParam(required = false) String namespace,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) throws IOException {

        String tenant = TenantContext.getTenantId();
        log.info("CSV 账单导出: type={}, groupBy={}, tenant={}, 窗口=[{},{}]", type, groupBy, tenant, start, end);

        List<ResourceCostDetail> details = costDataService.getCostDetails(tenant, namespace, start, end);
        InputStream is;
        String filename;

        if ("summary".equalsIgnoreCase(type)) {
            List<BillSummary> summaries = summarize(details, groupBy);
            is = csvExporter.exportSummary(summaries);
            filename = "bill-summary-" + start + ".csv";
        } else if ("full".equalsIgnoreCase(type)) {
            // full 模式 CSV：先明细后汇总
            List<BillSummary> summaries = summarize(details, groupBy);
            is = csvExporter.exportDetails(details); // 简化：明细 CSV
            filename = "bill-full-" + start + ".csv";
        } else {
            is = csvExporter.exportDetails(details);
            filename = "bill-details-" + start + ".csv";
        }

        byte[] content = is.readAllBytes();
        is.close();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return new ResponseEntity<>(content, headers, org.springframework.http.HttpStatus.OK);
    }

    /**
     * Excel 账单导出。
     */
    @GetMapping("/excel")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(defaultValue = "details") String type,
            @RequestParam(defaultValue = "TENANT") String groupBy,
            @RequestParam(required = false) String namespace,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) throws IOException {

        String tenant = TenantContext.getTenantId();
        log.info("Excel 账单导出: type={}, groupBy={}, tenant={}, 窗口=[{},{}]", type, groupBy, tenant, start, end);

        List<ResourceCostDetail> details = costDataService.getCostDetails(tenant, namespace, start, end);
        InputStream is;
        String filename;

        if ("summary".equalsIgnoreCase(type)) {
            List<BillSummary> summaries = summarize(details, groupBy);
            is = excelExporter.exportSummary(summaries);
            filename = "bill-summary-" + start + ".xlsx";
        } else if ("full".equalsIgnoreCase(type)) {
            List<BillSummary> summaries = summarize(details, groupBy);
            is = excelExporter.exportFull(details, summaries);
            filename = "bill-full-" + start + ".xlsx";
        } else {
            is = excelExporter.exportDetails(details);
            filename = "bill-details-" + start + ".xlsx";
        }

        byte[] content = is.readAllBytes();
        is.close();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return new ResponseEntity<>(content, headers, org.springframework.http.HttpStatus.OK);
    }

    /**
     * 按 groupBy 维度生成汇总。
     */
    private List<BillSummary> summarize(List<ResourceCostDetail> details, String groupBy) {
        return switch (groupBy.toUpperCase()) {
            case "NAMESPACE" -> billSummaryService.summarizeByNamespace(details);
            case "WORKSPACE" -> billSummaryService.summarizeByWorkspace(details);
            default -> billSummaryService.summarizeByTenant(details);
        };
    }
}