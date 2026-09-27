package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One completed step and its throughput. {@code timingSource} records which extractor produced
 * the two derived columns (DESIGN.md §3.3, §6).
 */
@Entity
@Table(name = "step")
public class StepEntity {

    @EmbeddedId
    private StepId id;
    @Column(name = "started_at", nullable = false)
    private long startedAt;
    @Column(name = "ended_at")
    private Long endedAt;
    @Column(name = "input_tokens")
    private Integer inputTokens;
    @Column(name = "output_tokens")
    private Integer outputTokens;
    @Column(name = "decode_tps")
    private Double decodeTps;
    @Column(name = "ttft_ms")
    private Integer ttftMs;
    @Column(name = "timing_source", nullable = false)
    private String timingSource;

    /** Read-only: the stream this step belongs to, for the joins the filter contract needs. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "source_file", referencedColumnName = "source_file", insertable = false, updatable = false)})
    private SessionEntity session;

    protected StepEntity() {
    }

    public StepEntity(final StepId id, final long startedAt, final Long endedAt,
                      final Integer inputTokens, final Integer outputTokens, final Double decodeTps,
                      final Integer ttftMs, final String timingSource) {
        this.id = id;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.decodeTps = decodeTps;
        this.ttftMs = ttftMs;
        this.timingSource = timingSource;
    }

    public StepId getId() {
        return id;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public Double getDecodeTps() {
        return decodeTps;
    }

    public Integer getTtftMs() {
        return ttftMs;
    }

    public String getTimingSource() {
        return timingSource;
    }

    public SessionEntity getSession() {
        return session;
    }
}
