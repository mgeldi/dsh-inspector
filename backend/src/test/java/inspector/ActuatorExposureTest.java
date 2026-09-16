package inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The management plane as the shipped configuration leaves it: one endpoint, and it says only
 * whether the application is up.
 *
 * <p>The two 404s are the point of this class, and they are the reason {@code
 * ManagementExposureKeyTest} exists next to it — Boot exposes {@code health} alone by default, so
 * a request for {@code env} returning 404 proves nothing on its own. Only proving the exposure
 * property <em>does</em> something (there, by opening a second endpoint and watching it answer)
 * turns these assertions from a description of a default into a check of a decision.
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "inspector.db=target/test-actuator.sqlite",
        "inspector.corpus=fixtures/sessions"})
class ActuatorExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    /**
     * Not a tautology, and the reason it is here: Boot's default web exposure is {@code health}
     * alone, so if the key in {@code application.yml} were misspelled — {@code managment:}, or the
     * spelling from an older major, which has moved before — every 404 assertion in this class
     * would still pass while the config line did nothing at all. Reading the property back under
     * its canonical name is the one check that distinguishes a decision from a decoration.
     */
    @Test
    void theShippedLinesAreTheKeysBootReads() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
    }

    @Test
    void healthSaysUpAndNothingElse() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                // no components block: show-details is pinned to never because a failing SQLite
                // connection reports the database file path in some of its error text (§4.1)
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void bothProbeGroupsAnswer() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void theEndpointsThatPrintConfigurationAreNotThere() throws Exception {
        // env and configprops would print inspector.corpus and inspector.db: absolute paths that
        // carry the username, which is the §4.1 boundary and why exposure is health-only.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
        // these two are here because they are the ones that map the whole application: a
        // reviewer reaching for a bean listing finds nothing, which is the intent
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/mappings")).andExpect(status().isNotFound());
    }
}
