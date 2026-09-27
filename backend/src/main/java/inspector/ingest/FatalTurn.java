package inspector.ingest;

/**
 * A turn that ended in error. {@code code} is the harness's typed code
 * ({@code turn/end.data.reason.error.code}); {@code detail} is the specific code carried inside a
 * provider body of the documented shape {@code NNN: {json}}, such as {@code media_budget_exceeded}
 * or {@code unavailable_error}. Both are constants the harness or the provider defines.
 *
 * <p>The raw message is deliberately not carried: it is provider text and could contain anything.
 * Only the two codes survive (§5.2 rule 1). occurredAt is the turn/end event's timestamp,
 * persisted verbatim as finding.occurred_at.
 */
public record FatalTurn(int turn, String code, String detail, long occurredAt) {
}
