package inspector.store;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.TestStore;
import inspector.detect.Finding;
import inspector.detect.Plane;
import inspector.ingest.Convention;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.StepRecord;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import inspector.query.FindingsQuery;
import inspector.query.InsightFilter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageRequest;

/**
 * The one filter contract, against real rows: the rail's facets always narrow by the session a
 * row belongs to, and the time window is rooted at the time the query counts — a session's start,
 * a call's start, a step's start, a finding's event time. The fixture puts those on two different
 * days, so a window that is rooted at the wrong column answers visibly wrong here instead of
 * plausibly right on a corpus whose rows share a day.
 *
 * <p>Two streams: {@code s-sub} is a subagent on route {@code route-a} that started on day 0 and
 * did all of its work on day 10; {@code s-top} is an orchestrator with no recorded route. Every
 * path and name is invented.
 */
final class OverviewRootingTest {

    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_760_000_000_000L;
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path temp;

    private TestStore store;
    private OverviewRepository overview;

    @BeforeEach
    void index() {
        store = TestStore.open(temp.resolve("rooting.sqlite"));
        overview = store.overview();
        write("s-sub", 1, "route-a");
        write("s-top", 0, null);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void theWindowIsRootedAtTheTimeEachTileCounts() {
        final InsightFilter startDay = window(T0 - HOUR, T0 + HOUR);
        assertThat(overview.sessionCount(startDay)).as("both sessions started on day 0").isEqualTo(2);
        assertThat(overview.toolCallCount(startDay)).as("no call started on day 0").isZero();
        assertThat(overview.stepCount(startDay)).isZero();
        assertThat(overview.findingCount(startDay)).isZero();

        final InsightFilter workDay = window(T0 + 10 * DAY - HOUR, T0 + 10 * DAY + HOUR);
        assertThat(overview.sessionCount(workDay)).as("no session started on day 10").isZero();
        assertThat(overview.toolCallCount(workDay)).isEqualTo(2);
        assertThat(overview.stepCount(workDay)).isEqualTo(2);
        assertThat(overview.findingCount(workDay)).isEqualTo(2);
    }

    @Test
    void providerAndRoleNarrowByTheSessionARowBelongsTo() {
        final InsightFilter routeA = facets("route-a", null);
        assertThat(overview.sessionCount(routeA)).isEqualTo(1);
        assertThat(overview.toolCallCount(routeA)).isEqualTo(1);
        assertThat(overview.findingCount(routeA)).isEqualTo(1);

        final InsightFilter subagents = facets(null, "subagent");
        assertThat(overview.sessionCount(subagents)).isEqualTo(1);
        assertThat(overview.stepCount(subagents)).isEqualTo(1);
        assertThat(overview.findingCount(facets(null, "orchestrator"))).isEqualTo(1);
    }

    /**
     * {@code unknown} is what the rail calls a NULL, so the filter has to select the NULLs. An
     * {@code =} comparison matches none of them — the rail would offer an option that guarantees
     * an empty dashboard, which reads as "nothing is wrong here".
     */
    @Test
    void theUnknownBucketSelectsTheRowsWhoseValueIsNull() {
        assertThat(overview.sessionCount(facets("unknown", null))).isEqualTo(1);
        assertThat(overview.findingCount(facets("unknown", null))).isEqualTo(1);
    }

    @Test
    void theFindingsPageTakesTheSameContractPlusItsOwnAxis() {
        assertThat(store.findings().page(new FindingsQuery(facets("route-a", null), null, null, null, null),
                PageRequest.of(0, 20)).getContent())
                .singleElement().satisfies(f -> assertThat(f.getSessionId()).isEqualTo("s-sub"));
        assertThat(store.findings().page(new FindingsQuery(InsightFilter.none(), "GUARD", null, null, null),
                PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(store.findings().page(new FindingsQuery(InsightFilter.none(), null, null, "unknown", null),
                PageRequest.of(0, 20)).getTotalElements())
                .as("a codeless finding is what ?code=unknown selects").isEqualTo(1);
    }

    @Test
    void theVocabularyOffersProviderAndRoleWithNullsFoldedIntoUnknown() {
        assertThat(store.vocabulary().vocabulary().providers()).containsExactly("route-a", "unknown");
        assertThat(store.vocabulary().vocabulary().roles()).containsExactly("orchestrator", "subagent");
    }

    private static InsightFilter window(final long from, final long to) {
        return new InsightFilter(from, to, null, null, null, null, null, null);
    }

    private static InsightFilter facets(final String provider, final String role) {
        return new InsightFilter(null, null, null, null, null, null, provider, role);
    }

    /** One stream: started on day 0, one step, one call and one finding on day 10. */
    private void write(final String id, final int depth, final String provider) {
        final long work = T0 + 10 * DAY;
        final StreamFacts facts = new StreamFacts(
                new SessionRecord(id, "session.jsonl.zstd", "demo", "V0", T0, work + 5_000, "builder", depth,
                        "model-a", provider, 131_072, 0, "/home/dev/demo"),
                List.of(new StepRecord(0, 0, work, work + 1_000, 10, 10, null, null, "none")),
                List.of(new ToolCallRecord(0, 0, 3, "read", work + 100, work + 200, 100L, null,
                        "/home/dev/demo/a.txt", false)),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0L);
        // the orchestrator's finding carries no code, the subagent's does
        final Finding finding = new Finding("error-plane", Plane.MODEL_MISUSE, null,
                depth == 0 ? null : "FS_NOT_FOUND", null, null, "/home/dev/demo/a.txt", 3, null, null,
                work + 200, "an invented summary", List.of());
        store.writer().writeStream(new SessionSource(Path.of(id), id, "demo", Convention.V0, "session.jsonl.zstd"),
                facts, List.of(finding), "v", false, T0);
    }
}
