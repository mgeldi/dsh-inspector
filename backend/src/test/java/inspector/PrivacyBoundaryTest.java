package inspector;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.ingest.RedactedExcerpt;
import inspector.ingest.ShellEvidence;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * DESIGN.md §4.1/§11 as an executable check: the privacy boundary is a rule about types, and
 * this test walks the compiled main classes in {@code target/classes} and asserts the named
 * predicate — no type outside {@code inspector.ingest} declares a non-static, non-synthetic
 * field whose declared type is {@link RedactedExcerpt} or {@link ShellEvidence}, or whose name
 * is on the content list.
 *
 * <p>What it proves, and what it does not — said up front, because an unnamed "architecture
 * test" degrades into a name lint the moment someone asks what it matches:
 * <ul>
 *   <li>It stops a raw {@code String arguments} (or any field typed as one of the content
 *       types) creeping into a DTO, detector, repository or controller, <i>by name or by
 *       type</i>. The whitelist is by type, not by package placement: {@code ShellEvidence}
 *       is defined and stays inside ingest, so scanning by package would merely bless whatever
 *       ingest decides to export.</li>
 *   <li>It will <b>not</b> stop a field called {@code note} that someone stuffs a command
 *       into. A name-and-type lint sees what a field is called and what it is typed, never
 *       what it holds. §4.1's deeper property — that no downstream type has a field <i>for</i>
 *       chat content — is a design rule this test approximates from the outside, not proves.</li>
 * </ul>
 *
 * <p>No dependency is added to enforce this: a library to police ten lines of policy is
 * DESIGN.md §2.2's mistake all over again. Synthetic fields are compiler bookkeeping and
 * static fields are class-level constants, so both are skipped.
 */
final class PrivacyBoundaryTest {

    /** The §11 content list: the names a conversation-content field is known to wear. */
    private static final Set<String> FORBIDDEN_NAMES =
            Set.of("message", "content", "text", "arguments", "prompt", "command", "output");

    @Test
    void noTypeOutsideIngestHoldsConversationContent() throws Exception {
        final Path classes = Path.of("target/classes");
        assertThat(Files.isDirectory(classes))
                .as("target/classes must be present; run a compile before this test")
                .isTrue();
        final List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(classes)) {
            files.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString()
                            .replace('/', '.').replace(".class", ""))
                    .filter(name -> !name.startsWith("inspector.ingest."))
                    .sorted()
                    .forEach(name -> {
                        final Class<?> clazz;
                        try {
                            clazz = Class.forName(name);
                        } catch (final ClassNotFoundException e) {
                            violations.add(name + "#<unloadable class>");
                            return;
                        }
                        for (final Field field : clazz.getDeclaredFields()) {
                            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                                continue;
                            }
                            final boolean forbiddenType =
                                    RedactedExcerpt.class.isAssignableFrom(field.getType())
                                            || ShellEvidence.class.isAssignableFrom(field.getType());
                            final boolean forbiddenName =
                                    FORBIDDEN_NAMES.contains(field.getName().toLowerCase());
                            if (forbiddenType || forbiddenName) {
                                violations.add(name + "#" + field.getName());
                            }
                        }
                    });
        }
        assertThat(violations)
                .as("content fields outside inspector.ingest (type or name on the content list)")
                .isEmpty();
    }
}
