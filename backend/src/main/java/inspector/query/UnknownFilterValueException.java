package inspector.query;

import java.util.List;

/**
 * A filter value that is not in the index vocabulary. Handled as an RFC 9457
 * {@code application/problem+json} 400 that carries the allowed set, so the
 * rail can show what the user could have picked (DESIGN.md §7).
 *
 * <p>The message names the filter and the value and stops there. The allowed set travels as the
 * problem's {@code allowed} property — once. Writing it into the message as well asked a client
 * to read 165 session ids twice to learn one thing: on a corpus with 165 sessions,
 * {@code ?session=<unknown>} came back 13,726 bytes, 6,727 characters of which were the list
 * repeated inside {@code detail}.
 */
public class UnknownFilterValueException extends RuntimeException {

    private final String filter;
    private final String value;
    private final List<String> allowed;

    public UnknownFilterValueException(final String filter, final String value, final List<String> allowed) {
        super("filter '" + filter + "' value '" + value + "' is not in the index vocabulary");
        this.filter = filter;
        this.value = value;
        this.allowed = List.copyOf(allowed);
    }

    public String filter() {
        return filter;
    }

    public String value() {
        return value;
    }

    public List<String> allowed() {
        return allowed;
    }
}
