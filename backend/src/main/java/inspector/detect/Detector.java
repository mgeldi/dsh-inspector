package inspector.detect;

import inspector.ingest.StreamFacts;
import java.util.List;
import java.util.Set;

/** Adding a detector is one class plus a bean declaration (DESIGN.md §5.5). */
public interface Detector {

    String id();

    /**
     * Codes this detector claims outright. ErrorPlaneDetector skips them so exactly one finding
     * is produced per source event — asserted at the seam in ErrorPlaneDetectorTest and over
     * the corpus by NoDuplicateFindingOwnerTest (Task 13).
     */
    default Set<String> ownsToolCodes() {
        return Set.of();
    }

    List<Finding> detect(StreamFacts facts);
}
