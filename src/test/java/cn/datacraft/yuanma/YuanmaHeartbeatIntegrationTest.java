package cn.datacraft.yuanma;

import cn.datacraft.ai.SecretCipher;
import cn.datacraft.homework.HomeworkRepository;
import cn.datacraft.yuanma.YuanmaHeartbeatService.Input;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:yuanma-tests;DB_CLOSE_DELAY=-1",
        "dataforge.homework.scheduling-enabled=false", "dataforge.yuanma.scheduling-enabled=false",
        "dataforge.bootstrap-enabled=false", "dataforge.runtime-dir=${java.io.tmpdir}/yuanma-test-runtime"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(cn.datacraft.CsrfTestConfiguration.class)
class YuanmaHeartbeatIntegrationTest {
    @Autowired HomeworkRepository repository;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @MockitoBean YuanmaGateway gateway;
    final Instant time = Instant.parse("2026-11-03T00:00:00Z");
    YuanmaHeartbeatService service;
    YuanmaHeartbeatService at(long seconds) {
        return new YuanmaHeartbeatService(repository, json, cipher, gateway, manager, Runnable::run,
                Clock.fixed(time.plusSeconds(seconds), ZoneOffset.UTC), true, 600);
    }
    @BeforeEach void setup() {
        repository.deleteById(YuanmaHeartbeatService.ID);
        service = at(0);
        when(gateway.identity(anyString())).thenReturn(new YuanmaGateway.Identity(10766, "teacher", "老师"));
    }
    @Test void encryptsAndPersistsNoSecretInViewAndContinuesAfterRestart() throws Exception {
        var saved = service.save(new Input("offline-token-one", true));
        assertThat(saved.configured()).isTrue(); assertThat(saved.enabled()).isTrue();
        assertThat(json.writeValueAsString(saved)).doesNotContain("offline-token-one", "encryptedToken", "credential");
        assertThat(repository.findById(YuanmaHeartbeatService.ID).orElseThrow().payload).doesNotContain("offline-token-one");
        clearInvocations(gateway);
        at(599).schedule(); verify(gateway, never()).identity(anyString());
        var restarted = at(601); restarted.schedule(); restarted.schedule();
        verify(gateway, times(1)).identity("offline-token-one");
        assertThat(restarted.settings().checkedAt()).isEqualTo(time.plusSeconds(601));
        assertThat(restarted.settings().lastSucceededAt()).isEqualTo(time.plusSeconds(601));
    }
    @Test void authFailurePausesAutomaticRequestsUntilCredentialReplaced() {
        service.save(new Input("old-token", true));
        when(gateway.identity("old-token")).thenThrow(new YuanmaException("AUTH_REQUIRED", "需要重新录入"));
        var later = at(601); later.schedule(); assertThat(later.settings().connectionState()).isEqualTo("AUTH_REQUIRED");
        clearInvocations(gateway); at(5000).schedule(); verify(gateway, never()).identity(anyString());
        var recovered = at(5000).save(new Input("new-token", null));
        assertThat(recovered.enabled()).isTrue(); assertThat(recovered.connectionState()).isEqualTo("CONNECTED");
        clearInvocations(gateway); at(5601).schedule(); verify(gateway).identity("new-token");
    }
    @Test void failurePreservesTokenAndSuccessTimeAndRateLimitBacksOff() {
        service.save(new Input("old-token", true));
        when(gateway.identity("bad-token")).thenThrow(new YuanmaException("AUTH_REQUIRED", "身份失败"));
        assertThatThrownBy(() -> service.save(new Input("bad-token", true))).isInstanceOf(YuanmaException.class);
        when(gateway.identity("old-token")).thenThrow(new YuanmaException("RATE_LIMITED", "限流"));
        at(601).schedule(); var state = at(601).settings();
        assertThat(state.configured()).isTrue(); assertThat(state.lastSucceededAt()).isEqualTo(time);
        clearInvocations(gateway); at(1202).schedule(); verify(gateway, never()).identity(anyString());
        at(4202).schedule(); verify(gateway).identity("old-token");
    }
    @Test void pauseAndClearStopScheduledRequests() {
        service.save(new Input("old-token", true));
        service.save(new Input(null, false)); clearInvocations(gateway);
        at(1000).schedule(); verify(gateway, never()).identity(anyString());
        service.clear(); at(10000).schedule(); verify(gateway, never()).identity(anyString());
        assertThat(service.settings().configured()).isFalse();
    }
    @Test void inFlightHeartbeatCannotRestoreTokenClearedByAnotherInstance() throws Exception {
        service.save(new Input("old-token", true));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(gateway.identity("old-token")).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return new YuanmaGateway.Identity(10766, "teacher", "老师");
        });
        Thread heartbeat = new Thread(() -> at(601).schedule());
        heartbeat.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            at(602).clear();
        } finally {
            release.countDown(); heartbeat.join(5000);
        }
        assertThat(heartbeat.isAlive()).isFalse();
        assertThat(at(603).settings().configured()).isFalse();
    }
    @Test void adminOnlyAndTokenRequiresHttpsOrTrueLoopback() throws Exception {
        String path = "/api/admin/yuanma-heartbeat";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("regular").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(put(path).with(user("admin").roles("ADMIN"))
                .with(r -> { r.setRemoteAddr("203.0.113.8"); return r; })
                .contentType("application/json").content("{\"credential\":\"offline-token\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        verify(gateway, never()).identity(anyString());
        mvc.perform(put(path).with(user("admin").roles("ADMIN"))
                .with(r -> { r.setRemoteAddr("203.0.113.8"); r.setSecure(true); return r; })
                .contentType("application/json").content("{\"credential\":\"offline-token\",\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        String body = mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("offline-token", "encryptedToken", "credential");
    }
}
