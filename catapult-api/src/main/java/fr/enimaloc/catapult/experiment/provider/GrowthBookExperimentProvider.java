package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import growthbook.sdk.java.GrowthBook;
import growthbook.sdk.java.model.GBContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Slf4j
public class GrowthBookExperimentProvider implements ExperimentProvider {

    private final ExperimentProviderProperties.GrowthBook config;
    private final RestClient restClient;

    public GrowthBookExperimentProvider(ExperimentProviderProperties props, RestClient.Builder builder) {
        this.config = props.getGrowthbook();
        this.restClient = builder.build();
    }

    GrowthBookExperimentProvider(ExperimentProviderProperties props, RestClient restClient) {
        this.config = props.getGrowthbook();
        this.restClient = restClient;
    }

    @Override
    public String type() {
        return "growthbook";
    }

    @Override
    public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
        try {
            String featuresJson = fetchFeaturesJson();
            String attributesJson = "{\"id\":\"" + user.getId().toString() + "\"}";

            GBContext context = GBContext.builder()
                .featuresJson(featuresJson)
                .attributesJson(attributesJson)
                .enabled(true)
                .build();

            GrowthBook growthBook = new GrowthBook(context);
            Boolean isOn = growthBook.isOn(experimentKey);
            if (!Boolean.TRUE.equals(isOn)) {
                return Optional.empty();
            }
            String value = growthBook.getFeatureValue(experimentKey, (String) null);
            String variantKey = value != null ? value : "on";
            ExperimentVariant ev = new ExperimentVariant();
            ev.setKey(variantKey);
            ev.setName(variantKey);
            ev.setControl("control".equals(variantKey));
            return Optional.of(ev);
        } catch (Exception e) {
            log.warn("[GrowthBook] Failed to resolve variant for '{}': {}", experimentKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ExperimentSummary> listExperiments() {
        return List.of();
    }

    @Override
    public Optional<String> adminUrl() {
        return Optional.ofNullable(config.getApiHost());
    }

    @Override
    public void trackEvent(UserAccount user, String experimentKey, String eventKey) {
        // Silent unsupported on GrowthBook
    }

    @Override
    public void importExperiments(List<Experiment> experiments) {
        log.info("[GrowthBook] importExperiments: {} experiments — create them in GrowthBook manually.", experiments.size());
    }

    @Override
    public boolean isHealthy() {
        try {
            fetchFeaturesJson();
            return true;
        } catch (Exception e) {
            log.warn("[GrowthBook] Health check failed: {}", e.getMessage());
            return false;
        }
    }

    private String fetchFeaturesJson() {
        String url = config.getApiHost() + "/api/features/" + config.getClientKey();
        log.debug("[GrowthBook] Fetching features from {}", url);
        return restClient.get()
            .uri(url)
            .retrieve()
            .body(String.class);
    }
}
