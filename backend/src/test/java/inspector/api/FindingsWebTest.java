package inspector.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import inspector.dto.FindingDetailDto;
import inspector.dto.FindingDto;
import inspector.dto.FindingsPageDto;
import inspector.insight.FindingsService;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the findings route does with a request, with the domain stubbed.
 *
 * <p>The service is a mock, so everything asserted here is genuinely the web layer's own
 * work: the eight query parameters binding to the right types and defaults, the rail's six
 * values arriving as one {@link InsightFilter}, and the two exception-to-status mappings the
 * advice defines (§7's RFC 9457 shapes). None of it needed an application context, a temp
 * directory or an indexed corpus — that is what {@code FindingsControllerTest} is for, and it
 * still does that job against a real index.
 */
@WebMvcTest(FindingsController.class)
class FindingsWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FindingsService findingsService;

    @Test
    void everyParameterReachesTheServiceAsItsOwnValue() throws Exception {
        when(findingsService.page(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FindingsPageDto(0, 2, 5, List.of()));

        mockMvc.perform(get("/api/findings")
                        .param("from", "1000").param("to", "2000")
                        .param("schema", "V0").param("model", "model-a")
                        .param("preset", "default").param("harnessVersion", "0.1.5-rc.2")
                        .param("plane", "GUARD").param("detector", "stamp-guard")
                        .param("session", "s-01").param("code", "FS_STALE_VERSION")
                        .param("sort", "confidence:asc")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5));

        verify(findingsService).page(
                eq(new InsightFilter(1000L, 2000L, "V0", "model-a", "default", "0.1.5-rc.2")),
                eq("GUARD"), eq("stamp-guard"), eq("s-01"), eq("FS_STALE_VERSION"),
                eq("confidence:asc"), eq(2), eq(5));
    }

    @Test
    void theDefaultsAreTheTablesOwnDefaultsNotTheServices() throws Exception {
        when(findingsService.page(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new FindingsPageDto(0, 0, 20, List.of()));

        mockMvc.perform(get("/api/findings")).andExpect(status().isOk());

        verify(findingsService).page(eq(new InsightFilter(null, null, null, null, null, null)),
                eq(null), eq(null), eq(null), eq(null), eq("time:desc"), eq(0), eq(20));
    }

    @Test
    void aFilterTheVocabularyDoesNotKnowIsAProblemDetailCarryingWhatWouldHaveBeenAllowed() throws Exception {
        doThrow(new UnknownFilterValueException("plane", "Bogus",
                List.of("INFRASTRUCTURE", "GUARD", "MODEL_MISUSE")))
                .when(findingsService).page(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());

        mockMvc.perform(get("/api/findings").param("plane", "Bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("urn:dsh-inspector:unknown-filter-value"))
                .andExpect(jsonPath("$.filter").value("plane"))
                .andExpect(jsonPath("$.value").value("Bogus"))
                .andExpect(jsonPath("$.allowed[?(@ == 'GUARD')]").exists());
    }

    /** A bad page bound is a different problem from a bad filter, and says so differently. */
    @Test
    void aRejectedBoundIsAPlainBadRequestProblem() throws Exception {
        doThrow(new IllegalArgumentException("size must be > 0, was 0"))
                .when(findingsService).page(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());

        mockMvc.perform(get("/api/findings").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:dsh-inspector:bad-request"))
                .andExpect(jsonPath("$.detail").value("size must be > 0, was 0"));
    }

    @Test
    void anAbsentFindingIsAProblemDetailNamingTheIdThatWasAskedFor() throws Exception {
        when(findingsService.detail(999_999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/findings/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:dsh-inspector:finding-not-found"))
                .andExpect(jsonPath("$.id").value(999_999));
    }

    @Test
    void aFoundFindingIsSerialisedAsTheDetailShape() throws Exception {
        when(findingsService.detail(7L)).thenReturn(Optional.of(new FindingDetailDto(
                new FindingDto(7, "s-01", "stamp-guard", "GUARD", "DIRECT_MUTATION",
                        "FS_STALE_VERSION", 0.9, "App.java", 15L, 11L, 4L, 1_700_000_000_000L,
                        "a synthetic finding"),
                "write_file",
                List.of(new FindingDto.Evidence(0, "write", "App.java", "a synthetic excerpt")))));

        mockMvc.perform(get("/api/findings/{id}", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finding.detector").value("stamp-guard"))
                .andExpect(jsonPath("$.tool").value("write_file"))
                .andExpect(jsonPath("$.evidence[0].excerptRedacted").value("a synthetic excerpt"));
    }
}
