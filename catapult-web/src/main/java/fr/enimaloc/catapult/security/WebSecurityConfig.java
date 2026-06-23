package fr.enimaloc.catapult.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtSessionAuthFilter jwtFilter) throws Exception {
        http
                .addFilterBefore(jwtFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // /ws/auth-ticket is technically auth-only but stays permitAll so
                        // unauthenticated requests get a clean 401 from the controller instead of
                        // a 302 redirect to /login from the entry point.
                        .requestMatchers("/", "/login", "/auth/callback", "/join", "/privacy", "/error",
                                "/css/**", "/js/**", "/images/**", "/webjars/**",
                                "/changelog", "/changelog/**", "/actuator/**", "/status",
                                "/ws", "/ws/**").permitAll()
                        .requestMatchers("/admin/impersonate/exit").hasAuthority("ROLE_PREVIOUS_ADMINISTRATOR")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/logout"));

        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
