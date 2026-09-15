package inspector.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CorpusScannerTest {

    @TempDir
    Path root;

    @Test
    void findsBothConventionsAndSkipsLockFiles() throws IOException {
        touch("proj-a/session-1/session.jsonl.zstd");
        touch("proj-a/session-1/session.lock");
        touch("proj-b/session-2/session.v3.jsonl.zstd");
        touch("proj-b/session-2/notes.txt");

        final List<SessionSource> found = new CorpusScanner().scan(root);

        assertThat(found).extracting(SessionSource::sessionId).containsExactly("session-1", "session-2");
        assertThat(found).extracting(SessionSource::convention)
                .containsExactly(Convention.V0, Convention.V3);
        assertThat(found).extracting(SessionSource::projectSlug).containsExactly("proj-a", "proj-b");
    }

    @Test
    void oneDirectoryHoldingBothFilesYieldsTwoStreamsForOneSession() throws IOException {
        touch("proj-a/session-1/session.jsonl.zstd");
        touch("proj-a/session-1/session.v3.jsonl.zstd");

        final List<SessionSource> found = new CorpusScanner().scan(root);

        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(s -> assertThat(s.sessionId()).isEqualTo("session-1"));
        assertThat(found).extracting(SessionSource::sourceFile)
                .containsExactly("session.jsonl.zstd", "session.v3.jsonl.zstd");
    }

    @Test
    void aMissingRootFailsLoudlyRatherThanReportingZeroSessions() {
        // §7: an empty dashboard that means "no data found" is the most dangerous wrong answer
        assertThatThrownBy(() -> new CorpusScanner().scan(root.resolve("nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corpus root");
    }

    private void touch(final String relative) throws IOException {
        final Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "");
    }
}
