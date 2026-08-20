package cn.datacraft.atcoder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "atcoder_cookie_config")
class AtcoderCookieEntity {
    static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "encrypted_cookie", nullable = false, columnDefinition = "TEXT")
    private String encryptedCookie;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AtcoderCookieEntity() {}

    AtcoderCookieEntity(String encryptedCookie, Instant updatedAt) {
        this.encryptedCookie = encryptedCookie;
        this.updatedAt = updatedAt;
    }

    void update(String encryptedCookie, Instant updatedAt) {
        this.encryptedCookie = encryptedCookie;
        this.updatedAt = updatedAt;
    }

    String getEncryptedCookie() { return encryptedCookie; }
    Instant getUpdatedAt() { return updatedAt; }
}
