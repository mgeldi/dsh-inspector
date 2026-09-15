package inspector.api;

/**
 * The requested finding id does not exist in the index. Handled as an RFC
 * 9457 {@code application/problem+json} 404.
 */
public class FindingNotFoundException extends RuntimeException {

    private final long id;

    public FindingNotFoundException(final long id) {
        super("finding " + id + " does not exist");
        this.id = id;
    }

    public long id() {
        return id;
    }
}
