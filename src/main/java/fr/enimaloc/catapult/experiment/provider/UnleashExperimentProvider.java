package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import io.getunleash.DefaultUnleash;
import io.getunleash.Unleash;
import io.getunleash.UnleashContext;
import io.getunleash.util.UnleashConfig;
import io.getunleash.Variant;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
public class UnleashExperimentProvider implements ExperimentProvider {

    private final ExperimentProviderProperties.Unleash config;
    private final Unleash unleash;

    public UnleashExperimentProvider(ExperimentProviderProperties props) {
        this.config = props.getUnleash();
        UnleashConfig unleashConfig = UnleashConfig.builder()
            .appName("catapult")
            .unleashAPI(config.getApiUrl())
            .customHttpHeader("Authorization", config.getClientKey())
            .build();
        this.unleash = new DefaultUnleash(unleashConfig);
    }

    UnleashExperimentProvider(ExperimentProviderProperties props, Unleash unleash) {
        this.config = props.getUnleash();
        this.unleash = unleash;
    }

    @Override
    public String type() { return "unleash"; }

    @Override
    public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
        try {
            UnleashContext ctx = UnleashContext.builder()
                .userId(user.getId().toString())
                .build();
            Variant variant = unleash.getVariant(experimentKey, ctx);
            if (!variant.isEnabled()) return Optional.empty();
            ExperimentVariant ev = new ExperimentVariant();
            ev.setKey(variant.getName());
            ev.setName(variant.getName());
            ev.setControl("control".equals(variant.getName()));
            return Optional.of(ev);
        } catch (Exception e) {
            log.warn("[Unleash] Failed to resolve variant for '{}': {}", experimentKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ExperimentSummary> listExperiments() { return List.of(); }

    @Override
    public Optional<String> adminUrl() {
        return Optional.ofNullable(config.getApiUrl());
    }

    @Override
    public void trackEvent(UserAccount user, String experimentKey, String eventKey) {
        // Silent unsupported on Unleash
    }

    @Override
    public void importExperiments(List<Experiment> experiments) {
        log.info("[Unleash] importExperiments: {} experiments — create them in Unleash manually.", experiments.size());
    }

    @Override
    public boolean isHealthy() {
        try {
            unleash.isEnabled("__health_check__");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
