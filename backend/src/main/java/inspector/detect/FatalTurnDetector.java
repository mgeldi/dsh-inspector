package inspector.detect;

import inspector.ingest.StreamFacts;
import java.util.List;
import org.springframework.stereotype.Component;

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
                .map(f -> new Finding(ID, Plane.INFRASTRUCTURE, null, f.code(), null, null,
                        null, null, null, 0L,
                        "turn %d ended in error%s".formatted(f.turn(),
                                f.code() == null ? "" : " (" + f.code() + ")"),
                        List.of()))
                .toList();
    }
}
