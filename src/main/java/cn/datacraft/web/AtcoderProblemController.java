package cn.datacraft.web;

import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemOverviewView;
import cn.datacraft.atcoder.AtcoderProblemTranslationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools/atcoder-problems")
public class AtcoderProblemController {
    private final AtcoderProblemTranslationService translations;

    public AtcoderProblemController(AtcoderProblemTranslationService translations) {
        this.translations = translations;
    }

    @GetMapping
    public ProblemOverviewView overview() {
        return translations.publicOverview();
    }

    @GetMapping("/{taskId}")
    public ProblemDetailView detail(@PathVariable String taskId) {
        return translations.detail(taskId);
    }
}
