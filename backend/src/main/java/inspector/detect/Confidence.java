package inspector.detect;

/**
 * A property of the evidence, never a guess: absolute path + mutating verb is HIGH,
 * basename + mutating verb is MEDIUM, everything else stays null (unattributed). DESIGN.md §5.3.
 */
public final class Confidence {

    public static final double HIGH = 0.9;
    public static final double MEDIUM = 0.6;

    private Confidence() {
    }
}
