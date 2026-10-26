package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
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
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

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
     * only — a JWT, a widget token or nothing gets a 401, with an {@code unauthorized} problem.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain customBindingsApiSecurityFilterChain(
            HttpSecurity http, UserApiTokenService userApiTokenService, JsonMapper json) throws Exception {
        http
                .securityMatcher(CUSTOM_BINDINGS_API, CUSTOM_BINDINGS_API + "/**")
                // Answers the preflight before any authentication, and adds the headers to every answer, 401 included
                .cors(cors -> cors.configurationSource(customBindingsCors()))
                .authorizeHttpRequests(auth -> auth.anyRequest().access(Authz.personalToken()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), json)))
                .addFilterBefore(new PersonalTokenAuthenticationFilter(userApiTokenService),
                        AuthorizationFilter.class)
                .addFilterAfter(new DebugSubjectFilter(), PersonalTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The custom bindings API is open to a page of any origin (Twitchat runs in the browser): the personal token, in
     * {@code Authorization}, is its only credential — no cookie or session counts here — so the answer allows no
     * credentials and any origin is safe. Not a bean, so no other chain picks it up.
     */
    static CorsConfigurationSource customBindingsCors() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin(CorsConfiguration.ALL);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(CUSTOM_BINDINGS_API, config);
        source.registerCorsConfiguration(CUSTOM_BINDINGS_API + "/**", config);
        return source;
    }

    @Bean
    @Profile("dev")
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();

        return request -> {
            if (request.getRequestURI().startsWith(ApiPaths.INTERNAL + "/config/app")
                    || request.getRequestURI().startsWith(ApiPaths.INTERNAL + "/config/providers")) {
                return null; // Ignores the Authorization header
            }
            return resolver.resolve(request);
        };
    }

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        // The bearer handlers answer as before (status, WWW-Authenticate); internal routes also get a problem body
        AuthenticationEntryPoint entryPoint =
                new ProblemAuthenticationEntryPoint(new BearerTokenAuthenticationEntryPoint(), json);
        AccessDeniedHandler deniedHandler =
                new ProblemAccessDeniedHandler(new BearerTokenAccessDeniedHandler(), json);
        http
                .securityMatcher("/api/**")
                // Required for @CrossOrigin (see ApiGameInfoController) to actually emit
                // Access-Control-* headers — without this, Spring Security never delegates
                // to the CorsConfigurationSource Spring MVC derives from it.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ApiPaths.INTERNAL + "/health", ApiPaths.INTERNAL + "/config/**", ApiPaths.INTERNAL + "/changelog", ApiPaths.INTERNAL + "/status/degraded", ApiPaths.INTERNAL + "/well-known-files", ApiPaths.INTERNAL + "/meta-tags", "/api/twitchat/widget/**", "/api/twitchat/actions/**", "/api/game/**", "/api/user/**", "/api/v3/api-docs/**").permitAll()
                        .requestMatchers(ApiPaths.INTERNAL + "/auth/exchange").permitAll()
                        .requestMatchers("/api/connect/steam/callback").permitAll()
                        .requestMatchers(ApiPaths.ADMIN + "/**").access(Authz.admin())
                        .anyRequest().access(Authz.memberOrLoopback())
                )
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // The same pair for refusals ExceptionTranslationFilter answers (no credential, @PreAuthorize)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
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
