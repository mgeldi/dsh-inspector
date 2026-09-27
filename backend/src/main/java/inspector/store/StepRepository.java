package inspector.store;

import inspector.store.entity.StepEntity;
import inspector.store.entity.StepId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The {@code step} table. */
public interface StepRepository extends JpaRepository<StepEntity, StepId> {

    @Modifying
    @Query("delete from StepEntity st where st.id.sessionId = :sessionId and st.id.sourceFile = :sourceFile")
    int deleteStream(@Param("sessionId") String sessionId, @Param("sourceFile") String sourceFile);
}
