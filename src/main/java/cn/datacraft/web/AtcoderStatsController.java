package cn.datacraft.web;

import cn.datacraft.tools.AtcoderStatsResponse;
import cn.datacraft.tools.AtcoderStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools/atcoder")
public class AtcoderStatsController {
    private final AtcoderStatsService stats;

    public AtcoderStatsController(AtcoderStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/{username}")
    public AtcoderStatsResponse recent(@PathVariable String username) {
        return stats.fetchRecentStats(username);
    }
}
