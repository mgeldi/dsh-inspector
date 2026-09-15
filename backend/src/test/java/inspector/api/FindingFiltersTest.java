package inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one-filter-contract property test (DESIGN.md §7): the WHERE clause and
 * its parameter list are built in one place, and every value — including
 * SQL-ish payloads — reaches the clause only as a {@code ?} bind, in the
 * documented order. No Spring context: this is pure string building.
 */
final class FindingFiltersTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "v0",
            "v0' OR '1'='1",
            "1;drop table finding",
            "x) from finding; --",
            "v0; delete from session where 1=1",
            "null"
    })
    void valuesNeverReachTheClauseOnlyTheBindList(final String schemaValue) {
        final InsightFilter filter = new InsightFilter(1_000L, 2_000L, schemaValue, "m", "p", "1.0");
        final FindingFilters.Sql sql = new FindingFilters(filter).forFindings("GUARD", "d", "c", "s");

        assertThat(sql.where()).doesNotContain(schemaValue);
        assertThat(sql.params())
                .containsExactly(1_000L, 2_000L, schemaValue, "m", "p", "1.0", "GUARD", "d", "c", "s");
        assertThat(sql.where()).contains(
                "f.occurred_at >= ?",
                "f.occurred_at <= ?",
                "s.\"schema\" = ?",
                "s.model = ?",
                "s.agent_preset = ?",
                "s.harness_version = ?",
                "f.plane = ?",
                "f.detector = ?",
                "f.code = ?",
                "f.session_id = ?");
        assertThat(sql.where().split(" and ")).hasSize(10);
    }

    @Test
    void absentValuesAddNoClauseAndNoParam() {
        final FindingFilters filters = new FindingFilters(new InsightFilter(null, null, null, null, null, null));
        assertThat(filters.isActive()).isFalse();
        final FindingFilters.Sql sql = filters.forFindings(null, null, null, null);
        assertThat(sql.isEmpty()).isTrue();
        assertThat(sql.where()).isEmpty();
        assertThat(sql.params()).isEmpty();
        assertThat(sql.asWhere()).isEmpty();
    }

    @Test
    void theTimeColumnFollowsTheRootTable() {
        final FindingFilters filters = new FindingFilters(new InsightFilter(1L, 2L, null, null, null, null));
        assertThat(filters.forFinding().where()).contains("f.occurred_at >= ?", "f.occurred_at <= ?");
        assertThat(filters.forSession().where()).contains("s.started_at >= ?", "s.started_at <= ?");
        assertThat(filters.forToolCall().where()).contains("t.started_at >= ?", "t.started_at <= ?");
        assertThat(filters.forStep().where()).contains("st.started_at >= ?", "st.started_at <= ?");
    }

    @Test
    void theClauseIsAssembledOnlyFromFixedFragments() {
        final FindingFilters filters =
                new FindingFilters(new InsightFilter(42L, null, null, "model'--", null, null));
        final FindingFilters.Sql sql = filters.forFinding();
        assertThat(sql.where()).isEqualTo("f.occurred_at >= ? and s.model = ?");
        assertThat(sql.params()).containsExactly(42L, "model'--");
        assertThat(sql.asWhere()).isEqualTo("where f.occurred_at >= ? and s.model = ?");
    }
}
