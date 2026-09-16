package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import inspector.index.IndexAlreadyRunningException;
import inspector.index.IndexService;
import inspector.index.IndexSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The one mutating endpoint's side of the contract, with the indexer stubbed: the status codes,
 * the two bodies, and the fact that a wipe cannot be triggered by a GET.
 *
 * <p>{@code IndexControllerTest} keeps the end-to-end case over the fixture corpus — that a run
 * really rebuilds and the counts it reports are the ones SQL holds. The 409 belongs here,
 * because producing it means putting the application into a state a test would rather not
 * race: the refusal itself is asserted, deterministically and with a real pipeline, in
 * {@code IndexServiceTest.SingleFlight}.
 */
@WebMvcTest(IndexController.class)
class IndexWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IndexService indexService;

    @Test
    void theRunSummaryIsCountsAndNothingElse() throws Exception {
        when(indexService.run()).thenReturn(new IndexSummary(2, 2, 2, 4, 3, 1, 1, 0, 17));

        // strict: an extra field here would be a leak, not noise — the summary is the index's
        // only self-report and it is allowed to carry counts (DESIGN.md §4.1). evidenceRows is
        // one of those counts: how many redacted rows survive, never what they say.
        mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"streams":2,"sessions":2,"steps":2,"toolCalls":4,"findings":3,
                         "evidenceRows":1,"pruned":1,"parseFailures":0,"durationMs":17}
                        """, true));
    }

    @Test
    void aSecondRunWhileOneIsGoingIsAConflictCarryingASentenceTheBrowserCanShowAsIs()
            throws Exception {
        when(indexService.run()).thenThrow(new IndexAlreadyRunningException());

        final MvcResult result = mockMvc.perform(post("/api/index/run"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("urn:dsh-inspector:index-already-running"))
                .andExpect(jsonPath("$.title").value("Index already running"))
                .andReturn();
        // The frontend shows this string rather than an error sentence, so it has to read as a
        // statement of fact and must not name a path or a pid.
        assertThat(result.getResponse().getContentAsString())
                .contains("an index run is already in progress");
    }

    /**
     * A browser prefetch, a link-follow or a crawler must not be able to wipe and rebuild the
     * index. The mutation is POST-only, and the 405 is what proves the mapping is not the
     * catch-all it would otherwise be.
     */
    @Test
    void aGetCannotAskForARebuild() throws Exception {
        mockMvc.perform(get("/api/index/run")).andExpect(status().isMethodNotAllowed());
    }
}
