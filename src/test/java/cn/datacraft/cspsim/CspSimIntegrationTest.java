package cn.datacraft.cspsim;

import cn.datacraft.cspsim.CspTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:csp-integration;DB_CLOSE_DELAY=-1", "dataforge.csp-sim.worker-secret=test-worker-key-at-least-24-characters"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CspSimIntegrationTest {
    static final String KEY="test-worker-key-at-least-24-characters";
    static final Path RUNTIME;
    static { try { RUNTIME=Files.createTempDirectory("csp-integration-"); } catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("dataforge.runtime-dir",RUNTIME::toString);}
    @Autowired CspSimService sim;
    @Autowired CspRecordRepository records;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    int counter;
    @BeforeEach void reset(){records.deleteAll();counter=0;}
    record Fixture(String examId,String participantId,String studentId,String code,String token,Problem problem){}
    @SuppressWarnings("unchecked")
    Fixture fixture(String environment,String mode) {
        Map<String,Object> student=sim.addStudent("teacher",new StudentInput("同名学生","算法班","id"+(++counter)));
        CreateExam create=new CreateExam();create.exam.name="目录训练";create.exam.environment=environment;create.exam.mode=mode;
        create.exam.rootPath=environment.equals("WINDOWS")?"D:/":"/mnt/answers";
        Problem problem=new Problem();problem.name="求和";problem.directory="sum";problem.sourceName="sum.cpp";create.exam.problems.add(problem);
        create.students=List.of(new Assignment((String)student.get("id"),"SIM-J00001",null));
        Map<String,Object> exam=sim.createExam("teacher",create);String id=(String)exam.get("id");
        Map<String,Object> joined=sim.join(id,new Join("同名学生",(String)student.get("code")));
        sim.start("teacher",id);
        return new Fixture(id,(String)joined.get("participationId"),(String)student.get("id"),(String)student.get("code"),(String)joined.get("token"),problem);
    }
    void mkdir(Fixture f,String path){sim.fileOperation(f.participantId,f.token,new FileOperation("mkdir",path,null,null));}
    void upload(Fixture f,String folder,String name,String code){sim.upload(f.participantId,f.token,folder,List.of(name),List.of(code.getBytes(StandardCharsets.UTF_8)),true,null);}
    String correctFolder(Fixture f){return sim.workspace(f.participantId,f.token).get("rootPath")+"/SIM-J00001/sum";}
    void correctSource(Fixture f){String path=correctFolder(f);mkdir(f,CspFiles.parent(path));mkdir(f,path);upload(f,path,"sum.cpp","int main(){return 0;}");}
    void data(Fixture f){
        Map<String,Object> exam=sim.uploadData("teacher",f.examId,f.problem.id,CspFiles.zip(Map.of("data/1.in","1\n".getBytes(),"data/1.ans","1\n".getBytes(),"problem.md","题面".getBytes())),false);
        Problem p=((Exam)exam.get("exam")).problems.get(0);
        sim.configureData("teacher",f.examId,p.id,new DataConfig(p.dataVersion,"POINTS",List.of(new CaseConfig("data/1",new BigDecimal("100"),""))));
    }
    @Test @SuppressWarnings("unchecked")
    void defaultDirectoriesUseDesktopOrDriveDAndRegionalAdmissionNumber() {
        for (String environment : List.of("LINUX", "WINDOWS")) for (String group : List.of("J", "S")) {
            String owner="teacher-"+environment,number=group.equals("J")?"10002":"00123";
            var student=sim.addStudent(owner,new StudentInput("路径学生","训练班",number));
            CreateExam create=new CreateExam();create.exam.name="默认路径验证";create.exam.environment=environment;create.exam.group=group;
            Problem problem=new Problem();problem.name="求和";problem.directory="sum";problem.sourceName="sum.cpp";create.exam.problems.add(problem);
            create.students=List.of(new Assignment((String)student.get("id"),null,null));
            String examId=(String)sim.createExam(owner,create).get("id");sim.start(owner,examId);
            var joined=sim.join(examId,new Join("路径学生",(String)student.get("code")));
            String participantId=(String)joined.get("participationId"),token=(String)joined.get("token");
            var workspace=sim.workspace(participantId,token);
            String root=environment.equals("LINUX")?"/home/noi/Desktop":"/D",folderName="GD-"+group+number,folder=root+"/"+folderName;
            assertThat(workspace.get("rootPath")).isEqualTo(root);
            assertThat(workspace.get("examNumber")).isEqualTo(folderName);
            assertThat(workspace.get("folderName")).isEqualTo(folderName).isNotEqualTo(student.get("studentNumber"));
            assertThat((Map<String,Entry>)workspace.get("entries")).containsKey(root).doesNotContainKey(folder);
            var checks=(List<Map<String,Object>>)sim.check(participantId,token).get("problems");
            assertThat(checks.get(0).get("expected")).isEqualTo(folder+"/sum/sum.cpp");
            assertThat((List<?>)checks.get(0).get("issues")).isNotEmpty();
            sim.fileOperation(participantId,token,new FileOperation("mkdir",folder,null,null));
            sim.fileOperation(participantId,token,new FileOperation("mkdir",folder+"/sum",null,null));
            sim.upload(participantId,token,folder+"/sum",List.of("sum.cpp"),List.of("source".getBytes()),false,null);
            checks=(List<Map<String,Object>>)sim.check(participantId,token).get("problems");
            assertThat((List<?>)checks.get(0).get("issues")).isEmpty();
        }
    }
    @Test @SuppressWarnings("unchecked")
    void teachersCanUseRegionAndRosterNumberOrKeepCustomPaths() {
        var student=sim.addStudent("teacher",new StudentInput("规则学生","训练班","001"));
        CreateExam create=new CreateExam();create.exam.name="自定义路径";create.exam.environment="WINDOWS";
        create.exam.regionCode="hn";create.exam.rootPath="D:/answers";create.exam.folderPattern="{region}-{studentNumber}";
        Problem problem=new Problem();problem.name="求和";problem.directory="sum";problem.sourceName="sum.cpp";create.exam.problems.add(problem);
        create.students=List.of(new Assignment((String)student.get("id"),"CUSTOM-J12345",null));
        var exam=sim.createExam("teacher",create);
        assertThat(((Exam)exam.get("exam")).rootPath).isEqualTo("/D/answers");
        var member=((List<Map<String,Object>>)exam.get("students")).get(0);
        assertThat(member.get("examNumber")).isEqualTo("CUSTOM-J12345");
        assertThat(member.get("folderName")).isEqualTo("HN-001");
    }
    @Test void identityOwnershipAndHiddenDataAreEnforced() throws Exception {
        Fixture f=fixture("LINUX","TEACHING");Fixture other=fixture("LINUX","TEACHING");data(f);
        assertThatThrownBy(()->sim.workspace(f.participantId,other.token)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->sim.teacherExam("another-teacher",f.examId)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        String view=json.writeValueAsString(sim.workspace(f.participantId,f.token));
        assertThat(view).doesNotContain("inputBlob","answerBlob","encryptedCode","leaseToken");
        Map<String,Object> recovered=sim.join(f.examId,new Join("同名学生",f.code));
        assertThat(recovered.get("participationId")).isEqualTo(f.participantId);
        assertThatThrownBy(()->sim.workspace(f.participantId,f.token)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        mvc.perform(get("/api/tools/csp-sim/exams/"+f.examId).with(user("another-teacher"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tools/csp-sim/worker/claim").header("X-CSP-Worker-Key","wrong")).andExpect(status().isForbidden());
        mvc.perform(get("/csp-sim-student.html")).andExpect(status().isOk());
        mvc.perform(get("/csp-sim.html")).andExpect(status().isUnauthorized());
    }
    @Test void windowsOperationsIgnoreCaseButFormalCollectionRequiresExactSpelling() throws Exception {
        Fixture f=fixture("WINDOWS","TEACHING");mkdir(f,"D:/SIM-J00001");mkdir(f,"D:/SIM-J00001/SUM");
        upload(f,"d:/sim-j00001/sum","Sum.cpp","first");
        assertThat(new String(sim.readFile(f.participantId,f.token,"d:/sim-j00001/sum/sum.cpp"))).isEqualTo("first");
        assertThatThrownBy(()->mkdir(f,"D:/sim-j00001/sum")).hasMessageContaining("已存在");
        sim.submit(f.participantId,f.token);sim.close("teacher",f.examId);data(f);
        var batch=sim.grade("teacher",f.examId,null);
        assertThat(json.writeValueAsString(batch)).contains("DIRECTORY_ERROR");
    }
    @Test void snapshotsRemainImmutableAndLastSubmissionIsUsed() {
        Fixture f=fixture("LINUX","TEACHING");correctSource(f);
        sim.submit(f.participantId,f.token);String first=(String)sim.workspace(f.participantId,f.token).get("latestSubmission");
        upload(f,correctFolder(f),"sum.cpp","new source");sim.submit(f.participantId,f.token);
        String last=(String)sim.workspace(f.participantId,f.token).get("latestSubmission");
        assertThat(last).isNotEqualTo(first);
        assertThat(new String(sim.teacherReadFile("teacher",f.examId,f.participantId,first,correctFolder(f)+"/sum.cpp"))).isEqualTo("int main(){return 0;}");
        upload(f,correctFolder(f),"sum.cpp","unsent change");sim.close("teacher",f.examId);
        assertThat(sim.workspace(f.participantId,f.token).get("latestSubmission")).isEqualTo(last);
        assertThat(new String(sim.teacherReadFile("teacher",f.examId,f.participantId,last,correctFolder(f)+"/sum.cpp"))).isEqualTo("new source");
        assertThatThrownBy(()->upload(f,correctFolder(f),"sum.cpp","late")).hasMessageContaining("截止");
    }
    @Test void deadlineAutomaticallyCollectsNonSubmittersAndExamChecksStayHidden() throws Exception {
        Fixture f=fixture("LINUX","EXAM");upload(f,"/home/noi/Desktop","sum.cpp","wrong location");
        assertThatThrownBy(()->sim.check(f.participantId,f.token)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        new TransactionTemplate(transactions).execute(status->{CspRecord record=records.lock(f.examId).orElseThrow();try{Exam e=json.readValue(record.payload,Exam.class);e.deadline=Instant.now().minusSeconds(1);record.payload=json.writeValueAsString(e);records.saveAndFlush(record);}catch(Exception ex){throw new RuntimeException(ex);}return null;});
        sim.expireExams();Map<String,Object> view=sim.workspace(f.participantId,f.token);
        assertThat(view.get("state")).isEqualTo("CLOSED");assertThat((String)view.get("latestSubmission")).isNotBlank();
        assertThat(json.writeValueAsString(sim.check(f.participantId,f.token))).contains("其他位置");
        assertThat(json.writeValueAsString(sim.submissions("teacher",f.examId,f.participantId))).contains("\"automatic\":true");
    }
    @Test void staleWorkspacesAndArchivesFailWithoutChangingStoredFiles() {
        Fixture f=fixture("LINUX","TEACHING");long revision=((Number)sim.workspace(f.participantId,f.token).get("revision")).longValue();
        mkdir(f,"/home/noi/Desktop/test");
        assertThatThrownBy(()->sim.fileOperation(f.participantId,f.token,new FileOperation("mkdir","/home/noi/Desktop/stale",null,revision))).hasMessageContaining("其他窗口");
        assertThatThrownBy(()->sim.uploadData("teacher",f.examId,f.problem.id,CspFiles.zip(Map.of("../bad.in",new byte[1])),false)).hasMessageContaining("不安全");
        assertThatThrownBy(()->sim.uploadData("teacher",f.examId,f.problem.id,CspFiles.zip(Map.of("1.in",new byte[1],"1.out",new byte[1],"1.ans",new byte[1])),false)).hasMessageContaining("唯一配对");
    }
    @Test void uploadPreservesCommasInFileNames() throws Exception {
        Fixture f=fixture("LINUX","TEACHING");
        mvc.perform(multipart("/api/tools/csp-sim/student/"+f.participantId+"/upload").file(new MockMultipartFile("files","a,b.txt","text/plain","content".getBytes()))
                .param("path","/home/noi/Desktop").param("paths","a,b.txt").param("revision",sim.workspace(f.participantId,f.token).get("revision").toString()).header("X-CSP-Token",f.token)).andExpect(status().isOk());
        assertThat(new String(sim.readFile(f.participantId,f.token,"/home/noi/Desktop/a,b.txt"))).isEqualTo("content");
    }
    @SuppressWarnings("unchecked")
    @Test void leasedTasksRecoverAndSystemErrorsCannotBePublished() throws Exception {
        Fixture f=fixture("LINUX","TEACHING");correctSource(f);sim.close("teacher",f.examId);data(f);
        String batchId=(String)sim.grade("teacher",f.examId,null).get("id");
        var claim=sim.claim(KEY);String taskId=(String)claim.get("id");Task initial=(Task)claim.get("task");
        assertThatThrownBy(()->sim.workerBlob(KEY,taskId,initial.leaseToken,UUID.randomUUID().toString())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        new TransactionTemplate(transactions).execute(status->{CspRecord r=records.lock(taskId).orElseThrow();r.leaseUntil=Instant.now().minusSeconds(1);records.saveAndFlush(r);return null;});
        Task renewed=(Task)sim.claim(KEY).get("task");assertThat(renewed.attempts).isEqualTo(2);assertThat(renewed.leaseToken).isNotEqualTo(initial.leaseToken);
        Result result=new Result();result.verdict="SYSTEM_ERROR";result.message="docker unavailable";
        assertThatThrownBy(()->sim.complete(KEY,taskId,new Complete(initial.leaseToken,result))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        sim.complete(KEY,taskId,new Complete(renewed.leaseToken,result));
        assertThatThrownBy(()->sim.publish("teacher",f.examId,batchId)).hasMessageContaining("系统故障");
        sim.retry("teacher",f.examId,batchId);Task retry=(Task)sim.claim(KEY).get("task");
        Result passed=new Result();passed.verdict="AC";passed.score=new BigDecimal("999");CaseResult cr=new CaseResult();cr.id="data/1";cr.verdict="AC";passed.cases=List.of(cr);
        sim.complete(KEY,taskId,new Complete(retry.leaseToken,passed));sim.publish("teacher",f.examId,batchId);
        String view=json.writeValueAsString(sim.workspace(f.participantId,f.token));assertThat(view).contains("\"score\":100").doesNotContain("999");
        sim.grade("teacher",f.examId,new GradeRequest(Map.of(f.participantId,Map.of(f.problem.id,correctFolder(f)+"/sum.cpp"))));
        assertThat(json.writeValueAsString(sim.history(f.participantId,f.token))).contains("results");
    }
    @Test void csvImportIsAtomicAndDuplicateNamesAreAllowed() {
        assertThat(sim.importStudents("teacher","\uFEFF姓名,班级,学号\r\n\"张,三\",一班,001\r\n\"张,三\",二班,002\r\n".getBytes(StandardCharsets.UTF_8))).hasSize(2);
        assertThatThrownBy(()->sim.importStudents("teacher","姓名,学号\n李四,003\n王五,001\n".getBytes(StandardCharsets.UTF_8))).hasMessageContaining("已存在");
        assertThat(sim.students("teacher")).hasSize(2);
    }
    @Test void sixDigitExamCodeAndStudentNumberIdentifyTheCorrectWorkspace() {
        Fixture first=fixture("LINUX","TEACHING"), second=fixture("WINDOWS","TEACHING");
        String code=(String)sim.entryCode(first.examId).get("examCode");
        assertThat(code).matches("[0-9]{6}").isNotEqualTo(sim.entryCode(second.examId).get("examCode"));
        var joined=sim.join(code,new Join("同名学生",first.code));
        assertThat(joined.get("participationId")).isEqualTo(first.participantId);
        assertThat(joined.get("examCode")).isEqualTo(code);
        assertThat(first.code).isEqualTo("id1");
        assertThatThrownBy(()->sim.join(code,new Join("同名学生",second.code))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->sim.join(code,new Join("错误姓名",first.code))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test @SuppressWarnings("unchecked")
    void existingDraftCanRegenerateNumbersFromStudentIdsButOpenExamsCannot() {
        var student=sim.addStudent("teacher",new StudentInput("训练学生","训练班","10002"));
        CreateExam create=new CreateExam();create.exam.name="旧考号更新";Problem problem=new Problem();problem.name="求和";problem.directory="sum";problem.sourceName="sum.cpp";create.exam.problems.add(problem);
        create.students=List.of(new Assignment((String)student.get("id"),"GD-J00001","GD-J00001"));
        var before=sim.createExam("teacher",create);String id=(String)before.get("id"),code=((Exam)before.get("exam")).joinCode;
        var updated=sim.updateAdmissionNumbers("teacher",id);
        var member=((List<Map<String,Object>>)updated.get("students")).get(0);
        assertThat(member.get("examNumber")).isEqualTo("GD-J10002");
        assertThat(member.get("folderName")).isEqualTo("GD-J10002");
        assertThat(((Exam)updated.get("exam")).joinCode).isEqualTo(code);
        assertThatThrownBy(()->sim.updateAdmissionNumbers("other-teacher",id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        sim.start("teacher",id);
        assertThatThrownBy(()->sim.updateAdmissionNumbers("teacher",id)).hasMessageContaining("开考前");
    }
    @Test void legacyExamsReceiveStableShortCodesWithoutLosingSavedFiles() {
        Fixture f=fixture("LINUX","TEACHING");upload(f,"/home/noi/Desktop","sum.cpp","keep me");
        new TransactionTemplate(transactions).execute(status->{CspRecord r=records.lock(f.examId).orElseThrow();try{Exam e=json.readValue(r.payload,Exam.class);e.joinCode=null;r.uniqueKey=null;r.payload=json.writeValueAsString(e);records.saveAndFlush(r);}catch(Exception ex){throw new RuntimeException(ex);}return null;});
        String code=(String)sim.exams("teacher").get(0).get("joinCode");
        assertThat(code).matches("[0-9]{6}");
        assertThat(sim.entryCode(f.examId).get("examCode")).isEqualTo(code);
        assertThat(new String(sim.readFile(f.participantId,f.token,"/home/noi/Desktop/sum.cpp"))).isEqualTo("keep me");
        assertThat(sim.join(code,new Join("同名学生",f.code)).get("participationId")).isEqualTo(f.participantId);
    }
    @Test void leadingZeroStudentNumbersAndSessionRevocationWork() {
        var student=sim.addStudent("teacher",new StudentInput("零号学生","训练班","000123"));
        CreateExam create=new CreateExam();create.exam.name="学号进入";Problem problem=new Problem();problem.name="求和";problem.directory="sum";problem.sourceName="sum.cpp";create.exam.problems.add(problem);
        String studentId=(String)student.get("id");create.students=List.of(new Assignment(studentId,null,null));
        var exam=sim.createExam("teacher",create);String code=((Exam)exam.get("exam")).joinCode;
        var joined=sim.join(code,new Join("零号学生","000123"));String participantId=(String)joined.get("participationId"),token=(String)joined.get("token");
        assertThatThrownBy(()->sim.join(code,new Join("零号学生","123"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        sim.revokeStudentSessions("teacher",studentId);
        assertThatThrownBy(()->sim.workspace(participantId,token)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        String previousToken=(String)sim.join(code,new Join("零号学生","000123")).get("token");
        sim.updateStudent("teacher",studentId,new UpdateStudent(new StudentInput("零号学生","训练班","000456"),true));
        assertThatThrownBy(()->sim.workspace(participantId,previousToken)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->sim.join(code,new Join("零号学生","000123"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(sim.join(code,new Join("零号学生","000456")).get("participationId")).isEqualTo(participantId);
    }
    @SuppressWarnings("unchecked")
    @Test void sameNameStudentsInOneExamReceiveIndependentSessionsAndTiedRanks() {
        var first=sim.addStudent("teacher",new StudentInput("张三","一班","01"));
        var second=sim.addStudent("teacher",new StudentInput("张三","二班","02"));
        CreateExam create=new CreateExam();create.exam.name="重名与同分";Problem problem=new Problem();problem.name="求和";problem.directory="sum";problem.sourceName="sum.cpp";create.exam.problems.add(problem);
        create.students=List.of(new Assignment((String)first.get("id"),null,null),new Assignment((String)second.get("id"),null,null));
        String examId=(String)sim.createExam("teacher",create).get("id");sim.start("teacher",examId);
        var a=sim.join(examId,new Join("张三",(String)first.get("code")));var b=sim.join(examId,new Join("张三",(String)second.get("code")));
        assertThat(a.get("participationId")).isNotEqualTo(b.get("participationId"));
        String aid=(String)a.get("participationId"),token=(String)a.get("token");
        sim.upload(aid,token,"/home/noi/Desktop",List.of("sum.cpp"),List.of("source".getBytes()),false,null);
        sim.close("teacher",examId);
        Fixture f=new Fixture(examId,aid,(String)first.get("id"),(String)first.get("code"),token,problem);data(f);
        String batch=(String)sim.grade("teacher",examId,null).get("id");sim.publish("teacher",examId,batch);
        var results=(Map<String,Object>)sim.workspace(aid,token).get("results");
        assertThat((List<?>)results.get("tasks")).hasSize(1);
        assertThat((List<Map<String,Object>>)results.get("leaderboard")).hasSize(2).allSatisfy(row->assertThat(row.get("rank")).isEqualTo(1));
        sim.leave(aid,token);assertThatThrownBy(()->sim.workspace(aid,token)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void exportedSourcesPreserveOriginalPathsAndSubmissionManifest() throws Exception {
        Fixture f=fixture("LINUX","TEACHING");upload(f,"/home/noi/Desktop","sum.cpp","misplaced source");sim.submit(f.participantId,f.token);
        var output=new java.io.ByteArrayOutputStream();sim.exportSources("teacher",f.examId,output);
        var unpacked=CspFiles.unzip(output.toByteArray());
        assertThat(unpacked).containsKeys(f.participantId+"/manifest.json",f.participantId+"/files/home/noi/Desktop/sum.cpp");
        assertThat(new String(unpacked.get(f.participantId+"/files/home/noi/Desktop/sum.cpp"))).isEqualTo("misplaced source");
    }
    @SuppressWarnings("unchecked")
    @Test void sixtyIndependentStudentSessionsCanJoinUploadAndSubmitConcurrently() throws Exception {
        List<Map<String,Object>> roster=new ArrayList<>();CreateExam create=new CreateExam();create.exam.name="60人验收";
        Problem p=new Problem();p.name="测试";p.directory="test";p.sourceName="test.cpp";create.exam.problems.add(p);
        for(int i=0;i<60;i++){var student=sim.addStudent("teacher",new StudentInput("学生"+i,"一班","n"+i));roster.add(student);create.students.add(new Assignment((String)student.get("id"),null,null));}
        String examId=(String)sim.createExam("teacher",create).get("id");sim.start("teacher",examId);
        ExecutorService executor=Executors.newFixedThreadPool(12);
        try {
            List<Callable<Void>> actions=new ArrayList<>();
            for(var student:roster)actions.add(()->{
                var response=mvc.perform(post("/api/tools/csp-sim/join/"+examId).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Join((String)student.get("name"),(String)student.get("code"))))).andExpect(status().isOk()).andReturn().getResponse();
                var joined=json.readValue(response.getContentAsString(),Map.class);String id=(String)joined.get("participationId"),token=(String)joined.get("token");
                var workspace=sim.workspace(id,token);String path="/home/noi/Desktop/answer";
                mvc.perform(post("/api/tools/csp-sim/student/"+id+"/files").header("X-CSP-Token",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new FileOperation("mkdir",path,null,((Number)workspace.get("revision")).longValue())))).andExpect(status().isOk());
                workspace=sim.workspace(id,token);
                mvc.perform(multipart("/api/tools/csp-sim/student/"+id+"/upload").file(new MockMultipartFile("files","test.cpp","text/plain","int main(){}".getBytes())).param("path",path).param("paths","test.cpp").param("revision",workspace.get("revision").toString()).header("X-CSP-Token",token)).andExpect(status().isOk());
                mvc.perform(post("/api/tools/csp-sim/student/"+id+"/submit").header("X-CSP-Token",token)).andExpect(status().isOk());return null;
            });
            for(Future<Void> future:executor.invokeAll(actions))future.get(30,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
        var members=(List<Map<String,Object>>)sim.teacherExam("teacher",examId).get("students");
        assertThat(members).hasSize(60).allSatisfy(s->{assertThat(s.get("joined")).isEqualTo(true);assertThat((String)s.get("latestSubmission")).isNotBlank();assertThat(s.get("fileCount")).isEqualTo(1L);});
    }
}
