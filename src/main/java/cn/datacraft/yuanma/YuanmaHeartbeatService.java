package cn.datacraft.yuanma;

import cn.datacraft.ai.SecretCipher;
import cn.datacraft.homework.HomeworkRecord;
import cn.datacraft.homework.HomeworkRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class YuanmaHeartbeatService {
    static final String KIND = "YUANMA_SETTINGS";
    static final String ID = UUID.nameUUIDFromBytes("dataforge:yuanma-settings".getBytes(StandardCharsets.UTF_8)).toString();
    private final HomeworkRepository repository;
    private final ObjectMapper json;
    private final SecretCipher cipher;
    private final YuanmaGateway gateway;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Clock clock;
    private final boolean scheduling;
    private final long intervalSeconds;
    private final Object lock = new Object();
    private final AtomicBoolean queued = new AtomicBoolean();

    @Autowired public YuanmaHeartbeatService(HomeworkRepository repository, ObjectMapper json, SecretCipher cipher,
            YuanmaGateway gateway, PlatformTransactionManager manager, @Qualifier("yuanmaHeartbeatExecutor") Executor executor,
            @Value("${dataforge.yuanma.scheduling-enabled:true}") boolean scheduling,
            @Value("${dataforge.yuanma.heartbeat-seconds:600}") long intervalSeconds) {
        this(repository, json, cipher, gateway, manager, executor, Clock.systemUTC(), scheduling, intervalSeconds);
    }
    YuanmaHeartbeatService(HomeworkRepository repository, ObjectMapper json, SecretCipher cipher, YuanmaGateway gateway,
            PlatformTransactionManager manager, Executor executor, Clock clock, boolean scheduling, long intervalSeconds) {
        if (intervalSeconds < 60 || intervalSeconds > 86400) throw new IllegalArgumentException("心跳间隔需为 60～86400 秒");
        this.repository = repository; this.json = json; this.cipher = cipher; this.gateway = gateway;
        this.tx = new TransactionTemplate(manager); this.executor = executor; this.clock = clock;
        this.scheduling = scheduling; this.intervalSeconds = intervalSeconds;
    }

    public record Input(String credential, Boolean enabled) {}
    public record View(boolean configured, boolean enabled, String connectionState, String message,
                       Instant checkedAt, Instant lastSucceededAt, Long userId, String userName, String nickName,
                       long intervalSeconds) {}
    public static class Settings {
        public String encryptedToken;
        public boolean enabled;
        public String connectionState = "UNCONFIGURED", message;
        public Instant checkedAt, lastSucceededAt;
        public Long userId;
        public String userName, nickName;
    }
    private record Snapshot(Settings settings, Long version) {}
    private Instant now() { return clock.instant(); }
    private Snapshot snapshot() {
        return repository.findById(ID).map(r -> {
            if (!KIND.equals(r.kind) || !"SYSTEM".equals(r.owner)) throw new IllegalStateException("源码系统配置记录异常");
            try { return new Snapshot(json.readValue(r.payload, Settings.class), r.version); }
            catch (JsonProcessingException e) { throw new IllegalStateException("源码系统配置读取失败"); }
        }).orElseGet(() -> new Snapshot(new Settings(), null));
    }
    private Settings read() { return snapshot().settings(); }
    private void write(Settings settings, Long expectedVersion) {
        tx.executeWithoutResult(t -> {
            var existing = repository.lock(ID);
            if (expectedVersion == null ? existing.isPresent() : existing.isEmpty() || existing.get().version != expectedVersion)
                throw new IllegalStateException("源码系统配置已更改，请刷新后重试");
            HomeworkRecord r = existing.orElseGet(() -> {
                var record = new HomeworkRecord(); record.id = ID; record.owner = "SYSTEM"; record.kind = KIND; return record;
            });
            if (!KIND.equals(r.kind) || !"SYSTEM".equals(r.owner)) throw new IllegalStateException("源码系统配置记录异常");
            try { r.payload = json.writeValueAsString(settings); }
            catch (JsonProcessingException e) { throw new IllegalStateException("源码系统配置保存失败"); }
            r.updatedAt = now(); repository.saveAndFlush(r);
        });
    }
    private View view(Settings s) {
        return new View(s.encryptedToken != null, s.enabled, s.connectionState, s.message,
                s.checkedAt, s.lastSucceededAt, s.userId, s.userName, s.nickName, intervalSeconds);
    }
    public View settings() { synchronized (lock) { return view(read()); } }

    public View save(Input input) {
        if (input == null) throw new IllegalArgumentException("请填写源码系统连接设置");
        synchronized (lock) {
            Snapshot before = snapshot(); Settings s = before.settings();
            String token = input.credential();
            if (token != null && !token.isEmpty()) {
                // Verify first; never replace a working credential with an invalid one.
                YuanmaGateway.Identity identity = gateway.identity(YuanmaClient.credential(token));
                s.encryptedToken = cipher.encrypt(token);
                connected(s, identity);
            }
            if (input.enabled() != null) s.enabled = input.enabled();
            if (s.enabled && s.encryptedToken == null) throw new IllegalArgumentException("请先录入有效的 Admin-Token");
            write(s, before.version()); return view(s);
        }
    }
    private void connected(Settings s, YuanmaGateway.Identity identity) {
        s.userId = identity.userId(); s.userName = identity.userName(); s.nickName = identity.nickName();
        s.connectionState = "CONNECTED"; s.message = "身份验证成功；无法仅凭本次检查确认有效期已延长";
        s.checkedAt = now(); s.lastSucceededAt = s.checkedAt;
    }
    public View verify() { return verify(false); }
    private View verify(boolean automatic) {
        synchronized (lock) {
            Snapshot before = snapshot(); Settings s = before.settings();
            if (automatic && (!s.enabled || "AUTH_REQUIRED".equals(s.connectionState) || "CREDENTIAL_ERROR".equals(s.connectionState))) return view(s);
            if (s.encryptedToken == null) throw new IllegalArgumentException("请先录入源码系统 Admin-Token");
            try {
                String token;
                try { token = cipher.decrypt(s.encryptedToken); }
                catch (RuntimeException e) { throw new YuanmaException("CREDENTIAL_ERROR", "源码系统凭据解密失败，请检查密钥或重新录入"); }
                connected(s, gateway.identity(token));
                write(s, before.version()); return view(s);
            } catch (YuanmaException e) {
                s.connectionState = e.code(); s.message = e.getMessage(); s.checkedAt = now();
                write(s, before.version()); throw e;
            }
        }
    }
    public View clear() {
        synchronized (lock) { Snapshot before = snapshot(); Settings s = new Settings(); write(s, before.version()); return view(s); }
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void schedule() {
        if (!scheduling || !queued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    synchronized (lock) {
                        Settings s = read();
                        if (!s.enabled || s.encryptedToken == null || "AUTH_REQUIRED".equals(s.connectionState)
                                || "CREDENTIAL_ERROR".equals(s.connectionState)) return;
                        long delay = "RATE_LIMITED".equals(s.connectionState) ? Math.max(intervalSeconds, 3600) : intervalSeconds;
                        if (s.checkedAt != null && s.checkedAt.plusSeconds(delay).isAfter(now())) return;
                        try { verify(true); }
                        catch (YuanmaException ignored) { /* verify stored a safe, actionable status */ }
                        catch (IllegalStateException ignored) { /* another instance changed the configuration */ }
                    }
                } finally { queued.set(false); }
            });
        } catch (RuntimeException rejected) { queued.set(false); }
    }
}
