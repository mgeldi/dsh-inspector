package inspector;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.query.FindingFilters;
import inspector.query.IndexWideRead;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * DESIGN.md §7 as an executable check: the filter contract is a rule about store reads, and
 * this test walks the compiled main classes in {@code target/classes} and asserts the named
 * predicate — every public method of a top-level {@code inspector.store.*Repository} class
 * or of {@code VocabularyService} that returns query results either takes a
 * {@link FindingFilters} (or its {@code Sql}) or carries an {@link IndexWideRead} whose
 * reason is not blank.
 *
 * <p>It exists because the one filter-contract bug this app shipped was green end to end: the
 * controller never declared the rail's parameters, the repository never had a WHERE, and no
 * test asked whether a declared aggregate honoured the filter. From now on the missing
 * parameter is a build failure, not a code-review hope.
 *
 * <p>What it proves, and what it does not — said up front, because an unnamed "architecture
 * test" degrades into a name lint the moment someone asks what it matches:
 * <ul>
 *   <li>Writes are out of scope on purpose: the contract is about what a read <i>answers</i>,
 *       and {@code IndexWriter} only ever creates the index. No screen claims a filtered
 *       selection of a write, so a write cannot misreport a population. {@code PathHints} is a
 *       string helper on the write side and the nested records are row shapes, not reads; all
 *       three stay outside the scope by name, the same way the privacy lint scopes by type.</li>
 *   <li>{@code void} methods and {@code Optional} single-row lookups are out of scope: the
 *       contract governs a population a filter narrows, and a primary-key fetch returns at
 *       most one row, which no filter can narrow. The next list, count or aggregate is what
 *       this lint is for.</li>
 *   <li>An exemption is a claim, not a waiver: {@link IndexWideRead} carries the reason next
 *       to the method, and a blank reason fails the test — a blank excuse is not an excuse.
 *       The one exemption today is {@code VocabularyService.vocabulary()} (DESIGN.md §7):
 *       the rail's options are the values present in the index, and a filter must never make
 *       one disappear.</li>
 *   <li>It will not stop a repository that takes the filters and forgets to apply them to
 *       one of four aggregates. A signature lint sees the parameter, never the SQL.</li>
 * </ul>
 *
 * <p>No dependency is added to enforce this: a library to police ten lines of policy is
 * DESIGN.md §2.2's mistake all over again. Synthetic methods are compiler bookkeeping, so
 * they are skipped, and a class the test cannot load is a violation, not a silent pass.
 */
final class FilterContractTest {

    @Test
    void storeReadsDeclareTheSharedFilterContract() throws Exception {
        final Path classes = Path.of("target/classes");
        assertThat(Files.isDirectory(classes))
                .as("target/classes must be present; run a compile before this test")
                .isTrue();
        final List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(classes)) {
            files.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString()
                            .replace('/', '.').replace(".class", ""))
                    .filter(FilterContractTest::inScope)
                    .sorted()
                    .forEach(name -> {
                        final Class<?> clazz;
                        try {
                            clazz = Class.forName(name);
                        } catch (final ClassNotFoundException e) {
                            violations.add(name + "#<unloadable class>");
                            return;
                        }
                        for (final Method method : clazz.getDeclaredMethods()) {
                            if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())
                                    || !returnsQueryResults(method)) {
                                continue;
                            }
                            final IndexWideRead exemption = method.getAnnotation(IndexWideRead.class);
                            if (exemption != null) {
                                if (exemption.reason().isBlank()) {
                                    violations.add(name + "#" + method.getName()
                                            + " — @IndexWideRead reason is blank; a blank excuse is not an"
                                            + " excuse, state why the read is index-wide");
                                }
                            } else if (!declaresFilterContract(method)) {
                                violations.add(name + "#" + method.getName()
                                        + " — declare the shared filter contract of §7: take a FindingFilters"
                                        + " parameter, or mark the read @IndexWideRead with a reason if it is"
                                        + " deliberately index-wide");
                            }
                        }
                    });
        }
        assertThat(violations)
                .as("store reads that ignore the shared filter contract (DESIGN.md §7: a FindingFilters"
                        + " parameter or a reasoned @IndexWideRead)")
                .isEmpty();
    }

    /** Top-level classes only: a {@code *Repository} in {@code inspector.store}, plus {@code VocabularyService}. */
    private static boolean inScope(final String name) {
        if (!name.startsWith("inspector.store.") || name.contains("$")) {
            return false;
        }
        final String simple = name.substring("inspector.store.".length());
        return simple.endsWith("Repository") || simple.equals("VocabularyService");
    }

    /**
     * A row list, a DTO, a count — anything the read hands to the screen. {@code void}
     * methods return no result, and an {@code Optional} single-row lookup returns at most
     * one row, which no filter can narrow.
     */
    private static boolean returnsQueryResults(final Method method) {
        final Class<?> type = method.getReturnType();
        return type != void.class && !Optional.class.isAssignableFrom(type);
    }

    /** At least one parameter assignable to {@code FindingFilters} or {@code FindingFilters.Sql}. */
    private static boolean declaresFilterContract(final Method method) {
        for (final Class<?> type : method.getParameterTypes()) {
            if (FindingFilters.class.isAssignableFrom(type)
                    || FindingFilters.Sql.class.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }
}
