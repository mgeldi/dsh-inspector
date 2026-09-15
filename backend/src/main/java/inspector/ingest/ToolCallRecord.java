package inspector.ingest;

public record ToolCallRecord(Integer turn, Integer step, int seq, String name,
                             Long startedAt, Long endedAt, Long durationMs,
                             String errorCode, String absolutePath) {
}
