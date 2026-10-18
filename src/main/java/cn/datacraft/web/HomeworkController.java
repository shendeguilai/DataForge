package cn.datacraft.web;

import cn.datacraft.homework.*;
import cn.datacraft.homework.HomeworkTypes.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/tools/kedaoj-homework")
public class HomeworkController {
    private final HomeworkService service;
    public HomeworkController(HomeworkService service) { this.service = service; }
    private String owner(Principal p) { if (p == null) throw new AccessDeniedException("请先登录教师账号"); return p.getName(); }
    @ModelAttribute public void noCache(org.springframework.web.context.request.WebRequest request,
                                       jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/settings") public SettingsView settings(Principal p) { return service.settings(owner(p)); }
    @PutMapping("/settings") public SettingsView settings(Principal p, @RequestBody SettingsInput input) { return service.saveSettings(owner(p), input); }
    @PostMapping("/settings/verify") public SettingsView verify(Principal p) { return service.verifyConnection(owner(p)); }
    @DeleteMapping("/settings/credential") public SettingsView clear(Principal p) { return service.clearCredential(owner(p)); }
    @GetMapping("/classes") public List<ClassView> classes(Principal p) { return service.classes(owner(p)); }
    @PostMapping("/classes") @ResponseStatus(HttpStatus.CREATED)
    public ClassView addClass(Principal p, @RequestBody ClassInput input) { return service.saveClass(owner(p), null, input); }
    @PutMapping("/classes/{id}") public ClassView editClass(Principal p, @PathVariable String id, @RequestBody ClassInput input) { return service.saveClass(owner(p), id, input); }
    @PostMapping("/students/preview") public List<Student> preview(Principal p, @RequestBody List<StudentInput> input) { return service.previewStudents(owner(p), input); }
    @PostMapping("/classes/{id}/students") public ClassView addStudents(Principal p, @PathVariable String id, @RequestBody List<StudentInput> input) { return service.addStudents(owner(p), id, input); }
    @PutMapping("/classes/{id}/students/{studentId}") public ClassView editStudent(Principal p, @PathVariable String id, @PathVariable String studentId, @RequestBody StudentInput input) { return service.editStudent(owner(p), id, studentId, input); }
    @DeleteMapping("/classes/{id}/students/{studentId}") public ClassView removeStudent(Principal p, @PathVariable String id, @PathVariable String studentId) { return service.removeStudent(owner(p), id, studentId); }
    @GetMapping("/assignments") public List<AssignmentView> assignments(Principal p) { return service.assignments(owner(p)); }
    @PostMapping("/assignments") @ResponseStatus(HttpStatus.CREATED)
    public AssignmentView create(Principal p, @RequestBody CreateAssignment input) { return service.createAssignment(owner(p), input); }
    @PutMapping("/assignments/{id}") public AssignmentView edit(Principal p, @PathVariable String id,
            @RequestParam long revision, @RequestBody CreateAssignment input) { return service.editAssignment(owner(p), id, input, revision); }
    @PostMapping("/assignments/{id}/copy") @ResponseStatus(HttpStatus.CREATED)
    public AssignmentView copy(Principal p, @PathVariable String id, @RequestBody CreateAssignment input) { return service.copyAssignment(owner(p), id, input); }
    @GetMapping("/assignments/{id}/incomplete") public IncompleteReport incomplete(Principal p, @PathVariable String id) { return service.incomplete(owner(p), id); }
    @GetMapping("/students/{uid}/overview") public StudentOverview student(Principal p, @PathVariable long uid) { return service.studentOverview(owner(p), uid); }
    @GetMapping("/assignments/{id}/export.xlsx") public ResponseEntity<byte[]> export(Principal p, @PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("作业完成情况.xlsx", java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(service.exportExcel(owner(p), id));
    }
    @GetMapping("/assignments/{id}") public AssignmentView assignment(Principal p, @PathVariable String id) { return service.assignment(owner(p), id); }
    @PostMapping("/assignments/{id}/refresh") @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView refresh(Principal p, @PathVariable String id, @RequestParam(defaultValue = "false") boolean full) { return service.refresh(owner(p), id, full, false); }
    @PostMapping("/assignments/{id}/retry-failed") @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView retry(Principal p, @PathVariable String id) { return service.refresh(owner(p), id, false, true); }
    @PostMapping("/assignments/{id}/archive") public AssignmentView archive(Principal p, @PathVariable String id) { return service.archive(owner(p), id, true); }
    @PostMapping("/assignments/{id}/restore") public AssignmentView restore(Principal p, @PathVariable String id) { return service.archive(owner(p), id, false); }
    @GetMapping("/tasks/active") public ResponseEntity<TaskView> active(Principal p) {
        TaskView task = service.activeTask(owner(p));
        return task == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(task);
    }
    @GetMapping("/tasks/{id}") public TaskView task(Principal p, @PathVariable String id) { return service.task(owner(p), id); }
}
