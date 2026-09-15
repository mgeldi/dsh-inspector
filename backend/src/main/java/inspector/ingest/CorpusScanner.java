package inspector.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public final class CorpusScanner {

    public List<SessionSource> scan(final Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("corpus root is not a directory: " + root);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> Convention.isSessionFile(p.getFileName().toString()))
                    .map(CorpusScanner::toSource)
                    .sorted(Comparator.comparing(SessionSource::sessionId)
                            .thenComparing(SessionSource::sourceFile))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot scan corpus " + root, e);
        }
    }

    private static SessionSource toSource(final Path file) {
        final String fileName = file.getFileName().toString();
        final Path sessionDir = file.getParent();
        final Path slugDir = sessionDir == null ? null : sessionDir.getParent();
        return new SessionSource(file,
                sessionDir == null ? "unknown" : sessionDir.getFileName().toString(),
                slugDir == null ? "unknown" : slugDir.getFileName().toString(),
                Convention.fromFileName(fileName),
                fileName);
    }
}
