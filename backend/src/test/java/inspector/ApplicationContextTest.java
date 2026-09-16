package inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(args = {"--no-index"})
@TestPropertySource(properties = {
        "inspector.db=target/test-context.sqlite",
        "inspector.corpus=fixtures/sessions"})
final class ApplicationContextTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoadsWithTheSchemaApplied() {
        assertThat(jdbc.sql("select count(*) from finding").query(Integer.class).single()).isZero();
    }

    @Test
    void journalModeIsWal() {
        assertThat(jdbc.sql("pragma journal_mode").query(String.class).single())
                .isEqualToIgnoringCase("wal");
    }

    /**
     * Foreign keys are declared in {@code schema.sql} and enforced per connection, a setting
     * SQLite leaves off and that never applies to a whole database file
     * (sqlite.org/foreignkeys.html §2: "must be enabled separately for each database
     * connection"). So the URL parameter is load-bearing, and it has to hold of every
     * connection the pool opens — a single lucky connection would leave the ones opened later
     * accepting orphan findings. Borrowing two at once forces two physical connections;
     * borrowing one after returning it would just hand back the same socket.
     */
    @Test
    void everyConnectionThePoolHandsOutEnforcesForeignKeys() throws SQLException {
        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            assertThat(foreignKeyPragma(first)).as("first pooled connection").isEqualTo(1);
            assertThat(foreignKeyPragma(second)).as("second pooled connection").isEqualTo(1);
        }
    }

    private static int foreignKeyPragma(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("pragma foreign_keys")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    /**
     * The shipped default database path must be openable on a machine that has never built this
     * project. sqlite-jdbc will not create a missing parent directory, so any directory in the
     * default turns `java -jar` into SQLITE_CANTOPEN on a fresh clone — which no other test can
     * see, because every test here overrides inspector.db with a temp path.
     */
    @Test
    void theDefaultDatabasePathNeedsNoDirectoryThatMightNotExist() throws IOException {
        final String url;
        try (var in = getClass().getResourceAsStream("/application.yml")) {
            url = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        final Matcher m = Pattern.compile("\\$\\{inspector\\.db:([^}]*)}").matcher(url);
        assertThat(m.find()).as("application.yml defines ${inspector.db:...}").isTrue();
        assertThat(m.group(1))
                .as("default db path is relative to the working directory, no missing parent")
                .doesNotContain("/");
    }
}
