// test/…/getter/MinecraftPresenceGetterTest.java
package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.MinecraftService;
import fr.enimaloc.catapult.service.MinecraftTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MinecraftPresenceGetterTest {

    @Mock private MinecraftService minecraftService;
    @Mock private MinecraftTokenService tokenService;
    @Mock private MinecraftServiceAccountRepository accountRepository;
    @Mock private MinecraftFriendLinkRepository linkRepository;

    @InjectMocks private MinecraftPresenceGetter getter;

    private UserAccount user;
    private MinecraftServiceAccount bot1;
    private MinecraftFriendLink link;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        bot1 = new MinecraftServiceAccount();
        bot1.setId(UUID.randomUUID());
        bot1.setLabel("CatapultBot1");
        bot1.setEnabled(true);

        link = new MinecraftFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot1);
        link.setMinecraftProfileId("profile-1");
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);

        when(accountRepository.findByEnabledTrueOrderByFillOrderAsc()).thenReturn(List.of(bot1));
        when(linkRepository.existsByServiceAccountAndStatus(bot1, MinecraftFriendLink.Status.ACCEPTED))
                .thenReturn(true);
        when(linkRepository.findByUser(user)).thenReturn(Optional.of(link));
        when(tokenService.getToken(bot1)).thenReturn(Optional.of("mc-token"));
    }

    private MinecraftService.PresenceList presenceAgedOf(String profileId, MinecraftService.PresenceStatus status, Instant lastUpdated) {
        return new MinecraftService.PresenceList(new MinecraftService.PresenceList.Presence[]{
                new MinecraftService.PresenceList.Presence(profileId, "pmid", status, null, lastUpdated)
        });
    }

    private MinecraftService.PresenceList presenceOf(String profileId, MinecraftService.PresenceStatus status) {
        return new MinecraftService.PresenceList(new MinecraftService.PresenceList.Presence[]{
                new MinecraftService.PresenceList.Presence(profileId, "pmid", status, null, Instant.now())
        });
    }

    @Test
    void playingStatus_isDetectedAsMinecraft() {
        when(minecraftService.updatePresence(eq("mc-token"), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(presenceOf("profile-1", MinecraftService.PresenceStatus.PLAYING_SERVER));

        getter.prefetchBatch().join();
        var detected = getter.getCurrentGame(user);

        assertThat(detected).isPresent();
        assertThat(detected.get().getSourceId()).isEqualTo("minecraft");
        assertThat(detected.get().getSourceName()).isEqualTo("Minecraft");
    }

    @Test
    void onlineStatus_countsAsPlaying() {
        when(minecraftService.updatePresence(eq("mc-token"), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(presenceOf("profile-1", MinecraftService.PresenceStatus.ONLINE));

        getter.prefetchBatch().join();

        assertThat(getter.getCurrentGame(user)).isPresent();
    }

    @Test
    void offlineStatus_isNotDetected() {
        when(minecraftService.updatePresence(eq("mc-token"), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(presenceOf("profile-1", MinecraftService.PresenceStatus.OFFLINE));

        getter.prefetchBatch().join();

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void userWithoutAcceptedLink_isNotDetected() {
        link.setStatus(MinecraftFriendLink.Status.PENDING);
        when(minecraftService.updatePresence(eq("mc-token"), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(presenceOf("profile-1", MinecraftService.PresenceStatus.PLAYING_SERVER));

        getter.prefetchBatch().join();

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void accountWithoutAcceptedLinks_isNotPolled() {
        when(linkRepository.existsByServiceAccountAndStatus(bot1, MinecraftFriendLink.Status.ACCEPTED))
                .thenReturn(false);

        getter.prefetchBatch().join();

        verify(minecraftService, never()).updatePresence(anyString(), any(MinecraftService.PresenceStatus.class));
    }

    @Test
    void presenceFailure_yieldsEmptyWithoutThrowing() {
        when(minecraftService.updatePresence(anyString(), any(MinecraftService.PresenceStatus.class)))
                .thenThrow(new RuntimeException("api down"));

        getter.prefetchBatch().join();

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void profileIdFormatMismatch_stillDetected() {
        // lien stocké avec tirets, presence sans tirets : normalisation des deux côtés
        link.setMinecraftProfileId("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        when(minecraftService.updatePresence(eq("mc-token"), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(presenceOf("069a79f444e94726a5befca90e38aaf5", MinecraftService.PresenceStatus.PLAYING_SERVER));

        getter.prefetchBatch().join();

        assertThat(getter.getCurrentGame(user)).isPresent();
    }

    @Test
    void stalePresence_isNotDetected() {
        // jeu fermé sans publier OFFLINE : l'API renvoie l'ancien statut, lastUpdated ne bouge plus
        when(minecraftService.updatePresence(eq("mc-token"), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(presenceAgedOf("profile-1", MinecraftService.PresenceStatus.PLAYING_SERVER,
                        Instant.now().minusSeconds(600)));

        getter.prefetchBatch().join();

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void clearCycleCache_emptiesDetection() {
        when(minecraftService.updatePresence(eq("mc-token"), any(MinecraftService.PresenceStatus.class)))
                .thenReturn(presenceOf("profile-1", MinecraftService.PresenceStatus.PLAYING_SERVER));

        getter.prefetchBatch().join();
        getter.clearCycleCache();

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }
}
