package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers {@link LegacyRoutesGate}. Neither this class nor the gate is a {@code WebMvcConfigurer} or
 * {@code HandlerInterceptor} component on purpose: {@code @WebMvcTest} slices pick those up, and every controller
 * slice would then need the gate's services. A slice that tests the gate imports this class.
 */
@Configuration
public class LegacyRoutesGateConfig {

    @Bean
    LegacyRoutesGate legacyRoutesGate(ExperimentService experimentService, WidgetTokenService widgetTokenService,
            PublicBaseUrl publicBaseUrl, JsonMapper jsonMapper) {
        return new LegacyRoutesGate(experimentService, widgetTokenService, publicBaseUrl, jsonMapper);
    }

    @Bean
    WebMvcConfigurer legacyRoutesGateRegistration(LegacyRoutesGate gate) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(gate);
            }
        };
    }
}
