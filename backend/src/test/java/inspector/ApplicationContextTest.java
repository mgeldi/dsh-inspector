package inspector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "inspector.db=target/test-context.sqlite",
        "inspector.corpus=fixtures/sessions"})
final class ApplicationContextTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void contextLoadsWithTheSchemaApplied() {
        assertThat(jdbc.sql("select count(*) from finding").query(Integer.class).single()).isZero();
    }

    @Test
    void journalModeIsWal() {
        assertThat(jdbc.sql("pragma journal_mode").query(String.class).single())
                .isEqualToIgnoringCase("wal");
    }
}
