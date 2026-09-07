package cn.datacraft.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AtcoderMultiContestMigrationTest {

    @Test
    void upgradesSingletonConfigWithoutLosingConfigOrTranslations() {
        String url = "jdbc:h2:mem:atcoder-multi-contest-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("5").load().migrate();
        jdbc.update("""
                INSERT INTO atcoder_leaderboard_config
                    (id, contest_id, display_title, official_title, tasks_json, updated_at)
                VALUES (1, 'abc430', '历史排行榜', 'AtCoder Beginner Contest 430', '[]', CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO atcoder_problem_translations
                    (contest_id, task_id, task_label, task_name, task_order, translated_html, status, updated_at)
                VALUES ('abc430', 'abc430_a', 'A', 'Warm Up', 0, '<p>最新译文</p>', 'READY', CURRENT_TIMESTAMP)
                """);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject(
                "SELECT display_title FROM atcoder_leaderboard_config WHERE contest_id='abc430'", String.class))
                .isEqualTo("历史排行榜");
        assertThat(jdbc.queryForObject(
                "SELECT translated_html FROM atcoder_problem_translations WHERE contest_id='abc430'", String.class))
                .isEqualTo("<p>最新译文</p>");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM atcoder_leaderboard_snapshots", Integer.class)).isZero();

        jdbc.update("""
                INSERT INTO atcoder_leaderboard_config
                    (id, contest_id, display_title, official_title, tasks_json, updated_at)
                VALUES (NEXT VALUE FOR atcoder_leaderboard_config_seq,
                    'abc431', '新排行榜', 'AtCoder Beginner Contest 431', '[]', CURRENT_TIMESTAMP)
                """);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM atcoder_leaderboard_config WHERE contest_id='abc431'", Long.class))
                .isGreaterThan(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM atcoder_problem_translations WHERE contest_id='abc430'", Integer.class))
                .isEqualTo(1);
    }
}
