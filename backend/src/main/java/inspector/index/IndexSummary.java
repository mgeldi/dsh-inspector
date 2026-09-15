package inspector.index;

/**
 * Counts only. The summary line this backs is the index's only self-report, and a report
 * that carried content or a corpus path would be a privacy breach (DESIGN.md §4.1).
 */
public record IndexSummary(int streams, int sessions, int steps, int toolCalls,
                           int findings, long parseFailures, long durationMs) {
}
