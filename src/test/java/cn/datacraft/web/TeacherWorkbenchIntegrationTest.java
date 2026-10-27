package cn.datacraft.web;

import cn.datacraft.homework.*;
import cn.datacraft.homework.HomeworkTypes.*;
import cn.datacraft.cspsim.*;
import cn.datacraft.teaching.TeacherWorkbench;
import cn.datacraft.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:teacher-workbench;DB_CLOSE_DELAY=-1","dataforge.homework.scheduling-enabled=false"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(cn.datacraft.CsrfTestConfiguration.class)
class TeacherWorkbenchIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired HomeworkRepository records;
    @Autowired CspRecordRepository exams;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired TeacherWorkbench reports;
    static final java.nio.file.Path RUNTIME;
    static { try { RUNTIME=java.nio.file.Files.createTempDirectory("teacher-workbench-test-"); } catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry r){r.add("dataforge.runtime-dir",RUNTIME::toString);}
    void fixture(String owner,String kind,Object payload)throws Exception {
        var r=new HomeworkRecord();r.id=UUID.randomUUID().toString();r.owner=owner;r.kind=kind;r.payload=json.writeValueAsString(payload);r.updatedAt=Instant.now();records.saveAndFlush(r);
    }
    @Test void dashboardAndLearningKeepUnknownSeparateAndNeverMixTeachers()throws Exception {
        users.register("report_teacher","test-password-123","443322");
        var a=new Assignment();a.classId="class-a";a.className="一班";a.title="周作业";a.domain="system";
        a.startAt=Instant.now().minus(Duration.ofDays(8));a.endAt=Instant.now().minus(Duration.ofDays(1));
        a.students=List.of(new Student("s1","同名同学",42,"oj42"));
        a.problems=List.of(new Problem("P1",1,"题一"),new Problem("P2",2,"题二"));
        var ac=new Cell();ac.status="AC";ac.completed=true;ac.beforeDeadline=true;
        var unknown=new Cell();unknown.status="UNKNOWN";unknown.completed=true;
        a.cells.put("42:1",ac);a.cells.put("42:2",unknown);fixture("report_teacher","ASSIGNMENT",a);
        a.title="其他教师的私有作业";fixture("other_teacher","ASSIGNMENT",a);
        mvc.perform(get("/api/tools/workbench").with(user("report_teacher")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingCells").value(0)).andExpect(jsonPath("$.unknownCells").value(1))
                .andExpect(jsonPath("$.assignments.length()").value(1));
        var response=mvc.perform(get("/api/tools/learning").with(user("report_teacher"))).andExpect(status().isOk()).andReturn();
        var body=json.readTree(response.getResponse().getContentAsString());
        assertThat(body.get("students").size()).isEqualTo(1);
        int completed=0,unchecked=0;
        for(var week:body.get("students").get(0).get("weeks")){completed+=week.get("completed").asInt();unchecked+=week.get("unknown").asInt();}
        assertThat(completed).isEqualTo(1);assertThat(unchecked).isEqualTo(1);
        assertThat(body.get("students").get(0).get("needsAttention").asBoolean()).isFalse();
        mvc.perform(get("/api/tools/learning")).andExpect(status().isUnauthorized());
        var foreign=new CspRecord();foreign.id=UUID.randomUUID().toString();foreign.owner="other_teacher";foreign.kind="STUDENT";foreign.payload="{}";exams.saveAndFlush(foreign);
        var preferences=new TeacherWorkbench.Preferences();preferences.links.put("42",foreign.id);
        assertThatThrownBy(()->reports.savePreferences("report_teacher",preferences)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void toolOrderRequiresAdminAndRejectsDuplicatesOrStaleEdits()throws Exception {
        var original=json.readTree(mvc.perform(get("/api/tools/catalog")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var ids=new ArrayList<>(ToolOrderController.DEFAULT);Collections.reverse(ids);
        String body=json.writeValueAsString(new ToolOrderController.Order(ids,original.get("revision").asLong()));
        mvc.perform(put("/api/admin/tool-order").with(user("regular"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/tool-order").with(user("admin").roles("ADMIN")).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/tools/catalog")).andExpect(jsonPath("$.ids[0]").value(ids.get(0)));
        mvc.perform(put("/api/admin/tool-order").with(user("admin").roles("ADMIN")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        ids.set(0,ids.get(1));
        mvc.perform(put("/api/admin/tool-order").with(user("admin").roles("ADMIN")).contentType("application/json").content(json.writeValueAsString(new ToolOrderController.Order(ids,0)))).andExpect(status().isBadRequest());
    }
}
