package cn.datacraft.web;

import cn.datacraft.ai.AiConfigService;
import cn.datacraft.job.*;
import cn.datacraft.user.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final UserService users;
    private final JobService jobs;
    private final AiConfigService aiConfig;
    public AdminController(UserService users, JobService jobs, AiConfigService aiConfig) {
        this.users = users; this.jobs = jobs; this.aiConfig = aiConfig;
    }

    @GetMapping("/users")
    public List<UserAccount> users() { return users.all(); }

    @PatchMapping("/users/{id}")
    public UserAccount setUserEnabled(@PathVariable Long id, @RequestBody UserStateRequest request) {
        return users.update(id, request.enabled, request.dailyGenerationLimit);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        return jobs.recentAll().stream().map(job -> {
            Map<String, Object> row = new LinkedHashMap<>();
            GenerationRequest request = job.getRequest();
            row.put("id", job.getId()); row.put("userId", job.getUserId()); row.put("status", job.getStatus());
            row.put("progress", job.getProgress()); row.put("message", job.getMessage()); row.put("error", job.getError());
            row.put("caseCount", request.getCaseCount()); row.put("createdAt", job.getCreatedAt());
            row.put("cppStandard", request.getCppStandard());
            row.put("statement", request.getStatement());
            row.put("standardCode", request.getStandardCode());
            row.put("requirements", request.getRequirements());
            row.put("downloadReady", job.isDownloadReady());
            if (job.getPlan() != null) {
                row.put("planSummary", job.getPlan().getSummary());
                row.put("planEstimatedSize", job.getPlan().getEstimatedSize());
                row.put("planGroups", job.getPlan().getGroups());
                row.put("planAiGenerated", job.getPlan().isAiGenerated());
            }
            String statement = request.getStatement();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^#\\s+(.+)$").matcher(statement);
            row.put("title", matcher.find() ? matcher.group(1) : "未命名任务");
            return row;
        }).collect(Collectors.toList());
    }

    @GetMapping("/ai-config")
    public AiConfigService.SettingsView getAiConfig() { return aiConfig.view(); }

    @PutMapping("/ai-config")
    public AiConfigService.SettingsView updateAiConfig(@RequestBody AiConfigRequest request) {
        return aiConfig.update(request.baseUrl, request.model, request.apiKey, request.dailyGenerationLimit);
    }

    public static class UserStateRequest { public Boolean enabled; public Integer dailyGenerationLimit; }
    public static class AiConfigRequest { public String baseUrl, model, apiKey; public Integer dailyGenerationLimit; }
}
