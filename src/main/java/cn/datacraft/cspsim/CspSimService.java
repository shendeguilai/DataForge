package cn.datacraft.cspsim;

import cn.datacraft.cspsim.CspTypes.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;

@Service
@EnableScheduling
@Transactional
public class CspSimService {
    private final CspRecordRepository records;
    private final ObjectMapper json;
    private final CspFiles files;
    private final String workerSecret;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public CspSimService(CspRecordRepository records, ObjectMapper json, CspFiles files,
                         @Value("${dataforge.csp-sim.worker-secret:}") String secret) {
        this(records, json, files, secret, Clock.systemUTC());
    }
    CspSimService(CspRecordRepository records, ObjectMapper json, CspFiles files,
                  String secret, Clock clock) {
        this.records = records; this.json = json; this.files = files;
        this.workerSecret = secret; this.clock = clock;
    }
    private Instant now() { return clock.instant(); }
    private String encode(Object data) {
        try { return json.writeValueAsString(data); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private <T> T decode(CspRecord record, Class<T> type) {
        try { return json.readValue(record.payload, type); } catch (JsonProcessingException e) { throw new IllegalStateException("保存的记录无法读取", e); }
    }
    private CspRecord create(String kind, String owner, String parent, Object data) {
        CspRecord record = new CspRecord(); record.id = UUID.randomUUID().toString(); record.kind = kind;
        if (data instanceof Student student) record.uniqueKey = "student:" + hash(owner + "\0" + student.studentNumber);
        if (data instanceof Exam exam) {
            exam.joinCode = newJoinCode(); record.uniqueKey = "exam:" + exam.joinCode;
        }
        record.owner = owner; record.parentId = parent; save(record, data); return record;
    }
    private void save(CspRecord record, Object data) { record.payload = encode(data); record.updatedAt = now(); records.saveAndFlush(record); }
    private String newJoinCode() {
        SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < 100; attempt++) {
            String code = Integer.toString(100000 + random.nextInt(900000));
            if (!records.existsByUniqueKey("exam:" + code)) return code;
        }
        throw new IllegalStateException("考场编号暂时无法分配，请稍后重试");
    }
    private Exam ensureJoinCode(CspRecord record) {
        Exam exam = decode(record, Exam.class);
        if (exam.joinCode == null) {
            exam.joinCode = newJoinCode(); record.uniqueKey = "exam:" + exam.joinCode; save(record, exam);
        }
        return exam;
    }
    private CspRecord resolveExam(String reference) {
        if (reference != null && reference.matches("[0-9]{6}")) {
            CspRecord record = records.findByUniqueKey("exam:" + reference).orElseThrow(() -> new NoSuchElementException("考场编号不存在，请向老师确认"));
            return get(record.id, "EXAM", true);
        }
        if (reference == null || !reference.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("考场编号应为六位数字");
        return get(reference, "EXAM", true);
    }
    public Map<String,Object> entryCode(String reference) {
        return Map.of("examCode", ensureJoinCode(resolveExam(reference)).joinCode);
    }
    private CspRecord get(String id, String kind, boolean lock) {
        CspRecord record = (lock ? records.lock(id) : records.findById(id)).orElseThrow(() -> new NoSuchElementException("记录不存在"));
        if (!kind.equals(record.kind)) throw new NoSuchElementException("记录不存在");
        return record;
    }
    private CspRecord owned(String id, String kind, String owner) {
        CspRecord record = get(id, kind, true);
        if (!record.owner.equals(owner)) throw new AccessDeniedException("只能管理自己的考场和学生");
        return record;
    }
    private static String text(String value, int max, String label) {
        String v = value == null ? "" : value.trim();
        if (v.isBlank() || v.length() > max || v.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException(label + "无效");
        return v;
    }
    private static String optional(String value, int max) { return value == null || value.isBlank() ? "" : text(value, max, "字段"); }
    private static String randomToken() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean equal(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private Map<String, Object> studentView(CspRecord record, boolean code) {
        Student s = decode(record, Student.class); Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id); view.put("name", s.name); view.put("className", s.className); view.put("studentNumber", s.studentNumber); view.put("enabled", s.enabled);
        if (code) view.put("code", s.studentNumber); return view;
    }
    public List<Map<String, Object>> students(String owner) { return records.findByKindAndOwnerOrderByUpdatedAtDesc("STUDENT", owner).stream().map(r -> studentView(r, true)).toList(); }
    public Map<String, Object> addStudent(String owner, StudentInput input) {
        if (input == null) throw new IllegalArgumentException("请填写学生信息");
        Student student = new Student(); student.name = text(input.name(), 50, "姓名"); student.className = optional(input.className(), 80);
        student.studentNumber = text(input.studentNumber(), 50, "学号");
        if (records.findByKindAndOwnerOrderByUpdatedAtDesc("STUDENT", owner).stream().anyMatch(r -> decode(r, Student.class).studentNumber.equals(student.studentNumber)))
            throw new IllegalArgumentException("学号已存在，请勿重复导入");
        return studentView(create("STUDENT", owner, null, student), true);
    }
    public Map<String,Object> updateStudent(String owner, String id, UpdateStudent input) {
        if (input == null || input.student() == null) throw new IllegalArgumentException("请填写学生信息");
        CspRecord r = owned(id,"STUDENT",owner); Student s = decode(r,Student.class); StudentInput info = input.student();
        String number = text(info.studentNumber(),50,"学号");
        if (records.findByKindAndOwnerOrderByUpdatedAtDesc("STUDENT",owner).stream().anyMatch(other -> !other.id.equals(id) && decode(other,Student.class).studentNumber.equals(number))) throw new IllegalArgumentException("学号已存在");
        boolean identityChanged = !s.studentNumber.equals(number) || !s.name.equals(info.name() == null ? "" : info.name().trim());
        s.name = text(info.name(),50,"姓名"); s.className = optional(info.className(),80); s.studentNumber = number; s.enabled = input.enabled(); r.uniqueKey = "student:" + hash(owner + "\0" + number); save(r,s);
        if (identityChanged || !s.enabled) revokeSessions(owner, id);
        return studentView(r,true);
    }
    public List<Map<String, Object>> importStudents(String owner, byte[] csv) {
        List<List<String>> rows = CspFiles.csv(csv);
        if (rows.isEmpty()) throw new IllegalArgumentException("名单为空");
        List<String> head = rows.get(0); int name = column(head, "姓名", "name"), cls = column(head, "班级", "className"), number = column(head, "学号", "studentNumber");
        if (name < 0 || number < 0) throw new IllegalArgumentException("CSV必须包含姓名和学号列，可选班级列");
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) result.add(addStudent(owner, new StudentInput(cell(row, name), cell(row, cls), cell(row, number))));
        return result;
    }
    private static int column(List<String> row, String a, String b) { for (int i = 0; i < row.size(); i++) if (row.get(i).trim().equalsIgnoreCase(a) || row.get(i).trim().equalsIgnoreCase(b)) return i; return -1; }
    private static String cell(List<String> row, int i) { return i < 0 || i >= row.size() ? "" : row.get(i); }
    public Map<String, Object> revokeStudentSessions(String owner, String id) {
        CspRecord r = owned(id, "STUDENT", owner); revokeSessions(owner, id);
        return studentView(r, true);
    }
    private void revokeSessions(String owner, String id) {
        for (CspRecord p : records.findByKindAndOwnerOrderByUpdatedAtDesc("PARTICIPATION", owner)) {
            Participation info = decode(p, Participation.class); if (info.studentId.equals(id)) { CspRecord locked = get(p.id, "PARTICIPATION", true); info = decode(locked, Participation.class); info.tokenHash = null; save(locked, info); }
        }
    }
    public byte[] rosterCsv(String owner) {
        StringBuilder csv = new StringBuilder("\uFEFF姓名,班级,学号\r\n");
        for (var s : students(owner)) csv.append(csvRow(s.get("name"), s.get("className"), s.get("studentNumber")));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String csvRow(Object... cells) { return Arrays.stream(cells).map(CspFiles::csvCell).reduce((a,b) -> a + "," + b).orElse("") + "\r\n"; }

    private void validateExam(Exam exam) {
        exam.name = text(exam.name, 80, "考场名称");
        if (!Set.of("LINUX", "WINDOWS").contains(exam.environment) || !Set.of("TEACHING", "EXAM").contains(exam.mode) || !Set.of("J", "S").contains(exam.group)) throw new IllegalArgumentException("考场模式无效");
        if (exam.durationMinutes < 1 || exam.durationMinutes > 600) throw new IllegalArgumentException("时长应为1至600分钟");
        exam.regionCode = text(exam.regionCode, 10, "地区代码").toUpperCase(Locale.ROOT);
        if (!exam.regionCode.matches("[A-Z]{2,10}")) throw new IllegalArgumentException("地区代码应为2至10位英文字母，例如广东为GD");
        boolean windows = windows(exam);
        if (exam.rootPath == null || exam.rootPath.isBlank()) exam.rootPath = windows ? "D:/" : "/home/noi/Desktop";
        exam.rootPath = CspFiles.path(exam.rootPath, windows);
        if (windows && !exam.rootPath.matches("/[A-Z](/.*)?")) throw new IllegalArgumentException("Windows答案路径应位于盘符内，如D:/answers");
        exam.folderPattern = text(exam.folderPattern, 150, "考生文件夹规则");
        if (exam.folderPattern.replace("{examNumber}", "").replace("{region}", "").replace("{name}", "").replace("{studentNumber}", "").matches(".*[{}].*")) throw new IllegalArgumentException("文件夹规则仅支持准考证号、地区代码、姓名和学号占位符");
        if (exam.problems == null || exam.problems.isEmpty() || exam.problems.size() > 10) throw new IllegalArgumentException("每场应有1至10题");
        Set<String> directories = new HashSet<>(), ids = new HashSet<>();
        for (Problem p : exam.problems) {
            if (p == null) throw new IllegalArgumentException("题目配置为空");
            if (p.id == null) p.id = UUID.randomUUID().toString();
            if (!p.id.matches("[a-f0-9-]{36}") || !ids.add(p.id)) throw new IllegalArgumentException("题目标识重复或无效");
            p.name = text(p.name, 80, "题目名称"); CspFiles.name(p.directory, windows); CspFiles.name(p.sourceName, windows);
            if (!p.sourceName.endsWith(".cpp") || !directories.add(windows ? p.directory.toLowerCase(Locale.ROOT) : p.directory)) throw new IllegalArgumentException("题目目录不得重复，源文件必须以.cpp结尾");
            if (!Set.of("STDIO", "FILE").contains(p.ioMode) || !Set.of("POINTS", "SUBTASKS").contains(p.scoring)) throw new IllegalArgumentException("评测模式无效");
            if (p.ioMode.equals("FILE")) { CspFiles.name(p.inputName, false); CspFiles.name(p.outputName, false); Set<String> reserved = Set.of("program","stdin","stdout","stderr","source.cpp"); if (p.inputName.equals(p.outputName) || reserved.contains(p.inputName) || reserved.contains(p.outputName)) throw new IllegalArgumentException("输入输出文件名冲突"); }
            if (p.timeLimitMs < 100 || p.timeLimitMs > 30000 || p.memoryMb < 16 || p.memoryMb > 1024 || p.maxScore == null || p.maxScore.signum() <= 0 || p.maxScore.compareTo(new BigDecimal("1000")) > 0) throw new IllegalArgumentException("题目限制或分值无效");
            if (p.statement == null) p.statement = "";
            if (p.statement.length() > 100000) throw new IllegalArgumentException("题面过长");
            p.cases = new ArrayList<>(); p.samples = new ArrayList<>(); p.dataVersion = null; p.dataConfirmed = false;
        }
        exam.startedAt = null; exam.deadline = null; exam.publishedBatch = null;
    }
    private static boolean windows(Exam exam) { return exam.environment.equals("WINDOWS"); }
    public Map<String, Object> createExam(String owner, CreateExam input) {
        if (input == null || input.exam == null) throw new IllegalArgumentException("请填写考场配置");
        validateExam(input.exam);
        if (input.students == null || input.students.isEmpty() || input.students.size() > 60) throw new IllegalArgumentException("请选择1至60名学生");
        CspRecord examRecord = create("EXAM", owner, null, input.exam); examRecord.state = "DRAFT"; save(examRecord, input.exam);
        Set<String> studentIds = new HashSet<>(), folders = new HashSet<>();
        for (Assignment assignment : input.students) {
            if (assignment == null) throw new IllegalArgumentException("学生分配信息为空");
            if (!studentIds.add(assignment.studentId())) throw new IllegalArgumentException("学生重复");
            Student student = decode(owned(assignment.studentId(), "STUDENT", owner), Student.class);
            Participation p = new Participation(); p.studentId = assignment.studentId();
            p.examNumber = assignment.examNumber() == null || assignment.examNumber().isBlank() ? input.exam.regionCode + "-" + input.exam.group + student.studentNumber : text(assignment.examNumber(), 100, "准考证号");
            p.folderName = assignment.folderName() == null || assignment.folderName().isBlank() ? input.exam.folderPattern.replace("{examNumber}", p.examNumber).replace("{region}", input.exam.regionCode).replace("{name}", student.name).replace("{studentNumber}", student.studentNumber) : assignment.folderName();
            CspFiles.name(p.folderName, windows(input.exam));
            if (!folders.add(windows(input.exam) ? p.folderName.toLowerCase(Locale.ROOT) : p.folderName)) throw new IllegalArgumentException("考生文件夹名称重复");
            CspFiles.seed(p.entries, input.exam.rootPath);
            CspFiles.seed(p.entries, windows(input.exam) ? "/C/Users/noi/Desktop" : "/home/noi/Desktop");
            create("PARTICIPATION", owner, examRecord.id, p);
        }
        return teacherExam(owner, examRecord.id);
    }
    private List<CspRecord> participants(String examId) {
        return records.findByKindAndParentIdOrderByUpdatedAtAsc("PARTICIPATION", examId).stream()
                .filter(r -> decode(r, Participation.class).active).toList();
    }
    public Map<String,Object> editExam(String owner, String id, CreateExam input) {
        CspRecord r = owned(id, "EXAM", owner); finishIfExpired(r);
        if (r.state.equals("OPEN")) throw new IllegalStateException("请先结束收卷，再编辑考场");
        if (input == null || input.exam == null) throw new IllegalArgumentException("请填写考场配置");
        Exam previous = ensureJoinCode(r), next = input.exam;
        validateExam(next);
        if (r.state.equals("CLOSED") && (!previous.environment.equals(next.environment) || !previous.rootPath.equals(next.rootPath)
                || !previous.folderPattern.equals(next.folderPattern) || !previous.regionCode.equals(next.regionCode) || !previous.group.equals(next.group)))
            throw new IllegalStateException("请先准备新一轮，再修改文件环境或考生目录规则");
        for (Problem p : next.problems) previous.problems.stream().filter(old -> old.id.equals(p.id)).findFirst().ifPresent(old -> {
            p.cases = old.cases; p.samples = old.samples; p.dataVersion = old.dataVersion; p.scoring = old.scoring;
            p.dataConfirmed = old.dataConfirmed && old.maxScore.compareTo(p.maxScore) == 0;
            if (old.maxScore.compareTo(p.maxScore) != 0 && !p.cases.isEmpty()) allocateScores(p);
        });
        if (r.state.equals("CLOSED") && !previous.problems.stream().map(p->p.id).collect(java.util.stream.Collectors.toSet()).equals(next.problems.stream().map(p->p.id).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("请先准备新一轮，再增删题目");
        next.joinCode = previous.joinCode; next.round = previous.round; next.startedAt = previous.startedAt; next.deadline = previous.deadline;
        next.publishedBatch = previous.publishedBatch;
        if (r.state.equals("DRAFT")) {
            if (input.students == null || input.students.isEmpty() || input.students.size() > 60) throw new IllegalArgumentException("请选择1至60名学生");
            Set<String> ids = new HashSet<>(), folders = new HashSet<>();
            List<CspRecord> existing = records.findByKindAndParentIdOrderByUpdatedAtAsc("PARTICIPATION", id);
            for (Assignment a : input.students) {
                if (a == null || !ids.add(a.studentId())) throw new IllegalArgumentException("学生重复或无效");
                Student student = decode(owned(a.studentId(), "STUDENT", owner), Student.class);
                CspRecord pr = existing.stream().filter(member -> decode(member, Participation.class).studentId.equals(a.studentId())).findFirst().orElse(null);
                Participation member = pr == null ? new Participation() : decode(get(pr.id,"PARTICIPATION",true), Participation.class);
                member.studentId = a.studentId(); member.active = true;
                member.examNumber = a.examNumber() == null || a.examNumber().isBlank() ? next.regionCode + "-" + next.group + student.studentNumber : text(a.examNumber(),100,"准考证号");
                member.folderName = a.folderName() == null || a.folderName().isBlank() ? next.folderPattern.replace("{examNumber}",member.examNumber).replace("{region}",next.regionCode).replace("{name}",student.name).replace("{studentNumber}",student.studentNumber) : a.folderName();
                CspFiles.name(member.folderName,windows(next));
                if (!folders.add(windows(next) ? member.folderName.toLowerCase(Locale.ROOT) : member.folderName)) throw new IllegalArgumentException("考生文件夹名称重复");
                member.entries = new LinkedHashMap<>(); seedWorkspace(member,next);
                if (pr == null) create("PARTICIPATION",owner,id,member); else save(pr,member);
            }
            for (CspRecord pr : existing) if (!ids.contains(decode(pr,Participation.class).studentId)) {
                Participation member = decode(get(pr.id,"PARTICIPATION",true),Participation.class); member.active=false; member.tokenHash=null; save(pr,member);
            }
        }
        save(r,next); return teacherExam(owner,id);
    }
    private void seedWorkspace(Participation p, Exam e) {
        CspFiles.seed(p.entries,e.rootPath);
        CspFiles.seed(p.entries,windows(e) ? "/C/Users/noi/Desktop" : "/home/noi/Desktop");
    }
    /** Preserve submissions/tasks; only the new round's workspace is reset. */
    public Map<String,Object> restart(String owner, String id) {
        CspRecord r = owned(id,"EXAM",owner);
        if (!r.state.equals("CLOSED")) throw new IllegalStateException("请先结束本轮收卷，再准备新一轮");
        Exam e = ensureJoinCode(r);
        if (e.publishedBatch != null) {CspRecord br=get(e.publishedBatch,"BATCH",true);Batch b=decode(br,Batch.class);b.published=true;save(br,b);}
        e.round++; e.startedAt=null; e.deadline=null; e.publishedBatch=null;
        for (CspRecord pr : participants(id)) {
            Participation p = decode(get(pr.id,"PARTICIPATION",true),Participation.class);
            p.entries = new LinkedHashMap<>(); seedWorkspace(p,e); p.latestSubmission=null; p.lastSeen=null; save(pr,p);
        }
        r.state="DRAFT"; save(r,e); return teacherExam(owner,id);
    }
    public List<Map<String, Object>> exams(String owner) {
        return records.findByKindAndOwnerOrderByUpdatedAtDesc("EXAM", owner).stream().map(r -> { Exam e = ensureJoinCode(get(r.id,"EXAM",true)); return Map.<String,Object>of("id", r.id, "joinCode", e.joinCode, "name", e.name, "state", r.state, "mode", e.mode, "environment", e.environment); }).toList();
    }
    public Map<String, Object> teacherExam(String owner, String id) {
        CspRecord r = owned(id, "EXAM", owner); finishIfExpired(r); Exam exam = ensureJoinCode(r);
        List<Map<String, Object>> participants = new ArrayList<>();
        for (CspRecord pr : participants(id)) {
            Participation p = decode(pr, Participation.class); Map<String,Object> v = new LinkedHashMap<>(studentView(get(p.studentId, "STUDENT", false), false));
            v.put("participationId", pr.id); v.put("examNumber", p.examNumber); v.put("folderName", p.folderName); v.put("joined", p.lastSeen != null);
            v.put("lastSeen", p.lastSeen == null ? "" : p.lastSeen); v.put("latestSubmission", p.latestSubmission == null ? "" : p.latestSubmission);
            v.put("fileCount", p.entries.values().stream().filter(entry -> !entry.directory).count()); participants.add(v);
        }
        Map<String,Object> view = new LinkedHashMap<>(); view.put("id", id); view.put("state", r.state); view.put("exam", exam); view.put("students", participants); view.put("serverTime", now());
        view.put("batches", records.findByKindAndParentIdOrderByUpdatedAtAsc("BATCH", id).stream().map(this::batchSummary).toList()); return view;
    }
    public Map<String, Object> start(String owner, String id) {
        CspRecord r = owned(id, "EXAM", owner); if (!r.state.equals("DRAFT")) throw new IllegalStateException("考场已开始或结束");
        Exam e = decode(r, Exam.class); e.startedAt = now(); e.deadline = now().plus(Duration.ofMinutes(e.durationMinutes)); r.state = "OPEN"; save(r, e);
        return teacherExam(owner, id);
    }
    public Map<String,Object> updateAdmissionNumbers(String owner, String id) {
        CspRecord examRecord=owned(id,"EXAM",owner);
        if (!examRecord.state.equals("DRAFT")) throw new IllegalStateException("只能在开考前更新考号和目录");
        Exam exam=decode(examRecord,Exam.class);Set<String> folders=new HashSet<>();
        for (CspRecord member : participants(id)) {
            CspRecord locked=get(member.id,"PARTICIPATION",true);Participation p=decode(locked,Participation.class);
            Student student=decode(get(p.studentId,"STUDENT",false),Student.class);
            p.examNumber=exam.regionCode+"-"+exam.group+student.studentNumber;
            p.folderName=exam.folderPattern.replace("{examNumber}",p.examNumber).replace("{region}",exam.regionCode).replace("{name}",student.name).replace("{studentNumber}",student.studentNumber);
            CspFiles.name(p.folderName,windows(exam));
            if (!folders.add(windows(exam)?p.folderName.toLowerCase(Locale.ROOT):p.folderName)) throw new IllegalArgumentException("考生文件夹名称重复");
            save(locked,p);
        }
        return teacherExam(owner,id);
    }
    public Map<String, Object> close(String owner, String id) {
        CspRecord r = owned(id, "EXAM", owner); if (!r.state.equals("OPEN")) throw new IllegalStateException("考场尚未开始或已经结束");
        closeRecord(r); return teacherExam(owner, id);
    }
    private void finishIfExpired(CspRecord record) {
        if (record.state.equals("OPEN") && !now().isBefore(decode(record, Exam.class).deadline)) closeRecord(record);
    }
    private void closeRecord(CspRecord record) {
        for (CspRecord pr : records.findByKindAndParentIdOrderByUpdatedAtAsc("PARTICIPATION", record.id)) {
            CspRecord locked = get(pr.id, "PARTICIPATION", true); Participation p = decode(locked, Participation.class);
            if (p.latestSubmission == null) snapshot(locked, p, true);
        }
        record.state = "CLOSED"; save(record, decode(record, Exam.class));
    }
    @Scheduled(fixedDelay = 10000)
    public void expireExams() {
        for (CspRecord r : records.findByKindAndStateOrderByUpdatedAtAsc("EXAM", "OPEN")) finishIfExpired(get(r.id, "EXAM", true));
    }
    public Map<String,Object> join(String examId, Join request) {
        if (request == null) throw new IllegalArgumentException("请填写姓名和学号");
        CspRecord r = resolveExam(examId); finishIfExpired(r); Exam exam = ensureJoinCode(r);
        String name = text(request.name(), 50, "姓名"), code = text(request.code(), 50, "学号");
        for (CspRecord pr : participants(r.id)) {
            Participation p = decode(pr, Participation.class); Student s = decode(get(p.studentId, "STUDENT", false), Student.class);
            if (s.enabled && s.name.equals(name) && equal(s.studentNumber, code)) {
                CspRecord locked = get(pr.id, "PARTICIPATION", true); p = decode(locked, Participation.class);
                String token = randomToken(); p.tokenHash = hash(token); p.lastSeen = now(); save(locked, p);
                return Map.of("participationId", pr.id, "token", token, "examCode", exam.joinCode);
            }
        }
        throw new AccessDeniedException("姓名或学号不正确，或未加入此考场");
    }
    private CspRecord studentAccess(String id, String token) {
        CspRecord untrusted = get(id, "PARTICIPATION", false); CspRecord exam = get(untrusted.parentId, "EXAM", true);
        finishIfExpired(exam); CspRecord record = get(id, "PARTICIPATION", true); Participation p = decode(record, Participation.class);
        if (!p.active || token == null || token.length() > 100 || !equal(p.tokenHash, hash(token))) throw new AccessDeniedException("请重新输入姓名和学号");
        if (!decode(get(p.studentId,"STUDENT",false),Student.class).enabled) throw new AccessDeniedException("学生档案已停用，请联系老师");
        return record;
    }
    private void writable(CspRecord participation, Long revision) {
        if (!get(participation.parentId, "EXAM", false).state.equals("OPEN")) throw new IllegalStateException("考场未开始或已截止，文件不能修改");
        if (revision != null && participation.version != revision) throw new IllegalStateException("文件已在其他窗口发生变化，请刷新后重试");
    }
    public Map<String,Object> workspace(String id, String token) {
        CspRecord r = studentAccess(id, token); Participation p = decode(r, Participation.class); CspRecord er = get(r.parentId, "EXAM", false); Exam e = decode(er, Exam.class);
        if (p.lastSeen == null && er.state.equals("OPEN")) {p.lastSeen=now();save(r,p);}
        Map<String,Object> view = new LinkedHashMap<>(); view.put("id", id); view.put("examId", er.id); view.put("state", er.state); view.put("revision", r.version);
        view.put("examCode", e.joinCode); view.put("round",e.round);
        view.put("student", studentView(get(p.studentId, "STUDENT", false), false)); view.put("examNumber", p.examNumber); view.put("folderName", p.folderName);
        view.put("name", e.name); view.put("environment", e.environment); view.put("mode", e.mode); view.put("rootPath", e.rootPath); view.put("deadline", e.deadline == null ? "" : e.deadline);
        view.put("serverTime", now()); view.put("entries", p.entries); view.put("latestSubmission", p.latestSubmission == null ? "" : p.latestSubmission);
        view.put("problems", e.problems.stream().map(this::publicProblem).toList());
        if (e.publishedBatch != null) view.put("results", batchView(e.publishedBatch, p.studentId));
        return view;
    }
    public void leave(String id, String token) {
        CspRecord record=studentAccess(id,token); Participation p=decode(record,Participation.class); p.tokenHash=null; save(record,p);
    }
    private Map<String,Object> publicProblem(Problem p) {
        Map<String,Object> v = new LinkedHashMap<>(); v.put("id", p.id); v.put("name", p.name); v.put("directory", p.directory); v.put("sourceName", p.sourceName);
        v.put("ioMode", p.ioMode); v.put("inputName", p.inputName == null ? "" : p.inputName); v.put("outputName", p.outputName == null ? "" : p.outputName);
        v.put("timeLimitMs", p.timeLimitMs); v.put("memoryMb", p.memoryMb); v.put("maxScore", p.maxScore); v.put("statement", p.statement);
        v.put("samples", p.samples.stream().map(c -> Map.of("id", c.id, "input", preview(files.get(c.inputBlob)), "answer", preview(files.get(c.answerBlob)))).toList()); return v;
    }
    private static String preview(byte[] bytes) { return new String(bytes, 0, Math.min(bytes.length, 128 * 1024), StandardCharsets.UTF_8); }
    public Map<String,Object> fileOperation(String id, String token, FileOperation op) {
        if (op == null) throw new IllegalArgumentException("请选择文件操作");
        CspRecord r = studentAccess(id, token); writable(r, op.revision()); Participation p = decode(r, Participation.class); Exam e = decode(get(r.parentId, "EXAM", false), Exam.class);
        CspFiles.operate(p.entries, op.action(), op.path(), op.target(), windows(e)); save(r, p); return workspace(id, token);
    }
    public Map<String,Object> upload(String id, String token, String target, List<String> names, List<byte[]> content, boolean replace, Long revision) {
        CspRecord r = studentAccess(id, token); writable(r, revision); Participation p = decode(r, Participation.class); Exam e = decode(get(r.parentId, "EXAM", false), Exam.class);
        boolean windows = windows(e); String folder = CspFiles.find(p.entries, CspFiles.path(target, windows), windows);
        if (folder == null || windows && folder.equals("/") || !p.entries.get(folder).directory || names.size() != content.size() || names.isEmpty() || names.size() > 200) throw new IllegalArgumentException("上传目标或文件数量无效");
        Set<String> requested = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            byte[] bytes = content.get(i); if (bytes.length > CspFiles.MAX_FILE) throw new IllegalArgumentException("单个学生文件不能超过2MB");
            String path = CspFiles.path((folder.equals("/") ? "" : folder) + "/" + names.get(i), windows);
            if (!requested.add(windows ? path.toLowerCase(Locale.ROOT) : path)) throw new IllegalArgumentException("上传包含重复文件名");
            String parent = CspFiles.parent(path), current = "";
            for (String part : parent.substring(1).split("/")) {
                if (part.isEmpty()) continue; current += "/" + part; String existing = CspFiles.find(p.entries, current, windows);
                if (existing != null && !p.entries.get(existing).directory) throw new IllegalArgumentException("上级路径已有同名文件");
                if (existing == null) p.entries.put(CspFiles.actualParent(p.entries, current, windows), new Entry(true));
            }
            path = CspFiles.actualParent(p.entries, path, windows); String existing = CspFiles.find(p.entries, path, windows);
            if (existing != null && (p.entries.get(existing).directory || !replace)) throw new IllegalArgumentException("文件已存在，请选择覆盖上传");
            Entry entry = new Entry(false); entry.bytes = bytes.length; entry.blob = files.put(bytes); entry.modifiedAt = now();
            p.entries.put(existing == null ? path : existing, entry);
        }
        CspFiles.quota(p.entries); save(r, p); return workspace(id, token);
    }
    public byte[] readFile(String id, String token, String path) {
        CspRecord r = studentAccess(id, token); Participation p = decode(r, Participation.class); Exam e = decode(get(r.parentId, "EXAM", false), Exam.class);
        String key = CspFiles.find(p.entries, CspFiles.path(path, windows(e)), windows(e));
        if (key == null || p.entries.get(key).directory) throw new NoSuchElementException("文件不存在"); return files.get(p.entries.get(key).blob);
    }
    private CspRecord snapshot(CspRecord r, Participation p, boolean automatic) {
        Submission s = new Submission(); s.round=decode(get(r.parentId,"EXAM",false),Exam.class).round; s.participationId = r.id; s.studentId = p.studentId; s.submittedAt = now(); s.automatic = automatic; s.entries = new LinkedHashMap<>(p.entries);
        CspRecord submission = create("SUBMISSION", r.owner, r.id, s); p.latestSubmission = submission.id; save(r, p); return submission;
    }
    public Map<String,Object> submit(String id, String token) {
        CspRecord r = studentAccess(id, token); writable(r, null); Participation p = decode(r, Participation.class); snapshot(r, p, false); return workspace(id, token);
    }
    private static String sourcePath(Exam e, Participation p, Problem problem) { return (e.rootPath.equals("/") ? "" : e.rootPath) + "/" + p.folderName + "/" + problem.directory + "/" + problem.sourceName; }
    private static List<String> directoryIssues(Map<String,Entry> entries, String expected, boolean windows) {
        Entry exact = entries.get(expected); if (exact != null && !exact.directory) return List.of();
        String similar = CspFiles.find(entries, expected, windows);
        if (similar != null && !similar.equals(expected)) return List.of("目录或文件名大小写不符合要求：" + similar + "；规定路径：" + expected);
        List<String> candidates = entries.keySet().stream().filter(p -> CspFiles.base(p).equalsIgnoreCase(CspFiles.base(expected)) && !entries.get(p).directory).toList();
        return List.of("未找到规定位置的源文件：" + expected + (candidates.isEmpty() ? "" : "；发现其他位置：" + String.join("、", candidates)));
    }
    public Map<String,Object> check(String id, String token) {
        CspRecord r = studentAccess(id, token); Participation p = decode(r, Participation.class); CspRecord er = get(r.parentId, "EXAM", false); Exam e = decode(er, Exam.class);
        if (e.mode.equals("EXAM") && !er.state.equals("CLOSED")) throw new AccessDeniedException("模拟考试结束后才开放目录检查");
        return Map.of("problems", e.problems.stream().map(problem -> Map.of("name", problem.name, "expected", sourcePath(e, p, problem), "issues", directoryIssues(p.entries, sourcePath(e,p,problem), windows(e)))).toList());
    }
    public Map<String,Object> submissions(String owner, String examId, String participationId) {
        owned(examId, "EXAM", owner); CspRecord p = owned(participationId, "PARTICIPATION", owner);
        if (!examId.equals(p.parentId)) throw new AccessDeniedException("学生不属于此考场");
        return Map.of("entries", decode(p, Participation.class).entries, "submissions", records.findByKindAndParentIdOrderByUpdatedAtAsc("SUBMISSION", participationId).stream().map(r -> Map.of("id", r.id, "submission", decode(r, Submission.class))).toList());
    }
    public byte[] teacherReadFile(String owner, String examId, String participationId, String submissionId, String path) {
        owned(examId,"EXAM",owner); CspRecord pr = owned(participationId,"PARTICIPATION",owner);
        if (!examId.equals(pr.parentId)) throw new AccessDeniedException("学生不属于此考场");
        Map<String,Entry> entries;
        if (submissionId == null || submissionId.isBlank()) entries = decode(pr,Participation.class).entries;
        else { CspRecord sr = owned(submissionId,"SUBMISSION",owner); if (!sr.parentId.equals(participationId)) throw new AccessDeniedException("交卷不属于此学生"); entries = decode(sr,Submission.class).entries; }
        Entry entry = entries.get(path); if (entry == null || entry.directory) throw new NoSuchElementException("文件不存在"); return files.get(entry.blob);
    }
    public List<Map<String,Object>> history(String id, String token) {
        CspRecord pr = studentAccess(id,token); String studentId = decode(pr,Participation.class).studentId;
        List<Map<String,Object>> result = new ArrayList<>();
        for (CspRecord r : records.findByKindAndOwnerOrderByUpdatedAtDesc("PARTICIPATION",pr.owner)) {
            if (!decode(r,Participation.class).studentId.equals(studentId)) continue;
            CspRecord er = get(r.parentId,"EXAM",false); Exam e = decode(er,Exam.class);
            Map<String,Object> item = new LinkedHashMap<>(); item.put("examId",er.id); item.put("name",e.name); item.put("state",er.state); item.put("startedAt",e.startedAt == null ? "" : e.startedAt);
            item.put("round",e.round);
            if (e.publishedBatch != null) item.put("results",batchView(e.publishedBatch,studentId)); result.add(item);
            Map<Integer,CspRecord> past=new TreeMap<>(Comparator.reverseOrder());
            for(CspRecord br:records.findByKindAndParentIdOrderByUpdatedAtAsc("BATCH",er.id)) {
                Batch b=decode(br,Batch.class);if(b.published && !b.review && b.round<e.round) past.put(b.round,br);
            }
            for(var prior:past.entrySet()) {Map<String,Object> archived=new LinkedHashMap<>(); archived.put("examId",er.id);archived.put("name",e.name);archived.put("round",prior.getKey());archived.put("state","CLOSED");archived.put("results",batchView(prior.getValue().id,studentId));result.add(archived);}
        }
        return result;
    }

    private Problem problem(Exam e, String id) { return e.problems.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("题目不存在")); }
    public Map<String,Object> uploadData(String owner, String examId, String problemId, byte[] zip, boolean samples) {
        return uploadDataZip(owner,examId,problemId,new ByteArrayInputStream(zip),zip.length,samples);
    }
    public Map<String,Object> uploadDataZip(String owner,String examId,String problemId,InputStream input,long size,boolean samples) {
        problem(decode(owned(examId,"EXAM",owner),Exam.class),problemId);
        try (CspFiles.StagedData staged=files.stageZip(input,size)) { return importData(owner,examId,problemId,staged,samples); }
    }
    public Map<String,Object> uploadDataFiles(String owner, String examId, String problemId, List<String> names, List<byte[]> contents, boolean samples) {
        if (names==null || contents==null || names.size()!=contents.size()) throw new IllegalArgumentException("请选择配对的.in与.out/.ans文件");
        List<CspFiles.DataUpload> uploads=new ArrayList<>();
        for (int i=0;i<names.size();i++) { byte[] content=contents.get(i); uploads.add(new CspFiles.DataUpload(names.get(i),content.length,()->new ByteArrayInputStream(content))); }
        return uploadDataStreams(owner,examId,problemId,uploads,samples);
    }
    public Map<String,Object> uploadDataStreams(String owner,String examId,String problemId,List<CspFiles.DataUpload> uploads,boolean samples) {
        problem(decode(owned(examId,"EXAM",owner),Exam.class),problemId);
        try (CspFiles.StagedData staged=files.stageFiles(uploads)) { return importData(owner,examId,problemId,staged,samples); }
    }
    private Map<String,Object> importData(String owner,String examId,String problemId,CspFiles.StagedData staged,boolean samples) {
        CspRecord r = owned(examId, "EXAM", owner); Exam e = decode(r, Exam.class); Problem problem = problem(e, problemId);
        var unpacked=staged.entries;
        List<String> inputs = unpacked.keySet().stream().filter(n -> n.endsWith(".in")).sorted().toList();
        if (inputs.isEmpty() || inputs.size() > 1000 || samples && inputs.size() > 10) throw new IllegalArgumentException("数据包应包含1至1000组数据，样例最多10组");
        // Validate all pairs before moving staged files into persistent blob storage.
        for (String input : inputs) {
            String stem=input.substring(0,input.length()-3), out=stem+".out", ans=stem+".ans";
            if (unpacked.containsKey(out)==unpacked.containsKey(ans)) throw new IllegalArgumentException("输入必须有唯一配对答案："+input);
            if (samples) try {
                if (java.nio.file.Files.size(unpacked.get(input))>128*1024 || java.nio.file.Files.size(unpacked.get(unpacked.containsKey(out)?out:ans))>128*1024) throw new IllegalArgumentException("样例文件不能超过128KB");
            } catch (IOException ex) { throw new IllegalStateException("数据文件无法读取",ex); }
        }
        for (String name : unpacked.keySet()) if ((name.endsWith(".out") || name.endsWith(".ans")) && !unpacked.containsKey(name.substring(0, name.lastIndexOf('.')) + ".in")) throw new IllegalArgumentException("答案没有配对输入：" + name);
        List<TestCase> cases = new ArrayList<>();
        for (String input : inputs) {
            String stem=input.substring(0,input.length()-3),out=stem+".out";
            TestCase c=new TestCase(); c.id=stem; c.inputBlob=files.put(unpacked.get(input)); c.answerBlob=files.put(unpacked.get(unpacked.containsKey(out)?out:stem+".ans")); cases.add(c);
        }
        if (samples) problem.samples = cases;
        else { problem.cases=cases; allocateScores(problem); problem.dataVersion=UUID.randomUUID().toString(); problem.dataConfirmed=false; }
        save(r,e); return teacherExam(owner,examId);
    }
    private void allocateScores(Problem p) {
        BigDecimal each=p.maxScore.divide(BigDecimal.valueOf(p.cases.size()),4,RoundingMode.DOWN),used=BigDecimal.ZERO;
        for(int i=0;i<p.cases.size();i++){p.cases.get(i).score=i==p.cases.size()-1?p.maxScore.subtract(used):each;used=used.add(p.cases.get(i).score);}
        p.scoring="POINTS";
    }
    public List<Map<String,Object>> dataFiles(String owner,String examId,String problemId,boolean samples) {
        Exam e=decode(owned(examId,"EXAM",owner),Exam.class); Problem p=problem(e,problemId);
        return (samples?p.samples:p.cases).stream().map(c -> Map.<String,Object>of("id",c.id,"inputBytes",files.size(c.inputBlob),"answerBytes",files.size(c.answerBlob),"previewLimit",128*1024)).toList();
    }
    public byte[] readData(String owner,String examId,String problemId,String caseId,String kind,boolean samples,boolean download) {
        try { return dataResource(owner,examId,problemId,caseId,kind,samples,download).getContentAsByteArray(); }
        catch (IOException ex) { throw new IllegalStateException("数据文件无法读取",ex); }
    }
    public org.springframework.core.io.Resource dataResource(String owner,String examId,String problemId,String caseId,String kind,boolean samples,boolean download) {
        Exam e=decode(owned(examId,"EXAM",owner),Exam.class);Problem p=problem(e,problemId);
        TestCase c=(samples?p.samples:p.cases).stream().filter(tc->tc.id.equals(caseId)).findFirst().orElseThrow(()->new NoSuchElementException("测试点不存在"));
        if (!Set.of("input","answer").contains(kind)) throw new IllegalArgumentException("请选择输入或答案");
        String blob=kind.equals("input")?c.inputBlob:c.answerBlob;
        if (!download && files.size(blob)>128*1024) throw new IllegalArgumentException("数据较大，请下载查看");
        return new org.springframework.core.io.FileSystemResource(files.blobPath(blob));
    }
    public Map<String,Object> configureData(String owner, String examId, String problemId, DataConfig config) {
        if (config == null) throw new IllegalArgumentException("请填写数据计分配置");
        CspRecord r = owned(examId, "EXAM", owner); Exam e = decode(r, Exam.class); Problem p = problem(e, problemId);
        if (config.dataVersion() == null || !config.dataVersion().equals(p.dataVersion)) throw new IllegalStateException("数据已更换，请刷新后配置");
        if (!Set.of("POINTS", "SUBTASKS").contains(config.scoring()) || config.cases() == null || config.cases().size() != p.cases.size()) throw new IllegalArgumentException("计分配置不完整");
        Set<String> configured = new HashSet<>(); Map<String,BigDecimal> groups = new LinkedHashMap<>(); BigDecimal total = BigDecimal.ZERO;
        for (CaseConfig cc : config.cases()) {
            if (cc == null) throw new IllegalArgumentException("测试点配置为空");
            if (!configured.add(cc.id()) || cc.score() == null || cc.score().signum() < 0 || cc.score().scale() > 4) throw new IllegalArgumentException("测试点或分值无效");
            TestCase c = p.cases.stream().filter(tc -> tc.id.equals(cc.id())).findFirst().orElseThrow(() -> new IllegalArgumentException("测试点不存在"));
            c.score = cc.score(); c.subtask = optional(cc.subtask(), 50);
            if (config.scoring().equals("SUBTASKS")) {
                if (c.subtask.isEmpty()) throw new IllegalArgumentException("子任务模式必须给每个测试点分组");
                BigDecimal prior = groups.putIfAbsent(c.subtask, c.score); if (prior != null && prior.compareTo(c.score) != 0) throw new IllegalArgumentException("同一子任务的分值必须一致");
            } else total = total.add(c.score);
        }
        if (config.scoring().equals("SUBTASKS")) total = groups.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(p.maxScore) != 0) throw new IllegalArgumentException("总分值必须等于题目满分 " + p.maxScore);
        p.scoring = config.scoring(); p.dataVersion = UUID.randomUUID().toString(); p.dataConfirmed = true; save(r, e); return teacherExam(owner, examId);
    }
    public Map<String,Object> grade(String owner, String id, GradeRequest request) {
        CspRecord r = owned(id, "EXAM", owner); finishIfExpired(r);
        if (!r.state.equals("CLOSED")) throw new IllegalStateException("结束收卷后才能正式评测");
        Exam e = decode(r, Exam.class); for (Problem p : e.problems) if (p.cases.isEmpty() || !p.dataConfirmed) throw new IllegalArgumentException("请先上传并确认全部题目的数据和分值");
        Batch batch = new Batch(); batch.round = e.round; batch.createdAt = now(); batch.review = request != null && request.reviewPaths() != null && !request.reviewPaths().isEmpty();
        CspRecord br = create("BATCH", owner, id, batch);
        for (CspRecord pr : participants(id)) {
            if (batch.review && !request.reviewPaths().containsKey(pr.id)) continue;
            Participation p = decode(pr, Participation.class); CspRecord sr = get(p.latestSubmission, "SUBMISSION", false); Submission s = decode(sr, Submission.class);
            for (Problem problem : e.problems) {
                if (batch.review && !request.reviewPaths().get(pr.id).containsKey(problem.id)) continue;
                Task t = new Task(); t.batchId = br.id; t.participationId = pr.id; t.studentId = p.studentId; t.submissionId = sr.id; t.problemId = problem.id;
                t.problem = problem; String expected = sourcePath(e,p,problem); t.sourcePath = expected; t.directoryIssues = directoryIssues(s.entries, expected, windows(e));
                if (batch.review) t.sourcePath = request.reviewPaths().getOrDefault(pr.id, Map.of()).getOrDefault(problem.id, expected);
                Entry source = s.entries.get(t.sourcePath);
                if (source != null && !source.directory && (batch.review || t.directoryIssues.isEmpty())) t.sourceBlob = source.blob;
                CspRecord tr = create("TASK", owner, br.id, t); tr.state = "QUEUED";
                if (t.sourceBlob == null) { tr.state = "DONE"; t.result = new Result(); t.result.verdict = "DIRECTORY_ERROR"; t.result.message = String.join("；", t.directoryIssues); }
                else if (source.bytes > 100 * 1024) { tr.state = "DONE"; t.result = new Result(); t.result.verdict = "SOURCE_TOO_LARGE"; t.result.message = "源码超过100KiB"; }
                save(tr, t); batch.taskIds.add(tr.id);
            }
        }
        if (batch.taskIds.isEmpty()) throw new IllegalArgumentException("没有选择有效的复盘题目");
        save(br, batch); return batchView(br.id, null);
    }
    private Map<String,Object> batchSummary(CspRecord br) {
        Batch b = decode(br, Batch.class); long done = records.findByKindAndParentIdOrderByUpdatedAtAsc("TASK", br.id).stream().filter(t -> t.state.equals("DONE")).count();
        return Map.of("id", br.id, "review", b.review, "createdAt", b.createdAt, "total", b.taskIds.size(), "done", done, "round", b.round, "published", b.published);
    }
    private Map<String,Object> batchView(String id, String selfStudent) {
        CspRecord br = get(id, "BATCH", false); Batch batch = decode(br, Batch.class);
        List<Map<String,Object>> tasks = new ArrayList<>(); Map<String,BigDecimal> totals = new LinkedHashMap<>(); Map<String,Boolean> completed = new LinkedHashMap<>();
        for (CspRecord tr : records.findByKindAndParentIdOrderByUpdatedAtAsc("TASK", id)) {
            Task t = decode(tr, Task.class); totals.merge(t.studentId, tr.state.equals("DONE") && t.result != null ? t.result.score : BigDecimal.ZERO, BigDecimal::add);
            completed.merge(t.studentId, tr.state.equals("DONE"), (a,b) -> a && b);
            if (selfStudent == null || t.studentId.equals(selfStudent)) {
                Map<String,Object> v = new LinkedHashMap<>(); v.put("id", tr.id); v.put("state", tr.state); v.put("participationId", t.participationId); v.put("studentId", t.studentId);
                v.put("problemId", t.problemId); v.put("problemName", t.problem.name); v.put("sourcePath", t.sourcePath); v.put("submissionId", t.submissionId); v.put("dataVersion", t.problem.dataVersion);
                v.put("directoryIssues", t.directoryIssues); v.put("result", t.result); tasks.add(v);
            }
        }
        List<Map<String,Object>> leaderboard = new ArrayList<>(); int position = 0, rank = 0; BigDecimal previous = null;
        for (var item : totals.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList()) {
            Map<String,Object> s = new LinkedHashMap<>(studentView(get(item.getKey(), "STUDENT", false), false));
            if (completed.get(item.getKey())) { position++; if (previous == null || previous.compareTo(item.getValue()) != 0) rank = position; previous = item.getValue(); s.put("rank",rank); s.put("score",item.getValue()); }
            else s.put("pending",true);
            leaderboard.add(s);
        }
        Map<String,Object> view = new LinkedHashMap<>(batchSummary(br)); view.put("tasks", tasks); view.put("leaderboard", leaderboard); return view;
    }
    public Map<String,Object> teacherBatch(String owner, String examId, String batchId) {
        owned(examId, "EXAM", owner); CspRecord br = owned(batchId, "BATCH", owner);
        if (!examId.equals(br.parentId)) throw new AccessDeniedException("评测不属于此考场"); return batchView(batchId, null);
    }
    public Map<String,Object> publish(String owner, String examId, String batchId) {
        CspRecord r = owned(examId, "EXAM", owner); CspRecord br = owned(batchId, "BATCH", owner);
        if (!br.parentId.equals(examId) || decode(br, Batch.class).round != decode(r,Exam.class).round || decode(br, Batch.class).review) throw new IllegalArgumentException("复盘或历史轮次的评测不能作为本轮正式榜单发布");
        if (records.findByKindAndParentIdOrderByUpdatedAtAsc("TASK", batchId).stream().anyMatch(t -> !t.state.equals("DONE"))) throw new IllegalStateException("评测尚未完成，系统故障任务需重试");
        Batch published = decode(br,Batch.class); published.published = true; save(br,published);
        Exam e = decode(r, Exam.class); e.publishedBatch = batchId; save(r, e); return teacherExam(owner, examId);
    }
    public byte[] scoresCsv(String owner, String examId, String batchId) {
        Map<String,Object> view = teacherBatch(owner, examId, batchId);
        List<Problem> batchProblems=records.findByKindAndParentIdOrderByUpdatedAtAsc("TASK",batchId).stream().map(t->decode(t,Task.class).problem).collect(java.util.stream.Collectors.toMap(p->p.id,p->p,(a,b)->a,LinkedHashMap::new)).values().stream().toList();
        if (!view.get("done").equals(Long.valueOf(((Number)view.get("total")).longValue()))) throw new IllegalStateException("评测尚未完成，不能导出正式成绩");
        StringBuilder csv = new StringBuilder("\uFEFF排名,姓名,班级,学号,总分"); for (Problem p : batchProblems) csv.append(',').append(CspFiles.csvCell(p.name)); csv.append("\r\n");
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>) view.get("leaderboard");
        for (var row : rows) {
            List<Object> cells = new ArrayList<>(List.of(row.get("rank"), row.get("name"), row.get("className"), row.get("studentNumber"), row.get("score")));
            for (Problem p : batchProblems) {
                Task task = records.findByKindAndParentIdOrderByUpdatedAtAsc("TASK", batchId).stream().map(t -> decode(t, Task.class)).filter(t -> t.studentId.equals(row.get("id")) && t.problemId.equals(p.id)).findFirst().orElseThrow();
                cells.add(task.result == null ? "未完成" : task.result.score);
            }
            csv.append(csvRow(cells.toArray()));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    public void exportSources(String owner, String id, OutputStream output) throws IOException {
        CspRecord er = owned(id, "EXAM", owner); finishIfExpired(er);
        ZipOutputStream zip = new ZipOutputStream(output);
        for (CspRecord pr : participants(id)) {
            Participation p = decode(pr, Participation.class); Map<String,Entry> entries = p.latestSubmission == null ? p.entries : decode(get(p.latestSubmission, "SUBMISSION", false), Submission.class).entries;
            Map<String,Object> manifest = new LinkedHashMap<>(); manifest.put("student", studentView(get(p.studentId,"STUDENT",false),false)); manifest.put("submissionId", p.latestSubmission); manifest.put("entries", entries);
            zip.putNextEntry(new ZipEntry(pr.id + "/manifest.json")); zip.write(encode(manifest).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (var file : entries.entrySet()) if (!file.getValue().directory) {
                zip.putNextEntry(new ZipEntry(pr.id + "/files" + file.getKey())); zip.write(files.get(file.getValue().blob)); zip.closeEntry();
            }
        }
        zip.finish(); zip.flush();
    }

    public void workerAuth(String key) {
        if (workerSecret.length() < 24 || !equal(workerSecret, key)) throw new AccessDeniedException("评测服务凭证无效或未配置");
    }
    public Map<String,Object> claim(String key) {
        workerAuth(key);
        List<CspRecord> candidates = new ArrayList<>(records.findByKindAndStateOrderByUpdatedAtAsc("TASK", "RUNNING"));
        candidates.addAll(records.findByKindAndStateOrderByUpdatedAtAsc("TASK", "QUEUED"));
        for (CspRecord candidate : candidates) {
            CspRecord r = get(candidate.id, "TASK", true);
            if (!r.state.equals("QUEUED") && !(r.state.equals("RUNNING") && r.leaseUntil != null && !now().isBefore(r.leaseUntil))) continue;
            Task t = decode(r, Task.class); t.leaseToken = randomToken(); t.attempts++; r.state = "RUNNING"; r.leaseUntil = now().plusSeconds(90); save(r,t);
            return Map.of("id", r.id, "task", t);
        }
        return Map.of();
    }
    private CspRecord leased(String key, String id, String token) {
        workerAuth(key); CspRecord r = get(id, "TASK", true); Task t = decode(r,Task.class);
        if (!r.state.equals("RUNNING") || !equal(t.leaseToken,token) || r.leaseUntil == null || !now().isBefore(r.leaseUntil)) throw new AccessDeniedException("任务租约已失效"); return r;
    }
    public void heartbeat(String key, String id, String token) { CspRecord r = leased(key,id,token); r.leaseUntil = now().plusSeconds(90); save(r,decode(r,Task.class)); }
    public byte[] workerBlob(String key, String id, String token, String blob) {
        Task t = decode(leased(key,id,token),Task.class);
        if (!blob.equals(t.sourceBlob) && t.problem.cases.stream().noneMatch(c -> blob.equals(c.inputBlob) || blob.equals(c.answerBlob))) throw new AccessDeniedException("文件不属于此评测任务"); return files.get(blob);
    }
    public void complete(String key, String id, Complete input) {
        CspRecord r = leased(key,id,input.leaseToken()); Task t = decode(r,Task.class); Result result = input.result();
        if (result == null || result.verdict == null || result.message == null || result.message.length() > 16000 || result.environment == null || result.environment.length() > 1000 || result.cases == null || result.cases.size() > 1000) throw new IllegalArgumentException("评测结果无效");
        if (result.verdict.equals("SYSTEM_ERROR")) { r.state = "ERROR"; result.score = null; }
        else {
            if (!Set.of("AC", "PARTIAL", "WA", "CE", "TLE", "MLE", "RE", "OLE").contains(result.verdict)) throw new IllegalArgumentException("评测状态无效");
            result.score = score(t.problem,result); r.state = "DONE";
        }
        t.result = result; t.leaseToken = null; r.leaseUntil = null; save(r,t);
    }
    public static BigDecimal score(Problem p, Result result) {
        if (result.verdict.equals("CE")) return BigDecimal.ZERO;
        Map<String,CaseResult> byId = new HashMap<>();
        for (CaseResult c : result.cases) {
            if (c.message == null || c.message.length()>2000 || c.expected == null || c.expected.length()>400 || c.actual == null || c.actual.length()>400 || c.differenceLine<0 || c.differenceColumn<0) throw new IllegalArgumentException("测试点诊断信息无效");
            if (c.id == null || !Set.of("AC", "WA", "TLE", "MLE", "RE", "OLE").contains(c.verdict) || byId.putIfAbsent(c.id,c) != null) throw new IllegalArgumentException("测试点结果无效");
        }
        if (byId.size() != p.cases.size() || p.cases.stream().anyMatch(c -> !byId.containsKey(c.id))) throw new IllegalArgumentException("测试点结果不完整");
        BigDecimal total = BigDecimal.ZERO;
        if (p.scoring.equals("POINTS")) { for (TestCase c : p.cases) if (byId.get(c.id).verdict.equals("AC")) total = total.add(c.score); }
        else {
            Set<String> seen = new HashSet<>();
            for (TestCase c : p.cases) if (seen.add(c.subtask) && p.cases.stream().filter(tc -> tc.subtask.equals(c.subtask)).allMatch(tc -> byId.get(tc.id).verdict.equals("AC"))) total = total.add(c.score);
        }
        return total;
    }
    public Map<String,Object> retry(String owner,String examId,String batchId) {
        teacherBatch(owner,examId,batchId);
        for (CspRecord task : records.findByKindAndParentIdOrderByUpdatedAtAsc("TASK",batchId)) {
            CspRecord r = get(task.id,"TASK",true); if (r.state.equals("ERROR")) { Task t = decode(r,Task.class); t.result = null; r.state = "QUEUED"; save(r,t); }
        }
        return batchView(batchId,null);
    }
}
