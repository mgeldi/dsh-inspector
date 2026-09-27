package inspector.store;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.SchemaValidationException;
import org.springframework.stereotype.Component;

/**
 * Brings the database file to the schema this build writes, then proves the entities match it —
 * in that order, and before the web server accepts a request.
 *
 * <p>The order is the point. Hibernate can validate entities against tables at boot, but it would
 * do so before anything had a chance to notice that the file was written by an older build — and
 * a validation failure there is a failed boot, where the right answer is "this index is a cache of
 * an older shape; drop it and rebuild" (DESIGN.md §4.2). So the version gate runs first
 * ({@link IndexWriter#resetIfStale}, which also creates the schema on a fresh file), and only then
 * is the mapping validated through the JPA schema manager. A mapping that disagrees with
 * {@code schema.sql} after that is a real defect, and failing the boot is the right response.
 *
 * <p>It runs while the context is being built, which is before the embedded server starts
 * listening: no request can reach a table that is about to be dropped. That is why this step no
 * longer needs the index run lock the startup runner holds for the rest of its work.
 */
@Component
public class SchemaGate {

    private final IndexWriter writer;
    private final EntityManagerFactory entityManagerFactory;

    public SchemaGate(final IndexWriter writer, final EntityManagerFactory entityManagerFactory) {
        this.writer = writer;
        this.entityManagerFactory = entityManagerFactory;
    }

    @PostConstruct
    public void open() {
        writer.resetIfStale(IndexWriter.SCHEMA_VERSION);
        try {
            entityManagerFactory.getSchemaManager().validate();
        } catch (final SchemaValidationException e) {
            throw new IllegalStateException("the entity mapping does not match schema.sql, which this"
                    + " build just applied — the two were edited apart: " + e.getMessage(), e);
        }
    }
}
