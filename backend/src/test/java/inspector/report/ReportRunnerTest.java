package inspector.report;

import static org.assertj.core.api.Assertions.assertThat;

import inspector.TestPipeline;
import inspector.TestStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The headless report: boot, write one file, carry everything a loop needs and nothing it must not.
 * The context boots over an index built beforehand from both fixture corpora (two harness
 * versions), so the report has two cohorts to judge.
 */
@SpringBootTest(args = {"--no-index"}, properties = "spring.main.web-application-type=none")
class ReportRunnerTest {

    @TempDir
    static Path temp;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("inspector.db", () -> temp.resolve("report.sqlite").toString());
        registry.add("inspector.corpus", () -> "fixtures/sessions");
        registry.add("inspector.report.path", () -> temp.resolve("out/report.json").toString());
    }

    @BeforeAll
    static void index() {
        try (TestStore store = TestStore.open(temp.resolve("report.sqlite"))) {
            final var service = TestPipeline.indexService(store,
                    TestPipeline.properties("fixtures/sessions", "0.1.5-rc.2", true));
            service.run(Path.of("fixtures/sessions"), "0.1.5-rc.2");
            service.run(Path.of("fixtures/sessions-b"), "0.1.4");
        }
    }

    @Test
    void theReportIsWrittenOnBootWithEverySection() throws Exception {
        final Path file = temp.resolve("out/report.json");
        assertThat(file).exists();
        final JsonNode report = new ObjectMapper().readTree(Files.readString(file));

        assertThat(report.path("format").asText()).isEqualTo(AnalysisReport.FORMAT);
        assertThat(report.path("overview").path("tiles").path("findings").asLong()).isEqualTo(21);
        final List<String> axes = new ArrayList<>();
        report.path("cohorts").propertyNames().forEach(axes::add);
        assertThat(axes).containsExactly("harnessVersion", "model", "provider", "role");
        assertThat(report.path("breakdown")).isNotEmpty();
        // two versions on the default axis: the judge picked its own pair
        assertThat(report.path("judge").path("candidate").asText()).isEqualTo("0.1.4");
        assertThat(report.path("judgeNote").isNull()).isTrue();
        assertThat(report.path("recentFindings").path("items")).isNotEmpty();
    }

    /** The report is an aggregate: the evidence excerpts stay behind the detail endpoint (§4.1). */
    @Test
    void theReportCarriesNoEvidenceText() throws Exception {
        final String text = Files.readString(temp.resolve("out/report.json"));
        assertThat(text).doesNotContain("excerpt").doesNotContain("sed -i").doesNotContain("/home/");
    }
}
