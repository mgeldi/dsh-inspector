package inspector.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileCopyUtils;

@SpringBootTest
@TestPropertySource(properties = {"inspector.db=target/schema.sqlite"})
final class SchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void allSixTablesExist() {
        assertThat(jdbc.sql("select name from sqlite_master where type='table' "
                + "and name not like 'sqlite_%'").query(String.class).list())
                .contains("session", "step", "tool_call", "finding", "shell_evidence", "meta");
    }

    @Test
    void ddlIsIdempotent() throws Exception {
        final String ddl = new String(FileCopyUtils.copyToByteArray(
                new ClassPathResource("schema.sql").getInputStream()), StandardCharsets.UTF_8);
        for (final String statement : ddl.split(";")) {
            if (!statement.isBlank()) {
                final String sql = statement.trim();
                assertThatCode(() -> jdbc.sql(sql).update()).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void nothingIndexesPathHint() {
        assertThat(jdbc.sql("select sql from sqlite_master where type='index' and sql is not null")
                .query(String.class).list()).noneMatch(s -> s.contains("path_hint"));
    }
}
