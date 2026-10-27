package cn.datacraft.teaching;

import cn.datacraft.homework.*;
import cn.datacraft.homework.HomeworkTypes.*;
import cn.datacraft.cspsim.*;
import cn.datacraft.cspsim.CspTypes.*;
import cn.datacraft.job.*;
import cn.datacraft.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Teacher-owned, read-only reports; OJ accounts and exam students are linked explicitly. */
@Service
public class TeacherWorkbench {
    private final HomeworkRepository homework;
    private final CspRecordRepository exams;
    private final JobService jobs;
    private final UserService users;
    private final ObjectMapper json;
    public TeacherWorkbench(HomeworkRepository homework, CspRecordRepository exams, JobService jobs, UserService users, ObjectMapper json) {
        this.homework=homework; this.exams=exams; this.jobs=jobs; this.users=users; this.json=json;
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException("教学统计读取失败"); }
    }
    private List<HomeworkRecord> records(String owner,String kind) { return homework.findByOwnerAndKindOrderByUpdatedAtDesc(owner,kind); }
    private String settingsId(String owner) { return UUID.nameUUIDFromBytes(("learning:"+owner).getBytes(StandardCharsets.UTF_8)).toString(); }
    public static class Preferences {
        public Map<String,String> links=new LinkedHashMap<>();
        public Map<String,List<String>> tags=new LinkedHashMap<>();
        public long revision;
    }
    public Preferences preferences(String owner) {
        return homework.findById(settingsId(owner)).map(r->read(r.payload,Preferences.class)).orElseGet(Preferences::new);
    }
    @Transactional
    public Preferences savePreferences(String owner, Preferences input) {
        if(input==null || input.links==null || input.tags==null || input.links.size()>500 || input.tags.size()>2000) throw new IllegalArgumentException("关联或标签配置过多");
        Set<String> ownedStudents=new HashSet<>();
        for(var r:exams.findByKindAndOwnerOrderByUpdatedAtDesc("STUDENT",owner)) ownedStudents.add(r.id);
        Set<String> knownUids=new HashSet<>();
        for(var r:records(owner,"CLASS")) for(var s:read(r.payload,SchoolClass.class).students) knownUids.add(""+s.uid());
        for(var r:records(owner,"ASSIGNMENT")) for(var s:read(r.payload,HomeworkTypes.Assignment.class).students) knownUids.add(""+s.uid());
        for(var e:input.links.entrySet()) if(!knownUids.contains(e.getKey()) || !ownedStudents.contains(e.getValue())) throw new IllegalArgumentException("学生关联不属于当前教师");
        if(new HashSet<>(input.links.values()).size()!=input.links.size()) throw new IllegalArgumentException("同一考试学生只能关联一个 OJ UID");
        for(var e:input.tags.entrySet()) {
            if(e.getKey()==null || e.getKey().isBlank() || e.getKey().length()>200 || e.getValue()==null || e.getValue().size()>8
                    || e.getValue().stream().anyMatch(t->t==null || t.isBlank() || t.length()>30)) throw new IllegalArgumentException("每题最多8个标签，每个标签1～30字");
        }
        var existing=homework.lock(settingsId(owner));
        var previous=existing.map(r->read(r.payload,Preferences.class)).orElseGet(Preferences::new);
        if(input.revision!=previous.revision) throw new IllegalStateException("配置已更新，请刷新后重试");
        var r=existing.orElseGet(HomeworkRecord::new); r.id=settingsId(owner); r.owner=owner; r.kind="LEARNING";
        input.revision++;
        try { r.payload=json.writeValueAsString(input); } catch(Exception e) { throw new IllegalArgumentException("标签配置无效"); }
        r.updatedAt=Instant.now(); homework.saveAndFlush(r); return input;
    }
    public Map<String,Object> dashboard(String owner) {
        var work=new ArrayList<Map<String,Object>>(); int pending=0, uncertain=0;
        Instant now=Instant.now();
        for(var r:records(owner,"ASSIGNMENT")) {
            var a=read(r.payload,HomeworkTypes.Assignment.class); if(a.archived || a.startAt.isAfter(now)) continue;
            int missing=0,unknown=0;
            for(var s:a.students) for(var p:a.problems) {
                Cell c=a.cells.get(HomeworkTypes.cellKey(s.uid(),p.docId()));
                if(c==null || "UNKNOWN".equals(c.status)) unknown++; else if(!c.completed) missing++;
            }
            pending+=missing; uncertain+=unknown;
            if(missing+unknown>0) work.add(Map.of("title",a.className+" · "+a.title,"pending",missing,"unknown",unknown,"href","/kedaoj-homework.html?assignment="+r.id));
        }
        var examViews=new ArrayList<Map<String,Object>>();
        for(var r:exams.findByKindAndOwnerOrderByUpdatedAtDesc("EXAM",owner)) {
            var e=read(r.payload,Exam.class);
            if(Set.of("OPEN","CLOSED").contains(r.state)) examViews.add(Map.of("id",r.id,"title",e.name,"state",r.state,"href","/csp-sim.html?exam="+r.id));
        }
        var taskViews=new ArrayList<Map<String,Object>>();
        for(var r:records(owner,"TASK")) if(Set.of("QUEUED","RUNNING","FAILED","PARTIAL").contains(r.state)) taskViews.add(Map.of("kind","作业更新","state",r.state,"href","/kedaoj-homework.html"));
        for(var j:jobs.recent(users.requireByUsername(owner).getId())) if(j.getStatus()!=JobStatus.COMPLETED) taskViews.add(Map.of("kind","数据生成","state",j.getStatus().name(),"href","/tools.html?tool=generator&job="+j.getId()));
        var connections=new ArrayList<Map<String,Object>>();
        for(var r:records(owner,"SETTINGS")) {
            var s=read(r.payload,Settings.class);
            connections.add(Map.of("configured",s.encryptedSid!=null,"state",s.connectionState==null?"UNCONFIGURED":s.connectionState,"href","/kedaoj-homework.html"));
        }
        if(connections.isEmpty())connections.add(Map.of("configured",false,"state","UNCONFIGURED","href","/kedaoj-homework.html"));
        return Map.of("pendingCells",pending,"unknownCells",uncertain,"assignments",work,"exams",examViews,"tasks",taskViews,"connections",connections,"updatedAt",now);
    }
    public Map<String,Object> learning(String owner) {
        Preferences prefs=preferences(owner);
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        LocalDate monday=LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var all=records(owner,"ASSIGNMENT");
        var decoded=all.stream().map(r->read(r.payload,HomeworkTypes.Assignment.class)).toList();
        Map<Long,List<HomeworkTypes.Assignment>> byStudent=new HashMap<>();
        for(var a:decoded)for(var s:a.students)byStudent.computeIfAbsent(s.uid(),k->new ArrayList<>()).add(a);
        Map<Long,HomeworkTypes.Student> students=new TreeMap<>();
        for(var r:records(owner,"CLASS")) for(var s:read(r.payload,SchoolClass.class).students) students.put(s.uid(),s);
        for(var a:decoded) for(var s:a.students) students.putIfAbsent(s.uid(),s);
        Map<String,Map<String,Object>> problemList=new LinkedHashMap<>();
        for(var a:decoded) for(var p:a.problems) problemList.putIfAbsent(a.domain+":"+p.docId(),Map.of("key",a.domain+":"+p.docId(),"title",p.pid()+" · "+p.title()));
        Map<String,List<Map<String,Object>>> examScores=new HashMap<>();
        for(var er:exams.findByKindAndOwnerOrderByUpdatedAtDesc("EXAM",owner)) {
            var e=read(er.payload,Exam.class);if(e.publishedBatch==null)continue;
            var batch=exams.findById(e.publishedBatch).orElse(null);
            if(batch==null||!owner.equals(batch.owner)||!read(batch.payload,Batch.class).published)continue;
            Map<String,java.math.BigDecimal[]> totals=new HashMap<>();
            for(var tr:exams.findByKindAndParentIdOrderByUpdatedAtAsc("TASK",batch.id)) {
                var t=read(tr.payload,Task.class);if(t.result==null||t.result.score==null||!"DONE".equals(tr.state))continue;
                var score=totals.computeIfAbsent(t.studentId,k->new java.math.BigDecimal[]{java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO});
                score[0]=score[0].add(t.result.score);score[1]=score[1].add(t.problem.maxScore);
            }
            totals.forEach((id,score)->examScores.computeIfAbsent(id,k->new ArrayList<>()).add(Map.of("name",e.name,"score",score[0],"maximum",score[1],"href","/csp-sim.html?exam="+er.id)));
        }
        var result=new ArrayList<Map<String,Object>>();
        for(var student:students.values()) {
            var weeks=new ArrayList<Map<String,Object>>(); var byTag=new TreeMap<String,int[]>();
            for(int offset=3;offset>=0;offset--) {
                LocalDate start=monday.minusWeeks(offset); int completed=0,total=0,unknown=0,late=0;
                for(var a:byStudent.getOrDefault(student.uid(),List.of())) {
                    if(a.startAt.isAfter(Instant.now())) continue;
                    LocalDate day=a.startAt.atZone(zone).toLocalDate();
                    if(day.isBefore(start) || !day.isBefore(start.plusWeeks(1))) continue;
                    for(var p:a.problems) {
                        total++; var c=a.cells.get(HomeworkTypes.cellKey(student.uid(),p.docId()));
                        boolean unchecked=c==null || "UNKNOWN".equals(c.status);
                        if(unchecked) unknown++; else if(c.completed) {completed++; if(!c.beforeDeadline)late++;}
                        for(String tag:prefs.tags.getOrDefault(a.domain+":"+p.docId(),List.of())) {
                            int[] counts=byTag.computeIfAbsent(tag,k->new int[3]); counts[0]++; if(unchecked)counts[2]++; else if(c.completed)counts[1]++;
                        }
                    }
                }
                weeks.add(Map.of("week",start.toString(),"total",total,"completed",completed,"unknown",unknown,"late",late));
            }
            String linked=prefs.links.get(""+student.uid());
            var scores=linked==null?List.<Map<String,Object>>of():examScores.getOrDefault(linked,List.of());
            var tags=new ArrayList<Map<String,Object>>(); byTag.forEach((tag,c)->tags.add(Map.of("tag",tag,"total",c[0],"completed",c[1],"unknown",c[2])));
            boolean needsAttention=weeks.subList(1,3).stream().allMatch(w->(int)w.get("total")-(int)w.get("unknown")>(int)w.get("completed"));
            result.add(Map.of("uid",student.uid(),"name",student.name(),"uname",student.uname(),"weeks",weeks,"tags",tags,"exams",scores,"needsAttention",needsAttention));
        }
        var choices=new ArrayList<Map<String,Object>>();
        for(var r:exams.findByKindAndOwnerOrderByUpdatedAtDesc("STUDENT",owner)) { var s=read(r.payload,CspTypes.Student.class); choices.add(Map.of("id",r.id,"name",s.name,"studentNumber",s.studentNumber)); }
        return Map.of("students",result,"preferences",prefs,"examStudents",choices,"problems",problemList.values());
    }
}
