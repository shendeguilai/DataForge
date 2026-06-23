package cn.datacraft.user;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 40)
    private String username;
    @JsonIgnore @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false, length = 16)
    private String role = "USER";
    @Column(nullable = false)
    private boolean enabled = true;
    private Integer dailyGenerationLimit;
    @Column(nullable = false, updatable = false)
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
