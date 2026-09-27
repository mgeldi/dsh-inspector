package inspector.store;

import inspector.query.DetailRead;
import inspector.store.entity.ToolCallEntity;
import inspector.store.entity.ToolCallId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The {@code tool_call} table. */
public interface ToolCallRepository extends JpaRepository<ToolCallEntity, ToolCallId> {

    /**
     * The tool a finding's seq points at: null (empty) for seq-less findings and for seqs that are
     * llm events rather than tool calls. A primary-key lookup, so no filter can narrow it.
     */
    @Query("select t.name from ToolCallEntity t where t.id = :id")
    Optional<String> nameAt(@Param("id") ToolCallId id);

    /** The calls at or before {@code seq} in one stream, nearest first — the lead-up to a finding. */
    @DetailRead(reason = "the sequence around one open finding: its own stream, unfiltered, or the"
            + " sequence it shows would have holes the log does not have")
    @Query("select t from ToolCallEntity t where t.id.sessionId = :sessionId and t.id.sourceFile = :sourceFile"
            + " and t.id.seq <= :seq order by t.id.seq desc")
    List<ToolCallEntity> upTo(@Param("sessionId") String sessionId, @Param("sourceFile") String sourceFile,
                              @Param("seq") int seq, Pageable limit);

    /** The calls after {@code seq} in one stream, nearest first — what the model did next. */
    @DetailRead(reason = "the sequence around one open finding: its own stream, unfiltered")
    @Query("select t from ToolCallEntity t where t.id.sessionId = :sessionId and t.id.sourceFile = :sourceFile"
            + " and t.id.seq > :seq order by t.id.seq asc")
    List<ToolCallEntity> after(@Param("sessionId") String sessionId, @Param("sourceFile") String sourceFile,
                               @Param("seq") int seq, Pageable limit);

    /**
     * The last call that had started by {@code time}: the anchor for a finding that carries no
     * tool-call seq of its own (a fatal turn, a retry storm), placed by its event time instead.
     */
    @Query("select max(t.id.seq) from ToolCallEntity t where t.id.sessionId = :sessionId"
            + " and t.id.sourceFile = :sourceFile and t.startedAt <= :time")
    Optional<Integer> lastSeqStartedBy(@Param("sessionId") String sessionId,
                                       @Param("sourceFile") String sourceFile, @Param("time") long time);

    @Modifying
    @Query("delete from ToolCallEntity t where t.id.sessionId = :sessionId and t.id.sourceFile = :sourceFile")
    int deleteStream(@Param("sessionId") String sessionId, @Param("sourceFile") String sourceFile);
}
