package cn.datacraft.homework;

import cn.datacraft.ai.SecretCipher;
import cn.datacraft.homework.HomeworkTypes.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.datacraft.homework.HomeworkTypes.cellKey;

@Service
@EnableScheduling
public class HomeworkService {
    private final HomeworkRepository repository;
    private final ObjectMapper json;
    private final SecretCipher cipher;
    private final KedaojGateway oj;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Clock clock;
    private final boolean scheduling;
    private final Set<String> executing = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> ownerLocks = new ConcurrentHashMap<>();

    @Autowired public HomeworkService(HomeworkRepository repository, ObjectMapper json, SecretCipher cipher,
            KedaojGateway oj, PlatformTransactionManager manager, @Qualifier("homeworkExecutor") Executor executor,
            @Value("${dataforge.homework.scheduling-enabled:true}") boolean scheduling) {
        this(repository, json, cipher, oj, manager, executor, Clock.systemUTC(), scheduling);
    }
    HomeworkService(HomeworkRepository repository, ObjectMapper json, SecretCipher cipher, KedaojGateway oj,
                    PlatformTransactionManager manager, Executor executor, Clock clock, boolean scheduling) {
        this.repository = repository; this.json = json; this.cipher = cipher; this.oj = oj;
        this.tx = new TransactionTemplate(manager); this.executor = executor; this.clock = clock; this.scheduling = scheduling;
    }
    private Instant now() { return clock.instant(); }
    private Object ownerLock(String owner) { return ownerLocks.computeIfAbsent(owner, k -> new Object()); }
    private static String stableId(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(); }
    private <T> T read(HomeworkRecord r, Class<T> type) {
        try { return json.readValue(r.payload, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("作业数据读取失败，请联系管理员"); }
    }
    private HomeworkRecord write(HomeworkRecord r, Object value) {
        try { r.payload = json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("作业数据保存失败"); }
        r.updatedAt = now(); return repository.saveAndFlush(r);
    }
    private HomeworkRecord fresh(String owner, String kind) {
        var r = new HomeworkRecord(); r.id = UUID.randomUUID().toString(); r.owner = owner; r.kind = kind; return r;
    }
    private HomeworkRecord require(String owner, String id, String kind) {
        var r = repository.findById(id).orElseThrow(() -> new NoSuchElementException("没有找到该作业数据"));
        if (!r.owner.equals(owner) || !r.kind.equals(kind)) throw new NoSuchElementException("没有找到该作业数据");
        return r;
    }
    private HomeworkRecord settingsRecord(String owner) {
        synchronized (ownerLock(owner)) {
            return repository.findById(stableId("settings:" + owner)).orElseGet(() -> {
                var r = fresh(owner, "SETTINGS"); r.id = stableId("settings:" + owner); return write(r, new Settings());
            });
        }
    }
    private Settings configuration(String owner) { return read(settingsRecord(owner), Settings.class); }
    public SettingsView settings(String owner) {
        Settings s = configuration(owner);
        return new SettingsView(s.encryptedSid != null, s.domain, s.autoUpdate, s.ojUid, s.ojUsername,
                s.connectionState, s.message, s.checkedAt);
    }
    private String sid(Settings s) {
        if (s.encryptedSid == null) throw new IllegalArgumentException("请先在 OJ 连接中保存登录凭据");
        try { return cipher.decrypt(s.encryptedSid); }
        catch (RuntimeException e) { throw new IllegalStateException("OJ 凭据解密失败，请检查 DATAFORGE_SECRET 或重新录入"); }
    }
    public SettingsView saveSettings(String owner, SettingsInput input) {
        if (input == null) throw new IllegalArgumentException("请填写连接设置");
        String domain = KedaojClient.domain(input.domain());
        String newSid = input.credential() == null || input.credential().isBlank() ? null : KedaojClient.credential(input.credential());
        KedaojGateway.Identity identity = newSid == null ? null : oj.identity(owner, newSid, domain);
        synchronized (ownerLock(owner)) {
            var r = settingsRecord(owner);
            tx.executeWithoutResult(status -> {
                var locked = repository.lock(r.id).orElseThrow(); var s = read(locked, Settings.class);
                if (!s.domain.equals(domain) && newSid == null) {
                    s.connectionState = "UNCHECKED"; s.message = "题库域已改变，请验证连接";
                }
                s.domain = domain;
                if (input.autoUpdate() != null) s.autoUpdate = input.autoUpdate();
                if (newSid != null) { s.encryptedSid = cipher.encrypt(newSid); connected(s, identity); }
                write(locked, s);
            });
        }
        return settings(owner);
    }
    private void connected(Settings s, KedaojGateway.Identity identity) {
        s.ojUid = identity.uid(); s.ojUsername = identity.uname(); s.connectionState = "CONNECTED";
        s.checkedAt = now(); s.message = "已验证 OJ 身份";
    }
    public SettingsView verifyConnection(String owner) {
        Settings config = configuration(owner);
        try {
            var identity = oj.identity(owner, sid(config), config.domain);
            updateSettingsState(owner, s -> connected(s, identity));
        } catch (KedaojException e) {
            updateSettingsState(owner, s -> { s.connectionState = e.code(); s.message = e.getMessage(); s.checkedAt = now(); });
            throw e;
        }
        return settings(owner);
    }
    private void updateSettingsState(String owner, java.util.function.Consumer<Settings> action) {
        synchronized (ownerLock(owner)) {
            var r = settingsRecord(owner);
            tx.executeWithoutResult(t -> { var locked = repository.lock(r.id).orElseThrow(); var s = read(locked, Settings.class); action.accept(s); write(locked, s); });
        }
    }
    public SettingsView clearCredential(String owner) {
        updateSettingsState(owner, s -> { s.encryptedSid = null; s.ojUid = null; s.ojUsername = null;
            s.connectionState = "UNCONFIGURED"; s.message = null; s.checkedAt = null; });
        return settings(owner);
    }
    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new IllegalArgumentException(label + "不能为空且不能超过 " + max + " 个字符");
        return value.trim();
    }
    public List<ClassView> classes(String owner) {
        return repository.findByOwnerAndKindOrderByUpdatedAtDesc(owner, "CLASS").stream().map(r -> classView(r, read(r, SchoolClass.class))).toList();
    }
    private ClassView classView(HomeworkRecord r, SchoolClass c) { return new ClassView(r.id, c.name, c.students); }
    public ClassView saveClass(String owner, String id, ClassInput input) {
        String name = text(input == null ? null : input.name(), "班级名称", 80);
        return tx.execute(t -> {
            var r = id == null ? fresh(owner, "CLASS") : repository.lock(require(owner, id, "CLASS").id).orElseThrow();
            SchoolClass c = id == null ? new SchoolClass() : read(r, SchoolClass.class); c.name = name;
            return classView(write(r, c), c);
        });
    }
    public List<Student> previewStudents(String owner, List<StudentInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 500) throw new IllegalArgumentException("每次请填写 1～500 位学生");
        var config = configuration(owner); String sid = sid(config);
        oj.identity(owner, sid, config.domain);
        Set<Long> seen = new HashSet<>(); List<Student> result = new ArrayList<>();
        for (var input : inputs) {
            if (input == null || input.uid() <= 0 || !seen.add(input.uid())) throw new IllegalArgumentException("OJ UID 必须是大于 0 的整数且不能重复");
            String name = text(input.name(), "学生姓名", 80);
            var user = oj.user(owner, sid, config.domain, input.uid());
            result.add(new Student(UUID.randomUUID().toString(), name, user.uid(), user.uname()));
        }
        return result;
    }
    public ClassView addStudents(String owner, String classId, List<StudentInput> inputs) {
        require(owner, classId, "CLASS"); List<Student> verified = previewStudents(owner, inputs);
        return tx.execute(t -> {
            var r = repository.lock(require(owner, classId, "CLASS").id).orElseThrow(); var c = read(r, SchoolClass.class);
            var existing = new HashSet<>(c.students.stream().map(Student::uid).toList());
            for (Student s : verified) if (!existing.add(s.uid())) throw new IllegalArgumentException("班级内已存在 UID " + s.uid());
            if (existing.size() > 500) throw new IllegalArgumentException("每个班级最多 500 位学生");
            c.students.addAll(verified); return classView(write(r, c), c);
        });
    }
    public ClassView editStudent(String owner, String classId, String studentId, StudentInput input) {
        var original = read(require(owner, classId, "CLASS"), SchoolClass.class);
        if (original.students.stream().noneMatch(s -> s.id().equals(studentId))) throw new NoSuchElementException("没有找到该学生");
        var verified = previewStudents(owner, List.of(input)).get(0);
        return tx.execute(t -> {
            var r = repository.lock(require(owner, classId, "CLASS").id).orElseThrow(); var c = read(r, SchoolClass.class);
            if (c.students.stream().anyMatch(s -> s.uid() == verified.uid() && !s.id().equals(studentId))) throw new IllegalArgumentException("班级内 UID 不能重复");
            int i = -1; for (int j = 0; j < c.students.size(); j++) if (c.students.get(j).id().equals(studentId)) i = j;
            if (i < 0) throw new NoSuchElementException("没有找到该学生");
            c.students.set(i, new Student(studentId, verified.name(), verified.uid(), verified.uname()));
            return classView(write(r, c), c);
        });
    }
    public ClassView removeStudent(String owner, String classId, String studentId) {
        return tx.execute(t -> {
            var r = repository.lock(require(owner, classId, "CLASS").id).orElseThrow(); var c = read(r, SchoolClass.class);
            if (!c.students.removeIf(s -> s.id().equals(studentId))) throw new NoSuchElementException("没有找到该学生");
            return classView(write(r, c), c);
        });
    }
    public AssignmentView createAssignment(String owner, CreateAssignment input) {
        if (input == null) throw new IllegalArgumentException("请填写作业");
        var c = read(require(owner, input.classId(), "CLASS"), SchoolClass.class);
        if (c.students.isEmpty()) throw new IllegalArgumentException("请先为班级添加学生");
        var a = new Assignment(); a.classId = input.classId(); a.className = c.name;
        a.title = text(input.title(), "作业名称", 100); a.domain = KedaojClient.domain(input.domain());
        if (input.startAt() == null) throw new IllegalArgumentException("请填写开始时间");
        a.startAt = input.startAt();
        a.endAt = input.endAt() == null ? a.startAt.plus(Duration.ofDays(7)) : input.endAt();
        if (!a.endAt.isAfter(a.startAt)) throw new IllegalArgumentException("截止时间必须晚于开始时间");
        if (input.problemIds() == null || input.problemIds().isEmpty() || input.problemIds().size() > 100) throw new IllegalArgumentException("每份作业请填写 1～100 个题号");
        var config = configuration(owner); String sid = sid(config); oj.identity(owner, sid, a.domain);
        Set<Long> seen = new HashSet<>();
        for (String pid : new LinkedHashSet<>(input.problemIds())) {
            var p = oj.problem(owner, sid, a.domain, pid == null ? null : pid.trim());
            if (seen.add(p.docId())) a.problems.add(p);
        }
        // Copy the roster only after validation, so subsequent edits never rewrite an old assignment.
        a.students = new ArrayList<>(read(require(owner, input.classId(), "CLASS"), SchoolClass.class).students);
        if (a.students.isEmpty()) throw new IllegalArgumentException("班级已没有学生，请重新选择");
        return view(write(fresh(owner, "ASSIGNMENT"), a), a);
    }
    public List<AssignmentView> assignments(String owner) {
        return repository.findByOwnerAndKindOrderByUpdatedAtDesc(owner, "ASSIGNMENT").stream().map(r -> view(r, read(r, Assignment.class))).toList();
    }
    public AssignmentView assignment(String owner, String id) { var r = require(owner, id, "ASSIGNMENT"); return view(r, read(r, Assignment.class)); }
    private AssignmentView view(HomeworkRecord r, Assignment a) {
        int completeStudents = 0, completeCells = 0, unknown = 0;
        for (Student s : a.students) {
            boolean complete = !a.problems.isEmpty();
            for (Problem p : a.problems) {
                Cell cell = a.cells.get(cellKey(s.uid(), p.docId()));
                if (cell != null && cell.completed) completeCells++; else complete = false;
                if (cell == null || cell.status.equals("UNKNOWN")) unknown++;
            }
            if (complete) completeStudents++;
        }
        return new AssignmentView(r.id, a.classId, a.className, a.title, a.domain, a.startAt, a.endAt, a.archived,
                a.students, a.problems, a.cells, a.syncedAt, a.attemptedAt, a.error, completeStudents, completeCells, unknown,
                HomeworkReports.stats(a), a.lastChange, a.revision, HomeworkReports.failed(a));
    }
    public AssignmentView copyAssignment(String owner, String id, CreateAssignment input) {
        require(owner, id, "ASSIGNMENT"); return createAssignment(owner, input);
    }
    public AssignmentView editAssignment(String owner, String id, CreateAssignment input, long expectedRevision) {
        var original = read(require(owner, id, "ASSIGNMENT"), Assignment.class);
        if (original.archived) throw new IllegalArgumentException("请先恢复跟踪，再编辑归档作业");
        if (input == null || !original.classId.equals(input.classId())) throw new IllegalArgumentException("编辑不能改变所属班级，请使用复制到其他班级");
        String title = text(input.title(), "作业名称", 100), domain = KedaojClient.domain(input.domain());
        if (input.startAt() == null || input.endAt() == null || !input.endAt().isAfter(input.startAt())) throw new IllegalArgumentException("请填写有效的开始和截止时间");
        if (input.problemIds() == null || input.problemIds().isEmpty() || input.problemIds().size() > 100) throw new IllegalArgumentException("请填写 1～100 个题号");
        // Resolve only changed problem metadata. Merely correcting a title or deadline needs no OJ call.
        Map<String, Problem> known = new HashMap<>();
        if (original.domain.equals(domain)) for (Problem p : original.problems) known.put(p.pid(), p);
        List<Problem> problems = new ArrayList<>(); Set<Long> seen = new HashSet<>();
        String session = null;
        for (String value : new LinkedHashSet<>(input.problemIds())) {
            String pid = value == null ? null : value.trim(); Problem p = known.get(pid);
            if (p == null) {
                if (session == null) { session = sid(configuration(owner)); oj.identity(owner, session, domain); }
                p = oj.problem(owner, session, domain, pid);
            }
            if (seen.add(p.docId())) problems.add(p);
        }
        var settings = settingsRecord(owner);
        return tx.execute(t -> {
            repository.lock(settings.id).orElseThrow();
            var r = repository.lock(require(owner, id, "ASSIGNMENT").id).orElseThrow(); var a = read(r, Assignment.class);
            if (a.archived || a.revision != expectedRevision) throw new IllegalArgumentException("作业已改变，请刷新后重新编辑");
            var active = repository.findByActiveKey("sync:" + owner);
            if (active.isPresent() && read(active.get(), SyncTask.class).assignmentIds.contains(id)) throw new IllegalArgumentException("这份作业正在更新，请结束后再编辑");
            boolean sameDomain = a.domain.equals(domain);
            Map<String, Cell> retained = new LinkedHashMap<>();
            for (Student s : a.students) for (Problem p : problems) {
                String key = cellKey(s.uid(), p.docId()); Cell c = sameDomain ? a.cells.get(key) : null;
                if (c == null) continue;
                if (c.accepted != null) {
                    c.beforeDeadline = c.accepted.submittedAt().isBefore(input.endAt());
                    if (!HomeworkReports.unknown(c)) c.status = c.beforeDeadline ? "AC" : "LATE";
                    else if (c.completed) c.previousStatus = c.beforeDeadline ? "AC" : "LATE";
                }
                retained.put(key, c);
            }
            boolean newCells = retained.size() < a.students.size() * problems.size();
            a.title = title; a.domain = domain; a.startAt = input.startAt(); a.endAt = input.endAt();
            a.problems = problems; a.cells = retained; a.revision++; a.lastChange = null;
            if (newCells) a.syncedAt = null;
            a.error = HomeworkReports.failed(a) == 0 ? null : "部分题目仍待核验，可重试失败项";
            return view(write(r, a), a);
        });
    }
    public IncompleteReport incomplete(String owner, String id) {
        return HomeworkReports.incomplete(read(require(owner, id, "ASSIGNMENT"), Assignment.class));
    }
    public byte[] exportExcel(String owner, String id) {
        return HomeworkExcel.export(read(require(owner, id, "ASSIGNMENT"), Assignment.class));
    }
    public StudentOverview studentOverview(String owner, long uid) {
        if (uid <= 0) throw new IllegalArgumentException("请填写有效的 OJ UID");
        Student profile = null; List<StudentAssignment> rows = new ArrayList<>();
        for (var r : repository.findByOwnerAndKindOrderByUpdatedAtDesc(owner, "ASSIGNMENT")) {
            var a = read(r, Assignment.class);
            var match = a.students.stream().filter(s -> s.uid() == uid).findFirst();
            if (match.isPresent()) { if (profile == null) profile = match.get(); rows.add(HomeworkReports.studentAssignment(r.id, a, uid)); }
        }
        for (var c : classes(owner)) for (Student s : c.students()) if (s.uid() == uid) profile = s;
        if (profile == null) throw new NoSuchElementException("没有找到该学生");
        rows.sort(Comparator.comparing(StudentAssignment::startAt).reversed().thenComparing(StudentAssignment::id));
        int outstanding = rows.stream().filter(a -> !a.archived() && !a.startAt().isAfter(now())).mapToInt(a -> a.remaining().size()).sum();
        int unknown = rows.stream().filter(a -> !a.archived() && !a.startAt().isAfter(now())).mapToInt(a -> a.unknown().size()).sum();
        return new StudentOverview(uid, profile.name(), profile.uname(), rows.size(),
                (int) rows.stream().filter(a -> a.onTime() == a.total()).count(), rows.stream().mapToInt(StudentAssignment::late).sum(), outstanding, unknown, rows);
    }
    public AssignmentView archive(String owner, String id, boolean archive) {
        return tx.execute(t -> {
            var r = repository.lock(require(owner, id, "ASSIGNMENT").id).orElseThrow(); var a = read(r, Assignment.class);
            a.archived = archive; return view(write(r, a), a);
        });
    }
    public TaskView refresh(String owner, String assignmentId) {
        return refresh(owner, assignmentId, false, false);
    }
    public TaskView refresh(String owner, String assignmentId, boolean fullScan, boolean retryOnly) {
        var a = assignment(owner, assignmentId);
        if (a.archived()) throw new IllegalArgumentException("请先恢复跟踪，再更新归档作业");
        if (retryOnly && a.failedCells() == 0) throw new IllegalArgumentException("没有失败项；首次查询请点击手动更新");
        sid(configuration(owner)); return enqueue(owner, List.of(assignmentId), fullScan, retryOnly);
    }
    private TaskView enqueue(String owner, List<String> ids) {
        return enqueue(owner, ids, false, false);
    }
    private TaskView enqueue(String owner, List<String> ids, boolean fullScan, boolean retryOnly) {
        synchronized (ownerLock(owner)) {
            var settings = settingsRecord(owner);
            return tx.execute(t -> {
                repository.lock(settings.id).orElseThrow();
                var existing = repository.findByActiveKey("sync:" + owner);
                var r = existing.isPresent() ? repository.lock(existing.get().id).orElseThrow() : null;
                boolean active = r != null && r.activeKey != null && Set.of("QUEUED", "RUNNING").contains(r.state);
                if (!active) r = fresh(owner, "TASK");
                var task = active ? read(r, SyncTask.class) : new SyncTask();
                if (!active) { r.state = "QUEUED"; r.activeKey = "sync:" + owner; }
                for (String id : ids) {
                    var a = assignment(owner, id); if (a.archived()) continue;
                    boolean added = task.assignmentIds.add(id);
                    if (retryOnly && added) task.retryOnlyIds.add(id);
                    if (!retryOnly) task.retryOnlyIds.remove(id);
                    if (fullScan) { task.fullScanIds.add(id); task.retryOnlyIds.remove(id); }
                }
                task.total = task.assignmentIds.size(); write(r, task); return taskView(r, task);
            });
        }
    }
    public TaskView task(String owner, String id) { var r = require(owner, id, "TASK"); return taskView(r, read(r, SyncTask.class)); }
    public TaskView activeTask(String owner) {
        return repository.findByActiveKey("sync:" + owner).map(r -> taskView(r, read(r, SyncTask.class))).orElse(null);
    }
    private TaskView taskView(HomeworkRecord r, SyncTask t) {
        return new TaskView(r.id, r.state, t.completed, t.total, t.message, t.startedAt, t.finishedAt, new ArrayList<>(t.assignmentIds), t.checkedCells, t.failedCells);
    }
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void nightlyUpdate() {
        if (!scheduling) return;
        for (var r : repository.findByKind("SETTINGS")) {
            var s = read(r, Settings.class);
            if (!s.autoUpdate || s.encryptedSid == null) continue;
            var ids = assignments(r.owner).stream().filter(a -> !a.archived() && !a.startAt().isAfter(now())).map(AssignmentView::id).toList();
            if (!ids.isEmpty()) enqueue(r.owner, ids);
        }
    }
    @Scheduled(fixedDelay = 2000)
    public void dispatch() {
        if (!scheduling) return;
        for (var candidate : repository.findByKindAndStateInOrderByUpdatedAtAsc("TASK", List.of("QUEUED", "RUNNING"))) {
            if (!executing.add(candidate.id)) continue;
            try { executor.execute(() -> { try { runTask(candidate.id); } finally { executing.remove(candidate.id); } }); }
            catch (org.springframework.core.task.TaskRejectedException e) { executing.remove(candidate.id); }
        }
    }
    private HomeworkRecord leased(String id, String token) {
        var r = repository.lock(id).orElseThrow();
        if (!r.state.equals("RUNNING") || !Objects.equals(read(r, SyncTask.class).leaseToken, token)) throw new IllegalStateException("更新任务已被接管");
        return r;
    }
    private void heartbeat(String id, String token) {
        tx.executeWithoutResult(t -> { var r = leased(id, token); r.leaseUntil = now().plusSeconds(120); repository.saveAndFlush(r); });
    }
    private record PairResult(List<Submission> records, String error, boolean full) {}
    private PairResult fetchPair(String owner, Settings config, String session, Assignment a, Student student,
                                 Problem problem, boolean forceFull, String taskId, String token) {
        String pairId = stableId("pair:" + owner + ":" + a.domain + ":" + cellKey(student.uid(), problem.docId()));
        var stored = repository.findById(pairId);
        PairSnapshot previous = stored.map(r -> read(r, PairSnapshot.class)).orElse(null);
        String credentialVersion = stableId("credential:" + config.encryptedSid);
        boolean full = forceFull || previous == null || previous.fullScanAt == null
                || !credentialVersion.equals(previous.credentialVersion)
                || !previous.fullScanAt.plus(Duration.ofDays(7)).isAfter(now())
                || previous.submissions.stream().anyMatch(r -> Set.of(0, 20, 21, 22).contains(r.status()));
        String knownRid = previous == null ? null : previous.submissions.stream().map(Submission::rid).max(String::compareTo).orElse(null);
        RecordFetch response = full || knownRid == null
                ? new RecordFetch(oj.records(owner, session, a.domain, student.uid(), problem.docId(), () -> heartbeat(taskId, token)), true)
                : oj.recentRecords(owner, session, a.domain, student.uid(), problem.docId(), knownRid, () -> heartbeat(taskId, token));
        if (response == null || response.records() == null) throw new KedaojException("INVALID_RESPONSE", "OJ 未返回可核验的提交摘要");
        Map<String, Submission> merged = new LinkedHashMap<>();
        if (!response.completeHistory() && previous != null) for (var r : previous.submissions) merged.put(r.rid(), r);
        for (var r : response.records()) merged.put(r.rid(), r);
        PairSnapshot snapshot = new PairSnapshot(); snapshot.submissions = new ArrayList<>(merged.values());
        snapshot.syncedAt = now(); snapshot.fullScanAt = response.completeHistory() ? now() : previous.fullScanAt;
        snapshot.credentialVersion = credentialVersion;
        tx.executeWithoutResult(t -> {
            leased(taskId, token);
            var record = repository.findById(pairId).orElseGet(() -> { var r = fresh(owner, "PAIR"); r.id = pairId; return r; });
            write(record, snapshot);
        });
        return new PairResult(snapshot.submissions, null, response.completeHistory());
    }
    private static boolean stopBatch(KedaojException e) {
        return Set.of("AUTH_REQUIRED", "RATE_LIMITED", "NETWORK_ERROR", "UPSTREAM_ERROR", "INTERRUPTED").contains(e.code());
    }
    void runTask(String id) {
        String token = UUID.randomUUID().toString();
        String owner = tx.execute(t -> {
            var r = repository.lock(id).orElseThrow();
            if (!r.state.equals("QUEUED") && !(r.state.equals("RUNNING") && (r.leaseUntil == null || !r.leaseUntil.isAfter(now())))) return null;
            var task = read(r, SyncTask.class); task.leaseToken = token; task.startedAt = now(); task.message = "正在查询 OJ";
            task.processedIds.clear(); task.completed = 0; task.checkedCells = 0; task.failedCells = 0;
            r.state = "RUNNING"; r.leaseUntil = now().plusSeconds(120); write(r, task); return r.owner;
        });
        if (owner == null) return;
        try {
            Settings config = configuration(owner); String session = sid(config);
            Map<String, PairResult> fetched = new HashMap<>();
            Map<String, String> domainErrors = new HashMap<>(); Set<String> verifiedDomains = new HashSet<>();
            String fatal = null; boolean authFailed = false;
            while (true) {
                String assignmentId = tx.execute(t -> {
                    var r = leased(id, token); var task = read(r, SyncTask.class);
                    String next = task.assignmentIds.stream().filter(a -> !task.processedIds.contains(a)).findFirst().orElse(null);
                    if (next == null) {
                        task.finishedAt = now();
                        r.state = task.failedCells == 0 ? "SUCCEEDED" : task.failedCells == task.checkedCells ? "FAILED" : "PARTIAL";
                        task.message = task.failedCells == 0 ? "更新完成，已核验 " + task.checkedCells + " 项"
                                : "更新结束：" + (task.checkedCells - task.failedCells) + " 项成功，" + task.failedCells + " 项待核验";
                        r.activeKey = null; r.leaseUntil = null; write(r, task);
                    }
                    return next;
                });
                if (assignmentId == null) break;
                var a = read(require(owner, assignmentId, "ASSIGNMENT"), Assignment.class);
                var task = read(require(owner, id, "TASK"), SyncTask.class);
                boolean retryOnly = task.retryOnlyIds.contains(assignmentId), forceFull = task.fullScanIds.contains(assignmentId);
                Set<String> targets = new LinkedHashSet<>();
                if (!a.archived) {
                    if (fatal == null && !domainErrors.containsKey(a.domain) && verifiedDomains.add(a.domain)) {
                        try { heartbeat(id, token); oj.identity(owner, session, a.domain); }
                        catch (KedaojException e) {
                            String message = e.code() + "：" + e.getMessage(); domainErrors.put(a.domain, message);
                            if (stopBatch(e)) fatal = message;
                            if (e.code().equals("AUTH_REQUIRED")) authFailed = true;
                        }
                    }
                    for (Student student : a.students) for (Problem problem : a.problems) {
                        String cell = cellKey(student.uid(), problem.docId()); Cell prior = a.cells.get(cell);
                        if (retryOnly && !(prior != null && HomeworkReports.unknown(prior) && prior.error != null)) continue;
                        targets.add(cell); String pair = a.domain + ":" + cell;
                        if (fetched.containsKey(pair) && (!forceFull || fetched.get(pair).full || fetched.get(pair).error != null)) continue;
                        String blocked = fatal != null ? fatal : domainErrors.get(a.domain);
                        if (blocked != null) { fetched.put(pair, new PairResult(null, blocked, false)); continue; }
                        try { fetched.put(pair, fetchPair(owner, config, session, a, student, problem, forceFull, id, token)); }
                        catch (KedaojException e) {
                            String message = e.code() + "：" + e.getMessage(); fetched.put(pair, new PairResult(null, message, false));
                            if (stopBatch(e)) fatal = message;
                            if (e.code().equals("AUTH_REQUIRED")) authFailed = true;
                        }
                    }
                }
                tx.executeWithoutResult(t -> {
                    var tr = leased(id, token); var currentTask = read(tr, SyncTask.class);
                    var ar = repository.lock(assignmentId).orElseThrow(); var latest = read(ar, Assignment.class);
                    if (!latest.archived && !targets.isEmpty()) {
                        Map<String, Cell> before = new LinkedHashMap<>(latest.cells);
                        for (String cell : targets) {
                            PairResult result = fetched.get(latest.domain + ":" + cell);
                            latest.cells.put(cell, result != null && result.error == null
                                    ? evaluate(result.records, latest.endAt, now())
                                    : uncertain(latest.cells.get(cell), result == null ? "查询未完成" : result.error));
                        }
                        latest.attemptedAt = now(); latest.lastChange = HomeworkReports.changes(latest, before, targets, now());
                        currentTask.checkedCells += latest.lastChange.checked; currentTask.failedCells += latest.lastChange.failed;
                        latest.error = latest.cells.values().stream().filter(c -> HomeworkReports.unknown(c) && c.error != null)
                                .map(c -> c.error).findFirst().orElse(null);
                        boolean complete = latest.students.stream().allMatch(s -> latest.problems.stream()
                                .allMatch(p -> !HomeworkReports.unknown(latest.cells.get(cellKey(s.uid(), p.docId())))));
                        if (complete) latest.syncedAt = now();
                        write(ar, latest);
                    }
                    currentTask.processedIds.add(assignmentId); currentTask.completed = currentTask.processedIds.size();
                    currentTask.message = "已更新 " + currentTask.completed + " / " + currentTask.total + " 份作业，核验 " + currentTask.checkedCells + " 项";
                    tr.leaseUntil = now().plusSeconds(120); write(tr, currentTask);
                });
            }
            if (authFailed) updateSettingsState(owner, s -> { s.connectionState = "AUTH_REQUIRED"; s.message = "OJ 身份未确认，请验证或更新登录凭据"; s.checkedAt = now(); });
            else if (verifiedDomains.contains(config.domain) && !domainErrors.containsKey(config.domain))
                updateSettingsState(owner, s -> { s.connectionState = "CONNECTED"; s.message = "已验证 OJ 身份"; s.checkedAt = now(); });
        } catch (RuntimeException e) {
            String message = e instanceof KedaojException k ? k.code() + "：" + k.getMessage()
                    : e instanceof IllegalArgumentException ? "缺少可用的 OJ 登录凭据，请更新连接设置" : "更新中断，请重新手动更新或联系管理员";
            tx.executeWithoutResult(t -> {
                var r = repository.lock(id).orElseThrow(); var task = read(r, SyncTask.class);
                if (!Objects.equals(task.leaseToken, token) || !r.state.equals("RUNNING")) return;
                for (String aid : task.assignmentIds) {
                    if (task.processedIds.contains(aid)) continue;
                    var ar = repository.lock(aid).orElseThrow(); var a = read(ar, Assignment.class);
                    if (a.archived) continue;
                    for (Student s : a.students) for (Problem p : a.problems) {
                        String key = cellKey(s.uid(), p.docId()); Cell previous = a.cells.get(key);
                        if (task.retryOnlyIds.contains(aid) && !(previous != null && HomeworkReports.unknown(previous) && previous.error != null)) continue;
                        a.cells.put(key, uncertain(previous, message)); task.checkedCells++; task.failedCells++;
                    }
                    a.attemptedAt = now(); a.error = message; write(ar, a);
                }
                task.finishedAt = now(); task.message = message; r.state = "FAILED"; r.activeKey = null; r.leaseUntil = null; write(r, task);
            });
        }
    }
    private static Cell uncertain(Cell previous, String message) {
        Cell cell = new Cell();
        if (previous != null) {
            cell.status = previous.status; cell.previousStatus = previous.previousStatus;
            cell.completed = previous.completed; cell.beforeDeadline = previous.beforeDeadline;
            cell.submissions = previous.submissions; cell.bestScore = previous.bestScore;
            cell.latest = previous.latest; cell.accepted = previous.accepted; cell.syncedAt = previous.syncedAt;
        }
        if (!cell.status.equals("UNKNOWN")) cell.previousStatus = cell.status;
        cell.status = "UNKNOWN"; cell.error = message; return cell;
    }
    static Cell evaluate(List<Submission> records, Instant deadline, Instant syncedAt) {
        var rows = records.stream().filter(r -> r.contest() == null || !r.contest().matches("0{23}[01]"))
                .sorted(Comparator.comparing(Submission::submittedAt).thenComparing(Submission::rid)).toList();
        var c = new Cell(); c.syncedAt = syncedAt; c.submissions = rows.size();
        c.latest = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        c.bestScore = rows.stream().mapToDouble(Submission::score).max().orElse(0);
        c.accepted = rows.stream().filter(r -> r.status() == 1).findFirst().orElse(null);
        c.completed = c.accepted != null;
        c.beforeDeadline = c.completed && c.accepted.submittedAt().isBefore(deadline);
        c.status = c.completed ? c.beforeDeadline ? "AC" : "LATE" : rows.isEmpty() ? "EMPTY"
                : rows.stream().anyMatch(r -> Set.of(0, 20, 21, 22).contains(r.status())) ? "PENDING" : "ATTEMPTED";
        return c;
    }
}
