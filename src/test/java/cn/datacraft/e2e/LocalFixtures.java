package cn.datacraft.e2e;

import cn.datacraft.homework.KedaojGateway;
import cn.datacraft.homework.HomeworkTypes.*;
import cn.datacraft.atcoder.AtcoderStandings;
import cn.datacraft.atcoder.AtcoderStandingsGateway;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Only loaded from the test classpath under the explicit e2e profile; never connects to real OJ accounts. */
public class LocalFixtures {
    @Component @Primary @Profile("e2e")
    public static class Oj implements KedaojGateway {
        public Identity identity(String owner,String sid,String domain){return new Identity(10766,"test_teacher");}
        public Identity user(String owner,String sid,String domain,long uid){return new Identity(uid,"student_"+uid);}
        public Problem problem(String owner,String sid,String domain,String pid){return new Problem(pid,32,"测试题目");}
        public List<Submission> records(String owner,String sid,String domain,long uid,long docId,Runnable heartbeat){
            heartbeat.run();return List.of(new Submission("6ad4457d2b71ec54920761b8",domain,uid,docId,1,100,Instant.now().minusSeconds(10),""));
        }
    }
    @Component @Primary @Profile("e2e")
    public static class Atcoder implements AtcoderStandingsGateway {
        public AtcoderStandings.Snapshot fetchStandings(String contestId){return new AtcoderStandings.Snapshot(
                List.of(new AtcoderStandings.Task(contestId+"_a","A","Sum",BigDecimal.valueOf(100))),Map.of());}
        public AtcoderStandings.ContestMetadata fetchMetadata(String contestId){return new AtcoderStandings.ContestMetadata("Test Contest",Instant.now().minusSeconds(3600),Instant.now().plusSeconds(3600));}
        public CookieStatus cookieStatus(){return CookieStatus.MISSING;}
        public String cookieSource(){return "none";}
        public Instant cookieUpdatedAt(){return null;}
        public AtcoderStandings.Snapshot updateCookie(String cookie,String contestId){return fetchStandings(contestId);}
        public void clearManagedCookie(){}
    }
}
