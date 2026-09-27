package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One redacted shell excerpt behind a finding — the only text the index holds, truncated and
 * credential-masked in ingest before it was ever handed over, and stored only while
 * {@code inspector.evidence.store} is on (DESIGN.md §4.1).
 */
@Entity
@Table(name = "shell_evidence")
public class ShellEvidenceEntity {

    @EmbeddedId
    private EvidenceId id;
    @Column(name = "verb_class", nullable = false)
    private String verbClass;
    @Column(name = "path_hint")
    private String pathHint;
    @Column(name = "excerpt_redacted")
    private String excerptRedacted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finding_id", insertable = false, updatable = false)
    private FindingEntity finding;

    protected ShellEvidenceEntity() {
    }

    public ShellEvidenceEntity(final EvidenceId id, final String verbClass, final String pathHint,
                               final String excerptRedacted) {
        this.id = id;
        this.verbClass = verbClass;
        this.pathHint = pathHint;
        this.excerptRedacted = excerptRedacted;
    }

    public EvidenceId getId() {
        return id;
    }

    public String getVerbClass() {
        return verbClass;
    }

    public String getPathHint() {
        return pathHint;
    }

    public String getExcerptRedacted() {
        return excerptRedacted;
    }
}
