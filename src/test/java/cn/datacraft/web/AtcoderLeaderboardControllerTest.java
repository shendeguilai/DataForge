package cn.datacraft.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atcoder-leaderboard-controller-test;DB_CLOSE_DELAY=-1",
        "dataforge.bootstrap-enabled=false",
        "dataforge.atcoder.cookie=",
        "dataforge.admin.password=test-admin-password",
        "dataforge.crypto-secret=test-encryption-secret"
})
@AutoConfigureMockMvc
class AtcoderLeaderboardControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void publicCanReadAndRefreshButOnlyAdminCanManageLeaderboard() throws Exception {
        mvc.perform(get("/atcoder-problems.html").with(anonymous()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tools/atcoder-problems").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(post("/api/admin/atcoder-leaderboard/translations").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/atcoder-leaderboard/translations").with(user("student").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/atcoder-leaderboard/translations").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/atcoder-leaderboard/translations/abc430_a").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/atcoder-leaderboard/translations/abc430_a")
                        .with(user("student").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/atcoder-leaderboard/translations/abc430_a")
                        .with(user("student").roles("USER"))
                        .contentType("application/json")
                        .content("{\"translatedHtml\":\"<p>中文</p>\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/tools/atcoder-leaderboard").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.refreshAfterSeconds").value(60));
        mvc.perform(post("/api/tools/atcoder-leaderboard/refresh").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tools/atcoder-leaderboard/refresh").with(user("student").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tools/atcoder-leaderboard/refresh").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/atcoder-leaderboard/config").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/atcoder-leaderboard/config").with(user("student").roles("USER")))
                .andExpect(status().isForbidden());

        String participant = "{\"displayName\":\"小明\",\"atcoderUsername\":\"Alice_01\"}";
        mvc.perform(post("/api/admin/atcoder-leaderboard/participants")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json").content(participant))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("小明"));
        mvc.perform(post("/api/admin/atcoder-leaderboard/participants")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"displayName\":\"重复\",\"atcoderUsername\":\"alice_01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该 AtCoder ID 已在排行榜中"));
        mvc.perform(post("/api/admin/atcoder-leaderboard/participants")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"displayName\":\"非法\",\"atcoderUsername\":\"bad-id!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/atcoder-leaderboard/participants/batch")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                [
                                  {"realName":"小红","atcoderId":"Bob_02"},
                                  {"realName":"小李","atcoderId":"Carol03"}
                                ]
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].displayName").value("小红"))
                .andExpect(jsonPath("$[1].atcoderUsername").value("Carol03"));
        mvc.perform(post("/api/admin/atcoder-leaderboard/participants/batch")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                [
                                  {"realName":"重复一","atcoderId":"NewUser"},
                                  {"realName":"重复二","atcoderId":"newuser"}
                                ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("本批次中重复")));

        mvc.perform(put("/api/admin/atcoder-leaderboard/config")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"contestId\":\"abc430\",\"displayTitle\":\"班级榜\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("AtCoder Cookie 未配置")));

        mvc.perform(put("/api/admin/atcoder-leaderboard/cookie")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"cookie\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请输入 AtCoder Cookie"));
        mvc.perform(put("/api/admin/atcoder-leaderboard/cookie")
                        .with(user("student").roles("USER"))
                        .contentType("application/json")
                        .content("{\"cookie\":\"REVEL_SESSION=secret\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/admin/atcoder-leaderboard/participants/1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
    }
}
