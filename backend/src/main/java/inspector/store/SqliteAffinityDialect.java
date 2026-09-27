package inspector.store;

import java.sql.Types;
import org.hibernate.community.dialect.SQLiteDialect;

/**
 * The community SQLite dialect, taught what SQLite's column types actually mean.
 *
 * <p>SQLite stores values by type <em>affinity</em> (sqlite.org/datatype3.html §3): a column
 * declared {@code INTEGER} holds a 64-bit integer, a boolean and a small int alike, and the JDBC
 * driver reports every such column as {@link Types#INTEGER}. Hibernate's schema validation compares
 * type codes, so a {@code long} field against an {@code INTEGER} column reads as a mismatch — for
 * a column that holds exactly that {@code long}. Validation is the reason the entities cannot
 * drift from {@code schema.sql} silently ({@code SchemaGate}), so the answer is not to switch it
 * off but to compare by affinity, which is the comparison SQLite itself makes.
 */
public class SqliteAffinityDialect extends SQLiteDialect {

    private enum Affinity { INTEGER, REAL, TEXT }

    @Override
    public boolean equivalentTypes(final int typeCode1, final int typeCode2) {
        if (super.equivalentTypes(typeCode1, typeCode2)) {
            return true;
        }
        final Affinity a = affinity(typeCode1);
        return a != null && a == affinity(typeCode2);
    }

    private static Affinity affinity(final int typeCode) {
        return switch (typeCode) {
            case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                    Affinity.INTEGER;
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> Affinity.REAL;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.NCLOB -> Affinity.TEXT;
            default -> null;
        };
    }
}
