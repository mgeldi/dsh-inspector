package inspector.ingest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    // A match starts only where a token starts (the lookbehind holds every character the path can
    // contain, ~ and + included), a directory segment is taken whole (possessive: it cannot give a
    // slash back), and a name is at most 255 characters, NAME_MAX. Unbounded and overlapping, the
    // runs backtracked quadratically on a long token and cubically when it held ~ or +: a 1 MB
    // base64 payload took 20 s, and the index waits on its workers in order.
    private static final Pattern RELATIVE =
            Pattern.compile("(?<![A-Za-z0-9_./~+-])(?:[A-Za-z0-9._~+-]{1,255}+/){0,32}[A-Za-z0-9._~+-]{0,254}[A-Za-z]"
                    + "[A-Za-z0-9._~+-]{0,254}\\.[A-Za-z][A-Za-z0-9]{0,5}");
    private static final Pattern VCS_RESTORE = Pattern.compile(
            "\\bgit\\s+(checkout|restore|stash|reset|clean|revert|pull|merge|rebase|switch)\\b");
    // The verb class is decided by the same union it always was: stamp-guard's attribution was
    // measured and validated against it, and it asks the broad question "could this have moved
    // an mtime". The redirect branch must not match `2>/dev/null` — the target is /dev, not a
    // file, and a stderr throwaway is the one redirect a reading command always carries.
    // Rev 5 added the content writes it had never known — `perl -i`, `sed`/`perl` with options
    // before the `-i` (`sed -e … -i f`), pathlib's `write_text`/`write_bytes` and node's
    // `createWriteStream` — which only ever adds causes a refusal can be attributed to.
    private static final Pattern MUTATING = Pattern.compile(
            "\\b(?:sed|perl)\\s+(?:[^\\s;&|]+\\s+){0,16}?-[A-Za-z]*i\\b|>>?\\s*(?:/(?!dev/)\\S|\\./\\S|~/\\S|[A-Za-z_][A-Za-z0-9_./-]*)"
                    + "|\\b(tee|truncate|cp|mv|rm|install|patch|touch|mkdir|chmod|chown|ln|dd)\\b"
                    + "|open\\([^)]{0,60}['\"][wax]|writeFileSync|writeFile\\s*\\(|writeText\\s*\\("
                    + "|\\.write_(?:text|bytes)\\s*\\(|createWriteStream"
                    + "|\\.write\\s*\\(|File\\.write|fwrite\\(");

    // The write kind asks a narrower question — "did this rewrite a file's content?" — and it feeds
    // a rate, so it is held to precision. The shell forms are read off the command's shell surface
    // only (heredoc bodies and quoted strings removed: `if n > limit` inside a python heredoc and
    // `x => y` inside a node -e string are code, not redirects), and a tool name only counts in
    // command position, so a path segment called `patch` or `dd` is not a patch or a dd.
    private static final String AT_COMMAND =
            "(?:^|[;&|(]\\s*|\\bsudo\\s+|\\bxargs\\s+(?:-\\S+\\s+){0,8}|-exec\\s+)";
    private static final Pattern IN_PLACE = Pattern.compile(
            AT_COMMAND + "(?:sed|perl)\\s+(?:[^\\s;&|]+\\s+){0,16}?-[A-Za-z]*i\\b", Pattern.MULTILINE);
    // A redirect target has to look like a path: absolute, ./ ~/ ../, a directory in it, or a name
    // with an extension. A bare identifier after `>` is, on the measured corpus, a comparison.
    private static final Pattern REDIRECT = Pattern.compile(
            "(?<![=<>&0-9-])>>?\\s*((?:/(?!dev/)|\\./|\\.\\./|~/)[^\\s'\"`;|&<>()]+"
                    + "|[A-Za-z0-9_.-]+/[^\\s'\"`;|&<>()]+|[A-Za-z0-9_-]+\\.[A-Za-z0-9]{1,8}\\b)");
    private static final Pattern TEE = Pattern.compile(AT_COMMAND + "tee\\b", Pattern.MULTILINE);
    private static final Pattern SCRIPT_WRITE = Pattern.compile(
            "open\\([^)]{0,60}['\"][wax]|writeFileSync|writeFile\\s*\\(|writeText\\s*\\("
                    + "|\\.write_(?:text|bytes)\\s*\\(|createWriteStream"
                    + "|(?<!stdout)(?<!stderr)\\.write\\s*\\(|File\\.write|fwrite\\(");
    // The file a script write names, when it names one as a literal: open('x', 'w'),
    // writeFileSync('x', …), createWriteStream('x'), Path('x').write_text(…). A write through a
    // variable names nothing the text can see, and then every path the script mentions is a
    // candidate — which is how a script that reads one tracked file and writes another would be
    // counted as editing the one it read.
    private static final Pattern SCRIPT_TARGET = Pattern.compile(
            "open\\(\\s*(['\"])([^'\"]+)\\1\\s*,\\s*['\"][wax]"
                    + "|(?:writeFileSync|writeFile|createWriteStream|appendFileSync)\\(\\s*(['\"])([^'\"]+)\\3"
                    + "|Path\\(\\s*(['\"])([^'\"]+)\\5\\s*\\)\\.write_(?:text|bytes)");
    private static final Pattern TRUNCATE = Pattern.compile(AT_COMMAND + "truncate\\b", Pattern.MULTILINE);
    private static final Pattern DD_OUTPUT = Pattern.compile("\\bof=([^\\s'\"`;|&<>()]+)");
    private static final Pattern SEGMENT_END = Pattern.compile("[;&|\\n]");
    /** A redirection operator standing alone, whose target or source is the next word. */
    private static final Pattern REDIRECTION_OPERATOR = Pattern.compile("[0-9]*(?:<<<|<|>>|>|>\\|)");
    /** sed's and perl's options that take the script, or a file holding it, as their argument. */
    private static final Pattern SCRIPT_OPTION = Pattern.compile("-[A-Za-z]*[ef]");
    /** A token that names a file: a directory in it, or a name with an extension. */
    private static final Pattern PATH_LIKE = Pattern.compile("(?:[~.]{0,2}/)?[A-Za-z0-9_.~+-]*(?:/[A-Za-z0-9_.~+-]+){1,64}|[A-Za-z0-9_~+-][A-Za-z0-9_.~+-]*\\.[A-Za-z0-9]{1,8}");
    private static final Pattern CONTENT_TOOL = Pattern.compile(
            AT_COMMAND + "(?:truncate|patch|dd)\\b", Pattern.MULTILINE);
    private static final Pattern FILE_OP = Pattern.compile(
            AT_COMMAND + "(?:cp|mv|rm|install|touch|mkdir|chmod|chown|ln)\\b", Pattern.MULTILINE);
    // `<<<` is a here-string, not a heredoc: it has no body to drop.
    private static final Pattern HEREDOC = Pattern.compile("(?<!<)<<-?(?!<)\\s*['\"]?([A-Za-z_][A-Za-z0-9_]*)['\"]?");
    private static final Pattern READ_ONLY = Pattern.compile(
            "\\b(cat|head|tail|wc|ls|cd|pwd|echo|printf|grep|rg|ag|find|fd|stat|file|du|df|less"
                    + "|more|jq|awk|cut|sort|uniq|diff|comm)\\b|\\bsed\\s+-n\\b"
                    + "|\\bgit\\s+(status|log|diff|show|blame|ls-files)\\b");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(token|secret|password|passwd|api[_-]?key|authorization|bearer)\\b\\s*[=:]?\\s*\\S+");
    private static final Pattern OPAQUE_RUN = Pattern.compile("[A-Za-z0-9+/_=-]{24,}");

    private final ObjectMapper mapper;

    /**
     * The only constructor, and deliberately the only one. While a no-arg constructor sat next to
     * this one, Spring used <em>that</em> for the bean: with no constructor annotated,
     * {@code AutowiredAnnotationBeanPostProcessor} falls back to "a primary/default constructor
     * (if present)". So the running application parsed shell commands with an {@code ObjectMapper}
     * this class had built itself, and the injected path was exercised only by tests. One
     * constructor leaves the container no choice; {@code ApplicationContextTest} asserts the bean
     * holds the context's mapper, and it fails against the two-constructor version.
     */
    public ShellAnalyzer(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ShellEvidence> analyze(final String argumentsJson, final int seq) {
        final String command = extractCommand(argumentsJson);
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        final VerbClass verbClass = classify(command);
        final String surface = surface(command);
        final WriteKind writeKind = verbClass == VerbClass.MUTATING ? writeKind(command, surface) : null;
        final Map<String, WriteKind> targets = writeKind == null || !writeKind.rewritesContent()
                ? Map.of() : writeTargets(command, surface);
        return Optional.of(new ShellEvidence(seq, referencedPaths(command), verbClass,
                new RedactedExcerpt(excerpt(command)), writeKind, targets));
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

    /**
     * How a mutating command writes: the first content form found, else a file operation, else
     * null — a command the broad verb class calls mutating but whose shell surface shows no write
     * the narrower question can stand behind. Content forms win over file operations because
     * {@code cp a b && sed -i … b} rewrites b. Script writes are looked for in the whole command,
     * since that is where they live; everything else on the surface.
     */
    private WriteKind writeKind(final String command, final String surface) {
        if (IN_PLACE.matcher(surface).find()) {
            return WriteKind.IN_PLACE;
        }
        if (REDIRECT.matcher(surface).find()) {
            return WriteKind.REDIRECT;
        }
        if (TEE.matcher(surface).find()) {
            return WriteKind.TEE;
        }
        if (SCRIPT_WRITE.matcher(command).find()) {
            return WriteKind.SCRIPT;
        }
        if (CONTENT_TOOL.matcher(surface).find()) {
            return WriteKind.CONTENT_TOOL;
        }
        if (FILE_OP.matcher(surface).find()) {
            return WriteKind.FILE_OP;
        }
        return null;
    }

    /**
     * What the shell itself would parse: the command with every heredoc body dropped (from the
     * line after {@code <<EOF} to the delimiter line) and every quoted string blanked, so code and
     * prose handed to an interpreter cannot pass for shell syntax.
     */
    static String surface(final String command) {
        final StringBuilder out = new StringBuilder();
        String delimiter = null;
        for (final String line : command.split("\n", -1)) {
            if (delimiter != null) {
                if (line.strip().equals(delimiter)) {
                    delimiter = null;
                }
                continue;
            }
            out.append(line).append('\n');
            final Matcher heredoc = HEREDOC.matcher(line);
            if (heredoc.find()) {
                delimiter = heredoc.group(1);
            }
        }
        return blankQuoted(out);
    }

    /**
     * Every quoted string replaced by {@code ''}: a double-quoted one up to its first unescaped
     * closing quote, a single-quoted one up to the next quote; an unclosed quote is left as text.
     * A scanner, not a regex: {@code "(?:[^"\\]|\\.)*"} recurses once per character in
     * java.util.regex, and a real command carrying a long quoted payload overflowed an ingest
     * worker's stack and failed the whole index — on some runs and not others, as the JIT decided
     * how large the frames were.
     */
    static String blankQuoted(final CharSequence text) {
        final StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            int close = -1;
            if (c == '"') {
                for (int j = i + 1; j < text.length(); j++) {
                    final char d = text.charAt(j);
                    if (d == '\\') {
                        j++;
                    } else if (d == '"') {
                        close = j;
                        break;
                    }
                }
            } else if (c == '\'') {
                for (int j = i + 1; j < text.length(); j++) {
                    if (text.charAt(j) == '\'') {
                        close = j;
                        break;
                    }
                }
            }
            if (close < 0) {
                out.append(c);
                i++;
            } else {
                out.append("''");
                i = close + 1;
            }
        }
        return out.toString();
    }

    /**
     * Every file the command's text names as written, whatever form wrote it: redirect targets, the
     * operands of {@code sed -i}/{@code perl -i}, {@code tee} and {@code truncate}, {@code dd}'s
     * {@code of=}, and the literal paths of script write calls. The union, because the write kind
     * names the first form found and a command can hold more than one — {@code node gen.js > a.txt
     * && sed -i … b.ts} writes both, and matching only the redirect's target missed the edit of
     * b.ts. A path the command merely runs or reads ({@code node x.js | tee log.txt}) is not here;
     * {@code patch} names its files inside the diff, so it contributes none.
     */
    private Map<String, WriteKind> writeTargets(final String command, final String surface) {
        // in writeKind's order of precedence, so a path two forms name gets the form that wins there
        final Map<String, WriteKind> targets = new LinkedHashMap<>();
        operands(surface, IN_PLACE, true).forEach(t -> targets.putIfAbsent(t, WriteKind.IN_PLACE));
        redirectTargets(surface).forEach(t -> targets.putIfAbsent(t, WriteKind.REDIRECT));
        operands(surface, TEE, false).forEach(t -> targets.putIfAbsent(t, WriteKind.TEE));
        scriptTargets(command).forEach(t -> targets.putIfAbsent(t, WriteKind.SCRIPT));
        operands(surface, TRUNCATE, false).forEach(t -> targets.putIfAbsent(t, WriteKind.CONTENT_TOOL));
        final Matcher dd = DD_OUTPUT.matcher(surface);
        while (dd.find()) {
            targets.putIfAbsent(dd.group(1), WriteKind.CONTENT_TOOL);
        }
        return targets.entrySet().stream().limit(MAX_PATHS)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The file operands after a command word, up to the end of its segment: tokens that look like a
     * path, not options, not redirections — and not the word a detached redirection takes
     * ({@code tee copy.txt < input.txt} reads input.txt). For {@code sed}/{@code perl} the argument
     * of {@code -e}/{@code -f} is a script or a script file, not an operand, and without either the
     * first operand is the script itself ({@code sed -i s/a/b/ f}); a quoted script was blanked by
     * the surface, and still counts as that first operand.
     */
    private static List<String> operands(final String surface, final Pattern command, final boolean scriptFirst) {
        final List<String> out = new java.util.ArrayList<>();
        final Matcher m = command.matcher(surface);
        while (m.find()) {
            final Matcher end = SEGMENT_END.matcher(surface);
            final int stop = end.find(m.end()) ? end.start() : surface.length();
            final String segment = surface.substring(m.end(), stop);
            final List<String> words = new java.util.ArrayList<>();
            boolean scriptOption = scriptFirst
                    && surface.substring(m.start(), m.end()).matches("(?s).*\\s-[A-Za-z]*[ef]\\s.*");
            final String[] tokens = segment.trim().split("\\s+");
            for (int t = 0; t < tokens.length; t++) {
                final String token = tokens[t];
                if (token.isEmpty()) {
                    continue;
                }
                if (REDIRECTION_OPERATOR.matcher(token).matches()) {
                    t++;                                  // its word is the redirection's, not an operand
                    continue;
                }
                if (token.startsWith("-")) {
                    if (scriptFirst && SCRIPT_OPTION.matcher(token).matches()) {
                        scriptOption = true;
                        t++;                              // the script, or the file it is read from
                    }
                    continue;
                }
                if (token.startsWith("<") || token.startsWith(">") || token.matches("[0-9]+[<>].*")) {
                    continue;
                }
                words.add(token);
            }
            final boolean inlineScript = scriptFirst && !scriptOption;
            for (int i = inlineScript ? 1 : 0; i < words.size(); i++) {
                final String word = words.get(i);
                if (!word.equals("''") && PATH_LIKE.matcher(word).matches()) {
                    out.add(word);
                }
            }
        }
        return out;
    }

    /** The literal paths a script's write calls name; empty when it writes through variables only. */
    private Set<String> scriptTargets(final String command) {
        final Set<String> targets = new LinkedHashSet<>();
        final Matcher m = SCRIPT_TARGET.matcher(command);
        while (m.find() && targets.size() < MAX_PATHS) {
            for (final int group : new int[] {2, 4, 6}) {
                if (m.group(group) != null) {
                    targets.add(m.group(group));
                }
            }
        }
        return Set.copyOf(targets);
    }

    /** The paths the redirects of a command's shell surface write to, in order of appearance. */
    private Set<String> redirectTargets(final String surface) {
        final Set<String> targets = new LinkedHashSet<>();
        final Matcher m = REDIRECT.matcher(surface);
        while (m.find() && targets.size() < MAX_PATHS) {
            targets.add(m.group(1));
        }
        return Set.copyOf(targets);
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
            // Only device files are excluded — a path *under* /dev (null, stdout, urandom),
            // never any path that merely contains the segment: /home/dev/demo/app.java is a
            // real file and dropping it would blind the attribution that depends on it.
            if (!candidate.startsWith("/dev/") && !candidate.startsWith("/proc/")) {
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
