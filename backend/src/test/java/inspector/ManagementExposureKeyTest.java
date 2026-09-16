package inspector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The companion to {@link ActuatorExposureTest}, and the only reason that class's 404s mean
 * anything: here the same property opens a second endpoint, and it opens it.
 *
 * <p>Without this, {@code ActuatorExposureTest} would pass against a typo. Boot's default web
 * exposure is {@code health} alone, so if {@code management.endpoints.web.exposure.include} were
 * misspelled in {@code application.yml} — a key that has moved before — every one of those
 * requests would still 404, the config line would be dead, and the file would read as though
 * someone had made a decision. This class fails the moment the property stops being honoured.
 *
 * <p>It also checks the one claim in {@code application.yml} that is not about hiding something:
 * the readiness group really does contain the database, so readiness means "the index file
 * answers a query" and not merely "the process started".
 */
@SpringBootTest(args = {"--no-index"})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "inspector.db=target/test-actuator-open.sqlite",
        "inspector.corpus=fixtures/sessions",
        "management.endpoints.web.exposure.include=health,beans",
        "management.endpoint.health.show-details=always"})
class ManagementExposureKeyTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theExposurePropertyIsTheThingThatDecidesWhatAnswers() throws Exception {
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isOk());
    }

    @Test
    void readinessWaitsOnTheIndexFileNotJustOnStartup() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"));
    }
}
