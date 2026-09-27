package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A step is (turn, step) inside one stream. */
@Embeddable
public record StepId(
        @Column(name = "session_id", nullable = false) String sessionId,
        @Column(name = "source_file", nullable = false) String sourceFile,
        @Column(name = "turn", nullable = false) int turn,
        @Column(name = "step", nullable = false) int step) {
}
