package cn.datacraft.migration;

import cn.datacraft.ai.SecretCipher;
import cn.datacraft.job.ArtifactStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class LegacyH2ImportIntegrationTest {
    private static final String OLD_SECRET = "legacy-secret-0123456789abcdef";
    private static final String NEW_SECRET = "production-secret-0123456789abcdef";
    private static final String PASSWORD_HASH = "$2a$10$123456789012345678901u12345678901234567890123456789012";
    private static final UUID JOB_ID = UUID.fromString("4fb70dd6-e966-47c0-89f2-eb54469f65ac");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 20, 9, 11, 34, 200_937_000);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-bookworm")
            .withDatabaseName("dataforge_import")
            .withUsername("dataforge")
            .withPassword("dataforge-test-password");

    @TempDir Path tempDir;
    private DataSource target;
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetPostgres() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        target = dataSource;
        jdbc = new JdbcTemplate(target);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void importsAllTablesArtifactsSecretsTimestampsAndSequenceOnlyOnce() throws Exception {
        LegacySource source = createLegacySource(true);
        Path targetRuntime = Files.createDirectory(tempDir.resolve("target-runtime"));
        SecretCipher newCipher = new SecretCipher(NEW_SECRET);
        LegacyH2ImportRunner importer = importer(source, targetRuntime, OLD_SECRET, newCipher);

        importer.importDatabase();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM user_accounts WHERE username='legacy_user'", Long.class))
                .isEqualTo(42L);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=42", String.class))
                .isEqualTo(PASSWORD_HASH);
        assertThat(jdbc.queryForObject("SELECT LENGTH(statement) FROM generation_jobs", Integer.class))
                .isGreaterThan(8_000);
        assertThat(jdbc.queryForObject("SELECT artifact_path FROM generation_jobs", String.class))
                .isEqualTo(ArtifactStorage.keyFor(JOB_ID));
        String encrypted = jdbc.queryForObject("SELECT encrypted_api_key FROM ai_config WHERE id=1", String.class);
        assertThat(newCipher.decrypt(encrypted)).isEqualTo("legacy-api-key");
        OffsetDateTime created = jdbc.queryForObject(
                "SELECT created_at FROM user_accounts WHERE id=42", OffsetDateTime.class);
        assertThat(created.toInstant()).isEqualTo(CREATED_AT.toInstant(ZoneOffset.UTC));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM typing_articles", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM typing_article_seed_state", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT contest_id FROM atcoder_leaderboard_config WHERE id=1", String.class))
                .isEqualTo("abc430");
        assertThat(jdbc.queryForObject(
                "SELECT atcoder_username FROM atcoder_leaderboard_participants WHERE id=71", String.class))
                .isEqualTo("legacy_coder");
        String encryptedCookie = jdbc.queryForObject(
                "SELECT encrypted_cookie FROM atcoder_cookie_config WHERE id=1", String.class);
        assertThat(newCipher.decrypt(encryptedCookie)).isEqualTo("REVEL_SESSION=legacy-cookie");
        assertThat(jdbc.queryForObject(
                "SELECT draft_html FROM atcoder_problem_translations WHERE id=88", String.class))
                .isEqualTo("<p>人工修订稿</p>");
        OffsetDateTime fetchedAt = jdbc.queryForObject(
                "SELECT source_fetched_at FROM atcoder_problem_translations WHERE id=88", OffsetDateTime.class);
        assertThat(fetchedAt.toInstant()).isEqualTo(CREATED_AT.plusMinutes(10).toInstant(ZoneOffset.UTC));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_imports", Integer.class)).isEqualTo(1);
        assertThat(Files.readString(targetRuntime.resolve(ArtifactStorage.keyFor(JOB_ID))))
                .isEqualTo("legacy-zip-content");

        Long nextId = jdbc.queryForObject(
                "INSERT INTO user_accounts (username, password_hash, role, enabled, created_at) " +
                        "VALUES ('after_import', 'hash', 'USER', TRUE, NOW()) RETURNING id",
                Long.class);
        assertThat(nextId).isGreaterThan(42L);
        Long nextParticipantId = jdbc.queryForObject(
                "INSERT INTO atcoder_leaderboard_participants " +
                        "(display_name, atcoder_username, atcoder_username_key, sort_order, created_at) " +
                        "VALUES ('new coder', 'new_coder', 'new_coder', 2, NOW()) RETURNING id",
                Long.class);
        assertThat(nextParticipantId).isGreaterThan(71L);
        Long nextTranslationId = jdbc.queryForObject(
                "INSERT INTO atcoder_problem_translations " +
                        "(contest_id, task_id, task_label, task_name, task_order, status, updated_at) " +
                        "VALUES ('abc430', 'abc430_b', 'B', 'New task', 2, 'QUEUED', NOW()) RETURNING id",
                Long.class);
        assertThat(nextTranslationId).isGreaterThan(88L);

        importer.importDatabase();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM atcoder_leaderboard_participants", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM atcoder_problem_translations", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_imports", Integer.class)).isEqualTo(1);
    }

    @Test
    void wrongLegacySecretRollsBackDatabaseAndCopiedArtifact() throws Exception {
        LegacySource source = createLegacySource(true);
        Path targetRuntime = Files.createDirectory(tempDir.resolve("target-runtime"));
        LegacyH2ImportRunner importer = importer(
                source, targetRuntime, "wrong-legacy-secret-0123456789", new SecretCipher(NEW_SECRET));

        assertThatThrownBy(importer::importDatabase)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATAFORGE_SECRET");

        assertBusinessTablesAreEmpty();
        assertThat(targetRuntime.resolve(ArtifactStorage.keyFor(JOB_ID))).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_imports", Integer.class)).isZero();
    }

    @Test
    void missingDeclaredArtifactRollsBackRows() throws Exception {
        LegacySource source = createLegacySource(false);
        Path targetRuntime = Files.createDirectory(tempDir.resolve("target-runtime"));
        LegacyH2ImportRunner importer = importer(source, targetRuntime, OLD_SECRET, new SecretCipher(NEW_SECRET));

        assertThatThrownBy(importer::importDatabase)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少");

        assertBusinessTablesAreEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_imports", Integer.class)).isZero();
    }

    @Test
    void refusesNonEmptyTargetBeforeCopyingAnything() throws Exception {
        LegacySource source = createLegacySource(true);
        Path targetRuntime = Files.createDirectory(tempDir.resolve("target-runtime"));
        jdbc.update("INSERT INTO user_accounts (username, password_hash, role, enabled, created_at) " +
                "VALUES ('existing', 'hash', 'ADMIN', TRUE, NOW())");

        LegacyH2ImportRunner importer = importer(source, targetRuntime, OLD_SECRET, new SecretCipher(NEW_SECRET));

        assertThatThrownBy(importer::importDatabase)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目标数据库不是空库");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class)).isEqualTo(1);
        assertThat(targetRuntime.resolve(ArtifactStorage.keyFor(JOB_ID))).doesNotExist();
    }

    private LegacyH2ImportRunner importer(LegacySource source, Path targetRuntime, String oldSecret,
                                          SecretCipher newCipher) throws Exception {
        return new LegacyH2ImportRunner(
                target,
                new ArtifactStorage(targetRuntime.toString()),
                newCipher,
                new ObjectMapper(),
                null,
                source.databaseFile.toString(),
                source.runtime.toString(),
                "sa",
                "",
                oldSecret,
                false);
    }

    private void assertBusinessTablesAreEmpty() {
        for (String table : new String[]{"user_accounts", "generation_jobs", "ai_config",
                "typing_articles", "typing_article_seed_state", "atcoder_leaderboard_config",
                "atcoder_leaderboard_participants", "atcoder_cookie_config", "atcoder_problem_translations"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .as(table)
                    .isZero();
        }
    }

    private LegacySource createLegacySource(boolean createArtifact) throws Exception {
        Path runtime = Files.createDirectory(tempDir.resolve("legacy-runtime"));
        if (createArtifact) {
            Files.writeString(runtime.resolve(ArtifactStorage.keyFor(JOB_ID)),
                    "legacy-zip-content", StandardCharsets.UTF_8);
        }
        Path databaseBase = tempDir.resolve("legacy-dataforge");
        String url = "jdbc:h2:file:" + databaseBase.toAbsolutePath();
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE user_accounts (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "username VARCHAR(40) NOT NULL, password_hash VARCHAR(100) NOT NULL, role VARCHAR(16) NOT NULL, " +
                    "enabled BOOLEAN NOT NULL, daily_generation_limit INTEGER, created_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE generation_jobs (id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, " +
                    "status VARCHAR(32) NOT NULL, progress INTEGER NOT NULL, message VARCHAR(500), error CLOB, " +
                    "statement CLOB NOT NULL, standard_code CLOB NOT NULL, requirements CLOB NOT NULL, " +
                    "case_count INTEGER NOT NULL, cpp_standard VARCHAR(12), plan_json CLOB, artifact_path VARCHAR(1000), " +
                    "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE ai_config (id BIGINT PRIMARY KEY, base_url VARCHAR(500), model VARCHAR(120), " +
                    "encrypted_api_key CLOB, daily_generation_limit INTEGER, updated_at TIMESTAMP)");
            statement.execute("CREATE TABLE typing_articles (id VARCHAR(64) PRIMARY KEY, title VARCHAR(80) NOT NULL, " +
                    "category VARCHAR(12) NOT NULL, content CLOB NOT NULL)");
            statement.execute("CREATE TABLE typing_article_seed_state (id VARCHAR(40) PRIMARY KEY, " +
                    "initialized_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE atcoder_leaderboard_config (id BIGINT PRIMARY KEY, " +
                    "contest_id VARCHAR(64) NOT NULL, display_title VARCHAR(160) NOT NULL, " +
                    "official_title VARCHAR(240) NOT NULL, start_at TIMESTAMP, end_at TIMESTAMP, " +
                    "tasks_json CLOB NOT NULL, updated_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE atcoder_leaderboard_participants (" +
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, display_name VARCHAR(40) NOT NULL, " +
                    "atcoder_username VARCHAR(32) NOT NULL, atcoder_username_key VARCHAR(32) NOT NULL UNIQUE, " +
                    "sort_order INTEGER NOT NULL, created_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE atcoder_cookie_config (id BIGINT PRIMARY KEY, " +
                    "encrypted_cookie CLOB NOT NULL, updated_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE atcoder_problem_translations (" +
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, contest_id VARCHAR(64) NOT NULL, " +
                    "task_id VARCHAR(128) NOT NULL, task_label VARCHAR(16) NOT NULL, task_name VARCHAR(240) NOT NULL, " +
                    "task_order INTEGER NOT NULL, source_html CLOB, translated_html CLOB, draft_html CLOB, " +
                    "status VARCHAR(24) NOT NULL, error_message CLOB, source_fetched_at TIMESTAMP, " +
                    "translated_at TIMESTAMP, updated_at TIMESTAMP NOT NULL)");

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO user_accounts VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, 42L);
                insert.setString(2, "legacy_user");
                insert.setString(3, PASSWORD_HASH);
                insert.setString(4, "USER");
                insert.setBoolean(5, true);
                insert.setInt(6, 77);
                insert.setObject(7, CREATED_AT);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO generation_jobs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, JOB_ID.toString());
                insert.setLong(2, 42L);
                insert.setString(3, "COMPLETED");
                insert.setInt(4, 100);
                insert.setString(5, "done");
                insert.setString(6, null);
                insert.setString(7, "长题面".repeat(3_000));
                insert.setString(8, "int main(){return 0;}");
                insert.setString(9, "requirements");
                insert.setInt(10, 10);
                insert.setString(11, "c++17");
                insert.setString(12, "{\"strategy\":\"legacy\"}");
                insert.setString(13, runtime.resolve(ArtifactStorage.keyFor(JOB_ID)).toAbsolutePath().toString());
                insert.setObject(14, CREATED_AT);
                insert.setObject(15, CREATED_AT.plusMinutes(5));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ai_config VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, 1L);
                insert.setString(2, "https://legacy.example/v1");
                insert.setString(3, "legacy-model");
                insert.setString(4, new SecretCipher(OLD_SECRET).encrypt("legacy-api-key"));
                insert.setInt(5, 55);
                insert.setObject(6, CREATED_AT);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO typing_articles VALUES (?, ?, ?, ?)")) {
                insert.setString(1, "legacy-article");
                insert.setString(2, "历史文章");
                insert.setString(3, "ZH");
                insert.setString(4, "文章内容".repeat(2_000));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO typing_article_seed_state VALUES (?, ?)")) {
                insert.setString(1, "default-v1");
                insert.setObject(2, CREATED_AT);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO atcoder_leaderboard_config VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, 1L);
                insert.setString(2, "abc430");
                insert.setString(3, "ABC 430 排行榜");
                insert.setString(4, "AtCoder Beginner Contest 430");
                insert.setObject(5, CREATED_AT.plusHours(1));
                insert.setObject(6, CREATED_AT.plusHours(3));
                insert.setString(7, "[{\"id\":\"abc430_a\",\"label\":\"A\"}]");
                insert.setObject(8, CREATED_AT.plusMinutes(1));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO atcoder_leaderboard_participants VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, 71L);
                insert.setString(2, "历史选手");
                insert.setString(3, "legacy_coder");
                insert.setString(4, "legacy_coder");
                insert.setInt(5, 1);
                insert.setObject(6, CREATED_AT.plusMinutes(2));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO atcoder_cookie_config VALUES (?, ?, ?)")) {
                insert.setLong(1, 1L);
                insert.setString(2, new SecretCipher(OLD_SECRET).encrypt("REVEL_SESSION=legacy-cookie"));
                insert.setObject(3, CREATED_AT.plusMinutes(3));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO atcoder_problem_translations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, 88L);
                insert.setString(2, "abc430");
                insert.setString(3, "abc430_a");
                insert.setString(4, "A");
                insert.setString(5, "历史题目");
                insert.setInt(6, 1);
                insert.setString(7, "<p>Source</p>");
                insert.setString(8, "<p>历史译文</p>");
                insert.setString(9, "<p>人工修订稿</p>");
                insert.setString(10, "READY");
                insert.setString(11, null);
                insert.setObject(12, CREATED_AT.plusMinutes(10));
                insert.setObject(13, CREATED_AT.plusMinutes(11));
                insert.setObject(14, CREATED_AT.plusMinutes(12));
                insert.executeUpdate();
            }
        }
        return new LegacySource(Path.of(databaseBase + ".mv.db"), runtime);
    }

    private static final class LegacySource {
        final Path databaseFile;
        final Path runtime;

        LegacySource(Path databaseFile, Path runtime) {
            this.databaseFile = databaseFile;
            this.runtime = runtime;
        }
    }
}
