package inspector;

import inspector.config.InspectorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InspectorProperties.class)
public final class InspectorApplication {

    private InspectorApplication() {
    }

    public static void main(final String[] args) {
        SpringApplication.run(InspectorApplication.class, args);
    }
}
