package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiUserResolverTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final ChannelAccessService access = mock(ChannelAccessService.class);
    private final ApiUserResolver resolver = new ApiUserResolver(users, access);

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
    void channel_unknownIsNotFound() {
        assertStatus(() -> resolver.channel("nope"), HttpStatus.NOT_FOUND);
    }

    @Test
    void accessibleChannel_requiresAccess() {
        UserAccount viewer = account("mod");
        UserAccount channel = account("streamer");
        when(users.findByTwitchUsername("streamer")).thenReturn(Optional.of(channel));

        when(access.canAccess(viewer, channel)).thenReturn(true);
        assertThat(resolver.accessibleChannel("streamer", viewer)).isSameAs(channel);

        when(access.canAccess(viewer, channel)).thenReturn(false);
        assertStatus(() -> resolver.accessibleChannel("streamer", viewer), HttpStatus.FORBIDDEN);
    }

    @Test
    void accessibleChannel_fromJwt() {
        UserAccount viewer = account("mod");
        UserAccount channel = account("streamer");
        when(users.findById(viewer.getId())).thenReturn(Optional.of(viewer));
        when(users.findByTwitchUsername("streamer")).thenReturn(Optional.of(channel));
        when(access.canAccess(viewer, channel)).thenReturn(true);

        assertThat(resolver.accessibleChannel("streamer", jwtFor(viewer))).isSameAs(channel);
    }

    @Test
    void ownChannel_onlyForItsOwner_withoutAskingTwitchForModerators() {
        UserAccount owner = account("streamer");
        UserAccount mod = account("mod");
        when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(users.findById(mod.getId())).thenReturn(Optional.of(mod));
        when(users.findByTwitchUsername("streamer")).thenReturn(Optional.of(owner));

        assertThat(resolver.ownChannel("streamer", jwtFor(owner))).isSameAs(owner);
        assertStatus(() -> resolver.ownChannel("streamer", jwtFor(mod)), HttpStatus.FORBIDDEN);
        assertStatus(() -> resolver.ownChannel("nope", jwtFor(owner)), HttpStatus.NOT_FOUND);
        verify(access, never()).canAccess(mod, owner);
    }
}
