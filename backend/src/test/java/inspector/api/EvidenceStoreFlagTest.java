package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

/**
 * {@code inspector.evidence.store=false} (DESIGN.md §7): the finding and its
 * causal sequence survive, but no shell excerpt is stored — so the detail
 * endpoint has nothing to leak either. No Spring context: the pipeline is
 * hand-constructed into a temp-dir database.
 */
final class EvidenceStoreFlagTest {

    @TempDir
    static Path temp;

    @Test
    void evidenceFlagOffStoresNothingButKeepsTheCausalSequence() {
        final Path dbFile = temp.resolve("no-evidence.sqlite");
        IndexedCorpus.index(dbFile, Path.of("fixtures/sessions"), IndexedCorpus.MAIN_VERSION, false);

        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // no excerpt is stored anywhere
        assertThat(jdbc.queryForObject("select count(*) from shell_evidence", Long.class)).isZero();

        // the findings and their causal sequences are unaffected:
        // s-01's stamp finding still points at tool call 13
        assertThat(jdbc.queryForObject("select count(*) from finding", Long.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject(
                "select cause_seq from finding where session_id = 's-01' and detector = 'stamp-guard'",
                Long.class)).isEqualTo(13L);
    }
}
