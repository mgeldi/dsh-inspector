package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * One finding. The id is the only generated key in the schema, because it is the only one the
 * API exposes ({@code GET /api/findings/{id}}); every other table is keyed by what the log says.
 *
 * <p>{@code occurredAt} is the event's time, never the index time (§6), and {@code day} its UTC
 * day, written at index time for the daily series. {@code summary} is a sentence a detector
 * generated from codes and seqs — never user text.
 */
@Entity
@Table(name = "finding")
public class FindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false)
    private String sessionId;
    @Column(name = "source_file", nullable = false)
    private String sourceFile;
    @Column(name = "detector", nullable = false)
    private String detector;
    @Column(name = "plane", nullable = false)
    private String plane;
    @Column(name = "category")
    private String category;
    @Column(name = "code")
    private String code;
    @Column(name = "detail")
    private String detail;
    @Column(name = "confidence")
    private Double confidence;
    @Column(name = "path_hint")
    private String pathHint;
    @Column(name = "seq")
    private Integer seq;
    @Column(name = "stale_seq")
    private Integer staleSeq;
    @Column(name = "cause_seq")
    private Integer causeSeq;
    @Column(name = "occurred_at", nullable = false)
    private long occurredAt;
    @Column(name = "day", nullable = false)
    private String day;
    @Column(name = "summary", nullable = false)
    private String summary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "source_file", referencedColumnName = "source_file", insertable = false, updatable = false)})
    private SessionEntity session;

    /** Only the detail read fetches this; no list or aggregate ever touches it (§4.1). */
    @OneToMany(mappedBy = "finding", fetch = FetchType.LAZY)
    @OrderBy("id.seq")
    private List<ShellEvidenceEntity> evidence = new ArrayList<>();

    protected FindingEntity() {
    }

    public FindingEntity(final String sessionId, final String sourceFile, final String detector,
                         final String plane, final String category, final String code,
                         final String detail, final Double confidence, final String pathHint,
                         final Integer seq, final Integer staleSeq, final Integer causeSeq,
                         final long occurredAt, final String day, final String summary) {
        this.sessionId = sessionId;
        this.sourceFile = sourceFile;
        this.detector = detector;
        this.plane = plane;
        this.category = category;
        this.code = code;
        this.detail = detail;
        this.confidence = confidence;
        this.pathHint = pathHint;
        this.seq = seq;
        this.staleSeq = staleSeq;
        this.causeSeq = causeSeq;
        this.occurredAt = occurredAt;
        this.day = day;
        this.summary = summary;
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public String getDetector() {
        return detector;
    }

    public String getPlane() {
        return plane;
    }

    public String getCategory() {
        return category;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getPathHint() {
        return pathHint;
    }

    public Integer getSeq() {
        return seq;
    }

    public Integer getStaleSeq() {
        return staleSeq;
    }

    public Integer getCauseSeq() {
        return causeSeq;
    }

    public long getOccurredAt() {
        return occurredAt;
    }

    public String getDay() {
        return day;
    }

    public String getSummary() {
        return summary;
    }

    public SessionEntity getSession() {
        return session;
    }

    public List<ShellEvidenceEntity> getEvidence() {
        return evidence;
    }
}
