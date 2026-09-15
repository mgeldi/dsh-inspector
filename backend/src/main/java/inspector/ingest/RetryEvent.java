package inspector.ingest;

public record RetryEvent(int seq, int turn, int step, String code) {
}
