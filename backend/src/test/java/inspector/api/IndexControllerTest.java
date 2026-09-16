package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

/**
 * {@code POST /api/index/run} (DESIGN.md §7): a re-run over the configured
 * corpus is idempotent, and the response is the counts-only
 * {@code IndexSummary} — no paths, no content.
 *
 * <p>The setup deliberately holds two corpora while only {@code fixtures/sessions} is
 * configured, which is the state a production install cannot reach (the startup reset clears
 * an index built from another corpus). It is kept here because it is the one case the prune
 * must refuse: rows this run never read are not its corpus, and removing them is
 * {@code resetIfCorpusChanged}'s job. What a prune actually does is exercised over at
 * {@link VanishedSessionsAreNotServedTest}, where the index really does describe the corpus.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
class IndexControllerTest {

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("index.sqlite").toString());
        registry.add("inspector.corpus", () -> "fixtures/sessions");
        registry.add("inspector.harness-version", () -> IndexedCorpus.MAIN_VERSION);
    }

    @BeforeAll
    static void indexCorpus() {
        IndexedCorpus.indexBoth(temp.resolve("index.sqlite"));
    }

    private long count(final String sql) {
        return jdbc.sql(sql).query((RowMapper<Long>) (rs, rowNum) -> rs.getLong(1)).single();
    }

    @Test
    void runIndexesTheConfiguredCorpusAndReportsCountsOnly() throws Exception {
        // Re-indexed here rather than trusted from @BeforeAll: the previous test's run
        // re-attributes the index, and this assertion is about the state right after a corpus
        // switch. The re-run over fixtures/sessions replaces the 12 streams; the 3 rows from
        // sessions-b survive — the index was seeded from that other corpus, so this run
        // prunes nothing it never read — so 15 rows remain. But one session is written in
        // both conventions, so the honest session count is 14, not 15.
        IndexedCorpus.indexBoth(temp.resolve("index.sqlite"));

        mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streams").value(12))
                .andExpect(jsonPath("$.sessions").value(14))
                .andExpect(jsonPath("$.toolCalls").value(32))
                .andExpect(jsonPath("$.findings").value(9))
                .andExpect(jsonPath("$.pruned").value(0))
                .andExpect(jsonPath("$.parseFailures").value(0))
                .andExpect(jsonPath("$.durationMs").isNumber());

        // the database matches: the re-run changed nothing structurally
        assertThat(count("select count(*) from session")).isEqualTo(15);
        assertThat(count("select count(*) from finding")).isEqualTo(12);
        assertThat(count("select count(*) from tool_call")).isEqualTo(41);
    }

    @Test
    void theForeignStreamsGoOnTheRunTheIndexIsAttributedTo() throws Exception {
        // The boundary of the guard, from both sides. The first run is not attributed to this
        // corpus — the index says it came from sessions-b — so it leaves those rows alone and
        // records the corpus it did read. From the next run on, the index describes this
        // corpus, and the rows it never scans are the corpus losing them: they go.
        IndexedCorpus.indexBoth(temp.resolve("index.sqlite"));

        mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pruned").value(0));
        assertThat(count("select count(*) from session")).isEqualTo(15);

        mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pruned").value(3))
                .andExpect(jsonPath("$.sessions").value(11));

        assertThat(count("select count(*) from session")).isEqualTo(12);
        assertThat(count("select count(*) from finding")).isEqualTo(9);
        assertThat(count("select count(*) from shell_evidence where finding_id not in"
                + " (select id from finding)")).isZero();
    }

    @Test
    void aRunReportsCountsOnly() throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isOk())
                .andReturn();

        // counts only: the summary is the index's one self-report, and a path in it would
        // carry the username of whoever configured the corpus
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("fixtures").doesNotContain(temp.toString());
    }
}
