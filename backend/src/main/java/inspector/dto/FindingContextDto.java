package inspector.dto;

import java.util.List;

/**
 * The structural sequence around one finding: the tool calls of its own stream before and after
 * it, and the other findings among them. What the model did, in order, without a word of what it
 * said — tool names, outcome codes, project-relative paths and timings only (DESIGN.md §4.1).
 * This is the "how did it happen" view: a failed edit followed by the same edit, a refusal
 * followed by a shell rewrite, a retry storm in the middle of a subagent's burst of calls.
 *
 * @param findingId the finding the sequence is centred on
 * @param anchorSeq the tool-call seq it is centred at; the finding's own seq, or for a finding with
 *                  none, the last call that had started by its event time; null when the stream
 *                  holds no call before it
 * @param calls     the calls in seq order
 * @param findings  the findings whose seq falls inside the window; the centred one is always
 *                  included, first when it has no seq of its own
 */
public record FindingContextDto(long findingId, Integer anchorSeq, List<Call> calls, List<Neighbour> findings) {

    /**
     * @param mark {@code finding} for the call the finding is at (only a finding with a tool-call
     *             seq has one), {@code stale} for its stale touch, {@code cause} for its cause;
     *             null for every other call
     */
    public record Call(long seq, String name, String errorCode, String plane, String pathHint,
                       Long durationMs, Long startedAt, String mark) {
    }

    public record Neighbour(long id, String detector, String code, String category, Long seq) {
    }
}
