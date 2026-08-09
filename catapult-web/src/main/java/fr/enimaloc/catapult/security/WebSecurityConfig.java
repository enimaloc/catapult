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
                                "/ws", "/ws/**", "/.well-known/**", "/widget/twitchat/**").permitAll()
                        .requestMatchers("/admin/impersonate/exit").hasAuthority("ROLE_PREVIOUS_ADMINISTRATOR")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // ThirdPartiesVerificationController : fichiers de vérification lus par des
                        // crawlers tiers (Riot, well-known...) sans session. Placé après /admin/** :
                        // /{file} ne doit jamais court-circuiter la protection ROLE_ADMIN du chemin
                        // littéral /admin.
                        .requestMatchers("/riot.txt", "/{file}").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        // Home page (HomeController -> home.html) carries the
                        // "Se connecter avec Twitch" entry; sending unauthenticated
                        // traffic there beats the standalone /login template that
                        // duplicated the same CTA.
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/"))
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
