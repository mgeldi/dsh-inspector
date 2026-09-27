package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import inspector.dto.CohortDto;
import inspector.insight.CohortService;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What the cohorts route does with a request, with the domain stubbed: which axis and which
 * baseline reach the service, and how the two things a caller can get wrong come back.
 *
 * <p>This is the half of {@code CohortsControllerTest} that never needed an index. The rates
 * themselves — and the fact that the SQL and the arithmetic agree about a real two-corpus
 * index — stay there.
 */
@WebMvcTest(CohortsController.class)
class CohortsWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CohortService cohortService;

    @Test
    void theAxisTheBaselineAndTheRailReachTheService() throws Exception {
        when(cohortService.cohorts(any(), eq("model"), eq("0.1.4")))
                .thenReturn(new CohortDto.Page("model", "0.1.4", "a note", List.of(
                        new CohortDto("model-a", 8, 20, 5, 3, 250.0, 150.0, 0.0, 0.0, 0, 0, null, null, null, null))));

        mockMvc.perform(get("/api/cohorts")
                        .param("groupBy", "model").param("baseline", "0.1.4")
                        .param("from", "1000").param("to", "2000").param("preset", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("model"))
                .andExpect(jsonPath("$.baseline").value("0.1.4"))
                .andExpect(jsonPath("$.basisNote").value("a note"))
                .andExpect(jsonPath("$.cohorts[0].key").value("model-a"))
                .andExpect(jsonPath("$.cohorts[0].findingsPerKCalls").value(250.0));

        verify(cohortService).cohorts(
                eq(new InsightFilter(1000L, 2000L, null, null, "default", null, null, null)),
                eq("model"), eq("0.1.4"));
    }

    @Test
    void anEmptyRailReachesTheServiceAsAnEmptyFilter() throws Exception {
        when(cohortService.cohorts(any(), eq("schema"), eq(null)))
                .thenReturn(new CohortDto.Page("schema", "V0", null, List.of()));

        mockMvc.perform(get("/api/cohorts?groupBy=schema")).andExpect(status().isOk());

        verify(cohortService).cohorts(
                eq(new InsightFilter(null, null, null, null, null, null, null, null)), eq("schema"), isNull());
    }

    /**
     * The screen has to know when the answer is a description of one cohort rather than a
     * comparison, and the way it learns that is this string. Serialising it as absent rather
     * than as an empty note is part of the contract.
     */
    @Test
    void aPageWithoutABasisNoteCarriesNoneRatherThanAnEmptyString() throws Exception {
        when(cohortService.cohorts(any(), eq("preset"), eq(null)))
                .thenReturn(new CohortDto.Page("preset", "default", null, List.of()));

        mockMvc.perform(get("/api/cohorts?groupBy=preset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basisNote").doesNotExist());
    }

    @Test
    void anUnknownAxisComesBackAsAProblemDetailWithTheAxesThatExist() throws Exception {
        doThrow(new UnknownFilterValueException("groupBy", "1;drop table finding",
                List.of("harnessVersion", "model", "schema", "preset")))
                .when(cohortService).cohorts(any(), eq("1;drop table finding"), eq(null));

        mockMvc.perform(get("/api/cohorts").param("groupBy", "1;drop table finding"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:dsh-inspector:unknown-filter-value"))
                .andExpect(jsonPath("$.filter").value("groupBy"))
                .andExpect(jsonPath("$.allowed[?(@ == 'harnessVersion')]").exists())
                .andExpect(jsonPath("$.allowed[?(@ == 'preset')]").exists());
    }

    /**
     * {@code groupBy} is required, and the answer to a missing one does not come from the
     * advice — Spring's own missing-parameter handling sets the status and nothing else, so in
     * a slice the body is empty and in the running application it is Boot's default error
     * document. Either way it is not the RFC 9457 shape every other failure on this API
     * carries, which is worth having on record: it is asserted as the status plus "the advice
     * never ran", and the inconsistency is reported rather than asserted away as the contract.
     */
    @Test
    void anAbsentAxisIsRefusedBeforeTheServiceIsCalled() throws Exception {
        final MvcResult result = mockMvc.perform(get("/api/cohorts"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("urn:dsh-inspector");

        verify(cohortService, never()).cohorts(any(), any(), any());
    }
}
