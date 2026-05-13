package fr.enimaloc.catapult.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("mock")
public class MockSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain mockFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/mock/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/mock/**"));
        return http.build();
    }
}
