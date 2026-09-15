package inspector.ingest;

/**
 * occurredAt is the llm/retry event's timestamp; the detector persists it verbatim as
 * finding.occurred_at, so the 24h/7d/30d presets filter when the retry actually happened.
 */
public record RetryEvent(int seq, int turn, int step, String code, long occurredAt) {
}
