package fr.enimaloc.catapult.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Handles the Twitch OAuth2 login flow for catapult-api.
 * Only /oauth2/** and /login/oauth2/** reach this filter chain (nginx routes them here).
 * Everything else is handled by ApiSecurityConfig (/api/**) or catapult-web.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@Profile("!mock-web")
@RequiredArgsConstructor
public class SecurityConfig {

    private final CatapultOAuth2UserService oAuth2UserService;
    private final TwitchLoginSuccessHandler loginSuccessHandler;
    private final Oauth2LoginFailureHandler loginFailureHandler;

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    @SuppressWarnings("RedundantThrows")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(loginSuccessHandler)
                        .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
                        .failureHandler(loginFailureHandler)
                )
                .csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }
}
