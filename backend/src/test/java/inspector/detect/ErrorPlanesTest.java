package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ErrorPlanesTest {

    /**
     * What this pins is the <em>map</em>, not the corpus. The name used to say
     * "everyCodeTheCorpusEmitsIsMappedExplicitly", which claimed more than the body does: the
     * assertion compares the map against a literal list written beside it, so it passes by
     * construction and can never notice a harness that starts emitting a code nobody mapped.
     *
     * <p>It did not notice. A live corpus emitted {@code FS_AMBIGUOUS_EDIT}, the
     * {@code getOrDefault} in {@link ErrorPlanes#ofToolCode} sent it to INFRASTRUCTURE, and a
     * model-misuse finding sat on the operator's plane in the headline chart with a green suite.
     * Only a run over real data can catch that, which is why {@code IndexService} now reports
     * unmapped codes as it indexes — the check that has the data is the one that has to make it.
     */
    @Test
    void theMapPinsOneExplicitPlanePerKnownCode() {
        assertThat(ErrorPlanes.knownToolCodes()).containsExactlyInAnyOrder(
                "FS_EDIT_NOT_FOUND", "FS_AMBIGUOUS_EDIT", "FS_NOT_OBSERVED", "FS_STALE_VERSION",
                "FS_NOT_FOUND", "WEB_PROVIDER_CREDENTIAL_MISSING", "INVALID_ARGS", "SEARCH_FAILED",
                "SEARCH_INVALID_PATTERN", "FS_NOT_REGULAR_FILE", "FS_SANDBOX_DENIED",
                "ASK_ABORTED", "GOAL_TOOL_AUTHORITY_REQUIRED", "ABORTED", "TOOL_OUTCOME_UNKNOWN",
                "CODEGRAPH_UNAVAILABLE", "INVALID_TOOL_OUTPUT");
    }

    /** An edit the model could not aim is the model's problem, not the operator's. */
    @Test
    void anAmbiguousEditIsModelMisuseNotInfrastructure() {
        assertThat(ErrorPlanes.ofToolCode("FS_AMBIGUOUS_EDIT")).isEqualTo(Plane.MODEL_MISUSE);
    }

    @Test
    void guardPlaneIsWhereTheHarnessWorked() {
        assertThat(ErrorPlanes.ofToolCode("FS_SANDBOX_DENIED")).isEqualTo(Plane.GUARD);
        assertThat(ErrorPlanes.ofToolCode("ASK_ABORTED")).isEqualTo(Plane.GUARD);
        assertThat(ErrorPlanes.ofToolCode("FS_EDIT_NOT_FOUND")).isEqualTo(Plane.MODEL_MISUSE);
        assertThat(ErrorPlanes.ofToolCode("WEB_PROVIDER_CREDENTIAL_MISSING"))
                .isEqualTo(Plane.INFRASTRUCTURE);
    }

    @Test
    void anUnknownCodeGoesToTheOperatorAndStaysVisible() {
        assertThat(ErrorPlanes.ofToolCode("SOMETHING_NEW")).isEqualTo(Plane.INFRASTRUCTURE);
        assertThat(ErrorPlanes.lookup("SOMETHING_NEW")).isEmpty();   // the visibility hook
    }
}
