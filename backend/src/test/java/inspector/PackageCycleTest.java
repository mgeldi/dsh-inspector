package inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The direction of the backend's packages as an executable check.
 *
 * <p>{@code inspector.api} and {@code inspector.store} used to import each other: the store
 * reached into the web package for the filter contract and the wire shapes, the controllers
 * reached back for the repositories, and neither package could be read, moved or tested
 * alone. {@code inspector.query} is what both sides point at now — the shared filter contract
 * ({@code FindingFilters}, {@code InsightFilter}, {@code UnknownFilterValueException},
 * {@code Vocabulary}, {@code IndexWideRead}) — so the dependency has one direction and this
 * test is what keeps it that way. Without it the layer names are decoration again, and a
 * single {@code import} in the wrong file restores the cycle in one keystroke.
 *
 * <p>It reads the compiled {@code .class} files in {@code target/classes}, the same way
 * {@code FilterContractTest} does, and looks at the constant pool rather than the source.
 * That is deliberate: a class file records every type the code actually touches — imports,
 * fully-qualified uses, method signatures, field types, nested classes — so the rule cannot
 * be dodged by writing {@code inspector.api.Foo.bar()} inline, and it cannot fire on a
 * mention in a javadoc comment, which is where this codebase puts its reasoning.
 *
 * <p>Every failure names the offending class and the specific type it reached for. A rule
 * that fails with "unexpected error" is not a rule — the wording
 * {@code frontend/src/app/architecture.spec.ts} uses for its own rules.
 */
final class PackageCycleTest {

    private static final Path CLASSES = Path.of("target/classes");

    /** Types in the forbidden package(s), as they appear in a constant pool. */
    private static Pattern forbidden(final String... packages) {
        return Pattern.compile("inspector/(" + String.join("|", packages) + ")/[A-Za-z0-9_/$]+");
    }

    @Test
    void storeDoesNotDependOnTheWebPackage() {
        assertNoForbiddenReferences("inspector/store", forbidden("api"),
                "inspector.store must not depend on inspector.api. The filter contract and the"
                        + " index-wide-read marker live in inspector.query, the wire shapes in"
                        + " inspector.dto — move the shared type there rather than reaching"
                        + " upward into the controller layer");
    }

    @Test
    void queryDependsOnNeitherTheWebPackageNorTheStore() {
        assertNoForbiddenReferences("inspector/query", forbidden("api", "store"),
                "inspector.query is what api and store both point at, so it may not point back"
                        + " at either: a type in query that imports them puts the cycle right"
                        + " back where it was and the package stops meaning anything");
    }

    /**
     * One violation per (class, referenced type) pair, so a class that touches five types in
     * the forbidden package reports five lines and not one, and the list reads as the import
     * block it replaces.
     */
    private void assertNoForbiddenReferences(final String packageDir, final Pattern forbidden,
            final String whatToDoInstead) {
        assertThat(Files.isDirectory(CLASSES))
                .as("target/classes must be present; run a compile before this test")
                .isTrue();
        final List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(CLASSES)) {
            final List<Path> classes = files.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> CLASSES.relativize(p).startsWith(packageDir))
                    .sorted()
                    .toList();
            assertThat(classes)
                    .as("no compiled class was found under %s — the rule would pass by scanning"
                            + " nothing, which is not the same as passing", packageDir)
                    .isNotEmpty();
            for (final Path classFile : classes) {
                final String constantPool;
                try {
                    // ISO-8859-1 maps every byte to one char, leaving the length-prefixed
                    // UTF8 entries readable as the type names they are.
                    constantPool = new String(Files.readAllBytes(classFile),
                            StandardCharsets.ISO_8859_1);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
                final String from = CLASSES.relativize(classFile).toString().replace('\\', '/');
                final Matcher matcher = forbidden.matcher(constantPool);
                final Set<String> seen = new LinkedHashSet<>();
                while (matcher.find()) {
                    seen.add(matcher.group().replace('/', '.'));
                }
                for (final String target : seen) {
                    violations.add(from + " -> " + target);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(violations)
                .as(whatToDoInstead)
                .isEmpty();
    }
}
