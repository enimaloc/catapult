package fr.enimaloc.catapult.security;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    @Value("${app.web-url:http://localhost:8081}")
    private String webUrl;

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * Custom request resolver that injects {@code force_verify=true} on the
     * Twitch authorize URL when the flow is a bot-link (session attribute
     * {@code bot-link-pending} present). Twitch interprets this flag as "always
     * show the consent screen", which is required so the admin can pick the
     * bot's Twitch account instead of being silently logged in as themselves.
     */
    @Bean
    public OAuth2AuthorizationRequestResolver botLinkAwareAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
            new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(builder -> {
            if (isBotLinkFlow()) {
                builder.additionalParameters(params -> params.put("force_verify", "true"));
            }
        });
        return resolver;
    }

    private boolean isBotLinkFlow() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpSession session = attrs.getRequest().getSession(false);
            return session != null && session.getAttribute(BotLinkController.SESSION_ATTR) != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    @Bean
    @SuppressWarnings("RedundantThrows")
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authz -> authz
                                .authorizationRequestResolver(authorizationRequestResolver))
                        .successHandler(loginSuccessHandler)
                        .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
                        .failureHandler((request, response, exception) -> {
                            log.error("OAuth2 login failed: [{}] {}", exception.getClass().getSimpleName(), exception.getMessage(), exception);
                            String redirectUrl = webUrl + "/login?error";
                            if (exception instanceof OAuth2AuthenticationException oauthEx) {
                                redirectUrl = switch (oauthEx.getError().getErrorCode()) {
                                    case "not_whitelisted" -> webUrl + "/login?error=not_whitelisted";
                                    case "invalid_invite"  -> webUrl + "/join?error=invalid_invite";
                                    case "alpha_full"      -> webUrl + "/join?error=alpha_full";
                                    default -> redirectUrl;
                                };
                            }
                            response.sendRedirect(redirectUrl);
                        })
                )
                .csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }
}
