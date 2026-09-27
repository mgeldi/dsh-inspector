package inspector.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The index's facts about itself: {@code schema_version}, {@code corpus}, and since rev 5
 * {@code harness_timeline} — which timeline the versions were attributed from (DESIGN.md §3.4) —
 * and {@code analysis_version}, which rules of ingest and detection produced the rows (§12).
 * Nothing else lives here — configuration is read from configuration; these record what the rows
 * were built from, which configuration cannot say.
 */
@Entity
@Table(name = "meta")
public class MetaEntity {

    public static final String SCHEMA_VERSION = "schema_version";
    public static final String CORPUS = "corpus";
    public static final String HARNESS_TIMELINE = "harness_timeline";
    public static final String ANALYSIS_VERSION = "analysis_version";

    @Id
    @Column(name = "`key`", nullable = false)
    private String key;
    @Column(name = "`value`", nullable = false)
    private String value;

    protected MetaEntity() {
    }

    public MetaEntity(final String key, final String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(final String value) {
        this.value = value;
    }
}
