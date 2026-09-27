package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One event stream: a session id and the log file it was read from. The whole schema is keyed on
 * this pair because one session id can exist in both log conventions with independent seq spaces
 * (DESIGN.md §3.2) — a session id alone names two streams on a real install.
 */
@Embeddable
public record StreamId(
        @Column(name = "id", nullable = false) String sessionId,
        @Column(name = "source_file", nullable = false) String sourceFile) {
}
