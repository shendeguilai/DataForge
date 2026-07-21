package cn.datacraft.migration;

import cn.datacraft.ai.SecretCipher;
import cn.datacraft.job.ArtifactStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "dataforge.legacy-import.enabled", havingValue = "true")
public class LegacyH2ImportRunner implements ApplicationRunner {
    private static final List<TableSpec> TABLES = Arrays.asList(
            new TableSpec("user_accounts", "id", "id", "username", "password_hash", "role", "enabled",
                    "daily_generation_limit", "created_at"),
            new TableSpec("generation_jobs", "id", "id", "user_id", "status", "progress", "message", "error",
                    "statement", "standard_code", "requirements", "case_count", "cpp_standard", "plan_json",
                    "artifact_path", "created_at", "updated_at"),
            new TableSpec("ai_config", "id", "id", "base_url", "model", "encrypted_api_key",
                    "daily_generation_limit", "updated_at"),
            new TableSpec("typing_articles", "id", "id", "title", "category", "content"),
            new TableSpec("typing_article_seed_state", "id", "id", "initialized_at")
    );

    private final DataSource targetDataSource;
    private final ArtifactStorage artifacts;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;
    private final Path sourceFile;
    private final Path sourceRuntime;
    private final String sourceUsername;
    private final String sourcePassword;
    private final String oldSecret;
    private final boolean allowMissingArtifacts;
    private final List<Path> createdArtifacts = new ArrayList<>();

    public LegacyH2ImportRunner(DataSource targetDataSource,
                                ArtifactStorage artifacts,
                                SecretCipher cipher,
                                ObjectMapper mapper,
                                ConfigurableApplicationContext context,
                                @Value("${dataforge.legacy-import.h2-file}") String sourceFile,
                                @Value("${dataforge.legacy-import.runtime-dir}") String sourceRuntime,
                                @Value("${dataforge.legacy-import.username:sa}") String sourceUsername,
                                @Value("${dataforge.legacy-import.password:}") String sourcePassword,
                                @Value("${dataforge.legacy-import.old-secret:}") String oldSecret,
                                @Value("${dataforge.legacy-import.allow-missing-artifacts:false}") boolean allowMissingArtifacts) {
        this.targetDataSource = targetDataSource;
        this.artifacts = artifacts;
        this.cipher = cipher;
        this.mapper = mapper;
        this.context = context;
        this.sourceFile = Paths.get(sourceFile).toAbsolutePath().normalize();
        this.sourceRuntime = Paths.get(sourceRuntime).toAbsolutePath().normalize();
        this.sourceUsername = sourceUsername;
        this.sourcePassword = sourcePassword;
        this.oldSecret = oldSecret;
        this.allowMissingArtifacts = allowMissingArtifacts;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        try {
            importDatabase();
        } finally {
            context.close();
        }
    }

    void importDatabase() throws Exception {
        requireSourceFiles();
        String sourceSha = sha256(sourceFile);
        String databaseBase = sourceFile.toString().substring(0, sourceFile.toString().length() - ".mv.db".length());
        String sourceUrl = "jdbc:h2:file:" + databaseBase + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;FILE_LOCK=NO";

        Class.forName("org.h2.Driver");
        try (Connection source = DriverManager.getConnection(sourceUrl, sourceUsername, sourcePassword);
             Connection target = targetDataSource.getConnection()) {
            target.setAutoCommit(false);
            try {
                if (alreadyImported(target, sourceSha)) {
                    System.out.println("H2 数据库已经导入，source_sha256=" + sourceSha);
                    target.rollback();
                    return;
                }
                requireEmptyTarget(target);

                Map<String, Long> counts = new LinkedHashMap<>();
                Map<String, String> hashes = new LinkedHashMap<>();
                for (TableSpec table : TABLES) {
                    CopyResult result = copyTable(source, target, table);
                    counts.put(table.name, result.rows);
                    hashes.put(table.name, result.hash);
                    CopyResult targetResult = hashTable(target, table, false);
                    if (result.rows != targetResult.rows || !result.hash.equals(targetResult.hash)) {
                        throw new IllegalStateException("表 " + table.name + " 迁移后行数或哈希不一致");
                    }
                }
                resetUserSequence(target);
                insertImportMarker(target, sourceSha, counts, hashes);
                target.commit();
                System.out.println("H2 全量迁移完成，source_sha256=" + sourceSha + "，rows=" + mapper.writeValueAsString(counts));
            } catch (Exception exception) {
                target.rollback();
                rollbackCreatedArtifacts();
                throw exception;
            }
        }
    }

    private void requireSourceFiles() {
        if (!sourceFile.getFileName().toString().endsWith(".mv.db") || !Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException("LEGACY_H2_FILE 必须指向已停止服务后的 dataforge.mv.db");
        }
        if (!Files.isDirectory(sourceRuntime)) {
            throw new IllegalArgumentException("LEGACY_RUNTIME_DIR 不存在或不是目录");
        }
    }

    private boolean alreadyImported(Connection target, String sourceSha) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT 1 FROM legacy_imports WHERE source_sha256 = ?")) {
            statement.setString(1, sourceSha);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void requireEmptyTarget(Connection target) throws SQLException {
        for (TableSpec table : TABLES) {
            try (PreparedStatement statement = target.prepareStatement("SELECT COUNT(*) FROM " + table.name);
                 ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) != 0) {
                    throw new IllegalStateException("目标数据库不是空库，拒绝导入：" + table.name);
                }
            }
        }
    }

    private CopyResult copyTable(Connection source, Connection target, TableSpec table) throws Exception {
        String columns = String.join(", ", table.columns);
        String placeholders = String.join(", ", java.util.Collections.nCopies(table.columns.size(), "?"));
        String selectSql = "SELECT " + columns + " FROM " + table.name + " ORDER BY " + table.orderBy;
        String insertSql = "INSERT INTO " + table.name + " (" + columns + ") VALUES (" + placeholders + ")";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long rows = 0;

        try (PreparedStatement select = source.prepareStatement(selectSql);
             ResultSet result = select.executeQuery();
             PreparedStatement insert = target.prepareStatement(insertSql)) {
            while (result.next()) {
                List<Object> values = readRow(result, table, true);
                updateDigest(digest, values);
                for (int index = 0; index < values.size(); index++) {
                    insert.setObject(index + 1, values.get(index));
                }
                insert.addBatch();
                rows++;
                if (rows % 250 == 0) insert.executeBatch();
            }
            insert.executeBatch();
        }
        return new CopyResult(rows, hex(digest.digest()));
    }

    private CopyResult hashTable(Connection connection, TableSpec table, boolean source) throws Exception {
        String selectSql = "SELECT " + String.join(", ", table.columns) + " FROM " + table.name + " ORDER BY " + table.orderBy;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long rows = 0;
        try (PreparedStatement statement = connection.prepareStatement(selectSql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                updateDigest(digest, readRow(result, table, source));
                rows++;
            }
        }
        return new CopyResult(rows, hex(digest.digest()));
    }

    private List<Object> readRow(ResultSet result, TableSpec table, boolean source) throws Exception {
        List<Object> values = new ArrayList<>(table.columns.size());
        for (String column : table.columns) {
            Object value;
            if (isTextColumn(column)) {
                value = result.getString(column);
            } else if (isInstantColumn(table.name, column)) {
                value = readInstant(result, column);
            } else if ("typing_article_seed_state".equals(table.name) && "initialized_at".equals(column)) {
                Timestamp timestamp = result.getTimestamp(column);
                value = timestamp == null ? null : timestamp.toLocalDateTime();
            } else {
                value = result.getObject(column);
            }

            if (source && "ai_config".equals(table.name) && "encrypted_api_key".equals(column) && value != null) {
                if (oldSecret == null || oldSecret.length() < 16) {
                    throw new IllegalArgumentException("数据库包含 AI Key，必须提供 LEGACY_DATAFORGE_SECRET");
                }
                value = cipher.reencryptFrom(value.toString(), oldSecret);
            }
            if (source && "generation_jobs".equals(table.name) && "artifact_path".equals(column) && value != null) {
                value = migrateArtifact(result.getString("id"));
            }
            values.add(value);
        }
        return values;
    }

    private String migrateArtifact(String jobId) throws Exception {
        UUID id = UUID.fromString(jobId);
        String key = ArtifactStorage.keyFor(id);
        Path source = sourceRuntime.resolve(key).normalize();
        if (!source.startsWith(sourceRuntime) || !Files.isRegularFile(source)) {
            if (allowMissingArtifacts) return null;
            throw new IllegalStateException("任务 " + jobId + " 在数据库中有数据包记录，但 runtime 中缺少 " + key);
        }

        Path target = artifacts.finalZip(id);
        if (Files.exists(target)) {
            if (!sha256(source).equals(sha256(target))) {
                throw new IllegalStateException("目标运行目录已有同名但内容不同的数据包：" + key);
            }
            return key;
        }
        Path pending = artifacts.pendingZip(id);
        Files.deleteIfExists(pending);
        try {
            Files.copy(source, pending);
            artifacts.publish(id, pending);
        } finally {
            Files.deleteIfExists(pending);
        }
        createdArtifacts.add(target);
        return key;
    }

    private void rollbackCreatedArtifacts() {
        for (Path artifact : createdArtifacts) {
            try {
                Files.deleteIfExists(artifact);
            } catch (IOException ignored) {
                System.err.println("迁移回滚时无法删除数据包：" + artifact);
            }
        }
    }

    private void resetUserSequence(Connection target) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT setval(pg_get_serial_sequence('user_accounts', 'id'), " +
                        "COALESCE((SELECT MAX(id) FROM user_accounts), 1), " +
                        "EXISTS(SELECT 1 FROM user_accounts))")) {
            statement.execute();
        }
    }

    private void insertImportMarker(Connection target, String sourceSha, Map<String, Long> counts,
                                    Map<String, String> hashes) throws Exception {
        try (PreparedStatement statement = target.prepareStatement(
                "INSERT INTO legacy_imports (source_sha256, source_file, imported_at, row_counts, row_hashes) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, sourceSha);
            statement.setString(2, sourceFile.getFileName().toString());
            statement.setObject(3, OffsetDateTime.now(ZoneOffset.UTC));
            statement.setString(4, mapper.writeValueAsString(counts));
            statement.setString(5, mapper.writeValueAsString(hashes));
            statement.executeUpdate();
        }
    }

    private static OffsetDateTime readInstant(ResultSet result, String column) throws SQLException {
        Object raw = result.getObject(column);
        if (raw == null) return null;
        if (raw instanceof OffsetDateTime) return ((OffsetDateTime) raw).withOffsetSameInstant(ZoneOffset.UTC);
        if (raw instanceof Instant) return ((Instant) raw).atOffset(ZoneOffset.UTC);
        if (raw instanceof Timestamp) return ((Timestamp) raw).toInstant().atOffset(ZoneOffset.UTC);
        // Hibernate 5 stored Instant in the legacy H2 TIMESTAMP column as a UTC LocalDateTime.
        if (raw instanceof LocalDateTime) return ((LocalDateTime) raw).atOffset(ZoneOffset.UTC);
        throw new SQLException("无法转换时间字段 " + column + "：" + raw.getClass().getName());
    }

    private static boolean isInstantColumn(String table, String column) {
        return "created_at".equals(column) || "updated_at".equals(column) ||
                ("legacy_imports".equals(table) && "imported_at".equals(column));
    }

    private static boolean isTextColumn(String column) {
        return Arrays.asList("error", "statement", "standard_code", "requirements", "plan_json",
                "encrypted_api_key", "content", "artifact_path").contains(column);
    }

    private static void updateDigest(MessageDigest digest, List<Object> values) {
        for (Object value : values) {
            String canonical;
            if (value == null) canonical = "<NULL>";
            else if (value instanceof OffsetDateTime) canonical = ((OffsetDateTime) value).toInstant().toString();
            else if (value instanceof Instant) canonical = value.toString();
            else if (value instanceof LocalDateTime) canonical = value.toString();
            else if (value instanceof Timestamp) canonical = ((Timestamp) value).toInstant().toString();
            else if (value instanceof BigDecimal) canonical = ((BigDecimal) value).stripTrailingZeros().toPlainString();
            else canonical = value.toString();
            byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ);
             DigestInputStream digestInput = new DigestInputStream(input, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (digestInput.read(buffer) >= 0) {
                // DigestInputStream updates the digest.
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static final class TableSpec {
        final String name;
        final String orderBy;
        final List<String> columns;

        TableSpec(String name, String orderBy, String... columns) {
            this.name = name;
            this.orderBy = orderBy;
            this.columns = Arrays.asList(columns);
        }
    }

    private static final class CopyResult {
        final long rows;
        final String hash;

        CopyResult(long rows, String hash) {
            this.rows = rows;
            this.hash = hash;
        }
    }
}
