package inspector.ingest;

/**
 * The raw reason string is deliberately not carried: it is provider text and could contain
 * anything. Only the code parsed from its documented prefix survives (§5.2 rule 1).
 * occurredAt is the turn/end event's timestamp, persisted verbatim as finding.occurred_at.
 */
public record FatalTurn(int turn, String code, long occurredAt) {
}
