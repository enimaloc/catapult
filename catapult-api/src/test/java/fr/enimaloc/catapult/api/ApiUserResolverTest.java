package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiUserResolverTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final ApiUserResolver resolver = new ApiUserResolver(users);

    @BeforeEach
    void inARequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void outOfTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static UserAccount account(String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        account.setTwitchId(username + "-id");
        return account;
    }

    private static Jwt jwtFor(UserAccount account) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(account.getId().toString()).claim("twitchId", account.getTwitchId()).build();
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void viewer_bySubject() {
        UserAccount me = account("me");
        when(users.findById(me.getId())).thenReturn(Optional.of(me));

        assertThat(resolver.viewer(jwtFor(me))).isSameAs(me);
    }

    @Test
    void byTwitchUsername_findsTheExactUsername() {
        UserAccount streamer = account("streamer");
        when(users.findByTwitchUsername("streamer")).thenReturn(Optional.of(streamer));

        assertThat(resolver.byTwitchUsername("streamer")).isSameAs(streamer);
    }

    @Test
    void byTwitchUsername_unknownIs404WithoutAReason() {
        when(users.findByTwitchUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.byTwitchUsername("nobody"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isNull();
                });
    }

    @Test
    void requireJwt_nullIs401() {
        assertStatus(() -> ApiUserResolver.requireJwt(null), HttpStatus.UNAUTHORIZED);
        Jwt jwt = jwtFor(account("me"));
        assertThat(ApiUserResolver.requireJwt(jwt)).isSameAs(jwt);
    }

    @Test
    void viewer_unknownIsUnauthorized() {
        assertStatus(() -> resolver.viewer(jwtFor(account("ghost"))), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void viewerByTwitchId() {
        UserAccount me = account("me");
        when(users.findByTwitchId("me-id")).thenReturn(Optional.of(me));

        assertThat(resolver.viewerByTwitchId(jwtFor(me))).isSameAs(me);
        assertStatus(() -> resolver.viewerByTwitchId(jwtFor(account("ghost"))), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noTokenIsUnauthorized() {
        assertStatus(() -> resolver.viewer(null), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> resolver.viewerByTwitchId(null), HttpStatus.UNAUTHORIZED);
    }


    @Test
    void user_byAccountId_orNotFound() {
        UserAccount someone = account("someone");
        when(users.findById(someone.getId())).thenReturn(Optional.of(someone));

        assertThat(resolver.user(someone.getId())).isSameAs(someone);
        assertStatus(() -> resolver.user(UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }

    @Test
    void withinARequest_eachAccountIsReadOnce() {
        UserAccount me = account("me");
        when(users.findById(me.getId())).thenReturn(Optional.of(me));

        assertThat(resolver.viewer(jwtFor(me))).isSameAs(me);
        assertThat(resolver.user(me.getId())).isSameAs(me);
        assertThat(resolver.user(me.getId())).isSameAs(me);

        verify(users, times(1)).findById(me.getId());
    }

    @Test
    void byTwitchIdReusesTheAccountReadBySubject() {
        UserAccount me = account("me");
        when(users.findById(me.getId())).thenReturn(Optional.of(me));

        resolver.viewer(jwtFor(me));

        assertThat(resolver.viewerByTwitchId(jwtFor(me))).isSameAs(me);
        verify(users, never()).findByTwitchId(any());
    }

    @Test
    void byTwitchId_aClaimOfAnotherAccountIsReadFromTheRepository() {
        UserAccount me = account("me");
        UserAccount other = account("other");
        when(users.findById(me.getId())).thenReturn(Optional.of(me));
        when(users.findByTwitchId("other-id")).thenReturn(Optional.of(other));
        Jwt mixed = Jwt.withTokenValue("t").header("alg", "none")
                .subject(me.getId().toString()).claim("twitchId", "other-id").build();

        resolver.viewer(mixed);

        assertThat(resolver.viewerByTwitchId(mixed)).isSameAs(other);
        verify(users, times(1)).findByTwitchId("other-id");
    }

    @Test
    void byTwitchId_thenBySubject_readsOnce() {
        UserAccount me = account("me");
        when(users.findByTwitchId("me-id")).thenReturn(Optional.of(me));

        resolver.viewerByTwitchId(jwtFor(me));

        assertThat(resolver.viewer(jwtFor(me))).isSameAs(me);
        assertThat(resolver.viewerByTwitchId(jwtFor(me))).isSameAs(me);
        verify(users, never()).findById(any());
        verify(users, times(1)).findByTwitchId("me-id");
    }

    @Test
    void anAccountWithoutTwitchId_isNotServedForAMissingClaim() {
        UserAccount noTwitch = account("none");
        noTwitch.setTwitchId(null);
        when(users.findById(noTwitch.getId())).thenReturn(Optional.of(noTwitch));
        Jwt noClaim = Jwt.withTokenValue("t").header("alg", "none").subject(noTwitch.getId().toString()).build();

        resolver.viewer(noClaim);

        assertStatus(() -> resolver.viewerByTwitchId(noClaim), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMissIsNotMemoized() {
        UUID unknown = UUID.randomUUID();

        assertStatus(() -> resolver.user(unknown), HttpStatus.NOT_FOUND);
        assertStatus(() -> resolver.user(unknown), HttpStatus.NOT_FOUND);

        verify(users, times(2)).findById(unknown);
    }

    @Test
    void anUnknownCallerIsNotMemoized() {
        UserAccount ghost = account("ghost");

        assertStatus(() -> resolver.viewer(jwtFor(ghost)), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> resolver.viewerByTwitchId(jwtFor(ghost)), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> resolver.viewer(jwtFor(ghost)), HttpStatus.UNAUTHORIZED);

        verify(users, times(2)).findById(ghost.getId());
        verify(users, times(1)).findByTwitchId("ghost-id");
    }

    @Test
    void outsideARequest_nothingIsMemoized() {
        RequestContextHolder.resetRequestAttributes();
        UserAccount someone = account("someone");
        when(users.findById(someone.getId())).thenReturn(Optional.of(someone));

        resolver.user(someone.getId());
        resolver.user(someone.getId());

        verify(users, times(2)).findById(someone.getId());
    }

    @Test
    void twoRequests_readTwice() {
        UserAccount someone = account("someone");
        when(users.findById(someone.getId())).thenReturn(Optional.of(someone));

        resolver.user(someone.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        resolver.user(someone.getId());

        verify(users, times(2)).findById(someone.getId());
    }

    @Test
    void anAsyncRedispatchOfTheSameRequest_readsAgain() {
        UserAccount someone = account("someone");
        when(users.findById(someone.getId())).thenReturn(Optional.of(someone));
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        resolver.user(someone.getId());
        request.setDispatcherType(DispatcherType.ASYNC);
        resolver.user(someone.getId());
        resolver.user(someone.getId());

        verify(users, times(3)).findById(someone.getId());
    }
}
