package inspector.store;

import inspector.query.IndexWideRead;
import inspector.query.Vocabulary;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The filter vocabulary, read from the index on every request (DESIGN.md §7).
 *
 * <p>The rail is fed by the values actually present in the data, so a re-indexed index shows its
 * new values without a restart, and a value that is in the index is always a legal filter. NULL
 * columns fold into the {@code unknown} bucket, which keeps the rail able to select them.
 *
 * <p><b>It is not cached, on a measurement.</b> The distinct reads together cost a 0.9 ms median
 * against a fully indexed corpus of 168 streams, where the whole overview request they precede
 * costs 18.6 ms (measured 2026-09-16). Caching a fifth of a request's time buys a stale-value
 * bug class the per-request read cannot have — a rail that silently disagrees with the index it
 * was rebuilt from.
 *
 * <p>What <em>was</em> worth cutting is what one of these lists costs to serialise: the session
 * ids are the only list that grows with the corpus, so they are read here for validation and
 * never sent (see {@code VocabularyOptions}).
 */
@Component
public class VocabularyService {

    private final EntityManager em;

    public VocabularyService(final EntityManager em) {
        this.em = em;
    }

    /**
     * The one deliberate index-wide read (DESIGN.md §7): the rail's options are the values
     * present in the index, and the rail keeps offering them even when a filter excludes their
     * rows — an option that disappeared with the filter would stop being clearable.
     */
    @IndexWideRead(reason = "the rail's facet vocabulary is deliberately index-wide (DESIGN.md §7): "
            + "an option is a value present in the index, and a filter must never make one "
            + "disappear from the rail")
    public Vocabulary vocabulary() {
        return new Vocabulary(
                sessionValues("schema"),
                sessionValues("model"),
                sessionValues("agentPreset"),
                sessionValues("harnessVersion"),
                distinct("select distinct coalesce(f.code, 'unknown') as v from FindingEntity f order by v"),
                distinct("select distinct f.detector as v from FindingEntity f order by v"),
                sessionValues("provider"),
                sessionValues("role"),
                distinct("select distinct s.id.sessionId as v from SessionEntity s order by v"));
    }

    /** The attribute name is one of this class's constants, never input. */
    private List<String> sessionValues(final String attribute) {
        return distinct("select distinct coalesce(s." + attribute + ", 'unknown') as v from SessionEntity s order by v");
    }

    private List<String> distinct(final String jpql) {
        return em.createQuery(jpql, String.class).getResultList();
    }
}
