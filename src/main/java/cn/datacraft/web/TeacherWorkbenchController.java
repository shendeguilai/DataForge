package cn.datacraft.web;

import cn.datacraft.teaching.TeacherWorkbench;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
public class TeacherWorkbenchController {
    private final TeacherWorkbench service;
    public TeacherWorkbenchController(TeacherWorkbench service) { this.service=service; }
    @GetMapping("/workbench") public Map<String,Object> dashboard(Principal p) { return service.dashboard(p.getName()); }
    @GetMapping("/learning") public Map<String,Object> learning(Principal p) { return service.learning(p.getName()); }
    @PutMapping("/learning/settings") public Object preferences(Principal p,@RequestBody TeacherWorkbench.Preferences input) { return service.savePreferences(p.getName(),input); }
}
