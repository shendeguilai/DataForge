package cn.datacraft.cspsim;

import cn.datacraft.DataForgeApplication;
import cn.datacraft.cspsim.CspTypes.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CspPersistenceTest {
    @Test void workspaceAndSubmissionSurviveApplicationRestart() throws Exception {
        Path root=Files.createTempDirectory("csp-restart-");
        String[] args={"--spring.datasource.url=jdbc:h2:file:"+root.resolve("database"),"--dataforge.runtime-dir="+root.resolve("runtime"),"--dataforge.bootstrap-enabled=false"};
        String examId,participantId,code,examCode;
        try(ConfigurableApplicationContext first=new SpringApplicationBuilder(DataForgeApplication.class).web(WebApplicationType.NONE).run(args)){
            CspSimService sim=first.getBean(CspSimService.class);
            var student=sim.addStudent("teacher",new StudentInput("李同学","竞赛班","001"));code=(String)student.get("code");
            CreateExam create=new CreateExam();create.exam.name="持久化验证";Problem problem=new Problem();problem.name="求和";problem.directory="sum";problem.sourceName="sum.cpp";create.exam.problems.add(problem);
            create.students=List.of(new Assignment((String)student.get("id"),null,null));examId=(String)sim.createExam("teacher",create).get("id");sim.start("teacher",examId);
            examCode=(String)sim.entryCode(examId).get("examCode");
            var joined=sim.join(examCode,new Join("李同学",code));participantId=(String)joined.get("participationId");String token=(String)joined.get("token");
            sim.upload(participantId,token,"/home/noi/Desktop",List.of("sum.cpp"),List.of("saved source".getBytes()),false,null);sim.submit(participantId,token);
        }
        try(ConfigurableApplicationContext second=new SpringApplicationBuilder(DataForgeApplication.class).web(WebApplicationType.NONE).run(args)){
            CspSimService sim=second.getBean(CspSimService.class);assertThat(sim.entryCode(examId).get("examCode")).isEqualTo(examCode);var joined=sim.join(examCode,new Join("李同学",code));String token=(String)joined.get("token");
            assertThat(joined.get("participationId")).isEqualTo(participantId);
            assertThat(new String(sim.readFile(participantId,token,"/home/noi/Desktop/sum.cpp"))).isEqualTo("saved source");
            assertThat((String)sim.workspace(participantId,token).get("latestSubmission")).isNotBlank();
        }
    }
}
