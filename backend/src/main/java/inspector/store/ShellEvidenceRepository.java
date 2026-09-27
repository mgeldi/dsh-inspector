package inspector.store;

import inspector.store.entity.EvidenceId;
import inspector.store.entity.ShellEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The {@code shell_evidence} table. Written by the indexer, read only through a finding's detail. */
public interface ShellEvidenceRepository extends JpaRepository<ShellEvidenceEntity, EvidenceId> {

    /** First, because its rows belong to finding ids that are about to disappear. */
    @Modifying
    @Query("delete from ShellEvidenceEntity e where e.id.findingId in "
            + "(select f.id from FindingEntity f where f.sessionId = :sessionId and f.sourceFile = :sourceFile)")
    int deleteStream(@Param("sessionId") String sessionId, @Param("sourceFile") String sourceFile);
}
