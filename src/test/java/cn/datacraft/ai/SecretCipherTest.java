package cn.datacraft.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {
    @Test
    void encryptsAndReencryptsWithDifferentSecrets() {
        SecretCipher oldCipher = new SecretCipher("old-secret-0123456789abcdef");
        SecretCipher newCipher = new SecretCipher("new-secret-0123456789abcdef");

        String oldEncrypted = oldCipher.encrypt("api-key-value");
        String migrated = newCipher.reencryptFrom(oldEncrypted, "old-secret-0123456789abcdef");

        assertThat(newCipher.decrypt(migrated)).isEqualTo("api-key-value");
        assertThat(migrated).isNotEqualTo(oldEncrypted);
        assertThatThrownBy(() -> oldCipher.decrypt(migrated)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWeakSecrets() {
        assertThatThrownBy(() -> new SecretCipher("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
