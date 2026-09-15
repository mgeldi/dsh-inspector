package inspector.detect;

import inspector.ingest.StreamFacts;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * One row per tool error not owned elsewhere. Confidence stays null because there is no
 * attribution to grade, and the UI distinguishes these by {@code detector}, not by rendering
 * every null as "unattributed".
 *
 * <p>One owner per source event: StampGuardDetector claims FS_STALE_VERSION, and this detector
 * skips exactly the codes another detector claims, so no source error produces two findings.
 */
@Component
public final class ErrorPlaneDetector implements Detector {

    public static final String ID = "error-plane";

    private final Set<String> ownedElsewhere;

    public ErrorPlaneDetector(final StampGuardDetector stampGuard) {
        this.ownedElsewhere = stampGuard.ownsToolCodes();
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
                        e.absolutePath(), e.seq(), null, null, e.occurredAt(),
                        "%s returned %s".formatted(e.tool() == null ? "tool" : e.tool(), e.code()),
                        List.of()))
                .toList();
    }
}
