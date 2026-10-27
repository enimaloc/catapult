package fr.enimaloc.catapult.security;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** {@code @authz.isAdmin()}: the instance form of the admin rule, read from the current authentication and request. */
class AuthzAdminTest {

    private final Authz authz =
            new Authz(org.mockito.Mockito.mock(ApiUserResolver.class), org.mockito.Mockito.mock(ChannelAccessService.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void current(String authority, String remote) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(authority)));
        request(remote);
    }

    private static void request(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void roleAdminIsAdmin() {
        current("ROLE_ADMIN", "203.0.113.7");

        assertThat(authz.isAdmin()).isTrue();
    }

    @Test
    void loopbackWithoutJwtIsAdmin() {
        request("127.0.0.1");

        assertThat(authz.isAdmin()).isTrue();
    }

    @Test
    void memberIsNotAdmin() {
        current("ROLE_USER", "203.0.113.7");

        assertThat(authz.isAdmin()).isFalse();
    }

    @Test
    void noRequestIsNotAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "HS256").subject("x").build(),
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        assertThat(authz.isAdmin()).isFalse();
    }
}
