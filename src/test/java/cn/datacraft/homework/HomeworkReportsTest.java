package cn.datacraft.homework;

import cn.datacraft.homework.HomeworkTypes.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.assertj.core.api.Assertions.*;

class HomeworkReportsTest {
    Assignment fixture() {
        var a = new Assignment(); a.title = "周作业 & <练习>"; a.className = "提高班"; a.domain = "system";
        a.startAt = Instant.parse("2026-10-01T00:00:00Z"); a.endAt = Instant.parse("2026-10-08T00:00:00Z");
        a.students.add(new Student("s1", "已确认学生", 1, "one")); a.students.add(new Student("s2", "待核验学生", 2, "two"));
        a.problems.add(new Problem("P0032", 32, "题目一"));
        var missing = new Cell(); missing.status = "EMPTY"; a.cells.put("1:32", missing);
        var unknown = new Cell(); unknown.status = "UNKNOWN"; unknown.previousStatus = "AC"; unknown.completed = true; unknown.error = "权限不足"; a.cells.put("2:32", unknown);
        return a;
    }
    @Test void keepsUnverifiedOldAcOutOfConfirmedMissingListAndProblemAcCount() {
        var a = fixture(); var report = HomeworkReports.incomplete(a); var stats = HomeworkReports.stats(a).get(0);
        assertThat(report.incompleteStudents()).isEqualTo(1); assertThat(report.unknownStudents()).isEqualTo(1);
        String confirmed = report.text().split("待核验（不作为")[0];
        assertThat(confirmed).contains("已确认学生").doesNotContain("待核验学生");
        assertThat(stats.completed()).isZero(); assertThat(stats.unknown()).isEqualTo(1); assertThat(stats.empty()).isEqualTo(1);
    }
    @Test void writesRealXlsxWithThreeSheetsEscapedTextAndNoExecutableFormulas() throws Exception {
        var a = fixture(); a.students.set(0, new Student("s1", "=HYPERLINK(\"https://example.invalid\")\u0001", 1, "one"));
        byte[] file = HomeworkExcel.export(a); Map<String, byte[]> entries = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertThat(entries).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml");
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (byte[] xml : entries.values()) factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        String sheet = new String(entries.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertThat(sheet).contains("t=\"inlineStr\"", "=HYPERLINK", "&amp;", "&lt;", "待核验（上次 AC）").doesNotContain("<f>", "\u0001");
        assertThat(new String(entries.get("xl/workbook.xml"), StandardCharsets.UTF_8)).contains("班级完成表", "每题统计", "待完成清单");
        assertThat(HomeworkExcel.column(25)).isEqualTo("Z"); assertThat(HomeworkExcel.column(26)).isEqualTo("AA");
        Files.write(Path.of("target/homework-export-check.xlsx"), file);
    }
}
