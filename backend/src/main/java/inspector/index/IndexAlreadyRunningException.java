package inspector.index;

/**
 * A second index run was asked for while one was already in flight.
 *
 * <p>Interleaving is not a slower result, it is a wrong one. The transaction boundary is per
 * stream ({@code IndexWriter#writeStream}), not per run, so two runs delete and insert the same
 * six tables underneath each other with nothing between them holding the index still. After the
 * prune landed, it got worse rather than better: a run prunes to the streams <em>it</em>
 * scanned, so the second run's prune discards rows the first run is still writing, and the
 * index that comes out depends on which thread reached {@code pruneToWritten} last.
 *
 * <p>What this does not cover is a reader in the middle of a run: a rebuild is visible either
 * way, and stream-by-stream it is consistent. The answer to that is the asynchronous job
 * deferred in DESIGN.md §9, not a second lock.
 */
public final class IndexAlreadyRunningException extends RuntimeException {

    public IndexAlreadyRunningException() {
        super("an index run is already in progress; this one was refused rather than"
                + " interleaved with the run that is going on");
    }
}
