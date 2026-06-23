package cn.datacraft.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class AiConfigService {
    private final AiConfigRepository repository;
    private final String envBaseUrl;
    private final String envApiKey;
    private final String envModel;
    private final int defaultDailyGenerationLimit;
    private final SecretKeySpec encryptionKey;

    public AiConfigService(AiConfigRepository repository,
                           @Value("${dataforge.ai.base-url:}") String baseUrl,
                           @Value("${dataforge.ai.api-key:}") String apiKey,
                           @Value("${dataforge.ai.model:gpt-4.1-mini}") String model,
                           @Value("${dataforge.daily-generation-limit:30}") int dailyGenerationLimit,
                           @Value("${dataforge.crypto-secret}") String secret) {
        this.repository = repository; this.envBaseUrl = baseUrl; this.envApiKey = apiKey; this.envModel = model;
        this.defaultDailyGenerationLimit = Math.max(1, dailyGenerationLimit);
        try { this.encryptionKey = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "AES"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public Settings current() {
        Optional<AiConfigEntity> stored = repository.findById(1L);
        if (!stored.isPresent()) return new Settings(envBaseUrl, envApiKey, envModel);
        AiConfigEntity entity = stored.get();
        String key = entity.getEncryptedApiKey() == null ? envApiKey : decrypt(entity.getEncryptedApiKey());
        return new Settings(valueOr(entity.getBaseUrl(), envBaseUrl), key, valueOr(entity.getModel(), envModel));
    }

    public SettingsView view() {
        Settings settings = current();
        return new SettingsView(settings.baseUrl, settings.model, settings.apiKey != null && !settings.apiKey.trim().isEmpty(), dailyGenerationLimit());
    }

    public SettingsView update(String baseUrl, String model, String apiKey, Integer dailyGenerationLimit) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) throw new IllegalArgumentException("API Base URL 不能为空");
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("模型名称不能为空");
        if (dailyGenerationLimit == null || dailyGenerationLimit < 1 || dailyGenerationLimit > 10000) throw new IllegalArgumentException("每日生成次数限制必须在 1 到 10000 之间");
        AiConfigEntity entity = repository.findById(1L).orElseGet(AiConfigEntity::new);
        entity.setBaseUrl(baseUrl.trim().replaceAll("/$", "")); entity.setModel(model.trim());
        entity.setDailyGenerationLimit(dailyGenerationLimit);
        if (apiKey != null && !apiKey.trim().isEmpty()) entity.setEncryptedApiKey(encrypt(apiKey.trim()));
        entity.touch(); repository.save(entity);
        return view();
    }

    public int dailyGenerationLimit() {
        return repository.findById(1L)
                .map(AiConfigEntity::getDailyGenerationLimit)
                .filter(limit -> limit != null && limit > 0)
                .orElse(defaultDailyGenerationLimit);
    }

    private String encrypt(String plain) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length); System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) { throw new IllegalStateException("API Key 加密失败", ex); }
    }
    private String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded); byte[] nonce = Arrays.copyOfRange(combined, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(Arrays.copyOfRange(combined, 12, combined.length)), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("API Key 解密失败，请确认 DATAFORGE_SECRET 未发生变化", ex); }
    }
    private String valueOr(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }

    public static class Settings {
        public final String baseUrl, apiKey, model;
        Settings(String baseUrl, String apiKey, String model) { this.baseUrl = baseUrl; this.apiKey = apiKey; this.model = model; }
    }
    public static class SettingsView {
        public final String baseUrl, model; public final boolean apiKeyConfigured; public final int dailyGenerationLimit;
        SettingsView(String baseUrl, String model, boolean configured, int dailyGenerationLimit) {
            this.baseUrl = baseUrl; this.model = model; this.apiKeyConfigured = configured; this.dailyGenerationLimit = dailyGenerationLimit;
        }
    }
}
