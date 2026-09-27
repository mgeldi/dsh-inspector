package inspector.dto;

/**
 * One finding row as served by the list and detail endpoints.
 *
 * <p>{@code detail} is a second constant under {@code code} — the provider's specific reason
 * inside a generic fatal-turn code ({@code INVALID_REQUEST} / {@code media_budget_exceeded}), or
 * the write form of a shell edit — and null wherever there is none.
 *
 * <p>This is the aggregate shape: it carries no evidence text. The redacted
 * shell excerpts live in {@link FindingDetailDto#getEvidence()} and are only
 * ever returned by {@code GET /api/findings/{id}} (DESIGN.md §4.1).
 */
public record FindingDto(
        long id,
        String sessionId,
        String detector,
        String plane,
        String category,
        String code,
        String detail,
        Double confidence,
        String pathHint,
        Long seq,
        Long staleSeq,
        Long causeSeq,
        Long occurredAt,
        String summary) {

    /**
     * One redacted shell-evidence row for a finding, in shell order.
     * Only on the detail endpoint.
     */
    public record Evidence(long seq, String verbClass, String pathHint, String excerptRedacted) {
    }
}
