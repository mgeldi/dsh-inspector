package inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
