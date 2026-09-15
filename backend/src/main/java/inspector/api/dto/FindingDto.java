package inspector.api.dto;

import java.util.List;

/**
 * One finding row as served by the list and detail endpoints.
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
        static List<Evidence> none() {
            return List.of();
        }
    }
}
