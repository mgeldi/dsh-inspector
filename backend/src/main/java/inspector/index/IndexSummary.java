package inspector.index;

/**
 * Counts only. The summary line this backs is the index's only self-report, and a report
 * that carried content or a corpus path would be a privacy breach (DESIGN.md §4.1).
 *
 * @param evidenceRows redacted evidence rows the run stored, a subset of {@code findings}.
 *                     Evidence is config-gated and every row has passed redaction to exist at
 *                     all (§4.1), so this is the number that says how much of the finding set
 *                     has command-level support behind it — and a run with evidence storage on
 *                     and this at zero is a detector regression nobody would otherwise see.
 *                     It was computed per stream and thrown away.
 * @param pruned       streams the run discarded because the corpus no longer holds them. A run
 *                     that silently drops rows is the same class of problem as one that silently
 *                     keeps them, so the number is on the wire next to the ones it explains.
 */
public record IndexSummary(int streams, int sessions, int steps, int toolCalls,
                           int findings, int evidenceRows, int pruned, long parseFailures,
                           long durationMs) {
}
