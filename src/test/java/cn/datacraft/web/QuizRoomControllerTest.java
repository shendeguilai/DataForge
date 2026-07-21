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
        "spring.datasource.url=jdbc:h2:mem:quiz-controller-test;DB_CLOSE_DELAY=-1",
        "dataforge.admin.password=test-admin-password",
        "dataforge.crypto-secret=test-encryption-secret",
        "dataforge.quiz.countdown-seconds=0"
})
@AutoConfigureMockMvc
class QuizRoomControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void teacherPageAndRoomCreationRequireLoginButStudentCanJoin() throws Exception {
        mvc.perform(get("/quiz-buzzer.html").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/quiz-join.html").with(anonymous())).andExpect(status().isOk());
        mvc.perform(post("/api/tools/quiz/rooms").with(anonymous())
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isUnauthorized());

        String createdJson = mvc.perform(post("/api/tools/quiz/rooms").with(user("quiz_teacher"))
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.inviteCode").isString())
                .andExpect(jsonPath("$.referenceAnswer").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(createdJson);
        String roomCode = created.path("roomCode").asText();
        String inviteCode = created.path("inviteCode").asText();

        String joinedJson = mvc.perform(post("/api/tools/quiz/rooms/" + roomCode + "/join").with(anonymous())
                        .contentType("application/json")
                        .content("{\"displayName\":\"测试学生\",\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.owner").value(false))
                .andExpect(jsonPath("$.room.inviteCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(joinedJson).path("token").asText();

        mvc.perform(post("/api/tools/quiz/rooms/" + roomCode + "/rounds/next").with(user("quiz_teacher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceAnswer.answer").isString());
        mvc.perform(get("/api/tools/quiz/rooms/" + roomCode).with(anonymous()).header("X-Room-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceAnswer").doesNotExist())
                .andExpect(jsonPath("$.revealedAnswer").doesNotExist());
    }

    private static String createBody() {
        return "{\"name\":\"接口抢答课堂\",\"questionCount\":3,\"buzzSeconds\":15}";
    }
}
