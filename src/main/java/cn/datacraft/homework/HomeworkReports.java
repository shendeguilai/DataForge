package cn.datacraft.homework;

import cn.datacraft.homework.HomeworkTypes.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static cn.datacraft.homework.HomeworkTypes.cellKey;

final class HomeworkReports {
    private HomeworkReports() {}
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    static boolean unknown(Cell c) { return c == null || "UNKNOWN".equals(c.status); }
    static String label(Cell c) {
        if (unknown(c)) return c != null && c.completed ? "待核验（上次 AC）" : "待核验";
        return switch (c.status) {
            case "AC" -> "AC"; case "LATE" -> "AC · 补交"; case "PENDING" -> "评测中";
            case "ATTEMPTED" -> "尝试中"; default -> "未见提交";
        };
    }
    static int failed(Assignment a) { return (int) a.cells.values().stream().filter(c -> unknown(c) && c.error != null).count(); }
    static List<ProblemStats> stats(Assignment a) {
        var result = new ArrayList<ProblemStats>();
        for (Problem p : a.problems) {
            int completed = 0, onTime = 0, late = 0, attempted = 0, pending = 0, empty = 0, unknown = 0;
            for (Student s : a.students) {
                Cell c = a.cells.get(cellKey(s.uid(), p.docId()));
                if (unknown(c)) { unknown++; continue; }
                if (c.completed) { completed++; if (c.beforeDeadline) onTime++; else late++; }
                else if (c.status.equals("PENDING")) pending++;
                else if (c.status.equals("ATTEMPTED")) attempted++;
                else empty++;
            }
            result.add(new ProblemStats(p.docId(), p.pid(), p.title(), a.students.size(), completed, onTime, late, attempted, pending, empty, unknown));
        }
        return result;
    }
    static IncompleteReport incomplete(Assignment a) {
        List<String> missing = new ArrayList<>(), unchecked = new ArrayList<>();
        for (Student s : a.students) {
            List<String> left = new ArrayList<>(), unknown = new ArrayList<>();
            for (Problem p : a.problems) {
                Cell c = a.cells.get(cellKey(s.uid(), p.docId()));
                if (unknown(c)) unknown.add(p.pid());
                else if (!c.completed) left.add(p.pid() + ("PENDING".equals(c.status) ? "（评测中）" : ""));
            }
            if (!left.isEmpty()) missing.add(s.name() + "（UID " + s.uid() + "）：" + String.join("、", left));
            if (!unknown.isEmpty()) unchecked.add(s.name() + "（UID " + s.uid() + "）：" + String.join("、", unknown));
        }
        StringBuilder text = new StringBuilder(a.className + " · " + a.title + "\n截止：" + DATE.format(a.endAt) + "（北京时间）\n\n");
        text.append("已确认待完成（").append(missing.size()).append(" 人）\n");
        text.append(missing.isEmpty() ? "暂无已确认的待完成题目" : String.join("\n", missing));
        if (!unchecked.isEmpty()) text.append("\n\n待核验（不作为未完成结论，").append(unchecked.size()).append(" 人）\n").append(String.join("\n", unchecked));
        text.append("\n\n数据更新：").append(a.syncedAt == null ? "尚未完整核验" : DATE.format(a.syncedAt));
        return new IncompleteReport(text.toString(), missing.size(), unchecked.size());
    }
    static ChangeSummary changes(Assignment a, Map<String, Cell> before, Set<String> checked, Instant at) {
        var result = new ChangeSummary(); result.at = at; result.checked = checked.size();
        result.initial = before.values().stream().noneMatch(c -> c.syncedAt != null);
        for (Student s : a.students) for (Problem p : a.problems) {
            String key = cellKey(s.uid(), p.docId()); if (!checked.contains(key)) continue;
            Cell old = before.get(key), c = a.cells.get(key);
            if (unknown(c)) { result.failed++; continue; }
            var change = new CellChange(s.uid(), s.name(), p.docId(), p.pid(), old == null ? "UNKNOWN" : old.status, c.status);
            if (c.completed && (old == null || !old.completed)) {
                result.newlyCompleted.add(change); if (!c.beforeDeadline) result.newlyLate.add(change);
            } else if (old != null && old.completed && !c.completed) result.changedResults.add(change);
        }
        return result;
    }
    static StudentAssignment studentAssignment(String id, Assignment a, long uid) {
        int completed = 0, onTime = 0, late = 0;
        List<Problem> left = new ArrayList<>(), unknown = new ArrayList<>();
        for (Problem p : a.problems) {
            Cell c = a.cells.get(cellKey(uid, p.docId()));
            if (unknown(c)) unknown.add(p);
            else if (c.completed) { completed++; if (c.beforeDeadline) onTime++; else late++; }
            else left.add(p);
        }
        return new StudentAssignment(id, a.title, a.classId, a.className, a.domain, a.startAt, a.endAt, a.archived,
                a.problems.size(), completed, onTime, late, left, unknown);
    }
}
