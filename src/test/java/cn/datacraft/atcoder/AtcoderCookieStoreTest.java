package cn.datacraft.atcoder;

import cn.datacraft.ai.SecretCipher;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtcoderCookieStoreTest {
    @Test
    void encryptedManagedCookieOverridesEnvironmentAndClearRestoresFallback() {
        AtcoderCookieRepository repository = mock(AtcoderCookieRepository.class);
        AtomicReference<AtcoderCookieEntity> stored = new AtomicReference<>();
        when(repository.findById(AtcoderCookieEntity.SINGLETON_ID))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.existsById(AtcoderCookieEntity.SINGLETON_ID))
                .thenAnswer(invocation -> stored.get() != null);
        when(repository.saveAndFlush(any(AtcoderCookieEntity.class))).thenAnswer(invocation -> {
            AtcoderCookieEntity entity = invocation.getArgument(0);
            stored.set(entity);
            return entity;
        });
        doAnswer(invocation -> { stored.set(null); return null; })
                .when(repository).deleteById(AtcoderCookieEntity.SINGLETON_ID);

        AtcoderCookieStore store = new AtcoderCookieStore(
                repository, new SecretCipher("test-secret-long-enough"), "REVEL_SESSION=environment"
        );
        assertThat(store.current().source()).isEqualTo("ENVIRONMENT");

        store.save("Cookie: REVEL_SESSION=managed");
        assertThat(store.current().value()).isEqualTo("REVEL_SESSION=managed");
        assertThat(store.current().source()).isEqualTo("MANAGED");
        assertThat(stored.get().getEncryptedCookie()).doesNotContain("managed");

        store.clear();
        assertThat(store.current().value()).isEqualTo("REVEL_SESSION=environment");
        assertThat(store.current().source()).isEqualTo("ENVIRONMENT");
    }
}
