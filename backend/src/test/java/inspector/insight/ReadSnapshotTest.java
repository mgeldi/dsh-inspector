package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.TestStore;
import inspector.detect.Finding;
import inspector.detect.Plane;
import inspector.ingest.Convention;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.StreamFacts;
import inspector.query.InsightFilter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The claim {@link ReadSnapshot} rests on, tested against SQLite rather than assumed: a read
 * transaction keeps seeing the index as it was when it began, while another connection writes and
 * commits a stream in the middle of it. Every name here is invented.
 */
final class ReadSnapshotTest {

    @TempDir
    Path temp;

    @Test
    void aWriteCommittedDuringTheReadIsInvisibleToItAndVisibleAfter() {
        try (TestStore store = TestStore.open(temp.resolve("snapshot.sqlite"))) {
            write(store, "s-1");
            final ReadSnapshot snapshot = new ReadSnapshot(store.transactions());

            final long[] seen = snapshot.read(() -> {
                final long before = store.overview().findingCount(InsightFilter.none());
                // another connection, another thread: the index run writing the next stream
                CompletableFuture.runAsync(() -> write(store, "s-2")).join();
                final long after = store.overview().findingCount(InsightFilter.none());
                return new long[] {before, after};
            });

            assertThat(seen).as("one answer, one index").containsExactly(1, 1);
            assertThat(store.overview().findingCount(InsightFilter.none()))
                    .as("the write did commit").isEqualTo(2);
        }
    }

    /** The control: the same two reads outside a snapshot do see the write — the test above is not vacuous. */
    @Test
    void withoutTheSnapshotTheSecondReadSeesTheWrite() {
        try (TestStore store = TestStore.open(temp.resolve("control.sqlite"))) {
            write(store, "s-1");
            final long[] seen = ReadSnapshot.none().read(() -> {
                final long before = store.overview().findingCount(InsightFilter.none());
                CompletableFuture.runAsync(() -> write(store, "s-2")).join();
                return new long[] {before, store.overview().findingCount(InsightFilter.none())};
            });
            assertThat(seen).containsExactly(1, 2);
        }
    }

    private static void write(final TestStore store, final String id) {
        final StreamFacts facts = new StreamFacts(new SessionRecord(id, "session.jsonl.zstd", "demo", "V0",
                1_760_000_000_000L, null, null, 0, null, null, null, 0, "/home/dev/demo"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L);
        final Finding finding = new Finding("error-plane", Plane.MODEL_MISUSE, null, "FS_NOT_FOUND", null, null,
                null, 1, null, null, 1_760_000_000_000L, "an invented summary", List.of());
        store.writer().writeStream(new SessionSource(Path.of(id), id, "demo", Convention.V0, "session.jsonl.zstd"),
                facts, List.of(finding), "v", false, 1L);
    }
}
