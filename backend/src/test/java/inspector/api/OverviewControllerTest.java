package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import inspector.query.Vocabulary;

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

/**
 * The dashboard tile board over the indexed fixture corpora (DESIGN.md §7).
 *
 * <p>The context is booted with {@code --no-index} and a temp-dir database;
 * the actual indexing is explicit test code in {@link #indexCorpus()}, so no
 * Spring context ever indexes the fixtures at startup.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
class OverviewControllerTest {

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("overview.sqlite").toString());
        registry.add("inspector.corpus", () -> "fixtures/sessions");
        registry.add("inspector.harness-version", () -> IndexedCorpus.MAIN_VERSION);
    }

    @BeforeAll
    static void indexCorpus() {
        IndexedCorpus.indexBoth(temp.resolve("overview.sqlite"));
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

    private String asString(final MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static List<String> strings(final JsonNode node) {
        final List<String> out = new ArrayList<>();
        node.forEach(value -> out.add(value.asText()));
        return out;
    }

    @Test
    void theSessionTileCountsSessionsNotStreams() throws Exception {
        // The fixture corpus contains one session written in both conventions, so the session
        // table holds 15 rows for 14 sessions. The tile has to answer "how many sessions": two
        // answers to one question is how a dashboard loses the right to be believed about
        // anything else. (The rail used to be the second opinion on this number by listing the
        // ids; the list left the payload because no screen reads it, so the ground truth here is
        // SQL. That the server still knows the ids is proved from the wire by
        // FindingsControllerTest.unknownSessionFailsWithTheSessionIdsTheIndexHolds.)
        assertThat(count("select count(*) from session")).isEqualTo(16);
        assertThat(count("select count(distinct id) from session")).isEqualTo(15);
        assertThat(asJson(get("/api/overview")).path("tiles").path("sessions").asLong()).isEqualTo(15);
    }

    @Test
    void overviewReportsTilesAndVocabulary() throws Exception {
        final JsonNode root = asJson(get("/api/overview"));

        // tiles against the database ground truth, not a hardcoded guess
        assertThat(root.path("tiles").path("sessions").asLong())
                .isEqualTo(count("select count(distinct id) from session"));
        assertThat(root.path("tiles").path("findings").asLong()).isEqualTo(count("select count(*) from finding"));
        assertThat(root.path("tiles").path("toolCalls").asLong())
                .isEqualTo(count("select count(*) from tool_call"));
        assertThat(root.path("tiles").path("steps").asLong()).isEqualTo(count("select count(*) from step"));
        // the fixture plan: 16 session rows, 21 findings
        assertThat(count("select count(*) from session")).isEqualTo(16);
        assertThat(count("select count(*) from finding")).isEqualTo(21);

        // plane mix matches the plan
        assertThat(root.path("planeMix").path("GUARD").asLong()).isEqualTo(6);
        assertThat(root.path("planeMix").path("INFRASTRUCTURE").asLong()).isEqualTo(5);
        assertThat(root.path("planeMix").path("MODEL_MISUSE").asLong()).isEqualTo(10);

        // top detectors in count order, ties broken by id: shell-edit and stamp-guard both 5
        assertThat(root.path("topDetectors").path(0).path("detector").asText()).isEqualTo("shell-edit");
        assertThat(root.path("topDetectors").path(0).path("count").asLong()).isEqualTo(5);
        assertThat(root.path("topDetectors").path(1).path("detector").asText()).isEqualTo("stamp-guard");
        assertThat(root.path("topDetectors").path(2).path("detector").asText()).isEqualTo("error-plane");

        // The codes, which is the breakdown a reader can act on: a detector name says which
        // rule fired, a code says what the harness refused. Ordered by count, and every entry
        // is a legal value of the ?code= filter the findings table takes.
        final JsonNode codes = root.path("topCodes");
        assertThat(codes.isArray()).isTrue();
        assertThat(codes).isNotEmpty();
        long previous = Long.MAX_VALUE;
        long summed = 0;
        for (final JsonNode entry : codes) {
            final long count = entry.path("count").asLong();
            assertThat(entry.path("code").asText()).isNotBlank();
            assertThat(count).as("counts descend").isLessThanOrEqualTo(previous);
            previous = count;
            summed += count;
        }
        // The panel is capped at eight codes and the fixtures emit nine, so exactly one finding
        // sits in "other codes"; the five shell edits carry no code at all and are counted
        // apart rather than folded into an "unknown" code the harness never emitted. The three
        // have to add up to the tile beside them — a panel that silently dropped a bucket would
        // be the same class of lie as a wrong denominator.
        assertThat(codes.size()).isEqualTo(8);
        assertThat(root.path("uncodedFindings").asLong()).isEqualTo(5);
        assertThat(root.path("tiles").path("findings").asLong() - summed - root.path("uncodedFindings").asLong())
                .as("findings in codes beyond the panel's eight").isEqualTo(1);

        // the two daily series, merged
        assertThat(root.path("series").isArray()).isTrue();
        assertThat(root.path("series")).isNotEmpty();

        // vocabulary is what is in the database
        assertThat(strings(root.path("vocabulary").path("schemas")))
                .containsExactlyInAnyOrder("V0", "V3");
        assertThat(strings(root.path("vocabulary").path("models")))
                .containsExactlyInAnyOrder("demo-brain-27b", "demo-flash-8b", Vocabulary.UNKNOWN);
        assertThat(strings(root.path("vocabulary").path("presets")))
                .containsExactlyInAnyOrderElementsOf(
                        jdbc.sql("select distinct coalesce(s.agent_preset, 'unknown') from session s")
                                .query(String.class).list());
        assertThat(strings(root.path("vocabulary").path("harnessVersions")))
                .containsExactlyInAnyOrder(IndexedCorpus.MAIN_VERSION, IndexedCorpus.SECOND_VERSION);
        assertThat(strings(root.path("vocabulary").path("codes")))
                .containsExactlyInAnyOrder("FS_STALE_VERSION", "FS_EDIT_NOT_FOUND", "INVALID_REQUEST", "TIMEOUT",
                        "SERVER", "FS_NOT_FOUND", "FS_NOT_OBSERVED", "SEARCH_FAILED",
                        "WEB_PROVIDER_CREDENTIAL_MISSING",
                        // the shell edits carry no code; the bucket is what ?code=unknown selects
                        Vocabulary.UNKNOWN);
        assertThat(strings(root.path("vocabulary").path("detectors")))
                .containsExactlyInAnyOrder("stamp-guard", "edit-miss", "error-plane", "fatal-turn",
                        "retry-storm", "shell-edit");
        // two routes, and s-09's missing request/context folded into the bucket the rail can select
        assertThat(strings(root.path("vocabulary").path("providers")))
                .containsExactlyInAnyOrder("local", "local-impl", Vocabulary.UNKNOWN);
        assertThat(strings(root.path("vocabulary").path("roles")))
                .containsExactlyInAnyOrder("orchestrator", "subagent");

        // The vocabulary's wire shape, pinned by name. The session ids are the list missing from
        // it: on the author's corpus 165 ids were 6,812 of an 8,997-byte response, and it is the
        // only list that grows with the corpus, so it is read for validation and never sent.
        // Pinned as a name set rather than an absent-path check so re-adding it fails here.
        final List<String> vocabularyFields = new ArrayList<>();
        root.path("vocabulary").propertyNames().forEach(vocabularyFields::add);
        assertThat(vocabularyFields).containsExactlyInAnyOrder(
                "schemas", "models", "presets", "harnessVersions", "codes", "detectors", "providers", "roles");

        // no evidence text on the overview
        assertThat(root.toString()).doesNotContain("excerpt");
    }

    @Test
    void unknownFilterValueFailsLoudlyWithTheAllowedSet() throws Exception {
        mockMvc.perform(get("/api/overview").param("model", "no-such-model"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.filter").value("model"))
                .andExpect(jsonPath("$.value").value("no-such-model"))
                .andExpect(jsonPath("$.allowed[?(@ == 'demo-brain-27b')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'unknown')]").exists());
        // the 400 happened before any SQL: the table is intact
        assertThat(count("select count(*) from session")).isEqualTo(16);
    }

    @Test
    void filteringBySchemaNarrowsTheResult() throws Exception {
        // V3: s-06 (v3 stream), s-07, s-08, s-10 -> 4 session rows; only s-07's findings — the
        // stamp refusal and the script write that caused it, which the shell-edit detector
        // counts in its own right
        final JsonNode root = asJson(get("/api/overview").param("schema", "V3"));
        assertThat(root.path("tiles").path("sessions").asLong()).isEqualTo(4);
        assertThat(root.path("tiles").path("findings").asLong()).isEqualTo(2);
        assertThat(root.path("planeMix").path("GUARD").asLong()).isEqualTo(1);
        assertThat(root.path("planeMix").path("MODEL_MISUSE").asLong()).isEqualTo(1);
        assertThat(root.path("planeMix").path("INFRASTRUCTURE").isMissingNode()).isTrue();
        final List<String> detectors = new ArrayList<>();
        root.path("topDetectors").forEach(d -> detectors.add(d.path("detector").asText()));
        assertThat(detectors).containsExactly("shell-edit", "stamp-guard");
    }

    @Test
    void evidenceIsOnlyEverOnTheDetailEndpoint() throws Exception {
        final List<String> excerpts =
                jdbc.sql("select excerpt_redacted from shell_evidence").query(String.class).list();
        assertThat(excerpts).isNotEmpty();

        final String overview = asString(get("/api/overview"));
        final String findingsPage = asString(get("/api/findings?size=100"));
        for (final String excerpt : excerpts) {
            assertThat(overview).as("overview body").doesNotContain(excerpt);
            assertThat(findingsPage).as("findings page body").doesNotContain(excerpt);
        }

        // the detail endpoint does return the redacted excerpt
        final long id = jdbc.sql("select id from finding where detector = 'stamp-guard'"
                + " and cause_seq is not null order by id limit 1").query(Long.class).single();
        final String itsExcerpt = jdbc.sql("select se.excerpt_redacted from shell_evidence se"
                + " where se.finding_id = ? order by se.seq limit 1").param(id).query(String.class).single();
        assertThat(itsExcerpt).isNotBlank();
        final String detail = asString(get("/api/findings/{id}", id));
        assertThat(detail).contains(itsExcerpt);
    }
}
