package cn.datacraft.homework;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class HomeworkMigrationTest {
    @Test void upgradesExistingDatabaseAndEnforcesOnlyOneActiveTaskPerTeacher() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:hw-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).locations("classpath:db/migration").target("7").load().migrate();
        var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO user_accounts(username,password_hash,role,enabled,created_at) VALUES('existing','hash','USER',TRUE,CURRENT_TIMESTAMP)");
        Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username='existing'", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO kedaoj_homework_records(id,owner_key,kind,state,active_key,payload,updated_at) VALUES('a','teacher','TASK','QUEUED','sync:teacher','{}',CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO kedaoj_homework_records(id,owner_key,kind,state,active_key,payload,updated_at) VALUES('b','teacher','TASK','QUEUED','sync:teacher','{}',CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
