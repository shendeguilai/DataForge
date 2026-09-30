package cn.datacraft.cspsim;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class CspMigrationTest {
    @Test void addsDurableRecordsWithoutChangingExistingUsers() {
        var db=new DriverManagerDataSource("jdbc:h2:mem:csp-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        db.setDriverClassName("org.h2.Driver");
        Flyway.configure().dataSource(db).locations("classpath:db/migration").target("6").load().migrate();
        var jdbc=new JdbcTemplate(db);
        jdbc.update("INSERT INTO user_accounts(username,password_hash,role,enabled,daily_generation_limit,created_at) VALUES('existing','hash','USER',TRUE,30,CURRENT_TIMESTAMP)");
        Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username='existing'",Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO csp_sim_records(id,kind,owner_key,state,payload,updated_at) VALUES('test','EXAM','existing','DRAFT','{}',CURRENT_TIMESTAMP)");
        assertThat(jdbc.queryForObject("SELECT version FROM csp_sim_records WHERE id='test'",Long.class)).isZero();
    }
}
