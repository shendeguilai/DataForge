package cn.datacraft.web;

import cn.datacraft.atcoder.AtcoderLeaderboardDtos.LeaderboardView;
import cn.datacraft.atcoder.AtcoderLeaderboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools/atcoder-leaderboard")
public class AtcoderLeaderboardController {
    private final AtcoderLeaderboardService leaderboard;

    public AtcoderLeaderboardController(AtcoderLeaderboardService leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping
    public LeaderboardView current(@RequestParam(required = false) String contestId) {
        return leaderboard.currentLeaderboard(contestId);
    }

    @PostMapping("/refresh")
    public LeaderboardView refresh(@RequestParam(required = false) String contestId) {
        return leaderboard.manualRefresh(contestId);
    }
}
