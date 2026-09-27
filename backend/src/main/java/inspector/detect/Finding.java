package inspector.detect;

import inspector.ingest.ShellEvidence;
import java.util.List;

/**
 * absolutePath is in memory only: the writer turns it into a project-relative path_hint before
 * anything reaches disk (§6). summary is generated from codes and seqs, never from user text.
 * {@code detail} is a second harness or provider constant under {@code code} — the specific
 * reason inside a generic one — and null wherever there is none.
 */
public record Finding(String detector, Plane plane, Category category, String code, String detail,
                      Double confidence, String absolutePath, Integer seq, Integer staleSeq,
                      Integer causeSeq, long occurredAt, String summary,
                      List<ShellEvidence> evidence) {
}
