package cn.datacraft.web;

import cn.datacraft.job.OperationsService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.security.Principal;
import java.io.IOException;

@RestController
@RequestMapping("/api/admin/operations")
public class OperationsController {
    private final OperationsService service;
    public OperationsController(OperationsService service){this.service=service;}
    @GetMapping public Object status()throws IOException{return service.status();}
    @GetMapping("/cleanup")public Object preview(Principal p)throws IOException{return service.preview(p.getName());}
    @PostMapping("/cleanup")public Object cleanup(Principal p,@RequestBody Map<String,String> input)throws IOException{return Map.of("removed",service.cleanup(p.getName(),input.get("token")));}
}
