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

/**
 * The findings list, its detail endpoint and its pagination/sort rules
 * (DESIGN.md §7). Same setup as {@link OverviewControllerTest}: a temp-dir
 * database indexed by explicit test code, never by the context.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
class FindingsControllerTest {

    @TempDir
    static Path temp;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("findings.sqlite").toString());
        registry.add("inspector.corpus", () -> "fixtures/sessions");
        registry.add("inspector.harness-version", () -> IndexedCorpus.MAIN_VERSION);
    }

    @BeforeAll
    static void indexCorpus() {
        IndexedCorpus.indexBoth(temp.resolve("findings.sqlite"));
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

    @Test
    void paginationIsStable() throws Exception {
        // size 5 over 12 findings: pages of 5, 5, 2 — disjoint, in the same order
        final JsonNode page0 = asJson(get("/api/findings?size=5&page=0"));
        final JsonNode page1 = asJson(get("/api/findings?size=5&page=1"));
        final JsonNode page2 = asJson(get("/api/findings?size=5&page=2"));

        assertThat(page0.path("total").asLong()).isEqualTo(12);
        assertThat(page1.path("total").asLong()).isEqualTo(12);
        assertThat(page2.path("total").asLong()).isEqualTo(12);
        assertThat(page0.path("items").size()).isEqualTo(5);
        assertThat(page1.path("items").size()).isEqualTo(5);
        assertThat(page2.path("items").size()).isEqualTo(2);

        final List<Long> ids0 = ids(page0);
        final List<Long> ids1 = ids(page1);
        final List<Long> ids2 = ids(page2);
        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
        assertThat(ids0).doesNotContainAnyElementsOf(ids2);
        assertThat(ids1).doesNotContainAnyElementsOf(ids2);
        assertThat(new ArrayList<>(ids0)).hasSize(5);

        // the union is the whole table, default sort is time descending
        final List<JsonNode> all = new ArrayList<>();
        for (final JsonNode page : List.of(page0, page1, page2)) {
            page.path("items").forEach(all::add);
        }
        assertThat(all).hasSize(12);
        assertThat(all.stream().map(item -> item.path("id").asLong()).toList()).doesNotHaveDuplicates();
        final List<Long> times =
                all.stream().map(item -> item.path("occurredAt").asLong()).toList();
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).isLessThanOrEqualTo(times.get(i - 1));
        }

        // every item is the aggregate shape: no evidence text
        for (final JsonNode item : all) {
            assertThat(item.path("summary").isTextual()).isTrue();
            assertThat(item.path("evidence").isMissingNode()).isTrue();
        }
    }

    @Test
    void unknownFindingIsAProblemDetail() throws Exception {
        mockMvc.perform(get("/api/findings/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.id").value(999_999));
    }

    @Test
    void sortInjectionIsRejectedWithTheTableIntact() throws Exception {
        mockMvc.perform(get("/api/findings").param("sort", "occurred_at desc; drop table finding"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.filter").value("sort"))
                .andExpect(jsonPath("$.allowed[?(@ == 'time')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'confidence')]").exists());
        // the table is intact
        assertThat(count("select count(*) from finding")).isEqualTo(12);
    }

    @Test
    void unknownSortDirectionIsRejectedToo() throws Exception {
        mockMvc.perform(get("/api/findings").param("sort", "time:drop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.filter").value("sort"))
                .andExpect(jsonPath("$.value").value("drop"))
                .andExpect(jsonPath("$.allowed[?(@ == 'asc')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'desc')]").exists());
        assertThat(count("select count(*) from finding")).isEqualTo(12);
    }

    @Test
    void planeValuesComeFromTheUiVocabulary() throws Exception {
        // the three plane constants are accepted and narrow the result
        final JsonNode guard = asJson(get("/api/findings?plane=GUARD&size=100"));
        assertThat(guard.path("total").asLong()).isEqualTo(6);
        guard.path("items").forEach(item ->
                assertThat(item.path("plane").asText()).isEqualTo("GUARD"));

        final JsonNode infra = asJson(get("/api/findings?plane=INFRASTRUCTURE&size=100"));
        assertThat(infra.path("total").asLong()).isEqualTo(4);
    }

    @Test
    void unknownPlaneFailsWithTheThreeUiConstants() throws Exception {
        mockMvc.perform(get("/api/findings").param("plane", "Bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.filter").value("plane"))
                .andExpect(jsonPath("$.value").value("Bogus"))
                .andExpect(jsonPath("$.allowed[?(@ == 'INFRASTRUCTURE')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'GUARD')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'MODEL_MISUSE')]").exists());
    }

    @Test
    void detailCarriesTheRedactedEvidence() throws Exception {
        final long id = jdbc.sql("select id from finding where detector = 'stamp-guard'"
                + " and cause_seq is not null order by id limit 1").query(Long.class).single();
        final JsonNode detail = asJson(get("/api/findings/{id}", id));

        assertThat(detail.path("finding").path("id").asLong()).isEqualTo(id);
        assertThat(detail.path("finding").path("detector").asText()).isEqualTo("stamp-guard");
        assertThat(detail.path("finding").path("confidence").asDouble()).isEqualTo(0.9);
        assertThat(detail.path("finding").path("causeSeq").asLong()).isNotNegative();
        assertThat(detail.path("tool").isTextual()).isTrue();
        assertThat(detail.path("evidence").isArray()).isTrue();
        assertThat(detail.path("evidence")).isNotEmpty();
        detail.path("evidence").forEach(row ->
                assertThat(row.path("excerptRedacted").isTextual()).isTrue());
    }

    @Test
    void invalidPaginationIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/findings").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/api/findings").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static List<Long> ids(final JsonNode page) {
        final List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }
}
