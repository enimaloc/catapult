package fr.enimaloc.catapult.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthzRequestRulesTest {

    private static MockHttpServletRequest from(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        return request;
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    }

    private static Authentication jwt(String authority) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(authority));
    }

    @Test
    void loopbackIsV4AndV6Only() {
        assertThat(Authz.isLoopback(from("127.0.0.1"))).isTrue();
        assertThat(Authz.isLoopback(from("::1"))).isTrue();
        assertThat(Authz.isLoopback(from("127.0.0.2"))).isFalse();
        assertThat(Authz.isLoopback(from("10.0.0.1"))).isFalse();
        assertThat(Authz.isLoopback(from("203.0.113.7"))).isFalse();
    }

    @Test
    void adminIsRoleOrLoopback() {
        assertThat(Authz.isAdmin(jwt("ROLE_ADMIN"), from("203.0.113.7"))).isTrue();
        assertThat(Authz.isAdmin(jwt("ROLE_USER"), from("203.0.113.7"))).isFalse();
        assertThat(Authz.isAdmin(null, from("::1"))).isTrue();
    }

    @Test
    void anonymousIsNotAMember() {
        assertThat(Authz.isMember(anonymous())).isFalse();
        assertThat(Authz.isMember(jwt("ROLE_USER"))).isTrue();
        assertThat(Authz.isMember(null)).isFalse();
    }

    @Test
    void personalTokenIsTheApiTokenAuthority() {
        Authentication token = new TestingAuthenticationToken(UUID.randomUUID(), null, List.of(
                () -> PersonalTokenAuthenticationFilter.AUTHORITY));
        assertThat(Authz.isPersonalToken(token)).isTrue();
        assertThat(Authz.isPersonalToken(jwt("ROLE_USER"))).isFalse();
        assertThat(Authz.isPersonalToken(null)).isFalse();
    }

    @Test
    void readOnlyTokenIsAPersonalTokenButNotAFullOne() {
        Authentication full = new TestingAuthenticationToken(UUID.randomUUID(), null, List.of(
                () -> PersonalTokenAuthenticationFilter.AUTHORITY));
        Authentication read = new TestingAuthenticationToken(UUID.randomUUID(), null, List.of(
                () -> PersonalTokenAuthenticationFilter.READ_AUTHORITY));
        assertThat(Authz.isPersonalToken(read)).isTrue();
        assertThat(Authz.isFullPersonalToken(full)).isTrue();
        assertThat(Authz.isFullPersonalToken(read)).isFalse();
        assertThat(Authz.isFullPersonalToken(null)).isFalse();
        assertThat(Authz.personalToken().authorize(() -> read, ctx("203.0.113.7")).isGranted()).isTrue();
        assertThat(Authz.fullPersonalToken().authorize(() -> read, ctx("127.0.0.1")).isGranted()).isFalse();
        assertThat(Authz.fullPersonalToken().authorize(() -> full, ctx("203.0.113.7")).isGranted()).isTrue();
    }

    @Test
    void managersDecideAsTheRules() {
        assertThat(Authz.admin().authorize(() -> anonymous(), ctx("203.0.113.7")).isGranted()).isFalse();
        assertThat(Authz.admin().authorize(() -> anonymous(), ctx("127.0.0.1")).isGranted()).isTrue();
        assertThat(Authz.memberOrLoopback().authorize(() -> anonymous(), ctx("203.0.113.7")).isGranted()).isFalse();
        assertThat(Authz.memberOrLoopback().authorize(() -> jwt("ROLE_USER"), ctx("203.0.113.7")).isGranted()).isTrue();
        assertThat(Authz.memberOrLoopback().authorize(() -> anonymous(), ctx("::1")).isGranted()).isTrue();
        assertThat(Authz.personalToken().authorize(() -> jwt("ROLE_USER"), ctx("127.0.0.1")).isGranted()).isFalse();
    }

    private static RequestAuthorizationContext ctx(String remote) {
        return new RequestAuthorizationContext(from(remote));
    }
}
