package cn.datacraft.ai;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ai_config")
public class AiConfigEntity {
    @Id private Long id = 1L;
    @Column(length = 500) private String baseUrl;
    @Column(length = 120) private String model;
    @Lob private String encryptedApiKey;
    private Integer dailyGenerationLimit;
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
