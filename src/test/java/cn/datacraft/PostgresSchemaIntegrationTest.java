package cn.datacraft;

import cn.datacraft.job.GenerationJob;
import cn.datacraft.job.GenerationRequest;
import cn.datacraft.job.JobRepository;
import cn.datacraft.user.UserAccount;
import cn.datacraft.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("prod")
@SpringBootTest(properties = {
        "dataforge.admin.username=integration_admin",
        "dataforge.admin.password=integration-admin-password",
        "dataforge.invite-code=integration-invite",
        "dataforge.crypto-secret=integration-encryption-secret-1234"
})
class PostgresSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-bookworm")
            .withDatabaseName("dataforge")
            .withUsername("dataforge")
            .withPassword("dataforge-test-password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        String runtime = Files.createTempDirectory("dataforge-postgres-test-").toString();
        registry.add("dataforge.runtime-dir", () -> runtime);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired JobRepository jobs;

    @Test
    void flywaySchemaMatchesHibernateAndSeedsBootstrapData() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE role = 'ADMIN'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM typing_articles", Integer.class))
                .isPositive();
    }

    @Test
    void repositoriesRoundTripLongTextTimestampsAndIdentitySequence() {
        UserAccount user = users.register("repo_user", "repository-password", "integration-invite");
        GenerationRequest request = new GenerationRequest();
        request.setStatement("长题面".repeat(12_000));
        request.setStandardCode("int main(){return 0;}".repeat(2_000));
        request.setRequirements("要求".repeat(4_000));
        request.setCaseCount(12);
        request.setCppStandard("c++17");
        GenerationJob job = new GenerationJob();
        job.setUserId(user.getId());
        job.setRequest(request);

        jobs.save(job);
        GenerationJob loaded = jobs.find(job.getId()).orElseThrow();

        assertThat(user.getId()).isGreaterThan(1L);
        assertThat(loaded.getRequest().getStatement()).isEqualTo(request.getStatement());
        assertThat(loaded.getRequest().getStandardCode()).isEqualTo(request.getStandardCode());
        assertThat(Duration.between(job.getCreatedAt(), loaded.getCreatedAt()).abs()).isLessThan(Duration.ofMillis(1));
    }
}
