package inspector.dto;

import java.util.List;

/**
 * The filter vocabulary as it crosses the wire (DESIGN.md §7): the lists a control can offer.
 *
 * <p>Every list here is bounded by the thing it enumerates — conventions, models, presets,
 * harness versions, error codes, detector ids — so the payload a dashboard pays for its rail
 * stays roughly the same size no matter how much has been indexed.
 *
 * <p>The seventh list of {@code inspector.query.Vocabulary}, every session id in the index, is
 * deliberately absent. It is the only list whose length <em>is</em> the size of the corpus, so
 * shipping it meant paying for it on every dashboard load forever: measured on the real corpus
 * copy, 165 ids were 6,812 of an 8,997-byte {@code /api/overview} response (T11 Log), and the
 * number grows with every session ever indexed while no screen on earth reads it — the rail has
 * four facets and the per-row session filter is a documented seam the UI never writes
 * ({@code insights.store.ts} says a filter nothing writes is residue, not a filter).
 *
 * <p>Removing it from the payload does not remove the filter. The server still reads the ids —
 * the read is index-wide and says why — and {@code ?session=} is still validated against them,
 * so a session that is not in the index is still a 400 that lists the ones that are.
 */
public record VocabularyOptions(
        List<String> schemas,
        List<String> models,
        List<String> presets,
        List<String> harnessVersions,
        List<String> codes,
        List<String> detectors) {
}
