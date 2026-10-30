package cn.datacraft.atcoder;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Discover actual contests from the official schedule, never guess contest numbers. */
@Component
public class AtcoderContestSchedule {
    public record Candidate(String id,String title,Instant start,Instant end) {}
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public List<Candidate> fetch() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://atcoder.jp/contests/"))
                .timeout(Duration.ofSeconds(20)).header("User-Agent","DataForge-Schedule/1.0").GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var body=response.body()) {
            if(response.statusCode()!=200)throw new IllegalStateException("官方赛程暂时不可用");
            byte[] bytes=body.readNBytes(2*1024*1024+1);
            if(bytes.length>2*1024*1024)throw new IllegalStateException("官方赛程响应过大");
            return parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    static List<Candidate> parse(String html) {
        var result=new LinkedHashMap<String,Candidate>();
        for(var row:Jsoup.parse(html).select("#contest-table-upcoming tbody tr, #contest-table-recent tbody tr, #contest-table-action tbody tr")) {
            var link=row.selectFirst("a[href~=/contests/abc[0-9]+$]");
            var time=row.selectFirst("time");
            if(link==null||time==null||row.select("td").size()<3)continue;
            try {
                String id=link.attr("href").substring("/contests/".length());
                Instant start=OffsetDateTime.parse(time.text(),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ")).toInstant();
                String[] duration=row.select("td").get(2).text().split(":");
                if(duration.length!=2)continue;
                long minutes=Long.parseLong(duration[0])*60+Long.parseLong(duration[1]);
                if(minutes<=0||minutes>1440||link.text().length()>160)continue;
                result.put(id,new Candidate(id,link.text(),start,start.plusSeconds(minutes*60)));
            } catch(RuntimeException ignored) { /* A changed row must not manufacture a contest. */ }
        }
        return List.copyOf(result.values());
    }
}
