package cn.datacraft.user;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Column(name = "username", nullable = false, unique = true, length = 40)
    private String username;
    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "role", nullable = false, length = 16)
    private String role = "USER";
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;
    @Column(name = "daily_generation_limit")
    private Integer dailyGenerationLimit;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getDailyGenerationLimit() { return dailyGenerationLimit; }
    public void setDailyGenerationLimit(Integer dailyGenerationLimit) { this.dailyGenerationLimit = dailyGenerationLimit; }
    public Instant getCreatedAt() { return createdAt; }
}
