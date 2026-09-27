package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The generated OpenAPI document, checked as a contract rather than admired.
 *
 * <p>The interesting assertion is the one that is not in the task: none of the three filtered
 * routes may advertise a parameter named {@code filter}. {@code InsightFilter} binds through
 * {@code @ModelAttribute}, and a generator that meets a model attribute describes it as one object
 * parameter — an object in a place where a query string has no objects, so the document describes
 * a request no client can make and no route answers. {@code @ParameterObject} on the binding is
 * what flattens it into the six names, and this test is what stops that annotation being deleted
 * as unused: nothing at runtime reads it, which is exactly why a green suite would not notice.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "inspector.db=target/test-openapi.sqlite",
        "inspector.corpus=fixtures/sessions"})
class OpenApiDocumentTest {

    private static final List<String> FILTER_PARAMS =
            List.of("from", "to", "schema", "model", "preset", "harnessVersion", "provider", "role");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyRouteIsDescribed() throws Exception {
        final DocumentContext doc = document();

        assertThat(doc.<Map<String, Object>>read("$.paths")).containsOnlyKeys(
                "/api/overview", "/api/findings", "/api/findings/{id}", "/api/findings/{id}/context",
                "/api/cohorts", "/api/judge", "/api/breakdown", "/api/index/run");
        assertThat(doc.<String>read("$.openapi")).startsWith("3.");
    }

    @Test
    void theSharedFilterIsIndividualQueryParametersAndNotOneObject() throws Exception {
        final DocumentContext doc = document();

        for (final String route : List.of("/api/overview", "/api/findings", "/api/cohorts", "/api/judge",
                "/api/breakdown")) {
            final List<String> names = namesOf(doc, route);
            assertThat(names)
                    .as("the six %s values on %s, individually", "InsightFilter", route)
                    .containsAll(FILTER_PARAMS);
            assertThat(names).as("no object parameter smuggled into a query string on %s", route)
                    .doesNotContain("filter");
        }
    }

    /**
     * The route-specific parameters, because a generator that flattens the model attribute can
     * still lose the explicit ones — and {@code groupBy} is the document's only required
     * parameter, so its flag being right is the cheapest proof that required-ness survives.
     */
    @Test
    void thePerRouteParametersSurviveTheFlattening() throws Exception {
        final DocumentContext doc = document();

        assertThat(namesOf(doc, "/api/findings")).contains("plane", "detector", "session", "code",
                "sort", "page", "size");
        assertThat(namesOf(doc, "/api/cohorts")).contains("groupBy", "baseline");
        assertThat(doc.<List<Boolean>>read("$.paths['/api/cohorts'].get.parameters[?(@.name == 'groupBy')].required"))
                .containsExactly(true);
        assertThat(doc.<List<String>>read("$.paths['/api/findings/{id}'].get.parameters[*].in"))
                .containsExactly("path");
        assertThat(namesOf(doc, "/api/findings/{id}")).containsExactly("id");
    }

    /** The mutating route takes nothing, which is also what makes it safe to point a browser at. */
    @Test
    void theIndexRunTakesNoParameters() throws Exception {
        final Map<String, Object> run = document().read("$.paths['/api/index/run'].post");

        assertThat((List<?>) run.getOrDefault("parameters", List.of())).isEmpty();
    }

    /**
     * The document is generated from the running application, and a running application knows the
     * corpus path. Nothing here carries it: schemas, descriptions and examples are all derived from
     * the DTO records, so §4.1 holds on the documentation endpoint too — which is worth pinning
     * precisely because springdoc will happily print whatever a schema example contains.
     */
    @Test
    void theDocumentCarriesNoCorpusPath() throws Exception {
        final String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("fixtures/sessions").doesNotContain("/home/");
    }

    /**
     * Structure is what a generator gives away; prose is not. Before these two assertions the
     * document had all five routes, all twenty-eight parameters and their types, and an empty
     * Description column on every row of the rendered page — correct, and no use to the first
     * person trying to call it.
     *
     * <p>The descriptions come from {@code @Parameter} and {@code @Schema}, not from the javadoc
     * that already says the same thing, because springdoc reads javadoc only through
     * {@code therapi-runtime-javadoc-scribe}, which produces nothing under JDK 26 even with
     * {@code -proc:full} — a green build and an empty document, which is the failure mode these
     * tests exist to make loud. They fail on the next parameter added without a sentence, which
     * is the only way a document like this stays written.
     */
    @Test
    void everyParameterCarriesADescription() throws Exception {
        final DocumentContext doc = document();
        final List<String> undescribed = new java.util.ArrayList<>();

        for (final Map.Entry<String, Map<String, Object>> route
                : doc.<Map<String, Map<String, Object>>>read("$.paths").entrySet()) {
            for (final Object operation : route.getValue().values()) {
                // Read from the map rather than with a second JsonPath: a route that takes no
                // parameters has no `parameters` key at all, and JsonPath throws PathNotFound for
                // an absent key instead of returning null — /api/index/run is exactly that route.
                final Object parameters = ((Map<?, ?>) operation).get("parameters");
                if (!(parameters instanceof List<?> rows)) {
                    continue;
                }
                rows.stream()
                        .map(row -> (Map<?, ?>) row)
                        .filter(row -> isBlank(row.get("description")))
                        .forEach(row -> undescribed.add(route.getKey() + " " + row.get("name")));
            }
        }

        assertThat(undescribed)
                .as("every documented parameter says what it is for")
                .isEmpty();
    }

    /** The operation summary is the line a reader sees before they open anything. */
    @Test
    void everyOperationCarriesASummary() throws Exception {
        final DocumentContext doc = document();
        final List<String> unsummarised = new java.util.ArrayList<>();

        for (final Map.Entry<String, Map<String, Object>> route
                : doc.<Map<String, Map<String, Object>>>read("$.paths").entrySet()) {
            route.getValue().forEach((method, operation) -> {
                if (isBlank(((Map<?, ?>) operation).get("summary"))) {
                    unsummarised.add(method.toUpperCase(java.util.Locale.ROOT) + " " + route.getKey());
                }
            });
        }

        assertThat(unsummarised).as("every operation is introduced by a sentence").isEmpty();
    }

    private static boolean isBlank(final Object value) {
        return value == null || value.toString().isBlank();
    }

    private DocumentContext document() throws Exception {
        return com.jayway.jsonpath.JsonPath.parse(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> namesOf(final DocumentContext doc, final String route) {
        return doc.read("$.paths['" + route + "'].get.parameters[*].name");
    }
}
