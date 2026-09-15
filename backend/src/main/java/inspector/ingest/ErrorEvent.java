package inspector.ingest;

public record ErrorEvent(int seq, Integer turn, Integer step, String tool, String code,
                         String absolutePath) {
}
