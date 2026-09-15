package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ErrorPlanesTest {

    @Test
    void everyCodeTheCorpusEmitsIsMappedExplicitly() {
        assertThat(ErrorPlanes.knownToolCodes()).containsExactlyInAnyOrder(
                "FS_EDIT_NOT_FOUND", "FS_NOT_OBSERVED", "FS_STALE_VERSION", "FS_NOT_FOUND",
                "WEB_PROVIDER_CREDENTIAL_MISSING", "INVALID_ARGS", "SEARCH_FAILED",
                "SEARCH_INVALID_PATTERN", "FS_NOT_REGULAR_FILE", "FS_SANDBOX_DENIED",
                "ASK_ABORTED", "GOAL_TOOL_AUTHORITY_REQUIRED", "ABORTED", "TOOL_OUTCOME_UNKNOWN",
                "CODEGRAPH_UNAVAILABLE", "INVALID_TOOL_OUTPUT");
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
