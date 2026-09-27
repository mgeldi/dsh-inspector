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
 * The routes an agent reads to judge a harness change (DESIGN.md §7a): the judge, the breakdown,
 * and the structural sequence around one finding — over the same two-corpus index as the other
 * route tests, where the corpora stand in for two harness versions.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
class AnalysisRoutesTest {

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("analysis.sqlite").toString());
        registry.add("inspector.corpus", () -> "fixtures/sessions");
        registry.add("inspector.harness-version", () -> IndexedCorpus.MAIN_VERSION);
    }

    @BeforeAll
    static void indexCorpus() {
        IndexedCorpus.indexBoth(temp.resolve("analysis.sqlite"));
    }

    private long count(final String sql) {
        return jdbc.sql(sql).query((RowMapper<Long>) (rs, rowNum) -> rs.getLong(1)).single();
    }

    private JsonNode asJson(final MockHttpServletRequestBuilder request) throws Exception {
        final MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void theJudgeComparesTheTwoCohortsRowByRow() throws Exception {
        // two cohorts on the axis: the candidate may be left out, and is the other one
        final JsonNode judge = asJson(get("/api/judge").param("groupBy", "harnessVersion"));
        assertThat(judge.path("baseline").asText()).isEqualTo(IndexedCorpus.MAIN_VERSION);
        assertThat(judge.path("candidate").asText()).isEqualTo(IndexedCorpus.SECOND_VERSION);
        assertThat(judge.path("baselineCohort").path("toolCalls").asLong()).isEqualTo(43);
        assertThat(judge.path("candidateCohort").path("toolCalls").asLong()).isEqualTo(9);

        final List<String> scopes = new ArrayList<>();
        judge.path("rows").forEach(row -> scopes.add(row.path("scope").asText() + ":" + row.path("key").asText()));
        assertThat(scopes).startsWith("total:all", "plane:GUARD", "plane:MODEL_MISUSE", "plane:INFRASTRUCTURE");

        final JsonNode total = judge.path("rows").path(0);
        assertThat(total.path("baselineCount").asLong()).isEqualTo(17);
        assertThat(total.path("candidateCount").asLong()).isEqualTo(4);
        assertThat(total.path("baselinePerK").asDouble()).isEqualTo(395.35);
        assertThat(total.path("candidatePerK").asDouble()).isEqualTo(444.44);
        // four findings against seventeen cannot tell the two apart, and the judge says so
        assertThat(total.path("verdict").asText()).isEqualTo("inconclusive");
        assertThat(total.path("dispersion").asDouble()).isGreaterThanOrEqualTo(1.0);
        judge.path("rows").forEach(row -> assertThat(row.path("verdict").asText())
                .isIn("better", "worse", "inconclusive", "no-data"));
        // the codeless shell edits are a row of their own, keyed by the detector
        assertThat(scopes).contains("code:shell-edit", "code:FS_STALE_VERSION");
        assertThat(judge.path("basisNote").asText()).contains("95% interval");
    }

    @Test
    void anAxisWithMoreThanTwoCohortsNeedsTheCandidateNamed() throws Exception {
        mockMvc.perform(get("/api/judge").param("groupBy", "model"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.filter").value("candidate"));
        mockMvc.perform(get("/api/judge").param("groupBy", "harnessVersion")
                        .param("baseline", IndexedCorpus.MAIN_VERSION).param("candidate", IndexedCorpus.MAIN_VERSION))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/judge").param("groupBy", "1;drop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.filter").value("groupBy"));
    }

    @Test
    void theBreakdownAddsUpToTheFindingsAndCarriesRates() throws Exception {
        final JsonNode rows = asJson(get("/api/breakdown"));
        long summed = 0;
        for (final JsonNode row : rows) {
            summed += row.path("count").asLong();
            assertThat(row.path("perKCalls").asDouble()).isPositive();
        }
        assertThat(summed).isEqualTo(count("select count(*) from finding"));
        final List<String> kinds = new ArrayList<>();
        rows.forEach(r -> kinds.add(r.path("detector").asText() + "/" + r.path("category").asText("-")));
        assertThat(kinds).contains("edit-miss/MISS_AFTER_EDIT", "edit-miss/REPEATED_MISS",
                "edit-miss/MISS_AFTER_READ", "stamp-guard/DIRECT_MUTATION");
    }

    @Test
    void theContextShowsTheSequenceAroundARefusalWithItsStampAndCauseMarked() throws Exception {
        final long id = count("select id from finding where session_id = 's-01' and detector = 'stamp-guard'");
        final JsonNode context = asJson(get("/api/findings/" + id + "/context").param("window", "5"));

        assertThat(context.path("anchorSeq").asLong()).isEqualTo(15);
        final Map<String, String> marks = new java.util.LinkedHashMap<>();
        context.path("calls").forEach(c -> marks.put(c.path("seq").asText(), c.path("mark").asText("")));
        assertThat(marks).containsEntry("11", "stale").containsEntry("13", "cause").containsEntry("15", "finding");
        // the refusal's neighbours: the shell edit that caused it is a finding of its own
        final List<String> neighbours = new ArrayList<>();
        context.path("findings").forEach(f -> neighbours.add(f.path("detector").asText()));
        assertThat(neighbours).contains("stamp-guard", "shell-edit");
        // structure only: no field anywhere in the answer carries text
        final String body = mapper.writeValueAsString(context);
        assertThat(body).doesNotContain("excerpt").doesNotContain("sed -i");
    }

    @Test
    void aFindingWithNoToolSeqIsPlacedByItsEventTime() throws Exception {
        final long id = count("select id from finding where session_id = 's-15' and detector = 'fatal-turn'");
        final JsonNode context = asJson(get("/api/findings/" + id + "/context"));
        // the last call that had started before the turn died: the copy at the end of step 1
        assertThat(context.path("anchorSeq").isNull()).isFalse();
        final JsonNode last = context.path("calls").path(context.path("calls").size() - 1);
        assertThat(last.path("seq").asLong()).isEqualTo(context.path("anchorSeq").asLong());
        assertThat(last.path("name").asText()).isEqualTo("bash");
        // the turn has no call of its own, so it is not marked — but it is listed, first
        assertThat(context.path("findings").path(0).path("id").asLong()).isEqualTo(id);
    }

    @Test
    void theContextWindowIsBoundedAndAMissingFindingIsA404() throws Exception {
        mockMvc.perform(get("/api/findings/1/context").param("window", "51")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/findings/999999/context")).andExpect(status().isNotFound());
    }
}
