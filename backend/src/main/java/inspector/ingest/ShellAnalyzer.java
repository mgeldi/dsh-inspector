package inspector.ingest;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a shell command into an unmatched, classified observation. This is the one place raw
 * command text is examined, and it is inside ingest by design (DESIGN.md §4.1).
 */
@Component
public final class ShellAnalyzer {

    private static final int EXCERPT_LIMIT = 160;
    private static final int MAX_PATHS = 20;

    private static final Pattern ABSOLUTE =
            Pattern.compile("/[A-Za-z0-9._~+-](?:[^\\s'\"`;|&<>()]*[A-Za-z0-9._~+-])?");
    private static final Pattern RELATIVE =
            Pattern.compile("(?<![A-Za-z0-9_./-])(?:[A-Za-z0-9._~+-]+/)*[A-Za-z0-9._~+-]*[A-Za-z]"
                    + "[A-Za-z0-9._~+-]*\\.[A-Za-z][A-Za-z0-9]{0,5}");
    private static final Pattern VCS_RESTORE = Pattern.compile(
            "\\bgit\\s+(checkout|restore|stash|reset|clean|revert|pull|merge|rebase|switch)\\b");
    // The redirect branch must not match `2>/dev/null` — the target is /dev, not a file, and a
    // stderr throwaway is the one redirect a reading command always carries.
    private static final Pattern MUTATING = Pattern.compile(
            "\\bsed\\s+-[A-Za-z]*i\\b|>>?\\s*(?:/(?!dev/)\\S|\\./\\S|~/\\S|[A-Za-z_][A-Za-z0-9_./-]*)"
                    + "|\\b(tee|truncate|cp|mv|rm|install|patch|touch|mkdir|chmod|chown|ln|dd)\\b"
                    + "|open\\([^)]{0,60}['\"][wax]|writeFileSync|writeFile\\s*\\(|writeText\\s*\\("
                    + "|\\.write\\s*\\(|File\\.write|fwrite\\(");
    private static final Pattern READ_ONLY = Pattern.compile(
            "\\b(cat|head|tail|wc|ls|cd|pwd|echo|printf|grep|rg|ag|find|fd|stat|file|du|df|less"
                    + "|more|jq|awk|cut|sort|uniq|diff|comm)\\b|\\bsed\\s+-n\\b"
                    + "|\\bgit\\s+(status|log|diff|show|blame|ls-files)\\b");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(token|secret|password|passwd|api[_-]?key|authorization|bearer)\\b\\s*[=:]?\\s*\\S+");
    private static final Pattern OPAQUE_RUN = Pattern.compile("[A-Za-z0-9+/_=-]{24,}");

    private final ObjectMapper mapper;

    public ShellAnalyzer() {
        this(new ObjectMapper());
    }

    public ShellAnalyzer(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ShellEvidence> analyze(final String argumentsJson, final int seq) {
        final String command = extractCommand(argumentsJson);
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ShellEvidence(seq, referencedPaths(command),
                classify(command), new RedactedExcerpt(excerpt(command))));
    }

    /** §3.6: arguments is a *string* containing JSON, and it can be unparseable. */
    private String extractCommand(final String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(argumentsJson).at("/command").asText(null);
        } catch (JacksonException malformed) {
            return null;
        }
    }

    private VerbClass classify(final String command) {
        if (VCS_RESTORE.matcher(command).find()) {
            return VerbClass.VCS_RESTORE;
        }
        if (MUTATING.matcher(command).find()) {
            return VerbClass.MUTATING;
        }
        if (READ_ONLY.matcher(command).find()) {
            return VerbClass.READ_ONLY;
        }
        return VerbClass.OTHER;                       // includes bare script execution
    }

    private Set<String> referencedPaths(final String command) {
        final Set<String> found = new LinkedHashSet<>();
        collect(ABSOLUTE.matcher(command), found);
        collect(RELATIVE.matcher(command), found);
        return Set.copyOf(found);
    }

    private void collect(final Matcher matcher, final Set<String> sink) {
        while (matcher.find() && sink.size() < MAX_PATHS) {
            final String candidate = matcher.group();
            if (!candidate.contains("/dev/") && !candidate.startsWith("/proc/")) {
                sink.add(candidate);
            }
        }
    }

    private String excerpt(final String command) {
        final String firstLine = command.lines().findFirst().orElse(command);
        String masked = CREDENTIAL.matcher(firstLine).replaceAll("$1=[REDACTED]");
        masked = OPAQUE_RUN.matcher(masked).replaceAll("[REDACTED]");
        if (masked.length() > EXCERPT_LIMIT) {
            masked = masked.substring(0, EXCERPT_LIMIT) + "…";
        }
        return masked;
    }
}
