package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ActiveProviderHolder {

    private static final String INTERNAL = "internal";

    private final ExperimentProvider provider;
    private final String providerType;

    public ActiveProviderHolder(ExperimentProviderProperties properties) {
        this.providerType = properties.getProvider();
        this.provider = switch (providerType) {
            case "unleash" -> new UnleashExperimentProvider(properties);
            case "growthbook" -> new GrowthBookExperimentProvider(properties);
            default -> {
                log.info("[Provider] Using internal experiment provider");
                yield null;
            }
        };
    }

    public ExperimentProvider get() {
        return provider;
    }

    public boolean isInternal() {
        return INTERNAL.equals(providerType);
    }
}
