package cn.datacraft.cspsim;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class CspTypes {
    private CspTypes() {}
    @JsonIgnoreProperties("encryptedCode")
    public static class Student {
        public String name, className, studentNumber;
        public boolean enabled = true;
    }
    public static class Exam {
        public String joinCode;
        public String name, environment = "LINUX", mode = "TEACHING", group = "J";
        public String regionCode = "GD";
        public String rootPath, folderPattern = "{examNumber}";
        public int durationMinutes = 210;
        public int round = 1;
        public Instant startedAt, deadline;
        public String publishedBatch;
        public List<Problem> problems = new ArrayList<>();
    }
    public static class Problem {
        public String id = UUID.randomUUID().toString(), name, directory, sourceName;
        public String ioMode = "STDIO", inputName, outputName, scoring = "POINTS";
        public int timeLimitMs = 1000, memoryMb = 256;
        public BigDecimal maxScore = new BigDecimal("100");
        public String statement = "", dataVersion;
        public boolean dataConfirmed;
        public List<TestCase> cases = new ArrayList<>();
        public List<TestCase> samples = new ArrayList<>();
    }
    public static class TestCase {
        public String id, inputBlob, answerBlob, subtask = "";
        public BigDecimal score = BigDecimal.ZERO;
    }
    public static class Entry {
        public boolean directory;
        public String blob;
        public long bytes;
        public Instant modifiedAt = Instant.now();
        public Entry() {}
        public Entry(boolean directory) { this.directory = directory; }
    }
    public static class Participation {
        public String studentId, examNumber, folderName, tokenHash, latestSubmission;
        public boolean active = true;
        public Instant lastSeen;
        public Map<String, Entry> entries = new LinkedHashMap<>();
    }
    public static class Submission {
        public String participationId, studentId;
        public Instant submittedAt = Instant.now();
        public boolean automatic;
        public int round = 1;
        public Map<String, Entry> entries = new LinkedHashMap<>();
    }
    public static class Batch {
        public boolean review, published;
        public int round = 1;
        public Instant createdAt = Instant.now();
        public List<String> taskIds = new ArrayList<>();
    }
    public static class Task {
        public String batchId, participationId, studentId, submissionId, problemId, sourcePath, sourceBlob;
        public String leaseToken;
        public int attempts;
        public String compiler = "g++ 9.3.0", standard = "c++14";
        public List<String> flags = List.of("-O2", "-std=c++14", "-static");
        public Problem problem;
        public List<String> directoryIssues = new ArrayList<>();
        public Result result;
    }
    public static class Result {
        public String verdict, message = "", environment = "";
        public BigDecimal score = BigDecimal.ZERO;
        public List<CaseResult> cases = new ArrayList<>();
    }
    public static class CaseResult {
        public String id, verdict, message = "", expected = "", actual = "";
        public int differenceLine, differenceColumn;
        public long runtimeMs, memoryBytes;
    }
    public record StudentInput(String name, String className, String studentNumber) {}
    public record UpdateStudent(StudentInput student, boolean enabled) {}
    public record Assignment(String studentId, String examNumber, String folderName) {}
    public static class CreateExam {
        public Exam exam = new Exam();
        public List<Assignment> students = new ArrayList<>();
    }
    public record Join(String name, String code) {}
    public record FileOperation(String action, String path, String target, Long revision) {}
    public record GradeRequest(Map<String, Map<String, String>> reviewPaths) {}
    public record Complete(String leaseToken, Result result) {}
    public record DataConfig(String dataVersion, String scoring, List<CaseConfig> cases) {}
    public record CaseConfig(String id, BigDecimal score, String subtask) {}
}
