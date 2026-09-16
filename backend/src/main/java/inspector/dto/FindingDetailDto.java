package inspector.dto;

import java.util.List;

/**
 * The single endpoint that may return evidence text (DESIGN.md §4.1):
 * the finding, the tool call it points at (if any), and the redacted shell
 * excerpts in shell order.
 *
 * <p>{@code tool} is the {@code tool_call.name} of the call the finding's
 * {@code seq} points at — null for findings without a seq (fatal turns,
 * retry storms) and for seqs that are llm calls rather than tool calls.
 */
public record FindingDetailDto(FindingDto finding, String tool, List<FindingDto.Evidence> evidence) {
}
