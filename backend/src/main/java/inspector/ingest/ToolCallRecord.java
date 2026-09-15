package inspector.ingest;

/**
 * One tool call as observed in one stream.
 *
 * <p>{@code outcomeOnly} marks the one kind of row that is not an observed call: a
 * {@code tool/result} whose {@code tool/call} never appeared in the stream. Such a result is
 * still stored — no outcome is lost — but it carries no call to point at, so it must not count
 * toward "tool calls" anywhere a rate is computed. Set only in the ingestor's orphan branch;
 * a call that never gets a result (flushed at end of stream) is still an observed call and
 * stays {@code false}.
 */
public record ToolCallRecord(Integer turn, Integer step, int seq, String name,
                             Long startedAt, Long endedAt, Long durationMs,
                             String errorCode, String absolutePath, boolean outcomeOnly) {
}
