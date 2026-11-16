package com.yuceyang.uitest.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 用例导出：列顺序按《功能测试提示词 v3.0》排，并加了项目名称与功能点两列、把用例标题提前到标题列后面。
 * <ul>
 *   <li>Excel：带标题带、深蓝表头、斑马行、短字段居中，截图真正嵌进单元格并按比例缩放。</li>
 *   <li>Markdown：带截图引用；CSV / JSON / HTML：只写截图路径。</li>
 * </ul>
 */
@Component
public class CaseExporter {

    /** 固定列顺序：编号 → 项目 → 系统 → 模块/一二级标题/功能点 → 用例标题 → 其余明细。 */
    public static final List<String> HEADERS = List.of(
            "用例编号", "项目名称", "系统名字", "模块分类", "一级标题", "二级标题", "功能点", "用例标题",
            "设计需求", "测试追踪", "终止条件", "前提与约束", "输入及操作说明", "期望测试结果",
            "测试执行人", "测试时间", "实际测试结果", "未通过详情", "审核人", "审核时间", "备注",
            "请求方式", "URL", "携带参数", "执行状态", "截图路径", "截图");

    /** 列宽（字符数），与屏幕上的列顺序一一对应；截图列按 240px 图片留宽。 */
    private static final int[] WIDTHS = {12, 20, 22, 12, 12, 14, 14, 30, 28, 16, 18, 30, 40, 32, 10, 10,
            24, 14, 10, 10, 22, 10, 28, 18, 10, 34, 34};

    /** 短字段列居中：编号、模块/标题/功能点、执行人/时间、请求方式、状态。 */
    private static final Set<Integer> CENTERED = Set.of(0, 3, 4, 5, 6, 14, 15, 18, 19, 21, 24);

    /** 第二张 sheet「报告-功能测试用例」：只保留报告需要的 10 列。 */
    public static final List<String> REPORT_HEADERS = List.of(
            "用例编号", "系统名字", "模块分类", "一级标题", "二级标题", "功能点",
            "用例标题", "输入及操作说明", "期望测试结果", "实际测试结果");

    private static final int[] REPORT_WIDTHS = {12, 22, 12, 12, 14, 14, 30, 44, 34, 14};

    /** 报告 sheet 里居中的短字段：编号、模块/标题/功能点、实际结果。 */
    private static final Set<Integer> REPORT_CENTERED = Set.of(0, 2, 3, 4, 5, 9);

    private static final int SCREENSHOT_COLUMN = HEADERS.size() - 1;
    private static final double MAX_IMAGE_WIDTH = 236.0;
    private static final double MAX_IMAGE_HEIGHT = 210.0;
    private static final float HEADER_ROW_HEIGHT = 32f;

    private static final byte[] TITLE_BG = {(byte) 0x1F, (byte) 0x4E, (byte) 0x79};
    private static final byte[] INFO_BG = {(byte) 0xDC, (byte) 0xE6, (byte) 0xF1};
    private static final byte[] ZEBRA_BG = {(byte) 0xF5, (byte) 0xF8, (byte) 0xFC};
    private static final byte[] BORDER = {(byte) 0xD3, (byte) 0xDA, (byte) 0xE4};

    public record ExportCase(String caseNo, String projectName, String systemName, String moduleCat,
                             String l1, String l2, String point, String title, String designRequirement,
                             String testTrack, String stopCond, String preconditions, String steps,
                             String expected, String executor, String execTime, String actualResult,
                             String failDetail, String reviewer, String reviewTime, String remark,
                             String method, String url, String params, String status,
                             String screenshotPath, Path screenshotFile) {

        /** 一行 27 列；截图列由各格式自行处理，这里留空。 */
        public List<String> cells() {
            return List.of(cell(caseNo), cell(projectName), cell(systemName), cell(moduleCat), cell(l1),
                    cell(l2), cell(point), cell(title), cell(designRequirement), cell(testTrack), cell(stopCond),
                    cell(preconditions), cell(steps), cell(expected), cell(executor), cell(execTime),
                    cell(actualResult), cell(failDetail), cell(reviewer), cell(reviewTime), cell(remark),
                    cell(method), cell(url), cell(params), cell(status), cell(screenshotPath), "");
        }

        /** 报告 sheet 的 10 列：实际测试结果默认「通过」，用例里标了失败/未通过的才是「未通过」。 */
        public List<String> reportCells() {
            return List.of(cell(caseNo), cell(systemName), cell(moduleCat), cell(l1), cell(l2), cell(point),
                    cell(title), cell(steps), cell(expected), cell(reportResult()));
        }

        public String reportResult() {
            String value = status == null ? "" : status;
            return value.contains("失败") || value.contains("未通过") ? "未通过" : "通过";
        }

        public Map<String, String> asMap() {
            List<String> values = cells();
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < HEADERS.size(); i++) {
                map.put(HEADERS.get(i), i == SCREENSHOT_COLUMN ? "" : values.get(i));
            }
            map.put("截图文件", screenshotPath == null ? "" : screenshotPath);
            return map;
        }
    }

    public static final String FORMAT_XLSX = "xlsx";
    public static final String FORMAT_MD = "md";
    public static final String FORMAT_CSV = "csv";
    public static final String FORMAT_JSON = "json";
    public static final String FORMAT_HTML = "html";

    /** 生成文件内容；format 为 xlsx/md/csv/json/html。 */
    public byte[] render(String format, List<ExportCase> cases) {
        return switch (format == null ? FORMAT_XLSX : format) {
            case FORMAT_MD -> markdown(cases).getBytes(StandardCharsets.UTF_8);
            case FORMAT_CSV -> csv(cases).getBytes(StandardCharsets.UTF_8);
            case FORMAT_JSON -> json(cases);
            case FORMAT_HTML -> html(cases).getBytes(StandardCharsets.UTF_8);
            default -> excel(cases);
        };
    }

    public String fileName(String format) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "功能测试用例_" + stamp + "." + (format == null ? FORMAT_XLSX : format);
    }

    private byte[] excel(List<ExportCase> cases) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("功能测试用例");
            sheet.setDefaultRowHeightInPoints(55);
            CellStyle title = titleStyle(workbook);
            CellStyle info = infoStyle(workbook);
            CellStyle head = headerStyle(workbook);
            CellStyle bodyLeft = bodyStyle(workbook, false, false);
            CellStyle bodyLeftZebra = bodyStyle(workbook, false, true);
            CellStyle bodyCenter = bodyStyle(workbook, true, false);
            CellStyle bodyCenterZebra = bodyStyle(workbook, true, true);

            String projectName = firstNonBlank(cases.stream().map(ExportCase::projectName).toList());
            String systemName = firstNonBlank(cases.stream().map(ExportCase::systemName).toList());
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(36);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("功能测试用例");
            titleCell.setCellStyle(title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, SCREENSHOT_COLUMN));

            String infoText = "项目：" + orDash(projectName) + "　　系统：" + orDash(systemName)
                    + "　　用例数：" + cases.size() + "　　导出时间："
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            Row infoRow = sheet.createRow(1);
            infoRow.setHeightInPoints(24);
            Cell infoCell = infoRow.createCell(0);
            infoCell.setCellValue(infoText);
            infoCell.setCellStyle(info);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, SCREENSHOT_COLUMN));

            Row headRow = sheet.createRow(2);
            headRow.setHeightInPoints(HEADER_ROW_HEIGHT);
            for (int i = 0; i < HEADERS.size(); i++) {
                Cell cell = headRow.createCell(i);
                cell.setCellValue(HEADERS.get(i));
                cell.setCellStyle(head);
            }

            int rowIndex = 3;
            for (ExportCase item : cases) {
                Row row = sheet.createRow(rowIndex);
                row.setHeightInPoints(55);
                boolean zebra = (rowIndex - 3) % 2 == 1;
                List<String> values = item.cells();
                for (int i = 0; i < values.size(); i++) {
                    Cell cell = row.createCell(i);
                    cell.setCellValue(values.get(i));
                    boolean center = CENTERED.contains(i);
                    cell.setCellStyle(center ? (zebra ? bodyCenterZebra : bodyCenter)
                            : (zebra ? bodyLeftZebra : bodyLeft));
                }
                embedScreenshot(workbook, sheet, item, rowIndex);
                rowIndex++;
            }

            for (int i = 0; i < HEADERS.size(); i++) {
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            sheet.createFreezePane(1, 3);
            sheet.setAutoFilter(new CellRangeAddress(2, Math.max(2, rowIndex - 1), 0, HEADERS.size() - 1));
            sheet.setDisplayGridlines(false);

            // 第二张 sheet：报告用的精简列，样式与第一张一致（标题带、深蓝表头、斑马行、短字段居中）。
            XSSFSheet report = workbook.createSheet("报告-功能测试用例");
            report.setDefaultRowHeightInPoints(55);
            Row reportTitle = report.createRow(0);
            reportTitle.setHeightInPoints(36);
            Cell reportTitleCell = reportTitle.createCell(0);
            reportTitleCell.setCellValue("功能测试用例报告");
            reportTitleCell.setCellStyle(title);
            report.addMergedRegion(new CellRangeAddress(0, 0, 0, REPORT_HEADERS.size() - 1));

            Row reportInfo = report.createRow(1);
            reportInfo.setHeightInPoints(24);
            Cell reportInfoCell = reportInfo.createCell(0);
            reportInfoCell.setCellValue(infoText);
            reportInfoCell.setCellStyle(info);
            report.addMergedRegion(new CellRangeAddress(1, 1, 0, REPORT_HEADERS.size() - 1));

            Row reportHead = report.createRow(2);
            reportHead.setHeightInPoints(HEADER_ROW_HEIGHT);
            for (int i = 0; i < REPORT_HEADERS.size(); i++) {
                Cell cell = reportHead.createCell(i);
                cell.setCellValue(REPORT_HEADERS.get(i));
                cell.setCellStyle(head);
            }
            int reportRow = 3;
            for (ExportCase item : cases) {
                Row row = report.createRow(reportRow);
                row.setHeightInPoints(55);
                boolean zebra = (reportRow - 3) % 2 == 1;
                List<String> values = item.reportCells();
                for (int i = 0; i < values.size(); i++) {
                    Cell cell = row.createCell(i);
                    cell.setCellValue(values.get(i));
                    boolean center = REPORT_CENTERED.contains(i);
                    cell.setCellStyle(center ? (zebra ? bodyCenterZebra : bodyCenter)
                            : (zebra ? bodyLeftZebra : bodyLeft));
                }
                reportRow++;
            }
            for (int i = 0; i < REPORT_HEADERS.size(); i++) {
                report.setColumnWidth(i, REPORT_WIDTHS[i] * 256);
            }
            report.createFreezePane(1, 3);
            report.setAutoFilter(new CellRangeAddress(2, Math.max(2, reportRow - 1), 0, REPORT_HEADERS.size() - 1));
            report.setDisplayGridlines(false);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出 Excel 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把截图按比例缩进「截图」列：用 EMU 偏移直接把图片画在单元格左上角，
     * 再按缩放后的高度调行高——之前只靠 resize() + 固定行高，Excel 会把图片裁成一条色带。
     */
    private void embedScreenshot(XSSFWorkbook workbook, XSSFSheet sheet, ExportCase item, int rowIndex) {
        Path file = item.screenshotFile();
        Cell cell = sheet.getRow(rowIndex).getCell(SCREENSHOT_COLUMN);
        if (file == null || !Files.isRegularFile(file)) {
            cell.setCellValue("-");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String lower = file.getFileName().toString().toLowerCase();
            int type = (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                    ? Workbook.PICTURE_TYPE_JPEG : Workbook.PICTURE_TYPE_PNG;
            int pictureIndex = workbook.addPicture(bytes, type);

            double width = 0;
            double height = 0;
            java.awt.image.BufferedImage buffered = javax.imageio.ImageIO.read(file.toFile());
            if (buffered != null) { width = buffered.getWidth(); height = buffered.getHeight(); }
            double scale = 1.0;
            if (width > 0 && height > 0) {
                scale = Math.min(1.0, Math.min(MAX_IMAGE_WIDTH / width, MAX_IMAGE_HEIGHT / height));
            }
            double drawWidth = Math.max(1, width * scale);
            double drawHeight = Math.max(1, height * scale);

            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = new XSSFClientAnchor(0, 0, 0, 0,
                    SCREENSHOT_COLUMN, rowIndex, SCREENSHOT_COLUMN, rowIndex);
            anchor.setDx2((int) Math.round(drawWidth * 9525));
            anchor.setDy2((int) Math.round(drawHeight * 9525));
            drawing.createPicture(anchor, pictureIndex);

            double points = drawHeight * 0.75 + 6;
            Row row = sheet.getRow(rowIndex);
            if (points > row.getHeightInPoints()) {
                row.setHeightInPoints((float) Math.min(points, 380));
            }
            cell.setCellValue("");
        } catch (Exception e) {
            cell.setCellValue(item.screenshotPath() == null ? "-" : item.screenshotPath());
        }
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(TITLE_BG, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setFontHeightInPoints((short) 16);
        font.setBold(true);
        font.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, null));
        style.setFont(font);
        return style;
    }

    private CellStyle infoStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(INFO_BG, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setIndention((short) 2);
        XSSFFont font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setFontHeightInPoints((short) 10);
        font.setColor(new XSSFColor(new byte[]{(byte) 0x33, (byte) 0x44, (byte) 0x55}, null));
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(TITLE_BG, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        thinBorder(style);
        XSSFFont font = workbook.createFont();
        font.setFontName("微软雅黑");
        font.setFontHeightInPoints((short) 11);
        font.setBold(true);
        font.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, null));
        style.setFont(font);
        return style;
    }

    private CellStyle bodyStyle(XSSFWorkbook workbook, boolean center, boolean zebra) {
        XSSFCellStyle style = workbook.createCellStyle();
        if (zebra) {
            style.setFillForegroundColor(new XSSFColor(ZEBRA_BG, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setAlignment(center ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT);
        style.setWrapText(true);
        if (!center) style.setIndention((short) 1);
        thinBorder(style);
        XSSFFont font = workbook.createFont();
        font.setFontName("宋体");
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        return style;
    }

    private void thinBorder(XSSFCellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        XSSFColor color = new XSSFColor(BORDER, null);
        style.setTopBorderColor(color);
        style.setBottomBorderColor(color);
        style.setLeftBorderColor(color);
        style.setRightBorderColor(color);
    }

    private String markdown(List<ExportCase> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 功能测试用例\n\n");
        sb.append("> 生成时间：").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("  \n");
        sb.append("> 用例数量：").append(cases.size()).append("  \n");
        sb.append("> 格式：功能测试提示词 v3.0\n\n");
        sb.append("| ").append(String.join(" | ", HEADERS)).append(" |\n");
        sb.append("|").append("---|".repeat(HEADERS.size())).append("\n");
        for (ExportCase item : cases) {
            List<String> cells = new ArrayList<>(item.cells());
            String link = item.screenshotPath() == null || item.screenshotPath().isBlank()
                    ? "" : "![" + escape(item.caseNo()) + "](" + item.screenshotPath().replace(" ", "%20") + ")";
            cells.set(cells.size() - 1, link);
            List<String> escaped = cells.stream().map(CaseExporter::escape).toList();
            sb.append("| ").append(String.join(" | ", escaped)).append(" |\n");
        }
        return sb.toString();
    }

    private String csv(List<ExportCase> cases) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(String.join(",", HEADERS.stream().map(CaseExporter::quote).toList())).append("\r\n");
        for (ExportCase item : cases) {
            sb.append(String.join(",", item.cells().stream().map(CaseExporter::quote).toList())).append("\r\n");
        }
        return sb.toString();
    }

    private byte[] json(List<ExportCase> cases) {
        try {
            List<Map<String, String>> data = new ArrayList<>();
            for (ExportCase item : cases) data.add(item.asMap());
            return new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(data);
        } catch (Exception e) {
            throw new IllegalStateException("导出 JSON 失败：" + e.getMessage(), e);
        }
    }

    private String html(List<ExportCase> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'><title>功能测试用例</title>")
                .append("<style>body{font:13px 'Microsoft YaHei',sans-serif;padding:16px;color:#25303c}")
                .append("h2{font-size:18px;margin:0 0 6px}")
                .append(".meta{color:#6b7684;font-size:12px;margin-bottom:12px}")
                .append("table{border-collapse:collapse;width:100%}")
                .append("th,td{border:1px solid #d3dae4;padding:6px 8px;vertical-align:middle}")
                .append("th{background:#1f4e79;color:#fff;position:sticky;top:0;font-weight:600}")
                .append("tbody tr:nth-child(even){background:#f5f8fc}")
                .append("td.num{white-space:nowrap;color:#3b4a5a}</style></head><body>")
                .append("<h2>功能测试用例</h2><div class='meta'>用例数 ").append(cases.size())
                .append(" · 导出时间 ").append(LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("</div>")
                .append("<table><thead><tr>");
        HEADERS.forEach(h -> sb.append("<th>").append(escape(h)).append("</th>"));
        sb.append("</tr></thead><tbody>");
        for (ExportCase item : cases) {
            sb.append("<tr>");
            List<String> cells = new ArrayList<>(item.cells());
            String link = item.screenshotPath() == null || item.screenshotPath().isBlank()
                    ? "" : "<a href='" + escape(item.screenshotPath()) + "'>" + escape(item.screenshotPath()) + "</a>";
            cells.set(cells.size() - 1, link);
            for (int i = 0; i < cells.size(); i++) {
                sb.append(i == 0 ? "<td class='num'>" : "<td>").append(escape(cells.get(i))).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></body></html>");
        return sb.toString();
    }

    private static String cell(String value) {
        if (value == null) return "-";
        String v = value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ").trim();
        return v.isEmpty() ? "-" : v;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String firstNonBlank(List<String> values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private static String quote(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
