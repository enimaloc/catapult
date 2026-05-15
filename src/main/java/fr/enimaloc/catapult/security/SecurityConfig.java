package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.switchuser.SwitchUserFilter;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CatapultOAuth2UserService oAuth2UserService;

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(UserAccountRepository userAccountRepository) {
        return new ApiKeyAuthFilter(userAccountRepository);
    }

    @Bean
    public SwitchUserFilter switchUserFilter(ImpersonationUserDetailsService impersonationUserDetailsService) {
        SwitchUserFilter filter = new SwitchUserFilter();
        filter.setUserDetailsService(impersonationUserDetailsService);
        filter.setSwitchUserUrl("/admin/impersonate");
        filter.setExitUserUrl("/admin/impersonate/exit");
        filter.setSuccessHandler((req, res, auth) -> {
            // If the resulting authentication has ROLE_PREVIOUS_ADMINISTRATOR it is a
            // switch-in; if not (i.e. we just exited back to the original admin) redirect
            // to the members page instead.
            boolean isSwitched = auth.getAuthorities().stream()
                    .anyMatch(a -> SwitchUserFilter.ROLE_PREVIOUS_ADMINISTRATOR.equals(a.getAuthority()));
            res.sendRedirect(isSwitched ? "/app" : "/admin/members");
        });
        filter.setFailureHandler((req, res, ex) -> {
            log.warn("Impersonation failed: {}", ex.getMessage());
            res.sendRedirect("/admin/members?error=impersonateFailed");
        });
        AccountStatusUserDetailsChecker statusChecker = new AccountStatusUserDetailsChecker();
        filter.setUserDetailsChecker(details -> {
            statusChecker.check(details);
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CatapultOAuth2User admin
                && admin.getUserAccount().getTwitchUsername().equals(details.getUsername())) {
                throw new LockedException("Cannot impersonate yourself");
            }
        });
        return filter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    ApiKeyAuthFilter apiKeyAuthFilter,
                                                    SwitchUserFilter switchUserFilter) throws Exception {
        http
            .addFilterBefore(apiKeyAuthFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(switchUserFilter, AuthorizationFilter.class)
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/obs/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers("/api/obs/**").authenticated()
                .requestMatchers("/admin/impersonate/exit").authenticated()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .defaultSuccessUrl("/app", true)
                .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
                .failureHandler((request, response, exception) -> {
                    log.error("OAuth2 login failed: [{}] {}", exception.getClass().getSimpleName(), exception.getMessage(), exception);
                    new SimpleUrlAuthenticationFailureHandler("/login?error").onAuthenticationFailure(request, response, exception);
                })
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );

        return http.build();
    }
}
