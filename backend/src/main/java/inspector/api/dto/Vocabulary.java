package inspector.api.dto;

import inspector.detect.Plane;

import java.util.Arrays;
import java.util.List;

/**
 * The values the filter rail may offer, read from the index itself (DESIGN.md §7).
 *
 * <p>Every list is built with {@code select distinct} and NULL columns are folded
 * into the {@value #UNKNOWN} bucket, so a value that is in the index is always a
 * legal filter value and vice versa. {@code plane} is a UI constant — the three
 * {@link Plane} names — not a roundtrip against the database.
 *
 * @param schemas        distinct {@code session.schema} values
 * @param models         distinct {@code session.model} values
 * @param presets        distinct {@code session.agent_preset} values
 * @param harnessVersions distinct {@code session.harness_version} values
 * @param codes          distinct {@code finding.code} values
 * @param detectors      distinct {@code finding.detector} ids
 * @param sessions       distinct {@code session.id} values
 */
public record Vocabulary(
        List<String> schemas,
        List<String> models,
        List<String> presets,
        List<String> harnessVersions,
        List<String> codes,
        List<String> detectors,
        List<String> sessions) {

    /** Bucket name for NULL columns; the rail must still be able to select them. */
    public static final String UNKNOWN = "unknown";

    /** The three planes are a UI constant (DESIGN.md §7), not a DB roundtrip. */
    public static final List<String> PLANES =
            Arrays.stream(Plane.values()).map(Enum::name).toList();

    /**
     * @param field the filter field name used in the query string
     * @return the allowed values for that field, for the 400 problem detail
     * @throws IllegalArgumentException if the field has no vocabulary
     */
    public List<String> allowedFor(final String field) {
        return switch (field) {
            case "schema" -> schemas;
            case "model" -> models;
            case "preset" -> presets;
            case "harnessVersion" -> harnessVersions;
            case "code" -> codes;
            case "detector" -> detectors;
            case "session" -> sessions;
            case "plane" -> PLANES;
            default -> throw new IllegalArgumentException("no vocabulary for field " + field);
        };
    }
}
