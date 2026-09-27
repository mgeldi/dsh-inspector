package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One evidence row is (finding, shell seq). */
@Embeddable
public record EvidenceId(
        @Column(name = "finding_id", nullable = false) long findingId,
        @Column(name = "seq", nullable = false) int seq) {
}
