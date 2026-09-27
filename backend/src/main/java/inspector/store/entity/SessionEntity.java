package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One indexed stream. {@code role} is derived from {@code delegation_depth} at write time and
 * stored, because it is a filter facet and a cohort axis: a derived value that every read would
 * otherwise re-derive in a CASE is a column.
 */
@Entity
@Table(name = "session")
public class SessionEntity {

    public static final String ORCHESTRATOR = "orchestrator";
    public static final String SUBAGENT = "subagent";

    @EmbeddedId
    private StreamId id;
    @Column(name = "project_slug", nullable = false)
    private String projectSlug;
    @Column(name = "`schema`", nullable = false)
    private String schema;
    @Column(name = "started_at", nullable = false)
    private long startedAt;
    @Column(name = "ended_at")
    private Long endedAt;
    @Column(name = "agent_preset")
    private String agentPreset;
    @Column(name = "delegation_depth")
    private Integer delegationDepth;
    @Column(name = "model")
    private String model;
    @Column(name = "provider")
    private String provider;
    @Column(name = "role")
    private String role;
    @Column(name = "context_window")
    private Integer contextWindow;
    @Column(name = "harness_version")
    private String harnessVersion;
    @Column(name = "version_inferred", nullable = false)
    private boolean versionInferred;
    @Column(name = "indexed_at", nullable = false)
    private long indexedAt;
    @Column(name = "fatal_turns", nullable = false)
    private int fatalTurns;

    protected SessionEntity() {
    }

    public SessionEntity(final StreamId id, final String projectSlug, final String schema,
                         final long startedAt, final Long endedAt, final String agentPreset,
                         final Integer delegationDepth, final String model, final String provider,
                         final Integer contextWindow, final String harnessVersion,
                         final boolean versionInferred, final long indexedAt, final int fatalTurns) {
        this.id = id;
        this.projectSlug = projectSlug;
        this.schema = schema;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.agentPreset = agentPreset;
        this.delegationDepth = delegationDepth;
        this.model = model;
        this.provider = provider;
        this.role = roleOf(delegationDepth);
        this.contextWindow = contextWindow;
        this.harnessVersion = harnessVersion;
        this.versionInferred = versionInferred;
        this.indexedAt = indexedAt;
        this.fatalTurns = fatalTurns;
    }

    /** Depth 0 is the session a person started; anything deeper was started by an agent. */
    public static String roleOf(final Integer delegationDepth) {
        if (delegationDepth == null) {
            return null;
        }
        return delegationDepth == 0 ? ORCHESTRATOR : SUBAGENT;
    }

    public StreamId getId() {
        return id;
    }

    public String getProjectSlug() {
        return projectSlug;
    }

    public String getSchema() {
        return schema;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public String getAgentPreset() {
        return agentPreset;
    }

    public Integer getDelegationDepth() {
        return delegationDepth;
    }

    public String getModel() {
        return model;
    }

    public String getProvider() {
        return provider;
    }

    public String getRole() {
        return role;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public String getHarnessVersion() {
        return harnessVersion;
    }

    public boolean isVersionInferred() {
        return versionInferred;
    }

    public long getIndexedAt() {
        return indexedAt;
    }

    public int getFatalTurns() {
        return fatalTurns;
    }
}
