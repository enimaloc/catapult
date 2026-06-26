package fr.enimaloc.catapult.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

@Configuration
@EnableAsync
public class WebClientConfig {

    /**
     * Register the trace-logging interceptor on every {@code RestClient.Builder}
     * Spring Boot hands out. Wraps the request factory in a
     * {@link BufferingClientHttpRequestFactory} so the interceptor can read
     * the response body without exhausting the underlying stream for the
     * actual caller.
     */
    @Bean
    public RestClientCustomizer traceLoggingCustomizer(HttpTraceLoggingInterceptor interceptor) {
        return builder -> builder
                .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory()))
                .requestInterceptor(interceptor);
    }

    /**
     * Pulled from the Boot-managed builder so every customizer (incl. the
     * trace logger above) applies. Replaces the previous {@code RestClient.create()}
     * call which bypassed customizers.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
