package com.yuceyang.uitest.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 导出列：功能点跟在二级标题后面，所有格式的列数都和表头一致；Excel 带一张报告 sheet。 */
class CaseExporterTest {

    private CaseExporter.ExportCase sample(String point) {
        return sample(point, "未执行");
    }

    private CaseExporter.ExportCase sample(String point, String status) {
        return new CaseExporter.ExportCase("GN-00001", "项目", "系统", "模块", "一级", "二级", point,
                "用例标题", "设计需求", "测试追踪", "终止条件", "前提", "步骤", "期望", "", "", "", "", "", "", "",
                "GET", "http://example.com", "", status, "", null);
    }

    @Test
    void pointColumnSitsRightAfterSecondLevelTitle() {
        assertEquals("功能点", CaseExporter.HEADERS.get(6));
        assertEquals("二级标题", CaseExporter.HEADERS.get(5));
        assertEquals("用例标题", CaseExporter.HEADERS.get(7));
        assertEquals(27, CaseExporter.HEADERS.size());
    }

    @Test
    void everyRowMatchesHeaderWidth() {
        CaseExporter.ExportCase row = sample("创建集群");
        assertEquals(CaseExporter.HEADERS.size(), row.cells().size());
        assertEquals("创建集群", row.cells().get(6));
    }

    @Test
    void missingPointIsWrittenAsSlashByCallerAndBlankBecomesDash() {
        // 调用方没有那一层时写「/」；万一传空，导出仍然占一列，显示 "-"（不串列）。
        assertEquals("/", sample("/").cells().get(6));
        assertEquals("-", sample("").cells().get(6));
    }

    @Test
    void csvAndMarkdownKeepColumnCount() {
        CaseExporter exporter = new CaseExporter();
        List<CaseExporter.ExportCase> rows = List.of(sample("创建集群"));
        String csv = new String(exporter.render(CaseExporter.FORMAT_CSV, rows), StandardCharsets.UTF_8);
        String headerLine = csv.lines().findFirst().orElse("");
        assertEquals(CaseExporter.HEADERS.size(), headerLine.split(",", -1).length);
        assertTrue(headerLine.contains("功能点"));

        String markdown = new String(exporter.render(CaseExporter.FORMAT_MD, rows), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("| 二级标题 | 功能点 | 用例标题 |"));
    }

    @Test
    void reportSheetHasTenColumnsAndDefaultsResultToPassed() {
        assertEquals(List.of("用例编号", "系统名字", "模块分类", "一级标题", "二级标题", "功能点",
                "用例标题", "输入及操作说明", "期望测试结果", "实际测试结果"), CaseExporter.REPORT_HEADERS);
        CaseExporter.ExportCase row = sample("创建集群");
        List<String> cells = row.reportCells();
        assertEquals(10, cells.size());
        assertEquals("步骤", cells.get(7));
        assertEquals("期望", cells.get(8));
        assertEquals("通过", cells.get(9));
    }

    @Test
    void failedCaseBecomesNotPassedInReport() {
        assertEquals("未通过", sample("创建集群", "失败").reportCells().get(9));
        assertEquals("未通过", sample("创建集群", "未通过").reportCells().get(9));
        assertEquals("通过", sample("创建集群", "通过").reportCells().get(9));
        assertEquals("通过", sample("创建集群", "待执行").reportCells().get(9));
    }

    @Test
    void excelContainsBothSheets() throws Exception {
        byte[] bytes = new CaseExporter().render(CaseExporter.FORMAT_XLSX,
                List.of(sample("创建集群"), sample("创建集群", "失败")));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("功能测试用例", workbook.getSheetName(0));
            assertEquals("报告-功能测试用例", workbook.getSheetName(1));
            XSSFSheet report = workbook.getSheetAt(1);
            assertEquals("用例编号", report.getRow(2).getCell(0).getStringCellValue());
            assertEquals("实际测试结果", report.getRow(2).getCell(9).getStringCellValue());
            assertEquals("通过", report.getRow(3).getCell(9).getStringCellValue());
            assertEquals("未通过", report.getRow(4).getCell(9).getStringCellValue());
        }
    }
}
