package inspector.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The DDL on its own terms: what the schema file creates, and whether running it twice is the
 * same as running it once.
 *
 * <p>No Spring context. This used to be a boot test whose only reason for existing was to be
 * handed a {@code JdbcClient} — the same trade {@code QueryPlanTest} and {@code
 * IndexWriterTest} already make, applying the script to a temp file directly. What the context
 * proved is that the application applies the script when it boots, and that is
 * {@code ApplicationContextTest.contextLoadsWithTheSchemaApplied}, which keeps doing it.
 */
final class SchemaTest {

    @TempDir
    Path temp;

    private JdbcTemplate jdbc;

    @BeforeEach
    void createTheDatabaseFromTheShippedScript() throws IOException {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("schema.sqlite"));
        jdbc = new JdbcTemplate(dataSource);
        applySchema();
    }

    private void applySchema() throws IOException {
        final String ddl;
        try (InputStream in = new ClassPathResource("schema.sql").getInputStream()) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (final String statement : ddl.split(";")) {
            if (!statement.isBlank()) {
                final String sql = statement.trim();
                assertThatCode(() -> jdbc.execute(sql)).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void allSixTablesExist() {
        assertThat(jdbc.queryForList("select name from sqlite_master where type='table' "
                + "and name not like 'sqlite_%'", String.class))
                .contains("session", "step", "tool_call", "finding", "shell_evidence", "meta");
    }

    /**
     * The script runs on every boot against a database an older build may have created, so
     * every statement has to survive a second application. Re-running it over the schema it
     * just made is the only way that is checked.
     */
    @Test
    void ddlIsIdempotent() throws IOException {
        applySchema();
        assertThat(jdbc.queryForList("select name from sqlite_master where type='table' "
                + "and name not like 'sqlite_%'", String.class))
                .contains("session", "step", "tool_call", "finding", "shell_evidence", "meta");
    }

    /**
     * {@code path_hint} is a redacted fragment and is deliberately never indexed: an index
     * would put its values, verbatim, into a b-tree that ships inside the database file.
     */
    @Test
    void nothingIndexesPathHint() {
        assertThat(jdbc.queryForList("select sql from sqlite_master where type='index' "
                + "and sql is not null", String.class)).noneMatch(sql -> sql.contains("path_hint"));
    }
}
