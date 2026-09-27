package inspector;

import inspector.config.InspectorProperties;
import inspector.report.ReportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({InspectorProperties.class, ReportProperties.class})
public final class InspectorApplication {

    private InspectorApplication() {
    }

    public static void main(final String[] args) {
        SpringApplication.run(InspectorApplication.class, args);
    }
}
