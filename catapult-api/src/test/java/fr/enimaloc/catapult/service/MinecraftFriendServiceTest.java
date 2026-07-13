package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MinecraftFriendServiceTest {

    @Mock private MinecraftService minecraftService;
    @Mock private MinecraftTokenService tokenService;
    @Mock private MinecraftServiceAccountRepository accountRepository;
    @Mock private MinecraftFriendLinkRepository linkRepository;
    @Mock private MinecraftAccountLimitMarker limitMarker;
    @Mock private fr.enimaloc.catapult.repository.GetterConfigRepository getterConfigRepository;

    @InjectMocks private MinecraftFriendService service;

    private UserAccount user;
    private MinecraftServiceAccount bot1;
    private MinecraftServiceAccount bot2;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        bot1 = account("CatapultBot1", 0);
        bot2 = account("CatapultBot2", 1);

        when(accountRepository.findByEnabledTrueOrderByFillOrderAsc()).thenReturn(List.of(bot1, bot2));
        when(tokenService.getToken(any())).thenReturn(Optional.of("mc-token"));
        when(minecraftService.lookupProfile("jeb_"))
                .thenReturn(Optional.of(new MinecraftService.ProfileLookup("069a79f444e94726a5befca90e38aaf5", "jeb_")));
        when(linkRepository.findByUser(user)).thenReturn(Optional.empty());
        when(linkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(getterConfigRepository.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(any())).thenReturn(List.of());
    }

    private MinecraftServiceAccount account(String label, int order) {
        var a = new MinecraftServiceAccount();
        a.setId(UUID.randomUUID());
        a.setLabel(label);
        a.setFillOrder(order);
        a.setEnabled(true);
        return a;
    }

    @Test
    void enroll_createsPendingLinkOnFirstAccount() {
        var link = service.enroll(user, "jeb_");

        assertThat(link.getStatus()).isEqualTo(MinecraftFriendLink.Status.PENDING);
        assertThat(link.getServiceAccount()).isSameAs(bot1);
        assertThat(link.getMinecraftProfileId()).isEqualTo("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        verify(minecraftService).addFriend("mc-token", null, "069a79f4-44e9-4726-a5be-fca90e38aaf5");
    }

    @Test
    void enroll_unknownPlayer_throwsWithReason() {
        when(minecraftService.lookupProfile("nexistepas")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enroll(user, "nexistepas"))
                .isInstanceOf(MinecraftFriendService.MinecraftEnrollmentException.class)
                .extracting(e -> ((MinecraftFriendService.MinecraftEnrollmentException) e).getReason())
                .isEqualTo(MinecraftFriendService.MinecraftEnrollmentException.Reason.UNKNOWN_PLAYER);
    }

    @Test
    void enroll_limitReachedOnFirstAccount_marksFullAndFallsBackToSecond() {
        when(minecraftService.addFriend(eq("mc-token"), isNull(), anyString()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null))
                .thenReturn(null);

        var link = service.enroll(user, "jeb_");

        verify(limitMarker).markFull(bot1);
        assertThat(link.getServiceAccount()).isSameAs(bot2);
    }

    @Test
    void enroll_noAccountAvailable_throwsWithReason() {
        bot1.setFriendLimitReached(true);
        bot2.setFriendLimitReached(true);

        assertThatThrownBy(() -> service.enroll(user, "jeb_"))
                .isInstanceOf(MinecraftFriendService.MinecraftEnrollmentException.class)
                .extracting(e -> ((MinecraftFriendService.MinecraftEnrollmentException) e).getReason())
                .isEqualTo(MinecraftFriendService.MinecraftEnrollmentException.Reason.NO_CAPACITY);
    }

    @Test
    void enroll_existingLink_isReusedInPlace() {
        var existing = new MinecraftFriendLink();
        existing.setUser(user);
        existing.setServiceAccount(bot1);
        existing.setMinecraftProfileId("old-profile-id");
        when(linkRepository.findByUser(user)).thenReturn(Optional.of(existing));

        var result = service.enroll(user, "jeb_");

        verify(minecraftService).removeFriend("mc-token", null, "old-profile-id");
        verify(linkRepository, never()).delete(any());
        assertThat(result).isSameAs(existing);
        assertThat(result.getStatus()).isEqualTo(MinecraftFriendLink.Status.PENDING);
        assertThat(result.getAcceptedAt()).isNull();
        assertThat(result.getMinecraftProfileId()).isEqualTo("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    }

    @Test
    void unenroll_removesFriendAndLink() {
        var existing = new MinecraftFriendLink();
        existing.setUser(user);
        existing.setServiceAccount(bot1);
        existing.setMinecraftProfileId("profile-id");
        when(linkRepository.findByUser(user)).thenReturn(Optional.of(existing));

        service.unenroll(user);

        verify(minecraftService).removeFriend("mc-token", null, "profile-id");
        verify(linkRepository).delete(existing);
    }

    @Test
    void sync_pendingLinkPresentInFriends_becomesAccepted() {
        var link = pendingLink("069a79f4-44e9-4726-a5be-fca90e38aaf5", "jeb_");
        when(linkRepository.findByServiceAccount(bot1)).thenReturn(List.of(link));
        when(linkRepository.findByServiceAccount(bot2)).thenReturn(List.of());
        when(minecraftService.getFriends("mc-token")).thenReturn(friendsListWith(
                new MinecraftService.FriendsList.Friend("069a79f4-44e9-4726-a5be-fca90e38aaf5", "jeb_")));

        service.syncFriendLinks();

        assertThat(link.getStatus()).isEqualTo(MinecraftFriendLink.Status.ACCEPTED);
        assertThat(link.getAcceptedAt()).isNotNull();
        verify(linkRepository).save(link);
    }

    @Test
    void sync_acceptedLinkAbsentFromFriends_becomesRemoved() {
        var link = pendingLink("069a79f4-44e9-4726-a5be-fca90e38aaf5", "jeb_");
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        when(linkRepository.findByServiceAccount(bot1)).thenReturn(List.of(link));
        when(linkRepository.findByServiceAccount(bot2)).thenReturn(List.of());
        when(minecraftService.getFriends("mc-token")).thenReturn(friendsListWith());

        service.syncFriendLinks();

        assertThat(link.getStatus()).isEqualTo(MinecraftFriendLink.Status.REMOVED);
        verify(linkRepository).save(link);
    }

    @Test
    void sync_friendRenamed_updatesStoredName() {
        var link = pendingLink("069a79f4-44e9-4726-a5be-fca90e38aaf5", "ancien_pseudo");
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        when(linkRepository.findByServiceAccount(bot1)).thenReturn(List.of(link));
        when(linkRepository.findByServiceAccount(bot2)).thenReturn(List.of());
        when(minecraftService.getFriends("mc-token")).thenReturn(friendsListWith(
                new MinecraftService.FriendsList.Friend("069a79f4-44e9-4726-a5be-fca90e38aaf5", "nouveau_pseudo")));

        service.syncFriendLinks();

        assertThat(link.getMinecraftName()).isEqualTo("nouveau_pseudo");
    }

    @Test
    void sync_accountWithoutToken_isSkippedWithoutTouchingLinks() {
        var link = pendingLink("069a79f4-44e9-4726-a5be-fca90e38aaf5", "jeb_");
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        when(linkRepository.findByServiceAccount(bot1)).thenReturn(List.of(link));
        when(linkRepository.findByServiceAccount(bot2)).thenReturn(List.of());
        when(tokenService.getToken(bot1)).thenReturn(Optional.empty());

        service.syncFriendLinks();

        assertThat(link.getStatus()).isEqualTo(MinecraftFriendLink.Status.ACCEPTED);
        verify(linkRepository, never()).save(any());
    }

    @Test
    void sync_getFriendsFailure_leavesLinksUntouched() {
        var link = pendingLink("069a79f4-44e9-4726-a5be-fca90e38aaf5", "jeb_");
        when(linkRepository.findByServiceAccount(bot1)).thenReturn(List.of(link));
        when(linkRepository.findByServiceAccount(bot2)).thenReturn(List.of());
        when(minecraftService.getFriends("mc-token")).thenThrow(new RuntimeException("api down"));

        service.syncFriendLinks();

        assertThat(link.getStatus()).isEqualTo(MinecraftFriendLink.Status.PENDING);
        verify(linkRepository, never()).save(any());
    }

    @Test
    void enroll_enablesMinecraftGetterConfig() {
        service.enroll(user, "jeb_");

        verify(getterConfigRepository).save(org.mockito.ArgumentMatchers.argThat(config ->
                config.getProvider() == fr.enimaloc.catapult.domain.GetterConfig.Provider.MINECRAFT
                        && config.isEnabled()
                        && config.getPriority() == 1));
    }

    @Test
    void unenroll_disablesMinecraftGetterConfig() {
        var config = new fr.enimaloc.catapult.domain.GetterConfig();
        config.setProvider(fr.enimaloc.catapult.domain.GetterConfig.Provider.MINECRAFT);
        config.setEnabled(true);
        when(getterConfigRepository.findByUserAndProvider(user, fr.enimaloc.catapult.domain.GetterConfig.Provider.MINECRAFT))
                .thenReturn(Optional.of(config));

        service.unenroll(user);

        assertThat(config.isEnabled()).isFalse();
        verify(getterConfigRepository).save(config);
    }

    @Test
    void sync_matchesProfileIdsRegardlessOfDashes() {
        // lien stocké avec tirets (lookup), friends list sans tirets : doit matcher quand même
        var link = pendingLink("069a79f4-44e9-4726-a5be-fca90e38aaf5", "jeb_");
        when(linkRepository.findByServiceAccount(bot1)).thenReturn(List.of(link));
        when(linkRepository.findByServiceAccount(bot2)).thenReturn(List.of());
        when(minecraftService.getFriends("mc-token")).thenReturn(friendsListWith(
                new MinecraftService.FriendsList.Friend("069a79f444e94726a5befca90e38aaf5", "jeb_")));

        service.syncFriendLinks();

        assertThat(link.getStatus()).isEqualTo(MinecraftFriendLink.Status.ACCEPTED);
    }

    private MinecraftFriendLink pendingLink(String profileId, String name) {
        var link = new MinecraftFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot1);
        link.setMinecraftProfileId(profileId);
        link.setMinecraftName(name);
        link.setStatus(MinecraftFriendLink.Status.PENDING);
        return link;
    }

    private MinecraftService.FriendsList friendsListWith(MinecraftService.FriendsList.Friend... friends) {
        return new MinecraftService.FriendsList(friends,
                new MinecraftService.FriendsList.Friend[0],
                new MinecraftService.FriendsList.Friend[0],
                friends.length == 0);
    }
}
