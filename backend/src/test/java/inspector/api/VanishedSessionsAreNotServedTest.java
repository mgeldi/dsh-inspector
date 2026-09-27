package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The reproduction of the pruning defect, kept as a test: copy the fixture corpus to a temp
 * directory, index it, remove one session from the copy, re-index through the endpoint.
 *
 * <p>Before the fix the run and the dashboard disagreed on the same screen — the summary line
 * reported the findings of the streams it wrote, the tiles reported everything the database
 * still held, and the difference belonged to a session no longer on disk. So the assertions
 * here are comparisons between the two numbers a user can see at once, not counts pulled from
 * a plan: whichever session is removed, the screen has to agree with itself.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
class VanishedSessionsAreNotServedTest {

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A writable copy, because the committed fixtures are never edited by a test. */
    private static Path corpusCopy() {
        final Path copy = temp.resolve("corpus");
        try (Stream<Path> paths = Files.walk(Path.of("fixtures/sessions"))) {
            for (final Path source : paths.toList()) {
                final Path target = copy.resolve(Path.of("fixtures/sessions").relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return copy;
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("prune.sqlite").toString());
        registry.add("inspector.corpus", () -> corpusCopy().toString());
        registry.add("inspector.harness-version", () -> IndexedCorpus.MAIN_VERSION);
    }

    @BeforeAll
    static void indexCorpus() {
        IndexedCorpus.index(temp.resolve("prune.sqlite"), corpusCopy(), IndexedCorpus.MAIN_VERSION);
    }

    private long count(final String sql, final Object... args) {
        return jdbc.sql(sql).params(args).query((RowMapper<Long>) (rs, rowNum) -> rs.getLong(1)).single();
    }

    private JsonNode body(final String url) throws Exception {
        return mapper.readTree(mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private JsonNode reindex() throws Exception {
        return mapper.readTree(mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /**
     * Back to the starting state — the whole corpus on disk, every stream of it in the index —
     * so each test holds whatever ran before it at the same beginning instead of depending on
     * a method order nobody wrote down.
     */
    private void restoreCorpusAndIndex() {
        IndexedCorpus.index(temp.resolve("prune.sqlite"), corpusCopy(), IndexedCorpus.MAIN_VERSION);
    }

    @Test
    void aSessionRemovedFromTheCorpusStopsBeingServedOnTheNextRun() throws Exception {
        restoreCorpusAndIndex();
        record Target(String slug, String sid, String file, long findings) {
        }
        // Whichever stream goes, the one carrying the most findings goes with it, so the
        // numbers have to move and a prune that did nothing cannot pass by leaving them equal.
        final Target target = jdbc.sql(
                "select s.project_slug as slug, f.session_id as sid, f.source_file as file,"
                        + " count(*) as n from finding f"
                        + " join session s on s.id = f.session_id and s.source_file = f.source_file"
                        + " group by 1, 2, 3 order by n desc, sid asc, file asc")
                .query((rs, rowNum) -> new Target(rs.getString("slug"), rs.getString("sid"),
                        rs.getString("file"), rs.getLong("n")))
                .list().get(0);
        final String sid = target.sid();
        final String file = target.file();
        final long findingsOfThatStream = target.findings();

        final long findingsBefore = count("select count(*) from finding");
        final long tilesFindingsBefore = body("/api/overview").path("tiles").path("findings").asLong();
        assertThat(tilesFindingsBefore).as("ground truth before the removal").isEqualTo(findingsBefore);

        Files.delete(corpusCopy().resolve(target.slug()).resolve(sid).resolve(file));

        final JsonNode summary = reindex();
        final JsonNode tiles = body("/api/overview").path("tiles");

        // the run noticed: one stream it did not write, one fewer on the wire than on disk
        assertThat(summary.path("pruned").asInt()).isEqualTo(1);
        assertThat(summary.path("streams").asInt()).isEqualTo(12);
        assertThat(summary.path("findings").asInt()).isEqualTo(findingsBefore - findingsOfThatStream);

        // the screen agrees with itself: no tile carries a row the run did not write
        assertThat(tiles.path("findings").asLong()).isEqualTo(summary.path("findings").asInt());
        assertThat(tiles.path("sessions").asLong()).isEqualTo(summary.path("sessions").asInt());
        assertThat(tiles.path("steps").asLong()).isEqualTo(summary.path("steps").asInt());
        assertThat(tiles.path("findings").asLong())
                .as("the tile dropped by exactly the removed stream's findings")
                .isEqualTo(tilesFindingsBefore - findingsOfThatStream);

        // and the removed stream left every table it appeared in
        assertThat(count("select count(*) from session where id = ? and source_file = ?", sid, file)).isZero();
        assertThat(count("select count(*) from step where session_id = ? and source_file = ?", sid, file)).isZero();
        assertThat(count("select count(*) from tool_call where session_id = ? and source_file = ?", sid, file)).isZero();
        assertThat(count("select count(*) from finding where session_id = ? and source_file = ?", sid, file)).isZero();
        assertThat(count("select count(*) from shell_evidence where finding_id in"
                + " (select id from finding where session_id = ? and source_file = ?)", sid, file)).isZero();
        assertThat(count("select count(*) from shell_evidence where finding_id not in"
                + " (select id from finding)")).isZero();
    }

    @Test
    void aRunOverAnIndexThatAlreadyMatchesTheCorpusPrunesNothing() throws Exception {
        restoreCorpusAndIndex();
        reindex();

        final JsonNode second = reindex();

        assertThat(second.path("pruned").asInt()).isZero();
        assertThat(second.path("streams").asInt())
                .as("every stream in the index was written by this run")
                .isEqualTo(count("select count(*) from session"));
    }
}
