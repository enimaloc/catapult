package fr.enimaloc.catapult.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "experiment")
@Getter
@Setter
public class ExperimentProviderProperties {

    private String provider = "internal";
    private Unleash unleash = new Unleash();
    private GrowthBook growthbook = new GrowthBook();

    @Getter
    @Setter
    public static class Unleash {
        private String apiUrl;
        private String clientKey;
    }

    @Getter
    @Setter
    public static class GrowthBook {
        private String apiHost;
        private String clientKey;
        private String apiKey;
    }
}
