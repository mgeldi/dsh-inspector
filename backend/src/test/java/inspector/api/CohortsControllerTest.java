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
        //   0.1.5-rc.2: 12 sessions in 13 stored files, 43 tool calls, 17 findings: 4 guard,
        //               9 misuse (3 edit misses, 4 shell edits, 2 error-plane), 4 infrastructure
        //   0.1.4:       3 sessions,  9 tool calls, 4 findings: 2 guard, 1 misuse, 1 infrastructure
        assertThat(count("select count(*) from session")).isEqualTo(16);
        assertThat(count("select count(*) from tool_call")).isEqualTo(52);
        assertThat(count("select count(*) from finding")).isEqualTo(21);

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
        // 12 distinct sessions from 13 stored files: s-06 exists under both conventions
        assertThat(main.path("sessions").asLong()).isEqualTo(12);
        assertThat(main.path("toolCalls").asLong()).isEqualTo(43);
        assertThat(main.path("findings").asLong()).isEqualTo(17);
        assertThat(main.path("guardFindings").asLong()).isEqualTo(4);
        assertThat(main.path("misuseFindings").asLong()).isEqualTo(9);
        assertThat(main.path("infraFindings").asLong()).isEqualTo(4);
        assertThat(main.path("findingsPerKCalls").asDouble()).isEqualTo(395.35);
        assertThat(main.path("violationRatePerK").asDouble()).isEqualTo(93.02);
        assertThat(main.path("misuseRatePerK").asDouble()).isEqualTo(209.3);
        assertThat(main.path("infraRatePerK").asDouble()).isEqualTo(93.02);
        // the baseline's own deltas are zero
        assertThat(main.path("findingsPerKCallsDelta").asDouble()).isEqualTo(0.0);
        assertThat(main.path("violationRatePerKDelta").asDouble()).isEqualTo(0.0);
        assertThat(main.path("misuseRatePerKDelta").asDouble()).isEqualTo(0.0);
        assertThat(main.path("infraRatePerKDelta").asDouble()).isEqualTo(0.0);

        final JsonNode second = byKey.get(IndexedCorpus.SECOND_VERSION);
        assertThat(second.path("sessions").asLong()).isEqualTo(3);
        assertThat(second.path("toolCalls").asLong()).isEqualTo(9);
        assertThat(second.path("findings").asLong()).isEqualTo(4);
        assertThat(second.path("guardFindings").asLong()).isEqualTo(2);
        assertThat(second.path("findingsPerKCalls").asDouble()).isEqualTo(444.44);
        assertThat(second.path("violationRatePerK").asDouble()).isEqualTo(222.22);
        assertThat(second.path("misuseRatePerK").asDouble()).isEqualTo(111.11);
        assertThat(second.path("infraRatePerK").asDouble()).isEqualTo(111.11);
        // deltas in percentage points, computed from the rounded rates
        assertThat(second.path("findingsPerKCallsDelta").asDouble()).isEqualTo(49.09);
        assertThat(second.path("violationRatePerKDelta").asDouble()).isEqualTo(129.2);
        assertThat(second.path("misuseRatePerKDelta").asDouble()).isEqualTo(-98.19);
        assertThat(second.path("infraRatePerKDelta").asDouble()).isEqualTo(18.09);
    }

    // The explicit-baseline case (same numbers, negated, with the choice note dropped) is not
    // here any more: it is inspector.insight.CohortServiceTest, which proves the flip against a
    // stubbed repository, and CohortsWebTest, which proves the baseline parameter reaches the
    // service. What stays above is the part only an index can prove — that SQL's counts and
    // this arithmetic agree about real rows.

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
                .andExpect(jsonPath("$.allowed[?(@ == 'preset')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'provider')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'role')]").exists());
        // the table is intact
        assertThat(count("select count(*) from finding")).isEqualTo(21);
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
        assertThat(sessions.stream().mapToLong(Long::longValue).sum())
                .isEqualTo(distinctSessionsPerCohortSum("model"));
        assertThat(calls.stream().mapToLong(Long::longValue).sum()).isEqualTo(52);
    }

    /**
     * The axis the harness-improvement loop needs most: the orchestrator and its subagents run
     * different models on this setup, and a rate that mixes them describes neither. s-10 and s-11
     * are the fixture's subagents.
     */
    @Test
    void groupingByRoleSeparatesOrchestratorsFromSubagents() throws Exception {
        final Map<String, JsonNode> byRole = cohortsByKey(asJson(get("/api/cohorts?groupBy=role")));
        assertThat(byRole.keySet()).containsExactlyInAnyOrder("orchestrator", "subagent");
        assertThat(byRole.get("subagent").path("sessions").asLong()).isEqualTo(2);
        assertThat(byRole.get("orchestrator").path("sessions").asLong()).isEqualTo(13);

        final Map<String, JsonNode> byProvider = cohortsByKey(asJson(get("/api/cohorts?groupBy=provider")));
        assertThat(byProvider.keySet()).containsExactlyInAnyOrder("local", "local-impl", "unknown");
    }

    @Test
    void emptyFilterStillCoversEveryCohort() throws Exception {
        // no InsightFilter parameters: the whole index is grouped. 15, not 16: the session
        // table has one row per stored file and s-06 is stored under both conventions.
        final JsonNode root = asJson(get("/api/cohorts?groupBy=harnessVersion"));
        final long[] sessions = {0};
        root.path("cohorts").forEach(row -> sessions[0] += row.path("sessions").asLong());
        assertThat(sessions[0]).isEqualTo(15);
    }

    /**
     * The session table is keyed (id, source file), so a session stored under both
     * conventions is two rows. A cohort counts <i>sessions</i>, because that is what the
     * number is read as: a reviewer who sees 11 in the Overview tile and 12 in the cohort
     * row for the same corpus concludes the two screens disagree. On an axis whose value
     * both files share, the session must count once.
     */
    @Test
    void aSessionStoredUnderBothConventionsCountsOnceInItsCohort() throws Exception {
        assertThat(count("select count(distinct id) from session")).isEqualTo(15);
        assertThat(count("select count(*) from session where id = 's-06'")).isEqualTo(2);

        final Map<String, JsonNode> byVersion = cohortsByKey(asJson(get("/api/cohorts?groupBy=harnessVersion")));
        assertThat(byVersion.get(IndexedCorpus.MAIN_VERSION).path("sessions").asLong()).isEqualTo(12);
        assertThat(byVersion.get(IndexedCorpus.SECOND_VERSION).path("sessions").asLong()).isEqualTo(3);

        // on the schema axis the same session legitimately belongs to both cohorts.
        // V3 = s-06's v3 file, s-07, s-08, s-10; V0 = the other 9 of this corpus plus all
        // three of sessions-b. The two cohorts sum to 16 for 15 sessions, and that is the
        // correct reading: s-06 is genuinely in both.
        final Map<String, JsonNode> bySchema = cohortsByKey(asJson(get("/api/cohorts?groupBy=schema")));
        assertThat(bySchema.get("V3").path("sessions").asLong()).isEqualTo(4);
        assertThat(bySchema.get("V0").path("sessions").asLong()).isEqualTo(12);
    }

    /**
     * Why this endpoint carries the shared filter contract at all: a rate on this screen has to
     * be computed over the population the rail says it is computed over. The table is four
     * separate aggregates with four different time columns, so one forgotten WHERE is invisible
     * until the table is compared against something independent — here, the same population
     * read off a different axis through the same endpoint. Until §7 was wired through, this is
     * the assertion that failed: the endpoint accepted the parameters and ignored them.
     */
    @Test
    void aFacetFilterNarrowsEveryAggregateInTheTableNotOnlyTheSessionList() throws Exception {
        final Map<String, JsonNode> byModel = cohortsByKey(asJson(get("/api/cohorts?groupBy=model")));
        final String model = byModel.entrySet().stream()
                .filter(entry -> entry.getValue().path("findings").asLong() > 0
                        && entry.getValue().path("guardFindings").asLong() > 0)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fixture has no model carrying guard findings"));
        final JsonNode expected = byModel.get(model);

        final JsonNode root = asJson(get("/api/cohorts")
                .param("groupBy", "harnessVersion")
                .param("model", model));

        // Sessions, calls, findings and guard findings each come from their own query: all four
        // have to agree with the model axis or one of them kept counting the whole index.
        assertThat(sum(root, "sessions")).as("distinct sessions under the filter")
                .isEqualTo(expected.path("sessions").asLong());
        assertThat(sum(root, "toolCalls")).as("observed calls under the filter")
                .isEqualTo(expected.path("toolCalls").asLong());
        assertThat(sum(root, "findings")).as("findings under the filter")
                .isEqualTo(expected.path("findings").asLong());
        assertThat(sum(root, "guardFindings")).as("guard findings under the filter")
                .isEqualTo(expected.path("guardFindings").asLong());

        // and the screen admits the numbers are a subset: a filtered rate and an accidentally
        // filtered rate are otherwise indistinguishable in a screenshot
        assertThat(root.path("basisNote").asText()).contains("shared filters are active");
    }

    /**
     * The window applies per root — finding event time, session start, call start — which is
     * the part a single WHERE cannot express. A midpoint window must narrow both the numerator
     * and the denominator while leaving sessions on the screen; a repository that applied the
     * filter to the session list only would still report every finding.
     */
    @Test
    void aTimeWindowNarrowsTheNumeratorAndTheDenominatorTogether() throws Exception {
        final long first = count("select min(occurred_at) from finding");
        final long last = count("select max(occurred_at) from finding");
        assertThat(first).as("the fixture spans a window worth cutting").isLessThan(last);

        final JsonNode root = asJson(get("/api/cohorts")
                .param("groupBy", "harnessVersion")
                .param("to", String.valueOf(first + (last - first) / 2)));

        assertThat(sum(root, "findings")).as("some findings fall outside the window")
                .isPositive()
                .isLessThan(12);
        assertThat(sum(root, "guardFindings")).as("so does the numerator of the headline rate")
                .isLessThan(6);
        assertThat(sum(root, "toolCalls")).as("and so does the denominator")
                .isLessThan(41);
        assertThat(sum(root, "sessions")).as("the sessions themselves are still listed")
                .isPositive();
    }

    /**
     * A value outside the vocabulary is a 400 that names the allowed values, not an empty table.
     * The difference is the whole difference between "your filter is wrong" and "this cohort
     * has no findings" — the second one is a finding about the code, and it is false.
     */
    @Test
    void aFilterValueOutsideTheVocabularyIsRejectedRatherThanAnsweredWithAnEmptyTable() throws Exception {
        mockMvc.perform(get("/api/cohorts").param("groupBy", "model").param("model", "no-such-model"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.filter").value("model"))
                .andExpect(jsonPath("$.value").value("no-such-model"))
                .andExpect(jsonPath("$.allowed[0]").exists());
    }

    private long sum(final JsonNode root, final String field) {
        long total = 0;
        for (final JsonNode row : root.path("cohorts")) {
            total += row.path(field).asLong();
        }
        return total;
    }

    /** Distinct sessions summed over every cohort of an axis: a session that belongs to two cohorts counts twice. */
    private long distinctSessionsPerCohortSum(final String axisColumn) {
        return count("select count(distinct coalesce(" + axisColumn + ", 'unknown') || '#' || id) from session");
    }
}
