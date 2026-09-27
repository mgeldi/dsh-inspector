package inspector.detect;

import inspector.ingest.StreamFacts;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * One finding per turn that ended in error. The code is the harness's typed code and the detail
 * the provider's specific one when the message carried it — {@code INVALID_REQUEST} with
 * {@code media_budget_exceeded}, {@code SERVER} with {@code unavailable_error} — so a fatal turn
 * reads as the same failure family as the retry storm that often precedes it.
 */
@Component
public final class FatalTurnDetector implements Detector {

    public static final String ID = "fatal-turn";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> detect(final StreamFacts facts) {
        return facts.fatalTurns().stream()
                .map(f -> new Finding(ID, Plane.INFRASTRUCTURE, null, f.code(), f.detail(), null,
                        null, null, null, null, f.occurredAt(),
                        "turn %d ended in error%s".formatted(f.turn(), describe(f.code(), f.detail())),
                        List.of()))
                .toList();
    }

    private static String describe(final String code, final String detail) {
        if (code == null && detail == null) {
            return "";
        }
        if (detail == null) {
            return ": " + code;
        }
        return ": " + (code == null ? "" : code + " ") + "(" + detail + ")";
    }
}
