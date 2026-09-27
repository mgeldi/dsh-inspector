package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A tool call is the event seq it was observed at, inside one stream. Measured unique on both
 * real indexes (0 duplicates over 17,244 rows), and it is unique by construction: each row comes
 * from one event — the call's own, or the result's for a call that never appeared — and a seq
 * names one event per stream. A natural key also means no generated id, which is what keeps the
 * 17k-row insert batched.
 */
@Embeddable
public record ToolCallId(
        @Column(name = "session_id", nullable = false) String sessionId,
        @Column(name = "source_file", nullable = false) String sourceFile,
        @Column(name = "seq", nullable = false) int seq) {
}
