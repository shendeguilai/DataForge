package cn.datacraft.web;

import cn.datacraft.job.JobService;
import cn.datacraft.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(JobController.class)
class JobControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JobService jobs;
    @MockitoBean UserService users;

    @Test
    @WithMockUser
    void rejectsEmptyRequest() throws Exception {
        mvc.perform(post("/api/jobs").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
