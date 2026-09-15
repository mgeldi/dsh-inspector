package inspector.detect;

import inspector.ingest.RetryEvent;
import inspector.ingest.StreamFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class RetryStormDetector implements Detector {

    public static final String ID = "retry-storm";
    private static final int STORM = 2;          // maxRetries is 2 in this corpus (§5.4)

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> detect(final StreamFacts facts) {
        final Map<String, List<RetryEvent>> byStep = new LinkedHashMap<>();
        for (final RetryEvent r : facts.retries()) {
            byStep.computeIfAbsent(r.turn() + ":" + r.step(), k -> new ArrayList<>()).add(r);
        }
        return byStep.values().stream()
                .filter(group -> group.size() >= STORM)
                .map(group -> {
                    final RetryEvent last = group.get(group.size() - 1);
                    return new Finding(ID, Plane.INFRASTRUCTURE, null, last.code(), null, null,
                            last.seq(), null, null, 0L,
                            "step exhausted its retry budget (%d retries, last %s)"
                                    .formatted(group.size(), last.code()),
                            List.of());
                })
                .toList();
    }
}
