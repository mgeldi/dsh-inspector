package inspector.detect;

import inspector.ingest.StreamFacts;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * One row per tool error not owned elsewhere. Confidence stays null because there is no
 * attribution to grade, and the UI distinguishes these by {@code detector}, not by rendering
 * every null as "unattributed".
 */
@Component
public final class ErrorPlaneDetector implements Detector {

    public static final String ID = "error-plane";

    private final Set<String> ownedElsewhere;

    public ErrorPlaneDetector() {
        // No other detector claims a tool code yet; the stamp-guard wiring arrives with
        // StampGuardDetector and takes exactly the codes that detector claims.
        this.ownedElsewhere = Set.of();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> detect(final StreamFacts facts) {
        return facts.errors().stream()
                .filter(e -> !ownedElsewhere.contains(e.code()))
                .map(e -> new Finding(ID, ErrorPlanes.ofToolCode(e.code()), null, e.code(), null,
                        e.absolutePath(), e.seq(), null, null, 0L,
                        "%s returned %s".formatted(e.tool() == null ? "tool" : e.tool(), e.code()),
                        List.of()))
                .toList();
    }
}
