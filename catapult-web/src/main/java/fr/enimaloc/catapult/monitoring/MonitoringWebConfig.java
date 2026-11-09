package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Names the member in Sentry reports (see {@link SentryUserInterceptor}), and counts pages and
 * actions (see {@link InteractionMetricsInterceptor}); not in the MVC test slices lacking what they
 * need.
 */
@Configuration
@RequiredArgsConstructor
class MonitoringWebConfig implements WebMvcConfigurer {
    private static final String[] STATIC = {"/css/**", "/js/**", "/_static/**", "/assets/**"};

    private final ObjectProvider<InteractionMetrics> metrics;
    private final ObjectProvider<ApiService> apiService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        ApiService api = apiService.getIfAvailable();
        if (api == null) {
            return;
        }
        registry.addInterceptor(new SentryUserInterceptor(api)).excludePathPatterns(STATIC);
        metrics.ifAvailable(interactions -> registry.addInterceptor(new InteractionMetricsInterceptor(interactions, api))
                .excludePathPatterns(STATIC));
    }
}
