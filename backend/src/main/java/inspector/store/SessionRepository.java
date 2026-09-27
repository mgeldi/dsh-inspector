package inspector.store;

import inspector.query.IndexWideRead;
import inspector.store.entity.SessionEntity;
import inspector.store.entity.StreamId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The {@code session} table. Reads that answer a filtered question live in the aggregate repositories. */
public interface SessionRepository extends JpaRepository<SessionEntity, StreamId> {

    /** Distinct ids: a session held in both log conventions is two rows and one session. */
    @IndexWideRead(reason = "the startup gate asks whether the index holds anything at all; "
            + "a filter would turn 'empty index' into 'empty selection'")
    @Query("select count(distinct s.id.sessionId) from SessionEntity s")
    long countDistinctSessions();

    /** Every stream the index holds — what a prune compares against what a run wrote. */
    @IndexWideRead(reason = "a prune compares the whole index against the streams a run wrote")
    @Query("select s.id from SessionEntity s order by s.id.sessionId, s.id.sourceFile")
    List<StreamId> allStreams();

    @Modifying
    @Query("delete from SessionEntity s where s.id = :stream")
    int deleteStream(@Param("stream") StreamId stream);
}
