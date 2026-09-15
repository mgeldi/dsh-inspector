package inspector.detect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static java.util.Map.entry;

/**
 * The whole of DESIGN.md §5.1 as data. Total over the 16 codes the corpus actually emits;
 * ErrorPlanesTest asserts that totality, so an unmapped code is a test failure rather than a
 * silently misclassified finding.
 */
public final class ErrorPlanes {

    private static final Map<String, Plane> TOOL_CODES = Map.ofEntries(
            entry("WEB_PROVIDER_CREDENTIAL_MISSING", Plane.INFRASTRUCTURE),
            entry("CODEGRAPH_UNAVAILABLE", Plane.INFRASTRUCTURE),
            entry("TOOL_OUTCOME_UNKNOWN", Plane.INFRASTRUCTURE),
            entry("INVALID_TOOL_OUTPUT", Plane.INFRASTRUCTURE),
            entry("FS_STALE_VERSION", Plane.GUARD),
            entry("FS_NOT_OBSERVED", Plane.GUARD),
            entry("FS_SANDBOX_DENIED", Plane.GUARD),
            entry("GOAL_TOOL_AUTHORITY_REQUIRED", Plane.GUARD),
            entry("ASK_ABORTED", Plane.GUARD),
            entry("ABORTED", Plane.GUARD),
            entry("FS_EDIT_NOT_FOUND", Plane.MODEL_MISUSE),
            entry("FS_NOT_FOUND", Plane.MODEL_MISUSE),
            entry("INVALID_ARGS", Plane.MODEL_MISUSE),
            entry("SEARCH_INVALID_PATTERN", Plane.MODEL_MISUSE),
            entry("SEARCH_FAILED", Plane.MODEL_MISUSE),
            entry("FS_NOT_REGULAR_FILE", Plane.MODEL_MISUSE));

    /** llm/retry failure codes actually observed: TIMEOUT, TRANSPORT, SERVER. RATE_LIMIT and
     *  EMPTY_RESPONSE are declared retryable by the harness policy but were not seen here. */
    public static final Set<String> RETRY_CODES =
            Set.of("TIMEOUT", "TRANSPORT", "SERVER", "RATE_LIMIT", "EMPTY_RESPONSE");

    private ErrorPlanes() {
    }

    public static Plane ofToolCode(final String code) {
        return TOOL_CODES.getOrDefault(code, Plane.INFRASTRUCTURE);
    }

    /** Any retry is the operator's problem, whatever the code — §5.1's first row. */
    public static Plane ofRetryCode(final String code) {
        return Plane.INFRASTRUCTURE;
    }

    public static Optional<Plane> lookup(final String code) {
        return Optional.ofNullable(TOOL_CODES.get(code));
    }

    public static Set<String> knownToolCodes() {
        return TOOL_CODES.keySet();
    }
}
