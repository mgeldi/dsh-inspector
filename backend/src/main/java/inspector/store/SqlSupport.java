package inspector.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * The shared pieces of the read queries (DESIGN.md §7). The session joins live
 * here because three repositories read the same tables, and two copies of a
 * join drifting apart is a WHERE clause matching different rows. The row-read
 * helpers sit next to them: they belong to the reads, and there is nothing
 * else in store to own them.
 */
public final class SqlSupport {

    private SqlSupport() {
    }

    /** The session join for queries rooted at finding {@code f}. */
    public static final String FINDING_JOIN =
            "from finding f join session s on s.id = f.session_id and s.source_file = f.source_file";

    /** The session join for queries rooted at tool_call {@code t}. */
    public static final String TOOL_CALL_JOIN =
            "from tool_call t join session s on s.id = t.session_id and s.source_file = t.source_file";

    /** A nullable numeric column, read as a boxed {@code Double}. */
    public static Double asDouble(final Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    /** One long from a single-row aggregate query. */
    public static long singleLong(final JdbcClient jdbc, final String sql, final List<Object> params) {
        return jdbc.sql(sql).params(params)
                .query((RowMapper<Long>) (rs, rowNum) -> rs.getLong(1))
                .single();
    }
}
