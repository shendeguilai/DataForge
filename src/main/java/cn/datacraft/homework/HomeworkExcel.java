package cn.datacraft.homework;

import cn.datacraft.homework.HomeworkTypes.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static cn.datacraft.homework.HomeworkTypes.cellKey;

/** A small SpreadsheetML writer. All external strings are inline text, never formulas or links. */
final class HomeworkExcel {
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private record Sheet(String name, List<List<Object>> rows, int header, int frozenColumns, List<Integer> widths) {}
    static byte[] export(Assignment a) {
        List<List<Object>> matrix = new ArrayList<>();
        matrix.add(List.of(a.title));
        matrix.add(List.of("班级", a.className, "题库域", a.domain));
        matrix.add(List.of("开始", HomeworkReports.DATE.format(a.startAt), "截止", HomeworkReports.DATE.format(a.endAt)));
        matrix.add(List.of("最近成功更新", a.syncedAt == null ? "尚未完整核验" : HomeworkReports.DATE.format(a.syncedAt), "说明", "待核验不代表未完成；上次 AC 数据仍需核对"));
        List<Object> headers = new ArrayList<>(List.of("学生姓名", "OJ UID", "已完成（含保留数据）", "已确认截止前完成"));
        for (Problem p : a.problems) headers.add(p.pid() + "\n" + p.title());
        matrix.add(headers);
        List<List<Object>> pending = new ArrayList<>();
        pending.add(List.of("学生姓名", "OJ UID", "已确认待完成题目", "待核验题目"));
        for (Student s : a.students) {
            List<Object> row = new ArrayList<>(List.of(s.name(), Long.toString(s.uid()),
                    a.problems.stream().filter(p -> a.cells.get(cellKey(s.uid(), p.docId())) != null && a.cells.get(cellKey(s.uid(), p.docId())).completed).count(),
                    a.problems.stream().filter(p -> { Cell c = a.cells.get(cellKey(s.uid(), p.docId())); return !HomeworkReports.unknown(c) && c.completed && c.beforeDeadline; }).count()));
            List<String> missing = new ArrayList<>(), unknown = new ArrayList<>();
            for (Problem p : a.problems) {
                Cell c = a.cells.get(cellKey(s.uid(), p.docId())); row.add(HomeworkReports.label(c));
                if (HomeworkReports.unknown(c)) unknown.add(p.pid());
                else if (!c.completed) missing.add(p.pid() + ("PENDING".equals(c.status) ? "（评测中）" : ""));
            }
            matrix.add(row);
            if (!missing.isEmpty() || !unknown.isEmpty()) pending.add(List.of(s.name(), Long.toString(s.uid()), String.join("、", missing), String.join("、", unknown)));
        }
        List<List<Object>> stats = new ArrayList<>();
        stats.add(List.of("题号", "题目名称", "总人数", "已确认 AC", "截止前完成", "补交", "尝试中", "评测中", "未见提交", "待核验"));
        for (ProblemStats p : HomeworkReports.stats(a)) stats.add(List.of(p.pid(), p.title(), p.total(), p.completed(), p.onTime(), p.late(), p.attempted(), p.pending(), p.empty(), p.unknown()));
        List<Integer> widths = new ArrayList<>(List.of(18, 14, 22, 22)); a.problems.forEach(p -> widths.add(22));
        return workbook(List.of(new Sheet("班级完成表", matrix, 4, 2, widths),
                new Sheet("每题统计", stats, 0, 2, List.of(14, 24, 12, 14, 16, 12, 12, 12, 14, 14)),
                new Sheet("待完成清单", pending, 0, 2, List.of(18, 14, 60, 60))));
    }
    private static byte[] workbook(List<Sheet> sheets) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            StringBuilder content = new StringBuilder(XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder workbook = new StringBuilder(XML + "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + REL + "\"><sheets>");
            StringBuilder rels = new StringBuilder(XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"styles\" Type=\"" + REL + "/styles\" Target=\"styles.xml\"/>");
            for (int i = 0; i < sheets.size(); i++) {
                int index = i + 1;
                content.append("<Override PartName=\"/xl/worksheets/sheet").append(index).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                workbook.append("<sheet name=\"").append(escape(sheets.get(i).name)).append("\" sheetId=\"").append(index).append("\" r:id=\"rId").append(index).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(index).append("\" Type=\"").append(REL).append("/worksheet\" Target=\"worksheets/sheet").append(index).append(".xml\"/>");
                entry(zip, "xl/worksheets/sheet" + index + ".xml", sheet(sheets.get(i)));
            }
            entry(zip, "[Content_Types].xml", content.append("</Types>").toString());
            entry(zip, "_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"" + REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
            entry(zip, "xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
            entry(zip, "xl/styles.xml", styles()); zip.finish(); return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Excel 导出失败，请重试"); }
    }
    private static String sheet(Sheet sheet) {
        StringBuilder xml = new StringBuilder(XML + "<worksheet xmlns=\"" + MAIN + "\"><sheetViews><sheetView workbookViewId=\"0\"><pane xSplit=\"" + sheet.frozenColumns + "\" ySplit=\"" + (sheet.header + 1) + "\" topLeftCell=\"" + column(sheet.frozenColumns) + (sheet.header + 2) + "\" activePane=\"bottomRight\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"24\"/><cols>");
        for (int i = 0; i < sheet.widths.size(); i++) xml.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(sheet.widths.get(i)).append("\" customWidth=\"1\"/>");
        xml.append("</cols><sheetData>");
        for (int row = 0; row < sheet.rows.size(); row++) {
            xml.append("<row r=\"").append(row + 1).append("\" ht=\"").append(row == sheet.header ? 42 : 28).append("\" customHeight=\"1\">");
            for (int col = 0; col < sheet.rows.get(row).size(); col++) {
                Object value = sheet.rows.get(row).get(col); String text = String.valueOf(value);
                int style = row == sheet.header ? 1 : text.equals("AC") ? 2 : text.equals("AC · 补交") ? 3 : text.startsWith("待核验") ? 4 : text.equals("尝试中") || text.equals("评测中") ? 5 : 0;
                xml.append("<c r=\"").append(column(col)).append(row + 1).append("\" s=\"").append(style).append("\"");
                if (value instanceof Number) xml.append("><v>").append(value).append("</v></c>");
                else xml.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(text)).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData><autoFilter ref=\"A").append(sheet.header + 1).append(":").append(column(sheet.widths.size() - 1)).append(sheet.rows.size()).append("\"/></worksheet>").toString();
    }
    static String column(int zeroBased) {
        StringBuilder result = new StringBuilder();
        for (int n = zeroBased + 1; n > 0; n = (n - 1) / 26) result.insert(0, (char) ('A' + (n - 1) % 26));
        return result.toString();
    }
    private static String escape(String text) {
        StringBuilder cleaned = new StringBuilder();
        text.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xd7ff || c >= 0xe000 && c <= 0xfffd || c >= 0x10000 && c <= 0x10ffff).limit(32000).forEach(cleaned::appendCodePoint);
        return cleaned.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static String styles() {
        return XML + "<styleSheet xmlns=\"" + MAIN + "\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font></fonts><fills count=\"7\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + fill("FF3F6F9F") + fill("FFE5F3EB") + fill("FFF2EAF8") + fill("FFF1F4F8") + fill("FFFFF4DE")
                + "</fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"6\">"
                + xf(0, 0) + xf(1, 2) + xf(0, 3) + xf(0, 4) + xf(0, 5) + xf(0, 6)
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }
    private static String fill(String color) { return "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"" + color + "\"/><bgColor indexed=\"64\"/></patternFill></fill>"; }
    private static String xf(int font, int fill) { return "<xf numFmtId=\"0\" fontId=\"" + font + "\" fillId=\"" + fill + "\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"; }
}
