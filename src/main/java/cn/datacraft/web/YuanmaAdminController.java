package cn.datacraft.web;

import cn.datacraft.yuanma.YuanmaHeartbeatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/yuanma-heartbeat")
public class YuanmaAdminController {
    private final YuanmaHeartbeatService service;
    public YuanmaAdminController(YuanmaHeartbeatService service) { this.service = service; }
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping public YuanmaHeartbeatService.View settings() { return service.settings(); }
    @PutMapping public YuanmaHeartbeatService.View save(@RequestBody YuanmaHeartbeatService.Input input, HttpServletRequest request) {
        if (input.credential() != null && !input.credential().isEmpty() && !secureOrLocal(request))
            throw new IllegalArgumentException("请使用 HTTPS 或服务器本地终端安全录入 Token");
        return service.save(input);
    }
    @PostMapping("/verify") public YuanmaHeartbeatService.View verify() { return service.verify(); }
    @DeleteMapping("/credential") public YuanmaHeartbeatService.View clear() { return service.clear(); }
    private static boolean secureOrLocal(HttpServletRequest r) {
        return r.isSecure() || "127.0.0.1".equals(r.getRemoteAddr()) || "::1".equals(r.getRemoteAddr())
                || "0:0:0:0:0:0:0:1".equals(r.getRemoteAddr());
    }
}
