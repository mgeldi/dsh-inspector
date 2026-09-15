package inspector.ingest;

/**
 * occurredAt is the raw event's timestamp, carried verbatim: the writer persists it as
 * finding.occurred_at, and nothing downstream may interpolate a time (§6 — event time, not
 * index time).
 */
public record ErrorEvent(int seq, Integer turn, Integer step, String tool, String code,
                         String absolutePath, long occurredAt) {
}
