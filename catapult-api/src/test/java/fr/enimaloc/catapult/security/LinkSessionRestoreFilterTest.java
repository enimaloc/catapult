package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** LinkSessionRestoreFilter: a secondary link finds its user although catapult-api's session was lost. */
class LinkSessionRestoreFilterTest {

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final LinkSessionRestoreFilter filter = new LinkSessionRestoreFilter(decoder, users);

    private static UserAccount account(UUID id, UserAccount.Status status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setTwitchId("tw-" + id);
        account.setStatus(status);
        return account;
    }

    private static Jwt jwt(UUID subject, List<String> roles) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(subject.toString()).claim("roles", roles)
                .issuedAt(Instant.now()).build();
    }

    private static MockHttpServletRequest linkStart(String provider, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/" + provider);
        request.setRequestURI("/oauth2/authorization/" + provider);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static CatapultOAuth2User sessionUser(MockHttpServletRequest request) {
        if (request.getSession(false) == null) return null;
        Object context = request.getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context instanceof SecurityContext c && c.getAuthentication() != null
                && c.getAuthentication().getPrincipal() instanceof CatapultOAuth2User user ? user : null;
    }

    @Test
    void aLostSession_getsTheUserTheTokenNames() throws Exception {
        UUID id = UUID.randomUUID();
        when(decoder.decode("good")).thenReturn(jwt(id, List.of("ROLE_USER", "ROLE_ADMIN")));
        when(users.findById(id)).thenReturn(Optional.of(account(id, UserAccount.Status.ACTIVE)));
        MockHttpServletRequest request = linkStart("battlenet", "good");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        CatapultOAuth2User user = sessionUser(request);
        assertThat(user).isNotNull();
        assertThat(user.getUserAccount().getId()).isEqualTo(id);
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_ADMIN");
        verify(chain).doFilter(request, response);
    }

    @Test
    void aStaleSessionOfAnotherUser_isReplaced_underANewSessionId() throws Exception {
        UUID stale = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        when(decoder.decode("good")).thenReturn(jwt(current, List.of("ROLE_USER")));
        when(users.findById(current)).thenReturn(Optional.of(account(current, UserAccount.Status.ACTIVE)));
        MockHttpServletRequest request = linkStart("xbox", "good");
        MockHttpSession session = new MockHttpSession();
        CatapultOAuth2User old = new CatapultOAuth2User(null, account(stale, UserAccount.Status.ACTIVE), false);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(old, null, old.getAuthorities())));
        request.setSession(session);
        String oldId = session.getId();

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(sessionUser(request).getUserAccount().getId()).isEqualTo(current);
        assertThat(request.getSession(false).getId()).isNotEqualTo(oldId);
    }

    @Test
    void theSessionsOwnUser_isKeptAsIs() throws Exception {
        UUID id = UUID.randomUUID();
        when(decoder.decode("good")).thenReturn(jwt(id, List.of("ROLE_USER")));
        when(users.findById(id)).thenReturn(Optional.of(account(id, UserAccount.Status.ACTIVE)));
        MockHttpServletRequest request = linkStart("steam", "good");
        MockHttpSession session = new MockHttpSession();
        CatapultOAuth2User own = new CatapultOAuth2User(null, account(id, UserAccount.Status.ACTIVE), false);
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(own, null, own.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        request.setSession(session);
        String id0 = session.getId();

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(request.getSession(false).getId()).isEqualTo(id0);
        assertThat(request.getSession(false).getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isSameAs(context);
    }

    @Test
    void anInvalidToken_anUnknownOrInactiveAccount_orNoToken_restoreNothing() throws Exception {
        UUID inactive = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(decoder.decode("bad")).thenThrow(new BadJwtException("expired"));
        when(decoder.decode("not-a-uuid")).thenReturn(
                Jwt.withTokenValue("t").header("alg", "HS256").subject("42").build());
        when(decoder.decode("inactive")).thenReturn(jwt(inactive, List.of("ROLE_USER")));
        when(decoder.decode("unknown")).thenReturn(jwt(unknown, List.of("ROLE_USER")));
        when(users.findById(inactive)).thenReturn(Optional.of(account(inactive, UserAccount.Status.INACTIVE)));
        when(users.findById(unknown)).thenReturn(Optional.empty());

        for (String token : new String[]{"bad", "not-a-uuid", "inactive", "unknown", null}) {
            MockHttpServletRequest request = linkStart("battlenet", token);
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(request.getSession(false)).as(String.valueOf(token)).isNull();
            verify(chain).doFilter(request, response);
        }
    }

    @Test
    void theTwitchLogin_andOtherPaths_areLeftAlone() throws Exception {
        for (String uri : new String[]{"/oauth2/authorization/twitch", "/login/oauth2/code/battlenet", "/api/x"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            request.addHeader("Authorization", "Bearer good");
            filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
            assertThat(request.getSession(false)).as(uri).isNull();
        }
        verifyNoInteractions(decoder);
        verifyNoInteractions(users);
    }
}
