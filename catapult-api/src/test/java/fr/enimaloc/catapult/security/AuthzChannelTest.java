package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthzChannelTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final ChannelAccessService channelAccessService = mock(ChannelAccessService.class);
    private final ApiUserResolver users = new ApiUserResolver(accounts);
    private final Authz authz = new Authz(users, channelAccessService);

    private UserAccount owner;
    private UserAccount moderator;
    private UserAccount member;

    @BeforeEach
    void setUp() {
        owner = registered("streamer", "1");
        moderator = registered("mod", "2");
        member = registered("someone", "3");
        when(accounts.findByTwitchUsername("streamer")).thenReturn(Optional.of(owner));
        when(accounts.findByTwitchUsername("nobody-here")).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UserAccount registered(String username, String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        account.setTwitchId(twitchId);
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    private static void signIn(Authentication auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void signInAs(UUID accountId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(accountId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        signIn(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void canAccessChannel_unknownViewerIs401_beforeUnknownChannel() {
        signInAs(UUID.randomUUID());

        assertStatus(() -> authz.canAccessChannel("nobody-here"), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authz.ownsChannel("nobody-here"), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void canAccessChannel_noJwtIs401() {
        assertStatus(() -> authz.canAccessChannel("streamer"), HttpStatus.UNAUTHORIZED);

        signIn(new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertStatus(() -> authz.canAccessChannel("streamer"), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authz.ownsChannel("streamer"), HttpStatus.UNAUTHORIZED);

        signIn(UsernamePasswordAuthenticationToken.authenticated(owner.getId(), null,
                AuthorityUtils.createAuthorityList(PersonalTokenAuthenticationFilter.AUTHORITY)));
        assertStatus(() -> authz.canAccessChannel("streamer"), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authz.ownsChannel("streamer"), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void canAccessChannel_unknownChannelIs404() {
        signInAs(owner.getId());

        assertStatus(() -> authz.canAccessChannel("nobody-here"), HttpStatus.NOT_FOUND);
        assertStatus(() -> authz.ownsChannel("nobody-here"), HttpStatus.NOT_FOUND);
    }

    @Test
    void canAccessChannel_ownerAndModeratorTrue_otherFalse() {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);
        when(channelAccessService.canAccess(moderator, owner)).thenReturn(true);
        when(channelAccessService.canAccess(member, owner)).thenReturn(false);

        signInAs(owner.getId());
        assertThat(authz.canAccessChannel("streamer")).isTrue();
        signInAs(moderator.getId());
        assertThat(authz.canAccessChannel("streamer")).isTrue();
        signInAs(member.getId());
        assertThat(authz.canAccessChannel("streamer")).isFalse();

        assertThat(authz.canAccess(moderator, owner)).isTrue();
        assertThat(authz.canAccess(member, owner)).isFalse();
    }

    @Test
    void canAccessChannel_twitchFails_withoutCache_isDenied() {
        TwitchService twitch = mock(TwitchService.class);
        when(twitch.getModeratedChannelIds(any())).thenThrow(new IllegalStateException("Twitch is down"));
        ChannelAccessService real = new ChannelAccessService(twitch, accounts);
        Authz withRealAccess = new Authz(new ApiUserResolver(accounts), real);

        signInAs(moderator.getId());
        assertThat(withRealAccess.canAccessChannel("streamer")).isFalse();
    }

    @Test
    void ownsChannel_neverAsksTwitch() {
        signInAs(owner.getId());
        assertThat(authz.ownsChannel("streamer")).isTrue();
        signInAs(moderator.getId());
        assertThat(authz.ownsChannel("streamer")).isFalse();

        verifyNoInteractions(channelAccessService);
    }

    @Test
    void canAccessChannelById_unknownViewerIs401_beforeUnknownChannel() {
        signInAs(UUID.randomUUID());
        UUID unknown = UUID.randomUUID();

        assertStatus(() -> authz.canAccessChannel(unknown), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authz.ownsChannel(unknown), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authz.isSelf(unknown), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void canAccessChannelById_unknownChannelIs404() {
        signInAs(owner.getId());
        UUID unknown = UUID.randomUUID();

        assertStatus(() -> authz.canAccessChannel(unknown), HttpStatus.NOT_FOUND);
        assertStatus(() -> authz.ownsChannel(unknown), HttpStatus.NOT_FOUND);
        assertStatus(() -> authz.isSelf(unknown), HttpStatus.NOT_FOUND);
    }

    @Test
    void canAccessChannelById_moderatorIsTrue() {
        when(channelAccessService.canAccess(moderator, owner)).thenReturn(true);
        when(channelAccessService.canAccess(member, owner)).thenReturn(false);

        signInAs(moderator.getId());
        assertThat(authz.canAccessChannel(owner.getId())).isTrue();
        signInAs(member.getId());
        assertThat(authz.canAccessChannel(owner.getId())).isFalse();
    }

    @Test
    void ownsChannelById_moderatorIsFalse() {
        signInAs(moderator.getId());
        assertThat(authz.ownsChannel(owner.getId())).isFalse();
        signInAs(owner.getId());
        assertThat(authz.ownsChannel(owner.getId())).isTrue();

        verifyNoInteractions(channelAccessService);
    }

    @Test
    void isSelf_sameAccountIsTrue() {
        signInAs(member.getId());
        assertThat(authz.isSelf(member.getId())).isTrue();
    }

    @Test
    void isSelf_otherAccountIsFalse() {
        signInAs(moderator.getId());
        assertThat(authz.isSelf(owner.getId())).isFalse();

        verifyNoInteractions(channelAccessService);
    }

    @Test
    void isSelf_noJwtIs401() {
        assertStatus(() -> authz.isSelf(member.getId()), HttpStatus.UNAUTHORIZED);

        signIn(UsernamePasswordAuthenticationToken.authenticated(member.getId(), null,
                AuthorityUtils.createAuthorityList(PersonalTokenAuthenticationFilter.AUTHORITY)));
        assertStatus(() -> authz.isSelf(member.getId()), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authz.canAccessChannel(owner.getId()), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authz.ownsChannel(owner.getId()), HttpStatus.UNAUTHORIZED);
    }
}
