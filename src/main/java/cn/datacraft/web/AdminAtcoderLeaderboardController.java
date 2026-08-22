package cn.datacraft.web;

import cn.datacraft.atcoder.AtcoderLeaderboardDtos.AdminConfigView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.ConfigRequest;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.CookieRequest;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.BulkParticipantRequest;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.ParticipantRequest;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.ParticipantView;
import cn.datacraft.atcoder.AtcoderLeaderboardService;
import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemOverviewView;
import cn.datacraft.atcoder.AtcoderProblemDtos.AdminProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemTranslationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/atcoder-leaderboard")
public class AdminAtcoderLeaderboardController {
    private final AtcoderLeaderboardService leaderboard;
    private final AtcoderProblemTranslationService translations;

    public AdminAtcoderLeaderboardController(AtcoderLeaderboardService leaderboard,
                                             AtcoderProblemTranslationService translations) {
        this.leaderboard = leaderboard;
        this.translations = translations;
    }

    @GetMapping("/config")
    public AdminConfigView config() {
        return leaderboard.getConfig();
    }

    @PutMapping("/config")
    public AdminConfigView saveConfig(@RequestBody ConfigRequest request) {
        return leaderboard.saveConfig(request.contestId, request.displayTitle);
    }

    @PutMapping("/cookie")
    public AdminConfigView updateCookie(@RequestBody CookieRequest request) {
        return leaderboard.updateCookie(request.cookie);
    }

    @DeleteMapping("/cookie")
    public AdminConfigView clearManagedCookie() {
        return leaderboard.clearManagedCookie();
    }

    @GetMapping("/participants")
    public List<ParticipantView> participants() {
        return leaderboard.getParticipants();
    }

    @PostMapping("/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantView addParticipant(@RequestBody ParticipantRequest request) {
        return leaderboard.addParticipant(request.displayName, request.atcoderUsername);
    }

    @PostMapping("/participants/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ParticipantView> addParticipants(@RequestBody List<BulkParticipantRequest> requests) {
        return leaderboard.addParticipants(requests);
    }

    @PutMapping("/participants/{id}")
    public ParticipantView updateParticipant(@PathVariable Long id, @RequestBody ParticipantRequest request) {
        return leaderboard.updateParticipant(id, request.displayName, request.atcoderUsername);
    }

    @DeleteMapping("/participants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteParticipant(@PathVariable Long id) {
        leaderboard.deleteParticipant(id);
    }

    @GetMapping("/translations")
    public ProblemOverviewView translations() {
        return translations.adminOverview();
    }

    @PostMapping("/translations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProblemOverviewView translateAll() {
        return translations.startAll(false);
    }

    @PostMapping("/translations/retranslate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProblemOverviewView retranslateAll() {
        return translations.startAll(true);
    }

    @PostMapping("/translations/{taskId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProblemOverviewView retryTranslation(@PathVariable String taskId) {
        return translations.retryTask(taskId);
    }

    @GetMapping("/translations/{taskId}")
    public AdminProblemDetailView translationDetail(@PathVariable String taskId) {
        return translations.adminDetail(taskId);
    }

    @PutMapping("/translations/{taskId}")
    public AdminProblemDetailView saveTranslation(@PathVariable String taskId,
                                                   @RequestBody TranslationEditRequest request) {
        return translations.saveManualTranslation(taskId, request.translatedHtml);
    }

    @PutMapping("/translations/{taskId}/manual")
    public AdminProblemDetailView importManualTranslation(@PathVariable String taskId,
                                                          @RequestBody ManualTranslationRequest request) {
        return translations.saveStructuredManualTranslation(taskId, request.content);
    }

    public static class TranslationEditRequest {
        public String translatedHtml;
    }

    public static class ManualTranslationRequest {
        public String content;
    }
}
