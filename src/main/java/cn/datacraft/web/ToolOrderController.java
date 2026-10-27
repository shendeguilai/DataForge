package cn.datacraft.web;

import cn.datacraft.teaching.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@RestController
public class ToolOrderController {
    public static final List<String> DEFAULT=List.of("homework","csp-sim","atcoder","leaderboard","typing","quiz","paper","data-generator","username","more");
    private final ToolOrderRepository records;
    private final ObjectMapper json;
    public ToolOrderController(ToolOrderRepository records,ObjectMapper json) {this.records=records;this.json=json;}
    public record Order(List<String> ids,long revision) {}
    @GetMapping("/api/tools/catalog") public Order order() {
        var record=records.findById(1L);
        if(record.isEmpty())return new Order(DEFAULT,0);
        try {
            List<String> saved=json.readValue(record.get().payload,new TypeReference<List<String>>(){});
            var result=new ArrayList<>(saved.stream().filter(DEFAULT::contains).distinct().toList());
            DEFAULT.stream().filter(id->!result.contains(id)).forEach(result::add);
            return new Order(result,record.get().version+1);
        } catch(Exception e) {throw new IllegalStateException("工具顺序读取失败");}
    }
    @PutMapping("/api/admin/tool-order") @Transactional
    public Order save(@RequestBody Order input) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(input.ids()==null || input.ids().size()!=DEFAULT.size() || !new HashSet<>(input.ids()).equals(new HashSet<>(DEFAULT))) throw new IllegalArgumentException("请包含全部工具且不要重复");
        var existing=records.findById(1L);var r=existing.orElseGet(ToolOrder::new);
        if((existing.isPresent()?r.version+1:0)!=input.revision())throw new IllegalStateException("工具顺序已被修改，请重新加载");
        r.payload=json.writeValueAsString(input.ids()); records.saveAndFlush(r);return order();
    }
}
