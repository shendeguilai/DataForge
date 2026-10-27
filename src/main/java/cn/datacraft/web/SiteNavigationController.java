package cn.datacraft.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SiteNavigationController {
    @GetMapping({"/", "/index.html"})
    public String home() { return "redirect:/tools.html"; }
}
