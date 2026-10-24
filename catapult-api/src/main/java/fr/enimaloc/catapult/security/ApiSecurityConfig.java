package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.service.account.UserApiTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
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

    /** Mount point of the custom bindings API, which only the personal API token opens. */
    public static final String CUSTOM_BINDINGS_API = "/api/v2/custom-bindings";

    /**
     * {@value #CUSTOM_BINDINGS_API}/**, in every profile: ordered before the /api/** chains below (which would take
     * its Bearer for a JWT, and let localhost through), stateless, no CSRF, authenticated by the personal API token
     * only — a JWT, a widget token or nothing gets a 401.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain customBindingsApiSecurityFilterChain(
            HttpSecurity http, ObjectProvider<UserApiTokenService> userApiTokenService) throws Exception {
        http
                .securityMatcher(CUSTOM_BINDINGS_API, CUSTOM_BINDINGS_API + "/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new PersonalTokenAuthenticationFilter(userApiTokenService),
                        AuthorizationFilter.class)
                .addFilterAfter(new DebugSubjectFilter(), PersonalTokenAuthenticationFilter.class);

        return http.build();
    }

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
                // Not a bean: a Filter bean would also be registered on the servlet container, outside the chain
                .addFilterAfter(new DebugSubjectFilter(), BearerTokenAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable);

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
                        .requestMatchers("/api/health", "/api/config/**", "/api/changelog", "/api/status/degraded", "/api/twitchat/widget/**", "/api/twitchat/actions/**", "/api/game/**", "/api/user/**", "/api/well-known/serve", "/api/meta-tags", "/api/v3/api-docs/**").permitAll()
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
                // Not a bean: a Filter bean would also be registered on the servlet container, outside the chain
                .addFilterAfter(new DebugSubjectFilter(), BearerTokenAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable);

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
            return Stream.concat(scopes.stream(), roleAuthorities).toList();
        });
        return converter;
    }
}
