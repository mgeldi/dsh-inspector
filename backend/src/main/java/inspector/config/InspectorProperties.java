package inspector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "inspector")
public record InspectorProperties(
        @DefaultValue("fixtures/sessions") String corpus,
        @DefaultValue("unknown") String harnessVersion,
        @DefaultValue Evidence evidence) {

    public record Evidence(@DefaultValue("true") boolean store) {
    }
}
