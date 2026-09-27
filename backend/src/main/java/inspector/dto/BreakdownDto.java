package inspector.dto;

/**
 * One kind of failure and how often it happened in the selection: the detector that fired, what
 * it concluded ({@code category}), the harness's code and the specific reason under it
 * ({@code detail}). Any of the last three may be null where the detector has nothing to say.
 */
public record BreakdownDto(String detector, String plane, String category, String code, String detail,
                           long count, Double perKCalls) {
}
