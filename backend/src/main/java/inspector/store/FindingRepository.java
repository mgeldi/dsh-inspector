package inspector.store;

import inspector.query.DetailRead;
import inspector.query.FindingsQuery;
import inspector.store.entity.FindingEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The findings table and the detail endpoint (DESIGN.md §7).
 *
 * <p>The page is a {@link JpaSpecificationExecutor} read: the filter becomes a specification in
 * {@link InsightPredicates}, and paging, the total count and the whitelisted sort are the
 * {@link Pageable}'s. It is declared here as {@link #page} rather than left to the inherited
 * {@code findAll}, so the filter contract is visible on the one method a caller uses — and
 * checkable by {@code FilterContractTest}.
 *
 * <p>The list read never touches evidence; {@link #findDetail} is the single read that fetches
 * the redacted excerpts, which is §4.1's "evidence stays home" as a query shape.
 */
public interface FindingRepository extends JpaRepository<FindingEntity, Long>,
        JpaSpecificationExecutor<FindingEntity> {

    /** One page of findings for the query, all pages counted. */
    default Page<FindingEntity> page(final FindingsQuery query, final Pageable pageable) {
        return findAll(InsightPredicates.findings(query), pageable);
    }

    /** The finding and its evidence rows, in shell order, in one query. */
    @Query("select f from FindingEntity f left join fetch f.evidence where f.id = :id")
    Optional<FindingEntity> findDetail(@Param("id") long id);

    /** The other findings of one stream inside a seq range — failures that sit next to each other. */
    @DetailRead(reason = "the neighbours of one open finding in its own stream, unfiltered")
    @Query("select f from FindingEntity f where f.sessionId = :sessionId and f.sourceFile = :sourceFile"
            + " and f.seq between :fromSeq and :toSeq order by f.seq, f.id")
    List<FindingEntity> inStreamBetween(@Param("sessionId") String sessionId,
                                        @Param("sourceFile") String sourceFile,
                                        @Param("fromSeq") int fromSeq, @Param("toSeq") int toSeq);

    @Modifying
    @Query("delete from FindingEntity f where f.sessionId = :sessionId and f.sourceFile = :sourceFile")
    int deleteStream(@Param("sessionId") String sessionId, @Param("sourceFile") String sourceFile);
}
