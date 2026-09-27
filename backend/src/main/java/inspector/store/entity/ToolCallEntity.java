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
 * One tool call as observed. {@code outcomeOnly} marks a result whose call never appeared: stored
 * so no outcome is lost, excluded from every rate denominator (DESIGN.md §6, §7). {@code day} is
 * the UTC day of {@code startedAt}, written at index time so the daily series is a plain
 * {@code GROUP BY} instead of a call to one database's date function.
 */
@Entity
@Table(name = "tool_call")
public class ToolCallEntity {

    @EmbeddedId
    private ToolCallId id;
    @Column(name = "turn")
    private Integer turn;
    @Column(name = "step")
    private Integer step;
    @Column(name = "name")
    private String name;
    @Column(name = "started_at")
    private Long startedAt;
    @Column(name = "ended_at")
    private Long endedAt;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "plane")
    private String plane;
    @Column(name = "path_hint")
    private String pathHint;
    @Column(name = "outcome_only", nullable = false)
    private boolean outcomeOnly;
    @Column(name = "day")
    private String day;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "session_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "source_file", referencedColumnName = "source_file", insertable = false, updatable = false)})
    private SessionEntity session;

    protected ToolCallEntity() {
    }

    public ToolCallEntity(final ToolCallId id, final Integer turn, final Integer step, final String name,
                          final Long startedAt, final Long endedAt, final Long durationMs,
                          final String errorCode, final String plane, final String pathHint,
                          final boolean outcomeOnly, final String day) {
        this.id = id;
        this.turn = turn;
        this.step = step;
        this.name = name;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.plane = plane;
        this.pathHint = pathHint;
        this.outcomeOnly = outcomeOnly;
        this.day = day;
    }

    public ToolCallId getId() {
        return id;
    }

    public Integer getTurn() {
        return turn;
    }

    public Integer getStep() {
        return step;
    }

    public String getName() {
        return name;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getPlane() {
        return plane;
    }

    public String getPathHint() {
        return pathHint;
    }

    public boolean isOutcomeOnly() {
        return outcomeOnly;
    }

    public String getDay() {
        return day;
    }

    public SessionEntity getSession() {
        return session;
    }
}
