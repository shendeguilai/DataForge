package cn.datacraft.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:typing-controller-test;DB_CLOSE_DELAY=-1",
        "dataforge.admin.password=test-admin-password",
        "dataforge.crypto-secret=test-encryption-secret",
        "dataforge.typing.countdown-seconds=0",
        "dataforge.typing.round-seconds=2"
})
@AutoConfigureMockMvc
class TypingRoomControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void publicCanListAndJoinButOnlyLoggedInUserCanCreate() throws Exception {
        mvc.perform(get("/api/tools/typing/rooms").with(anonymous()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tools/typing/rooms").with(anonymous())
                        .contentType("application/json").content("{\"name\":\"匿名房间\"}"))
                .andExpect(status().isUnauthorized());

        String createdJson = mvc.perform(post("/api/tools/typing/rooms").with(user("controller_owner"))
                        .contentType("application/json").content("{\"name\":\"接口测试房间\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.inviteCode").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(createdJson);
        String roomId = created.path("roomId").asText();
        String inviteCode = created.path("inviteCode").asText();

        String joinedJson = mvc.perform(post("/api/tools/typing/rooms/" + roomId + "/join").with(anonymous())
                        .contentType("application/json")
                        .content("{\"displayName\":\"测试同学\",\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.owner").value(false))
                .andExpect(jsonPath("$.room.inviteCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(joinedJson).path("token").asText();

        mvc.perform(get("/api/tools/typing/rooms/" + roomId).with(anonymous())
                        .header("X-Room-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfMemberId").isString());

        mvc.perform(delete("/api/tools/typing/rooms/" + roomId).with(user("controller_owner")))
                .andExpect(status().isNoContent());
    }
}
