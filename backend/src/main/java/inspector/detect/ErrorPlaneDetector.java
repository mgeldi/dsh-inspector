package inspector.detect;

import inspector.ingest.StreamFacts;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * One row per tool error not owned elsewhere. Confidence stays null because there is no
 * attribution to grade — a different fact from stamp-guard's {@code EXTERNAL}, where a cause
 * was looked for in the window and none was found.
 *
 * <p>The screen tells those two apart by {@code category}, which is null here and always set
 * by the detector that does attribute. This sentence used to say "by {@code detector}" and the
 * UI ignored it, rendering every null confidence as "unattributed" under a tooltip claiming a
 * search had failed — for rows where no search is ever run. The rule is now
 * {@code findings.spec.ts} rather than this paragraph, which is the only reason to trust it.
 *
 * <p>One owner per source event: this detector skips exactly the codes <em>other</em> detectors
 * claim, so no source error produces two findings. It asks every detector, because
 * {@link Detector#ownsToolCodes()} is a contract on the interface and not a quirk of one
 * implementation. It used to ask {@code StampGuardDetector} by concrete type, which held for as
 * long as that was the only owning detector and would have broken silently — with no failing test
 * — the moment a second one appeared. That moment is exactly what {@code Detector}'s "one class
 * plus a bean declaration" promise is about.
 */
@Component
public final class ErrorPlaneDetector implements Detector {

    public static final String ID = "error-plane";

    private final Set<String> ownedElsewhere;

    /**
     * @param detectors every detector in the context. This is not a constructor cycle: the
     *     container does not hand a bean a collection containing the bean under construction, and
     *     {@code ApplicationContextTest} proves it the blunt way — the context boots with this
     *     detector as one of the six registered in it. The identity filter below keeps the same
     *     semantics for the lists the tests assemble by hand, where nothing is excluded for
     *     anybody.
     */
    public ErrorPlaneDetector(final List<Detector> detectors) {
        this.ownedElsewhere = detectors.stream()
                .filter(other -> other != this)
                .flatMap(other -> other.ownsToolCodes().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> detect(final StreamFacts facts) {
        return facts.errors().stream()
                .filter(e -> !ownedElsewhere.contains(e.code()))
                .map(e -> new Finding(ID, ErrorPlanes.ofToolCode(e.code()), null, e.code(), null, null,
                        e.absolutePath(), e.seq(), null, null, e.occurredAt(),
                        "%s returned %s".formatted(e.tool() == null ? "tool" : e.tool(), e.code()),
                        List.of()))
                .toList();
    }
}
