package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Counts pages and actions (see {@link InteractionMetricsInterceptor}); not in the MVC test slices
 * lacking what it needs.
 */
@Configuration
@RequiredArgsConstructor
class MonitoringWebConfig implements WebMvcConfigurer {
    private static final String[] STATIC = {"/css/**", "/js/**", "/_static/**"};

    private final ObjectProvider<InteractionMetrics> metrics;
    private final ObjectProvider<ApiService> apiService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        ApiService api = apiService.getIfAvailable();
        if (api == null) {
            return;
        }
        metrics.ifAvailable(interactions -> registry.addInterceptor(new InteractionMetricsInterceptor(interactions, api))
                .excludePathPatterns(STATIC));
    }
}
