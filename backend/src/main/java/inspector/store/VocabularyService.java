package inspector.store;

import inspector.api.dto.Vocabulary;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The filter vocabulary, read from the index on every request (DESIGN.md §7).
 *
 * <p>The rail is fed by the values actually present in the data, so a
 * re-indexed index shows its new values without a restart, and a value that
 * is in the index is always a legal filter. NULL columns fold into the
 * {@code unknown} bucket, which keeps the rail able to select them.
 *
 * <p>Everything is a {@code select distinct} over {@code session} and
 * {@code finding}; no value is ever spliced into the SQL.
 */
@Component
public final class VocabularyService {

    private final JdbcClient jdbc;

    public VocabularyService(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Vocabulary vocabulary() {
        return new Vocabulary(
                distinct("select distinct coalesce(s.\"schema\", 'unknown') from session s order by 1"),
                distinct("select distinct coalesce(s.model, 'unknown') from session s order by 1"),
                distinct("select distinct coalesce(s.agent_preset, 'unknown') from session s order by 1"),
                distinct("select distinct coalesce(s.harness_version, 'unknown') from session s order by 1"),
                distinct("select distinct coalesce(f.code, 'unknown') from finding f order by 1"),
                distinct("select distinct f.detector from finding f order by 1"),
                distinct("select distinct s.id from session s order by 1"));
    }

    private List<String> distinct(final String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }
}
