package cn.datacraft.web;

import cn.datacraft.cspsim.*;
import cn.datacraft.cspsim.CspTypes.*;
import cn.datacraft.job.*;
import cn.datacraft.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.security.access.AccessDeniedException;
import java.io.IOException;
import java.nio.file.Files;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/tools/csp-sim")
public class CspSimController {
    private final CspSimService sim;
    private final JobService jobs;
    private final UserService users;
    private final Map<String, long[]> joinLimits = new LinkedHashMap<>();
    public CspSimController(CspSimService sim, JobService jobs, UserService users) { this.sim = sim; this.jobs = jobs; this.users = users; }
    private static String owner(Principal principal) { if (principal == null) throw new AccessDeniedException("请先登录教师账号"); return principal.getName(); }
    @GetMapping("/students") public Object students(Principal p) { return sim.students(owner(p)); }
    @PostMapping("/students") public Object add(Principal p,@RequestBody StudentInput input) { return sim.addStudent(owner(p),input); }
    @PutMapping("/students/{id}") public Object edit(Principal p,@PathVariable String id,@RequestBody UpdateStudent input) { return sim.updateStudent(owner(p),id,input); }
    @PostMapping("/students/import") public Object importStudents(Principal p,@RequestParam MultipartFile file) throws IOException { return sim.importStudents(owner(p),file.getBytes()); }
    @PostMapping({"/students/{id}/revoke-sessions", "/students/{id}/reset-code"}) public Object revoke(Principal p,@PathVariable String id) { return sim.revokeStudentSessions(owner(p),id); }
    @GetMapping("/students/export") public ResponseEntity<byte[]> roster(Principal p) { return download(sim.rosterCsv(owner(p)),"students.csv","text/csv;charset=UTF-8"); }
    @GetMapping("/exams") public Object exams(Principal p) { return sim.exams(owner(p)); }
    @PostMapping("/exams") public Object create(Principal p,@RequestBody CreateExam input) { return sim.createExam(owner(p),input); }
    @PutMapping("/exams/{id}") public Object editExam(Principal p,@PathVariable String id,@RequestBody CreateExam input) { return sim.editExam(owner(p),id,input); }
    @PostMapping("/exams/{id}/restart") public Object restart(Principal p,@PathVariable String id) { return sim.restart(owner(p),id); }
    @PostMapping("/exams/{id}/admission-numbers") public Object admissionNumbers(Principal p,@PathVariable String id) { return sim.updateAdmissionNumbers(owner(p),id); }
    @GetMapping("/exams/{id}") public Object exam(Principal p,@PathVariable String id) { return sim.teacherExam(owner(p),id); }
    @PostMapping("/exams/{id}/start") public Object start(Principal p,@PathVariable String id) { return sim.start(owner(p),id); }
    @PostMapping("/exams/{id}/close") public Object close(Principal p,@PathVariable String id) { return sim.close(owner(p),id); }
    @GetMapping("/exams/{id}/students/{studentId}/submissions") public Object submissions(Principal p,@PathVariable String id,@PathVariable String studentId) { return sim.submissions(owner(p),id,studentId); }
    @GetMapping("/exams/{id}/students/{studentId}/file") public ResponseEntity<byte[]> teacherFile(Principal p,@PathVariable String id,@PathVariable String studentId,@RequestParam(required=false) String submissionId,@RequestParam String path) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore()).body(sim.teacherReadFile(owner(p),id,studentId,submissionId,path)); }
    @PostMapping("/exams/{id}/problems/{problemId}/data") public Object data(Principal p,@PathVariable String id,@PathVariable String problemId,@RequestParam MultipartFile file,@RequestParam(defaultValue="false") boolean samples) throws IOException { try (var input=file.getInputStream()) {return sim.uploadDataZip(owner(p),id,problemId,input,file.getSize(),samples);} }
    @PostMapping("/exams/{id}/problems/{problemId}/data-files") public Object dataFiles(Principal p,@PathVariable String id,@PathVariable String problemId,@RequestParam("files") List<MultipartFile> uploads,@RequestParam(defaultValue="false") boolean samples) throws IOException {
        return sim.uploadDataStreams(owner(p),id,problemId,uploads.stream()
                .map(file->new CspFiles.DataUpload(file.getOriginalFilename(),file.getSize(),file::getInputStream)).toList(),samples);
    }
    @GetMapping("/exams/{id}/problems/{problemId}/data-files") public Object listData(Principal p,@PathVariable String id,@PathVariable String problemId,@RequestParam(defaultValue="false") boolean samples) {return sim.dataFiles(owner(p),id,problemId,samples);}
    @GetMapping("/exams/{id}/problems/{problemId}/data-file") public ResponseEntity<org.springframework.core.io.Resource> readData(Principal p,@PathVariable String id,@PathVariable String problemId,@RequestParam String caseId,@RequestParam String kind,@RequestParam(defaultValue="false") boolean samples,@RequestParam(defaultValue="false") boolean download) throws IOException {
        var data=sim.dataResource(owner(p),id,problemId,caseId,kind,samples,download);
        String filename=CspFiles.base(caseId)+(kind.equals("input")?".in":".out");
        return download ? ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(data.contentLength()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(data)
                : ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).contentLength(data.contentLength()).cacheControl(CacheControl.noStore()).body(data);
    }
    public record GeneratedData(String jobId) {}
    @PostMapping("/exams/{id}/problems/{problemId}/generated-data") public Object generated(Principal p,@PathVariable String id,@PathVariable String problemId,@RequestBody GeneratedData input) throws IOException {
        GenerationJob job = jobs.requireOwned(UUID.fromString(input.jobId()),users.requireByUsername(owner(p)).getId());
        if (!job.isDownloadReady()) throw new IllegalStateException("数据包尚未生成完成");
        try (var stream=Files.newInputStream(job.getArtifact())) {return sim.uploadDataZip(owner(p),id,problemId,stream,Files.size(job.getArtifact()),false);}
    }
    @PutMapping("/exams/{id}/problems/{problemId}/data-config") public Object config(Principal p,@PathVariable String id,@PathVariable String problemId,@RequestBody DataConfig input) { return sim.configureData(owner(p),id,problemId,input); }
    @PostMapping("/exams/{id}/grade") public Object grade(Principal p,@PathVariable String id,@RequestBody(required=false) GradeRequest input) { return sim.grade(owner(p),id,input); }
    @GetMapping("/exams/{id}/batches/{batchId}") public Object batch(Principal p,@PathVariable String id,@PathVariable String batchId) { return sim.teacherBatch(owner(p),id,batchId); }
    @PostMapping("/exams/{id}/batches/{batchId}/publish") public Object publish(Principal p,@PathVariable String id,@PathVariable String batchId) { return sim.publish(owner(p),id,batchId); }
    @PostMapping("/exams/{id}/batches/{batchId}/retry") public Object retry(Principal p,@PathVariable String id,@PathVariable String batchId) { return sim.retry(owner(p),id,batchId); }
    @GetMapping("/exams/{id}/batches/{batchId}/export") public ResponseEntity<byte[]> export(Principal p,@PathVariable String id,@PathVariable String batchId) { return download(sim.scoresCsv(owner(p),id,batchId),"scores.csv","text/csv;charset=UTF-8"); }
    @GetMapping("/exams/{id}/sources") public ResponseEntity<StreamingResponseBody> sources(Principal p,@PathVariable String id) {
        String teacher=owner(p); sim.teacherExam(teacher,id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=sources.zip").body(output -> sim.exportSources(teacher,id,output));
    }
    @PostMapping("/join/{id}") public Object join(@PathVariable String id,@RequestBody Join input,HttpServletRequest request) {
        rateLimit(request.getRemoteAddr()); return sim.join(id,input);
    }
    @GetMapping("/join/{id}") public Object entry(@PathVariable String id,HttpServletRequest request) {
        rateLimit(request.getRemoteAddr()); return sim.entryCode(id);
    }
    private synchronized void rateLimit(String ip) {
        long now = System.currentTimeMillis(); joinLimits.entrySet().removeIf(e -> now-e.getValue()[0] > 300000);
        if (joinLimits.size() >= 10000 && !joinLimits.containsKey(ip)) throw new AccessDeniedException("请稍后重试");
        long[] count = joinLimits.computeIfAbsent(ip,k -> new long[]{now,0});
        if (++count[1] > 180) throw new AccessDeniedException("尝试次数过多，请五分钟后重试");
    }
    @GetMapping("/student/{id}") public Object workspace(@PathVariable String id,@RequestHeader("X-CSP-Token") String token) { return sim.workspace(id,token); }
    @PostMapping("/student/{id}/leave") public void leave(@PathVariable String id,@RequestHeader("X-CSP-Token") String token) { sim.leave(id,token); }
    @PostMapping("/student/{id}/files") public Object operation(@PathVariable String id,@RequestHeader("X-CSP-Token") String token,@RequestBody FileOperation input) { return sim.fileOperation(id,token,input); }
    @PostMapping("/student/{id}/upload") public Object upload(@PathVariable String id,@RequestHeader("X-CSP-Token") String token,@RequestParam String path,@RequestParam("files") List<MultipartFile> uploads,@RequestParam(defaultValue="false") boolean replace,@RequestParam long revision,HttpServletRequest request) throws IOException {
        String[] names=request.getParameterValues("paths");
        if (uploads.size()>200 || uploads.stream().mapToLong(MultipartFile::getSize).sum()>CspFiles.MAX_WORKSPACE || uploads.stream().anyMatch(file->file.getSize()>CspFiles.MAX_FILE)) throw new IllegalArgumentException("学生文件最多2MB，一次最多200个文件、25MB");
        List<byte[]> bytes = new ArrayList<>(); for (MultipartFile file : uploads) bytes.add(file.getBytes()); return sim.upload(id,token,path,names == null ? List.of() : Arrays.asList(names),bytes,replace,revision);
    }
    @GetMapping("/student/{id}/file") public ResponseEntity<byte[]> file(@PathVariable String id,@RequestHeader("X-CSP-Token") String token,@RequestParam String path) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore()).body(sim.readFile(id,token,path)); }
    @PostMapping("/student/{id}/submit") public Object submit(@PathVariable String id,@RequestHeader("X-CSP-Token") String token) { return sim.submit(id,token); }
    @GetMapping("/student/{id}/check") public Object check(@PathVariable String id,@RequestHeader("X-CSP-Token") String token) { return sim.check(id,token); }
    @GetMapping("/student/{id}/history") public Object history(@PathVariable String id,@RequestHeader("X-CSP-Token") String token) { return sim.history(id,token); }
    @PostMapping("/worker/claim") public Object claim(@RequestHeader("X-CSP-Worker-Key") String key) { return sim.claim(key); }
    public record Lease(String leaseToken) {}
    @PostMapping("/worker/tasks/{id}/heartbeat") public void heartbeat(@RequestHeader("X-CSP-Worker-Key") String key,@PathVariable String id,@RequestBody Lease input) { sim.heartbeat(key,id,input.leaseToken()); }
    @GetMapping("/worker/tasks/{id}/blobs/{blob}") public ResponseEntity<byte[]> blob(@RequestHeader("X-CSP-Worker-Key") String key,@RequestHeader("X-CSP-Lease") String lease,@PathVariable String id,@PathVariable String blob) { return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore()).body(sim.workerBlob(key,id,lease,blob)); }
    @PostMapping("/worker/tasks/{id}/complete") public void complete(@RequestHeader("X-CSP-Worker-Key") String key,@PathVariable String id,@RequestBody Complete input) { sim.complete(key,id,input); }
    private static ResponseEntity<byte[]> download(byte[] bytes,String filename,String type) { return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+filename).body(bytes); }
}
