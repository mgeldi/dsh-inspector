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

import java.nio.file.Path;

/**
 * {@code POST /api/index/run} (DESIGN.md §7): a re-run over the configured
 * corpus is idempotent, and the response is the counts-only
 * {@code IndexSummary} — no paths, no content.
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
        // the re-run over fixtures/sessions replaces the 12 streams; the 3
        // rows from sessions-b survive, so the session total is 15
        mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streams").value(12))
                .andExpect(jsonPath("$.sessions").value(15))
                .andExpect(jsonPath("$.toolCalls").value(32))
                .andExpect(jsonPath("$.findings").value(9))
                .andExpect(jsonPath("$.parseFailures").value(0))
                .andExpect(jsonPath("$.durationMs").isNumber());

        // the database matches: the re-run changed nothing structurally
        assertThat(count("select count(*) from session")).isEqualTo(15);
        assertThat(count("select count(*) from finding")).isEqualTo(12);
        assertThat(count("select count(*) from tool_call")).isEqualTo(41);
    }
}
