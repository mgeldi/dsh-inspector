package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

/**
 * The one-row cohort (DESIGN.md §7: "the screen states which basis it is
 * showing"). No axis in the two-corpus index is single-valued, so this test
 * indexes a single stream into its own database: every groupBy axis then has
 * exactly one row, and the response must say so in {@code basisNote}
 * instead of pretending to compare.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
class OneRowCohortsTest {

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("onerow.sqlite").toString());
        registry.add("inspector.corpus", () -> "fixtures/sessions/demo-app/s-01");
        registry.add("inspector.harness-version", () -> IndexedCorpus.MAIN_VERSION);
    }

    @BeforeAll
    static void indexSingleStream() {
        IndexedCorpus.index(temp.resolve("onerow.sqlite"),
                Path.of("fixtures/sessions/demo-app/s-01"), IndexedCorpus.MAIN_VERSION);
    }

    @Test
    void oneRowCohortStatesItsBasis() throws Exception {
        final String preset = jdbc.sql("select coalesce(agent_preset, 'unknown') from session")
                .query(String.class).single();

        final MvcResult result = mockMvc.perform(get("/api/cohorts?groupBy=preset"))
                .andExpect(status().isOk())
                .andReturn();
        final String body = result.getResponse().getContentAsString();
        final DocumentContext json = JsonPath.parse(body);

        assertThat(json.read("$.groupBy", String.class)).isEqualTo("preset");
        assertThat(json.read("$.cohorts.length()", Integer.class)).isEqualTo(1);
        assertThat(json.read("$.cohorts[0].key", String.class)).isEqualTo(preset);
        assertThat(json.read("$.cohorts[0].findingsPerKCallsDelta", Double.class)).isEqualTo(0.0);
        assertThat(json.read("$.cohorts[0].violationRatePerKDelta", Double.class)).isEqualTo(0.0);

        final String basisNote = json.read("$.basisNote", String.class);
        assertThat(basisNote).contains("one-row cohort");
        assertThat(basisNote).contains("description, not a comparison");
        // the single session is version_inferred, and the baseline was still chosen
        assertThat(basisNote).contains("inferred");
        assertThat(basisNote).contains("chosen by highest tool-call count");
    }
}
