package inspector.insight;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One answer, one snapshot of the index. A screen is several reads — the four tiles, the
 * vocabulary, the series; a cohort table's four aggregates; a page and its total; the judge's
 * cohort totals and per-session tables — and a re-index can land between any two of them, so an
 * answer assembled across it adds numbers that never coexisted (a tile total that disagrees with
 * the plane mix beside it). One read-only transaction per answer closes that: SQLite in WAL mode
 * gives a reader its snapshot for the length of the transaction, and the transaction manager binds
 * every read of the answer to that one connection.
 *
 * <p>The index run's own documentation used to accept the mix — "a rebuild is visible either way,
 * and stream-by-stream it is consistent" — and defer the answer to an asynchronous job. A snapshot
 * costs one transaction per request and removes the question now; {@code ReadSnapshotTest} checks
 * the SQLite behaviour it relies on, and its control shows the same reads without it do see the
 * write.
 */
@Component
public final class ReadSnapshot {

    private final TransactionOperations transactions;

    @Autowired
    public ReadSnapshot(final PlatformTransactionManager transactionManager) {
        final TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        this.transactions = template;
    }

    private ReadSnapshot(final TransactionOperations transactions) {
        this.transactions = transactions;
    }

    /** No transaction at all: for a service tested against stubs, where there is nothing to snapshot. */
    public static ReadSnapshot none() {
        return new ReadSnapshot(TransactionOperations.withoutTransaction());
    }

    public <T> T read(final Supplier<T> body) {
        return transactions.execute(status -> body.get());
    }
}
