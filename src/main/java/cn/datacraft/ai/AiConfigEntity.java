package cn.datacraft.ai;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ai_config")
public class AiConfigEntity {
    @Id @Column(name = "id") private Long id = 1L;
    @Column(name = "base_url", length = 500) private String baseUrl;
    @Column(name = "model", length = 120) private String model;
    @Column(name = "encrypted_api_key", columnDefinition = "TEXT") private String encryptedApiKey;
    @Column(name = "daily_generation_limit")
    private Integer dailyGenerationLimit;
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
    public Long getId() { return id; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getEncryptedApiKey() { return encryptedApiKey; }
    public void setEncryptedApiKey(String encryptedApiKey) { this.encryptedApiKey = encryptedApiKey; }
    public Integer getDailyGenerationLimit() { return dailyGenerationLimit; }
    public void setDailyGenerationLimit(Integer dailyGenerationLimit) { this.dailyGenerationLimit = dailyGenerationLimit; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { updatedAt = Instant.now(); }
}
