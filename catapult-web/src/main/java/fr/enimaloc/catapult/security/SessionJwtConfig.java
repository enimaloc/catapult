package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.service.ApiService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Registers {@link SessionJwtFilter} after the security chain, within the request's context (its JWT read from there). */
@Configuration
public class SessionJwtConfig {

    @Bean
    FilterRegistrationBean<SessionJwtFilter> sessionJwtFilter(ObjectProvider<ApiService> apiService,
                                                              ObjectProvider<SseStreams> sse) {
        FilterRegistrationBean<SessionJwtFilter> registration = new FilterRegistrationBean<>(
                new SessionJwtFilter(apiService::getIfAvailable, sse::getIfAvailable, Clock.systemUTC()));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
