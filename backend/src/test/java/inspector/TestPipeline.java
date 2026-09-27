package inspector;

import inspector.config.InspectorProperties;
import inspector.detect.Detector;
import inspector.detect.EditMissDetector;
import inspector.detect.ErrorPlaneDetector;
import inspector.detect.FatalTurnDetector;
import inspector.detect.RetryStormDetector;
import inspector.detect.ShellEditDetector;
import inspector.detect.StampGuardDetector;
import inspector.index.IndexService;
import inspector.ingest.CorpusScanner;
import inspector.ingest.SessionIngestor;
import inspector.ingest.ShellAnalyzer;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * The indexing pipeline as the application wires it, assembled by hand. One place, so a detector
 * added to the application cannot be missing from the hand-built pipelines the tests compare
 * against — {@code ApplicationContextTest} asserts the context holds exactly these.
 */
public final class TestPipeline {

    private TestPipeline() {
    }

    /**
     * Every detector, in the shape Spring hands them over: the error-plane detector receives the
     * others, so it skips exactly the codes they own.
     */
    public static List<Detector> detectors() {
        final List<Detector> owners = List.of(new StampGuardDetector(), new EditMissDetector(),
                new FatalTurnDetector(), new RetryStormDetector(), new ShellEditDetector());
        final List<Detector> all = new ArrayList<>(owners);
        all.add(new ErrorPlaneDetector(owners));
        return List.copyOf(all);
    }

    public static SessionIngestor ingestor() {
        final ObjectMapper mapper = new ObjectMapper();
        return new SessionIngestor(mapper, new ShellAnalyzer(mapper));
    }

    public static IndexService indexService(final TestStore store, final InspectorProperties properties) {
        return new IndexService(new CorpusScanner(), ingestor(), detectors(), store.writer(), properties);
    }

    public static InspectorProperties properties(final String corpus, final String harnessVersion,
                                                 final boolean storeEvidence) {
        return new InspectorProperties(corpus, harnessVersion,
                new InspectorProperties.Evidence(storeEvidence), List.of());
    }
}
