package cn.datacraft.atcoder;

import cn.datacraft.ai.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

interface AtcoderCookieRepository extends JpaRepository<AtcoderCookieEntity, Long> {}

@Component
class AtcoderCookieStore {
    private static final int MAX_COOKIE_LENGTH = 4096;

    private final AtcoderCookieRepository repository;
    private final SecretCipher cipher;
    private final String environmentCookie;

    AtcoderCookieStore(AtcoderCookieRepository repository, SecretCipher cipher,
                       @Value("${dataforge.atcoder.cookie:}") String environmentCookie) {
        this.repository = repository;
        this.cipher = cipher;
        this.environmentCookie = normalizeOptional(environmentCookie);
    }

    CurrentCookie current() {
        Optional<AtcoderCookieEntity> stored = repository.findById(AtcoderCookieEntity.SINGLETON_ID);
        if (stored.isPresent()) {
            try {
                return new CurrentCookie(cipher.decrypt(stored.get().getEncryptedCookie()),
                        "MANAGED", stored.get().getUpdatedAt());
            } catch (RuntimeException ex) {
                throw new IllegalStateException("AtCoder Cookie 解密失败，请确认 DATAFORGE_SECRET 未发生变化", ex);
            }
        }
        return environmentCookie.isBlank()
                ? new CurrentCookie("", "NONE", null)
                : new CurrentCookie(environmentCookie, "ENVIRONMENT", null);
    }

    void save(String rawCookie) {
        String cookie = normalizeRequired(rawCookie);
        Instant now = Instant.now();
        String encrypted = cipher.encrypt(cookie);
        AtcoderCookieEntity entity = repository.findById(AtcoderCookieEntity.SINGLETON_ID)
                .orElseGet(() -> new AtcoderCookieEntity(encrypted, now));
        entity.update(encrypted, now);
        repository.saveAndFlush(entity);
    }

    void clear() {
        if (repository.existsById(AtcoderCookieEntity.SINGLETON_ID)) {
            repository.deleteById(AtcoderCookieEntity.SINGLETON_ID);
            repository.flush();
        }
    }

    static String normalizeRequired(String value) {
        String cookie = normalizeOptional(value);
        if (cookie.isBlank()) throw new IllegalArgumentException("请输入 AtCoder Cookie");
        if (cookie.length() > MAX_COOKIE_LENGTH) throw new IllegalArgumentException("AtCoder Cookie 不能超过 4096 个字符");
        if (cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0 || cookie.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("AtCoder Cookie 包含无效字符");
        }
        return cookie;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return "";
        return value.trim().replaceFirst("(?i)^cookie\\s*:\\s*", "");
    }

    record CurrentCookie(String value, String source, Instant updatedAt) {}
}
