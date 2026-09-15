package inspector.ingest;

/** timingSource: "chunk-events" | "embedded-stream" | "none" (DESIGN.md §3.3, §6). */
public record StepRecord(int turn, int step, long startedAt, Long endedAt,
                         Integer inputTokens, Integer outputTokens,
                         Double decodeTps, Integer ttftMs, String timingSource) {

    StepRecord withEndedAt(final long ended) {
        return new StepRecord(turn, step, startedAt, ended, inputTokens, outputTokens,
                decodeTps, ttftMs, timingSource);
    }
}
