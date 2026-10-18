package cn.datacraft.homework;

import java.time.Instant;
import java.util.*;

public final class HomeworkTypes {
    private HomeworkTypes() {}
    public record SettingsInput(String credential, String domain, Boolean autoUpdate) {}
    public record SettingsView(boolean configured, String domain, boolean autoUpdate, Long ojUid,
                               String ojUsername, String connectionState, String message, Instant checkedAt) {}
    public record ClassInput(String name) {}
    public record StudentInput(String name, long uid) {}
    public record Student(String id, String name, long uid, String uname) {}
    public record ClassView(String id, String name, List<Student> students) {}
    public record CreateAssignment(String classId, String title, String domain, Instant startAt,
                                   Instant endAt, List<String> problemIds) {}
    public record Problem(String pid, long docId, String title) {}
    public record Submission(String rid, String domain, long uid, long docId, int status,
                             double score, Instant submittedAt, String contest) {}
    public record AssignmentView(String id, String classId, String className, String title, String domain,
                                 Instant startAt, Instant endAt, boolean archived, List<Student> students,
                                 List<Problem> problems, Map<String, Cell> cells, Instant syncedAt,
                                 Instant attemptedAt, String error, int completedStudents, int completedCells,
                                 int unknownCells, List<ProblemStats> problemStats, ChangeSummary lastChange,
                                 long revision, int failedCells) {}
    public record TaskView(String id, String state, int completed, int total, String message,
                           Instant startedAt, Instant finishedAt, List<String> assignmentIds, int checkedCells, int failedCells) {}
    public record ProblemStats(long docId, String pid, String title, int total, int completed, int onTime,
                               int late, int attempted, int pending, int empty, int unknown) {}
    public record IncompleteReport(String text, int incompleteStudents, int unknownStudents) {}
    public record CellChange(long uid, String name, long docId, String pid, String from, String to) {}
    public static class ChangeSummary {
        public Instant at;
        public boolean initial;
        public int checked, failed;
        public List<CellChange> newlyCompleted = new ArrayList<>();
        public List<CellChange> newlyLate = new ArrayList<>();
        public List<CellChange> changedResults = new ArrayList<>();
    }
    public record StudentAssignment(String id, String title, String classId, String className, String domain,
                                    Instant startAt, Instant endAt, boolean archived, int total, int completed,
                                    int onTime, int late, List<Problem> remaining, List<Problem> unknown) {}
    public record StudentOverview(long uid, String name, String uname, int assignmentCount, int onTimeAssignments,
                                  int lateCells, int outstandingCells, int unknownCells, List<StudentAssignment> assignments) {}
    public record RecordFetch(List<Submission> records, boolean completeHistory) {}

    public static class Settings {
        public String encryptedSid;
        public String domain = "system";
        public boolean autoUpdate = true;
        public Long ojUid;
        public String ojUsername;
        public String connectionState = "UNCONFIGURED";
        public String message;
        public Instant checkedAt;
    }
    public static class SchoolClass {
        public String name;
        public List<Student> students = new ArrayList<>();
    }
    public static class Assignment {
        public String classId, className, title, domain;
        public Instant startAt, endAt;
        public boolean archived;
        public List<Student> students = new ArrayList<>();
        public List<Problem> problems = new ArrayList<>();
        public Map<String, Cell> cells = new LinkedHashMap<>();
        public Instant syncedAt, attemptedAt;
        public String error;
        public long revision;
        public ChangeSummary lastChange;
    }
    public static class Cell {
        public String status = "UNKNOWN";
        public String previousStatus;
        public boolean completed, beforeDeadline;
        public int submissions;
        public double bestScore;
        public Submission latest, accepted;
        public Instant syncedAt;
        public String error;
    }
    public static class PairSnapshot {
        public List<Submission> submissions = new ArrayList<>();
        public Instant syncedAt;
        public Instant fullScanAt;
        public String credentialVersion;
    }
    public static class SyncTask {
        public Set<String> assignmentIds = new LinkedHashSet<>();
        public Set<String> processedIds = new LinkedHashSet<>();
        public int completed, total;
        public int checkedCells, failedCells;
        public Set<String> retryOnlyIds = new LinkedHashSet<>();
        public Set<String> fullScanIds = new LinkedHashSet<>();
        public String message = "等待更新", leaseToken;
        public Instant startedAt, finishedAt;
    }
    public static String cellKey(long uid, long docId) { return uid + ":" + docId; }
}
