package fr.enimaloc.catapult.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns method security on in every profile, so a {@code @PreAuthorize} is enforced. The only
 * expressions allowed are calls to the {@code authz} bean ({@link Authz}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class MethodSecurityConfig {}
