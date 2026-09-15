package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The cohorts route over the two-corpus index (DESIGN.md §7). The corpora
 * stand in for two harness versions, so the default axis has two rows and
 * the screen can compare. Rates are per 1,000 tool calls; deltas are
 * percentage points against the chosen baseline.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
class CohortsControllerTest {

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("cohorts.sqlite").toString());
        registry.add("inspector.corpus", () -> "fixtures/sessions");
        registry.add("inspector.harness-version", () -> IndexedCorpus.MAIN_VERSION);
    }

    @BeforeAll
    static void indexCorpus() {
        IndexedCorpus.indexBoth(temp.resolve("cohorts.sqlite"));
    }

    private long count(final String sql) {
        return jdbc.sql(sql).query((RowMapper<Long>) (rs, rowNum) -> rs.getLong(1)).single();
    }

    private JsonNode asJson(final MockHttpServletRequestBuilder request) throws Exception {
        final MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, JsonNode> cohortsByKey(final JsonNode root) {
        final Map<String, JsonNode> byKey = new java.util.LinkedHashMap<>();
        root.path("cohorts").forEach(row -> byKey.put(row.path("key").asText(), row));
        return byKey;
    }

    @Test
    void twoRowCohortsComputeRatesAndDeltasInPercentagePoints() throws Exception {
        // ground truth from the indexed fixture corpora:
        //   0.1.5-rc.2: 12 sessions, 32 tool calls, 9 findings, 4 guard findings
        //   0.1.4:       3 sessions,  9 tool calls, 3 findings, 2 guard findings
        assertThat(count("select count(*) from session")).isEqualTo(15);
        assertThat(count("select count(*) from tool_call")).isEqualTo(41);
        assertThat(count("select count(*) from finding")).isEqualTo(12);

        final JsonNode root = asJson(get("/api/cohorts?groupBy=harnessVersion"));
        assertThat(root.path("groupBy").asText()).isEqualTo("harnessVersion");
        // default baseline: the cohort with the most tool calls
        assertThat(root.path("baseline").asText()).isEqualTo(IndexedCorpus.MAIN_VERSION);
        // two rows: no one-row note, but the inferred-version and baseline-choice notes
        assertThat(root.path("basisNote").asText()).doesNotContain("one-row");
        assertThat(root.path("basisNote").asText()).contains("inferred");
        assertThat(root.path("basisNote").asText()).contains("highest tool-call count");

        final Map<String, JsonNode> byKey = cohortsByKey(root);
        assertThat(byKey.keySet()).containsExactlyInAnyOrder(IndexedCorpus.MAIN_VERSION, IndexedCorpus.SECOND_VERSION);

        final JsonNode main = byKey.get(IndexedCorpus.MAIN_VERSION);
        assertThat(main.path("sessions").asLong()).isEqualTo(12);
        assertThat(main.path("toolCalls").asLong()).isEqualTo(32);
        assertThat(main.path("findings").asLong()).isEqualTo(9);
        assertThat(main.path("guardFindings").asLong()).isEqualTo(4);
        assertThat(main.path("findingsPerKCalls").asDouble()).isEqualTo(281.25);
        assertThat(main.path("violationRatePerK").asDouble()).isEqualTo(125.0);
        // the baseline's own deltas are zero
        assertThat(main.path("findingsPerKCallsDelta").asDouble()).isEqualTo(0.0);
        assertThat(main.path("violationRatePerKDelta").asDouble()).isEqualTo(0.0);

        final JsonNode second = byKey.get(IndexedCorpus.SECOND_VERSION);
        assertThat(second.path("sessions").asLong()).isEqualTo(3);
        assertThat(second.path("toolCalls").asLong()).isEqualTo(9);
        assertThat(second.path("findings").asLong()).isEqualTo(3);
        assertThat(second.path("guardFindings").asLong()).isEqualTo(2);
        assertThat(second.path("findingsPerKCalls").asDouble()).isEqualTo(333.33);
        assertThat(second.path("violationRatePerK").asDouble()).isEqualTo(222.22);
        // deltas in percentage points, computed from the rounded rates
        assertThat(second.path("findingsPerKCallsDelta").asDouble()).isEqualTo(52.08);
        assertThat(second.path("violationRatePerKDelta").asDouble()).isEqualTo(97.22);
    }

    @Test
    void explicitBaselineFlipsTheDeltasAndDropsTheChoiceNote() throws Exception {
        final JsonNode root = asJson(get("/api/cohorts?groupBy=harnessVersion&baseline="
                + IndexedCorpus.SECOND_VERSION));
        assertThat(root.path("baseline").asText()).isEqualTo(IndexedCorpus.SECOND_VERSION);
        assertThat(root.path("basisNote").asText()).doesNotContain("highest tool-call count");

        final Map<String, JsonNode> byKey = cohortsByKey(root);
        assertThat(byKey.get(IndexedCorpus.SECOND_VERSION).path("findingsPerKCallsDelta").asDouble()).isEqualTo(0.0);
        // 281.25 - 333.33
        assertThat(byKey.get(IndexedCorpus.MAIN_VERSION).path("findingsPerKCallsDelta").asDouble())
                .isEqualTo(-52.08);
        // 125.0 - 222.22
        assertThat(byKey.get(IndexedCorpus.MAIN_VERSION).path("violationRatePerKDelta").asDouble())
                .isEqualTo(-97.22);
    }

    @Test
    void unknownBaselineFailsWithTheAllowedKeys() throws Exception {
        mockMvc.perform(get("/api/cohorts?groupBy=harnessVersion").param("baseline", "9.9.9"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.filter").value("baseline"))
                .andExpect(jsonPath("$.value").value("9.9.9"))
                .andExpect(jsonPath("$.allowed[?(@ == '0.1.4')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == '0.1.5-rc.2')]").exists());
    }

    @Test
    void groupByInjectionIsRejectedWithTheTableIntact() throws Exception {
        mockMvc.perform(get("/api/cohorts").param("groupBy", "1;drop table finding"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.filter").value("groupBy"))
                .andExpect(jsonPath("$.value").value("1;drop table finding"))
                .andExpect(jsonPath("$.allowed[?(@ == 'harnessVersion')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'model')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'schema')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'preset')]").exists());
        // the table is intact
        assertThat(count("select count(*) from finding")).isEqualTo(12);
    }

    @Test
    void groupingByModelProducesOneRowPerModel() throws Exception {
        final JsonNode root = asJson(get("/api/cohorts?groupBy=model"));
        assertThat(root.path("cohorts").size()).isEqualTo(3); // two models + "unknown"
        final List<Long> sessions = new ArrayList<>();
        final List<Long> calls = new ArrayList<>();
        root.path("cohorts").forEach(row -> {
            sessions.add(row.path("sessions").asLong());
            calls.add(row.path("toolCalls").asLong());
        });
        assertThat(sessions.stream().mapToLong(Long::longValue).sum()).isEqualTo(15);
        assertThat(calls.stream().mapToLong(Long::longValue).sum()).isEqualTo(41);
    }

    @Test
    void emptyFilterStillCoversEveryCohort() throws Exception {
        // no InsightFilter parameters: the whole index is grouped
        final JsonNode root = asJson(get("/api/cohorts?groupBy=harnessVersion"));
        final long[] sessions = {0};
        root.path("cohorts").forEach(row -> sessions[0] += row.path("sessions").asLong());
        assertThat(sessions[0]).isEqualTo(15);
    }
}
