package fr.enimaloc.catapult.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableAsync
public class WebClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Register the trace-logging interceptor on every {@code RestClient.Builder}
     * Spring Boot hands out. Wraps the request factory in a
     * {@link BufferingClientHttpRequestFactory} so the interceptor can read
     * the response body without exhausting the underlying stream for the
     * actual caller.
     */
    @Bean
    public RestClientCustomizer traceLoggingCustomizer(HttpTraceLoggingInterceptor interceptor) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return builder -> builder
                .requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
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
