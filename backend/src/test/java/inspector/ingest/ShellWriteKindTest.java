package inspector.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * How a mutating command writes, as far as its text says — the split the shell-edit rate is
 * built on. Every command and path is invented.
 */
final class ShellWriteKindTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ShellAnalyzer analyzer = new ShellAnalyzer(mapper);

    private ShellEvidence analyze(final String command) {
        return analyzer.analyze(mapper.writeValueAsString(Map.of("command", command)), 1).orElseThrow();
    }

    @Test
    void eachWriteFormIsNamed() {
        assertThat(analyze("sed -i 's/a/b/' /home/dev/demo/App.java").writeKind()).isEqualTo(WriteKind.IN_PLACE);
        assertThat(analyze("perl -pi -e 's/a/b/' notes.md").writeKind()).isEqualTo(WriteKind.IN_PLACE);
        assertThat(analyze("cat > /home/dev/demo/notes.md <<'EOF'\nx\nEOF").writeKind()).isEqualTo(WriteKind.REDIRECT);
        assertThat(analyze("echo done | tee build.log").writeKind()).isEqualTo(WriteKind.TEE);
        assertThat(analyze("python3 -c \"open('out.txt','w').write('x')\"").writeKind()).isEqualTo(WriteKind.SCRIPT);
        assertThat(analyze("python3 - <<'EOF'\nPath('a.txt').write_text('x')\nEOF").writeKind())
                .as("pathlib's write_text — the heredoc also redirects nothing, so this is the script form")
                .isEqualTo(WriteKind.SCRIPT);
        assertThat(analyze("patch -p1 < fix.diff").writeKind()).isEqualTo(WriteKind.CONTENT_TOOL);
        assertThat(analyze("cp a.txt b.txt").writeKind()).isEqualTo(WriteKind.FILE_OP);
        assertThat(analyze("chmod +x run.sh").writeKind()).isEqualTo(WriteKind.FILE_OP);
    }

    @Test
    void aContentRewriteWinsOverAFileOperationInTheSameCommand() {
        assertThat(analyze("cp a.txt b.txt && sed -i 's/x/y/' b.txt").writeKind()).isEqualTo(WriteKind.IN_PLACE);
    }

    @Test
    void aNonMutatingCommandHasNoWriteKind() {
        final ShellEvidence read = analyze("cat /home/dev/demo/App.java 2>/dev/null | wc -l");
        assertThat(read.verbClass()).isEqualTo(VerbClass.READ_ONLY);
        assertThat(read.writeKind()).isNull();
        assertThat(read.writeTargets()).isEmpty();
    }

    @Test
    void aRedirectNamesItsTargetsAndOnlyThem() {
        final ShellEvidence redirect = analyze("cat /home/dev/demo/App.java > /home/dev/demo/copy.txt");
        assertThat(redirect.writeTargets()).containsExactly("/home/dev/demo/copy.txt");
        assertThat(redirect.referencedPaths()).contains("/home/dev/demo/App.java", "/home/dev/demo/copy.txt");
    }

    @Test
    void aStderrThrowawayIsNotAWrite() {
        assertThat(analyze("grep -r needle src 2>/dev/null").writeKind()).isNull();
    }

    /**
     * Code handed to an interpreter is not shell syntax. An arrow function and a comparison used to
     * read as redirects to files named {@code y} and {@code limit}, which classified a script
     * write as a redirect to a file that does not exist and hid it from the shell-edit rate.
     */
    @Test
    void codeInsideQuotesOrAHeredocIsNotARedirect() {
        final ShellEvidence arrow = analyze("node -e \"[1,2].map(x => x * 2); require('fs').writeFileSync('out.json', '1')\"");
        assertThat(arrow.writeKind()).isEqualTo(WriteKind.SCRIPT);
        assertThat(arrow.writeTargets()).as("the file the write names, not the arrow's operand")
                .containsExactly("out.json");

        final ShellEvidence heredoc = analyze("python3 - <<'EOF'\nif n > limit:\n    open('a.txt','w').write('x')\nEOF");
        assertThat(heredoc.writeKind()).isEqualTo(WriteKind.SCRIPT);

        final ShellEvidence after = analyze("python3 - <<'EOF'\nprint(1 > 0)\nEOF\necho done > build.log");
        assertThat(after.writeKind()).as("a real redirect after the heredoc body still counts").isEqualTo(WriteKind.REDIRECT);
        assertThat(after.writeTargets()).containsExactly("build.log");
    }

    @Test
    void aStderrRedirectAndABareIdentifierAreNotFileWrites() {
        assertThat(analyze("make 2> errors.log").writeKind()).isNull();
        assertThat(analyze("awk '{ if ($1 > max) max = $1 }' data.csv").writeKind()).isNull();
    }

    @Test
    void aToolNameCountsOnlyInCommandPosition() {
        assertThat(analyze("git diff > /home/dev/demo/fix.patch").writeKind())
                .as("the redirect, not a patch").isEqualTo(WriteKind.REDIRECT);
        assertThat(analyze("cat /home/dev/demo/dd/notes.patch").writeKind()).isNull();
        assertThat(analyze("find src -name '*.ts' -exec sed -i 's/a/b/' {} +").writeKind())
                .isEqualTo(WriteKind.IN_PLACE);
        assertThat(analyze("cd src && patch -p1 < fix.diff").writeKind()).isEqualTo(WriteKind.CONTENT_TOOL);
    }

    /** A script write that names its file literally is matched on that file, not on what it read. */
    @Test
    void aScriptWriteNamesItsLiteralTarget() {
        assertThat(analyze("python3 -c \"d=open('/home/dev/demo/in.txt').read(); open('/home/dev/demo/out.txt','w').write(d)\"")
                .writeTargets()).containsExactly("/home/dev/demo/out.txt");
        assertThat(analyze("node -e \"require('fs').writeFileSync('dist/app.js', src)\"").writeTargets())
                .containsExactly("dist/app.js");
        assertThat(analyze("python3 - <<'EOF'\nPath('notes.md').write_text(x)\nEOF").writeTargets())
                .containsExactly("notes.md");
        assertThat(analyze("python3 -c \"p='a.txt'; open(p,'w').write('x')\"").writeTargets())
                .as("a write through a variable names nothing").isEmpty();
    }

    /**
     * Every form names its own operands, and only those. A path the command runs or reads is not a
     * write target: {@code node x.js | tee log.txt} writes log.txt, and counting x.js as written
     * made a HIGH edit of a file the command only executed.
     */
    @Test
    void eachFormContributesTheFilesItWritesAndOnlyThose() {
        assertThat(analyze("node gen/x.js | tee build.log").writeTargets()).containsExactly("build.log");
        assertThat(analyze("sed -i 's/a/b/' src/a.ts src/b.ts").writeTargets())
                .containsExactlyInAnyOrder("src/a.ts", "src/b.ts");
        assertThat(analyze("sed -e 's/a/b/' -i src/a.ts").writeTargets())
                .as("-e before -i: every operand is a file").containsExactly("src/a.ts");
        assertThat(analyze("sed -i s/a/b/ src/a.ts").writeTargets())
                .as("an unquoted inline script is the first operand, not a file").containsExactly("src/a.ts");
        assertThat(analyze("truncate -s 0 logs/out.log").writeTargets()).containsExactly("logs/out.log");
        assertThat(analyze("dd if=/dev/zero of=disk.img bs=1M count=1").writeTargets()).containsExactly("disk.img");
    }

    /**
     * What a command reads is not what it writes: the word after a detached {@code <} is an input,
     * and the argument of sed's {@code -f} is the script file.
     */
    @Test
    void anInputAndAScriptFileAreNotWriteTargets() {
        assertThat(analyze("tee /tmp/copy.txt < src/input.txt").writeTargets()).containsExactly("/tmp/copy.txt");
        assertThat(analyze("sed -i -f tools/fix.sed src/other.js").writeTargets()).containsExactly("src/other.js");
        assertThat(analyze("sed -i -e 's/a/b/' src/a.ts").writeTargets()).containsExactly("src/a.ts");
        assertThat(analyze("perl -pi -e 's/a/b/' notes.md").writeTargets()).containsExactly("notes.md");
        assertThat(analyze("sed -i 's/a/b/' src/a.ts 2> err.log").targetKinds())
                .containsEntry("src/a.ts", WriteKind.IN_PLACE).doesNotContainKey("2>");
    }

    /**
     * Each target carries the form that named it. A script that writes a tracked file while its
     * output is redirected to a log is a script write of that file, whatever the command's first form.
     */
    @Test
    void eachTargetKeepsTheFormThatWroteIt() {
        final ShellEvidence mixed = analyze("python3 - <<'EOF' > /tmp/run.log\nopen('src/app.py', 'w').write(x)\nEOF");
        assertThat(mixed.writeKind()).isEqualTo(WriteKind.REDIRECT);
        assertThat(mixed.targetKinds()).containsEntry("/tmp/run.log", WriteKind.REDIRECT)
                .containsEntry("src/app.py", WriteKind.SCRIPT);
    }

    /** A command can write in more than one form; the targets are the union, whatever the kind says. */
    @Test
    void aCommandWithTwoWriteFormsNamesBothTargets() {
        final ShellEvidence both = analyze("node gen.js > out/a.txt && sed -i 's/x/y/' src/b.ts");
        assertThat(both.writeKind()).isEqualTo(WriteKind.IN_PLACE);
        assertThat(both.writeTargets()).containsExactlyInAnyOrder("out/a.txt", "src/b.ts");
    }

    @Test
    void writingToStdoutIsNotWritingAFile() {
        assertThat(analyze("node -e \"process.stdout.write(JSON.stringify(require('./package.json')))\"").writeKind())
                .isNull();
    }

    @Test
    void theRestoreVerbStillWinsOverAnyWriteForm() {
        final ShellEvidence restore = analyze("git checkout -- App.java > /dev/null");
        assertThat(restore.verbClass()).isEqualTo(VerbClass.VCS_RESTORE);
        assertThat(restore.writeKind()).as("only a MUTATING verb carries a write kind").isNull();
    }
}
