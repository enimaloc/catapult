package fr.enimaloc.catapult.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

import javax.crypto.SecretKey;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stateless filter chain for /api/** routes.
 * Validates Bearer JWT tokens issued by JwtService after OAuth2 login.
 * Must be ordered before SecurityConfig (which handles the session-based UI).
 */
@Configuration
@Order(1)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiSecurityConfig {

    @Bean
    @Profile("dev")
    public SecurityFilterChain devApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                // Required for @CrossOrigin (see ApiGameInfoController) to actually emit
                // Access-Control-* headers — without this, Spring Security never delegates
                // to the CorsConfigurationSource Spring MVC derives from it.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    @Profile("dev")
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();

        return request -> {
            if (request.getRequestURI().startsWith("/api/config/app")
                    || request.getRequestURI().startsWith("/api/config/providers")) {
                return null; // Ignore le header Authorization
            }
            return resolver.resolve(request);
        };
    }

    @Bean
    @Profile("!dev")
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                // Required for @CrossOrigin (see ApiGameInfoController) to actually emit
                // Access-Control-* headers — without this, Spring Security never delegates
                // to the CorsConfigurationSource Spring MVC derives from it.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/config/**", "/api/changelog", "/api/twitchat/widget/**", "/api/twitchat/actions/**", "/api/twitchat/defaults", "/api/game/**", "/api/user/**", "/api/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/auth/exchange").permitAll()
                        .requestMatchers("/api/connect/steam/callback").permitAll()
                        .requestMatchers("/api/admin/**").access(new WebExpressionAuthorizationManager(
                                "hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')"
                        ))
                        .anyRequest().access(new WebExpressionAuthorizationManager(
                                "isAuthenticated() or hasIpAddress('127.0.0.1') or hasIpAddress('::1')"
                        ))
                )
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).build();
    }

    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> scopes = scopesConverter.convert(jwt);
            List<String> roles = jwt.getClaimAsStringList("roles");
            Stream<GrantedAuthority> roleAuthorities = roles == null ? Stream.empty() :
                    roles.stream().map(SimpleGrantedAuthority::new);
            return Stream.concat(scopes == null ? Stream.empty() : scopes.stream(), roleAuthorities).toList();
        });
        return converter;
    }
}
